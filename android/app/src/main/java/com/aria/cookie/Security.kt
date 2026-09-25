package com.aria.cookie

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.aria.cookie.core.Codec
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cifrado AES-GCM con una clave que vive dentro del Android Keystore
 * (no se puede extraer del teléfono). Protege tu perfil y tus claves.
 */
object KeystoreCodec : Codec {
    private const val ALIAS = "cookie_master_key"
    private const val MAGIC = 0x434B // "CK"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    override fun encode(text: String): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        val body = c.doFinal(text.toByteArray())
        return ByteBuffer.allocate(2 + 1 + iv.size + body.size)
            .putShort(MAGIC.toShort()).put(iv.size.toByte()).put(iv).put(body).array()
    }

    override fun decode(bytes: ByteArray): String {
        val buf = ByteBuffer.wrap(bytes)
        require(bytes.size > 3 && buf.short == MAGIC.toShort()) { "no cifrado" }
        val iv = ByteArray(buf.get().toInt()).also { buf.get(it) }
        val body = ByteArray(buf.remaining()).also { buf.get(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(c.doFinal(body))
    }

    fun encryptString(s: String): String = "enc:" + Base64.getEncoder().encodeToString(encode(s))

    fun decryptString(s: String): String =
        if (s.startsWith("enc:")) runCatching { decode(Base64.getDecoder().decode(s.removePrefix("enc:"))) }.getOrDefault("")
        else s // valor antiguo sin cifrar
}
