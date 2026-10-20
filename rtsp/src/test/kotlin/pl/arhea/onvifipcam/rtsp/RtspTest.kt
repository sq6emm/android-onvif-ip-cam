package pl.arhea.onvifipcam.rtsp

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RtspTest {
    @Test
    fun splitsAnnexBWithThreeAndFourByteStartCodes() {
        val data = byteArrayOf(0, 0, 0, 1, 0x67, 1, 2, 0, 0, 1, 0x68, 3, 0, 0, 0, 1, 0x65, 9, 9)
        val nals = Nal.split(data)
        assertEquals(3, nals.size)
        assertContentEquals(byteArrayOf(0x67, 1, 2), nals[0])
        assertContentEquals(byteArrayOf(0x68, 3), nals[1])
        assertContentEquals(byteArrayOf(0x65, 9, 9), nals[2])
    }

    @Test
    fun h264FuAReassemblesToOriginalNal() {
        val nal = ByteArray(5000) { (it % 251).toByte() }.also { it[0] = 0x65 }
        val frame = MediaFrame(byteArrayOf(0, 0, 0, 1) + nal, 1_000_000, true)
        val out = ByteArrayOutputStream()
        val markers = ArrayList<Boolean>()
        H264Packetizer(96, 1234).packetize(frame) { buf, len ->
            assertTrue(len <= RtpPacketizer.MAX_PACKET)
            markers.add(buf[1].toInt() and 0x80 != 0)
            val fuHeader = buf[13].toInt()
            if (fuHeader and 0x80 != 0) out.write(((buf[12].toInt() and 0xe0) or (fuHeader and 0x1f)))
            out.write(buf, 14, len - 14)
        }
        assertContentEquals(nal, out.toByteArray())
        assertEquals(listOf(true), markers.takeLast(1))
        assertFalse(markers.dropLast(1).any { it })
    }

    @Test
    fun digestAuthAcceptsValidResponse() {
        val auth = DigestAuth("realm") { Credentials("admin", "secret") }
        val nonce = Regex("nonce=\"([^\"]+)\"").find(auth.rtspChallenges()[0])!!.groupValues[1]
        val uri = "rtsp://cam/stream1"
        val ha1 = DigestAuth.md5("admin:realm:secret")
        val ha2 = DigestAuth.md5("DESCRIBE:$uri")
        val resp = DigestAuth.md5("$ha1:$nonce:$ha2")
        val header = "Digest username=\"admin\", realm=\"realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$resp\""
        assertTrue(auth.check("DESCRIBE", header))
        assertFalse(auth.check("DESCRIBE", header.replace(resp, "0".repeat(32))))
        assertFalse(auth.check("DESCRIBE", header.replace(nonce, "deadbeef" + "0".repeat(32))))
        assertTrue(auth.check("DESCRIBE", "Basic " + java.util.Base64.getEncoder().encodeToString("admin:secret".toByteArray())))
    }

    @Test
    fun aacConfigFor44kMono() {
        assertContentEquals(byteArrayOf(0x12, 0x08), AudioTrackInfo.aacLcConfig(44100, 1))
    }
}
