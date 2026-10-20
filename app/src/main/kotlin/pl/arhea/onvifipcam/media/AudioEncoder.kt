package pl.arhea.onvifipcam.media

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import pl.arhea.onvifipcam.rtsp.AudioTrackInfo
import pl.arhea.onvifipcam.rtsp.MediaFrame
import pl.arhea.onvifipcam.rtsp.StreamEndpoint
import kotlin.concurrent.thread

/** Microphone -> AAC-LC, published to every endpoint. */
class AudioEncoder(private val endpoints: List<StreamEndpoint>, private val sampleRate: Int = 44100, private val bitrate: Int = 64000) {
    private var record: AudioRecord? = null
    private var codec: MediaCodec? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.CAMCORDER, sampleRate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 2, 8192),
        )
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord init failed" }
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        c.start()
        rec.startRecording()
        record = rec
        codec = c
        running = true
        val info = AudioTrackInfo(sampleRate, 1, AudioTrackInfo.aacLcConfig(sampleRate, 1))
        endpoints.forEach { it.setAudioTrack(info) }
        thread = thread(name = "audio-enc") { loop(rec, c) }
    }

    fun stop() {
        running = false
        thread?.join(1000)
        runCatching { record?.stop() }
        runCatching { record?.release() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        record = null
        codec = null
        endpoints.forEach { it.setAudioTrack(null) }
    }

    private fun loop(rec: AudioRecord, c: MediaCodec) {
        val pcm = ByteArray(2048) // 1024 mono samples = one AAC frame
        val info = MediaCodec.BufferInfo()
        var anchorUs = -1L
        var samples = 0L
        try {
            while (running) {
                val n = rec.read(pcm, 0, pcm.size)
                if (n <= 0) continue
                val nowUs = System.nanoTime() / 1000
                // Timestamp from the sample counter, re-anchored to the clock if it drifts by >100 ms.
                var pts = anchorUs + samples * 1_000_000L / sampleRate
                val expected = nowUs - n / 2 * 1_000_000L / sampleRate
                if (anchorUs < 0 || kotlin.math.abs(pts - expected) > 100_000) {
                    anchorUs = expected
                    samples = 0
                    pts = expected
                }
                samples += n / 2
                val inIdx = c.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val ib = c.getInputBuffer(inIdx)!!
                    ib.clear()
                    ib.put(pcm, 0, n)
                    c.queueInputBuffer(inIdx, 0, n, pts, 0)
                }
                while (true) {
                    val outIdx = c.dequeueOutputBuffer(info, 0)
                    if (outIdx < 0) break
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val ob = c.getOutputBuffer(outIdx)!!
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val data = ByteArray(info.size)
                        ob.get(data)
                        val frame = MediaFrame(data, info.presentationTimeUs, true)
                        endpoints.forEach { it.pushAudio(frame) }
                    }
                    c.releaseOutputBuffer(outIdx, false)
                }
            }
        } catch (e: Exception) {
            Log.e("AudioEncoder", "audio loop failed", e)
        }
    }
}
