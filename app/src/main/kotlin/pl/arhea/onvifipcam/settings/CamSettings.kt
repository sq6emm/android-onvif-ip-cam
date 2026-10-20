package pl.arhea.onvifipcam.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import pl.arhea.onvifipcam.rtsp.VideoCodec
import java.security.SecureRandom
import java.util.UUID

data class Resolution(val width: Int, val height: Int) {
    override fun toString() = "${width}x$height"

    companion object {
        fun parse(s: String?, default: Resolution): Resolution {
            val p = s?.split('x') ?: return default
            val w = p.getOrNull(0)?.toIntOrNull() ?: return default
            val h = p.getOrNull(1)?.toIntOrNull() ?: return default
            return Resolution(w, h)
        }
    }
}

data class StreamSettings(
    val resolution: Resolution,
    val fps: Int,
    val bitrateKbps: Int,
    val codec: VideoCodec,
)

data class CamSettings(
    val cameraId: String,
    val main: StreamSettings,
    val sub: StreamSettings,
    val keyframeIntervalS: Int,
    /** Capture resolution relative to the main stream; >1 gives digital PTZ zoom headroom. */
    val captureScale: Float,
    val rotation: Int,
    val audioEnabled: Boolean,
    val username: String,
    val password: String,
    val rtspPort: Int,
    val onvifPort: Int,
    val deviceName: String,
    val osdEnabled: Boolean,
    val motionEnabled: Boolean,
    /** 0..100 */
    val motionSensitivity: Int,
    val autostart: Boolean,
    val deviceUuid: String,
) {
    val authEnabled get() = password.isNotEmpty()

    companion object {
        val MAIN_RESOLUTIONS = listOf(Resolution(3840, 2160), Resolution(2560, 1440), Resolution(1920, 1080), Resolution(1280, 720))
        val SUB_RESOLUTIONS = listOf(Resolution(1280, 720), Resolution(960, 540), Resolution(854, 480), Resolution(640, 360))
        val FPS_OPTIONS = listOf(5, 10, 15, 20, 25, 30)
        val ROTATIONS = listOf(0, 90, 180, 270)
        val CAPTURE_SCALES = listOf(1.0f, 1.5f, 2.0f)
    }
}

/** Settings persisted in SharedPreferences and exposed as a StateFlow. */
object SettingsStore {
    private lateinit var prefs: SharedPreferences
    private val _settings = MutableStateFlow(placeholder())
    val settings: StateFlow<CamSettings> = _settings.asStateFlow()
    val current: CamSettings get() = _settings.value

    fun init(context: Context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!prefs.contains(K_UUID)) {
            prefs.edit()
                .putString(K_UUID, UUID.randomUUID().toString())
                .putString(K_PASSWORD, randomPassword())
                .apply()
        }
        _settings.value = load()
    }

    fun update(transform: (CamSettings) -> CamSettings) {
        val s = transform(current)
        save(s)
        _settings.value = s
    }

    private fun load(): CamSettings = CamSettings(
        cameraId = prefs.getString(K_CAMERA, "0")!!,
        main = StreamSettings(
            Resolution.parse(prefs.getString(K_MAIN_RES, null), Resolution(1920, 1080)),
            prefs.getInt(K_MAIN_FPS, 25),
            prefs.getInt(K_MAIN_BITRATE, 4000),
            codec(prefs.getString(K_MAIN_CODEC, null)),
        ),
        sub = StreamSettings(
            Resolution.parse(prefs.getString(K_SUB_RES, null), Resolution(640, 360)),
            prefs.getInt(K_SUB_FPS, 10),
            prefs.getInt(K_SUB_BITRATE, 600),
            VideoCodec.H264,
        ),
        keyframeIntervalS = prefs.getInt(K_GOP, 2),
        captureScale = prefs.getFloat(K_CAPTURE_SCALE, 1.5f),
        rotation = prefs.getInt(K_ROTATION, 0),
        audioEnabled = prefs.getBoolean(K_AUDIO, true),
        username = prefs.getString(K_USERNAME, "admin")!!,
        password = prefs.getString(K_PASSWORD, "")!!,
        rtspPort = prefs.getInt(K_RTSP_PORT, 8554),
        onvifPort = prefs.getInt(K_ONVIF_PORT, 8080),
        deviceName = prefs.getString(K_NAME, "Android IP Cam")!!,
        osdEnabled = prefs.getBoolean(K_OSD, true),
        motionEnabled = prefs.getBoolean(K_MOTION, true),
        motionSensitivity = prefs.getInt(K_MOTION_SENS, 50),
        autostart = prefs.getBoolean(K_AUTOSTART, false),
        deviceUuid = prefs.getString(K_UUID, "")!!,
    )

    private fun save(s: CamSettings) {
        prefs.edit()
            .putString(K_CAMERA, s.cameraId)
            .putString(K_MAIN_RES, s.main.resolution.toString())
            .putInt(K_MAIN_FPS, s.main.fps)
            .putInt(K_MAIN_BITRATE, s.main.bitrateKbps)
            .putString(K_MAIN_CODEC, s.main.codec.name)
            .putString(K_SUB_RES, s.sub.resolution.toString())
            .putInt(K_SUB_FPS, s.sub.fps)
            .putInt(K_SUB_BITRATE, s.sub.bitrateKbps)
            .putInt(K_GOP, s.keyframeIntervalS)
            .putFloat(K_CAPTURE_SCALE, s.captureScale)
            .putInt(K_ROTATION, s.rotation)
            .putBoolean(K_AUDIO, s.audioEnabled)
            .putString(K_USERNAME, s.username)
            .putString(K_PASSWORD, s.password)
            .putInt(K_RTSP_PORT, s.rtspPort)
            .putInt(K_ONVIF_PORT, s.onvifPort)
            .putString(K_NAME, s.deviceName)
            .putBoolean(K_OSD, s.osdEnabled)
            .putBoolean(K_MOTION, s.motionEnabled)
            .putInt(K_MOTION_SENS, s.motionSensitivity)
            .putBoolean(K_AUTOSTART, s.autostart)
            .apply()
    }

    /** Opaque string storage for other components (e.g. PTZ presets). */
    fun getString(key: String): String? = prefs.getString("x_$key", null)
    fun putString(key: String, value: String) = prefs.edit().putString("x_$key", value).apply()

    private fun codec(s: String?) = if (s == VideoCodec.H265.name) VideoCodec.H265 else VideoCodec.H264

    private fun randomPassword(): String {
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        val r = SecureRandom()
        return (1..10).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    private fun placeholder() = CamSettings(
        "0", StreamSettings(Resolution(1920, 1080), 25, 4000, VideoCodec.H264),
        StreamSettings(Resolution(640, 360), 10, 600, VideoCodec.H264), 2, 1.5f, 0, true, "admin", "",
        8554, 8080, "Android IP Cam", true, true, 50, false, "",
    )

    private const val K_CAMERA = "camera"
    private const val K_MAIN_RES = "main_res"
    private const val K_MAIN_FPS = "main_fps"
    private const val K_MAIN_BITRATE = "main_bitrate"
    private const val K_MAIN_CODEC = "main_codec"
    private const val K_SUB_RES = "sub_res"
    private const val K_SUB_FPS = "sub_fps"
    private const val K_SUB_BITRATE = "sub_bitrate"
    private const val K_GOP = "gop"
    private const val K_CAPTURE_SCALE = "capture_scale"
    private const val K_ROTATION = "rotation"
    private const val K_AUDIO = "audio"
    private const val K_USERNAME = "username"
    private const val K_PASSWORD = "password"
    private const val K_RTSP_PORT = "rtsp_port"
    private const val K_ONVIF_PORT = "onvif_port"
    private const val K_NAME = "name"
    private const val K_OSD = "osd"
    private const val K_MOTION = "motion"
    private const val K_MOTION_SENS = "motion_sens"
    private const val K_AUTOSTART = "autostart"
    private const val K_UUID = "uuid"
}
