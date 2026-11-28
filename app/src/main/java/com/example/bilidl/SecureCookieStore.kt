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
        val value = normalizeCookieHeader(rawCookie)
        require(cookieValue(value, "SESSDATA").isNotBlank()) { "SESSDATA 不能为空" }

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
            val stored = String(cipher.doFinal(Base64.decode(valueText, Base64.NO_WRAP)), Charsets.UTF_8)
            cookieValue(stored, "SESSDATA").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            clear(context)
            null
        }
    }

    /** Returns the encrypted full cookie header for CSRF-protected Bilibili mutations. */
    fun loadCookieHeader(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ivText = prefs.getString(IV, null) ?: return null
        val valueText = prefs.getString(VALUE, null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP))
            )
            val stored = String(
                cipher.doFinal(Base64.decode(valueText, Base64.NO_WRAP)),
                Charsets.UTF_8
            )
            normalizeCookieHeader(stored).takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            clear(context)
            null
        }
    }

    fun csrf(context: Context): String? =
        loadCookieHeader(context)?.let { cookieValue(it, "bili_jct") }?.takeIf { it.isNotBlank() }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(IV)
            .remove(VALUE)
            .apply()
    }

    fun normalizeCookie(rawCookie: String): String {
        val trimmed = rawCookie.trim()
        val match = Regex("""(?:^|;\\s*)SESSDATA=([^;]+)""").find(trimmed)
        return match?.groupValues?.get(1) ?: trimmed
    }

    fun normalizeCookieHeader(rawCookie: String): String {
        val knownNames = setOf(
            "SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5",
            "buvid3", "buvid4", "sid", "b_nut", "buvid_fp", "buvid_fp_plain"
        )
        val values = linkedMapOf<String, String>()
        rawCookie.replace("\\r", ";").replace("\\n", ";")
            .split(';')
            .map(String::trim)
            .filter { it.isNotBlank() && it.contains('=') }
            .forEach { token ->
                val name = token.substringBefore('=').trim()
                val value = token.substringAfter('=', "").trim()
                if (name in knownNames && value.isNotBlank()) values[name] = value
            }
        if (values.isEmpty() && rawCookie.isNotBlank() && !rawCookie.contains('=')) {
            values["SESSDATA"] = rawCookie.trim()
        }
        return values.entries.joinToString("; ") { it.key + "=" + it.value }
    }

    fun cookieValue(cookieHeader: String, name: String): String {
        val escaped = Regex.escape(name)
        return Regex("""(?:^|;\\s*)$escaped=([^;]+)""")
            .find(cookieHeader)?.groupValues?.getOrNull(1).orEmpty()
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
