package pl.arhea.onvifipcam.onvif

import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WsSecurityTest {
    private fun envelope(user: String, digest: String, nonce: String, created: String) = """
        <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope">
          <s:Header><Security xmlns="${Ns.WSSE}"><UsernameToken>
            <Username>$user</Username>
            <Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">$digest</Password>
            <Nonce>$nonce</Nonce><Created>$created</Created>
          </UsernameToken></Security></s:Header>
          <s:Body><GetProfiles xmlns="${Ns.TRT}"/></s:Body>
        </s:Envelope>""".trimIndent()

    @Test
    fun verifiesPasswordDigest() {
        val nonce = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val created = "2026-10-21T10:00:00Z"
        val sha = MessageDigest.getInstance("SHA-1")
        sha.update(nonce); sha.update(created.toByteArray()); sha.update("secret".toByteArray())
        val digest = Base64.getEncoder().encodeToString(sha.digest())
        val b64Nonce = Base64.getEncoder().encodeToString(nonce)
        val req = SoapRequest(Xml.parse(envelope("admin", digest, b64Nonce, created).toByteArray()))
        assertEquals("GetProfiles", req.opName)
        assertEquals(Ns.TRT, req.opNs)
        assertEquals(true, WsSecurity.verify(req.header, "admin", "secret"))
        assertEquals(false, WsSecurity.verify(req.header, "admin", "other"))
    }

    @Test
    fun noTokenMeansNull() {
        val req = SoapRequest(Xml.parse("<s:Envelope xmlns:s=\"${Ns.SOAP12}\"><s:Body><X/></s:Body></s:Envelope>".toByteArray()))
        assertNull(WsSecurity.verify(req.header, "admin", "secret"))
    }

    @Test
    fun parsesDurations() {
        assertEquals(60, Time.duration("PT60S")!!.seconds)
        assertEquals(600, Time.duration("PT10M")!!.seconds)
    }
}
