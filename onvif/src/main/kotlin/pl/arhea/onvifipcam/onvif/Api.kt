package pl.arhea.onvifipcam.onvif

import pl.arhea.onvifipcam.rtsp.VideoCodec

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val firmwareVersion: String,
    val serialNumber: String,
    val hardwareId: String,
)

/** One media profile = one RTSP stream. */
data class StreamProfile(
    val token: String,
    val name: String,
    /** RTSP path, e.g. "stream1". */
    val path: String,
    val encoderToken: String,
    val codec: VideoCodec,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int,
    val keyframeIntervalS: Int,
)

data class AudioInfo(val sampleRate: Int, val bitrateKbps: Int)

/** Requested encoder change from SetVideoEncoderConfiguration; null fields are unchanged. */
data class EncoderChange(
    val width: Int? = null,
    val height: Int? = null,
    val fps: Int? = null,
    val bitrateKbps: Int? = null,
    val keyframeIntervalS: Int? = null,
    val codec: VideoCodec? = null,
)

/** Everything the ONVIF services need from the camera. Implemented by the app. */
interface OnvifDevice {
    val info: DeviceInfo
    /** Stable UUID (without the urn:uuid: prefix). */
    val uuid: String
    val name: String
    val rtspPort: Int
    val httpPort: Int
    fun profiles(): List<StreamProfile>
    /** Null when audio is disabled. */
    fun audio(): AudioInfo?
    /** Width/height of the video source (main stream). */
    fun sourceSize(): Pair<Int, Int>
    fun snapshotJpeg(): ByteArray?
    /** Applies an encoder change. Returns false if not accepted. */
    fun changeEncoder(encoderToken: String, change: EncoderChange): Boolean
    val ptz: PtzControl?
    val imaging: ImagingControl?
}

data class PtzPosition(
    /** -1..1 */
    val pan: Float,
    /** -1..1 */
    val tilt: Float,
    /** 0..1 */
    val zoom: Float,
    val panTiltMoving: Boolean,
    val zoomMoving: Boolean,
)

/**
 * PTZ in ONVIF generic spaces: pan/tilt position -1..1, zoom position 0..1,
 * velocities -1..1. FOV translations are fractions of the current field of view (-1..1 = half a view).
 */
interface PtzControl {
    fun continuousMove(pan: Float, tilt: Float, zoom: Float, timeoutMs: Long)
    fun absoluteMove(pan: Float?, tilt: Float?, zoom: Float?)
    fun relativeMove(pan: Float, tilt: Float, zoom: Float)
    fun relativeMoveFov(pan: Float, tilt: Float)
    fun stop(panTilt: Boolean, zoom: Boolean)
    fun position(): PtzPosition
}

data class ImagingSettings(
    /** 0..100 */
    val brightness: Float,
    val autoFocus: Boolean,
    /** 0..1 */
    val focusPosition: Float,
    /** IR cut filter / illumination mapped to the torch: true = light on. */
    val light: Boolean,
    val hasLight: Boolean,
)

interface ImagingControl {
    fun settings(): ImagingSettings
    fun apply(brightness: Float?, autoFocus: Boolean?, focusPosition: Float?, light: Boolean?)
}
