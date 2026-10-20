package pl.arhea.onvifipcam.rtsp

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal sealed class RtpTransport {
    open fun close() {}

    class Interleaved(val rtpChannel: Int, val rtcpChannel: Int) : RtpTransport()

    class Udp private constructor(
        private val rtpSocket: DatagramSocket,
        private val rtcpSocket: DatagramSocket,
        private val remote: InetAddress,
        private val remoteRtpPort: Int,
        private val remoteRtcpPort: Int,
    ) : RtpTransport() {
        val localRtpPort get() = rtpSocket.localPort
        val localRtcpPort get() = rtcpSocket.localPort
        @Volatile var onActivity: (() -> Unit)? = null
        @Volatile private var closed = false

        init {
            rtpSocket.sendBufferSize = 512 * 1024
            thread(name = "rtcp-rx-$localRtcpPort", isDaemon = true) {
                val buf = ByteArray(1500)
                val pkt = DatagramPacket(buf, buf.size)
                while (!closed) {
                    try {
                        rtcpSocket.receive(pkt)
                        onActivity?.invoke()
                    } catch (_: IOException) {
                        break
                    }
                }
            }
        }

        fun sendRtp(data: ByteArray, len: Int) = send(rtpSocket, remoteRtpPort, data, len)
        fun sendRtcp(data: ByteArray, len: Int) = send(rtcpSocket, remoteRtcpPort, data, len)

        private fun send(socket: DatagramSocket, port: Int, data: ByteArray, len: Int): Boolean = try {
            socket.send(DatagramPacket(data, len, remote, port))
            true
        } catch (_: IOException) {
            !closed
        }

        override fun close() {
            closed = true
            rtpSocket.close()
            rtcpSocket.close()
        }

        companion object {
            private val random = SecureRandom()

            /** Binds an even/odd port pair for RTP/RTCP. */
            fun open(remote: InetAddress, rtpPort: Int, rtcpPort: Int): Udp {
                repeat(50) {
                    val p = 50000 + random.nextInt(7000) * 2
                    val rtp = try { DatagramSocket(p) } catch (_: SocketException) { return@repeat }
                    val rtcp = try { DatagramSocket(p + 1) } catch (_: SocketException) { rtp.close(); return@repeat }
                    return Udp(rtp, rtcp, remote, rtpPort, rtcpPort)
                }
                throw IOException("No free UDP port pair")
            }
        }
    }
}

/** An RTSP session: the tracks set up by one client and the thread that sends them. */
internal class RtspSession(
    val endpoint: StreamEndpoint,
    private val interleavedWriter: (Int, ByteArray, Int) -> Boolean,
    private val closeConnection: () -> Unit,
    private val logger: Logger,
) : FrameSubscriber {
    val id: String = "%08X".format(random.nextInt())
    @Volatile var lastActivity = System.currentTimeMillis()

    private class Track(val id: Int, val ssrc: Int, val packetizer: RtpPacketizer, val transport: RtpTransport) {
        var lastSenderReport = 0L
    }

    private class Item(val track: Track, val frame: MediaFrame)

    private val tracks = ConcurrentHashMap<Int, Track>()
    private val queue = ArrayBlockingQueue<Item>(QUEUE_CAPACITY)
    @Volatile var playing = false
        private set
    @Volatile private var stopped = false
    @Volatile private var waitingKeyframe = true
    private var sender: Thread? = null
    private val rtcpBuf = ByteArray(28)

    fun addTrack(trackId: Int, video: VideoTrackInfo, audio: AudioTrackInfo?, transport: RtpTransport) {
        tracks.remove(trackId)?.transport?.close()
        val ssrc = random.nextInt()
        val packetizer = when {
            trackId == RtspServer.TRACK_VIDEO && video.codec == VideoCodec.H264 -> H264Packetizer(RtspServer.PT_VIDEO, ssrc)
            trackId == RtspServer.TRACK_VIDEO -> H265Packetizer(RtspServer.PT_VIDEO, ssrc)
            else -> AacPacketizer(RtspServer.PT_AUDIO, audio!!.sampleRate, ssrc)
        }
        if (transport is RtpTransport.Udp) transport.onActivity = { lastActivity = System.currentTimeMillis() }
        tracks[trackId] = Track(trackId, ssrc, packetizer, transport)
    }

    fun ssrc(trackId: Int): Int = tracks[trackId]?.ssrc ?: 0

    fun tracks(): List<Pair<Int, RtpPacketizer>> = tracks.values.sortedBy { it.id }.map { it.id to it.packetizer }

    @Synchronized
    fun play() {
        if (playing || stopped) return
        playing = true
        waitingKeyframe = true
        queue.clear()
        sender = thread(name = "rtsp-send-$id", isDaemon = true) { sendLoop() }
        endpoint.subscribe(this)
    }

    @Synchronized
    fun pause() {
        playing = false
        endpoint.unsubscribe(this)
        sender?.interrupt()
        sender = null
    }

    @Synchronized
    fun stop() {
        if (stopped) return
        stopped = true
        pause()
        tracks.values.forEach { it.transport.close() }
    }

    override fun onVideo(frame: MediaFrame) {
        if (!playing) return
        val track = tracks[RtspServer.TRACK_VIDEO] ?: return
        if (waitingKeyframe) {
            if (!frame.keyframe) return
            waitingKeyframe = false
        }
        if (!queue.offer(Item(track, frame))) {
            // Client can't keep up: drop everything queued and resume from the next keyframe.
            queue.clear()
            waitingKeyframe = true
            endpoint.requestKeyframe()
        }
    }

    override fun onAudio(frame: MediaFrame) {
        if (!playing || waitingKeyframe) return
        val track = tracks[RtspServer.TRACK_AUDIO] ?: return
        queue.offer(Item(track, frame))
    }

    override fun onReset() {
        logger.log("RTSP session $id reset (stream parameters changed)")
        stop()
        closeConnection()
    }

    private fun sendLoop() {
        try {
            while (playing && !stopped) {
                val item = queue.poll(500, TimeUnit.MILLISECONDS)
                if (item != null) {
                    val t = item.track
                    var ok = true
                    t.packetizer.packetize(item.frame) { buf, len -> if (ok) ok = sendRtp(t, buf, len) }
                    if (!ok) break
                }
                val now = System.currentTimeMillis()
                for (t in tracks.values) {
                    if (now - t.lastSenderReport >= SR_INTERVAL_MS && t.packetizer.packetCount > 0) {
                        t.lastSenderReport = now
                        sendSenderReport(t, now)
                    }
                }
                if (tracks.values.any { it.transport is RtpTransport.Udp } &&
                    now - lastActivity > RtspServer.SESSION_TIMEOUT_S * 2000L
                ) {
                    logger.log("RTSP session $id timed out")
                    stop()
                    break
                }
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun sendRtp(t: Track, buf: ByteArray, len: Int): Boolean = when (val tr = t.transport) {
        is RtpTransport.Interleaved -> interleavedWriter(tr.rtpChannel, buf, len)
        is RtpTransport.Udp -> tr.sendRtp(buf, len)
    }

    private fun sendSenderReport(t: Track, nowMs: Long) {
        val b = rtcpBuf
        val ntpSec = nowMs / 1000 + NTP_EPOCH_OFFSET
        val ntpFrac = ((nowMs % 1000) shl 32) / 1000
        val rtpTs = t.packetizer.rtpTimestamp(System.nanoTime() / 1000)
        b[0] = 0x80.toByte(); b[1] = 200.toByte(); b[2] = 0; b[3] = 6
        putInt(b, 4, t.ssrc)
        putInt(b, 8, ntpSec.toInt())
        putInt(b, 12, ntpFrac.toInt())
        putInt(b, 16, rtpTs)
        putInt(b, 20, t.packetizer.packetCount.toInt())
        putInt(b, 24, t.packetizer.octetCount.toInt())
        when (val tr = t.transport) {
            is RtpTransport.Interleaved -> interleavedWriter(tr.rtcpChannel, b, b.size)
            is RtpTransport.Udp -> tr.sendRtcp(b, b.size)
        }
    }

    private fun putInt(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 24).toByte(); b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte(); b[off + 3] = v.toByte()
    }

    companion object {
        private val random = SecureRandom()
        private const val QUEUE_CAPACITY = 256
        private const val SR_INTERVAL_MS = 3000L
        private const val NTP_EPOCH_OFFSET = 2208988800L
    }
}
