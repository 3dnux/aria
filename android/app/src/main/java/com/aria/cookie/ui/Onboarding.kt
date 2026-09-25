package com.aria.cookie.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aria.cookie.MainViewModel
import com.aria.cookie.Platform
import com.aria.cookie.core.SpotifyAuth

/** Bienvenida: qué es Cookie, privacidad, claves, Spotify y sentidos, paso a paso. */
@Composable
fun Onboarding(vm: MainViewModel, platform: Platform) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf("") }
    var about by rememberSaveable { mutableStateOf("") }
    var key by rememberSaveable { mutableStateOf(vm.settings.claudeKey) }
    var clientId by rememberSaveable { mutableStateOf(vm.settings.spotifyClientId) }
    val steps = 5

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            LinearProgressIndicator(progress = { (step + 1f) / steps }, modifier = Modifier.fillMaxWidth())
            when (step) {
                0 -> {
                    Text("🍪", style = MaterialTheme.typography.displayMedium)
                    Text("Hola. Voy a ser tu segunda copia.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Aprendo de ti sin que me lo pidas: lo que me cuentas, tu música, tu agenda, tus lugares, cómo duermes. " +
                        "Con eso pienso como tú, me adelanto a lo que necesitas, trabajo tus objetivos por mi cuenta y hago cosas por ti cuando me lo permites.")
                    Text("Cada semana escribo tu retrato «Quién soy» y cada día tu diario. Y puedes medir cuánto me parezco a ti.")
                }
                1 -> {
                    Text("🔒 Tu copia es tuya", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Todo lo que sé de ti vive cifrado en este teléfono. Tú eliges cada sentido, puedes ver todo lo que sé, borrar recuerdos sueltos u olvidarlo todo.")
                    Text("Nunca envío mensajes en tu nombre ni hago nada importante sin preguntarte, salvo lo que tú marques como automático.")
                }
                2 -> {
                    Text("¿Cómo te llamas?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), placeholder = { Text("Tu nombre") }, singleLine = true)
                    Text("Cuéntame un poco de ti: a qué te dedicas, qué te gusta, qué no soportas, qué estás intentando lograr.")
                    OutlinedTextField(about, { about = it }, Modifier.fillMaxWidth(), minLines = 4,
                        placeholder = { Text("«Trabajo como diseñadora, me encanta la fotografía y el pádel, odio madrugar y estoy ahorrando para viajar a Japón»") })
                }
                3 -> {
                    Text("✨ Mi cerebro: Claude", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Para entenderte de verdad, recordar, reflexionar y hablar como tú necesito una clave de la API de Claude (se guarda cifrada aquí).")
                    TextButton(onClick = { platform.openUrl("https://platform.claude.com/settings/keys") }) { Text("Conseguir una API key") }
                    OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("API key") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    Text("Sin clave también funciono, pero de forma básica.", style = MaterialTheme.typography.bodySmall)
                }
                4 -> {
                    Text("🎧 Tu música y tus sentidos", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Conecta Spotify para que aprenda tus gustos y qué escuchas en cada momento. Crea una app en developer.spotify.com con este Redirect URI:")
                    Text(SpotifyAuth.REDIRECT_URI, fontWeight = FontWeight.Bold)
                    OutlinedTextField(clientId, { clientId = it }, Modifier.fillMaxWidth(), label = { Text("Spotify Client ID (opcional)") }, singleLine = true)
                    Button(onClick = { vm.update { spotifyClientId = clientId }; platform.connectSpotify() }, enabled = clientId.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen)) { Text("Conectar Spotify") }
                    Text("Activa ahora los sentidos que quieras (luego hay más en Ajustes):")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = platform::requestCalendar) { Text("📅 Agenda") }
                        OutlinedButton(onClick = platform::requestLocation) { Text("📍 Lugares") }
                        OutlinedButton(onClick = platform::requestHealth) { Text("😴 Salud") }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (step > 0) TextButton(onClick = { step-- }) { Text("Atrás") }
                Button(onClick = {
                    when (step) {
                        2 -> {
                            val intro = listOfNotNull(name.takeIf { it.isNotBlank() }?.let { "Me llamo $it." }, about.takeIf { it.isNotBlank() }).joinToString(" ")
                            if (intro.isNotBlank()) vm.learn(intro)
                        }
                        3 -> if (key.isNotBlank()) { vm.update { claudeKey = key }; if (about.isNotBlank()) vm.learn(about) }
                    }
                    if (step < steps - 1) step++ else vm.finishOnboarding()
                }) { Text(if (step < steps - 1) "Siguiente" else "Empezar") }
            }
        }
    }
}
