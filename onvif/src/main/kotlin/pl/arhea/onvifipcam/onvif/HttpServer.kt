package pl.arhea.onvifipcam.onvif

import pl.arhea.onvifipcam.rtsp.Logger
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class HttpRequest(
    val method: String,
    val path: String,
    val query: String,
    val headers: Map<String, String>,
    val body: ByteArray,
    /** Address of our socket the client connected to. */
    val localAddress: String,
    val remoteAddress: String,
) {
    fun header(name: String) = headers[name.lowercase()]
}

class HttpResponse(
    val status: Int,
    val body: ByteArray = ByteArray(0),
    val contentType: String = "text/plain",
    val headers: List<Pair<String, String>> = emptyList(),
) {
    companion object {
        fun text(status: Int, text: String) = HttpResponse(status, text.toByteArray(), "text/plain; charset=utf-8")
    }
}

/** Small blocking HTTP/1.1 server with keep-alive; enough for SOAP and snapshots. */
class HttpServer(
    private val port: Int,
    private val logger: Logger,
    private val handler: (HttpRequest) -> HttpResponse,
) {
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "http").apply { isDaemon = true } }
    private val sockets = CopyOnWriteArrayList<Socket>()
    @Volatile private var running = false

    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        serverSocket = ss
        running = true
        pool.execute {
            while (running) {
                val s = try { ss.accept() } catch (e: IOException) { break }
                sockets.add(s)
                pool.execute {
                    try { serve(s) } finally { sockets.remove(s); runCatching { s.close() } }
                }
            }
        }
        logger.log("HTTP server listening on port $port")
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        sockets.forEach { runCatching { it.close() } }
        pool.shutdownNow()
    }

    private fun serve(s: Socket) {
        s.soTimeout = 75_000
        val input = BufferedInputStream(s.getInputStream())
        val out = s.getOutputStream()
        try {
            while (running) {
                val req = read(input, s) ?: break
                val resp = try {
                    handler(req)
                } catch (e: Exception) {
                    logger.log("HTTP handler error for ${req.path}: $e")
                    HttpResponse.text(500, "Internal error")
                }
                val keepAlive = !req.header("connection").equals("close", ignoreCase = true)
                val head = StringBuilder()
                head.append("HTTP/1.1 ${resp.status} ${reason(resp.status)}\r\n")
                head.append("Server: OnvifIpCam\r\n")
                head.append("Content-Type: ${resp.contentType}\r\n")
                head.append("Content-Length: ${resp.body.size}\r\n")
                head.append("Connection: ${if (keepAlive) "keep-alive" else "close"}\r\n")
                for ((k, v) in resp.headers) head.append("$k: $v\r\n")
                head.append("\r\n")
                out.write(head.toString().toByteArray())
                out.write(resp.body)
                out.flush()
                if (!keepAlive) break
            }
        } catch (_: SocketTimeoutException) {
        } catch (_: IOException) {
        }
    }

    private fun read(input: InputStream, s: Socket): HttpRequest? {
        val line = readLine(input) ?: return null
        val parts = line.split(' ')
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(input) ?: return null
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len > MAX_BODY) throw IOException("body too large")
        val body = ByteArray(len)
        var r = 0
        while (r < len) {
            val n = input.read(body, r, len - r)
            if (n < 0) return null
            r += n
        }
        val target = parts[1]
        return HttpRequest(
            parts[0].uppercase(), target.substringBefore('?'), target.substringAfter('?', ""), headers, body,
            s.localAddress.hostAddress ?: "", s.inetAddress.hostAddress ?: "",
        )
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 16384) throw IOException("line too long")
        }
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"
        405 -> "Method Not Allowed"; 500 -> "Internal Server Error"; 503 -> "Service Unavailable"
        else -> "Status"
    }

    companion object {
        private const val MAX_BODY = 1 shl 20
    }
}
