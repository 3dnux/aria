package com.aria.cookie.core

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Copia de seguridad cifrada con tu contraseña (PBKDF2-SHA256 + AES-256-GCM).
 * Sin la contraseña nadie puede leerla, ni siquiera tú: no la olvides.
 */
object Backup {
    private const val MAGIC = "COOKIE1"
    private const val ITERATIONS = 150_000

    fun encrypt(json: String, password: String): ByteArray {
        require(password.length >= 8) { "La contraseña debe tener al menos 8 caracteres" }
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        val body = c.doFinal(json.toByteArray())
        return ByteBuffer.allocate(MAGIC.length + salt.size + iv.size + body.size)
            .put(MAGIC.toByteArray()).put(salt).put(iv).put(body).array()
    }

    fun decrypt(data: ByteArray, password: String): String {
        val buf = ByteBuffer.wrap(data)
        val magic = ByteArray(MAGIC.length).also { buf.get(it) }
        require(String(magic) == MAGIC) { "No es una copia de Cookie" }
        val salt = ByteArray(16).also { buf.get(it) }
        val iv = ByteArray(12).also { buf.get(it) }
        val body = ByteArray(buf.remaining()).also { buf.get(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        return try {
            String(c.doFinal(body))
        } catch (e: Exception) {
            throw IllegalArgumentException("Contraseña incorrecta o copia dañada")
        }
    }

    private fun key(password: String, salt: ByteArray): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val bytes = f.generateSecret(PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)).encoded
        return SecretKeySpec(bytes, "AES")
    }
}
