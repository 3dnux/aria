package com.aria.cookie

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
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
import com.aria.cookie.ui.CookieRoot
import com.aria.cookie.ui.CookieTheme
import com.aria.cookie.ui.LockedScreen

/** Lo que la interfaz puede pedirle al sistema. */
interface Platform {
    fun openUrl(url: String)
    fun connectSpotify()
    fun listen(onText: (String) -> Unit)
    fun startConversation()
    fun stopConversation()
    val conversationActive: Boolean
    val partialSpeech: String
    fun setHandsFree(on: Boolean)
    fun requestCalendar()
    fun requestLocation()
    fun requestBackgroundLocation()
    fun requestHealth()
    fun openUsageAccess()
    fun requestMovement()
    fun requestPhotos()
    fun openNotificationAccess()
    fun confirmAction(id: String)
    fun exportBackup(password: String)
    fun importBackup(password: String)
    fun shareDiagnostics()
}

class MainActivity : FragmentActivity(), Platform {
    private val vm: MainViewModel by viewModels()
    private var unlocked by mutableStateOf(false)
    private var startVoice by mutableStateOf(false)
    override var conversationActive by mutableStateOf(false)
        private set
    override var partialSpeech by mutableStateOf("")
        private set
    private var pendingVoice: ((String) -> Unit)? = null
    private var backupPassword: String? = null
    private var afterMic: (() -> Unit)? = null

    private lateinit var voice: CookieVoice
    private lateinit var listener: Listener

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) afterMic?.invoke() else vm.toast.value = "Necesito el micrófono para escucharte"
        afterMic = null
    }
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
    private val askMovement = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        vm.update { senseMovement = ok }
        if (ok) Sensors.startActivityRecognition(this)
    }
    private val askPhotos = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        vm.update { sensePhotos = ok }
        if (ok) vm.senseNow()
    }
    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pw = backupPassword
        backupPassword = null
        if (uri != null && pw != null) vm.exportBackup(this, uri, pw)
    }
    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val pw = backupPassword
        backupPassword = null
        if (uri != null && pw != null) vm.importBackup(this, uri, pw)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)

        // Voz: habla frase a frase lo que llega en streaming; al terminar, vuelve a escuchar (modo conversación).
        voice = CookieVoice(this) { runOnUiThread { if (conversationActive && !vm.twinThinking.value) listener.start() } }
        listener = Listener(this, onPartial = { partialSpeech = it }, onFinal = { text ->
            partialSpeech = ""
            listener.stop()
            vm.sendToTwin(text, spoken = true)
        })
        vm.voiceSink = { runOnUiThread { voice.feed(it) } }
        vm.voiceDone = { runOnUiThread { voice.flush() } }

        unlocked = !vm.settings.biometricLock
        if (!unlocked) authenticate()
        handleIntent(intent)
        if (vm.settings.handsFree && hasMic()) HandsFreeService.start(this)

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
        vm.bumpSettings() // por si concediste accesos en los ajustes del sistema
    }

    override fun onPause() {
        stopConversation()
        super.onPause()
    }

    override fun onDestroy() {
        listener.destroy()
        voice.shutdown()
        vm.voiceSink = null
        vm.voiceDone = null
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
            unlocked = true
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

    private fun hasMic() = Sensors.granted(this, Manifest.permission.RECORD_AUDIO)

    private fun withMic(block: () -> Unit) {
        if (hasMic()) block() else { afterMic = block; askMic.launch(Manifest.permission.RECORD_AUDIO) }
    }

    /** Dicta una sola frase (sin ventanas del sistema). */
    override fun listen(onText: (String) -> Unit) = withMic {
        pendingVoice = onText
        Listener(this, onPartial = { partialSpeech = it }, onFinal = { t ->
            partialSpeech = ""
            pendingVoice?.invoke(t)
            pendingVoice = null
        }).also { l -> l.start(); lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onPause(owner: androidx.lifecycle.LifecycleOwner) = l.destroy()
        }) }
    }

    /** Conversación continua: hablas, te responde en voz y vuelve a escucharte. */
    override fun startConversation() = withMic {
        conversationActive = true
        voice.say("Te escucho")
    }

    override fun stopConversation() {
        conversationActive = false
        partialSpeech = ""
        if (::listener.isInitialized) listener.stop()
        if (::voice.isInitialized) voice.stop()
    }

    override fun setHandsFree(on: Boolean) {
        if (on) withMic {
            vm.update { handsFree = true }
            HandsFreeService.start(this)
        } else {
            vm.update { handsFree = false }
            HandsFreeService.stop(this)
        }
    }

    // ==================== PERMISOS ====================

    override fun requestCalendar() = askCalendar.launch(Sensors.calendarPermissions)
    override fun requestLocation() = askLocation.launch(Sensors.locationPermissions)

    override fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= 30) {
            startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } else if (Build.VERSION.SDK_INT == 29) {
            askBackgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }

    override fun requestHealth() {
        if (!Sensors.healthAvailable(this)) {
            vm.toast.value = "Instala o actualiza Health Connect para compartir tu sueño, pasos y pulso"
            openUrl("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata")
            return
        }
        askHealth.launch(Sensors.healthPermissions)
    }

    override fun openUsageAccess() {
        vm.update { senseApps = true }
        startActivity(Intent(AndroidSettings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    override fun requestMovement() {
        if (Build.VERSION.SDK_INT >= 29) askMovement.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        else { vm.update { senseMovement = true }; Sensors.startActivityRecognition(this) }
    }

    override fun requestPhotos() = askPhotos.launch(Sensors.photoPermission)

    override fun openNotificationAccess() {
        vm.update { senseNotifications = true }
        startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    // ==================== OTROS ====================

    override fun confirmAction(id: String) = vm.confirmAction(this, id)

    override fun exportBackup(password: String) {
        backupPassword = password
        createBackup.launch("cookie-${java.time.LocalDate.now()}.cookie")
    }

    override fun importBackup(password: String) {
        backupPassword = password
        openBackup.launch(arrayOf("*/*"))
    }

    override fun shareDiagnostics() = CrashLog.share(this)

    override fun connectSpotify() {
        vm.spotifyLoginUrl()?.let(::openUrl)
    }

    override fun openUrl(url: String) {
        if (url.isBlank()) return
        runCatching { CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(url)) }
            .onFailure { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
    }
}
