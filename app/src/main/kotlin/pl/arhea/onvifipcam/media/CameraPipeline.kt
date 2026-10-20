package pl.arhea.onvifipcam.media

import android.content.Context
import android.util.Log
import android.util.Size
import android.view.Surface
import pl.arhea.onvifipcam.onvif.ImagingControl
import pl.arhea.onvifipcam.onvif.ImagingSettings
import pl.arhea.onvifipcam.rtsp.StreamEndpoint
import pl.arhea.onvifipcam.rtsp.VideoCodec
import pl.arhea.onvifipcam.settings.CamSettings
import pl.arhea.onvifipcam.settings.SettingsStore

/**
 * Camera -> GL -> encoders (main + sub) + audio, motion detection and digital PTZ.
 * Everything is created in [start] and torn down in [stop]; settings changes require a restart.
 */
class CameraPipeline(
    private val context: Context,
    private val settings: CamSettings,
    private val main: StreamEndpoint,
    private val sub: StreamEndpoint,
    private val audioAllowed: Boolean,
    private val onMotion: (Boolean) -> Unit,
    private val onCameraStatus: (String?) -> Unit,
) {
    /** Effective main codec (H.265 falls back to H.264 when the device has no HEVC encoder). */
    val mainCodec: VideoCodec =
        if (settings.main.codec == VideoCodec.H265 && !VideoEncoder.isSupported(VideoCodec.H265)) VideoCodec.H264 else settings.main.codec

    private val rotated = settings.rotation == 90 || settings.rotation == 270
    /** Output sizes in the rotated (viewer) orientation. */
    val mainSize: Size = settings.main.resolution.let { if (rotated) Size(it.height, it.width) else Size(it.width, it.height) }
    val subSize: Size = settings.sub.resolution.let { if (rotated) Size(it.height, it.width) else Size(it.width, it.height) }

    val captureSize: Size = CameraSource.chooseCaptureSize(
        context, settings.cameraId,
        Size(settings.main.resolution.width, settings.main.resolution.height),
        settings.captureScale, settings.main.fps,
    )

    val ptz: VirtualPtz
    private var camera: CameraSource? = null
    private var renderer: GlRenderer? = null
    private var mainEncoder: VideoEncoder? = null
    private var subEncoder: VideoEncoder? = null
    private var audio: AudioEncoder? = null
    private var motion: MotionDetector? = null
    @Volatile var audioError: String? = null
        private set

    init {
        // Fraction of the (rotated) source the unzoomed main view covers, for FOV-relative PTZ moves.
        val sw = (if (rotated) captureSize.height else captureSize.width).toFloat()
        val sh = (if (rotated) captureSize.width else captureSize.height).toFloat()
        val srcAspect = sw / sh
        val dstAspect = mainSize.width.toFloat() / mainSize.height
        val bw = if (srcAspect > dstAspect) dstAspect / srcAspect else 1f
        val bh = if (srcAspect > dstAspect) 1f else srcAspect / dstAspect
        ptz = VirtualPtz(MAX_ZOOM, bw, bh, loadPtzPosition()) { moving -> motion?.suspended = moving }
    }

    fun start() {
        Log.i(TAG, "start: camera ${settings.cameraId} capture $captureSize, main $mainSize $mainCodec, sub $subSize")
        val r = GlRenderer(captureSize, settings.rotation, if (settings.osdEnabled) OsdText(settings.deviceName) else null, ptz::view)
        val texture = r.start()
        renderer = r

        val me = VideoEncoder(
            mainCodec, mainSize.width, mainSize.height, settings.main.fps,
            settings.main.bitrateKbps, settings.keyframeIntervalS, main,
        )
        me.start()
        r.addEncoderTarget(me.inputSurface, mainSize.width, mainSize.height, settings.main.fps)
        mainEncoder = me

        val se = VideoEncoder(
            VideoCodec.H264, subSize.width, subSize.height, settings.sub.fps,
            settings.sub.bitrateKbps, settings.keyframeIntervalS, sub,
        )
        se.start()
        r.addEncoderTarget(se.inputSurface, subSize.width, subSize.height, settings.sub.fps)
        subEncoder = se

        if (settings.motionEnabled) {
            val md = MotionDetector(GlRenderer.MOTION_W, GlRenderer.MOTION_H, settings.motionSensitivity, onChange = onMotion)
            motion = md
            r.motionSink = md::feed
        }

        if (settings.audioEnabled && audioAllowed) {
            try {
                audio = AudioEncoder(listOf(main, sub)).also { it.start() }
            } catch (e: Exception) {
                Log.e(TAG, "audio start failed", e)
                audioError = e.message ?: "audio failed"
            }
        }

        camera = CameraSource(context, settings.cameraId, captureSize, settings.main.fps, onCameraStatus).also { cam ->
            cam.setImaging(loadImaging())
            cam.start(texture)
        }
        pendingPreview?.let { (s, w, h) -> r.setPreview(s, w, h) }
    }

    fun stop() {
        savePtzPosition()
        camera?.stop()
        camera = null
        audio?.stop()
        audio = null
        renderer?.motionSink = null
        mainEncoder?.stop()
        subEncoder?.stop()
        mainEncoder = null
        subEncoder = null
        renderer?.release()
        renderer = null
    }

    private var pendingPreview: Triple<Surface, Int, Int>? = null

    fun setPreview(surface: Surface?, width: Int, height: Int) {
        pendingPreview = surface?.let { Triple(it, width, height) }
        renderer?.setPreview(surface, width, height)
    }

    fun snapshotJpeg(): ByteArray? = renderer?.snapshotJpeg(mainSize.width, mainSize.height)

    val motionActive: Boolean get() = motion?.active == true
    val motionLevel: Float get() = motion?.level ?: 0f
    val mainKbps: Int get() = mainEncoder?.measuredKbps ?: 0
    val subKbps: Int get() = subEncoder?.measuredKbps ?: 0
    val framesRendered: Long get() = renderer?.framesRendered ?: 0

    /** Thermal throttling: scales encoder bitrates (1.0 = configured). */
    fun setBitrateScale(scale: Float) {
        mainEncoder?.setBitrate((settings.main.bitrateKbps * scale).toInt())
        subEncoder?.setBitrate((settings.sub.bitrateKbps * scale).toInt())
    }

    fun requestKeyframes() {
        mainEncoder?.requestKeyframe()
        subEncoder?.requestKeyframe()
    }

    val imaging: ImagingControl = object : ImagingControl {
        override fun settings(): ImagingSettings {
            val s = camera?.imaging ?: loadImaging()
            return ImagingSettings(s.brightness, s.autoFocus, s.focusPosition, s.torch, camera?.hasFlash == true)
        }

        override fun apply(brightness: Float?, autoFocus: Boolean?, focusPosition: Float?, light: Boolean?) {
            val cur = camera?.imaging ?: loadImaging()
            val next = ImagingState(
                brightness = brightness?.coerceIn(0f, 100f) ?: cur.brightness,
                autoFocus = autoFocus ?: cur.autoFocus,
                focusPosition = focusPosition?.coerceIn(0f, 1f) ?: cur.focusPosition,
                torch = light ?: cur.torch,
            )
            camera?.setImaging(next)
            saveImaging(next)
        }
    }

    private fun loadPtzPosition(): Triple<Float, Float, Float> {
        val p = SettingsStore.getString("ptz_pos")?.split(',')?.mapNotNull { it.toFloatOrNull() }
        return if (p != null && p.size == 3) Triple(p[0], p[1], p[2]) else Triple(0f, 0f, 0f)
    }

    private fun savePtzPosition() {
        val (p, t, z) = ptz.snapshotPosition()
        SettingsStore.putString("ptz_pos", "$p,$t,$z")
    }

    private fun loadImaging(): ImagingState {
        val p = SettingsStore.getString("imaging")?.split(',') ?: return ImagingState()
        return ImagingState(
            brightness = p.getOrNull(0)?.toFloatOrNull() ?: 50f,
            autoFocus = p.getOrNull(1)?.toBooleanStrictOrNull() ?: true,
            focusPosition = p.getOrNull(2)?.toFloatOrNull() ?: 0f,
            torch = p.getOrNull(3)?.toBooleanStrictOrNull() ?: false,
        )
    }

    private fun saveImaging(s: ImagingState) {
        SettingsStore.putString("imaging", "${s.brightness},${s.autoFocus},${s.focusPosition},${s.torch}")
    }

    companion object {
        private const val TAG = "CameraPipeline"
        const val MAX_ZOOM = 4f
    }
}
