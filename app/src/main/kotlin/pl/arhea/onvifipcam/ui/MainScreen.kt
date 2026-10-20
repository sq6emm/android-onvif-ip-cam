package pl.arhea.onvifipcam.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.arhea.onvifipcam.service.CameraService
import pl.arhea.onvifipcam.service.ServiceStatus
import pl.arhea.onvifipcam.settings.SettingsStore

@Composable
fun MainScreen(
    env: AppEnvironment,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSettings: () -> Unit,
    onRequestBattery: () -> Unit,
    onRequestOverlay: () -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
) {
    val status by CameraService.status.collectAsStateWithLifecycle()
    val settings by SettingsStore.settings.collectAsStateWithLifecycle()
    var showPreview by rememberSaveable { mutableStateOf(true) }
    var showLog by rememberSaveable { mutableStateOf(false) }
    var keepOn by rememberSaveable { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("ONVIF IP Cam", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(settings.deviceName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onSettings) { Text("Ustawienia") }
            }

            val rotated = settings.rotation == 90 || settings.rotation == 270
            val r = settings.main.resolution
            val aspect = if (rotated) r.height.toFloat() / r.width else r.width.toFloat() / r.height
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspect.coerceIn(0.5f, 2.5f))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (status.running && showPreview) {
                    PreviewSurface(Modifier.fillMaxSize())
                } else {
                    Text(
                        if (status.running) "Podgląd wyłączony" else if (status.starting) "Uruchamianie…" else "Kamera zatrzymana",
                        color = Color.White,
                    )
                }
                if (status.motion) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .background(Color(0xCCE53935), MaterialTheme.shapes.small)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) { Text("RUCH", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (status.running || status.starting) {
                    Button(
                        onClick = onStop,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) { Text("Zatrzymaj") }
                } else {
                    Button(onClick = onStart) { Text("Uruchom kamerę") }
                }
                Spacer(Modifier.weight(1f))
                Text("Podgląd", style = MaterialTheme.typography.bodySmall)
                Switch(checked = showPreview, onCheckedChange = { showPreview = it })
            }

            status.error?.let { ErrorCard(it) }
            status.cameraStatus?.let { ErrorCard(it) }
            if (!env.hasCamera) ErrorCard("Aplikacja potrzebuje uprawnienia do kamery. Naciśnij „Uruchom kamerę”.")

            StatusCard(status)
            AddressesCard(status, settings.username, settings.password, settings.authEnabled)

            if (!env.ignoringBattery) {
                ActionCard(
                    "Optymalizacja baterii jest włączona. Android może uśpić kamerę przy wygaszonym ekranie.",
                    "Wyłącz optymalizację", onRequestBattery,
                )
            }
            if (settings.autostart && !env.canOverlay) {
                ActionCard(
                    "Autostart po restarcie wymaga uprawnienia „Wyświetlanie nad innymi aplikacjami” (Android 11+).",
                    "Nadaj uprawnienie", onRequestOverlay,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Nie wygaszaj ekranu", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = keepOn, onCheckedChange = { keepOn = it; onKeepScreenOn(it) })
            }

            OutlinedButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Ukryj dziennik" else "Pokaż dziennik") }
            if (showLog) {
                Card(Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Text(
                            status.log.reversed().joinToString("\n").ifEmpty { "(pusto)" },
                            Modifier.padding(12.dp),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PreviewSurface(modifier: Modifier) {
    val callback = remember {
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                CameraService.setPreview(holder.surface, width, height)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                CameraService.setPreview(null, 0, 0)
            }
        }
    }
    DisposableEffect(Unit) { onDispose { CameraService.setPreview(null, 0, 0) } }
    AndroidView(
        modifier = modifier,
        factory = { ctx -> SurfaceView(ctx).apply { holder.addCallback(callback) } },
    )
}

@Composable
private fun StatusCard(s: ServiceStatus) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val color = when {
                    s.running && s.cameraStatus == null -> Color(0xFF43A047)
                    s.starting -> Color(0xFFFFA000)
                    s.running -> Color(0xFFFFA000)
                    else -> Color(0xFF9E9E9E)
                }
                Box(Modifier.size(10.dp).background(color, CircleShape))
                Spacer(Modifier.size(8.dp))
                Text(
                    when {
                        s.running -> "Nadaje"
                        s.starting -> "Uruchamianie"
                        else -> "Zatrzymana"
                    },
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (s.running) {
                Text("Klienci RTSP: ${s.clients}")
                Text("Klatki: %.1f fps   main: %d kb/s   sub: %d kb/s".format(s.fps, s.mainKbps, s.subKbps))
                Text("Ruch: ${if (s.motion) "TAK" else "nie"} (%.0f%% pola)".format(s.motionLevel * 100))
                if (s.thermal.isNotEmpty()) Text("Temperatura: ${s.thermal}")
                if (s.info.isNotEmpty()) Text(s.info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun AddressesCard(s: ServiceStatus, user: String, password: String, auth: Boolean) {
    if (!s.running) return
    Card(Modifier.fillMaxWidth()) {
        SelectionContainer {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Adresy", fontWeight = FontWeight.SemiBold)
                if (s.addresses.isEmpty()) Text("Brak sieci. Połącz telefon z Wi-Fi.")
                for (ip in s.addresses) {
                    Text("rtsp://$ip:${s.rtspPort}/stream1", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text("rtsp://$ip:${s.rtspPort}/stream2", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text("ONVIF: $ip:${s.onvifPort}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text("Snapshot: http://$ip:${s.onvifPort}/onvif/snapshot.jpg", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
                Spacer(Modifier.height(4.dp))
                if (auth) {
                    Text("Login: $user   Hasło: $password", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                } else {
                    Text("Autoryzacja wyłączona (puste hasło)", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(text: String) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(text, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable
private fun ActionCard(text: String, action: String, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(text, color = MaterialTheme.colorScheme.onSecondaryContainer)
            TextButton(onClick = onClick) { Text(action) }
        }
    }
}
