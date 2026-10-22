package com.xrdroid

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

// Draws each virtual screen as a section of a cylinder around the wearer, so wide layouts wrap around
// rather than stretch at the edges, and turns the view with their head
class SceneRenderer(
    private val imu: VitureImu,
    private val cursorBitmap: Bitmap,
    layout: ScreenLayout,
    scale: Float,
    private val onScreensReady: (List<Surface>) -> Unit
) : GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "XRdroid"
        // Radius of the cylinder the screens sit on, in world units
        private const val SCREEN_DISTANCE = 2f
        private const val BORDER_DEGREES = 0.4f
        // Cursor height in screen pixels
        private const val CURSOR_SIZE_PX = 36f
        // How far past the midpoint the gaze must go before the trackpad switches screens
        private const val SWITCH_HYSTERESIS_DEGREES = 4f
        // Mesh vertices: x, y, z, u, v
        private const val MESH_STRIDE = 5 * 4

        private const val TEXTURE_VERTEX_SHADER = """
            uniform mat4 uMvp;
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMvp * aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """
        private const val SCREEN_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
        private const val CURSOR_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
        private const val COLOR_VERTEX_SHADER = """
            uniform mat4 uMvp;
            attribute vec4 aPosition;
            void main() {
                gl_Position = uMvp * aPosition;
            }
        """
        private const val COLOR_FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uColor;
            void main() {
                gl_FragColor = uColor;
            }
        """

        // Flat unit quad for the cursor, as a triangle strip: x, y, u, v
        private val QUAD = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f
        )
        // Bitmaps are uploaded top row first, so flip v for the cursor
        private val FLIP_V = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)

        // A screen as a triangle strip of bottom/top vertex pairs in world space, grown by margin on every
        // side (for the border). Curved screens follow the cylinder; flat ones face the wearer from their yaw.
        private fun screenStrip(curved: Boolean, yawDegrees: Float, widthDegrees: Float, width: Float, height: Float, margin: Float): FloatBuffer {
            val segments = if (curved) max(8, (widthDegrees / 2f).toInt()) else 1
            val yaw = Math.toRadians(yawDegrees.toDouble())
            val marginDegrees = Math.toDegrees((margin / SCREEN_DISTANCE).toDouble()).toFloat()
            val data = FloatArray((segments + 1) * 2 * 5)
            var i = 0
            for (segment in 0..segments) {
                val u = segment.toFloat() / segments
                // Left edge first: positive yaw is to the left
                val x: Float
                val z: Float
                if (curved) {
                    val span = widthDegrees + 2 * marginDegrees
                    val angle = Math.toRadians((yawDegrees + span / 2f - u * span).toDouble())
                    x = (-SCREEN_DISTANCE * sin(angle)).toFloat()
                    z = (-SCREEN_DISTANCE * cos(angle)).toFloat()
                } else {
                    val across = (u - 0.5f) * (width + 2 * margin)
                    x = (across * cos(yaw) - SCREEN_DISTANCE * sin(yaw)).toFloat()
                    z = (-across * sin(yaw) - SCREEN_DISTANCE * cos(yaw)).toFloat()
                }
                for ((y, v) in listOf(-height / 2f - margin to 0f, height / 2f + margin to 1f)) {
                    data[i++] = x; data[i++] = y; data[i++] = z; data[i++] = u; data[i++] = v
                }
            }
            return ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(data)
        }
    }

    class Screen(val spec: ScreenSpec) {
        // Where the screen sits at the current size: bringing screens closer makes each one wider and
        // spreads them apart, so neighbours never overlap
        var yawDegrees = 0f
        var widthDegrees = 0f
        // Width along the surface (an arc for curved screens, the plane width for flat ones); the height
        // keeps the screen's pixel aspect
        var width = 0f
        var height = 0f
        lateinit var mesh: FloatBuffer
        lateinit var borderMesh: FloatBuffer
        var vertexCount = 0
        var borderVertexCount = 0

        fun place(scale: Float) {
            yawDegrees = spec.yawDegrees * scale
            widthDegrees = spec.widthDegrees * scale
            val halfAngle = Math.toRadians(widthDegrees / 2.0)
            width = (if (spec.curved) SCREEN_DISTANCE * 2 * halfAngle else SCREEN_DISTANCE * 2 * tan(halfAngle)).toFloat()
            height = width * spec.heightPx / spec.widthPx
            // Built straight ahead; turned to yawDegrees when drawn
            mesh = screenStrip(spec.curved, 0f, widthDegrees, width, height, 0f)
            borderMesh = screenStrip(spec.curved, 0f, widthDegrees, width, height, SCREEN_DISTANCE * Math.toRadians(BORDER_DEGREES.toDouble()).toFloat())
            vertexCount = mesh.capacity() / 5
            borderVertexCount = borderMesh.capacity() / 5
        }

        var textureId = 0
        lateinit var surfaceTexture: SurfaceTexture
        lateinit var surface: Surface
        val frameAvailable = AtomicBoolean(false)
        val textureMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    }

    val screens = layout.screens.map { Screen(it).apply { place(scale) } }

    // Rebuilds the screens' geometry at a new size; call on the GL thread
    fun setScale(scale: Float) {
        screens.forEach { it.place(scale) }
    }

    // Set to recentre on where the wearer is looking, up or down included
    @Volatile var recenterRequested = false
    // Must match the glasses' optics, or world-locked screens drift as the head turns
    @Volatile var verticalFovDegrees = 25f
    // How far past the sample time to predict the head pose, covering render and scan-out latency
    @Volatile var lookaheadSeconds = 0.03f

    private val quadBuffer = ByteBuffer.allocateDirect(QUAD.size * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().put(QUAD).also { it.position(0) }
    private var screenProgram = 0
    private var cursorProgram = 0
    private var colorProgram = 0
    private var cursorTextureId = 0
    // Where the wearer was looking when they last recentred; the screens are placed around it
    private var viewReference = Quat.IDENTITY
    // How far above (positive) or below the horizon the screens sit, from the last recentre
    private var pitchReferenceDegrees = 0f
    // The first sample sets the heading only: at launch the wearer is usually looking down at the phone
    private var centred = false
    private var aspect = 16f / 9f
    private val view = FloatArray(16)
    private val gaze = FloatArray(16)
    private val projection = FloatArray(16)
    private val viewProjection = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)

    private var statsStartNanos = 0L
    private var statsFrames = 0
    private var statsSamples = 0L

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        screenProgram = buildProgram(TEXTURE_VERTEX_SHADER, SCREEN_FRAGMENT_SHADER)
        cursorProgram = buildProgram(TEXTURE_VERTEX_SHADER, CURSOR_FRAGMENT_SHADER)
        colorProgram = buildProgram(COLOR_VERTEX_SHADER, COLOR_FRAGMENT_SHADER)
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        // The EGL context is preserved across pauses, so textures are only set up once
        if (screens.first().textureId != 0) return
        val textures = IntArray(screens.size + 1)
        GLES20.glGenTextures(textures.size, textures, 0)
        screens.forEachIndexed { i, screen ->
            screen.textureId = textures[i]
            bindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, screen.textureId)
            screen.surfaceTexture = SurfaceTexture(screen.textureId).apply {
                setDefaultBufferSize(screen.spec.widthPx, screen.spec.heightPx)
                setOnFrameAvailableListener { screen.frameAvailable.set(true) }
            }
            screen.surface = Surface(screen.surfaceTexture)
        }
        cursorTextureId = textures.last()
        bindTexture(GLES20.GL_TEXTURE_2D, cursorTextureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, cursorBitmap, 0)
        onScreensReady(screens.map { it.surface })
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        aspect = width.toFloat() / height
    }

    override fun onDrawFrame(gl: GL10?) {
        for (screen in screens) {
            if (screen.frameAvailable.getAndSet(false)) {
                screen.surfaceTexture.updateTexImage()
                screen.surfaceTexture.getTransformMatrix(screen.textureMatrix)
            }
        }

        val sample = imu.latest
        val head = if (sample == null) Quat.IDENTITY else {
            if (recenterRequested || !centred) {
                val (yaw, pitch) = Quat.lookAngles(sample.orientation)
                viewReference = Quat.aboutY(yaw)
                pitchReferenceDegrees = if (centred) Math.toDegrees(pitch.toDouble()).toFloat() else 0f
                centred = true
                recenterRequested = false
            }
            Quat.mul(Quat.conj(viewReference), sample.orientation)
        }
        updateActiveScreen(head)
        // Predict from when the sample arrived, so every frame lands at the same point in time
        // whatever the sample's age
        val age = if (sample == null) 0f else ((System.nanoTime() - sample.nanos) / 1e9f).coerceIn(0f, 0.05f)
        val predicted = Quat.integrate(head, imu.angularRate, lookaheadSeconds + age)
        // The view is the inverse of the head rotation; the meshes are already in world space
        Quat.toMatrix(Quat.conj(predicted), view)
        Matrix.perspectiveM(projection, 0, verticalFovDegrees, aspect, 0.1f, 100f)
        Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val inputScreens = Session.screens
        screens.forEachIndexed { i, screen ->
            // A faint border so an empty screen can still be found (black is see-through on the glasses)
            setScreenModel(screen)
            drawColorMesh(screen.borderMesh, screen.borderVertexCount, 0.2f)
            drawScreenMesh(screen)
            val input = inputScreens.getOrNull(i)
            if (input != null && input === Session.activeScreen) drawCursor(screen, input)
        }
        logStats()
    }

    // The screen nearest the centre of view gets the trackpad. Hysteresis stops it flickering when
    // looking at the gap, and it never changes while a finger is on the trackpad.
    private fun updateActiveScreen(head: FloatArray) {
        val inputScreens = Session.screens
        if (inputScreens.size != screens.size || Session.touchActive) return
        // Forward is -Z, so the gaze direction is minus the rotation's third column
        Quat.toMatrix(head, gaze)
        // Measured around the arrangement's own vertical, which tips with it when recentred looking up or down
        val pitch = Math.toRadians(pitchReferenceDegrees.toDouble())
        val towardZ = gaze[10] * cos(pitch) - gaze[9] * sin(pitch)
        val yaw = Math.toDegrees(atan2(gaze[8].toDouble(), towardZ)).toFloat()
        val current = inputScreens.indexOfFirst { it === Session.activeScreen }
        var best = screens.indices.minByOrNull { abs(yaw - screens[it].yawDegrees) } ?: return
        if (current >= 0 && best != current &&
            abs(yaw - screens[current].yawDegrees) < abs(yaw - screens[best].yawDegrees) + SWITCH_HYSTERESIS_DEGREES
        ) {
            best = current
        }
        Session.activeScreen = inputScreens[best]
    }

    // Turns a screen to its place in the arrangement, then tips the whole arrangement up or down as one
    // to face the wearer at the recentred height, so screens side by side stay parallel at any height
    private fun placeScreen(screen: Screen) {
        Matrix.setIdentityM(model, 0)
        Matrix.rotateM(model, 0, pitchReferenceDegrees, 1f, 0f, 0f)
        Matrix.rotateM(model, 0, screen.yawDegrees, 0f, 1f, 0f)
    }

    private fun setScreenModel(screen: Screen) {
        placeScreen(screen)
        Matrix.multiplyMM(mvp, 0, viewProjection, 0, model, 0)
    }

    private fun drawColorMesh(mesh: FloatBuffer, vertexCount: Int, grey: Float) {
        GLES20.glUseProgram(colorProgram)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(colorProgram, "uMvp"), 1, false, mvp, 0)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(colorProgram, "uColor"), grey, grey, grey, 1f)
        bindMesh(colorProgram, mesh, withTexCoords = false)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, vertexCount)
    }

    private fun drawScreenMesh(screen: Screen) {
        GLES20.glUseProgram(screenProgram)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(screenProgram, "uMvp"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(screenProgram, "uTexMatrix"), 1, false, screen.textureMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, screen.textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(screenProgram, "uTexture"), 0)
        bindMesh(screenProgram, screen.mesh, withTexCoords = true)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, screen.vertexCount)
    }

    // A small flat quad on the screen at the cursor position, slightly in front of the surface; the
    // cursor's tip is the top-left corner of its image
    private fun drawCursor(screen: Screen, input: Session.Screen) {
        val u = input.cursorX / input.width
        val y = screen.height / 2f - input.cursorY / input.height * screen.height
        val half = CURSOR_SIZE_PX * screen.height / input.height / 2f
        placeScreen(screen)
        if (screen.spec.curved) {
            Matrix.rotateM(model, 0, screen.widthDegrees / 2f - u * screen.widthDegrees, 0f, 1f, 0f)
            Matrix.translateM(model, 0, half, y - half, -SCREEN_DISTANCE * 0.995f)
        } else {
            Matrix.translateM(model, 0, (u - 0.5f) * screen.width + half, y - half, -SCREEN_DISTANCE * 0.995f)
        }
        Matrix.scaleM(model, 0, half, half, 1f)
        Matrix.multiplyMM(mvp, 0, viewProjection, 0, model, 0)

        GLES20.glEnable(GLES20.GL_BLEND)
        // Android bitmaps are premultiplied
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(cursorProgram)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(cursorProgram, "uMvp"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(cursorProgram, "uTexMatrix"), 1, false, FLIP_V, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cursorTextureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(cursorProgram, "uTexture"), 0)
        val position = GLES20.glGetAttribLocation(cursorProgram, "aPosition")
        val texCoord = GLES20.glGetAttribLocation(cursorProgram, "aTexCoord")
        quadBuffer.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)
        GLES20.glEnableVertexAttribArray(position)
        quadBuffer.position(2)
        GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 16, quadBuffer)
        GLES20.glEnableVertexAttribArray(texCoord)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    private fun bindMesh(program: Int, mesh: FloatBuffer, withTexCoords: Boolean) {
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        mesh.position(0)
        GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, MESH_STRIDE, mesh)
        GLES20.glEnableVertexAttribArray(position)
        if (withTexCoords) {
            val texCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
            mesh.position(3)
            GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, MESH_STRIDE, mesh)
            GLES20.glEnableVertexAttribArray(texCoord)
        }
    }

    private fun bindTexture(target: Int, textureId: Int) {
        GLES20.glBindTexture(target, textureId)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    // Logs render rate and head-tracking rate every two seconds
    private fun logStats() {
        val now = System.nanoTime()
        if (statsStartNanos == 0L) {
            statsStartNanos = now
            statsSamples = imu.sampleCount
        }
        statsFrames++
        val elapsed = (now - statsStartNanos) / 1e9f
        if (elapsed >= 2f) {
            val samples = imu.sampleCount
            Log.i(TAG, "render %.1f fps, head tracking %.1f Hz".format(statsFrames / elapsed, (samples - statsSamples) / elapsed))
            statsStartNanos = now
            statsFrames = 0
            statsSamples = samples
        }
    }

    private fun buildProgram(vertexSource: String, fragmentSource: String): Int {
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, compileShader(GLES20.GL_VERTEX_SHADER, vertexSource))
        GLES20.glAttachShader(program, compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource))
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) Log.e(TAG, "Program link failed: ${GLES20.glGetProgramInfoLog(program)}")
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source.trimIndent())
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) Log.e(TAG, "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
        return shader
    }
}
