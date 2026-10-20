package pl.arhea.onvifipcam.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import pl.arhea.onvifipcam.OnvifCamApp
import pl.arhea.onvifipcam.R
import pl.arhea.onvifipcam.media.CameraPipeline
import pl.arhea.onvifipcam.media.CameraSource
import pl.arhea.onvifipcam.onvif.EventHub
import pl.arhea.onvifipcam.onvif.OnvifServer
import pl.arhea.onvifipcam.onvif.PresetStore
import pl.arhea.onvifipcam.onvif.WsDiscovery
import pl.arhea.onvifipcam.rtsp.Credentials
import pl.arhea.onvifipcam.rtsp.DigestAuth
import pl.arhea.onvifipcam.rtsp.Logger
import pl.arhea.onvifipcam.rtsp.RtspServer
import pl.arhea.onvifipcam.rtsp.StreamEndpoint
import pl.arhea.onvifipcam.settings.CamSettings
import pl.arhea.onvifipcam.settings.SettingsStore
import pl.arhea.onvifipcam.ui.MainActivity
import pl.arhea.onvifipcam.util.Net
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class ServiceStatus(
    val running: Boolean = false,
    val starting: Boolean = false,
    val error: String? = null,
    val cameraStatus: String? = null,
    val addresses: List<String> = emptyList(),
    val rtspPort: Int = 0,
    val onvifPort: Int = 0,
    val clients: Int = 0,
    val mainKbps: Int = 0,
    val subKbps: Int = 0,
    val fps: Float = 0f,
    val motion: Boolean = false,
    val motionLevel: Float = 0f,
    val thermal: String = "",
    val info: String = "",
    val log: List<String> = emptyList(),
)

/**
 * Foreground service that owns the camera pipeline and the RTSP/ONVIF/WS-Discovery servers.
 * Start/stop work runs on a single worker thread; status is published through [status].
 */
class CameraService : Service() {
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "cam-service") }
    @Volatile private var pipeline: CameraPipeline? = null
    private var rtsp: RtspServer? = null
    private var onvif: OnvifServer? = null
    private var discovery: WsDiscovery? = null
    private var statusTask: ScheduledFuture<*>? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var thermalListener: Any? = null
    private val mainEndpoint = StreamEndpoint("stream1")
    private val subEndpoint = StreamEndpoint("stream2")
    private var lastFrames = 0L
    private var lastFramesAt = 0L

    private val logger = Logger { msg ->
        Log.i(TAG, msg)
        _status.update { it.copy(log = (it.log + "${timeNow()} $msg").takeLast(80)) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                worker.execute { stopStreaming() }
                worker.execute {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            else -> {
                if (!enterForeground()) return START_NOT_STICKY
                val restart = intent?.action == ACTION_RESTART
                worker.execute {
                    if (restart || pipeline == null) {
                        stopStreaming()
                        startStreaming()
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        worker.execute { stopStreaming() }
        worker.shutdown()
        worker.awaitTermination(5, TimeUnit.SECONDS)
        instance = null
        super.onDestroy()
    }

    private fun enterForeground(): Boolean {
        val s = SettingsStore.current
        var types = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (s.audioEnabled && hasPermission(Manifest.permission.RECORD_AUDIO)) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(s, null), types)
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            _status.update { it.copy(error = "Nie można uruchomić serwisu w tle: ${e.message}", running = false, starting = false) }
            stopSelf()
            false
        }
    }

    private fun startStreaming() {
        var s = SettingsStore.current
        val cameras = runCatching { CameraSource.list(this) }.getOrDefault(emptyList())
        if (cameras.none { it.id == s.cameraId }) {
            // Camera ids differ between devices; fall back to the first back-facing camera.
            val pick = cameras.firstOrNull { it.facing == "back" } ?: cameras.firstOrNull()
            if (pick == null) {
                _status.update { it.copy(starting = false, error = "Nie znaleziono żadnej kamery") }
                return
            }
            logger.log("Camera ${s.cameraId} not found, using ${pick.id} (${pick.facing})")
            SettingsStore.update { it.copy(cameraId = pick.id) }
            s = SettingsStore.current
        }
        _status.update {
            ServiceStatus(starting = true, rtspPort = s.rtspPort, onvifPort = s.onvifPort, log = it.log)
        }
        if (!hasPermission(Manifest.permission.CAMERA)) {
            _status.update { it.copy(starting = false, error = "Brak uprawnienia do kamery") }
            return
        }
        acquireLocks()
        val auth = DigestAuth("OnvifIpCam") {
            val cur = SettingsStore.current
            if (cur.authEnabled) Credentials(cur.username, cur.password) else null
        }
        try {
            val audioAllowed = hasPermission(Manifest.permission.RECORD_AUDIO)
            val p = CameraPipeline(
                this, s, mainEndpoint, subEndpoint, audioAllowed,
                onMotion = { active ->
                    logger.log("Motion ${if (active) "started" else "ended"}")
                    onvif?.publishMotion(active)
                },
                onCameraStatus = { msg -> _status.update { it.copy(cameraStatus = msg) } },
            )
            p.start()
            preview?.let { (surface, w, h) -> p.setPreview(surface, w, h) }
            pipeline = p
            logger.log("Camera ${s.cameraId}: capture ${p.captureSize}, main ${p.mainSize} ${p.mainCodec}, sub ${p.subSize}")
            if (s.main.codec != p.mainCodec) logger.log("H.265 encoder not available, using H.264")
            p.audioError?.let { logger.log("Audio disabled: $it") }

            val r = RtspServer(s.rtspPort, auth, logger, "OnvifIpCam")
            r.addEndpoint(mainEndpoint)
            r.addEndpoint(subEndpoint)
            r.start()
            rtsp = r

            val device = AppOnvifDevice(s, p) {
                // Encoder changes from ONVIF clients: restart after the SOAP response has been sent.
                worker.schedule({ restartFromWorker() }, 500, TimeUnit.MILLISECONDS)
            }
            val presets = object : PresetStore {
                override fun load() = SettingsStore.getString("ptz_presets")
                override fun save(data: String) = SettingsStore.putString("ptz_presets", data)
            }
            val o = OnvifServer(device, auth, EventHub(logger), presets, Net::localIpv4, logger)
            o.start()
            o.publishMotion(false)
            onvif = o

            discovery = runCatching { WsDiscovery(device, Net::localIpv4, logger).also { it.start() } }
                .onFailure { logger.log("WS-Discovery failed: $it") }
                .getOrNull()

            registerThermal()
            _status.update { it.copy(running = true, starting = false, error = null) }
            updateNotification(s)
            statusTask = worker.scheduleWithFixedDelay({ refreshStatus() }, 0, 1, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            val msg = if (e is java.net.BindException) "Port zajęty: ${e.message}" else (e.message ?: e.toString())
            logger.log("Start failed: $msg")
            stopStreaming()
            _status.update { it.copy(running = false, starting = false, error = msg) }
        }
    }

    private fun restartFromWorker() {
        stopStreaming()
        startStreaming()
    }

    private fun stopStreaming() {
        statusTask?.cancel(false)
        statusTask = null
        unregisterThermal()
        discovery?.stop()
        discovery = null
        onvif?.stop()
        onvif = null
        rtsp?.stop()
        rtsp = null
        mainEndpoint.reset()
        subEndpoint.reset()
        pipeline?.stop()
        pipeline = null
        releaseLocks()
        _status.update { it.copy(running = false, starting = false, clients = 0, mainKbps = 0, subKbps = 0, fps = 0f, motion = false) }
    }

    private fun refreshStatus() {
        val p = pipeline ?: return
        val now = System.nanoTime()
        val frames = p.framesRendered
        val fps = if (lastFramesAt > 0) (frames - lastFrames) * 1e9f / (now - lastFramesAt) else 0f
        lastFrames = frames
        lastFramesAt = now
        _status.update {
            it.copy(
                addresses = Net.localIpv4(),
                clients = rtsp?.clientCount ?: 0,
                mainKbps = p.mainKbps,
                subKbps = p.subKbps,
                fps = fps,
                motion = p.motionActive,
                motionLevel = p.motionLevel,
                info = "${p.mainSize.width}x${p.mainSize.height} ${p.mainCodec} / ${p.subSize.width}x${p.subSize.height} H264, " +
                    "capture ${p.captureSize}",
            )
        }
    }

    private fun registerThermal() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val pm = getSystemService(PowerManager::class.java)
        val l = PowerManager.OnThermalStatusChangedListener { st ->
            val (label, scale) = when {
                st >= PowerManager.THERMAL_STATUS_SEVERE -> "krytyczna (bitrate 50%)" to 0.5f
                st >= PowerManager.THERMAL_STATUS_MODERATE -> "podwyższona (bitrate 75%)" to 0.75f
                st >= PowerManager.THERMAL_STATUS_LIGHT -> "lekko podwyższona" to 1f
                else -> "normalna" to 1f
            }
            pipeline?.setBitrateScale(scale)
            logger.log("Thermal status $st -> bitrate x$scale")
            _status.update { it.copy(thermal = label) }
        }
        pm.addThermalStatusListener(worker, l)
        thermalListener = l
    }

    private fun unregisterThermal() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        (thermalListener as? PowerManager.OnThermalStatusChangedListener)?.let {
            getSystemService(PowerManager::class.java).removeThermalStatusListener(it)
        }
        thermalListener = null
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "onvifipcam:stream").apply {
            setReferenceCounted(false)
            acquire()
        }
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "onvifipcam:wifi").apply {
            setReferenceCounted(false)
            acquire()
        }
        multicastLock = wm.createMulticastLock("onvifipcam:discovery").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.release() }
        runCatching { wifiLock?.release() }
        runCatching { multicastLock?.release() }
        wakeLock = null
        wifiLock = null
        multicastLock = null
    }

    private fun notification(s: CamSettings, ip: String?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CameraService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (ip != null) "rtsp://$ip:${s.rtspPort}/stream1" else "Uruchamianie…"
        return NotificationCompat.Builder(this, OnvifCamApp.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${s.deviceName}: kamera aktywna")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Zatrzymaj", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(s: CamSettings) {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification(s, Net.localIpv4().firstOrNull()))
    }

    private fun hasPermission(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "CameraService"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        private const val ACTION_RESTART = "restart"

        private val _status = MutableStateFlow(ServiceStatus())
        val status: StateFlow<ServiceStatus> = _status.asStateFlow()

        @Volatile private var instance: CameraService? = null
        @Volatile private var preview: Triple<Surface, Int, Int>? = null

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, CameraService::class.java).setAction(ACTION_START))
        }

        fun restart(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, CameraService::class.java).setAction(ACTION_RESTART))
        }

        fun stop(context: Context) {
            if (instance != null) context.startService(Intent(context, CameraService::class.java).setAction(ACTION_STOP))
        }

        val isRunning: Boolean get() = instance != null

        /** Attaches the in-app preview; null detaches (blocks until the renderer released it). */
        fun setPreview(surface: Surface?, width: Int, height: Int) {
            preview = surface?.let { Triple(it, width, height) }
            instance?.pipeline?.setPreview(surface, width, height)
        }

        private fun timeNow(): String =
            java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
    }
}
