package pl.arhea.onvifipcam.service

import android.os.Build
import pl.arhea.onvifipcam.BuildConfig
import pl.arhea.onvifipcam.media.CameraPipeline
import pl.arhea.onvifipcam.onvif.AudioInfo
import pl.arhea.onvifipcam.onvif.DeviceInfo
import pl.arhea.onvifipcam.onvif.EncoderChange
import pl.arhea.onvifipcam.onvif.ImagingControl
import pl.arhea.onvifipcam.onvif.OnvifDevice
import pl.arhea.onvifipcam.onvif.PtzControl
import pl.arhea.onvifipcam.onvif.StreamProfile
import pl.arhea.onvifipcam.settings.CamSettings
import pl.arhea.onvifipcam.settings.Resolution
import pl.arhea.onvifipcam.settings.SettingsStore

/** Exposes the running pipeline and settings to the ONVIF services. */
class AppOnvifDevice(
    private val settings: CamSettings,
    private val pipeline: CameraPipeline,
    private val requestRestart: () -> Unit,
) : OnvifDevice {
    override val info = DeviceInfo(
        manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
        model = Build.MODEL,
        firmwareVersion = BuildConfig.VERSION_NAME,
        serialNumber = settings.deviceUuid.replace("-", "").take(12).uppercase(),
        hardwareId = Build.DEVICE,
    )
    override val uuid: String = settings.deviceUuid
    override val name: String = settings.deviceName
    override val rtspPort: Int = settings.rtspPort
    override val httpPort: Int = settings.onvifPort

    override fun profiles(): List<StreamProfile> = listOf(
        StreamProfile(
            "profile_main", "MainStream", "stream1", "vec_main", pipeline.mainCodec,
            pipeline.mainSize.width, pipeline.mainSize.height, settings.main.fps, settings.main.bitrateKbps, settings.keyframeIntervalS,
        ),
        StreamProfile(
            "profile_sub", "SubStream", "stream2", "vec_sub", settings.sub.codec,
            pipeline.subSize.width, pipeline.subSize.height, settings.sub.fps, settings.sub.bitrateKbps, settings.keyframeIntervalS,
        ),
    )

    override fun audio(): AudioInfo? =
        if (settings.audioEnabled && pipeline.audioError == null) AudioInfo(44100, 64) else null

    override fun sourceSize() = pipeline.mainSize.width to pipeline.mainSize.height

    override fun snapshotJpeg(): ByteArray? = pipeline.snapshotJpeg()

    override fun changeEncoder(encoderToken: String, change: EncoderChange): Boolean {
        val main = encoderToken == "vec_main"
        val cur = if (main) settings.main else settings.sub
        val allowed = if (main) CamSettings.MAIN_RESOLUTIONS else CamSettings.SUB_RESOLUTIONS
        var res = cur.resolution
        val w = change.width
        val h = change.height
        if (w != null && h != null) {
            // Clients see rotated sizes; settings store landscape sizes.
            val landscape = Resolution(maxOf(w, h), minOf(w, h))
            res = allowed.firstOrNull { it == landscape } ?: return false
        }
        val fps = change.fps?.let { f -> CamSettings.FPS_OPTIONS.minByOrNull { kotlin.math.abs(it - f) } } ?: cur.fps
        val bitrate = change.bitrateKbps?.coerceIn(100, 20000) ?: cur.bitrateKbps
        val codec = if (main) change.codec ?: cur.codec else cur.codec
        val next = cur.copy(resolution = res, fps = fps, bitrateKbps = bitrate, codec = codec)
        val gop = change.keyframeIntervalS?.coerceIn(1, 10) ?: settings.keyframeIntervalS
        if (next == cur && gop == settings.keyframeIntervalS) return true
        SettingsStore.update { if (main) it.copy(main = next, keyframeIntervalS = gop) else it.copy(sub = next, keyframeIntervalS = gop) }
        requestRestart()
        return true
    }

    override val ptz: PtzControl = pipeline.ptz
    override val imaging: ImagingControl = pipeline.imaging
}
