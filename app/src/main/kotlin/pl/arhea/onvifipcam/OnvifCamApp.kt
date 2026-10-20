package pl.arhea.onvifipcam

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import pl.arhea.onvifipcam.settings.SettingsStore

class OnvifCamApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsStore.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Camera service", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the camera is streaming"
                setShowBadge(false)
            },
        )
    }

    companion object {
        const val CHANNEL_SERVICE = "service"
    }
}
