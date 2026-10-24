package com.xrdroid

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
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
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

// Draws each virtual screen as a section of a cylinder around the wearer, so wide layouts wrap around
// rather than stretch at the edges, and turns the view with their head. A single screen can instead be
// fixed: always straight ahead, following the head.
class SceneRenderer(
    private val imu: VitureImu,
    layout: ScreenLayout,
    scale: Float,
    fixed: Boolean,
    private val onScreensReady: (List<Surface>) -> Unit
) : GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "XRdroid"
        // A fixed screen is shrunk if need be to fill this much of the glasses' view, so its edges show
        private const val FIXED_FILL = 0.92f
        private const val VIEW_HEIGHT_DEGREES = 25f
        private const val VIEW_ASPECT = 16f / 9f
        // Radius of the cylinder the screens sit on, in world units
        private const val SCREEN_DISTANCE = ScreenGeometry.DISTANCE
        // Kept clear around each screen when working out how far neighbours may tilt: its rim and the
        // brightest part of its glow
        private const val BORDER_DEGREES = 0.4f
        private val BORDER_MARGIN = SCREEN_DISTANCE * Math.toRadians(BORDER_DEGREES.toDouble()).toFloat()
        // How far the soft glow around each screen reaches
        private const val GLOW_DEGREES = 1.2f
        private val GLOW_MARGIN = SCREEN_DISTANCE * Math.toRadians(GLOW_DEGREES.toDouble()).toFloat()
        // The glasses can only add light, so a screen's edge is drawn as a thin rim of light with a glow
        // fading out from it, rather than a shadow. The screen with the trackpad gets a violet rim.
        private val RIM_COLOR = floatArrayOf(0.36f, 0.38f, 0.42f)
        private val ACTIVE_RIM_COLOR = floatArrayOf(0.42f, 0.37f, 0.72f)
        private const val GLOW_STRENGTH = 0.22f
        private const val GLOW_FALLOFF_PX = 14f
        // Span of the cursor's dot of light and the glow and shade around it, in screen pixels
        private const val DOT_SIZE_PX = 34f
        // How far past the edge of the screen with the trackpad the gaze must go before it switches screens
        private const val SWITCH_HYSTERESIS_DEGREES = 3f
        // Closest that neighbouring screens' borders may come once tilted towards the wearer
        private const val MIN_GAP_DEGREES = 1f
        // Mesh vertices: x, y, z, u, v
        private const val MESH_STRIDE = 5 * 4

        // vLocal is where the vertex sits across the mesh, 0 to 1 each way, before the texture transform
        private const val TEXTURE_VERTEX_SHADER = """
            uniform mat4 uMvp;
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vTexCoord;
            varying vec2 vLocal;
            void main() {
                gl_Position = uMvp * aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
                vLocal = aTexCoord.xy;
            }
        """
        // Signed distance from a point to a rectangle centred on the origin, in the screen's own pixels (about
        // the same size as the glasses' pixels): negative inside. Rounded outside the corners, so the rim and
        // glow curve around them.
        private const val BOX = """
            float box(vec2 p, vec2 halfSize) {
                vec2 q = abs(p) - halfSize;
                return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0);
            }
        """
        // The screen, with its edges anti-aliased over about a pixel; premultiplied
        private const val SCREEN_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            uniform samplerExternalOES uTexture;
            uniform vec2 uSize;
            varying vec2 vTexCoord;
            varying vec2 vLocal;
        """ + BOX + """
            void main() {
                float d = box((vLocal - 0.5) * uSize, uSize * 0.5);
                gl_FragColor = texture2D(uTexture, vTexCoord) * clamp(0.5 - d, 0.0, 1.0);
            }
        """
        // A thin rim of light just outside the screen's edge, a little brighter along the top as if lit from
        // above, and a glow fading out from it to nothing at the edge of the strip. Added to what's there.
        private const val FRAME_FRAGMENT_SHADER = """
            precision highp float;
            uniform vec2 uSize;
            uniform float uMargin;
            uniform vec3 uRim;
            uniform float uGlow;
            uniform float uFalloff;
            varying vec2 vLocal;
        """ + BOX + """
            void main() {
                vec2 full = uSize + 2.0 * uMargin;
                vec2 p = vLocal * full - full * 0.5;
                float d = box(p, uSize * 0.5);
                if (d < -1.0) discard;
                float lift = 0.75 + 0.5 * clamp(p.y / uSize.y + 0.5, 0.0, 1.0);
                float rim = clamp(1.5 - abs(d - 1.0), 0.0, 1.0) * lift;
                float glow = uGlow * exp(-max(d, 0.0) / uFalloff) * (1.0 - smoothstep(uMargin * 0.6, uMargin, d));
                gl_FragColor = vec4(uRim * (rim + glow), 0.0);
            }
        """
        // The cursor: a bright dot with a soft violet glow, over a faint shade that dims what's underneath so
        // the dot still shows on a white page. Premultiplied.
        private const val DOT_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            void main() {
                float r = length(vTexCoord - 0.5) * 2.0;
                float core = 1.0 - smoothstep(0.26, 0.36, r);
                float glow = 0.55 * exp(-r * r * 7.0) * (1.0 - smoothstep(0.8, 1.0, r));
                float shade = 0.4 * (1.0 - smoothstep(0.45, 1.0, r));
                vec3 light = vec3(1.0) * core + vec3(0.78, 0.74, 1.0) * glow * (1.0 - core);
                gl_FragColor = vec4(light, max(core, shade));
            }
        """

        // Flat unit quad for the cursor, as a triangle strip: x, y, u, v
        private val QUAD = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f
        )
        private val IDENTITY = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

        // A screen as a triangle strip of bottom/top vertex pairs in its own frame, grown by margin on every
        // side (for the border) and tilted to face the wearer. Curved screens follow the cylinder in segments.
        private fun screenStrip(curved: Boolean, widthDegrees: Float, width: Float, height: Float, margin: Float, tiltDegrees: Float): FloatBuffer {
            val segments = if (curved) max(8, (widthDegrees / 2f).toInt()) else 1
            val data = FloatArray((segments + 1) * 2 * 5)
            val point = FloatArray(3)
            var i = 0
            for (segment in 0..segments) {
                // Left edge first
                val u = segment.toFloat() / segments
                for (v in listOf(0f, 1f)) {
                    ScreenGeometry.point(curved, widthDegrees, width, height, margin, tiltDegrees, u, v, point)
                    data[i++] = point[0]; data[i++] = point[1]; data[i++] = point[2]; data[i++] = u; data[i++] = v
                }
            }
            return ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(data)
        }
    }

    class Screen(val spec: ScreenSpec) {
        // Where the screen sits at the current size: bringing screens closer makes each one wider and
        // spreads them apart, so neighbours never overlap
        var yawDegrees = 0f
        var pitchDegrees = 0f
        var widthDegrees = 0f
        var heightDegrees = 0f
        // Width along the surface (an arc for curved screens, the plane width for flat ones); the height
        // keeps the screen's pixel aspect
        var width = 0f
        var height = 0f
        lateinit var mesh: FloatBuffer
        lateinit var borderMesh: FloatBuffer
        var vertexCount = 0
        var borderVertexCount = 0
        // The tilt built into the meshes, which changes when the screens are recentred up or down
        var meshTilt = Float.NaN
            private set

        fun place(scale: Float) {
            yawDegrees = spec.yawDegrees * scale
            pitchDegrees = spec.pitchDegrees * scale
            widthDegrees = spec.widthDegrees * scale
            width = ScreenGeometry.width(spec.curved, widthDegrees)
            height = width * spec.heightPx / spec.widthPx
            heightDegrees = Math.toDegrees(2 * atan(height / 2.0 / SCREEN_DISTANCE)).toFloat()
            meshTilt = Float.NaN
        }

        // Built straight ahead and tilted up or down; turned to yawDegrees when drawn. Call on the GL thread.
        fun build(tiltDegrees: Float) {
            mesh = screenStrip(spec.curved, widthDegrees, width, height, 0f, tiltDegrees)
            borderMesh = screenStrip(spec.curved, widthDegrees, width, height, GLOW_MARGIN, tiltDegrees)
            vertexCount = mesh.capacity() / 5
            borderVertexCount = borderMesh.capacity() / 5
            meshTilt = tiltDegrees
        }

        var textureId = 0
        lateinit var surfaceTexture: SurfaceTexture
        lateinit var surface: Surface
        val frameAvailable = AtomicBoolean(false)
        val textureMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    }

    // Scratch space for the gap checks, which run while the renderer is constructed
    private val corner = FloatArray(3)
    private var scale = scale
    private var fixed = fixed
    val screens = layout.screens.map { Screen(it).apply { place(placementScale(it)) } }
    // How far each screen may tilt about its own horizontal axis, which keeps it level as the head turns
    // to it, before neighbours come too close; any more pitch tilts the whole arrangement
    private var maxOwnTiltDegrees = maxOwnTilt()

    // Rebuilds the screens' geometry at a new size; call on the GL thread
    fun setScale(scale: Float) {
        this.scale = scale
        placeScreens()
    }

    // Switches a single screen between staying put in the room and following the head; call on the GL thread.
    // Going back to floating recentres, so the screen stays where the wearer is looking rather than
    // jumping back to wherever they last recentred.
    fun setFixed(fixed: Boolean) {
        if (fixed == this.fixed) return
        this.fixed = fixed
        if (!fixed) recenterRequested = true
        placeScreens()
    }

    private fun placeScreens() {
        screens.forEach { it.place(placementScale(it.spec)) }
        maxOwnTiltDegrees = maxOwnTilt()
    }

    // A fixed screen too big for the glasses' view is shrunk to fit inside it
    private fun placementScale(spec: ScreenSpec): Float {
        if (!fixed) return scale
        val viewWidth = Math.toDegrees(2 * atan(tan(Math.toRadians(VIEW_HEIGHT_DEGREES / 2.0)) * VIEW_ASPECT)).toFloat()
        return scale * minOf(1f, FIXED_FILL * viewWidth / spec.widthDegrees, FIXED_FILL * VIEW_HEIGHT_DEGREES / spec.heightDegrees)
    }

    // Where the screens are tipped to face; a fixed screen is always straight ahead
    private fun pitchReference() = if (fixed) 0f else pitchReferenceDegrees

    // Set to recentre on where the wearer is looking, up or down included
    @Volatile var recenterRequested = false
    // Must match the glasses' optics, or world-locked screens drift as the head turns
    @Volatile var verticalFovDegrees = 25f
    // How far past the sample time to predict the head pose, covering render and scan-out latency
    @Volatile var lookaheadSeconds = 0.03f

    private val quadBuffer = ByteBuffer.allocateDirect(QUAD.size * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().put(QUAD).also { it.position(0) }
    private var screenProgram = 0
    private var dotProgram = 0
    private var frameProgram = 0
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
    private val gazePoint = FloatArray(2)
    private val mvp = FloatArray(16)

    private var statsStartNanos = 0L
    private var statsFrames = 0
    private var statsSamples = 0L

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        screenProgram = buildProgram(TEXTURE_VERTEX_SHADER, SCREEN_FRAGMENT_SHADER)
        dotProgram = buildProgram(TEXTURE_VERTEX_SHADER, DOT_FRAGMENT_SHADER)
        frameProgram = buildProgram(TEXTURE_VERTEX_SHADER, FRAME_FRAGMENT_SHADER)
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        // The EGL context is preserved across pauses, so textures are only set up once
        if (screens.first().textureId != 0) return
        val textures = IntArray(screens.size)
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
        updateActiveScreen(if (fixed) Quat.IDENTITY else head)
        // Predict from when the sample arrived, so every frame lands at the same point in time
        // whatever the sample's age. A fixed screen ignores the head altogether.
        val age = if (sample == null) 0f else ((System.nanoTime() - sample.nanos) / 1e9f).coerceIn(0f, 0.05f)
        val predicted = if (fixed) Quat.IDENTITY else Quat.integrate(head, imu.angularRate, lookaheadSeconds + age)
        // The view is the inverse of the head rotation; the meshes are already in world space
        Quat.toMatrix(Quat.conj(predicted), view)
        Matrix.perspectiveM(projection, 0, verticalFovDegrees, aspect, 0.1f, 100f)
        Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val ownTilt = ownTilt()
        for (screen in screens) {
            if (screen.meshTilt != ownTilt + screen.pitchDegrees) screen.build(ownTilt + screen.pitchDegrees)
        }
        val inputScreens = Session.screens
        val active = screens.indices.firstOrNull { inputScreens.getOrNull(it)?.let { input -> input === Session.activeScreen } == true }
        // Every frame first, so no screen's glow lands on a neighbour's content. The frames also show where
        // an empty screen is, since black is see-through on the glasses.
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
        screens.forEachIndexed { i, screen ->
            setScreenModel(screen)
            drawFrame(screen, i == active)
        }
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        screens.forEachIndexed { i, screen ->
            setScreenModel(screen)
            drawScreenMesh(screen)
        }
        GLES20.glDisable(GLES20.GL_BLEND)
        if (active != null) drawCursor(screens[active], inputScreens[active])
        logStats()
    }

    // The screen being looked at gets the trackpad, or the nearest one when looking between screens. It's
    // judged by each screen's whole area rather than its centre, so looking at the end of a wide screen
    // doesn't hand the trackpad to a neighbour. Hysteresis stops it flickering in the gaps, and it never
    // changes while a finger is on the trackpad.
    private fun updateActiveScreen(head: FloatArray) {
        val inputScreens = Session.screens
        if (inputScreens.size != screens.size || Session.touchActive) return
        // Forward is -Z, so the gaze direction is minus the rotation's third column
        Quat.toMatrix(head, gaze)
        // Measured around the arrangement's own vertical, which tips with it when recentred looking up or down
        val ownTilt = ownTilt()
        val tilt = Math.toRadians((pitchReference() - ownTilt).toDouble())
        val towardZ = gaze[10] * cos(tilt) - gaze[9] * sin(tilt)
        val up = -gaze[9] * cos(tilt) - gaze[10] * sin(tilt)
        val yaw = Math.toDegrees(atan2(gaze[8].toDouble(), towardZ)).toFloat()
        val pitch = Math.toDegrees(asin(up.coerceIn(-1.0, 1.0))).toFloat()
        // Angle from the gaze to the edge of a screen, or 0 inside it; each screen's centre sits at its own
        // pitch plus the tilt the screens take on their own
        fun distance(i: Int): Float {
            val screen = screens[i]
            val across = abs(yaw - screen.yawDegrees) - screen.widthDegrees / 2
            val up = abs(pitch - screen.pitchDegrees - ownTilt) - screen.heightDegrees / 2
            return hypot(max(across, 0f), max(up, 0f))
        }
        val current = inputScreens.indexOfFirst { it === Session.activeScreen }
        var best = screens.indices.minByOrNull { distance(it) } ?: return
        if (current >= 0 && best != current && distance(current) < distance(best) + SWITCH_HYSTERESIS_DEGREES) {
            best = current
        }
        // For the phone pointer: where the gaze meets the screen, before it becomes the active one
        screens.forEachIndexed { i, screen ->
            inputScreens[i].pxPerDegreeX = screen.spec.widthPx / screen.widthDegrees
            inputScreens[i].pxPerDegreeY = screen.spec.heightPx / screen.heightDegrees
        }
        if (pointOn(screens[best], -gaze[8].toDouble(), -gaze[9].toDouble(), -gaze[10].toDouble(), gazePoint)) {
            inputScreens[best].gazeX = gazePoint[0] * inputScreens[best].width
            inputScreens[best].gazeY = gazePoint[1] * inputScreens[best].height
        }
        Session.activeScreen = inputScreens[best]
    }

    // Where a direction from the wearer, in the recentred frame, meets a screen: the inverse of
    // ScreenGeometry.point. Writes how far across (0 at the left) and down (0 at the top) it lands, kept
    // within the screen; false if the direction points away from it.
    private fun pointOn(screen: Screen, dx: Double, dy: Double, dz: Double, out: FloatArray): Boolean {
        // Undo the arrangement's tilt, the screen's turn to its yaw, then the screen's own tilt
        val arrangement = Math.toRadians((pitchReference() - ownTilt()).toDouble())
        val y1 = dy * cos(arrangement) + dz * sin(arrangement)
        val z1 = -dy * sin(arrangement) + dz * cos(arrangement)
        val yaw = Math.toRadians(screen.yawDegrees.toDouble())
        val x2 = dx * cos(yaw) - z1 * sin(yaw)
        val z2 = dx * sin(yaw) + z1 * cos(yaw)
        val tilt = Math.toRadians((ownTilt() + screen.pitchDegrees).toDouble())
        val y3 = y1 * cos(tilt) + z2 * sin(tilt)
        val z3 = -y1 * sin(tilt) + z2 * cos(tilt)
        val across: Double
        val up: Double
        if (screen.spec.curved) {
            // Positive angles are to the left; the ray meets the upright cylinder at the screen's distance
            val angle = Math.toDegrees(atan2(-x2, -z3))
            across = 0.5 - angle / screen.widthDegrees
            up = SCREEN_DISTANCE * y3 / hypot(x2, z3) / screen.height + 0.5
        } else {
            if (z3 > -1e-3) return false
            val reach = -SCREEN_DISTANCE / z3
            across = reach * x2 / screen.width + 0.5
            up = reach * y3 / screen.height + 0.5
        }
        out[0] = across.coerceIn(0.0, 1.0).toFloat()
        out[1] = (1 - up).coerceIn(0.0, 1.0).toFloat()
        return true
    }

    // Faces the screens towards the wearer at the recentred height: each screen tilts on its own as far as
    // its neighbours allow (built into its mesh, with its own pitch), then the whole arrangement tips as one
    // for the rest
    private fun placeScreen(screen: Screen) {
        Matrix.setIdentityM(model, 0)
        Matrix.rotateM(model, 0, pitchReference() - ownTilt(), 1f, 0f, 0f)
        Matrix.rotateM(model, 0, screen.yawDegrees, 0f, 1f, 0f)
    }

    private fun ownTilt() = pitchReference().coerceIn(-maxOwnTiltDegrees, maxOwnTiltDegrees)

    // Tilting screens that sit side by side brings their top (or bottom) inner corners together, so find
    // the most tilt that keeps every such pair at least MIN_GAP_DEGREES apart. Screens above or below each
    // other tilt together and keep their distance.
    private fun maxOwnTilt(): Float {
        val byYaw = screens.sortedBy { it.yawDegrees }
        val pairs = byYaw.indices.flatMap { i -> (i + 1 until byYaw.size).map { byYaw[i] to byYaw[it] } }
            .filter { (a, b) -> abs(a.pitchDegrees - b.pitchDegrees) < (a.heightDegrees + b.heightDegrees) / 2 }
        if (pairs.isEmpty()) return 90f
        var clear = 0f
        var blocked = 90f
        repeat(16) {
            val tilt = (clear + blocked) / 2f
            if (pairs.all { (right, left) -> gapDegrees(right, left, tilt) >= MIN_GAP_DEGREES }) clear = tilt else blocked = tilt
        }
        Log.i(TAG, "screens tilt up to %.1f° on their own".format(clear))
        return clear
    }

    // Angle between two neighbouring screens' facing borders, both tilted, measured either side of the
    // vertical plane midway between them
    private fun gapDegrees(right: Screen, left: Screen, tilt: Float): Float {
        val midYaw = (right.yawDegrees + left.yawDegrees) / 2f
        // The right-hand screen's left edge faces the left-hand screen's right edge
        val rightClearance = listOf(0f, 1f).minOf { v -> clearance(right, 0f, v, right.yawDegrees - midYaw, tilt + right.pitchDegrees) }
        val leftClearance = listOf(0f, 1f).minOf { v -> clearance(left, 1f, v, left.yawDegrees - midYaw, tilt + left.pitchDegrees) }
        return rightClearance + leftClearance
    }

    // How far a corner of a screen's border sits on its own side of the midway plane, in degrees, once the
    // screen is tilted and turned to its yaw relative to that plane (positive yaw is to the left)
    private fun clearance(screen: Screen, u: Float, v: Float, yawDegrees: Float, tilt: Float): Float {
        ScreenGeometry.point(screen.spec.curved, screen.widthDegrees, screen.width, screen.height, BORDER_MARGIN, tilt, u, v, corner)
        val (x, y, z) = corner
        val r = Math.toRadians(yawDegrees.toDouble())
        val turnedX = x * cos(r) + z * sin(r)
        val side = asin(turnedX / sqrt(x * x + y * y + z * z)) * if (yawDegrees > 0) -1.0 else 1.0
        return Math.toDegrees(side).toFloat()
    }

    private fun setScreenModel(screen: Screen) {
        placeScreen(screen)
        Matrix.multiplyMM(mvp, 0, viewProjection, 0, model, 0)
    }

    private fun drawFrame(screen: Screen, active: Boolean) {
        GLES20.glUseProgram(frameProgram)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(frameProgram, "uMvp"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(frameProgram, "uTexMatrix"), 1, false, IDENTITY, 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(frameProgram, "uSize"), screen.spec.widthPx.toFloat(), screen.spec.heightPx.toFloat())
        // The glow strip's margin in the screen's own pixels
        GLES20.glUniform1f(GLES20.glGetUniformLocation(frameProgram, "uMargin"), GLOW_MARGIN / screen.width * screen.spec.widthPx)
        val rim = if (active) ACTIVE_RIM_COLOR else RIM_COLOR
        GLES20.glUniform3f(GLES20.glGetUniformLocation(frameProgram, "uRim"), rim[0], rim[1], rim[2])
        GLES20.glUniform1f(GLES20.glGetUniformLocation(frameProgram, "uGlow"), GLOW_STRENGTH)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(frameProgram, "uFalloff"), GLOW_FALLOFF_PX)
        bindMesh(frameProgram, screen.borderMesh, withTexCoords = true)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, screen.borderVertexCount)
    }

    private fun drawScreenMesh(screen: Screen) {
        GLES20.glUseProgram(screenProgram)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(screenProgram, "uMvp"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(screenProgram, "uTexMatrix"), 1, false, screen.textureMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, screen.textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(screenProgram, "uTexture"), 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(screenProgram, "uSize"), screen.spec.widthPx.toFloat(), screen.spec.heightPx.toFloat())
        bindMesh(screenProgram, screen.mesh, withTexCoords = true)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, screen.vertexCount)
    }

    // The cursor's dot of light: a small flat quad centred on the cursor position, slightly in front of
    // the screen's surface
    private fun drawCursor(screen: Screen, input: Session.Screen) {
        val u = input.cursorX / input.width
        val y = screen.height / 2f - input.cursorY / input.height * screen.height
        val half = DOT_SIZE_PX * screen.height / input.height / 2f
        // Centred on the cursor, and tilted as the screen's mesh is
        placeScreen(screen)
        if (screen.spec.curved) {
            Matrix.rotateM(model, 0, screen.meshTilt, 1f, 0f, 0f)
            Matrix.rotateM(model, 0, screen.widthDegrees / 2f - u * screen.widthDegrees, 0f, 1f, 0f)
            Matrix.translateM(model, 0, 0f, y, -SCREEN_DISTANCE * 0.995f)
        } else {
            Matrix.rotateM(model, 0, screen.meshTilt, 1f, 0f, 0f)
            Matrix.translateM(model, 0, (u - 0.5f) * screen.width, y, -SCREEN_DISTANCE * 0.995f)
        }
        Matrix.scaleM(model, 0, half, half, 1f)
        Matrix.multiplyMM(mvp, 0, viewProjection, 0, model, 0)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(dotProgram)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(dotProgram, "uMvp"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(dotProgram, "uTexMatrix"), 1, false, IDENTITY, 0)
        val position = GLES20.glGetAttribLocation(dotProgram, "aPosition")
        val texCoord = GLES20.glGetAttribLocation(dotProgram, "aTexCoord")
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
