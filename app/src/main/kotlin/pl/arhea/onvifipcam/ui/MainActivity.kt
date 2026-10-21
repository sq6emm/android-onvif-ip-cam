package pl.arhea.onvifipcam.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import pl.arhea.onvifipcam.service.CameraService
import pl.arhea.onvifipcam.settings.SettingsStore

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionsVersion++
        if (startAfterPermissions && hasPermission(Manifest.permission.CAMERA)) CameraService.start(this)
        startAfterPermissions = false
        // The next missing permission is asked from onResume, which follows this callback.
    }
    private var startAfterPermissions = false
    private var permissionsVersion by mutableIntStateOf(0)
    private var resumeVersion by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra(EXTRA_AUTOSTART, false) == true) autostart()
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                var screen by remember { mutableStateOf(Screen.Main) }
                // Re-read permission/battery state whenever we come back to the activity.
                val env = AppEnvironment(
                    hasCamera = hasPermission(Manifest.permission.CAMERA),
                    hasMic = hasPermission(Manifest.permission.RECORD_AUDIO),
                    ignoringBattery = ignoringBatteryOptimizations(),
                    canOverlay = canOverlay(),
                    version = permissionsVersion + resumeVersion,
                )
                when (screen) {
                    Screen.Main -> MainScreen(
                        env = env,
                        onStart = ::startCamera,
                        onStop = { CameraService.stop(this) },
                        onSettings = { screen = Screen.Settings },
                        onRequestBattery = ::requestBatteryExemption,
                        onRequestOverlay = ::requestOverlay,
                        onKeepScreenOn = { on -> setKeepScreenOn(on) },
                    )
                    Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Main })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_AUTOSTART, false)) autostart()
    }

    override fun onResume() {
        super.onResume()
        resumeVersion++
        // Not during autostart: the activity is only briefly shown over the lock screen then.
        if (intent?.getBooleanExtra(EXTRA_AUTOSTART, false) != true && !autostarting) requestMissing()
    }

    /**
     * Asks for the next missing permission, one at a time; coming back from each dialog or settings
     * screen (onResume) moves on to the next. Each is asked once per process so a
     * refusal doesn't loop; the cards on the main screen stay as a reminder.
     */
    private fun requestMissing() {
        val runtime = runtimePermissions().filter { !hasPermission(it) }
        when {
            runtime.isNotEmpty() && asked.add("runtime") -> permissionLauncher.launch(runtime.toTypedArray())
            !ignoringBatteryOptimizations() && asked.add("battery") -> requestBatteryExemption()
            SettingsStore.current.autostart && !canOverlay() && asked.add("overlay") -> requestOverlay()
        }
    }

    private fun autostart() {
        // Handle the boot request once; a recreated activity must not start/hide itself again.
        intent?.removeExtra(EXTRA_AUTOSTART)
        autostarting = true
        if (hasPermission(Manifest.permission.CAMERA)) {
            // Be visible over the lock screen so the camera service counts as started from the foreground.
            showOverLockScreen(true)
            CameraService.start(this)
            // The service keeps camera access once started from the foreground; get out of the way.
            Handler(Looper.getMainLooper()).postDelayed({
                showOverLockScreen(false)
                moveTaskToBack(true)
                autostarting = false
            }, 3000)
        }
    }

    private fun showOverLockScreen(on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(on)
        } else {
            @Suppress("DEPRECATION")
            if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
    }

    private fun runtimePermissions() = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun canOverlay() = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Settings.canDrawOverlays(this)

    private fun startCamera() {
        val needed = runtimePermissions().filter { !hasPermission(it) }
        if (needed.isEmpty()) {
            CameraService.start(this)
        } else {
            startAfterPermissions = true
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun setKeepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun hasPermission(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun ignoringBatteryOptimizations() =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    private fun requestOverlay() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private enum class Screen { Main, Settings }

    private var autostarting = false

    companion object {
        const val EXTRA_AUTOSTART = "autostart"
        private val asked = mutableSetOf<String>()
    }
}

data class AppEnvironment(
    val hasCamera: Boolean,
    val hasMic: Boolean,
    val ignoringBattery: Boolean,
    val canOverlay: Boolean,
    val version: Int,
)
