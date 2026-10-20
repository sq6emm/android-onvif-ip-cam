package pl.arhea.onvifipcam.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import pl.arhea.onvifipcam.rtsp.MediaFrame
import pl.arhea.onvifipcam.rtsp.StreamEndpoint
import pl.arhea.onvifipcam.rtsp.VideoCodec
import pl.arhea.onvifipcam.rtsp.VideoTrackInfo
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

/** Surface-input MediaCodec encoder publishing access units to a [StreamEndpoint]. */
class VideoEncoder(
    val codec: VideoCodec,
    val width: Int,
    val height: Int,
    val fps: Int,
    bitrateKbps: Int,
    private val keyframeIntervalS: Int,
    private val endpoint: StreamEndpoint,
) {
    private val mime = mimeOf(codec)
    private val mediaCodec = MediaCodec.createEncoderByType(mime)
    lateinit var inputSurface: Surface
        private set
    @Volatile private var running = false
    private var drainThread: Thread? = null
    private var lastKeyRequest = 0L
    @Volatile var bitrateKbps = bitrateKbps
        private set

    /** Measured output bitrate (kbit/s) over the last second. */
    @Volatile var measuredKbps = 0
        private set
    @Volatile var framesOut = 0L
        private set

    fun start() {
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyframeIntervalS)
            // Keep the stream alive (repeat last frame) when the camera stalls, e.g. long exposures at night.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / fps * 3)
            if (supportsCbr()) setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        try {
            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            // Some encoders reject optional keys; retry with the basics only.
            Log.w(TAG, "configure failed with extended format, retrying", e)
            format.removeKey(MediaFormat.KEY_LOW_LATENCY)
            format.removeKey(MediaFormat.KEY_PRIORITY)
            format.removeKey(MediaFormat.KEY_BITRATE_MODE)
            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        inputSurface = mediaCodec.createInputSurface()
        mediaCodec.start()
        running = true
        endpoint.keyframeRequester = ::requestKeyframe
        drainThread = thread(name = "enc-${endpoint.path}") { drain() }
    }

    fun requestKeyframe() {
        val now = System.currentTimeMillis()
        if (!running || now - lastKeyRequest < 500) return
        lastKeyRequest = now
        runCatching {
            mediaCodec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        }
    }

    fun setBitrate(kbps: Int) {
        bitrateKbps = kbps
        runCatching {
            mediaCodec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, kbps * 1000) })
        }
    }

    fun stop() {
        running = false
        endpoint.keyframeRequester = null
        drainThread?.join(1000)
        runCatching { mediaCodec.stop() }
        runCatching { mediaCodec.release() }
        runCatching { inputSurface.release() }
        endpoint.setVideoTrack(null)
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        var windowStart = System.nanoTime()
        var windowBytes = 0L
        while (running) {
            val idx = try {
                mediaCodec.dequeueOutputBuffer(info, 20_000)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "dequeue failed", e)
                break
            }
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormat(mediaCodec.outputFormat)
                idx >= 0 -> {
                    val buf = mediaCodec.getOutputBuffer(idx)
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val data = ByteArray(info.size)
                        buf.get(data)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            onConfig(data)
                        } else {
                            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            endpoint.pushVideo(MediaFrame(data, info.presentationTimeUs, key))
                            framesOut++
                            windowBytes += data.size
                        }
                    }
                    mediaCodec.releaseOutputBuffer(idx, false)
                }
            }
            val now = System.nanoTime()
            if (now - windowStart >= 1_000_000_000L) {
                measuredKbps = (windowBytes * 8 * 1_000_000L / (now - windowStart)).toInt()
                windowStart = now
                windowBytes = 0
            }
        }
    }

    private fun onFormat(format: MediaFormat) {
        val out = ByteArrayOutputStream()
        for (key in listOf("csd-0", "csd-1", "csd-2")) {
            val b = format.getByteBuffer(key) ?: continue
            val a = ByteArray(b.remaining())
            b.get(a)
            out.write(a)
        }
        if (out.size() > 0) onConfig(out.toByteArray())
    }

    private fun onConfig(config: ByteArray) {
        val info = VideoTrackInfo.fromConfig(codec, width, height, fps, config)
        if (info == null) {
            Log.w(TAG, "could not parse codec config (${config.size} bytes)")
            return
        }
        if (endpoint.videoInfo?.sps?.contentEquals(info.sps) != true) {
            Log.i(TAG, "${endpoint.path}: ${codec.name} ${width}x$height@$fps track ready")
            endpoint.setVideoTrack(info)
        }
    }

    private fun supportsCbr(): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .firstOrNull { it.isEncoder && it.name == mediaCodec.name }
            ?.getCapabilitiesForType(mime)?.encoderCapabilities
            ?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true
    }.getOrDefault(false)

    companion object {
        private const val TAG = "VideoEncoder"

        fun mimeOf(codec: VideoCodec) =
            if (codec == VideoCodec.H265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

        fun isSupported(codec: VideoCodec): Boolean = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .any { it.isEncoder && it.supportedTypes.any { t -> t.equals(mimeOf(codec), ignoreCase = true) } }
    }
}
