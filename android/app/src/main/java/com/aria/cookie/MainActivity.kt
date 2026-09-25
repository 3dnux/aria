package com.aria.cookie

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.aria.cookie.ui.CookieRoot
import com.aria.cookie.ui.CookieTheme
import com.aria.cookie.ui.LockedScreen
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

/** Lo que la interfaz puede pedirle al sistema. */
interface Platform {
    fun openUrl(url: String)
    fun connectSpotify()
    fun listen(onText: (String) -> Unit)
    fun requestCalendar()
    fun requestLocation()
    fun requestBackgroundLocation()
    fun requestHealth()
    fun openUsageAccess()
    fun confirmAction(id: String)
}

class MainActivity : FragmentActivity(), Platform {
    private val vm: MainViewModel by viewModels()
    private var tts: TextToSpeech? = null
    private var unlocked by mutableStateOf(false)
    private var pendingVoice: ((String) -> Unit)? = null
    /** Se pide dictar al abrir desde el widget. */
    private var startVoice by mutableStateOf(false)

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val askCalendar = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        vm.update { senseCalendar = r[Manifest.permission.READ_CALENDAR] == true }
        if (vm.settings.senseCalendar) vm.senseNow()
    }
    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        vm.update { senseLocation = r.values.any { it } }
        if (vm.settings.senseLocation) vm.senseNow()
    }
    private val askBackgroundLocation = registerForActivityResult(ActivityResultContracts.RequestPermission()) { vm.bumpSettings() }
    private val askHealth = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        vm.update { senseHealth = granted.isNotEmpty() }
        if (granted.isNotEmpty()) vm.senseNow()
    }
    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) pendingVoice?.invoke(text)
        pendingVoice = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        tts = TextToSpeech(this) { status -> if (status == TextToSpeech.SUCCESS) tts?.language = Locale("es") }
        lifecycleScope.launch { vm.speak.collectLatest { text -> text?.let { say(it); vm.speak.value = null } } }

        unlocked = !vm.settings.biometricLock
        if (!unlocked) authenticate()
        handleIntent(intent)

        setContent {
            CookieTheme {
                if (unlocked) CookieRoot(vm, this, startVoice) { startVoice = false }
                else LockedScreen(::authenticate)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.bumpSettings() // por si concediste el acceso a uso de apps en Ajustes del sistema
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.data?.let { data ->
            if (data.scheme == "ariacookie" && data.host == "spotify-callback") {
                vm.handleSpotifyCallback(data)
                setIntent(Intent(this, MainActivity::class.java))
            }
        }
        intent.getStringExtra(CookieApp.EXTRA_RUN_ACTION)?.let { id ->
            NotificationManagerCompat.from(this).cancel(intent.getIntExtra(CookieApp.EXTRA_NOTIFICATION, 0))
            intent.removeExtra(CookieApp.EXTRA_RUN_ACTION)
            vm.confirmAction(this, id) // lo pediste tú desde la notificación
        }
        if (intent.getBooleanExtra(CookieApp.EXTRA_VOICE, false)) {
            intent.removeExtra(CookieApp.EXTRA_VOICE)
            startVoice = true
        }
    }

    // ==================== SEGURIDAD ====================

    private fun authenticate() {
        val auth = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) {
            unlocked = true // el teléfono no tiene bloqueo configurado
            return
        }
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { unlocked = true }
        }).authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Tu copia está protegida")
                .setSubtitle("Desbloquea para entrar en Cookie")
                .setAllowedAuthenticators(auth)
                .build()
        )
    }

    // ==================== VOZ ====================

    private fun say(text: String) {
        tts?.speak(text.replace(Regex("[*_#>`]"), ""), TextToSpeech.QUEUE_FLUSH, null, "cookie")
    }

    override fun listen(onText: (String) -> Unit) {
        pendingVoice = onText
        runCatching {
            speech.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Háblale a tu copia")
            )
        }.onFailure { vm.toast.value = "Tu teléfono no tiene reconocimiento de voz" }
    }

    // ==================== PERMISOS ====================

    override fun requestCalendar() = askCalendar.launch(Sensors.calendarPermissions)
    override fun requestLocation() = askLocation.launch(Sensors.locationPermissions)

    override fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= 30) {
            // Android pide activarlo en Ajustes: "Permitir todo el tiempo".
            startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } else if (Build.VERSION.SDK_INT == 29) {
            askBackgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    override fun requestHealth() {
        if (!Sensors.healthAvailable(this)) {
            vm.toast.value = "Instala o actualiza Health Connect para compartir tu sueño y tus pasos"
            openUrl("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata")
            return
        }
        askHealth.launch(Sensors.healthPermissions)
    }

    override fun openUsageAccess() {
        vm.update { senseApps = true }
        startActivity(Intent(AndroidSettings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    // ==================== OTROS ====================

    override fun confirmAction(id: String) = vm.confirmAction(this, id)

    override fun connectSpotify() {
        vm.spotifyLoginUrl()?.let(::openUrl)
    }

    override fun openUrl(url: String) {
        if (url.isBlank()) return
        runCatching { CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(url)) }
            .onFailure { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
    }
}
