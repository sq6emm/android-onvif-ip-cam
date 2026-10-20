package pl.arhea.onvifipcam.media

/**
 * Grid-based motion detector over a small luma image. A cell counts as "moving" when enough of its
 * pixels differ from a slowly adapting background; motion is reported when enough cells move.
 * Motion turns off after [holdMs] without movement.
 */
class MotionDetector(
    private val width: Int,
    private val height: Int,
    sensitivity: Int,
    private val holdMs: Long = 5000,
    private val onChange: (Boolean) -> Unit,
) {
    private val cols = 16
    private val rows = 9
    private val background = FloatArray(width * height)
    private var initialized = false
    /** Frames left during which the background adapts fast and nothing is reported (AE/AWB settling). */
    private var warmup = 0
    private var lastMotionMs = 0L
    @Volatile var active = false
        private set
    /** Fraction of moving cells in the last frame (0..1), for the UI. */
    @Volatile var level = 0f
        private set
    @Volatile var suspended = false

    private val s = sensitivity.coerceIn(0, 100)
    /** Pixel difference threshold: 40 at lowest sensitivity, 10 at highest. */
    private val pixelThreshold = 40 - 30 * s / 100
    /** Cells needed: 8 at lowest sensitivity, 1 at highest. */
    private val cellsNeeded = maxOf(1, 8 - 7 * s / 100)

    fun feed(luma: ByteArray) {
        if (suspended) {
            initialized = false
            return
        }
        if (!initialized) {
            for (i in background.indices) background[i] = (luma[i].toInt() and 0xff).toFloat()
            initialized = true
            warmup = WARMUP_FRAMES
            return
        }
        if (warmup > 0) {
            warmup--
            for (i in background.indices) {
                val v = (luma[i].toInt() and 0xff).toFloat()
                background[i] += (v - background[i]) * 0.3f
            }
            return
        }
        val cw = width / cols
        val ch = height / rows
        val cellCounts = IntArray(cols * rows)
        for (y in 0 until rows * ch) {
            val row = y * width
            val cellRow = (y / ch) * cols
            for (x in 0 until cols * cw) {
                val i = row + x
                val v = (luma[i].toInt() and 0xff).toFloat()
                val b = background[i]
                if (kotlin.math.abs(v - b) > pixelThreshold) cellCounts[cellRow + x / cw]++
                background[i] = b + (v - b) * 0.05f
            }
        }
        val cellPixels = cw * ch
        val moving = cellCounts.count { it > cellPixels / 4 }
        level = moving.toFloat() / (cols * rows)
        val now = System.currentTimeMillis()
        // Global changes (lights switching, auto exposure jumps) are not motion.
        if (moving >= cellsNeeded && moving < cols * rows * 3 / 4) lastMotionMs = now
        val newActive = now - lastMotionMs < holdMs && lastMotionMs > 0
        if (newActive != active) {
            active = newActive
            onChange(newActive)
        }
    }

    companion object {
        /** ~3 s at the renderer's 5 Hz motion sampling. */
        private const val WARMUP_FRAMES = 15
    }
}
