package pl.arhea.onvifipcam.rtsp

import java.util.concurrent.CopyOnWriteArrayList

/** Receives frames published on a [StreamEndpoint]. Called on the encoder thread: must not block. */
interface FrameSubscriber {
    fun onVideo(frame: MediaFrame)
    fun onAudio(frame: MediaFrame)
    /** Track parameters changed or the stream stopped; the subscriber should disconnect. */
    fun onReset()
}

/** One published stream, e.g. `/stream1`. Encoders push frames, RTSP sessions subscribe. */
class StreamEndpoint(val path: String) {
    @Volatile var videoInfo: VideoTrackInfo? = null
        private set
    @Volatile var audioInfo: AudioTrackInfo? = null
        private set

    /** Asks the video encoder for an IDR frame (new client joined or a client dropped frames). */
    @Volatile var keyframeRequester: (() -> Unit)? = null
    /** Notified with the new subscriber count. */
    @Volatile var onClientsChanged: ((Int) -> Unit)? = null

    private val subscribers = CopyOnWriteArrayList<FrameSubscriber>()
    private val lock = Object()

    val clientCount: Int get() = subscribers.size

    fun setTracks(video: VideoTrackInfo?, audio: AudioTrackInfo?) {
        val changed = !sameVideo(videoInfo, video) || !sameAudio(audioInfo, audio)
        synchronized(lock) {
            videoInfo = video
            audioInfo = audio
            lock.notifyAll()
        }
        if (changed) subscribers.forEach { it.onReset() }
    }

    fun setVideoTrack(video: VideoTrackInfo?) = setTracks(video, audioInfo)
    fun setAudioTrack(audio: AudioTrackInfo?) = setTracks(videoInfo, audio)

    /** Waits until the video track is known (encoder started). */
    fun awaitVideoInfo(timeoutMs: Long): VideoTrackInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (videoInfo == null) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                lock.wait(left)
            }
            return videoInfo
        }
    }

    fun pushVideo(frame: MediaFrame) {
        if (subscribers.isEmpty()) return
        val info = videoInfo ?: return
        var f = frame
        if (frame.keyframe && !containsParameterSets(info.codec, frame.data)) {
            val ps = info.annexBParameterSets()
            val data = ByteArray(ps.size + frame.data.size)
            System.arraycopy(ps, 0, data, 0, ps.size)
            System.arraycopy(frame.data, 0, data, ps.size, frame.data.size)
            f = MediaFrame(data, frame.ptsUs, true)
        }
        for (s in subscribers) s.onVideo(f)
    }

    fun pushAudio(frame: MediaFrame) {
        for (s in subscribers) s.onAudio(frame)
    }

    fun requestKeyframe() {
        keyframeRequester?.invoke()
    }

    internal fun subscribe(s: FrameSubscriber) {
        subscribers.add(s)
        onClientsChanged?.invoke(subscribers.size)
        requestKeyframe()
    }

    internal fun unsubscribe(s: FrameSubscriber) {
        if (subscribers.remove(s)) onClientsChanged?.invoke(subscribers.size)
    }

    /** Disconnects all clients. */
    fun reset() = subscribers.forEach { it.onReset() }

    private fun containsParameterSets(codec: VideoCodec, data: ByteArray): Boolean =
        Nal.split(data).any { Nal.isParameterSet(codec, Nal.type(codec, it)) }

    private fun sameVideo(a: VideoTrackInfo?, b: VideoTrackInfo?) =
        a === b || (a != null && b != null && a.codec == b.codec && a.sps.contentEquals(b.sps) && a.pps.contentEquals(b.pps))

    private fun sameAudio(a: AudioTrackInfo?, b: AudioTrackInfo?) =
        a === b || (a != null && b != null && a.sampleRate == b.sampleRate && a.config.contentEquals(b.config))
}
