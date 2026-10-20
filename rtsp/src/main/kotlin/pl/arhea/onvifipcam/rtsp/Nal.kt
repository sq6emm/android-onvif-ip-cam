package pl.arhea.onvifipcam.rtsp

object Nal {
    const val H264_IDR = 5
    const val H264_SPS = 7
    const val H264_PPS = 8
    const val H264_AUD = 9

    const val H265_VPS = 32
    const val H265_SPS = 33
    const val H265_PPS = 34
    const val H265_AUD = 35

    fun h264Type(nal: ByteArray): Int = if (nal.isEmpty()) -1 else nal[0].toInt() and 0x1f
    fun h265Type(nal: ByteArray): Int = if (nal.isEmpty()) -1 else (nal[0].toInt() shr 1) and 0x3f

    fun type(codec: VideoCodec, nal: ByteArray) = if (codec == VideoCodec.H264) h264Type(nal) else h265Type(nal)

    fun isParameterSet(codec: VideoCodec, type: Int) = when (codec) {
        VideoCodec.H264 -> type == H264_SPS || type == H264_PPS
        VideoCodec.H265 -> type == H265_VPS || type == H265_SPS || type == H265_PPS
    }

    fun isAud(codec: VideoCodec, type: Int) = if (codec == VideoCodec.H264) type == H264_AUD else type == H265_AUD

    fun isKeyframe(codec: VideoCodec, type: Int) = when (codec) {
        VideoCodec.H264 -> type == H264_IDR
        VideoCodec.H265 -> type in 16..21
    }

    /** Splits an Annex-B byte stream into NAL units (without start codes). */
    fun split(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): List<ByteArray> {
        val end = offset + length
        val out = ArrayList<ByteArray>(4)
        var start = -1
        var i = offset
        while (i + 2 < end) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                if (start >= 0) out.add(trimmed(data, start, i))
                start = i + 3
                i += 3
            } else {
                i++
            }
        }
        if (start >= 0 && start < end) out.add(data.copyOfRange(start, end))
        else if (start < 0 && length > 0) out.add(data.copyOfRange(offset, end)) // no start code: single NAL
        return out
    }

    /** Copies [from, to) dropping trailing zero bytes that belong to a 4-byte start code. */
    private fun trimmed(data: ByteArray, from: Int, to: Int): ByteArray {
        var e = to
        while (e > from && data[e - 1].toInt() == 0) e--
        return data.copyOfRange(from, e)
    }
}
