package pl.arhea.onvifipcam.media

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import pl.arhea.onvifipcam.settings.SettingsStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Tracks how the phone is turned, from the accelerometer, as its clockwise rotation from the natural
 * (portrait) position: 0, 90 (left side up), 180 (upside down) or 270 (right side up) — the same
 * convention as OrientationEventListener. Lying flat keeps the last value, which is also remembered
 * across restarts. A change is reported only once it has been stable for [settleMs], so handling the
 * phone doesn't restart the stream.
 */
class DeviceOrientation(
    context: Context,
    private val settleMs: Long = 3000,
    private val onChange: (Int) -> Unit,
) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val firstReading = CountDownLatch(1)
    private var candidate = -1
    private var candidateSince = 0L

    @Volatile var current: Int = SettingsStore.getString(KEY)?.toIntOrNull()?.takeIf { it in QUADRANTS } ?: 0
        private set

    fun start() {
        val accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accel == null) firstReading.countDown() else sensors.registerListener(this, accel, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() = sensors.unregisterListener(this)

    /** Waits briefly for the first usable reading so a fresh start doesn't use a stale orientation. */
    fun awaitFirstReading(timeoutMs: Long = 700) {
        firstReading.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    override fun onSensorChanged(e: SensorEvent) {
        val x = e.values[0]
        val y = e.values[1]
        val z = e.values[2]
        // Too close to flat to tell (same test as OrientationEventListener).
        if ((x * x + y * y) * 4 < z * z) {
            firstReading.countDown()
            return
        }
        val angle = (90 - Math.toDegrees(atan2(y.toDouble(), -x.toDouble())) + 720) % 360
        val q = ((Math.round(angle / 90) % 4) * 90).toInt()
        // Ignore the diagonals between two positions.
        val off = abs(angle - q).let { minOf(it, 360 - it) }
        if (off > 30) return

        if (firstReading.count > 0) {
            firstReading.countDown()
            if (q != current) set(q)
            return
        }
        if (q == current) {
            candidate = -1
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (q != candidate) {
            candidate = q
            candidateSince = now
        } else if (now - candidateSince >= settleMs) {
            candidate = -1
            set(q)
        }
    }

    private fun set(q: Int) {
        current = q
        SettingsStore.putString(KEY, q.toString())
        onChange(q)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        private const val KEY = "device_orientation"
        private val QUADRANTS = setOf(0, 90, 180, 270)

        /**
         * Clockwise image rotation that makes the picture upright for a phone turned by [orientation].
         * The camera frames already arrive upright for the natural position; front cameras are mirrored,
         * which reverses the direction.
         */
        fun uprightRotation(orientation: Int, facing: String) =
            if (facing == "front") (360 - orientation) % 360 else orientation
    }
}
