package pl.arhea.onvifipcam.devserver

import pl.arhea.onvifipcam.onvif.AudioInfo
import pl.arhea.onvifipcam.onvif.CameraConfig
import pl.arhea.onvifipcam.onvif.ConfigChange
import pl.arhea.onvifipcam.onvif.ConfigControl
import pl.arhea.onvifipcam.onvif.VideoSourceInfo
import pl.arhea.onvifipcam.onvif.DeviceInfo
import pl.arhea.onvifipcam.onvif.EncoderChange
import pl.arhea.onvifipcam.onvif.ImagingControl
import pl.arhea.onvifipcam.onvif.ImagingSettings
import pl.arhea.onvifipcam.onvif.OnvifDevice
import pl.arhea.onvifipcam.onvif.PtzControl
import pl.arhea.onvifipcam.onvif.PtzPosition
import pl.arhea.onvifipcam.onvif.StreamProfile
import pl.arhea.onvifipcam.rtsp.VideoCodec
import java.io.File

/** PTZ that jumps instantly and logs every command. */
class FakePtz : PtzControl {
    @Volatile var pos = PtzPosition(0f, 0f, 0f, false, false)
    override fun continuousMove(pan: Float, tilt: Float, zoom: Float, timeoutMs: Long) {
        println("PTZ continuous $pan $tilt $zoom for ${timeoutMs}ms")
        pos = pos.copy(panTiltMoving = pan != 0f || tilt != 0f, zoomMoving = zoom != 0f)
    }
    override fun absoluteMove(pan: Float?, tilt: Float?, zoom: Float?) {
        println("PTZ absolute $pan $tilt $zoom")
        pos = PtzPosition(pan ?: pos.pan, tilt ?: pos.tilt, zoom ?: pos.zoom, false, false)
    }
    override fun relativeMove(pan: Float, tilt: Float, zoom: Float) {
        println("PTZ relative $pan $tilt $zoom")
        pos = PtzPosition((pos.pan + pan).coerceIn(-1f, 1f), (pos.tilt + tilt).coerceIn(-1f, 1f), (pos.zoom + zoom).coerceIn(0f, 1f), false, false)
    }
    override fun relativeMoveFov(pan: Float, tilt: Float) {
        println("PTZ relative FOV $pan $tilt")
        relativeMove(pan * 0.5f, tilt * 0.5f, 0f)
    }
    override fun stop(panTilt: Boolean, zoom: Boolean) {
        println("PTZ stop $panTilt $zoom")
        pos = pos.copy(panTiltMoving = false, zoomMoving = false)
    }
    override fun position() = pos
}

class FakeImaging : ImagingControl {
    @Volatile var s = ImagingSettings(50f, true, 0f, false, true)
    override fun settings() = s
    override fun apply(brightness: Float?, autoFocus: Boolean?, focusPosition: Float?, light: Boolean?) {
        println("Imaging $brightness $autoFocus $focusPosition $light")
        s = s.copy(
            brightness = brightness ?: s.brightness, autoFocus = autoFocus ?: s.autoFocus,
            focusPosition = focusPosition ?: s.focusPosition, light = light ?: s.light,
        )
    }
}

class FakeDevice(private val mainCodec: VideoCodec, private val dataDir: File) : OnvifDevice {
    override val info = DeviceInfo("Android", "DevServer", "0.1.0", "DEV0001", "devserver")
    override val uuid = "5f3c2b10-0000-4000-8000-000000000001"
    override val name get() = cfg.name
    override val rtspPort = 8554
    override val httpPort = 8080
    override fun profiles() = listOf(
        StreamProfile("profile_main", "MainStream", "stream1", "vec_main", mainCodec, 1920, 1080, 25, 4000, 2),
        StreamProfile("profile_sub", "SubStream", "stream2", "vec_sub", VideoCodec.H264, 640, 360, 25, 800, 2),
    )
    override fun audio() = if (cfg.audioEnabled) AudioInfo(44100, 64) else null
    override fun sourceSize() = 1920 to 1080
    override fun snapshotJpeg(): ByteArray? = File(dataDir, "snapshot.jpg").takeIf { it.exists() }?.readBytes()
    override fun changeEncoder(encoderToken: String, change: EncoderChange): Boolean {
        println("changeEncoder $encoderToken $change")
        return true
    }
    @Volatile var cfg = CameraConfig(
        "Dev Cam", "admin",
        listOf(VideoSourceInfo("vs_0", "Back camera 0", 1920, 1080), VideoSourceInfo("vs_1", "Front camera 1", 1280, 720)),
        "vs_0", 0, true, true, true, 50, 8080, 8554,
    )
    override val configuration = object : ConfigControl {
        override fun config() = cfg
        override fun change(change: ConfigChange): Boolean {
            println("config change $change")
            cfg = cfg.copy(
                name = change.name ?: cfg.name, username = change.username ?: cfg.username,
                currentSource = change.source ?: cfg.currentSource, rotation = change.rotation ?: cfg.rotation,
                osdEnabled = change.osdEnabled ?: cfg.osdEnabled, audioEnabled = change.audioEnabled ?: cfg.audioEnabled,
                motionEnabled = change.motionEnabled ?: cfg.motionEnabled,
                motionSensitivity = change.motionSensitivity ?: cfg.motionSensitivity,
            )
            return true
        }
    }
    override val ptz = FakePtz()
    override val imaging = FakeImaging()
}
