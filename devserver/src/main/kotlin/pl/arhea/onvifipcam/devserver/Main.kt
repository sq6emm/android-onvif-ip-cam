package pl.arhea.onvifipcam.devserver

import pl.arhea.onvifipcam.onvif.EventHub
import pl.arhea.onvifipcam.onvif.OnvifServer
import pl.arhea.onvifipcam.onvif.PresetStore
import pl.arhea.onvifipcam.onvif.WsDiscovery
import pl.arhea.onvifipcam.rtsp.Credentials
import pl.arhea.onvifipcam.rtsp.DigestAuth
import pl.arhea.onvifipcam.rtsp.Logger
import pl.arhea.onvifipcam.rtsp.RtspServer
import pl.arhea.onvifipcam.rtsp.StreamEndpoint
import pl.arhea.onvifipcam.rtsp.VideoCodec
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.concurrent.thread

/**
 * JVM test harness: serves files from [dataDir] through the same RTSP/ONVIF code the app uses.
 * Env: DATA (dir), USER/PASS (auth, empty = off), MAIN_CODEC (h264|h265).
 */
fun main() {
    val dataDir = File(System.getenv("DATA") ?: "testdata")
    val user = System.getenv("CAM_USER") ?: ""
    val pass = System.getenv("CAM_PASS") ?: ""
    val mainCodec = if (System.getenv("MAIN_CODEC") == "h265") VideoCodec.H265 else VideoCodec.H264
    val auth = DigestAuth("OnvifIpCam") { if (user.isEmpty()) null else Credentials(user, pass) }

    val main = StreamEndpoint("stream1")
    val sub = StreamEndpoint("stream2")
    val rtsp = RtspServer(8554, auth, Logger.STDOUT)
    rtsp.addEndpoint(main)
    rtsp.addEndpoint(sub)
    rtsp.start()

    val mainFile = if (mainCodec == VideoCodec.H265) "main.h265" else "main.h264"
    VideoFileSource(File(dataDir, mainFile), mainCodec, 25, 1920, 1080).start(main)
    VideoFileSource(File(dataDir, "sub.h264"), VideoCodec.H264, 25, 640, 360).start(sub)
    AudioFileSource(File(dataDir, "audio.aac")).start(listOf(main, sub))

    val addresses = {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().map { it.hostAddress }
    }
    val device = FakeDevice(mainCodec, dataDir)
    val onvif = OnvifServer(device, auth, EventHub(Logger.STDOUT), PresetStore.Memory(), addresses, Logger.STDOUT)
    onvif.start()
    onvif.publishMotion(false)
    WsDiscovery(device, addresses, Logger.STDOUT).start()

    // Toggle motion every MOTION_PERIOD seconds so event subscribers have something to receive.
    val period = (System.getenv("MOTION_PERIOD") ?: "15").toLong()
    thread(isDaemon = true) {
        var active = false
        while (true) {
            Thread.sleep(period * 1000)
            active = !active
            println("motion -> $active")
            onvif.publishMotion(active)
        }
    }

    println("devserver running")
    Thread.currentThread().join()
}
