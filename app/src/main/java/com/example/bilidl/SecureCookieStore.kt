package com.example.bilidl

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the user's SESSDATA with an AES key held by Android Keystore.
 * The raw cookie is never written to a plaintext preference or log.
 */
object SecureCookieStore {
    private const val KEY_ALIAS = "bilidl_sessdata_v1"
    private const val PREFS = "private_auth"
    private const val IV = "cookie_iv"
    private const val VALUE = "cookie_value"

    fun save(context: Context, rawCookie: String) {
        val value = normalizeCookie(rawCookie)
        require(value.isNotBlank()) { "SESSDATA 不能为空" }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(VALUE, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun load(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ivText = prefs.getString(IV, null) ?: return null
        val valueText = prefs.getString(VALUE, null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = Base64.decode(ivText, Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(Base64.decode(valueText, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            clear(context)
            null
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(IV)
            .remove(VALUE)
            .apply()
    }

    fun normalizeCookie(rawCookie: String): String {
        val trimmed = rawCookie.trim()
        val match = Regex("""(?:^|;\s*)SESSDATA=([^;]+)""").find(trimmed)
        return match?.groupValues?.get(1) ?: trimmed
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
