package pl.arhea.onvifipcam.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import pl.arhea.onvifipcam.media.CameraSource
import pl.arhea.onvifipcam.media.VideoEncoder
import pl.arhea.onvifipcam.rtsp.VideoCodec
import pl.arhea.onvifipcam.service.CameraService
import pl.arhea.onvifipcam.settings.CamSettings
import pl.arhea.onvifipcam.settings.SettingsStore

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var s by remember { mutableStateOf(SettingsStore.current) }
    val cameras = remember { runCatching { CameraSource.list(context) }.getOrDefault(emptyList()) }
    val hevc = remember { runCatching { VideoEncoder.isSupported(VideoCodec.H265) }.getOrDefault(false) }
    // Text fields keep their own strings so partial input (e.g. empty) doesn't fight the parser.
    var mainBitrate by remember { mutableStateOf(s.main.bitrateKbps.toString()) }
    var subBitrate by remember { mutableStateOf(s.sub.bitrateKbps.toString()) }
    var rtspPort by remember { mutableStateOf(s.rtspPort.toString()) }
    var onvifPort by remember { mutableStateOf(s.onvifPort.toString()) }

    BackHandler(onBack = onBack)

    fun save() {
        val next = s.copy(
            main = s.main.copy(bitrateKbps = mainBitrate.toIntOrNull()?.coerceIn(100, 20000) ?: s.main.bitrateKbps),
            sub = s.sub.copy(bitrateKbps = subBitrate.toIntOrNull()?.coerceIn(100, 8000) ?: s.sub.bitrateKbps),
            rtspPort = rtspPort.toIntOrNull()?.takeIf { it in 1024..65535 } ?: s.rtspPort,
            onvifPort = onvifPort.toIntOrNull()?.takeIf { it in 1024..65535 } ?: s.onvifPort,
        )
        if (next != SettingsStore.current) {
            SettingsStore.update { next }
            if (CameraService.isRunning) CameraService.restart(context)
        }
        onBack()
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // No save button up here: it would sit where "Ustawienia" is on the main screen and catch double taps.
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Wróć bez zapisywania") }
                Spacer(Modifier.weight(1f))
                Text("Ustawienia", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }

            Section("Kamera")
            Choice(
                "Kamera",
                cameras.map { it.id },
                s.cameraId,
                { id -> cameras.firstOrNull { it.id == id }?.let { "${facingPl(it.facing)} (id ${it.id}, max ${it.maxSize})" } ?: "id $id" },
            ) { s = s.copy(cameraId = it) }
            Choice("Obrót obrazu", CamSettings.ROTATIONS, s.rotation, { rotationLabel(it) }) { s = s.copy(rotation = it) }
            Choice(
                "Zapas na zoom PTZ",
                CamSettings.CAPTURE_SCALES, s.captureScale,
                { if (it == 1f) "brak (najmniej obciąża telefon)" else "×$it rozdzielczości przechwytywania" },
            ) { s = s.copy(captureScale = it) }

            Section("Strumień główny (stream1)")
            Choice("Rozdzielczość", CamSettings.MAIN_RESOLUTIONS, s.main.resolution, { it.toString() }) {
                s = s.copy(main = s.main.copy(resolution = it))
            }
            Choice("Klatki/s", CamSettings.FPS_OPTIONS, s.main.fps, { "$it fps" }) { s = s.copy(main = s.main.copy(fps = it)) }
            Choice(
                "Kodek", listOf(VideoCodec.H264, VideoCodec.H265), s.main.codec,
                { if (it == VideoCodec.H265 && !hevc) "H.265 (niedostępny na tym telefonie)" else if (it == VideoCodec.H265) "H.265" else "H.264" },
            ) { s = s.copy(main = s.main.copy(codec = it)) }
            NumberField("Bitrate (kb/s)", mainBitrate) { mainBitrate = it }

            Section("Strumień pomocniczy (stream2, H.264)")
            Choice("Rozdzielczość", CamSettings.SUB_RESOLUTIONS, s.sub.resolution, { it.toString() }) {
                s = s.copy(sub = s.sub.copy(resolution = it))
            }
            Choice("Klatki/s", CamSettings.FPS_OPTIONS, s.sub.fps, { "$it fps" }) { s = s.copy(sub = s.sub.copy(fps = it)) }
            NumberField("Bitrate (kb/s)", subBitrate) { subBitrate = it }
            Choice("Klatka kluczowa co", listOf(1, 2, 3, 4, 5), s.keyframeIntervalS, { "$it s" }) { s = s.copy(keyframeIntervalS = it) }

            Section("Audio i obraz")
            Toggle("Dźwięk z mikrofonu (AAC)", s.audioEnabled) { s = s.copy(audioEnabled = it) }
            Toggle("Data i godzina na obrazie (OSD)", s.osdEnabled) { s = s.copy(osdEnabled = it) }
            OutlinedTextField(s.deviceName, { s = s.copy(deviceName = it) }, Modifier.fillMaxWidth(), label = { Text("Nazwa kamery") }, singleLine = true)

            Section("Detekcja ruchu (zdarzenia ONVIF)")
            Toggle("Wykrywaj ruch", s.motionEnabled) { s = s.copy(motionEnabled = it) }
            Text("Czułość: ${s.motionSensitivity}")
            Slider(s.motionSensitivity.toFloat(), { s = s.copy(motionSensitivity = it.toInt()) }, valueRange = 0f..100f)

            Section("Dostęp")
            OutlinedTextField(s.username, { s = s.copy(username = it.trim()) }, Modifier.fillMaxWidth(), label = { Text("Login") }, singleLine = true)
            OutlinedTextField(
                s.password, { s = s.copy(password = it) }, Modifier.fillMaxWidth(),
                label = { Text("Hasło (puste = bez autoryzacji)") }, singleLine = true,
            )
            NumberField("Port RTSP", rtspPort) { rtspPort = it }
            NumberField("Port ONVIF (HTTP)", onvifPort) { onvifPort = it }

            Section("System")
            Toggle("Uruchamiaj po restarcie telefonu", s.autostart) { s = s.copy(autostart = it) }
            Text(
                "ID urządzenia: ${s.deviceUuid}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = ::save, modifier = Modifier.fillMaxWidth()) { Text("Zapisz") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun facingPl(f: String) = when (f) {
    "back" -> "Tylna"
    "front" -> "Przednia"
    else -> "Zewnętrzna"
}

private fun rotationLabel(r: Int) = when (r) {
    0 -> "0° (poziomo)"
    90 -> "90° (pionowo)"
    180 -> "180° (poziomo, odwrócony)"
    else -> "270° (pionowo, odwrócony)"
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(6.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    HorizontalDivider()
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, { v -> onChange(v.filter { it.isDigit() }.take(6)) }, Modifier.fillMaxWidth(),
        label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun <T> Choice(label: String, options: List<T>, value: T, text: (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(text(value), Modifier.weight(1f))
                Text("▾")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (o in options) {
                    DropdownMenuItem(text = { Text(text(o)) }, onClick = { onSelect(o); open = false })
                }
            }
        }
    }
}
