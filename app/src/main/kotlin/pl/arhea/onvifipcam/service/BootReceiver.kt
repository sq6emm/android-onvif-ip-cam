package pl.arhea.onvifipcam.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import pl.arhea.onvifipcam.settings.SettingsStore
import pl.arhea.onvifipcam.ui.MainActivity

/**
 * Autostart after boot / app update. Since Android 11 a camera foreground service started from the
 * background gets no camera access, so when "display over other apps" is granted we start the
 * activity (allowed for overlay holders, shown over the lock screen), which then starts the service
 * in the foreground. LOCKED_BOOT_COMPLETED arrives before the PIN is entered (Direct Boot).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val actions = setOf(Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)
        if (intent.action !in actions) return
        if (!SettingsStore.current.autostart) return
        // BOOT_COMPLETED follows LOCKED_BOOT_COMPLETED once the user unlocks; don't start twice.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && CameraService.isRunning) return
        Log.i("BootReceiver", "autostart (${intent.action})")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Settings.canDrawOverlays(context)) {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_AUTOSTART, true),
            )
        } else {
            runCatching { CameraService.start(context) }.onFailure { Log.e("BootReceiver", "start failed", it) }
        }
    }
}
