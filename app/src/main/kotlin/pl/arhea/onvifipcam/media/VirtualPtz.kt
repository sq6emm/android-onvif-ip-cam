package pl.arhea.onvifipcam.media

import pl.arhea.onvifipcam.onvif.PtzControl
import pl.arhea.onvifipcam.onvif.PtzPosition
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign

/**
 * Digital PTZ state. Positions use ONVIF generic spaces (pan/tilt -1..1, zoom 0..1); the renderer
 * reads [view] every frame, which also advances continuous and animated absolute moves.
 * [baseWidth]/[baseHeight] are the fractions of the source the unzoomed view covers (aspect-fit),
 * needed to convert FOV-relative moves into positions.
 */
class VirtualPtz(
    private val maxZoom: Float,
    private val baseWidth: Float,
    private val baseHeight: Float,
    initial: Triple<Float, Float, Float> = Triple(0f, 0f, 0f),
    private val onMovingChanged: (Boolean) -> Unit = {},
) : PtzControl {
    private var pan = initial.first
    private var tilt = initial.second
    private var zoom = initial.third
    private var vPan = 0f
    private var vTilt = 0f
    private var vZoom = 0f
    private var velocityUntilMs = 0L
    private var targetPan: Float? = null
    private var targetTilt: Float? = null
    private var targetZoom: Float? = null
    private var lastNs = System.nanoTime()
    private var wasMoving = false

    @Synchronized
    fun view(): ViewWindow {
        step()
        return ViewWindow(maxZoom.pow(zoom), pan, tilt)
    }

    val moving: Boolean @Synchronized get() = panTiltMoving() || zoomMoving()

    @Synchronized
    override fun continuousMove(pan: Float, tilt: Float, zoom: Float, timeoutMs: Long) {
        step()
        targetPan = null; targetTilt = null; targetZoom = null
        vPan = pan.coerceIn(-1f, 1f)
        vTilt = tilt.coerceIn(-1f, 1f)
        vZoom = zoom.coerceIn(-1f, 1f)
        velocityUntilMs = System.currentTimeMillis() + timeoutMs.coerceIn(100, 60_000)
        notifyMoving()
    }

    @Synchronized
    override fun absoluteMove(pan: Float?, tilt: Float?, zoom: Float?) {
        step()
        vPan = 0f; vTilt = 0f; vZoom = 0f
        targetPan = pan?.coerceIn(-1f, 1f)
        targetTilt = tilt?.coerceIn(-1f, 1f)
        targetZoom = zoom?.coerceIn(0f, 1f)
        notifyMoving()
    }

    @Synchronized
    override fun relativeMove(pan: Float, tilt: Float, zoom: Float) {
        step()
        absoluteMove(
            if (pan != 0f) (targetPan ?: this.pan) + pan else null,
            if (tilt != 0f) (targetTilt ?: this.tilt) + tilt else null,
            if (zoom != 0f) (targetZoom ?: this.zoom) + zoom else null,
        )
    }

    @Synchronized
    override fun relativeMoveFov(pan: Float, tilt: Float) {
        step()
        val z = maxZoom.pow(targetZoom ?: zoom)
        val cw = baseWidth / z
        val ch = baseHeight / z
        // A shift of `f` half-views moves the window by f*cw/2 source units; the pan range is (1-cw)/2.
        val dp = if (cw < 0.999f) pan * cw / (1f - cw) else 0f
        val dt = if (ch < 0.999f) tilt * ch / (1f - ch) else 0f
        relativeMove(dp, dt, 0f)
    }

    @Synchronized
    override fun stop(panTilt: Boolean, zoom: Boolean) {
        step()
        if (panTilt) { vPan = 0f; vTilt = 0f; targetPan = null; targetTilt = null }
        if (zoom) { vZoom = 0f; targetZoom = null }
        notifyMoving()
    }

    @Synchronized
    override fun position(): PtzPosition {
        step()
        return PtzPosition(pan, tilt, zoom, panTiltMoving(), zoomMoving())
    }

    @Synchronized
    fun snapshotPosition() = Triple(pan, tilt, zoom)

    private fun panTiltMoving() = vPan != 0f || vTilt != 0f || targetPan != null || targetTilt != null
    private fun zoomMoving() = vZoom != 0f || targetZoom != null

    private fun step() {
        val now = System.nanoTime()
        val dt = ((now - lastNs) / 1e9f).coerceIn(0f, 0.5f)
        lastNs = now
        if ((vPan != 0f || vTilt != 0f || vZoom != 0f) && System.currentTimeMillis() > velocityUntilMs) {
            vPan = 0f; vTilt = 0f; vZoom = 0f
        }
        pan = (pan + vPan * PAN_SPEED * dt).coerceIn(-1f, 1f)
        tilt = (tilt + vTilt * PAN_SPEED * dt).coerceIn(-1f, 1f)
        zoom = (zoom + vZoom * ZOOM_SPEED * dt).coerceIn(0f, 1f)
        targetPan?.let { t -> pan = approach(pan, t, ABS_PAN_SPEED * dt); if (pan == t) targetPan = null }
        targetTilt?.let { t -> tilt = approach(tilt, t, ABS_PAN_SPEED * dt); if (tilt == t) targetTilt = null }
        targetZoom?.let { t -> zoom = approach(zoom, t, ABS_ZOOM_SPEED * dt); if (zoom == t) targetZoom = null }
        notifyMoving()
    }

    private fun notifyMoving() {
        val m = panTiltMoving() || zoomMoving()
        if (m != wasMoving) {
            wasMoving = m
            onMovingChanged(m)
        }
    }

    private fun approach(v: Float, t: Float, step: Float): Float =
        if (abs(t - v) <= step) t else v + sign(t - v) * step

    companion object {
        /** Position units per second at velocity 1 (full -1..1 sweep in 4 s). */
        private const val PAN_SPEED = 0.5f
        private const val ZOOM_SPEED = 0.33f
        private const val ABS_PAN_SPEED = 2f
        private const val ABS_ZOOM_SPEED = 1f
    }
}
