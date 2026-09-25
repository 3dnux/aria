package com.aria.cookie.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aria.cookie.MainViewModel
import com.aria.cookie.Platform
import com.aria.cookie.core.CookieState
import com.aria.cookie.core.MEMORY_KINDS
import com.aria.cookie.core.Memory
import com.aria.cookie.core.Place
import com.aria.cookie.core.topApps
import com.aria.cookie.core.topContacts
import com.aria.cookie.core.twinSimilarity
import com.aria.cookie.core.zoned

@Composable
fun ProfileScreen(vm: MainViewModel, state: CookieState, platform: Platform) {
    val p = state.profile
    val now = System.currentTimeMillis()
    var input by rememberSaveable { mutableStateOf("") }
    var toForget by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<Place?>(null) }
    var memoryToDelete by remember { mutableStateOf<Memory?>(null) }
    var newMission by remember { mutableStateOf(false) }
    var showAllDays by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Cuéntame algo de ti", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("«Mi hermana Laura vive en Madrid y estoy preparando un maratón»") }, minLines = 2)
            IconButton(onClick = { platform.listen { input = it } }) { Icon(Icons.Filled.Mic, "Dictar") }
        }
        Button(onClick = { vm.learn(input); input = "" }, enabled = input.isNotBlank()) { Text("🧠 Aprende esto") }
        HorizontalDivider()

        // ---- Quién soy ----
        val self = state.selfModels.lastOrNull()
        Section("🪞 Quién soy (mi retrato, escrito por tu copia)") {
            if (self == null) {
                Hint("Cada semana tu copia relee todo lo que sabe de ti y escribe tu retrato: valores, cómo decides, cómo hablas, qué te preocupa. Necesita al menos 10 recuerdos.")
            } else {
                Hint("Actualizado el ${zoned(self.at).toLocalDate()} · ${state.selfModels.size} versiones")
                Text(self.summary)
                if (self.values.isNotEmpty()) Field("Valores", self.values.joinToString(", "))
                if (self.decisionStyle.isNotBlank()) Field("Decido", self.decisionStyle)
                if (self.communication.isNotBlank()) Field("Hablo", self.communication)
                if (self.worries.isNotEmpty()) Field("Me preocupa", self.worries.joinToString(", "))
                if (self.goals.isNotEmpty()) Field("Busco", self.goals.joinToString(", "))
                if (self.changes.isNotBlank()) Text("🔄 Cómo he cambiado: ${self.changes}", color = MaterialTheme.colorScheme.primary)
            }
            OutlinedButton(onClick = vm::reflectNow) { Text("Reflexionar ahora") }
        }

        // ---- Diario ----
        Section("📔 Mi diario (lo escribe tu copia cada día)") {
            if (state.days.isEmpty()) Hint("Cada noche resumo tu día con lo que noté: dónde estuviste, qué escuchaste, qué hiciste y cómo parecías estar.")
            state.days.takeLast(if (showAllDays) 30 else 4).reversed().forEach { d ->
                Text("${d.date}${if (d.mood.isNotBlank()) " · ${d.mood}" else ""}", fontWeight = FontWeight.SemiBold)
                Text(d.summary, style = MaterialTheme.typography.bodyMedium)
            }
            Row {
                OutlinedButton(onClick = vm::writeToday) { Text("Escribir hoy") }
                if (state.days.size > 4) TextButton(onClick = { showAllDays = !showAllDays }) { Text(if (showAllDays) "Ver menos" else "Ver más") }
            }
        }

        // ---- Misiones ----
        Section("🎯 Misiones (objetivos que trabajo por mi cuenta)") {
            if (state.missions.isEmpty()) Hint("Dame un objetivo de días o semanas: revisaré tu agenda, sueño y pasos, investigaré, ajustaré el plan y te propondré acciones.")
            state.missions.sortedBy { it.status != "activa" }.take(8).forEach { m ->
                Text("${if (m.status == "activa") "▶️" else if (m.status == "cumplida") "✅" else "⏸️"} ${m.goal}", fontWeight = FontWeight.SemiBold)
                if (m.plan.isNotEmpty()) Hint("Plan: " + m.plan.take(4).joinToString(" → "))
                m.updates.lastOrNull()?.let { Text("↳ ${it.text}", style = MaterialTheme.typography.bodySmall) }
                Row {
                    if (m.status == "activa") {
                        TextButton(onClick = { vm.setMissionStatus(m.id, "pausada") }) { Text("Pausar") }
                        TextButton(onClick = { vm.setMissionStatus(m.id, "cumplida") }) { Text("Cumplida") }
                    } else TextButton(onClick = { vm.setMissionStatus(m.id, "activa") }) { Text("Reanudar") }
                }
            }
            OutlinedButton(onClick = { newMission = true }) { Text("Nueva misión") }
        }

        Section("🔗 Patrones de tu vida (ARIA)") {
            if (state.patterns.isEmpty()) {
                Hint("ARIA busca en tu línea de tiempo cosas que suelen venir juntas (p. ej. «cuando duermes poco, al día siguiente tu estrés sube»), comparando con los días en que no pasó. Necesita unas semanas de datos.")
            } else {
                Hint("Correlaciones que se repiten en tu vida (no son certezas). Se recalculan cada 12 horas, en tu teléfono.")
                state.patterns.take(8).forEach { Text("• " + com.aria.cookie.aria.Patterns.describe(it)) }
            }
            OutlinedButton(onClick = vm::minePatterns) { Text("Buscar patrones ahora") }
        }

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
        val contacts = topContacts(state)
        if (contacts.isNotEmpty()) Section("💬 Con quién más hablas") { contacts.forEach { Text("${it.name}: ${it.count} mensajes (${it.apps.joinToString()})") } }
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
            Section("📅 Tu agenda") { state.upcoming.filter { it.start > now }.take(6).forEach { Text("${zoned(it.start).format(DAY_HM)} · ${it.title}") } }
        }
        val h = state.health
        if (h.sleep.isNotEmpty() || h.stepsToday > 0 || h.restingBpm > 0) {
            Section("😴 Descanso, movimiento y corazón") {
                val avg = h.averageSleepMinutes()
                if (avg > 0) Field("Duermes", "${avg / 60}h ${avg % 60}min de media")
                h.lastNight(now)?.let { Field("Anoche", "${zoned(it.start).format(HM)} → ${zoned(it.end).format(HM)}") }
                if (h.stepsToday > 0) Field("Pasos hoy", "${h.stepsToday} (media ${h.stepsAvg})")
                if (h.restingBpm > 0) Field("Reposo", "${h.restingBpm} ppm")
                h.stress?.let { Field("Estrés", it) }
            }
        }
        val apps = topApps(state)
        if (apps.isNotEmpty()) {
            Section("📱 Tus apps") {
                apps.forEach { a ->
                    val hh = a.hourMinutes.indices.maxBy { a.hourMinutes[it] }
                    Text("${a.label}: ~${a.minutes.toInt()} min/día, sobre todo a las %02d:00".format(hh))
                }
            }
        }
        if (p.routines.isNotEmpty()) {
            Section("🔁 Tus rutinas") {
                p.routines.values.sortedByDescending { it.count }.take(10).forEach { r ->
                    val hh = r.hourCounts.indices.maxBy { r.hourCounts[it] }
                    Text("${r.activity}: ${r.count} veces, normalmente ~%02d:00".format(hh))
                }
            }
        }
        if (state.timeline.isNotEmpty()) {
            Section("🕒 Lo último que noté") {
                state.timeline.takeLast(12).reversed().forEach { e -> Text("${zoned(e.at).format(DAY_HM)} · ${e.text}", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }

    toForget?.let { topic -> ConfirmDialog("¿Olvidar «$topic»?", "Dejaré de investigar sobre esto.", "Olvidar", { vm.forget(topic) }) { toForget = null } }
    memoryToDelete?.let { mem -> ConfirmDialog("¿Borrar este recuerdo?", mem.text, "Borrar", { vm.forgetMemory(mem.id) }) { memoryToDelete = null } }
    if (newMission) TextDialog("Nueva misión", "p. ej. «Preparar el maratón de abril»", "Crear", onConfirm = vm::createMission) { newMission = false }
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
