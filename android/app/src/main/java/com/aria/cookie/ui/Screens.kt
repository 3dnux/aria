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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.GraphicEq
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
import androidx.compose.ui.unit.dp
import com.aria.cookie.MainViewModel
import com.aria.cookie.Platform
import com.aria.cookie.QuizUi
import com.aria.cookie.core.ChatMessage
import com.aria.cookie.core.CookieState
import com.aria.cookie.core.Item
import com.aria.cookie.core.currentPlace
import com.aria.cookie.core.greeting
import com.aria.cookie.core.predictContext
import com.aria.cookie.core.twinSimilarity
import com.aria.cookie.core.zoned
import java.time.format.DateTimeFormatter

private enum class Tab(val label: String, val icon: ImageVector) {
    Hoy("Hoy", Icons.Filled.WbSunny),
    Copia("Tu copia", Icons.Filled.Face),
    Tu("Tú", Icons.Filled.Person),
    Ajustes("Ajustes", Icons.Filled.Settings),
}

val DAY_HM: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE HH:mm")
val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

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
            platform.startConversation()
        }
    }

    if (!state.onboarded) {
        Onboarding(vm, platform)
        return
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
                Tab.Ajustes -> SettingsScreen(vm, state, platform)
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
    val nudges = state.recentNudges.filter { now - it.at < 24 * 3_600_000L }.takeLast(5).reversed()

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(greeting(p, now), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            val guess = predictContext(state, now)
            when {
                guess != null && guess.probability >= 0.4 -> Text("🔮 Creo que ahora toca: ${guess.activity} (${(guess.probability * 100).toInt()}%, ${guess.because})", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> p.predictNext(now)?.let { Text("🔮 Creo que ahora toca: ${it.activity} (${it.reason})", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            state.weather?.let { Text("🌡️ ${it.description}, ${it.temperature.toInt()}° · hoy ${it.minToday.toInt()}–${it.maxToday.toInt()}°" + if (it.rainProbNext3h >= 30) " · lluvia ${it.rainProbNext3h}%" else "") }
            currentPlace(state)?.takeIf { it.known }?.let { Text("📍 Estás en ${it.label}") }
            state.upcoming.firstOrNull { it.start > now }?.let { e ->
                val travel = state.travel.firstOrNull { it.eventKey == com.aria.cookie.core.eventKey(e) }
                Text("📅 ${zoned(e.start).format(DAY_HM)} · ${e.title}" + (travel?.let { " · ${it.minutes} min de camino" } ?: ""))
            }
            state.health.lastNight(now)?.let { Text("😴 Anoche: ${it.minutes / 60}h ${it.minutes % 60}min") }
            state.health.stress?.let { Text("💓 Estrés: $it") }
            p.music.nowPlaying?.let { Text("🎧 Estás escuchando: $it", color = SpotifyGreen) }
        }
        if (pending.isNotEmpty()) {
            item { Text("Tu copia quiere hacer por ti", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(pending, key = { "a" + it.id }) { ActionCard(it, vm, platform) }
        }
        if (nudges.isNotEmpty()) {
            item { Text("Me adelanté a esto", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(nudges, key = { "n" + it.key + it.at }) { n ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(n.title, fontWeight = FontWeight.SemiBold)
                        Text(n.text, style = MaterialTheme.typography.bodySmall)
                        if (n.feedback == 0) Row {
                            TextButton(onClick = { vm.nudgeFeedback(n.key, true) }) { Text("👍 Me sirvió") }
                            TextButton(onClick = { vm.nudgeFeedback(n.key, false) }) { Text("👎 No") }
                        } else Hint(if (n.feedback > 0) "Anotado: te avisaré más de esto" else "Anotado: te avisaré menos de esto")
                    }
                }
            }
        }
        state.missions.filter { it.status == "activa" }.takeIf { it.isNotEmpty() }?.let { ms ->
            item {
                Section("🎯 Trabajando por ti") {
                    ms.forEach { m -> Text("• ${m.goal}" + (m.updates.lastOrNull()?.let { "\n   ↳ ${it.text}" } ?: "\n   ↳ empezando…")) }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::research) { Text("🔎 Investigar ahora") }
                if (state.lastResearch > 0) Text("Última vez: " + zoned(state.lastResearch).format(DAY_HM), Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.labelMedium)
            }
            state.lastError?.let { Text("⚠️ $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
        }
        if (p.interests.isEmpty()) {
            item {
                InfoCard(
                    "Aún no te conozco",
                    "Háblame en «Tu copia», cuéntame cosas en «Tú», conecta Spotify y activa mis sentidos en Ajustes. " +
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

// ==================== TU COPIA ====================

@Composable
private fun TwinScreen(vm: MainViewModel, state: CookieState, platform: Platform) {
    val thinking by vm.twinThinking.collectAsState()
    val streaming by vm.streaming.collectAsState()
    val quiz by vm.quiz.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    var correcting by remember { mutableStateOf<ChatMessage?>(null) }
    val list = rememberLazyListState()
    val chat = state.chat
    val actions = state.actions.associateBy { it.id }
    LaunchedEffect(chat.size, thinking, streaming.length / 40) {
        val total = list.layoutInfo.totalItemsCount
        if (total > 0) list.animateScrollToItem(total - 1)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        if (platform.conversationActive) {
            Card(Modifier.fillMaxWidth().padding(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.GraphicEq, null, tint = MaterialTheme.colorScheme.onPrimary)
                    Text(
                        when {
                            thinking -> "  Pensando como tú…"
                            platform.partialSpeech.isNotBlank() -> "  «${platform.partialSpeech}»"
                            else -> "  Te escucho… habla cuando quieras"
                        },
                        Modifier.weight(1f), color = MaterialTheme.colorScheme.onPrimary,
                    )
                    TextButton(onClick = platform::stopConversation) { Text("Terminar", color = MaterialTheme.colorScheme.onPrimary) }
                }
            }
        }
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { QuizCard(vm, state, quiz) }
            if (chat.isEmpty()) {
                item {
                    InfoCard(
                        "Tu segunda copia",
                        "Soy tú, versión digital. Aprendo de lo que me cuentas, tu Spotify, tu agenda, tus lugares, tu sueño, tu pulso y tus apps. " +
                            "Pulsa 🎙️ para hablarme en voz (te contesto en voz y te sigo escuchando). Pídeme «pon mi música de gimnasio», " +
                            "«recuérdame llamar a mamá a las 7» o «ayúdame a preparar el maratón» y lo trabajo por mi cuenta.",
                    )
                }
            }
            items(chat) { m -> ChatBubble(m, actions, vm, platform) { correcting = it } }
            if (thinking) item {
                Text(
                    streaming.ifBlank { "Pensando como tú… 🍪" },
                    Modifier.widthIn(max = 300.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)).padding(12.dp),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (platform.conversationActive) platform.stopConversation() else platform.startConversation() }) {
                Icon(Icons.Filled.Mic, "Conversación por voz", tint = if (platform.conversationActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
private fun ChatBubble(m: ChatMessage, actions: Map<String, com.aria.cookie.core.PendingAction>, vm: MainViewModel, platform: Platform, correct: (ChatMessage) -> Unit) {
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
                TextButton(onClick = { correct(m) }) { Icon(Icons.Filled.Edit, null); Text(" Yo diría…", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

@Composable
private fun QuizCard(vm: MainViewModel, state: CookieState, quiz: QuizUi) {
    var answer by rememberSaveable(quiz.question) { mutableStateOf("") }
    val similarity = twinSimilarity(state)
    val exam = state.fidelity.lastOrNull()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🧬 ¿Qué haría yo?", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(similarity?.let { "Parecido: $it%" } ?: "Sin medir", color = MaterialTheme.colorScheme.primary)
            }
            exam?.let { e ->
                val trend = state.fidelity.takeLast(6).joinToString(" → ") { "${(it.score * 100).toInt()}%" }
                Hint("Último examen: ${(e.score * 100).toInt()}% en ${e.questions} preguntas · evolución: $trend")
            }
            when {
                quiz.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                quiz.question != null -> {
                    Text(quiz.question, style = MaterialTheme.typography.bodyLarge)
                    Hint("Tu copia ya escribió su respuesta en secreto. Responde tú:")
                    OutlinedTextField(answer, { answer = it }, Modifier.fillMaxWidth(), minLines = 2)
                    Row {
                        Button(onClick = { vm.answerQuiz(answer) }, enabled = answer.isNotBlank()) { Text("Comparar") }
                        TextButton(onClick = vm::closeQuiz) { Text("Saltar") }
                    }
                }
                else -> {
                    quiz.last?.let { r ->
                        Hint("Tu copia dijo: «${r.twinGuess}»")
                        Hint("Parecido en esta: ${(r.score * 100).toInt()}%" + (r.lesson.takeIf { it.isNotBlank() }?.let { " · Aprendí: $it" } ?: ""))
                    }
                    Hint("Te pregunto; tu copia responde a escondidas y comparamos. El examen mide el parecido con tus respuestas guardadas.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = vm::startQuiz) { Text(if (quiz.last == null) "Ponme a prueba" else "Otra") }
                        if (state.quiz.size >= 6) OutlinedButton(onClick = vm::evaluateFidelity, colors = ButtonDefaults.outlinedButtonColors()) { Text("Examinar copia") }
                    }
                }
            }
        }
    }
}
