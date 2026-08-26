package com.xmax.xlab

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

internal class ApiKeyStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val secretKey: SecretKey by lazy(::getOrCreateSecretKey)

    @Synchronized
    fun load(): String {
        val encryptedValue = preferences.getString(API_KEY_PREFERENCE, null) ?: return ""
        return runCatching { decrypt(encryptedValue) }
            .getOrElse {
                preferences.edit().remove(API_KEY_PREFERENCE).apply()
                ""
            }
    }

    @Synchronized
    fun save(apiKey: String) {
        if (apiKey.isEmpty()) {
            preferences.edit().remove(API_KEY_PREFERENCE).apply()
            return
        }
        runCatching {
            preferences.edit()
                .putString(API_KEY_PREFERENCE, encrypt(apiKey))
                .apply()
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val initializationVector = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val ciphertext = Base64.encodeToString(
            cipher.doFinal(value.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP,
        )
        return "$initializationVector:$ciphertext"
    }

    private fun decrypt(value: String): String {
        val parts = value.split(':', limit = 2)
        require(parts.size == 2) { "Invalid encrypted API key" }
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        val parameters = javax.crypto.spec.GCMParameterSpec(
            GCM_TAG_LENGTH_BITS,
            Base64.decode(parts[0], Base64.NO_WRAP),
        )
        cipher.init(Cipher.DECRYPT_MODE, secretKey, parameters)
        return cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "xlab_secure_preferences"
        const val API_KEY_PREFERENCE = "xmax_api_key"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "com.xmax.xlab.api_key"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
