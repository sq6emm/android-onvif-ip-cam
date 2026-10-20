package pl.arhea.onvifipcam.onvif

import pl.arhea.onvifipcam.onvif.Soap.esc
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.ZoneId
import java.time.ZonedDateTime

/** Per-request context: which host/port the client used, for building URLs it can reach. */
class Ctx(val req: HttpRequest, val soap: SoapRequest, val device: OnvifDevice) {
    val host: String = run {
        val h = req.header("host")?.let { hostOnly(it) }
        val raw = if (!h.isNullOrEmpty()) h else req.localAddress
        if (raw.contains(':') && !raw.startsWith("[")) "[$raw]" else raw
    }
    val httpPort: Int = req.header("host")?.let { portOf(it) } ?: device.httpPort
    val base = "http://$host:$httpPort"
    val op: Xml get() = soap.op!!

    fun xaddr(service: String) = "$base/onvif/$service"
    fun rtspUri(path: String) = "rtsp://$host:${device.rtspPort}/$path"
    fun snapshotUri(profile: String) = "$base/onvif/snapshot.jpg?profile=$profile"

    private fun hostOnly(h: String): String =
        if (h.startsWith("[")) h.substringBefore(']').removePrefix("[") else h.substringBefore(':')

    private fun portOf(h: String): Int? {
        val rest = if (h.startsWith("[")) h.substringAfter("]", "") else h
        return rest.substringAfter(':', "").toIntOrNull()
    }
}

object Paths {
    const val DEVICE = "device_service"
    const val MEDIA = "media_service"
    const val MEDIA2 = "media2_service"
    const val PTZ = "ptz_service"
    const val IMAGING = "imaging_service"
    const val EVENTS = "events_service"
}

class DeviceService(private val addresses: () -> List<String>) {

    fun handle(c: Ctx): String? = when (c.soap.opName) {
        "GetSystemDateAndTime" -> getSystemDateAndTime()
        "GetDeviceInformation" -> c.device.info.let {
            "<tds:GetDeviceInformationResponse><tds:Manufacturer>${esc(it.manufacturer)}</tds:Manufacturer>" +
                "<tds:Model>${esc(it.model)}</tds:Model><tds:FirmwareVersion>${esc(it.firmwareVersion)}</tds:FirmwareVersion>" +
                "<tds:SerialNumber>${esc(it.serialNumber)}</tds:SerialNumber><tds:HardwareId>${esc(it.hardwareId)}</tds:HardwareId>" +
                "</tds:GetDeviceInformationResponse>"
        }
        "GetCapabilities" -> getCapabilities(c)
        "GetServices" -> getServices(c)
        "GetServiceCapabilities" -> "<tds:GetServiceCapabilitiesResponse>${serviceCapabilities()}</tds:GetServiceCapabilitiesResponse>"
        "GetScopes" -> "<tds:GetScopesResponse>" +
            scopes(c.device).joinToString("") { (fixed, s) ->
                "<tds:Scopes><tt:ScopeDef>${if (fixed) "Fixed" else "Configurable"}</tt:ScopeDef><tt:ScopeItem>${esc(s)}</tt:ScopeItem></tds:Scopes>"
            } + "</tds:GetScopesResponse>"
        "GetHostname" -> "<tds:GetHostnameResponse><tds:HostnameInformation><tt:FromDHCP>false</tt:FromDHCP>" +
            "<tt:Name>${esc(hostname(c.device.name))}</tt:Name></tds:HostnameInformation></tds:GetHostnameResponse>"
        "GetNetworkInterfaces" -> getNetworkInterfaces(c)
        "GetNetworkProtocols" -> "<tds:GetNetworkProtocolsResponse>" +
            "<tds:NetworkProtocols><tt:Name>HTTP</tt:Name><tt:Enabled>true</tt:Enabled><tt:Port>${c.device.httpPort}</tt:Port></tds:NetworkProtocols>" +
            "<tds:NetworkProtocols><tt:Name>RTSP</tt:Name><tt:Enabled>true</tt:Enabled><tt:Port>${c.device.rtspPort}</tt:Port></tds:NetworkProtocols>" +
            "</tds:GetNetworkProtocolsResponse>"
        "GetDNS" -> "<tds:GetDNSResponse><tds:DNSInformation><tt:FromDHCP>true</tt:FromDHCP></tds:DNSInformation></tds:GetDNSResponse>"
        "GetNTP" -> "<tds:GetNTPResponse><tds:NTPInformation><tt:FromDHCP>false</tt:FromDHCP>" +
            "<tt:NTPManual><tt:Type>DNS</tt:Type><tt:DNSname>time.android.com</tt:DNSname></tt:NTPManual></tds:NTPInformation></tds:GetNTPResponse>"
        "GetNetworkDefaultGateway" -> "<tds:GetNetworkDefaultGatewayResponse><tds:NetworkGateway/></tds:GetNetworkDefaultGatewayResponse>"
        "GetDiscoveryMode" -> "<tds:GetDiscoveryModeResponse><tds:DiscoveryMode>Discoverable</tds:DiscoveryMode></tds:GetDiscoveryModeResponse>"
        "GetUsers" -> "<tds:GetUsersResponse><tds:User><tt:Username>admin</tt:Username><tt:UserLevel>Administrator</tt:UserLevel></tds:User></tds:GetUsersResponse>"
        "GetWsdlUrl" -> "<tds:GetWsdlUrlResponse><tds:WsdlUrl>http://www.onvif.org/Documents/Specifications.aspx</tds:WsdlUrl></tds:GetWsdlUrlResponse>"
        "GetEndpointReference" -> "<tds:GetEndpointReferenceResponse><tds:GUID>urn:uuid:${c.device.uuid}</tds:GUID></tds:GetEndpointReferenceResponse>"
        "GetRelayOutputs" -> "<tds:GetRelayOutputsResponse/>"
        "GetZeroConfiguration" -> "<tds:GetZeroConfigurationResponse><tds:ZeroConfiguration><tt:InterfaceToken>eth0</tt:InterfaceToken>" +
            "<tt:Enabled>false</tt:Enabled></tds:ZeroConfiguration></tds:GetZeroConfigurationResponse>"
        "SetSystemDateAndTime", "SetNTP", "SetDNS", "SetHostname", "SetScopes", "AddScopes", "SetDiscoveryMode" ->
            "<tds:${c.soap.opName}Response/>"
        "SystemReboot" -> "<tds:SystemRebootResponse><tds:Message>Rebooting is not supported; restart the app instead</tds:Message></tds:SystemRebootResponse>"
        else -> null
    }

    fun serviceCapabilities() =
        "<tds:Capabilities><tds:Network IPFilter=\"false\" ZeroConfiguration=\"false\" IPVersion6=\"false\" DynDNS=\"false\" " +
            "Dot11Configuration=\"false\" HostnameFromDHCP=\"false\" NTP=\"1\"/>" +
            "<tds:Security TLS1.0=\"false\" TLS1.1=\"false\" TLS1.2=\"false\" OnboardKeyGeneration=\"false\" AccessPolicyConfig=\"false\" " +
            "DefaultAccessPolicy=\"false\" Dot1X=\"false\" RemoteUserHandling=\"false\" X.509Token=\"false\" SAMLToken=\"false\" " +
            "KerberosToken=\"false\" UsernameToken=\"true\" HttpDigest=\"true\" RELToken=\"false\"/>" +
            "<tds:System DiscoveryResolve=\"true\" DiscoveryBye=\"true\" RemoteDiscovery=\"false\" SystemBackup=\"false\" " +
            "SystemLogging=\"false\" FirmwareUpgrade=\"false\" HttpFirmwareUpgrade=\"false\" HttpSystemBackup=\"false\" " +
            "HttpSystemLogging=\"false\" HttpSupportInformation=\"false\"/></tds:Capabilities>"

    private fun getSystemDateAndTime(): String {
        val now = ZonedDateTime.now()
        val utc = now.withZoneSameInstant(ZoneId.of("UTC"))
        fun dt(t: ZonedDateTime) =
            "<tt:Time><tt:Hour>${t.hour}</tt:Hour><tt:Minute>${t.minute}</tt:Minute><tt:Second>${t.second}</tt:Second></tt:Time>" +
                "<tt:Date><tt:Year>${t.year}</tt:Year><tt:Month>${t.monthValue}</tt:Month><tt:Day>${t.dayOfMonth}</tt:Day></tt:Date>"
        val dst = now.zone.rules.isDaylightSavings(now.toInstant())
        return "<tds:GetSystemDateAndTimeResponse><tds:SystemDateAndTime>" +
            "<tt:DateTimeType>NTP</tt:DateTimeType><tt:DaylightSavings>$dst</tt:DaylightSavings>" +
            "<tt:TimeZone><tt:TZ>${posixTz(now)}</tt:TZ></tt:TimeZone>" +
            "<tt:UTCDateTime>${dt(utc)}</tt:UTCDateTime><tt:LocalDateTime>${dt(now)}</tt:LocalDateTime>" +
            "</tds:SystemDateAndTime></tds:GetSystemDateAndTimeResponse>"
    }

    /** Fixed-offset POSIX TZ for the current offset, e.g. CET-1 -> "UTC-01:00" style "GMT-1". */
    private fun posixTz(now: ZonedDateTime): String {
        val secs = now.offset.totalSeconds
        if (secs == 0) return "UTC0"
        val sign = if (secs > 0) "-" else "+" // POSIX offsets are inverted
        val a = kotlin.math.abs(secs)
        val h = a / 3600
        val m = (a % 3600) / 60
        return "LOC$sign$h" + if (m != 0) ":%02d".format(m) else ""
    }

    private fun getCapabilities(c: Ctx): String {
        val cats = c.op.children("Category").map { it.text() }.ifEmpty { listOf("All") }
        fun want(cat: String) = "All" in cats || cat in cats
        val sb = StringBuilder("<tds:GetCapabilitiesResponse><tds:Capabilities>")
        if (want("Device")) {
            sb.append(
                "<tt:Device><tt:XAddr>${c.xaddr(Paths.DEVICE)}</tt:XAddr>" +
                    "<tt:Network><tt:IPFilter>false</tt:IPFilter><tt:ZeroConfiguration>false</tt:ZeroConfiguration>" +
                    "<tt:IPVersion6>false</tt:IPVersion6><tt:DynDNS>false</tt:DynDNS></tt:Network>" +
                    "<tt:System><tt:DiscoveryResolve>true</tt:DiscoveryResolve><tt:DiscoveryBye>true</tt:DiscoveryBye>" +
                    "<tt:RemoteDiscovery>false</tt:RemoteDiscovery><tt:SystemBackup>false</tt:SystemBackup>" +
                    "<tt:SystemLogging>false</tt:SystemLogging><tt:FirmwareUpgrade>false</tt:FirmwareUpgrade>" +
                    "<tt:SupportedVersions><tt:Major>2</tt:Major><tt:Minor>40</tt:Minor></tt:SupportedVersions></tt:System>" +
                    "<tt:IO><tt:InputConnectors>0</tt:InputConnectors><tt:RelayOutputs>0</tt:RelayOutputs></tt:IO>" +
                    "<tt:Security><tt:TLS1.1>false</tt:TLS1.1><tt:TLS1.2>false</tt:TLS1.2>" +
                    "<tt:OnboardKeyGeneration>false</tt:OnboardKeyGeneration><tt:AccessPolicyConfig>false</tt:AccessPolicyConfig>" +
                    "<tt:X.509Token>false</tt:X.509Token><tt:SAMLToken>false</tt:SAMLToken>" +
                    "<tt:KerberosToken>false</tt:KerberosToken><tt:RELToken>false</tt:RELToken></tt:Security></tt:Device>",
            )
        }
        if (want("Events")) {
            sb.append(
                "<tt:Events><tt:XAddr>${c.xaddr(Paths.EVENTS)}</tt:XAddr><tt:WSSubscriptionPolicySupport>false</tt:WSSubscriptionPolicySupport>" +
                    "<tt:WSPullPointSupport>true</tt:WSPullPointSupport>" +
                    "<tt:WSPausableSubscriptionManagerInterfaceSupport>false</tt:WSPausableSubscriptionManagerInterfaceSupport></tt:Events>",
            )
        }
        if (want("Imaging") && c.device.imaging != null) {
            sb.append("<tt:Imaging><tt:XAddr>${c.xaddr(Paths.IMAGING)}</tt:XAddr></tt:Imaging>")
        }
        if (want("Media")) {
            sb.append(
                "<tt:Media><tt:XAddr>${c.xaddr(Paths.MEDIA)}</tt:XAddr><tt:StreamingCapabilities>" +
                    "<tt:RTPMulticast>false</tt:RTPMulticast><tt:RTP_TCP>true</tt:RTP_TCP><tt:RTP_RTSP_TCP>true</tt:RTP_RTSP_TCP>" +
                    "</tt:StreamingCapabilities><tt:Extension><tt:ProfileCapabilities>" +
                    "<tt:MaximumNumberOfProfiles>${c.device.profiles().size}</tt:MaximumNumberOfProfiles>" +
                    "</tt:ProfileCapabilities></tt:Extension></tt:Media>",
            )
        }
        if (want("PTZ") && c.device.ptz != null) {
            sb.append("<tt:PTZ><tt:XAddr>${c.xaddr(Paths.PTZ)}</tt:XAddr></tt:PTZ>")
        }
        sb.append("</tds:Capabilities></tds:GetCapabilitiesResponse>")
        return sb.toString()
    }

    private fun getServices(c: Ctx): String {
        val include = c.op.childText("IncludeCapability") == "true"
        fun svc(ns: String, path: String, major: Int, minor: Int, caps: String) =
            "<tds:Service><tds:Namespace>$ns</tds:Namespace><tds:XAddr>${c.xaddr(path)}</tds:XAddr>" +
                (if (include) "<tds:Capabilities>$caps</tds:Capabilities>" else "") +
                "<tds:Version><tt:Major>$major</tt:Major><tt:Minor>$minor</tt:Minor></tds:Version></tds:Service>"
        val sb = StringBuilder("<tds:GetServicesResponse>")
        sb.append(svc(Ns.TDS, Paths.DEVICE, 2, 40, serviceCapabilities()))
        sb.append(svc(Ns.TRT, Paths.MEDIA, 2, 40, MediaService.capabilities(c.device)))
        sb.append(svc(Ns.TR2, Paths.MEDIA2, 2, 40, Media2Service.capabilities(c.device)))
        sb.append(svc(Ns.TEV, Paths.EVENTS, 2, 40, EventService.capabilities()))
        if (c.device.ptz != null) sb.append(svc(Ns.TPTZ, Paths.PTZ, 2, 40, PtzService.capabilities()))
        if (c.device.imaging != null) sb.append(svc(Ns.TIMG, Paths.IMAGING, 2, 40, ImagingService.capabilities()))
        sb.append("</tds:GetServicesResponse>")
        return sb.toString()
    }

    private fun getNetworkInterfaces(c: Ctx): String {
        val ip = addresses().firstOrNull() ?: c.req.localAddress
        return "<tds:GetNetworkInterfacesResponse><tds:NetworkInterfaces token=\"eth0\"><tt:Enabled>true</tt:Enabled>" +
            "<tt:Info><tt:Name>eth0</tt:Name><tt:HwAddress>${pseudoMac(c.device.uuid)}</tt:HwAddress><tt:MTU>1500</tt:MTU></tt:Info>" +
            "<tt:IPv4><tt:Enabled>true</tt:Enabled><tt:Config><tt:Manual><tt:Address>$ip</tt:Address>" +
            "<tt:PrefixLength>24</tt:PrefixLength></tt:Manual><tt:DHCP>true</tt:DHCP></tt:Config></tt:IPv4>" +
            "</tds:NetworkInterfaces></tds:GetNetworkInterfacesResponse>"
    }

    companion object {
        fun scopes(d: OnvifDevice): List<Pair<Boolean, String>> {
            val enc = { s: String -> URLEncoder.encode(s, "UTF-8").replace("+", "%20") }
            return listOfNotNull(
                true to "onvif://www.onvif.org/type/video_encoder",
                true to "onvif://www.onvif.org/type/audio_encoder",
                if (d.ptz != null) true to "onvif://www.onvif.org/type/ptz" else null,
                true to "onvif://www.onvif.org/type/Network_Video_Transmitter",
                true to "onvif://www.onvif.org/Profile/Streaming",
                true to "onvif://www.onvif.org/Profile/S",
                true to "onvif://www.onvif.org/Profile/T",
                false to "onvif://www.onvif.org/name/${enc(d.name)}",
                true to "onvif://www.onvif.org/hardware/${enc(d.info.model)}",
                false to "onvif://www.onvif.org/location/",
            )
        }

        /** Android hides the real MAC; derive a stable locally administered one from the device UUID. */
        fun pseudoMac(uuid: String): String {
            val h = MessageDigest.getInstance("MD5").digest(uuid.toByteArray())
            h[0] = ((h[0].toInt() and 0xfc) or 0x02).toByte()
            return (0 until 6).joinToString(":") { "%02x".format(h[it]) }
        }

        fun hostname(name: String) = name.replace(Regex("[^A-Za-z0-9-]"), "-").trim('-').ifEmpty { "ipcam" }
    }
}
