package com.aria.cookie.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aria.cookie.MainViewModel
import com.aria.cookie.core.CookieState
import com.aria.cookie.core.Item
import com.aria.cookie.core.SpotifyAuth
import com.aria.cookie.core.greeting
import com.aria.cookie.core.zoned
import java.time.format.DateTimeFormatter

private enum class Tab(val label: String, val icon: ImageVector) {
    Hoy("Hoy", Icons.Filled.WbSunny),
    Copia("Tu copia", Icons.Filled.Face),
    Tu("Tú", Icons.Filled.Person),
    Ajustes("Ajustes", Icons.Filled.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CookieRoot(vm: MainViewModel, openUrl: (String) -> Unit, connectSpotify: () -> Unit) {
    val state by vm.state.collectAsState()
    val busy by vm.busy.collectAsState()
    val toast by vm.toast.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(Tab.Hoy) }

    LaunchedEffect(toast) {
        toast?.let { snackbar.showSnackbar(it); vm.toast.value = null }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text("🍪 Cookie", fontWeight = FontWeight.Bold) })
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t, onClick = { tab = t },
                        icon = { Icon(t.icon, null) }, label = { Text(t.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                Tab.Hoy -> TodayScreen(vm, state, openUrl) { tab = Tab.Tu }
                Tab.Copia -> TwinScreen(vm, state)
                Tab.Tu -> ProfileScreen(vm, state)
                Tab.Ajustes -> SettingsScreen(vm, connectSpotify, openUrl)
            }
        }
    }
}

// ==================== HOY ====================

@Composable
private fun TodayScreen(vm: MainViewModel, state: CookieState, openUrl: (String) -> Unit, goProfile: () -> Unit) {
    val now = System.currentTimeMillis()
    val p = state.profile
    val recent = state.inbox.filter { it.feedback >= 0 && now - it.foundAt < 3L * 24 * 3600 * 1000 }
        .sortedWith(compareBy<Item> { it.delivered }.thenByDescending { it.score }).take(20)

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(greeting(p, now), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            p.predictNext(now)?.let {
                Text("🔮 Creo que ahora toca: ${it.activity} (${it.reason})", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            p.music.nowPlaying?.let { Text("🎧 Estás escuchando: $it", color = SpotifyGreen) }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::research) { Text("🔎 Investigar ahora") }
                if (state.lastResearch > 0) {
                    Text(
                        "Última vez: " + zoned(state.lastResearch).format(DateTimeFormatter.ofPattern("EEE HH:mm")),
                        Modifier.align(Alignment.CenterVertically),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            state.lastError?.let { Text("⚠️ $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
        }
        if (p.interests.isEmpty()) {
            item {
                InfoCard(
                    "Aún no te conozco",
                    "Cuéntame a qué te dedicas y qué te gusta en la pestaña «Tú», o conecta Spotify en Ajustes. " +
                        "A partir de ahí investigo solo y te aviso cuando sueles estar disponible.",
                    action = "Contarle algo" to goProfile,
                )
            }
        } else if (recent.isEmpty()) {
            item { InfoCard("Nada nuevo por ahora", "Investigo por mi cuenta cada 3 horas. Pulsa «Investigar ahora» si no quieres esperar.") }
        }
        items(recent, key = { it.id }) { ItemCard(it, openUrl, vm::feedback) }
    }
}

@Composable
private fun ItemCard(it: Item, openUrl: (String) -> Unit, feedback: (String, Boolean) -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable { openUrl(it.url) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = {}, label = { Text(it.topic) })
                Spacer(Modifier.width(8.dp))
                if (!it.delivered) Text("NUEVO", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f))
                if (it.source == "claude") Text("✨", style = MaterialTheme.typography.labelSmall)
            }
            Text(it.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (it.summary.isNotBlank()) Text(it.summary, style = MaterialTheme.typography.bodyMedium)
            if (it.why.isNotBlank()) {
                Text("↳ ${it.why}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = { feedback(it.id, false) }) { Icon(Icons.Filled.ThumbDown, "No me interesa") }
                IconButton(onClick = { feedback(it.id, true) }) {
                    Icon(Icons.Filled.ThumbUp, "Me sirvió", tint = if (it.feedback > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String, action: Pair<String, () -> Unit>? = null) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            action?.let { (label, go) -> Button(onClick = go) { Text(label) } }
        }
    }
}

// ==================== TU COPIA ====================

@Composable
private fun TwinScreen(vm: MainViewModel, state: CookieState) {
    val thinking by vm.twinThinking.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    val chat = state.chat
    LaunchedEffect(chat.size, thinking) { if (chat.isNotEmpty()) list.animateScrollToItem(chat.size - 1 + if (thinking) 1 else 0) }

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (chat.isEmpty()) {
                item {
                    InfoCard(
                        "Tu segunda copia",
                        "Soy tú, versión digital. Aprendo de lo que me cuentas, de tu Spotify y de tu rutina. " +
                            "Pregúntame «¿qué haría yo hoy?», «¿qué me pongo a escuchar?» o cuéntame tu día: todo lo que digas aquí también me enseña.",
                    )
                }
            }
            items(chat) { m ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start) {
                    Text(
                        m.text,
                        Modifier.widthIn(max = 300.dp)
                            .background(
                                if (m.fromUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(16.dp),
                            )
                            .padding(12.dp),
                        color = if (m.fromUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (thinking) item { Text("Pensando como tú… 🍪", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                input, { input = it }, Modifier.weight(1f),
                placeholder = { Text("Háblale a tu copia…") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                maxLines = 4,
            )
            IconButton(onClick = { vm.sendToTwin(input); input = "" }, enabled = input.isNotBlank() && !thinking) {
                Icon(Icons.AutoMirrored.Filled.Send, "Enviar")
            }
        }
    }
}

// ==================== TÚ ====================

@Composable
private fun ProfileScreen(vm: MainViewModel, state: CookieState) {
    val p = state.profile
    val now = System.currentTimeMillis()
    var input by rememberSaveable { mutableStateOf("") }
    var toForget by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Cuéntame algo de ti", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            input, { input = it }, Modifier.fillMaxWidth(),
            placeholder = { Text("«Trabajo como enfermera, me encanta el senderismo y hoy fui al gimnasio»") },
            minLines = 2,
        )
        Button(onClick = { vm.learn(input); input = "" }, enabled = input.isNotBlank()) { Text("🧠 Aprende esto") }
        HorizontalDivider()

        Section("Quién eres") {
            Field("Nombre", p.name)
            Field("Trabajo", p.occupation)
            Field("Vives en", p.location)
            Field("Conversaciones", p.observations.toString())
        }
        Section("❤️ Lo que te importa (mantén pulsado para olvidar)") {
            if (p.interests.isEmpty()) Text("Aún nada.")
            val top = p.topInterests(20, now)
            val max = top.maxOfOrNull { it.effectiveWeight(now) } ?: 1.0
            top.forEach { i ->
                Column(Modifier.fillMaxWidth().clickable { toForget = i.topic }.padding(vertical = 4.dp)) {
                    Row {
                        Text(i.topic, Modifier.weight(1f))
                        Text(i.kind, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LinearProgressIndicator(
                        progress = { (i.effectiveWeight(now) / max).toFloat() },
                        Modifier.fillMaxWidth().height(6.dp),
                    )
                }
            }
        }
        if (p.dislikes.isNotEmpty()) Section("🚫 No te interesa") { Text(p.dislikes.keys.sorted().joinToString(", ")) }
        val m = p.music
        if (m.connected) {
            Section("🎧 Tu música") {
                m.spotifyName?.let { Field("Spotify", it) }
                if (m.topArtists.isNotEmpty()) Field("Artistas", m.topArtists.take(8).joinToString(", "))
                if (m.genres.isNotEmpty()) Field("Géneros", m.genres.take(6).joinToString(", "))
                if (m.topTracks.isNotEmpty()) Field("Del momento", m.topTracks.take(3).joinToString("\n"))
                val byHour = listOf(7, 12, 18, 22).mapNotNull { h -> m.favoriteAt(h)?.let { "%02d:00 → %s".format(h, it) } }
                if (byHour.isNotEmpty()) Field("Según la hora", byHour.joinToString("\n"))
                OutlinedButton(onClick = vm::syncSpotify) { Text("Sincronizar ahora") }
            }
        }
        if (p.routines.isNotEmpty()) {
            Section("🔁 Tus rutinas") {
                p.routines.values.sortedByDescending { it.count }.take(10).forEach { r ->
                    val h = r.hourCounts.indices.maxBy { r.hourCounts[it] }
                    Text("${r.activity}: ${r.count} veces, normalmente ~%02d:00".format(h))
                }
            }
        }
        val peaks = p.peakHours(zoned(now).dayOfWeek.value % 7).take(3)
        if (peaks.isNotEmpty()) {
            Section("⏰ Tu ritmo") {
                Text("Sueles estar activo a las " + peaks.joinToString(", ") { "%02d:00".format(it) } + ". Ahí te aviso de lo nuevo.")
            }
        }
    }

    toForget?.let { topic ->
        AlertDialog(
            onDismissRequest = { toForget = null },
            title = { Text("¿Olvidar «$topic»?") },
            text = { Text("Dejaré de investigar sobre esto.") },
            confirmButton = { TextButton(onClick = { vm.forget(topic); toForget = null }) { Text("Olvidar") } },
            dismissButton = { TextButton(onClick = { toForget = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun Field(label: String, value: String?) {
    Row {
        Text(label, Modifier.width(110.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value ?: "(aún no lo sé)")
    }
}

// ==================== AJUSTES ====================

@Composable
private fun SettingsScreen(vm: MainViewModel, connectSpotify: () -> Unit, openUrl: (String) -> Unit) {
    val s = vm.settings
    val version by vm.settingsVersion.collectAsState()
    var clientId by remember(version) { mutableStateOf(s.spotifyClientId) }
    var claudeKey by remember(version) { mutableStateOf(s.claudeKey) }
    var model by remember(version) { mutableStateOf(s.claudeModel) }
    var notify by remember(version) { mutableStateOf(s.notifications) }
    var confirmWipe by remember { mutableStateOf(false) }
    val connected = remember(version) { s.spotifyConnected }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section("🎧 Spotify") {
            if (connected) {
                Text("Conectado. Aprendo de tus artistas, géneros y de a qué hora escuchas música.", color = SpotifyGreen)
                OutlinedButton(onClick = vm::disconnectSpotify) { Text("Desconectar") }
            } else {
                Text(
                    "Para darme acceso a tu Spotify (solo lectura):\n" +
                        "1. Entra en developer.spotify.com → Dashboard → Create app.\n" +
                        "2. En «Redirect URIs» pon exactamente:\n   ${SpotifyAuth.REDIRECT_URI}\n" +
                        "3. Marca «Web API», guarda y copia el Client ID aquí.\n" +
                        "4. (Modo desarrollo) en «User Management» añade tu correo de Spotify.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { openUrl("https://developer.spotify.com/dashboard") }) { Text("Abrir Spotify for Developers") }
                OutlinedTextField(clientId, { clientId = it }, Modifier.fillMaxWidth(), label = { Text("Spotify Client ID") }, singleLine = true)
                Button(
                    onClick = { vm.saveSettings(clientId, claudeKey, model, notify); connectSpotify() },
                    colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen),
                    enabled = clientId.isNotBlank(),
                ) { Text("Conectar con Spotify") }
            }
        }
        Section("✨ Claude (investigación y tu copia)") {
            Text(
                "Con tu clave de Claude, Cookie busca en la web por ti y tu copia puede hablar. " +
                    "Sin clave, investiga solo con Google News.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { openUrl("https://platform.claude.com/settings/keys") }) { Text("Conseguir una API key") }
            OutlinedTextField(
                claudeKey, { claudeKey = it }, Modifier.fillMaxWidth(), label = { Text("API key de Anthropic") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
            )
            OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("Modelo") }, singleLine = true)
        }
        Section("🔔 Avisos") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Avisarme en mis horas activas", Modifier.weight(1f))
                Switch(notify, { notify = it })
            }
        }
        Button(onClick = { vm.saveSettings(clientId, claudeKey, model, notify) }, Modifier.fillMaxWidth()) { Text("Guardar ajustes") }

        Section("🔒 Privacidad") {
            Text(
                "Todo lo que sé de ti vive solo en este teléfono. Lo único que sale es: las búsquedas a Google News, " +
                    "las lecturas a Spotify y, si pones tu clave, tu perfil resumido a Claude para investigar y hablar como tú.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = { confirmWipe = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Olvidar todo lo que sabes de mí") }
        }
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("¿Borrar todo?") },
            text = { Text("Se borrará tu perfil, tu música, tu diario, las conversaciones con tu copia y las claves guardadas.") },
            confirmButton = { TextButton(onClick = { vm.wipeAll(); confirmWipe = false }) { Text("Borrar todo") } },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancelar") } },
        )
    }
}

