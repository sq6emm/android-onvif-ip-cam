package pl.arhea.onvifipcam.onvif

import pl.arhea.onvifipcam.onvif.Soap.esc
import pl.arhea.onvifipcam.rtsp.Logger
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** WS-Discovery target service: answers Probe on 239.255.255.250:3702 and announces Hello/Bye. */
class WsDiscovery(
    private val device: OnvifDevice,
    /** Our IPv4 addresses, used to build XAddrs. */
    private val addresses: () -> List<String>,
    private val logger: Logger,
) {
    private var socket: MulticastSocket? = null
    @Volatile private var running = false
    private val instanceId = System.currentTimeMillis() / 1000
    private val messageNumber = AtomicInteger(1)
    private val group = InetAddress.getByName(GROUP)

    fun start() {
        val s = MulticastSocket(null)
        s.reuseAddress = true
        s.bind(InetSocketAddress(PORT))
        s.timeToLive = 4
        joinAll(s)
        socket = s
        running = true
        thread(name = "ws-discovery", isDaemon = true) { loop(s) }
        send(s, hello(), InetSocketAddress(group, PORT))
        logger.log("WS-Discovery listening on $GROUP:$PORT")
    }

    fun stop() {
        val s = socket ?: return
        running = false
        runCatching { send(s, bye(), InetSocketAddress(group, PORT)) }
        s.close()
        socket = null
    }

    private fun joinAll(s: MulticastSocket) {
        var joined = 0
        runCatching {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback || !ni.supportsMulticast()) continue
                if (ni.inetAddresses.toList().none { it is Inet4Address }) continue
                runCatching { s.joinGroup(InetSocketAddress(group, PORT), ni); joined++ }
            }
        }
        if (joined == 0) runCatching { @Suppress("DEPRECATION") s.joinGroup(group) }
    }

    private fun loop(s: MulticastSocket) {
        val buf = ByteArray(65535)
        while (running) {
            val pkt = DatagramPacket(buf, buf.size)
            try {
                s.receive(pkt)
            } catch (e: Exception) {
                if (running) logger.log("WS-Discovery receive failed: $e")
                break
            }
            try {
                val xml = Xml.parse(pkt.data.copyOf(pkt.length))
                val header = xml.child("Header")
                val body = xml.child("Body")?.children()?.firstOrNull() ?: continue
                if (body.name != "Probe") continue
                val types = body.childText("Types") ?: ""
                if (types.isNotEmpty() && !types.contains("NetworkVideoTransmitter") && !types.contains("Device")) continue
                val messageId = header?.childText("MessageID") ?: continue
                val reply = probeMatches(messageId, pkt.address)
                send(s, reply, InetSocketAddress(pkt.address, pkt.port))
            } catch (e: Exception) {
                // Not a SOAP message we understand.
            }
        }
    }

    private fun send(s: MulticastSocket, msg: String, to: InetSocketAddress) {
        val b = msg.toByteArray()
        s.send(DatagramPacket(b, b.size, to))
    }

    private fun xaddrs(peer: InetAddress?): String {
        val all = addresses()
        val peerPrefix = peer?.hostAddress?.substringBeforeLast('.')
        val ordered = all.filter { peerPrefix != null && it.substringBeforeLast('.') == peerPrefix }.ifEmpty { all }
        return ordered.joinToString(" ") { "http://$it:${device.httpPort}/onvif/${Paths.DEVICE}" }
    }

    private fun scopes() = DeviceService.scopes(device).joinToString(" ") { esc(it.second) }

    private fun envelope(header: String, body: String) =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:a=\"$WSA\" xmlns:d=\"$WSD\" " +
            "xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\" xmlns:tds=\"${Ns.TDS}\">" +
            "<s:Header>$header<d:AppSequence InstanceId=\"$instanceId\" MessageNumber=\"${messageNumber.getAndIncrement()}\"/></s:Header>" +
            "<s:Body>$body</s:Body></s:Envelope>"

    private fun endpoint() = "<a:EndpointReference><a:Address>urn:uuid:${device.uuid}</a:Address></a:EndpointReference>"

    private fun probeMatches(relatesTo: String, peer: InetAddress) = envelope(
        "<a:MessageID>urn:uuid:${UUID.randomUUID()}</a:MessageID><a:RelatesTo>${esc(relatesTo)}</a:RelatesTo>" +
            "<a:To>$WSA/role/anonymous</a:To><a:Action>$WSD/ProbeMatches</a:Action>",
        "<d:ProbeMatches><d:ProbeMatch>${endpoint()}<d:Types>dn:NetworkVideoTransmitter tds:Device</d:Types>" +
            "<d:Scopes>${scopes()}</d:Scopes><d:XAddrs>${xaddrs(peer)}</d:XAddrs>" +
            "<d:MetadataVersion>1</d:MetadataVersion></d:ProbeMatch></d:ProbeMatches>",
    )

    private fun hello() = envelope(
        "<a:MessageID>urn:uuid:${UUID.randomUUID()}</a:MessageID><a:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</a:To>" +
            "<a:Action>$WSD/Hello</a:Action>",
        "<d:Hello>${endpoint()}<d:Types>dn:NetworkVideoTransmitter tds:Device</d:Types><d:Scopes>${scopes()}</d:Scopes>" +
            "<d:XAddrs>${xaddrs(null)}</d:XAddrs><d:MetadataVersion>1</d:MetadataVersion></d:Hello>",
    )

    private fun bye() = envelope(
        "<a:MessageID>urn:uuid:${UUID.randomUUID()}</a:MessageID><a:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</a:To>" +
            "<a:Action>$WSD/Bye</a:Action>",
        "<d:Bye>${endpoint()}</d:Bye>",
    )

    companion object {
        const val GROUP = "239.255.255.250"
        const val PORT = 3702
        private const val WSA = "http://schemas.xmlsoap.org/ws/2004/08/addressing"
        private const val WSD = "http://schemas.xmlsoap.org/ws/2005/04/discovery"
    }
}
