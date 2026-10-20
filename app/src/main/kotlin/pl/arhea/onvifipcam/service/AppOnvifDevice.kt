package pl.arhea.onvifipcam.service

import android.content.Context
import android.os.Build
import pl.arhea.onvifipcam.BuildConfig
import pl.arhea.onvifipcam.media.CameraPipeline
import pl.arhea.onvifipcam.media.CameraSource
import pl.arhea.onvifipcam.onvif.CameraConfig
import pl.arhea.onvifipcam.onvif.ConfigChange
import pl.arhea.onvifipcam.onvif.ConfigControl
import pl.arhea.onvifipcam.onvif.VideoSourceInfo
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
    context: Context,
    private val settings: CamSettings,
    private val pipeline: CameraPipeline,
    private val requestRestart: (String) -> Unit,
) : OnvifDevice {
    override val info = DeviceInfo(
        manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
        model = Build.MODEL,
        firmwareVersion = BuildConfig.VERSION_NAME,
        serialNumber = settings.deviceUuid.replace("-", "").take(12).uppercase(),
        hardwareId = Build.DEVICE,
    )
    override val uuid: String = settings.deviceUuid
    override val name: String get() = SettingsStore.current.deviceName
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
        requestRestart("$encoderToken ${next.resolution} ${next.fps}fps ${next.bitrateKbps}kbps ${next.codec} gop ${gop}s")
        return true
    }

    private val sources: List<VideoSourceInfo> = runCatching { CameraSource.list(context) }.getOrDefault(emptyList()).map {
        val label = when (it.facing) { "back" -> "Back camera"; "front" -> "Front camera"; else -> "External camera" }
        VideoSourceInfo(sourceToken(it.id), "$label ${it.id}", it.maxSize.width, it.maxSize.height)
    }

    override val configuration: ConfigControl = object : ConfigControl {
        override fun config(): CameraConfig {
            val s = SettingsStore.current
            return CameraConfig(
                name = s.deviceName, username = s.username, sources = sources, currentSource = sourceToken(s.cameraId),
                rotation = s.rotation, osdEnabled = s.osdEnabled, audioEnabled = s.audioEnabled,
                motionEnabled = s.motionEnabled, motionSensitivity = s.motionSensitivity,
                httpPort = s.onvifPort, rtspPort = s.rtspPort,
            )
        }

        override fun change(change: ConfigChange): Boolean {
            val cur = SettingsStore.current
            val cameraId = change.source?.let { t -> sources.firstOrNull { it.token == t }?.token?.removePrefix(SOURCE_PREFIX) ?: return false }
            if (change.rotation != null && change.rotation !in CamSettings.ROTATIONS) return false
            if (change.motionSensitivity != null && change.motionSensitivity !in 0..100) return false
            if (listOfNotNull(change.httpPort, change.rtspPort).any { it !in 1024..65535 }) return false
            if (change.name?.isBlank() == true || change.username?.isBlank() == true) return false
            val next = cur.copy(
                deviceName = change.name ?: cur.deviceName,
                username = change.username ?: cur.username,
                password = change.password ?: cur.password,
                cameraId = cameraId ?: cur.cameraId,
                rotation = change.rotation ?: cur.rotation,
                osdEnabled = change.osdEnabled ?: cur.osdEnabled,
                audioEnabled = change.audioEnabled ?: cur.audioEnabled,
                motionEnabled = change.motionEnabled ?: cur.motionEnabled,
                motionSensitivity = change.motionSensitivity ?: cur.motionSensitivity,
                onvifPort = change.httpPort ?: cur.onvifPort,
                rtspPort = change.rtspPort ?: cur.rtspPort,
            )
            if (next == cur) return true
            SettingsStore.update { next }
            // Credentials are read live by the auth code; everything else needs the pipeline/servers rebuilt.
            if (next.copy(username = cur.username, password = cur.password) != cur) requestRestart(change.toString().replace(Regex("password=[^,)]*"), "password=***"))
            return true
        }
    }

    override val ptz: PtzControl = pipeline.ptz
    override val imaging: ImagingControl = pipeline.imaging

    companion object {
        private const val SOURCE_PREFIX = "vs_"
        fun sourceToken(cameraId: String) = "$SOURCE_PREFIX$cameraId"
    }
}
