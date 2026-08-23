package com.sanhaengii.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class WatchCredentials(val token: String, val userId: Long)

class SecureCredentialStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun save(credentials: WatchCredentials) {
        require(credentials.token.isNotBlank() && credentials.userId > 0)
        val plaintext = JSONObject().put("token", credentials.token).put("user_id", credentials.userId).toString()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, getOrCreateKey()) }
        val encrypted = cipher.iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString(KEY_ENCRYPTED_CREDENTIALS, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .remove(LEGACY_TOKEN_KEY)
            .remove(LEGACY_USER_ID_KEY)
            .apply()
    }

    @Synchronized
    fun load(): WatchCredentials? {
        preferences.getString(KEY_ENCRYPTED_CREDENTIALS, null)?.takeIf { it.isNotBlank() }?.let {
            return runCatching { decrypt(it) }.getOrNull()
        }
        val token = preferences.getString(LEGACY_TOKEN_KEY, null)?.trim().orEmpty()
        val userId = preferences.getString(LEGACY_USER_ID_KEY, null)?.toLongOrNull()
        return if (token.isNotBlank() && userId != null && userId > 0) {
            WatchCredentials(token, userId).also(::save)
        } else null
    }

    fun clear() {
        preferences.edit().remove(KEY_ENCRYPTED_CREDENTIALS).remove(LEGACY_TOKEN_KEY).remove(LEGACY_USER_ID_KEY).apply()
    }

    private fun decrypt(encoded: String): WatchCredentials {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.size > GCM_IV_LENGTH_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, payload.copyOfRange(0, GCM_IV_LENGTH_BYTES)),
            )
        }
        val json = JSONObject(String(cipher.doFinal(payload.copyOfRange(GCM_IV_LENGTH_BYTES, payload.size)), Charsets.UTF_8))
        return WatchCredentials(json.getString("token"), json.getLong("user_id"))
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }

    companion object {
        private const val PREFS_NAME = "sanhaengii_watch_prefs"
        private const val KEY_ENCRYPTED_CREDENTIALS = "encrypted_watch_credentials"
        private const val LEGACY_TOKEN_KEY = "health_api_token"
        private const val LEGACY_USER_ID_KEY = "health_api_user_id"
        private const val KEY_ALIAS = "sanhaengii_watch_credentials_v1"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}
