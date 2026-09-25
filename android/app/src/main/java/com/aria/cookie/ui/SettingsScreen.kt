package com.aria.cookie.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aria.cookie.MainViewModel
import com.aria.cookie.Platform
import com.aria.cookie.Sensors
import com.aria.cookie.core.AUTONOMY_ALLOWED
import com.aria.cookie.core.ActionTypes
import com.aria.cookie.core.CookieState
import com.aria.cookie.core.SpotifyAuth
import com.aria.cookie.core.isAutomatic
import com.aria.cookie.core.nudgeUsefulness

private val ACTION_NAMES = mapOf(
    ActionTypes.PLAY_MUSIC to "🎧 Poner música", ActionTypes.REMINDER to "🔔 Recordatorios", ActionTypes.OPEN to "🔗 Abrir enlaces y rutas",
    ActionTypes.HOME to "🏠 Casa inteligente", ActionTypes.ALARM to "⏰ Alarmas",
)
private val NUDGE_NAMES = mapOf(
    "evento" to "📅 Eventos", "clima" to "🌦️ Clima", "sueño" to "😴 Sueño", "llegada" to "📍 Llegadas", "rutina" to "🔁 Rutinas",
    "estrés" to "💓 Estrés", "pasos" to "🚶 Movimiento", "misión" to "🎯 Misiones",
)

@Composable
fun SettingsScreen(vm: MainViewModel, state: CookieState, platform: Platform) {
    val s = vm.settings
    val ctx = LocalContext.current
    val version by vm.settingsVersion.collectAsState()
    var clientId by remember(version) { mutableStateOf(s.spotifyClientId) }
    var claudeKey by remember(version) { mutableStateOf(s.claudeKey) }
    var model by remember(version) { mutableStateOf(s.claudeModel) }
    var homeUrl by remember(version) { mutableStateOf(s.homeUrl) }
    var homeToken by remember(version) { mutableStateOf(s.homeToken.orEmpty()) }
    var mapsKey by remember(version) { mutableStateOf(s.mapsKey.orEmpty()) }
    var confirmWipe by remember { mutableStateOf(false) }
    var backupDialog by remember { mutableStateOf<String?>(null) } // "export" | "import"
    val connected = remember(version) { s.spotifyConnected }
    val save = { vm.saveSettings(clientId, claudeKey, model, homeUrl, homeToken, mapsKey) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section("✨ Claude (entender, recordar, reflexionar y hablar como tú)") {
            Hint("Con tu clave entiendo de verdad lo que me cuentas, escribo tu diario, reflexiono sobre quién eres, trabajo tus misiones, busco en la web y hablo como tú.")
            TextButton(onClick = { platform.openUrl("https://platform.claude.com/settings/keys") }) { Text("Conseguir una API key") }
            OutlinedTextField(claudeKey, { claudeKey = it }, Modifier.fillMaxWidth(), label = { Text("API key de Anthropic") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("Modelo") }, singleLine = true)
            Toggle("Guardar recuerdos de lo que le cuento", s.learnWithClaude) { vm.update { learnWithClaude = it } }
        }

        Section("🧠 ARIA · el cerebro local") {
            val stats = state.routeStats
            val local = stats["local"] ?: 0
            val claude = stats.filterKeys { it.startsWith("claude") }
            val low = claude.filterKeys { it.startsWith("claude:low") }.values.sum()
            val medium = claude.filterKeys { it.startsWith("claude:medium") }.values.sum()
            val high = claude.filterKeys { it.startsWith("claude:high") }.values.sum()
            val total = local + low + medium + high
            Hint("M1 decide si cada mensaje se responde en tu teléfono o con Claude (y con cuánto esfuerzo o búsqueda web). " +
                "M2 responde al instante lo que ya sabe de ti y aprende de tus correcciones. M3 descubre patrones y asocia recuerdos.")
            if (total > 0) {
                Field("Mensajes", "$local en tu teléfono · $low esfuerzo bajo · $medium medio · $high alto")
                val saved = 100 * (1 - (low + medium * 2 + high * 4).toDouble() / (total * 4))
                Field("Ahorro", "~${saved.toInt()}% frente a usar siempre el máximo")
            }
            val g = state.gate
            Field("Umbral M2", "${(g.threshold * 100).toInt()}%" + (g.accuracy()?.let { " · aciertos ${(it * 100).toInt()}% (${g.correct}/${g.correct + g.wrong})" } ?: ""))
            Field("Patrones", "${state.patterns.size} descubiertos")
        }

        Section("🎧 Spotify") {
            if (connected) {
                Text("Conectado. Aprendo de tus artistas, géneros y de a qué hora y dónde escuchas música.", color = SpotifyGreen)
                if (!s.spotifyCanControl) {
                    Hint("Para que tu copia pueda poner música, vuelve a conectar (se añadió un permiso nuevo).")
                    Button(onClick = platform::connectSpotify, colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen)) { Text("Volver a conectar") }
                }
                OutlinedButton(onClick = vm::disconnectSpotify) { Text("Desconectar") }
            } else {
                Hint(
                    "1. developer.spotify.com → Dashboard → Create app.\n2. Redirect URI exactamente:\n   ${SpotifyAuth.REDIRECT_URI}\n" +
                        "3. Marca «Web API», guarda y copia el Client ID aquí.\n4. En «User Management» añade tu correo de Spotify.\n" +
                        "Poner música desde la API requiere Premium; si no, abro la canción en la app."
                )
                TextButton(onClick = { platform.openUrl("https://developer.spotify.com/dashboard") }) { Text("Abrir Spotify for Developers") }
                OutlinedTextField(clientId, { clientId = it }, Modifier.fillMaxWidth(), label = { Text("Spotify Client ID") }, singleLine = true)
                Button(onClick = { save(); platform.connectSpotify() }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen), enabled = clientId.isNotBlank()) { Text("Conectar con Spotify") }
            }
        }

        Section("👀 Mis sentidos (aprendo sin que me cuentes)") {
            SenseRow("📅 Calendario", "Rutinas y me adelanto a tus eventos", s.senseCalendar && Sensors.granted(ctx, android.Manifest.permission.READ_CALENDAR), platform::requestCalendar) { vm.update { senseCalendar = false } }
            SenseRow("📍 Ubicación", "Descubro tus lugares sin guardar tu recorrido; clima y tráfico", s.senseLocation && Sensors.hasLocation(ctx), platform::requestLocation) { vm.update { senseLocation = false } }
            if (s.senseLocation && Sensors.hasLocation(ctx) && !Sensors.hasBackgroundLocation(ctx)) {
                TextButton(onClick = platform::requestBackgroundLocation) { Text("Permitir también en segundo plano («Permitir todo el tiempo»)") }
            }
            SenseRow("😴 Sueño, pasos y pulso", "Vía Health Connect; pulso para medir estrés", s.senseHealth, platform::requestHealth) { vm.update { senseHealth = false } }
            SenseRow("🏃 Movimiento", "Cuándo caminas, corres, vas en bici o en coche", s.senseMovement, platform::requestMovement) { vm.update { senseMovement = false } }
            SenseRow("📱 Uso de apps", "Qué apps usas y a qué hora (tu ritmo real)", s.senseApps && Sensors.hasUsageAccess(ctx), platform::openUsageAccess) { vm.update { senseApps = false } }
            SenseRow("💬 Con quién hablas", "De tus notificaciones de mensajes y correo: quién y cuándo, nunca el texto", s.senseNotifications && Sensors.hasNotificationAccess(ctx), platform::openNotificationAccess) { vm.update { senseNotifications = false } }
            SenseRow("📸 Momentos", "Días en que haces muchas fotos (solo la fecha, no las imágenes)", s.sensePhotos && Sensors.granted(ctx, Sensors.photoPermission), platform::requestPhotos) { vm.update { sensePhotos = false } }
            OutlinedButton(onClick = vm::senseNow) { Text("Actualizar ahora") }
        }

        Section("🗣️ Voz") {
            Toggle("Leer en voz alta las respuestas del chat", s.speakReplies) { vm.update { speakReplies = it } }
            Toggle("Manos libres: «Oye Cookie»", s.handsFree, "Te escucho aunque no tengas la app abierta (usa batería; experimental)") { platform.setHandsFree(it) }
            Hint("Añade el widget de Cookie a tu pantalla de inicio. Y puedes responderle desde las notificaciones o tu reloj.")
        }

        Section("🤖 Autonomía (qué puede hacer sin preguntarte)") {
            AUTONOMY_ALLOWED.forEach { type ->
                Toggle(ACTION_NAMES[type] ?: type, isAutomatic(state, type)) { vm.setAutonomy(type, it) }
            }
            Hint("Los mensajes nunca se envían solos: siempre te dejo el borrador.")
        }

        Section("🔔 Avisos que aprenden") {
            Toggle("Avisarme y adelantarme", s.notifications) { vm.update { notifications = it } }
            val learned = NUDGE_NAMES.mapNotNull { (k, name) -> nudgeUsefulness(state.nudgeStats, k)?.let { "$name: ${(it * 100).toInt()}% útiles" } }
            if (learned.isNotEmpty()) Hint("Lo que he aprendido de tus 👍/👎:\n" + learned.joinToString("\n"))
            else Hint("Marca 👍/👎 en mis avisos y ajustaré cuándo y de qué te aviso.")
            OutlinedTextField(mapsKey, { mapsKey = it }, Modifier.fillMaxWidth(), label = { Text("Clave de Google Maps (tráfico real, opcional)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            Hint("Con la clave (Routes API) te aviso de cuándo salir según el tráfico. El clima funciona sin clave.")
        }

        Section("🏠 Casa inteligente (Home Assistant)") {
            Hint("Perfil → Seguridad → Token de acceso de larga duración.")
            OutlinedTextField(homeUrl, { homeUrl = it }, Modifier.fillMaxWidth(), label = { Text("URL (p. ej. http://homeassistant.local:8123)") }, singleLine = true)
            OutlinedTextField(homeToken, { homeToken = it }, Modifier.fillMaxWidth(), label = { Text("Token") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        }

        Button(onClick = save, Modifier.fillMaxWidth()) { Text("Guardar ajustes") }

        Section("💾 Copia de seguridad de tu copia") {
            Hint("Un archivo cifrado con tu contraseña con todo lo que sé de ti. Si cambias de teléfono, la restauras y sigo siendo yo.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { backupDialog = "export" }) { Text("Exportar") }
                OutlinedButton(onClick = { backupDialog = "import" }) { Text("Restaurar") }
            }
        }

        Section("🔒 Seguridad y privacidad") {
            Toggle("Pedir huella o PIN al abrir", s.biometricLock) { vm.update { biometricLock = it } }
            Hint(
                "Tu perfil, recuerdos y claves están cifrados con una clave del propio teléfono (Android Keystore). " +
                    "Solo sale: búsquedas a Google News, clima (Open-Meteo), Spotify/Home Assistant/Google Maps si los configuras, " +
                    "y lo necesario a Claude para entenderte y responder como tú."
            )
            OutlinedButton(onClick = platform::shareDiagnostics) { Text("Enviar diagnóstico de errores") }
            OutlinedButton(onClick = { confirmWipe = true }, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text("Olvidar todo lo que sabes de mí")
            }
        }
    }

    if (confirmWipe) {
        ConfirmDialog("¿Borrar todo?", "Se borrará tu perfil, recuerdos, diario, retratos, misiones, lugares, música, conversaciones y claves.", "Borrar todo", vm::wipeAll) { confirmWipe = false }
    }
    backupDialog?.let { mode ->
        TextDialog(
            if (mode == "export") "Contraseña para cifrar la copia" else "Contraseña de la copia",
            "mínimo 8 caracteres", if (mode == "export") "Elegir dónde guardar" else "Elegir archivo", password = true,
            onConfirm = { pw -> if (mode == "export") platform.exportBackup(pw) else platform.importBackup(pw) },
        ) { backupDialog = null }
    }
}

@Composable
private fun SenseRow(title: String, desc: String, on: Boolean, onEnable: () -> Unit, onDisable: () -> Unit) =
    Toggle(title, on, desc) { if (it) onEnable() else onDisable() }
