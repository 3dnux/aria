package com.aria.cookie

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aria.cookie.ui.CookieTheme

/** Pantalla que Health Connect muestra cuando preguntas para qué quiere Cookie tus datos. */
class HealthRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CookieTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(24.dp)) {
                        Text("🍪 Cookie y tu salud", style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.health_rationale), Modifier.padding(vertical = 16.dp))
                        Button(onClick = { finish() }) { Text("Entendido") }
                    }
                }
            }
        }
    }
}
