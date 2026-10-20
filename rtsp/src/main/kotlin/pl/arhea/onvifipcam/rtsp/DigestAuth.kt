package pl.arhea.onvifipcam.rtsp

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class Credentials(val username: String, val password: String)

/**
 * HTTP/RTSP Digest (RFC 2617, MD5, qop=auth optional) with Basic fallback.
 * [credentials] returns null when authentication is disabled.
 * Nonces are stateless: timestamp + MAC with a per-process secret.
 */
class DigestAuth(val realm: String, private val credentials: () -> Credentials?) {
    private val secret = ByteArray(16).also { SecureRandom().nextBytes(it) }

    val enabled: Boolean get() = credentials() != null

    fun currentCredentials(): Credentials? = credentials()

    fun challenges(): List<String> = listOf(
        "Digest realm=\"$realm\", nonce=\"${newNonce()}\", algorithm=MD5, qop=\"auth\"",
        "Basic realm=\"$realm\"",
    )

    /** RTSP clients often don't handle qop; offer a plain digest challenge first. */
    fun rtspChallenges(): List<String> = listOf(
        "Digest realm=\"$realm\", nonce=\"${newNonce()}\"",
        "Basic realm=\"$realm\"",
    )

    fun check(method: String, authorization: String?): Boolean {
        val c = credentials() ?: return true
        if (authorization == null) return false
        val auth = authorization.trim()
        return when {
            auth.startsWith("Basic ", ignoreCase = true) -> {
                val decoded = runCatching { String(Base64.getDecoder().decode(auth.substring(6).trim())) }.getOrNull()
                decoded == "${c.username}:${c.password}"
            }
            auth.startsWith("Digest ", ignoreCase = true) -> checkDigest(method, parseParams(auth.substring(7)), c)
            else -> false
        }
    }

    private fun checkDigest(method: String, p: Map<String, String>, c: Credentials): Boolean {
        val username = p["username"] ?: return false
        val nonce = p["nonce"] ?: return false
        val uri = p["uri"] ?: return false
        val response = p["response"] ?: return false
        if (username != c.username || !validNonce(nonce)) return false
        val ha1 = md5("${c.username}:${p["realm"] ?: realm}:${c.password}")
        val ha2 = md5("$method:$uri")
        val qop = p["qop"]
        val expected = if (qop != null) {
            md5("$ha1:$nonce:${p["nc"] ?: ""}:${p["cnonce"] ?: ""}:$qop:$ha2")
        } else {
            md5("$ha1:$nonce:$ha2")
        }
        return expected.equals(response, ignoreCase = true)
    }

    private fun newNonce(): String {
        val ts = java.lang.Long.toHexString(System.currentTimeMillis())
        return ts + mac(ts)
    }

    private fun validNonce(nonce: String): Boolean {
        if (nonce.length <= 32) return false
        val ts = nonce.substring(0, nonce.length - 32)
        return mac(ts) == nonce.substring(nonce.length - 32)
    }

    private fun mac(s: String): String {
        val md = MessageDigest.getInstance("MD5")
        md.update(secret)
        md.update(s.toByteArray())
        return md.digest().toHex()
    }

    companion object {
        fun md5(s: String): String = MessageDigest.getInstance("MD5").digest(s.toByteArray()).toHex()

        fun parseParams(s: String): Map<String, String> {
            val out = HashMap<String, String>()
            val re = Regex("""(\w+)\s*=\s*(?:"([^"]*)"|([^,\s]+))""")
            for (m in re.findAll(s)) {
                out[m.groupValues[1].lowercase()] = m.groupValues[2].ifEmpty { m.groupValues[3] }
            }
            return out
        }

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
