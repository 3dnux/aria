package com.aria.cookie

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aria.cookie.core.Engine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Prueba en un teléfono/emulador real: la app arranca y el cifrado del Keystore funciona. */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun appStartsOnWelcome() {
        compose.onNodeWithText("Hola. Voy a ser tu segunda copia.").assertExists()
    }

    @Test
    fun profileIsEncryptedWithKeystore() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(ctx.cacheDir, "smoke.json").also { it.delete() }
        Engine(file, KeystoreCodec).learn("Me llamo Prueba y me encanta el ajedrez")
        assertFalse(String(file.readBytes()).contains("ajedrez"))
        assertEquals("Prueba", Engine(file, KeystoreCodec).current().profile.name)
    }
}
