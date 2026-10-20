package pl.arhea.onvifipcam.media

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import kotlin.math.abs

data class CameraDescription(val id: String, val facing: String, val sensorOrientation: Int, val maxSize: Size)

/** Imaging parameters the user/ONVIF client can change at runtime. */
data class ImagingState(
    /** 0..100, mapped onto the camera's exposure compensation range. */
    val brightness: Float = 50f,
    val autoFocus: Boolean = true,
    /** 0..1 manual focus position (0 = infinity) when [autoFocus] is false. */
    val focusPosition: Float = 0f,
    val torch: Boolean = false,
)

/**
 * Camera2 capture into a single surface (the GL renderer's SurfaceTexture).
 * Reopens the camera automatically after errors or when another app releases it.
 */
class CameraSource(
    context: Context,
    private val cameraId: String,
    private val size: Size,
    private val fps: Int,
    private val onError: (String?) -> Unit,
) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val chars = manager.getCameraCharacteristics(cameraId)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null
    @Volatile private var closed = false
    @Volatile var imaging = ImagingState()
        private set

    val exposureRange: Range<Int> = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
    val hasFlash: Boolean = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    val minFocusDistance: Float = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

    fun start(texture: SurfaceTexture) {
        surface = Surface(texture)
        handler.post { open() }
    }

    fun setImaging(state: ImagingState) {
        imaging = state
        handler.post { applyRequest() }
    }

    fun stop() {
        closed = true
        handler.post {
            runCatching { session?.close() }
            runCatching { device?.close() }
            session = null
            device = null
        }
        thread.quitSafely()
        thread.join(2000)
        surface?.release()
    }

    @SuppressLint("MissingPermission")
    private fun open() {
        if (closed) return
        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (closed) {
                        camera.close()
                        return
                    }
                    device = camera
                    createSession(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "camera disconnected")
                    camera.close()
                    device = null
                    onError("Camera in use by another app")
                    retry()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "camera error $error")
                    camera.close()
                    device = null
                    onError("Camera error $error")
                    retry()
                }
            }, handler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "openCamera failed", e)
            onError("Cannot open camera: ${e.message}")
            retry()
        } catch (e: SecurityException) {
            onError("Camera permission missing")
        }
    }

    private fun retry() {
        if (!closed) handler.postDelayed({ open() }, 3000)
    }

    @Suppress("DEPRECATION")
    private fun createSession(camera: CameraDevice) {
        val s = surface ?: return
        try {
            camera.createCaptureSession(listOf(s), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(cs: CameraCaptureSession) {
                    if (closed) {
                        cs.close()
                        return
                    }
                    session = cs
                    applyRequest()
                    onError(null)
                }

                override fun onConfigureFailed(cs: CameraCaptureSession) {
                    Log.e(TAG, "session configuration failed")
                    onError("Camera session configuration failed")
                    camera.close()
                    device = null
                    retry()
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "createCaptureSession failed", e)
            onError("Camera session failed: ${e.message}")
            retry()
        }
    }

    private fun applyRequest() {
        val cam = device ?: return
        val cs = session ?: return
        val s = surface ?: return
        try {
            val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(s)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                bestFpsRange()?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                val img = imaging
                if (exposureRange.upper > exposureRange.lower) {
                    val ev = exposureRange.lower + ((exposureRange.upper - exposureRange.lower) * img.brightness / 100f)
                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev.toInt().coerceIn(exposureRange.lower, exposureRange.upper))
                }
                val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
                if (img.autoFocus || minFocusDistance <= 0f) {
                    if (CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO in afModes) {
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    }
                } else {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, img.focusPosition * minFocusDistance)
                }
                val stab = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: intArrayOf()
                if (CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF in stab) {
                    set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                }
                if (hasFlash) {
                    set(CaptureRequest.FLASH_MODE, if (img.torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
                }
            }
            cs.setRepeatingRequest(req.build(), null, handler)
        } catch (e: Exception) {
            Log.e(TAG, "setRepeatingRequest failed", e)
        }
    }

    /** Prefers a fixed [fps,fps] range, then the narrowest range whose upper bound is fps. */
    private fun bestFpsRange(): Range<Int>? {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        return ranges.firstOrNull { it.lower == fps && it.upper == fps }
            ?: ranges.filter { it.upper == fps }.maxByOrNull { it.lower }
            ?: ranges.minByOrNull { abs(it.upper - fps) * 100 + (it.upper - it.lower) }
    }

    companion object {
        private const val TAG = "CameraSource"

        fun list(context: Context): List<CameraDescription> {
            val m = context.getSystemService(CameraManager::class.java)
            return m.cameraIdList.mapNotNull { id ->
                runCatching {
                    val c = m.getCameraCharacteristics(id)
                    val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                        CameraCharacteristics.LENS_FACING_FRONT -> "front"
                        CameraCharacteristics.LENS_FACING_BACK -> "back"
                        else -> "external"
                    }
                    val sizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        ?.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
                    CameraDescription(
                        id, facing, c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
                        sizes.maxByOrNull { it.width * it.height } ?: Size(0, 0),
                    )
                }.getOrNull()
            }
        }

        /**
         * Picks the capture size: same aspect ratio as [target], no larger than [target] * [scale]
         * (capped at 4K) and able to run at [fps]. Falls back to the closest usable size.
         */
        fun chooseCaptureSize(context: Context, cameraId: String, target: Size, scale: Float, fps: Int): Size {
            val c = context.getSystemService(CameraManager::class.java).getCameraCharacteristics(cameraId)
            val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return target
            val sizes = map.getOutputSizes(SurfaceTexture::class.java) ?: return target
            val aspect = target.width.toFloat() / target.height
            val maxW = minOf((target.width * scale).toInt(), 3840)
            val frameNs = 1_000_000_000L / fps
            val fast = sizes.filter { s ->
                val d = runCatching { map.getOutputMinFrameDuration(SurfaceTexture::class.java, s) }.getOrDefault(0L)
                d <= frameNs * 1.05
            }
            val sameAspect = fast.filter { abs(it.width.toFloat() / it.height - aspect) < 0.02f }
            return sameAspect.filter { it.width <= maxW }.maxByOrNull { it.width * it.height }
                ?: fast.filter { it.width <= maxW }.maxByOrNull { it.width * it.height }
                ?: sizes.minByOrNull { abs(it.width - target.width) }
                ?: target
        }
    }
}
