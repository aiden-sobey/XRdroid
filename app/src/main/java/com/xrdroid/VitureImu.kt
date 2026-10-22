package com.xrdroid

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Reads head orientation from VITURE Gen2 glasses (e.g. Pro 2) over USB HID, using the protocol
// documented at github.com/elasticjava/viture-v2 (MIT). No vendor SDK: open the interface, send one
// command, and the glasses stream fused orientation quaternions.
class VitureImu(private val context: Context) {

    companion object {
        private const val TAG = "XRdroid"
        const val VITURE_VENDOR_ID = 0x35CA

        // Frame: u16 preamble 0x0010, u16 message id, u16 payload length, u16 byte-sum checksum, payload
        private const val PREAMBLE = 0x0010
        private const val MSG_IMU_CONTROL = 0x0301
        private const val MSG_IMU_CONTROL_ACK = 0x2301
        private const val MSG_POSE = 0x7308
        private const val STREAM_POSE: Byte = 0x01
        // 0 = 60, 1 = 90, 2 = 120, 3 = 240 Hz; 240 is the Pro 2's fastest pose rate
        private const val RATE_240_HZ: Byte = 0x03
        // The glasses deliver about 200 Hz when asked for 240, so the interval is measured rather than assumed
        private const val INTERVAL_AVERAGING = 0.01f

        private const val READ_TIMEOUT_MS = 50
        private const val START_ATTEMPTS = 5
        // About two seconds of silence before the stream counts as dead
        private const val MAX_CONSECUTIVE_FAILURES = 40
        // Light low-pass on the differentiated rate; prediction turns rate noise into a restless image
        private const val RATE_SMOOTHING = 0.35f
        // The glasses' display comes up before their motion sensor does, so keep looking for it
        private const val RETRY_MS = 500L
    }

    // A head orientation [w, x, y, z] and when it arrived (System.nanoTime)
    class Sample(val orientation: FloatArray, val nanos: Long)

    // Latest sample, or null before the first one
    @Volatile var latest: Sample? = null
        private set
    // Smoothed body-frame angular rate in rad/s, for predicting where the head will be
    @Volatile var angularRate = floatArrayOf(0f, 0f, 0f)
        private set
    @Volatile var sampleCount = 0L
        private set

    @Volatile private var running = false
    private var readerThread: Thread? = null
    private val handler = Handler(Looper.getMainLooper())
    private var wanted = false
    private var onStatus: (String) -> Unit = {}
    @Volatile private var lastStatus: String? = null

    // Keeps trying until the stream starts, and starts it again if it stops while still wanted
    private val connect = object : Runnable {
        override fun run() {
            if (wanted && !running && !tryOpen()) handler.postDelayed(this, RETRY_MS)
        }
    }

    fun start(onStatus: (String) -> Unit) {
        this.onStatus = onStatus
        wanted = true
        handler.post(connect)
    }

    fun stop() {
        wanted = false
        handler.removeCallbacks(connect)
        running = false
        readerThread?.join(500)
        readerThread = null
    }

    // Repeats are dropped, since a missing sensor is reported on every retry
    private fun report(status: String) {
        if (status == lastStatus) return
        lastStatus = status
        onStatus(status)
    }

    private fun tryOpen(): Boolean {
        val usbManager = context.getSystemService(UsbManager::class.java)
        val device = usbManager.deviceList.values.firstOrNull { it.vendorId == VITURE_VENDOR_ID }
        if (device == null) {
            report("waiting for the glasses' motion sensor on USB")
            return false
        }
        // Android forgets access whenever the glasses are unplugged. The helper grants it without a prompt,
        // which from here would appear in the glasses; otherwise it's granted from the phone screen.
        if (!usbManager.hasPermission(device) && !(Helper.allowUsb(device) && usbManager.hasPermission(device))) {
            report("USB access to the glasses hasn't been allowed yet")
            return false
        }
        return open(usbManager, device)
    }

    private fun open(usbManager: UsbManager, device: UsbDevice): Boolean {
        val usbInterface = (0 until device.interfaceCount).map { device.getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_HID } ?: device.getInterface(0)
        val endpoints = (0 until usbInterface.endpointCount).map { usbInterface.getEndpoint(it) }
        val input = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_IN }
        val output = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
        val connection = usbManager.openDevice(device)
        // Forcing the claim detaches the kernel HID driver from the interface
        if (input == null || output == null || connection == null || !connection.claimInterface(usbInterface, true)) {
            connection?.close()
            report("could not open the glasses' USB interface (is SpaceWalker using it?)")
            return false
        }

        running = true
        readerThread = Thread({
            readLoop(connection, usbInterface, input, output)
            running = false
            handler.postDelayed(connect, RETRY_MS)
        }, "VitureImu").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return true
    }

    private fun readLoop(
        connection: UsbDeviceConnection,
        usbInterface: UsbInterface,
        input: UsbEndpoint,
        output: UsbEndpoint
    ) {
        val buffer = ByteArray(64)
        if (!startStream(connection, input, output, buffer)) {
            report("glasses did not start the head-tracking stream")
        } else {
            report("head tracking started")
            var previous: FloatArray? = null
            var previousNanos = 0L
            var sampleInterval = 1f / 240f
            var failures = 0
            while (running) {
                val length = connection.bulkTransfer(input, buffer, buffer.size, READ_TIMEOUT_MS)
                if (length <= 0) {
                    if (++failures > MAX_CONSECUTIVE_FAILURES) {
                        report("head tracking stopped responding")
                        break
                    }
                    continue
                }
                failures = 0
                val q = parsePose(buffer, length) ?: continue
                val now = System.nanoTime()
                previous?.let { before ->
                    val arrivalGap = (now - previousNanos) / 1e9f
                    // Samples are taken at a fixed rate but arrive over USB with jitter, so use the
                    // long-run average interval (times any samples missed) rather than the arrival gap
                    if (arrivalGap < 0.1f) {
                        val intervals = Math.round(arrivalGap / sampleInterval).coerceAtLeast(1)
                        if (intervals == 1) sampleInterval += (arrivalGap - sampleInterval) * INTERVAL_AVERAGING
                        val measured = Quat.angularRate(before, q, intervals * sampleInterval)
                        val last = angularRate
                        angularRate = FloatArray(3) { last[it] + (measured[it] - last[it]) * RATE_SMOOTHING }
                    }
                }
                previous = q
                previousNanos = now
                latest = Sample(q, now)
                sampleCount++
            }
            val stopCommand = frame(MSG_IMU_CONTROL, byteArrayOf(0, 0))
            connection.bulkTransfer(output, stopCommand, stopCommand.size, 100)
        }
        connection.releaseInterface(usbInterface)
        connection.close()
    }

    // The glasses sometimes miss the first command, so retry until an ACK or pose data arrives
    private fun startStream(connection: UsbDeviceConnection, input: UsbEndpoint, output: UsbEndpoint, buffer: ByteArray): Boolean {
        val command = frame(MSG_IMU_CONTROL, byteArrayOf(STREAM_POSE, RATE_240_HZ))
        repeat(START_ATTEMPTS) {
            if (connection.bulkTransfer(output, command, command.size, 100) != command.size) return@repeat
            repeat(10) {
                val length = connection.bulkTransfer(input, buffer, buffer.size, READ_TIMEOUT_MS)
                if (length >= 9) {
                    val messageId = (buffer[2].toInt() and 0xFF) or ((buffer[3].toInt() and 0xFF) shl 8)
                    if ((messageId == MSG_IMU_CONTROL_ACK && buffer[8].toInt() == 0) || messageId == MSG_POSE) return true
                }
            }
        }
        return false
    }

    private fun frame(messageId: Int, payload: ByteArray): ByteArray {
        val checksum = payload.sumOf { it.toInt() and 0xFF }
        return ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(PREAMBLE.toShort())
            .putShort(messageId.toShort())
            .putShort(payload.size.toShort())
            .putShort(checksum.toShort())
            .put(payload)
            .array()
    }

    // Pose payload: u32 unknown, u32 timestamp, then f32 qw, qx, qy, qz
    private fun parsePose(buffer: ByteArray, length: Int): FloatArray? {
        if (length < 32) return null
        val frame = ByteBuffer.wrap(buffer, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        if (frame.getShort(0).toInt() != PREAMBLE || (frame.getShort(2).toInt() and 0xFFFF) != MSG_POSE) return null
        val payloadLength = frame.getShort(4).toInt() and 0xFFFF
        if (payloadLength < 24 || 8 + payloadLength > length) return null
        var sum = 0
        for (i in 8 until 8 + payloadLength) sum += buffer[i].toInt() and 0xFF
        if ((sum and 0xFFFF) != (frame.getShort(6).toInt() and 0xFFFF)) return null
        return floatArrayOf(frame.getFloat(16), frame.getFloat(20), frame.getFloat(24), frame.getFloat(28))
    }
}
