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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aria.cookie.MainViewModel
import com.aria.cookie.Platform
import com.aria.cookie.Sensors
import com.aria.cookie.core.ChatMessage
import com.aria.cookie.core.CookieState
import com.aria.cookie.core.Item
import com.aria.cookie.core.MEMORY_KINDS
import com.aria.cookie.core.PendingAction
import com.aria.cookie.core.SpotifyAuth
import com.aria.cookie.core.currentPlace
import com.aria.cookie.core.greeting
import com.aria.cookie.core.topApps
import com.aria.cookie.core.twinSimilarity
import com.aria.cookie.core.zoned
import java.time.format.DateTimeFormatter

private enum class Tab(val label: String, val icon: ImageVector) {
    Hoy("Hoy", Icons.Filled.WbSunny),
    Copia("Tu copia", Icons.Filled.Face),
    Tu("Tú", Icons.Filled.Person),
    Ajustes("Ajustes", Icons.Filled.Settings),
}

private val DAY_HM = DateTimeFormatter.ofPattern("EEE HH:mm")
private val HM = DateTimeFormatter.ofPattern("HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CookieRoot(vm: MainViewModel, platform: Platform, startVoice: Boolean, onVoiceStarted: () -> Unit) {
    val state by vm.state.collectAsState()
    val busy by vm.busy.collectAsState()
    val toast by vm.toast.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(Tab.Hoy) }

    LaunchedEffect(toast) { toast?.let { snackbar.showSnackbar(it); vm.toast.value = null } }
    LaunchedEffect(startVoice) {
        if (startVoice) {
            tab = Tab.Copia
            onVoiceStarted()
            platform.listen { vm.sendToTwin(it) }
        }
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
                    NavigationBarItem(selected = tab == t, onClick = { tab = t }, icon = { Icon(t.icon, null) }, label = { Text(t.label) })
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                Tab.Hoy -> TodayScreen(vm, state, platform) { tab = Tab.Tu }
                Tab.Copia -> TwinScreen(vm, state, platform)
                Tab.Tu -> ProfileScreen(vm, state, platform)
                Tab.Ajustes -> SettingsScreen(vm, platform)
            }
        }
    }
}

@Composable
fun LockedScreen(unlock: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.primary)
        Text("Tu copia está protegida", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
        Button(onClick = unlock) { Text("Desbloquear") }
    }
}

// ==================== HOY ====================

@Composable
private fun TodayScreen(vm: MainViewModel, state: CookieState, platform: Platform, goProfile: () -> Unit) {
    val now = System.currentTimeMillis()
    val p = state.profile
    val recent = state.inbox.filter { it.feedback >= 0 && now - it.foundAt < 3L * 24 * 3600 * 1000 }
        .sortedWith(compareBy<Item> { it.delivered }.thenByDescending { it.score }).take(20)
    val pending = state.actions.filter { it.status == "pendiente" && now - it.createdAt < 24 * 3_600_000L }.takeLast(5).reversed()

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(greeting(p, now), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            p.predictNext(now)?.let { Text("🔮 Creo que ahora toca: ${it.activity} (${it.reason})", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            currentPlace(state)?.takeIf { it.known }?.let { Text("📍 Estás en ${it.label}") }
            state.upcoming.firstOrNull { it.start > now }?.let { Text("📅 ${zoned(it.start).format(DAY_HM)} · ${it.title}") }
            state.health.lastNight(now)?.let { Text("😴 Anoche: ${it.minutes / 60}h ${it.minutes % 60}min") }
            p.music.nowPlaying?.let { Text("🎧 Estás escuchando: $it", color = SpotifyGreen) }
        }
        if (pending.isNotEmpty()) {
            item { Text("Tu copia quiere hacer por ti", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(pending, key = { "a" + it.id }) { ActionCard(it, vm, platform) }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::research) { Text("🔎 Investigar ahora") }
                if (state.lastResearch > 0) {
                    Text("Última vez: " + zoned(state.lastResearch).format(DAY_HM), Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.labelMedium)
                }
            }
            state.lastError?.let { Text("⚠️ $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
        }
        if (p.interests.isEmpty()) {
            item {
                InfoCard(
                    "Aún no te conozco",
                    "Cuéntame a qué te dedicas y qué te gusta en «Tú», conecta Spotify y activa mis sentidos en Ajustes. " +
                        "A partir de ahí aprendo solo, investigo y me adelanto a lo que necesitas.",
                    action = "Contarle algo" to goProfile,
                )
            }
        } else if (recent.isEmpty()) {
            item { InfoCard("Nada nuevo por ahora", "Investigo por mi cuenta cada 3 horas. Pulsa «Investigar ahora» si no quieres esperar.") }
        }
        items(recent, key = { it.id }) { ItemCard(it, platform::openUrl, vm::feedback) }
    }
}

@Composable
private fun ActionCard(a: PendingAction, vm: MainViewModel, platform: Platform) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp)) {
            Text(a.description, fontWeight = FontWeight.SemiBold)
            when (a.status) {
                "pendiente" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { platform.confirmAction(a.id) }) { Text("Hazlo") }
                    TextButton(onClick = { vm.cancelAction(a.id) }) { Text("No") }
                }
                "hecha" -> Text("✅ ${a.result ?: "Hecho"}", color = MaterialTheme.colorScheme.primary)
                "error" -> Text("⚠️ ${a.result}", color = MaterialTheme.colorScheme.error)
                else -> Text("Cancelado", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ItemCard(it: Item, openUrl: (String) -> Unit, feedback: (String, Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { openUrl(it.url) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
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
            if (it.why.isNotBlank()) Text("↳ ${it.why}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
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
private fun TwinScreen(vm: MainViewModel, state: CookieState, platform: Platform) {
    val thinking by vm.twinThinking.collectAsState()
    val quiz by vm.quiz.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    var correcting by remember { mutableStateOf<ChatMessage?>(null) }
    val list = rememberLazyListState()
    val chat = state.chat
    val actions = state.actions.associateBy { it.id }
    LaunchedEffect(chat.size, thinking) { if (chat.isNotEmpty()) list.animateScrollToItem(list.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1) }

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { QuizCard(vm, state, quiz) }
            if (chat.isEmpty()) {
                item {
                    InfoCard(
                        "Tu segunda copia",
                        "Soy tú, versión digital. Aprendo de lo que me cuentas, de tu Spotify, tu agenda, tus lugares, tu sueño y tus apps. " +
                            "Pregúntame «¿qué haría yo hoy?», pídeme «pon mi música de gimnasio» o «recuérdame llamar a mamá a las 7». " +
                            "Si respondo distinto a ti, corrígeme con ✏️.",
                    )
                }
            }
            items(chat) { m ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.fromUser) Alignment.End else Alignment.Start) {
                    Text(
                        m.text,
                        Modifier.widthIn(max = 300.dp)
                            .background(if (m.fromUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                            .padding(12.dp),
                        color = if (m.fromUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    )
                    m.actions.mapNotNull { actions[it] }.forEach { a -> Box(Modifier.widthIn(max = 300.dp).padding(top = 4.dp)) { ActionCard(a, vm, platform) } }
                    if (!m.fromUser && !m.text.startsWith("⚠️")) {
                        Row {
                            TextButton(onClick = { vm.approveTwin(m) }, enabled = !m.approved) {
                                Icon(Icons.Filled.Check, null); Text(if (m.approved) " Así hablo yo" else " Así diría yo", style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick = { correcting = m }) { Icon(Icons.Filled.Edit, null); Text(" Yo diría…", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }
            if (thinking) item { Text("Pensando como tú… 🍪", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { platform.listen { vm.sendToTwin(it) } }) { Icon(Icons.Filled.Mic, "Hablar") }
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Háblale a tu copia…") }, maxLines = 4)
            IconButton(onClick = { vm.sendToTwin(input); input = "" }, enabled = input.isNotBlank() && !thinking) {
                Icon(Icons.AutoMirrored.Filled.Send, "Enviar")
            }
        }
    }

    correcting?.let { m ->
        var better by remember(m) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { correcting = null },
            title = { Text("¿Cómo lo dirías tú?") },
            text = {
                Column {
                    Text("Tu copia dijo: «${m.text.take(200)}»", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(better, { better = it }, Modifier.fillMaxWidth().padding(top = 8.dp), minLines = 2)
                }
            },
            confirmButton = { TextButton(onClick = { vm.correctTwin(m, better); correcting = null }) { Text("Enseñarle") } },
            dismissButton = { TextButton(onClick = { correcting = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun QuizCard(vm: MainViewModel, state: CookieState, quiz: com.aria.cookie.QuizUi) {
    var answer by rememberSaveable(quiz.question) { mutableStateOf("") }
    val similarity = twinSimilarity(state)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🧬 ¿Qué haría yo?", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(similarity?.let { "Parecido: $it%" } ?: "Sin medir", color = MaterialTheme.colorScheme.primary)
            }
            when {
                quiz.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                quiz.question != null -> {
                    Text(quiz.question, style = MaterialTheme.typography.bodyLarge)
                    Text("Tu copia ya escribió su respuesta en secreto. Responde tú:", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(answer, { answer = it }, Modifier.fillMaxWidth(), minLines = 2)
                    Row {
                        Button(onClick = { vm.answerQuiz(answer) }, enabled = answer.isNotBlank()) { Text("Comparar") }
                        TextButton(onClick = vm::closeQuiz) { Text("Saltar") }
                    }
                }
                else -> {
                    quiz.last?.let { r ->
                        Text("Tu copia dijo: «${r.twinGuess}»", style = MaterialTheme.typography.bodySmall)
                        Text("Parecido en esta: ${(r.score * 100).toInt()}%" + (r.lesson.takeIf { it.isNotBlank() }?.let { " · Aprendí: $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Te hago una pregunta; tu copia responde a escondidas y comparamos. Cada ronda la hace más parecida a ti.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = vm::startQuiz) { Text(if (quiz.last == null) "Ponme a prueba" else "Otra") }
                }
            }
        }
    }
}

// ==================== TÚ ====================

@Composable
private fun ProfileScreen(vm: MainViewModel, state: CookieState, platform: Platform) {
    val p = state.profile
    val now = System.currentTimeMillis()
    var input by rememberSaveable { mutableStateOf("") }
    var toForget by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<com.aria.cookie.core.Place?>(null) }
    var memoryToDelete by remember { mutableStateOf<com.aria.cookie.core.Memory?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Cuéntame algo de ti", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("«Mi hermana Laura vive en Madrid y estoy preparando un maratón»") }, minLines = 2)
            IconButton(onClick = { platform.listen { input = it } }) { Icon(Icons.Filled.Mic, "Dictar") }
        }
        Button(onClick = { vm.learn(input); input = "" }, enabled = input.isNotBlank()) { Text("🧠 Aprende esto") }
        HorizontalDivider()

        Section("Quién eres") {
            Field("Nombre", p.name)
            Field("Trabajo", p.occupation)
            Field("Vives en", p.location)
            Field("Recuerdos", state.memories.size.toString())
            twinSimilarity(state)?.let { Field("Tu copia", "se parece a ti un $it%") }
        }
        if (state.memories.isNotEmpty()) {
            Section("🧠 Lo que recuerdo (toca para borrar)") {
                MEMORY_KINDS.forEach { kind ->
                    val list = state.memories.filter { it.kind == kind }.sortedByDescending { it.lastSeen }.take(8)
                    if (list.isNotEmpty()) {
                        Text(kind.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        list.forEach { m -> Text("• ${m.text}", Modifier.fillMaxWidth().clickable { memoryToDelete = m }.padding(vertical = 2.dp)) }
                    }
                }
            }
        }
        Section("❤️ Lo que te importa (toca para olvidar)") {
            if (p.interests.isEmpty()) Text("Aún nada.")
            val top = p.topInterests(20, now)
            val max = top.maxOfOrNull { it.effectiveWeight(now) } ?: 1.0
            top.forEach { i ->
                Column(Modifier.fillMaxWidth().clickable { toForget = i.topic }.padding(vertical = 4.dp)) {
                    Row {
                        Text(i.topic, Modifier.weight(1f))
                        Text(i.kind, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LinearProgressIndicator(progress = { (i.effectiveWeight(now) / max).toFloat() }, modifier = Modifier.fillMaxWidth().height(6.dp))
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
        if (state.places.isNotEmpty()) {
            Section("📍 Tus lugares (toca para ponerles nombre)") {
                state.places.sortedByDescending { it.samples }.take(8).forEach { pl ->
                    val h = pl.hourCounts.indices.maxBy { pl.hourCounts[it] }
                    Text(
                        "${pl.label}: ${pl.visits} visitas, sueles estar ~%02d:00".format(h) + (pl.favoriteMusic()?.let { " · aquí escuchas $it" } ?: ""),
                        Modifier.fillMaxWidth().clickable { renaming = pl }.padding(vertical = 4.dp),
                    )
                }
            }
        }
        if (state.upcoming.isNotEmpty()) {
            Section("📅 Tu agenda") {
                state.upcoming.filter { it.start > now }.take(6).forEach { Text("${zoned(it.start).format(DAY_HM)} · ${it.title}") }
            }
        }
        if (state.health.sleep.isNotEmpty() || state.health.stepsToday > 0) {
            Section("😴 Descanso y movimiento") {
                val avg = state.health.averageSleepMinutes()
                if (avg > 0) Field("Duermes", "${avg / 60}h ${avg % 60}min de media")
                state.health.lastNight(now)?.let { Field("Anoche", "${zoned(it.start).format(HM)} → ${zoned(it.end).format(HM)}") }
                if (state.health.stepsToday > 0) Field("Pasos hoy", "${state.health.stepsToday} (media ${state.health.stepsAvg})")
            }
        }
        val apps = topApps(state)
        if (apps.isNotEmpty()) {
            Section("📱 Tus apps") {
                apps.forEach { a ->
                    val h = a.hourMinutes.indices.maxBy { a.hourMinutes[it] }
                    Text("${a.label}: ~${a.minutes.toInt()} min/día, sobre todo a las %02d:00".format(h))
                }
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
            Section("⏰ Tu ritmo") { Text("Sueles estar activo a las " + peaks.joinToString(", ") { "%02d:00".format(it) } + ". Ahí te aviso de lo nuevo.") }
        }
    }

    toForget?.let { topic ->
        ConfirmDialog("¿Olvidar «$topic»?", "Dejaré de investigar sobre esto.", "Olvidar", { vm.forget(topic) }) { toForget = null }
    }
    memoryToDelete?.let { mem ->
        ConfirmDialog("¿Borrar este recuerdo?", mem.text, "Borrar", { vm.forgetMemory(mem.id) }) { memoryToDelete = null }
    }
    renaming?.let { pl ->
        var name by remember(pl.id) { mutableStateOf(if (pl.custom) pl.label else "") }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("¿Qué es este lugar?") },
            text = { OutlinedTextField(name, { name = it }, placeholder = { Text("gimnasio, casa de mis padres…") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.renamePlace(pl.id, name); renaming = null }, enabled = name.isNotBlank()) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, dismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); dismiss() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancelar") } },
    )
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
private fun SettingsScreen(vm: MainViewModel, platform: Platform) {
    val s = vm.settings
    val ctx = LocalContext.current
    val version by vm.settingsVersion.collectAsState()
    var clientId by remember(version) { mutableStateOf(s.spotifyClientId) }
    var claudeKey by remember(version) { mutableStateOf(s.claudeKey) }
    var model by remember(version) { mutableStateOf(s.claudeModel) }
    var homeUrl by remember(version) { mutableStateOf(s.homeUrl) }
    var homeToken by remember(version) { mutableStateOf(s.homeToken.orEmpty()) }
    var confirmWipe by remember { mutableStateOf(false) }
    val connected = remember(version) { s.spotifyConnected }
    val save = { vm.saveSettings(clientId, claudeKey, model, homeUrl, homeToken) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section("🎧 Spotify") {
            if (connected) {
                Text("Conectado. Aprendo de tus artistas, géneros y de a qué hora escuchas música.", color = SpotifyGreen)
                if (!s.spotifyCanControl) {
                    Text("Para que tu copia pueda poner música, vuelve a conectar (se añadió un permiso nuevo).", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = platform::connectSpotify, colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen)) { Text("Volver a conectar") }
                }
                OutlinedButton(onClick = vm::disconnectSpotify) { Text("Desconectar") }
            } else {
                Text(
                    "Para darme acceso a tu Spotify:\n" +
                        "1. developer.spotify.com → Dashboard → Create app.\n" +
                        "2. Redirect URI exactamente:\n   ${SpotifyAuth.REDIRECT_URI}\n" +
                        "3. Marca «Web API», guarda y copia el Client ID aquí.\n" +
                        "4. En «User Management» añade tu correo de Spotify.\n" +
                        "Controlar la reproducción desde la API requiere Premium; si no, abro la canción en la app.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { platform.openUrl("https://developer.spotify.com/dashboard") }) { Text("Abrir Spotify for Developers") }
                OutlinedTextField(clientId, { clientId = it }, Modifier.fillMaxWidth(), label = { Text("Spotify Client ID") }, singleLine = true)
                Button(onClick = { save(); platform.connectSpotify() }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen), enabled = clientId.isNotBlank()) {
                    Text("Conectar con Spotify")
                }
            }
        }

        Section("👀 Mis sentidos (aprendo sin que me cuentes)") {
            SenseRow("📅 Calendario", "Aprendo tus rutinas y me adelanto a tus eventos", s.senseCalendar && Sensors.granted(ctx, android.Manifest.permission.READ_CALENDAR),
                onEnable = platform::requestCalendar, onDisable = { vm.update { senseCalendar = false } })
            SenseRow("📍 Ubicación", "Descubro tus lugares (casa, trabajo, gimnasio…) sin guardar tu recorrido", s.senseLocation && Sensors.hasLocation(ctx),
                onEnable = platform::requestLocation, onDisable = { vm.update { senseLocation = false } })
            if (s.senseLocation && Sensors.hasLocation(ctx) && !Sensors.hasBackgroundLocation(ctx)) {
                TextButton(onClick = platform::requestBackgroundLocation) { Text("Permitir también en segundo plano («Permitir todo el tiempo»)") }
            }
            SenseRow("😴 Sueño y pasos", "Vía Health Connect: cómo duermes y cuánto te mueves", s.senseHealth,
                onEnable = platform::requestHealth, onDisable = { vm.update { senseHealth = false } })
            SenseRow("📱 Uso de apps", "Qué apps usas y a qué hora (tu ritmo real)", s.senseApps && Sensors.hasUsageAccess(ctx),
                onEnable = platform::openUsageAccess, onDisable = { vm.update { senseApps = false } })
            OutlinedButton(onClick = vm::senseNow) { Text("Actualizar ahora") }
        }

        Section("✨ Claude (entender, investigar y hablar como tú)") {
            Text("Con tu clave, entiendo de verdad lo que me cuentas y lo guardo como recuerdos, busco en la web por ti, " +
                "y tu copia puede hablar y actuar. Sin clave, aprendo con reglas simples e investigo con Google News.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { platform.openUrl("https://platform.claude.com/settings/keys") }) { Text("Conseguir una API key") }
            OutlinedTextField(claudeKey, { claudeKey = it }, Modifier.fillMaxWidth(), label = { Text("API key de Anthropic") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("Modelo") }, singleLine = true)
            Toggle("Guardar recuerdos de lo que le cuento", s.learnWithClaude) { vm.update { learnWithClaude = it } }
        }

        Section("🏠 Casa inteligente (Home Assistant)") {
            Text("Tu copia puede encender luces, poner el clima o pausar la música de casa (siempre con tu confirmación). " +
                "En Home Assistant: Perfil → Seguridad → Token de acceso de larga duración.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(homeUrl, { homeUrl = it }, Modifier.fillMaxWidth(), label = { Text("URL (p. ej. http://homeassistant.local:8123)") }, singleLine = true)
            OutlinedTextField(homeToken, { homeToken = it }, Modifier.fillMaxWidth(), label = { Text("Token") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        }

        Section("🗣️ Voz y avisos") {
            Toggle("Leer en voz alta las respuestas de mi copia", s.speakReplies) { vm.update { speakReplies = it } }
            Toggle("Avisarme y adelantarse (notificaciones)", s.notifications) { vm.update { notifications = it } }
            Text("Añade el widget de Cookie a tu pantalla de inicio para ver qué toca y hablarle con un toque.", style = MaterialTheme.typography.bodySmall)
        }

        Button(onClick = save, Modifier.fillMaxWidth()) { Text("Guardar ajustes") }

        Section("🔒 Seguridad y privacidad") {
            Toggle("Pedir huella o PIN al abrir", s.biometricLock) { vm.update { biometricLock = it } }
            Text(
                "Tu perfil, recuerdos y claves están cifrados con una clave del propio teléfono (Android Keystore) y no salen de él. " +
                    "Solo sale: búsquedas a Google News, lecturas a Spotify/Home Assistant y, si pones tu clave, lo necesario a Claude para entenderte y responder como tú. " +
                    "Tu copia nunca envía mensajes por ti: solo te deja borradores.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { confirmWipe = true }, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text("Olvidar todo lo que sabes de mí")
            }
        }
    }

    if (confirmWipe) {
        ConfirmDialog(
            "¿Borrar todo?", "Se borrará tu perfil, recuerdos, lugares, música, conversaciones, pruebas y las claves guardadas.",
            "Borrar todo", vm::wipeAll,
        ) { confirmWipe = false }
    }
}

@Composable
private fun SenseRow(title: String, desc: String, on: Boolean, onEnable: () -> Unit, onDisable: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(on, { if (it) onEnable() else onDisable() })
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(value, onChange)
    }
}
