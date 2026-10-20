package pl.arhea.onvifipcam.rtsp

/**
 * Turns frames into RTP packets. Packets are handed to [emit] as (buffer, length); the buffer is reused,
 * so the callee must consume it synchronously.
 */
abstract class RtpPacketizer(val payloadType: Int, val clockRate: Int, ssrc: Int) {
    var sequence: Int = (Math.random() * 65535).toInt()
        private set
    val timestampBase: Int = (Math.random() * Int.MAX_VALUE).toInt()
    var packetCount = 0L
        private set
    var octetCount = 0L
        private set

    protected val buf = ByteArray(MAX_PACKET)

    init {
        buf[0] = 0x80.toByte()
        buf[8] = (ssrc ushr 24).toByte(); buf[9] = (ssrc ushr 16).toByte()
        buf[10] = (ssrc ushr 8).toByte(); buf[11] = ssrc.toByte()
    }

    fun rtpTimestamp(ptsUs: Long): Int = (timestampBase + ptsUs * clockRate / 1_000_000L).toInt()

    abstract fun packetize(frame: MediaFrame, emit: (ByteArray, Int) -> Unit)

    protected fun writeHeader(marker: Boolean, timestamp: Int) {
        buf[1] = ((if (marker) 0x80 else 0) or payloadType).toByte()
        buf[2] = (sequence ushr 8).toByte(); buf[3] = sequence.toByte()
        buf[4] = (timestamp ushr 24).toByte(); buf[5] = (timestamp ushr 16).toByte()
        buf[6] = (timestamp ushr 8).toByte(); buf[7] = timestamp.toByte()
        sequence = (sequence + 1) and 0xffff
    }

    protected fun send(length: Int, emit: (ByteArray, Int) -> Unit) {
        packetCount++
        octetCount += length - HEADER
        emit(buf, length)
    }

    companion object {
        const val HEADER = 12
        const val MAX_PAYLOAD = 1400
        const val MAX_PACKET = HEADER + MAX_PAYLOAD + 4
    }
}

class H264Packetizer(payloadType: Int, ssrc: Int) : RtpPacketizer(payloadType, 90000, ssrc) {
    override fun packetize(frame: MediaFrame, emit: (ByteArray, Int) -> Unit) {
        val ts = rtpTimestamp(frame.ptsUs)
        val nals = Nal.split(frame.data).filter { it.isNotEmpty() && Nal.h264Type(it) != Nal.H264_AUD }
        for ((i, nal) in nals.withIndex()) {
            val last = i == nals.size - 1
            if (nal.size <= MAX_PAYLOAD) {
                writeHeader(last, ts)
                System.arraycopy(nal, 0, buf, HEADER, nal.size)
                send(HEADER + nal.size, emit)
            } else {
                // FU-A
                val nri = nal[0].toInt() and 0x60
                val type = nal[0].toInt() and 0x1f
                var pos = 1
                while (pos < nal.size) {
                    val chunk = minOf(MAX_PAYLOAD - 2, nal.size - pos)
                    val start = pos == 1
                    val end = pos + chunk == nal.size
                    writeHeader(last && end, ts)
                    buf[HEADER] = (nri or 28).toByte()
                    buf[HEADER + 1] = ((if (start) 0x80 else 0) or (if (end) 0x40 else 0) or type).toByte()
                    System.arraycopy(nal, pos, buf, HEADER + 2, chunk)
                    send(HEADER + 2 + chunk, emit)
                    pos += chunk
                }
            }
        }
    }
}

class H265Packetizer(payloadType: Int, ssrc: Int) : RtpPacketizer(payloadType, 90000, ssrc) {
    override fun packetize(frame: MediaFrame, emit: (ByteArray, Int) -> Unit) {
        val ts = rtpTimestamp(frame.ptsUs)
        val nals = Nal.split(frame.data).filter { it.size > 2 && Nal.h265Type(it) != Nal.H265_AUD }
        for ((i, nal) in nals.withIndex()) {
            val last = i == nals.size - 1
            if (nal.size <= MAX_PAYLOAD) {
                writeHeader(last, ts)
                System.arraycopy(nal, 0, buf, HEADER, nal.size)
                send(HEADER + nal.size, emit)
            } else {
                // Fragmentation unit (type 49)
                val type = (nal[0].toInt() shr 1) and 0x3f
                val h0 = (nal[0].toInt() and 0x81) or (49 shl 1)
                val h1 = nal[1].toInt()
                var pos = 2
                while (pos < nal.size) {
                    val chunk = minOf(MAX_PAYLOAD - 3, nal.size - pos)
                    val start = pos == 2
                    val end = pos + chunk == nal.size
                    writeHeader(last && end, ts)
                    buf[HEADER] = h0.toByte()
                    buf[HEADER + 1] = h1.toByte()
                    buf[HEADER + 2] = ((if (start) 0x80 else 0) or (if (end) 0x40 else 0) or type).toByte()
                    System.arraycopy(nal, pos, buf, HEADER + 3, chunk)
                    send(HEADER + 3 + chunk, emit)
                    pos += chunk
                }
            }
        }
    }
}

/** MPEG4-GENERIC AAC-hbr, one access unit per packet (RFC 3640). */
class AacPacketizer(payloadType: Int, clockRate: Int, ssrc: Int) : RtpPacketizer(payloadType, clockRate, ssrc) {
    override fun packetize(frame: MediaFrame, emit: (ByteArray, Int) -> Unit) {
        val size = frame.data.size
        if (size > MAX_PAYLOAD - 4 || size == 0) return
        writeHeader(true, rtpTimestamp(frame.ptsUs))
        buf[HEADER] = 0
        buf[HEADER + 1] = 16 // AU-headers-length in bits
        buf[HEADER + 2] = (size shr 5).toByte()
        buf[HEADER + 3] = ((size and 0x1f) shl 3).toByte()
        System.arraycopy(frame.data, 0, buf, HEADER + 4, size)
        send(HEADER + 4 + size, emit)
    }
}
