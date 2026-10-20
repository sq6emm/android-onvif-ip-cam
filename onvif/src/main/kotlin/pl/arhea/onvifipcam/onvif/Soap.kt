package pl.arhea.onvifipcam.onvif

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory

object Ns {
    const val SOAP12 = "http://www.w3.org/2003/05/soap-envelope"
    const val SOAP11 = "http://schemas.xmlsoap.org/soap/envelope/"
    const val TT = "http://www.onvif.org/ver10/schema"
    const val TDS = "http://www.onvif.org/ver10/device/wsdl"
    const val TRT = "http://www.onvif.org/ver10/media/wsdl"
    const val TR2 = "http://www.onvif.org/ver20/media/wsdl"
    const val TPTZ = "http://www.onvif.org/ver20/ptz/wsdl"
    const val TIMG = "http://www.onvif.org/ver20/imaging/wsdl"
    const val TEV = "http://www.onvif.org/ver10/events/wsdl"
    const val TAN = "http://www.onvif.org/ver20/analytics/wsdl"
    const val WSNT = "http://docs.oasis-open.org/wsn/b-2"
    const val WSA = "http://www.w3.org/2005/08/addressing"
    const val WSSE = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"

    /** Namespace declarations put on every response envelope. */
    val DECLS = listOf(
        "tt" to TT, "tds" to TDS, "trt" to TRT, "tr2" to TR2, "tptz" to TPTZ, "timg" to TIMG, "tev" to TEV, "tan" to TAN,
        "wsnt" to WSNT, "wsa" to WSA, "tns1" to "http://www.onvif.org/ver10/topics",
        "wstop" to "http://docs.oasis-open.org/wsn/t-1", "ter" to "http://www.onvif.org/ver10/error",
        "xsd" to "http://www.w3.org/2001/XMLSchema", "xsi" to "http://www.w3.org/2001/XMLSchema-instance",
    ).joinToString(" ") { (p, u) -> "xmlns:$p=\"$u\"" }
}

/** Thin wrapper over a DOM element with namespace-agnostic child lookup by local name. */
class Xml(val el: Element) {
    val name: String get() = el.localName ?: el.nodeName.substringAfter(':')
    val ns: String get() = el.namespaceURI ?: ""

    fun children(): List<Xml> {
        val out = ArrayList<Xml>()
        var n = el.firstChild
        while (n != null) {
            if (n.nodeType == Node.ELEMENT_NODE) out.add(Xml(n as Element))
            n = n.nextSibling
        }
        return out
    }

    fun children(name: String) = children().filter { it.name == name }
    fun child(name: String): Xml? = children().firstOrNull { it.name == name }
    fun path(vararg names: String): Xml? {
        var cur: Xml? = this
        for (n in names) cur = cur?.child(n) ?: return null
        return cur
    }

    fun text(): String = el.textContent?.trim() ?: ""
    fun attr(name: String): String? = el.getAttribute(name).ifEmpty { null }
    fun childText(name: String): String? = child(name)?.text()
    fun float(name: String): Float? = child(name)?.text()?.toFloatOrNull()
    fun attrFloat(name: String): Float? = attr(name)?.toFloatOrNull()

    companion object {
        fun parse(bytes: ByteArray): Xml {
            val f = DocumentBuilderFactory.newInstance()
            f.isNamespaceAware = true
            runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { f.isExpandEntityReferences = false }
            val doc = f.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
            return Xml(doc.documentElement)
        }
    }
}

class SoapRequest(val envelope: Xml) {
    val envNs: String = envelope.ns.ifEmpty { Ns.SOAP12 }
    val header: Xml? = envelope.child("Header")
    val body: Xml? = envelope.child("Body")
    /** The operation element (first child of Body). */
    val op: Xml? = body?.children()?.firstOrNull()
    val opName: String get() = op?.name ?: ""
    val opNs: String get() = op?.ns ?: ""
    val wsaTo: String? get() = header?.childText("To")
}

class SoapFault(val code: String, val subcode: String, val reason: String, val httpStatus: Int = 400) : Exception(reason)

object Soap {
    fun esc(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '<' -> append("&lt;"); '>' -> append("&gt;"); '&' -> append("&amp;")
            '"' -> append("&quot;"); '\'' -> append("&apos;")
            else -> append(c)
        }
    }

    fun envelope(envNs: String, body: String, header: String = ""): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<s:Envelope xmlns:s=\"$envNs\" ${Ns.DECLS}>" +
            (if (header.isNotEmpty()) "<s:Header>$header</s:Header>" else "") +
            "<s:Body>$body</s:Body></s:Envelope>"

    fun fault(envNs: String, f: SoapFault): String {
        val body = if (envNs == Ns.SOAP11) {
            "<s:Fault><faultcode>s:${if (f.code == "Sender") "Client" else "Server"}</faultcode>" +
                "<faultstring>${esc(f.reason)}</faultstring></s:Fault>"
        } else {
            "<s:Fault><s:Code><s:Value>s:${f.code}</s:Value><s:Subcode><s:Value>${f.subcode}</s:Value></s:Subcode></s:Code>" +
                "<s:Reason><s:Text xml:lang=\"en\">${esc(f.reason)}</s:Text></s:Reason></s:Fault>"
        }
        return envelope(envNs, body)
    }

    fun contentType(envNs: String) =
        if (envNs == Ns.SOAP11) "text/xml; charset=utf-8" else "application/soap+xml; charset=utf-8"

    fun notAuthorized() = SoapFault("Sender", "ter:NotAuthorized", "Sender not authorized")
    fun actionNotSupported(op: String) = SoapFault("Receiver", "ter:ActionNotSupported", "Optional action not implemented: $op", 400)
    fun invalidArg(sub: String, reason: String) = SoapFault("Sender", "ter:InvalidArgVal", "$reason ($sub)")
    fun noProfile() = SoapFault("Sender", "ter:NoProfile", "Profile token does not exist")
}

/** WS-Security UsernameToken check (PasswordDigest or PasswordText). */
object WsSecurity {
    fun verify(header: Xml?, username: String, password: String): Boolean? {
        val token = header?.path("Security", "UsernameToken") ?: return null
        val user = token.childText("Username") ?: return false
        val pw = token.child("Password") ?: return false
        if (user != username) return false
        val type = pw.attr("Type") ?: ""
        return if (type.endsWith("#PasswordText")) {
            pw.text() == password
        } else {
            val nonce = runCatching { Base64.getDecoder().decode(token.childText("Nonce") ?: "") }.getOrNull() ?: return false
            val created = token.childText("Created") ?: ""
            val sha = MessageDigest.getInstance("SHA-1")
            sha.update(nonce)
            sha.update(created.toByteArray())
            sha.update(password.toByteArray())
            Base64.getEncoder().encodeToString(sha.digest()) == pw.text()
        }
    }
}

object Time {
    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")

    fun iso(instant: Instant = Instant.now()): String = ISO.format(instant.atOffset(ZoneOffset.UTC))

    /** Parses an xs:duration like PT60S / PT1M30S / P1D, or returns null. */
    fun duration(s: String?): Duration? = s?.trim()?.let { runCatching { Duration.parse(it) }.getOrNull() }

    /** InitialTerminationTime/TerminationTime may be a duration or an absolute dateTime. */
    fun termination(s: String?, default: Duration): Instant {
        val now = Instant.now()
        if (s.isNullOrBlank()) return now.plus(default)
        duration(s)?.let { return now.plus(it) }
        return runCatching { OffsetDateTime.parse(s.trim()).toInstant() }.getOrElse { now.plus(default) }
    }
}
