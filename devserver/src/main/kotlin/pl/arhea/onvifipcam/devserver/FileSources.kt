package pl.arhea.onvifipcam.devserver

import pl.arhea.onvifipcam.rtsp.AudioTrackInfo
import pl.arhea.onvifipcam.rtsp.MediaFrame
import pl.arhea.onvifipcam.rtsp.Nal
import pl.arhea.onvifipcam.rtsp.StreamEndpoint
import pl.arhea.onvifipcam.rtsp.VideoCodec
import pl.arhea.onvifipcam.rtsp.VideoTrackInfo
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.concurrent.thread

/** Loops an Annex-B elementary stream (with AUDs) into an endpoint, paced at [fps]. */
class VideoFileSource(file: File, private val codec: VideoCodec, private val fps: Int, w: Int, h: Int) {
    private val accessUnits: List<Pair<ByteArray, Boolean>>
    val info: VideoTrackInfo

    init {
        val nals = Nal.split(file.readBytes())
        val config = ByteArrayOutputStream()
        val aus = ArrayList<Pair<ByteArray, Boolean>>()
        var cur = ByteArrayOutputStream()
        var key = false
        for (nal in nals) {
            val t = Nal.type(codec, nal)
            if (Nal.isParameterSet(codec, t)) {
                if (config.size() < 4096) { config.write(byteArrayOf(0, 0, 0, 1)); config.write(nal) }
                continue // the endpoint re-inserts parameter sets before keyframes
            }
            if (Nal.isAud(codec, t)) {
                if (cur.size() > 0) aus.add(cur.toByteArray() to key)
                cur = ByteArrayOutputStream(); key = false
                continue
            }
            if (Nal.isKeyframe(codec, t)) key = true
            cur.write(byteArrayOf(0, 0, 0, 1)); cur.write(nal)
        }
        if (cur.size() > 0) aus.add(cur.toByteArray() to key)
        accessUnits = aus
        info = VideoTrackInfo.fromConfig(codec, w, h, fps, config.toByteArray()) ?: error("no parameter sets in $file")
    }

    var forceKeyframe = false

    fun start(endpoint: StreamEndpoint) {
        endpoint.setVideoTrack(info)
        endpoint.keyframeRequester = { forceKeyframe = true }
        thread(isDaemon = true, name = "video-${endpoint.path}") {
            val period = 1_000_000_000L / fps
            var next = System.nanoTime()
            var i = 0
            while (true) {
                val (data, keyframe) = accessUnits[i]
                endpoint.pushVideo(MediaFrame(data, System.nanoTime() / 1000, keyframe))
                i = (i + 1) % accessUnits.size
                if (forceKeyframe) {
                    // Jump to the next keyframe so new clients start quickly, like an encoder IDR request.
                    forceKeyframe = false
                    while (!accessUnits[i].second) i = (i + 1) % accessUnits.size
                }
                next += period
                val sleep = next - System.nanoTime()
                if (sleep > 0) Thread.sleep(sleep / 1_000_000, (sleep % 1_000_000).toInt())
            }
        }
    }
}

/** Loops an ADTS AAC file. */
class AudioFileSource(file: File) {
    private val frames = ArrayList<ByteArray>()
    val info: AudioTrackInfo

    init {
        val d = file.readBytes()
        var p = 0
        var rate = 44100
        var ch = 1
        val rates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
        while (p + 7 < d.size) {
            if ((d[p].toInt() and 0xff) != 0xff || (d[p + 1].toInt() and 0xf0) != 0xf0) { p++; continue }
            val protectionAbsent = d[p + 1].toInt() and 1
            rate = rates[(d[p + 2].toInt() shr 2) and 0xf]
            ch = ((d[p + 2].toInt() and 1) shl 2) or ((d[p + 3].toInt() and 0xff) shr 6)
            val len = ((d[p + 3].toInt() and 3) shl 11) or ((d[p + 4].toInt() and 0xff) shl 3) or ((d[p + 5].toInt() and 0xff) shr 5)
            val hdr = if (protectionAbsent == 1) 7 else 9
            if (p + len > d.size || len <= hdr) break
            frames.add(d.copyOfRange(p + hdr, p + len))
            p += len
        }
        info = AudioTrackInfo(rate, ch, AudioTrackInfo.aacLcConfig(rate, ch))
    }

    fun start(endpoints: List<StreamEndpoint>) {
        endpoints.forEach { it.setAudioTrack(info) }
        thread(isDaemon = true, name = "audio") {
            val period = 1024L * 1_000_000_000L / info.sampleRate
            var next = System.nanoTime()
            var i = 0
            while (true) {
                val f = MediaFrame(frames[i], System.nanoTime() / 1000, true)
                endpoints.forEach { it.pushAudio(f) }
                i = (i + 1) % frames.size
                next += period
                val sleep = next - System.nanoTime()
                if (sleep > 0) Thread.sleep(sleep / 1_000_000, (sleep % 1_000_000).toInt())
            }
        }
    }
}
