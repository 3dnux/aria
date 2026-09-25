package com.aria.cookie

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import com.aria.cookie.ui.CookieRoot
import com.aria.cookie.ui.CookieTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        handleIntent(intent)
        setContent {
            CookieTheme {
                CookieRoot(
                    vm = vm,
                    openUrl = ::openUrl,
                    connectSpotify = { vm.spotifyLoginUrl()?.let(::openUrl) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "ariacookie" && data.host == "spotify-callback") {
            vm.handleSpotifyCallback(data)
            setIntent(Intent(this, MainActivity::class.java))
        }
    }

    private fun openUrl(url: String) {
        if (url.isBlank()) return
        runCatching { CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(url)) }
            .onFailure { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
}
