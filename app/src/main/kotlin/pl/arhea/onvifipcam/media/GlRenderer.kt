package pl.arhea.onvifipcam.media

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Digital PTZ window: zoom >= 1, pan/tilt in -1..1 across the area available at that zoom. */
data class ViewWindow(val zoom: Float = 1f, val pan: Float = 0f, val tilt: Float = 0f)

/**
 * Owns the GL thread. Receives camera frames on a SurfaceTexture and renders them (rotated, PTZ-cropped,
 * with OSD) into every registered target: encoder input surfaces and the optional preview.
 * Also produces a small luma image for motion detection and JPEG snapshots.
 */
class GlRenderer(
    private val captureSize: Size,
    /** Image size after the SurfaceTexture transform and [rotation]; the crop keeps targets undistorted. */
    private val sourceSize: Size,
    private val rotation: Int,
    private val osd: OsdText?,
    private val view: () -> ViewWindow,
) {
    private class Target(val surface: EGLSurface, val width: Int, val height: Int, fps: Int, val isPreview: Boolean) {
        val intervalNs = if (fps > 0) 1_000_000_000L / fps else 0L
        var nextNs = 0L
    }

    private class Fbo(val width: Int, val height: Int) {
        val fb: Int
        val tex: Int
        val pixels: ByteBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())

        init {
            tex = Gl.texture(GLES20.GL_TEXTURE_2D)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            val f = IntArray(1)
            GLES20.glGenFramebuffers(1, f, 0)
            fb = f[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fb)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }

        fun release() {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fb), 0)
            GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
        }
    }

    private class SnapshotRequest(val width: Int, val height: Int, val callback: (Bitmap?) -> Unit)

    private val thread = HandlerThread("gl").apply { start() }
    private val handler = Handler(thread.looper)
    private val jpegExecutor = Executors.newSingleThreadExecutor()

    private lateinit var egl: EglCore
    private lateinit var pbuffer: EGLSurface
    private lateinit var cameraProgram: CameraProgram
    private lateinit var overlayProgram: OverlayProgram
    private var oesTexture = 0
    private var osdTexture = 0
    private var osdWidth = 0
    private var osdHeight = 0
    private var osdKey: String? = null
    lateinit var surfaceTexture: SurfaceTexture
        private set

    private val targets = ArrayList<Target>()
    private var preview: Target? = null
    private var motionFbo: Fbo? = null
    private var lastMotionNs = 0L
    private val snapshotRequests = ArrayList<SnapshotRequest>()
    private val stMatrix = FloatArray(16)
    private val tmp = FloatArray(16)
    private val tmp2 = FloatArray(16)
    private val texMatrix = FloatArray(16)
    @Volatile private var released = false

    /** Receives a [MOTION_W]x[MOTION_H] luma image a few times per second (GL thread). */
    @Volatile var motionSink: ((ByteArray) -> Unit)? = null
    @Volatile var framesRendered = 0L
        private set

    fun start(): SurfaceTexture {
        runSync {
            egl = EglCore()
            pbuffer = egl.createPbufferSurface(1, 1)
            egl.makeCurrent(pbuffer)
            cameraProgram = CameraProgram()
            overlayProgram = OverlayProgram()
            oesTexture = Gl.texture(Gl.OES)
            osdTexture = Gl.texture(GLES20.GL_TEXTURE_2D)
            surfaceTexture = SurfaceTexture(oesTexture).apply {
                setDefaultBufferSize(captureSize.width, captureSize.height)
                setOnFrameAvailableListener({ onFrame() }, handler)
            }
        }
        return surfaceTexture
    }

    fun addEncoderTarget(surface: Surface, width: Int, height: Int, fps: Int) = runSync {
        targets.add(Target(egl.createWindowSurface(surface), width, height, fps, false))
    }

    /** Sets or clears the preview surface. Blocks until the GL thread no longer uses the old one. */
    fun setPreview(surface: Surface?, width: Int, height: Int) = runSync {
        preview?.let { targets.remove(it); egl.releaseSurface(it.surface) }
        preview = null
        if (surface != null && surface.isValid) {
            preview = runCatching { Target(egl.createWindowSurface(surface), width, height, 15, true) }
                .onFailure { Log.w(TAG, "preview surface failed", it) }
                .getOrNull()
                ?.also { targets.add(it) }
        }
    }

    fun requestSnapshot(width: Int, height: Int, callback: (Bitmap?) -> Unit) {
        handler.post {
            if (released) callback(null) else snapshotRequests.add(SnapshotRequest(width, height, callback))
        }
    }

    /** Takes a JPEG snapshot, waiting up to [timeoutMs] for the next camera frame. */
    fun snapshotJpeg(width: Int, height: Int, quality: Int = 85, timeoutMs: Long = 3000): ByteArray? {
        val latch = CountDownLatch(1)
        var result: ByteArray? = null
        requestSnapshot(width, height) { bmp ->
            if (bmp != null) {
                val out = java.io.ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
                bmp.recycle()
                result = out.toByteArray()
            }
            latch.countDown()
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return result
    }

    fun release() {
        runSync {
            released = true
            snapshotRequests.forEach { it.callback(null) }
            snapshotRequests.clear()
            targets.forEach { egl.releaseSurface(it.surface) }
            targets.clear()
            preview = null
            motionFbo?.release()
            surfaceTexture.release()
            egl.releaseSurface(pbuffer)
            egl.release()
        }
        thread.quitSafely()
        jpegExecutor.shutdown()
    }

    private fun onFrame() {
        if (released) return
        try {
            egl.makeCurrent(pbuffer)
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(stMatrix)
            val now = System.nanoTime()
            updateOsd()
            val v = view()
            val dead = ArrayList<Target>()
            for (t in targets) {
                if (t.intervalNs > 0 && now < t.nextNs - t.intervalNs / 4) continue
                t.nextNs = if (now - t.nextNs > t.intervalNs) now + t.intervalNs else t.nextNs + t.intervalNs
                try {
                    egl.makeCurrent(t.surface)
                    drawScene(t.width, t.height, v, flipY = false)
                    egl.setPresentationTime(t.surface, now)
                    if (!egl.swap(t.surface) && t.isPreview) dead.add(t)
                } catch (e: IllegalStateException) {
                    if (t.isPreview) dead.add(t) else throw e
                }
            }
            for (t in dead) {
                targets.remove(t)
                egl.releaseSurface(t.surface)
                if (preview === t) preview = null
            }
            framesRendered++
            egl.makeCurrent(pbuffer)
            if (motionSink != null && now - lastMotionNs >= MOTION_INTERVAL_NS) {
                lastMotionNs = now
                readMotion(v)
            }
            if (snapshotRequests.isNotEmpty()) {
                val reqs = ArrayList(snapshotRequests)
                snapshotRequests.clear()
                for (r in reqs) readSnapshot(r, v)
            }
        } catch (e: Exception) {
            Log.e(TAG, "render failed", e)
        }
    }

    private fun drawScene(width: Int, height: Int, v: ViewWindow, flipY: Boolean) {
        GLES20.glViewport(0, 0, width, height)
        computeTexMatrix(width.toFloat() / height, v)
        cameraProgram.draw(oesTexture, texMatrix, flipY)
        if (osd != null && osdWidth > 0) {
            // Text height ~4% of the frame, anchored top-left with a small margin.
            val h = 2f * 0.04f
            val w = h * osdWidth / osdHeight * height / width
            val mx = 2f * 0.015f * height / width
            val my = 2f * 0.015f
            overlayProgram.draw(osdTexture, -1f + mx, 1f - my - h, -1f + mx + w, 1f - my, flipY)
        }
    }

    /**
     * Texture matrix = SurfaceTexture transform * rotation about the centre * crop window.
     * The crop window keeps the target's aspect ratio and is shrunk/moved by the PTZ view.
     */
    private fun computeTexMatrix(targetAspect: Float, v: ViewWindow) {
        val srcAspect = sourceSize.width.toFloat() / sourceSize.height
        var bw = 1f
        var bh = 1f
        if (srcAspect > targetAspect) bw = targetAspect / srcAspect else bh = srcAspect / targetAspect
        val zoom = v.zoom.coerceAtLeast(1f)
        val cw = bw / zoom
        val ch = bh / zoom
        val cx = 0.5f + v.pan.coerceIn(-1f, 1f) * (1f - cw) / 2f
        val cy = 0.5f + v.tilt.coerceIn(-1f, 1f) * (1f - ch) / 2f

        // crop: t' = (cx - cw/2, cy - ch/2) + t * (cw, ch)
        Matrix.setIdentityM(tmp, 0)
        Matrix.translateM(tmp, 0, cx - cw / 2f, cy - ch / 2f, 0f)
        Matrix.scaleM(tmp, 0, cw, ch, 1f)
        // rotation about (0.5, 0.5)
        Matrix.setIdentityM(tmp2, 0)
        Matrix.translateM(tmp2, 0, 0.5f, 0.5f, 0f)
        Matrix.rotateM(tmp2, 0, rotation.toFloat(), 0f, 0f, 1f)
        Matrix.translateM(tmp2, 0, -0.5f, -0.5f, 0f)
        val rc = FloatArray(16)
        Matrix.multiplyMM(rc, 0, tmp2, 0, tmp, 0)
        Matrix.multiplyMM(texMatrix, 0, stMatrix, 0, rc, 0)
    }

    private fun updateOsd() {
        val o = osd ?: return
        val key = o.currentText()
        if (key == osdKey) return
        osdKey = key
        val bmp = o.render(key)
        osdWidth = bmp.width
        osdHeight = bmp.height
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, osdTexture)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
    }

    private fun readMotion(v: ViewWindow) {
        val fbo = motionFbo ?: Fbo(MOTION_W, MOTION_H).also { motionFbo = it }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo.fb)
        GLES20.glViewport(0, 0, MOTION_W, MOTION_H)
        computeTexMatrix(MOTION_W.toFloat() / MOTION_H, v)
        cameraProgram.draw(oesTexture, texMatrix, flipY = true)
        fbo.pixels.position(0)
        GLES20.glReadPixels(0, 0, MOTION_W, MOTION_H, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, fbo.pixels)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        val luma = ByteArray(MOTION_W * MOTION_H)
        val px = fbo.pixels
        for (i in luma.indices) {
            val r = px.get(i * 4).toInt() and 0xff
            val g = px.get(i * 4 + 1).toInt() and 0xff
            val b = px.get(i * 4 + 2).toInt() and 0xff
            luma[i] = ((r * 77 + g * 150 + b * 29) shr 8).toByte()
        }
        motionSink?.invoke(luma)
    }

    private fun readSnapshot(r: SnapshotRequest, v: ViewWindow) {
        val fbo = Fbo(r.width, r.height)
        try {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo.fb)
            drawScene(r.width, r.height, v, flipY = true)
            fbo.pixels.position(0)
            GLES20.glReadPixels(0, 0, r.width, r.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, fbo.pixels)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            val pixels = fbo.pixels
            jpegExecutor.execute {
                val bmp = Bitmap.createBitmap(r.width, r.height, Bitmap.Config.ARGB_8888)
                pixels.position(0)
                bmp.copyPixelsFromBuffer(pixels)
                r.callback(bmp)
            }
        } catch (e: Exception) {
            Log.e(TAG, "snapshot failed", e)
            r.callback(null)
        } finally {
            fbo.release()
        }
    }

    private fun runSync(block: () -> Unit) {
        if (Thread.currentThread() === thread) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        var error: Throwable? = null
        val posted = handler.post {
            try {
                block()
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }
        if (!posted) return
        latch.await(5, TimeUnit.SECONDS)
        error?.let { throw it }
    }

    companion object {
        private const val TAG = "GlRenderer"
        const val MOTION_W = 160
        const val MOTION_H = 90
        private const val MOTION_INTERVAL_NS = 200_000_000L
    }
}
