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
 * Encrypts the full Bilibili cookie header with an AES key held by Android Keystore.
 * The raw cookie is never written to a plaintext preference or log.
 */
object SecureCookieStore {
    private const val KEY_ALIAS = "bilidl_sessdata_v1"
    private const val PREFS = "private_auth"
    private const val IV = "cookie_iv"
    private const val VALUE = "cookie_value"

    fun save(context: Context, rawCookie: String) {
        val value = normalizeCookieHeader(rawCookie)
        require(normalizeCookie(value).isNotBlank()) { "SESSDATA 不能为空" }

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

    /** Returns the SESSDATA value from either a raw value or a Cookie header. */
    fun normalizeCookie(rawCookie: String): String {
        val trimmed = rawCookie.trim()
        val match = Regex("""(?:^|;\s*)SESSDATA=([^;]+)""").find(trimmed)
        return match?.groupValues?.get(1) ?: if ('=' !in trimmed) trimmed else ""
    }

    /**
     * Keeps the complete cookie header from the official Bilibili login page.
     * Old app versions stored only a raw SESSDATA value; those values remain
     * compatible and are upgraded to a one-cookie header when sent.
     */
    fun normalizeCookieHeader(rawCookie: String): String {
        val trimmed = rawCookie.trim()
        if (trimmed.isBlank()) return ""
        if ('=' !in trimmed) return "SESSDATA=$trimmed"

        val pairs = linkedMapOf<String, String>()
        val ignoredAttributes = setOf(
            "path", "domain", "expires", "max-age", "secure",
            "httponly", "samesite", "priority"
        )
        for (token in trimmed.split(';')) {
            val separator = token.indexOf('=')
            if (separator <= 0) continue
            val name = token.substring(0, separator).trim()
            val value = token.substring(separator + 1).trim()
            if (name.isBlank() ||
                ignoredAttributes.any { it.equals(name, ignoreCase = true) } ||
                !name.matches(Regex("[A-Za-z0-9_]+")) ||
                value.any { it == '\r' || it == '\n' }
            ) continue
            pairs[name] = value
        }
        return pairs.entries.joinToString("; ") { (name, value) -> "$name=$value" }
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
