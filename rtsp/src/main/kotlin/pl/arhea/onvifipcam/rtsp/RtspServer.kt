package pl.arhea.onvifipcam.rtsp

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Minimal logger so the module stays free of Android dependencies. */
fun interface Logger {
    fun log(message: String)

    companion object {
        val STDOUT = Logger { println(it) }
        val NONE = Logger { }
    }
}

class RtspServer(
    val port: Int,
    private val auth: DigestAuth?,
    private val logger: Logger = Logger.NONE,
    private val serverName: String = "OnvifIpCam",
) {
    private val endpoints = ConcurrentHashMap<String, StreamEndpoint>()
    private val connections = CopyOnWriteArrayList<RtspConnection>()
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    val clientCount: Int get() = connections.sumOf { it.playingSessions }

    fun addEndpoint(endpoint: StreamEndpoint) {
        endpoints[endpoint.path.trim('/')] = endpoint
    }

    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        serverSocket = ss
        running = true
        thread(name = "rtsp-accept", isDaemon = true) {
            while (running) {
                val socket = try {
                    ss.accept()
                } catch (e: IOException) {
                    if (running) logger.log("RTSP accept failed: $e")
                    break
                }
                val conn = RtspConnection(socket)
                connections.add(conn)
                thread(name = "rtsp-conn-${socket.inetAddress.hostAddress}", isDaemon = true) {
                    try {
                        conn.run()
                    } finally {
                        connections.remove(conn)
                        conn.close()
                    }
                }
            }
        }
        logger.log("RTSP server listening on port $port")
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        connections.forEach { it.close() }
        connections.clear()
    }

    private class Request(
        val method: String,
        val uri: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    ) {
        fun header(name: String) = headers[name.lowercase()]
        val cseq get() = header("cseq") ?: "0"
    }

    private inner class RtspConnection(private val socket: Socket) {
        private val input: InputStream = BufferedInputStream(socket.getInputStream(), 8192)
        private val output: OutputStream = socket.getOutputStream()
        /** Guards [output]: RTSP replies and interleaved RTP share the socket. */
        private val writeLock = Object()
        private val sessions = HashMap<String, RtspSession>()
        private val remote = socket.inetAddress
        @Volatile private var closed = false

        val playingSessions: Int get() = synchronized(sessions) { sessions.values.count { it.playing } }

        init {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.sendBufferSize = 512 * 1024
        }

        fun run() {
            logger.log("RTSP connection from ${remote.hostAddress}")
            try {
                while (!closed) {
                    val req = readRequest() ?: break
                    handle(req)
                }
            } catch (_: SocketException) {
            } catch (e: IOException) {
                logger.log("RTSP connection error: $e")
            } catch (e: Exception) {
                logger.log("RTSP connection crashed: $e")
            }
            logger.log("RTSP connection from ${remote.hostAddress} closed")
        }

        fun close() {
            if (closed) return
            closed = true
            synchronized(sessions) {
                sessions.values.forEach { it.stop() }
                sessions.clear()
            }
            runCatching { socket.close() }
        }

        /** Reads the next request, skipping interleaved binary data (RTCP receiver reports). */
        private fun readRequest(): Request? {
            while (true) {
                input.mark(4)
                val b = input.read()
                if (b < 0) return null
                if (b == '$'.code) {
                    val channel = input.read()
                    val len = (input.read() shl 8) or input.read()
                    if (channel < 0 || len < 0) return null
                    input.skipNBytesCompat(len)
                    touchSessions()
                    continue
                }
                if (b == '\r'.code || b == '\n'.code) continue
                input.reset()
                break
            }
            val requestLine = readLine() ?: return null
            val parts = requestLine.split(' ')
            if (parts.size < 3) return null
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine() ?: return null
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) return null
                read += n
            }
            return Request(parts[0].uppercase(), parts[1], headers, body)
        }

        private fun readLine(): String? {
            val sb = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) return if (sb.isEmpty()) null else sb.toString()
                if (c == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(c.toChar())
                if (sb.length > 8192) throw IOException("RTSP line too long")
            }
        }

        private fun touchSessions() {
            val now = System.currentTimeMillis()
            synchronized(sessions) { sessions.values.forEach { it.lastActivity = now } }
        }

        private fun handle(req: Request) {
            touchSessions()
            if (req.method != "OPTIONS" && auth != null && !auth.check(req.method, req.header("authorization"))) {
                logger.log("RTSP ${req.method} unauthorized from ${remote.hostAddress}")
                reply(req, 401, "Unauthorized", auth.rtspChallenges().map { "WWW-Authenticate" to it })
                return
            }
            when (req.method) {
                "OPTIONS" -> reply(
                    req, 200, "OK",
                    listOf("Public" to "OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, GET_PARAMETER, SET_PARAMETER"),
                )
                "DESCRIBE" -> describe(req)
                "SETUP" -> setup(req)
                "PLAY" -> play(req)
                "PAUSE" -> withSession(req) { s ->
                    s.pause()
                    reply(req, 200, "OK", listOf("Session" to s.id))
                }
                "TEARDOWN" -> {
                    val id = sessionId(req)
                    val s = synchronized(sessions) { sessions.remove(id) }
                    s?.stop()
                    reply(req, 200, "OK", if (id != null) listOf("Session" to id) else emptyList())
                }
                "GET_PARAMETER", "SET_PARAMETER" -> {
                    val id = sessionId(req)
                    reply(req, 200, "OK", if (id != null) listOf("Session" to id) else emptyList())
                }
                else -> reply(req, 501, "Not Implemented")
            }
        }

        private fun resolve(uri: String): Target? {
            var path = uri
            val scheme = path.indexOf("://")
            if (scheme >= 0) {
                val slash = path.indexOf('/', scheme + 3)
                path = if (slash >= 0) path.substring(slash) else "/"
            }
            path = path.substringBefore('?').trim('/')
            val segments = path.split('/').filter { it.isNotEmpty() }
            if (segments.isEmpty()) return null
            var trackId: Int? = null
            var streamSegments = segments
            val last = segments.last()
            if (last.startsWith("trackID=", ignoreCase = true)) {
                trackId = last.substringAfter('=').toIntOrNull()
                streamSegments = segments.dropLast(1)
            }
            val endpoint = endpoints[streamSegments.joinToString("/")] ?: return null
            return Target(endpoint, trackId)
        }

        private fun baseUri(req: Request): String {
            val u = req.uri.substringBefore('?')
            val t = resolve(req.uri)
            return if (t?.trackId != null) u.substringBeforeLast('/') + "/" else u.trimEnd('/') + "/"
        }

        private fun describe(req: Request) {
            val target = resolve(req.uri)
            if (target == null) {
                reply(req, 404, "Not Found")
                return
            }
            val video = target.endpoint.awaitVideoInfo(5000)
            if (video == null) {
                reply(req, 503, "Service Unavailable")
                return
            }
            val audio = target.endpoint.audioInfo
            val local = socket.localAddress.hostAddress
            val sdp = buildString {
                append("v=0\r\n")
                append("o=- ${System.currentTimeMillis()} 1 IN IP4 $local\r\n")
                append("s=$serverName\r\n")
                append("c=IN IP4 0.0.0.0\r\n")
                append("t=0 0\r\n")
                append("a=control:*\r\n")
                append("a=range:npt=0-\r\n")
                append("m=video 0 RTP/AVP $PT_VIDEO\r\n")
                append("a=rtpmap:$PT_VIDEO ${video.codec.rtpName}/90000\r\n")
                append(video.sdpFmtp(PT_VIDEO)).append("\r\n")
                if (video.width > 0) append("a=x-dimensions:${video.width},${video.height}\r\n")
                if (video.fps > 0) append("a=framerate:${video.fps}\r\n")
                append("a=control:trackID=$TRACK_VIDEO\r\n")
                if (audio != null) {
                    append("m=audio 0 RTP/AVP $PT_AUDIO\r\n")
                    append("a=rtpmap:$PT_AUDIO MPEG4-GENERIC/${audio.sampleRate}/${audio.channels}\r\n")
                    append(audio.sdpFmtp(PT_AUDIO)).append("\r\n")
                    append("a=control:trackID=$TRACK_AUDIO\r\n")
                }
            }.toByteArray()
            reply(
                req, 200, "OK",
                listOf("Content-Base" to baseUri(req), "Content-Type" to "application/sdp"),
                sdp,
            )
        }

        private fun setup(req: Request) {
            val target = resolve(req.uri)
            if (target == null) {
                reply(req, 404, "Not Found")
                return
            }
            val trackId = target.trackId ?: TRACK_VIDEO
            val video = target.endpoint.videoInfo
            val audio = target.endpoint.audioInfo
            if (video == null || (trackId == TRACK_AUDIO && audio == null) || trackId !in listOf(TRACK_VIDEO, TRACK_AUDIO)) {
                reply(req, 404, "Not Found")
                return
            }
            val transportHeader = req.header("transport") ?: ""
            val existingId = sessionId(req)
            val session = synchronized(sessions) {
                val existing = existingId?.let { sessions[it] }
                if (existingId != null && existing == null) null
                else existing ?: RtspSession(target.endpoint, ::writeInterleaved, ::close, logger).also { sessions[it.id] = it }
            }
            if (session == null) {
                reply(req, 454, "Session Not Found")
                return
            }
            if (session.endpoint !== target.endpoint) {
                reply(req, 459, "Aggregate Operation Not Allowed")
                return
            }
            val transport = chooseTransport(transportHeader)
            val replyTransport: String = when (transport) {
                is TransportSpec.Tcp -> {
                    session.addTrack(trackId, video, audio, RtpTransport.Interleaved(transport.rtp, transport.rtp + 1))
                    "RTP/AVP/TCP;unicast;interleaved=${transport.rtp}-${transport.rtp + 1}"
                }
                is TransportSpec.Udp -> {
                    val udp = try {
                        RtpTransport.Udp.open(remote, transport.rtp, transport.rtcp)
                    } catch (e: IOException) {
                        logger.log("RTSP UDP setup failed: $e")
                        reply(req, 500, "Internal Server Error")
                        return
                    }
                    session.addTrack(trackId, video, audio, udp)
                    "RTP/AVP;unicast;client_port=${transport.rtp}-${transport.rtcp};" +
                        "server_port=${udp.localRtpPort}-${udp.localRtcpPort}"
                }
                null -> {
                    reply(req, 461, "Unsupported Transport")
                    return
                }
            }
            reply(
                req, 200, "OK",
                listOf(
                    "Transport" to "$replyTransport;ssrc=%08X".format(session.ssrc(trackId)),
                    "Session" to "${session.id};timeout=$SESSION_TIMEOUT_S",
                ),
            )
        }

        private fun play(req: Request) = withSession(req) { s ->
            val base = baseUri(req)
            val rtpInfo = s.tracks().joinToString(",") { (id, p) ->
                "url=${base}trackID=$id;seq=${p.sequence};rtptime=${p.rtpTimestamp(System.nanoTime() / 1000).toUInt()}"
            }
            reply(
                req, 200, "OK",
                listOf("Session" to s.id, "Range" to "npt=0.000-", "RTP-Info" to rtpInfo),
            )
            s.play()
            logger.log("RTSP PLAY ${s.endpoint.path} to ${remote.hostAddress} (session ${s.id})")
        }

        private inline fun withSession(req: Request, block: (RtspSession) -> Unit) {
            val id = sessionId(req)
            val s = synchronized(sessions) { id?.let { sessions[it] } }
            if (s == null) reply(req, 454, "Session Not Found") else block(s)
        }

        private fun sessionId(req: Request): String? = req.header("session")?.substringBefore(';')?.trim()

        private fun writeInterleaved(channel: Int, data: ByteArray, length: Int): Boolean {
            if (closed) return false
            return try {
                synchronized(writeLock) {
                    val hdr = byteArrayOf('$'.code.toByte(), channel.toByte(), (length shr 8).toByte(), length.toByte())
                    output.write(hdr)
                    output.write(data, 0, length)
                }
                true
            } catch (e: IOException) {
                close()
                false
            }
        }

        private fun reply(
            req: Request,
            code: Int,
            reason: String,
            headers: List<Pair<String, String>> = emptyList(),
            body: ByteArray? = null,
        ) {
            val sb = StringBuilder()
            sb.append("RTSP/1.0 $code $reason\r\n")
            sb.append("CSeq: ${req.cseq}\r\n")
            sb.append("Server: $serverName\r\n")
            sb.append("Date: ${httpDate()}\r\n")
            for ((k, v) in headers) sb.append("$k: $v\r\n")
            if (body != null) sb.append("Content-Length: ${body.size}\r\n")
            sb.append("\r\n")
            try {
                synchronized(writeLock) {
                    output.write(sb.toString().toByteArray())
                    if (body != null) output.write(body)
                    output.flush()
                }
            } catch (e: IOException) {
                close()
            }
        }
    }

    private class Target(val endpoint: StreamEndpoint, val trackId: Int?)

    private sealed class TransportSpec {
        class Tcp(val rtp: Int) : TransportSpec()
        class Udp(val rtp: Int, val rtcp: Int) : TransportSpec()
    }

    /** Picks the first transport from the client's list that we support. */
    private fun chooseTransport(header: String): TransportSpec? {
        for (option in header.split(',')) {
            val params = option.split(';').map { it.trim() }
            val proto = params.firstOrNull()?.uppercase() ?: continue
            if (params.any { it.equals("multicast", ignoreCase = true) }) continue
            val kv = params.drop(1).associate { p -> p.substringBefore('=').lowercase() to p.substringAfter('=', "") }
            if (proto == "RTP/AVP/TCP") {
                val rtp = kv["interleaved"]?.substringBefore('-')?.toIntOrNull() ?: 0
                return TransportSpec.Tcp(rtp)
            }
            if (proto == "RTP/AVP" || proto == "RTP/AVP/UDP") {
                val ports = kv["client_port"] ?: continue
                val rtp = ports.substringBefore('-').toIntOrNull() ?: continue
                val rtcp = ports.substringAfter('-', "").toIntOrNull() ?: (rtp + 1)
                return TransportSpec.Udp(rtp, rtcp)
            }
        }
        return null
    }

    companion object {
        const val PT_VIDEO = 96
        const val PT_AUDIO = 97
        const val TRACK_VIDEO = 0
        const val TRACK_AUDIO = 1
        const val SESSION_TIMEOUT_S = 60

        private fun httpDate(): String {
            val fmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US)
            fmt.timeZone = java.util.TimeZone.getTimeZone("GMT")
            return fmt.format(java.util.Date())
        }
    }
}

internal fun InputStream.skipNBytesCompat(n: Int) {
    var left = n.toLong()
    while (left > 0) {
        val s = skip(left)
        if (s <= 0) {
            if (read() < 0) throw IOException("EOF")
            left--
        } else {
            left -= s
        }
    }
}
