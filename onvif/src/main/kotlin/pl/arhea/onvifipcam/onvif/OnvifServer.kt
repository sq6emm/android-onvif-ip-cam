package pl.arhea.onvifipcam.onvif

import pl.arhea.onvifipcam.rtsp.DigestAuth
import pl.arhea.onvifipcam.rtsp.Logger

/**
 * ONVIF endpoint: SOAP over HTTP for all services plus the JPEG snapshot URL.
 * Operations are dispatched by the request element's namespace, so clients that post everything
 * to the device service URL still work.
 */
class OnvifServer(
    private val device: OnvifDevice,
    private val auth: DigestAuth,
    val events: EventHub,
    presets: PresetStore,
    private val addresses: () -> List<String>,
    private val logger: Logger,
) {
    private val http = HttpServer(device.httpPort, logger, ::handle)
    private val deviceService = DeviceService(addresses)
    private val media = MediaService()
    private val media2 = Media2Service()
    private val ptz = PtzService(presets)
    private val imaging = ImagingService()
    private val eventService = EventService(events)

    fun start() = http.start()

    fun stop() {
        http.stop()
        events.stop()
    }

    fun handle(req: HttpRequest): HttpResponse {
        if (req.path.endsWith("/snapshot.jpg") || req.path.endsWith("/snapshot")) return snapshot(req)
        if (req.method != "POST") {
            return HttpResponse.text(if (req.method == "GET") 200 else 405, "ONVIF device service. POST SOAP requests to /onvif/device_service\n")
        }
        val soap = try {
            SoapRequest(Xml.parse(req.body))
        } catch (e: Exception) {
            // HTTP Digest clients (curl --digest) probe with an empty body first and expect a challenge.
            if (auth.enabled && !auth.check("POST", req.header("authorization"))) {
                return HttpResponse(401, ByteArray(0), "text/plain", auth.challenges().map { "WWW-Authenticate" to it })
            }
            return HttpResponse.text(400, "Malformed SOAP request")
        }
        if (soap.op == null) return fault(soap, SoapFault("Sender", "ter:InvalidArgVal", "Empty body"))
        val ctx = Ctx(req, soap, device)

        if (soap.opName !in ANONYMOUS && auth.enabled) {
            val creds = auth.currentCredentials()!!
            when (WsSecurity.verify(soap.header, creds.username, creds.password)) {
                true -> {}
                false -> {
                    logger.log("ONVIF ${soap.opName}: WS-Security rejected from ${req.remoteAddress}")
                    return fault(soap, Soap.notAuthorized())
                }
                null -> if (!auth.check("POST", req.header("authorization"))) {
                    return HttpResponse(
                        401,
                        Soap.fault(soap.envNs, Soap.notAuthorized()).toByteArray(),
                        Soap.contentType(soap.envNs),
                        auth.challenges().map { "WWW-Authenticate" to it },
                    )
                }
            }
        }

        return try {
            val body = dispatch(ctx) ?: throw Soap.actionNotSupported(soap.opName)
            HttpResponse(200, Soap.envelope(soap.envNs, body).toByteArray(), Soap.contentType(soap.envNs))
        } catch (f: SoapFault) {
            if (f.subcode == "ter:ActionNotSupported") logger.log("ONVIF unsupported: {${soap.opNs}}${soap.opName}")
            fault(soap, f)
        }
    }

    private fun dispatch(c: Ctx): String? {
        val subId = Regex("/sub/([0-9a-f]+)").find(c.req.path)?.groupValues?.get(1)
            ?: c.soap.wsaTo?.let { Regex("/sub/([0-9a-f]+)").find(it)?.groupValues?.get(1) }
        return when (c.soap.opNs) {
            Ns.TDS -> deviceService.handle(c)
            Ns.TRT -> media.handle(c)
            Ns.TR2 -> media2.handle(c)
            Ns.TPTZ -> ptz.handle(c)
            Ns.TIMG -> imaging.handle(c)
            Ns.TEV, Ns.WSNT -> eventService.handle(c, subId)
            else -> null
        }
    }

    private fun fault(soap: SoapRequest, f: SoapFault) =
        HttpResponse(f.httpStatus, Soap.fault(soap.envNs, f).toByteArray(), Soap.contentType(soap.envNs))

    private fun snapshot(req: HttpRequest): HttpResponse {
        if (auth.enabled && !auth.check(req.method, req.header("authorization"))) {
            return HttpResponse(401, "Unauthorized".toByteArray(), "text/plain", auth.challenges().map { "WWW-Authenticate" to it })
        }
        val jpeg = device.snapshotJpeg() ?: return HttpResponse.text(503, "No frame available")
        return HttpResponse(200, jpeg, "image/jpeg", listOf("Cache-Control" to "no-cache"))
    }

    fun publishMotion(active: Boolean) {
        EventService.motionEvents(active).forEach { events.publish(it) }
    }

    companion object {
        /** Pre-auth operations per the ONVIF core spec (access class PRE_AUTH). */
        private val ANONYMOUS = setOf(
            "GetSystemDateAndTime", "GetWsdlUrl", "GetServices", "GetServiceCapabilities", "GetCapabilities",
            "GetHostname", "GetEndpointReference",
        )
    }
}
