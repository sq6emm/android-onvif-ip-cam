package pl.arhea.onvifipcam.rtsp

import java.util.Base64

enum class VideoCodec(val rtpName: String) { H264("H264"), H265("H265") }

/**
 * One encoded unit. For video: a complete access unit in Annex-B format (start codes included).
 * For audio: one raw AAC frame (no ADTS header).
 * [ptsUs] uses the shared monotonic clock (System.nanoTime() / 1000).
 */
class MediaFrame(val data: ByteArray, val ptsUs: Long, val keyframe: Boolean)

class VideoTrackInfo(
    val codec: VideoCodec,
    val width: Int,
    val height: Int,
    val fps: Int,
    /** Parameter sets without start codes. [vps] only for H.265. */
    val vps: ByteArray?,
    val sps: ByteArray,
    val pps: ByteArray,
) {
    fun sdpFmtp(payloadType: Int): String {
        val b64 = Base64.getEncoder()
        return when (codec) {
            VideoCodec.H264 -> {
                val profileLevelId = if (sps.size >= 4) "%02X%02X%02X".format(sps[1], sps[2], sps[3]) else "42E01F"
                "a=fmtp:$payloadType packetization-mode=1;profile-level-id=$profileLevelId;" +
                    "sprop-parameter-sets=${b64.encodeToString(sps)},${b64.encodeToString(pps)}"
            }
            VideoCodec.H265 ->
                "a=fmtp:$payloadType sprop-vps=${b64.encodeToString(vps ?: ByteArray(0))};" +
                    "sprop-sps=${b64.encodeToString(sps)};sprop-pps=${b64.encodeToString(pps)}"
        }
    }

    /** Parameter sets as Annex-B, to be prepended to keyframes that lack them. */
    fun annexBParameterSets(): ByteArray {
        val parts = listOfNotNull(vps, sps, pps)
        val out = ByteArray(parts.sumOf { it.size + 4 })
        var p = 0
        for (nal in parts) {
            out[p + 3] = 1
            System.arraycopy(nal, 0, out, p + 4, nal.size)
            p += nal.size + 4
        }
        return out
    }

    companion object {
        /** Builds track info from codec-config NAL units (e.g. MediaCodec csd buffers or BUFFER_FLAG_CODEC_CONFIG output). */
        fun fromConfig(codec: VideoCodec, width: Int, height: Int, fps: Int, configAnnexB: ByteArray): VideoTrackInfo? {
            var vps: ByteArray? = null
            var sps: ByteArray? = null
            var pps: ByteArray? = null
            for (nal in Nal.split(configAnnexB)) {
                when (codec) {
                    VideoCodec.H264 -> when (Nal.h264Type(nal)) {
                        Nal.H264_SPS -> sps = nal
                        Nal.H264_PPS -> pps = nal
                    }
                    VideoCodec.H265 -> when (Nal.h265Type(nal)) {
                        Nal.H265_VPS -> vps = nal
                        Nal.H265_SPS -> sps = nal
                        Nal.H265_PPS -> pps = nal
                    }
                }
            }
            if (sps == null || pps == null || (codec == VideoCodec.H265 && vps == null)) return null
            return VideoTrackInfo(codec, width, height, fps, vps, sps, pps)
        }
    }
}

class AudioTrackInfo(
    val sampleRate: Int,
    val channels: Int,
    /** AudioSpecificConfig (MediaCodec csd-0). */
    val config: ByteArray,
) {
    fun sdpFmtp(payloadType: Int): String =
        "a=fmtp:$payloadType streamtype=5;profile-level-id=15;mode=AAC-hbr;" +
            "config=${config.joinToString("") { "%02x".format(it) }};sizelength=13;indexlength=3;indexdeltalength=3"

    companion object {
        private val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

        /** AAC-LC AudioSpecificConfig. */
        fun aacLcConfig(sampleRate: Int, channels: Int): ByteArray {
            val idx = RATES.indexOf(sampleRate).let { if (it < 0) 4 else it }
            val v = (2 shl 11) or (idx shl 7) or (channels shl 3)
            return byteArrayOf((v shr 8).toByte(), v.toByte())
        }
    }
}
