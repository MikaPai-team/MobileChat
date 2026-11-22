package com.mobilechat.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.settingsDataStore by preferencesDataStore("mobilechat_settings")

class SettingsStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val key = stringPreferencesKey("settings")
    val settings = context.settingsDataStore.data.map { preferences ->
        preferences[key]?.let { json.decodeFromString<AppSettings>(it) } ?: AppSettings()
    }
    private suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { preferences ->
            val previous = preferences[key]?.let { json.decodeFromString<AppSettings>(it) } ?: AppSettings()
            preferences[key] = json.encodeToString(transform(previous))
        }
    }
    suspend fun saveProvider(provider: Provider) = update { it.copy(providers = it.providers.filter { p -> p.id != provider.id } + provider) }
    suspend fun deleteProvider(id: String) = update { it.copy(providers = it.providers.filter { p -> p.id != id }) }
    suspend fun appearance(theme: String, glass: Boolean) = update { it.copy(theme = theme, glass = glass) }
}

class KeyVault(private val alias: String = "mobilechat_api_keys_v1") {
    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
    }
    fun decrypt(encrypted: String): String {
        if (encrypted.isEmpty()) return ""
        try {
            val bytes = Base64.getDecoder().decode(encrypted)
            require(bytes.size >= 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            }
            return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        } catch (_: Exception) { throw IllegalStateException("无法解密 API Key，请在站点设置中重新填写") }
    }
}
