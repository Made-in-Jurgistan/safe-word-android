package com.safeword.android.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Helper class for Android Keystore operations to securely store and retrieve encryption keys.
 */
object KeyStoreHelper {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "safeword_database_encryption_key"
    private const val KEY_SIZE = 256
    private const val PREFS_NAME = "safeword_security_prefs"
    private const val ENCRYPTED_PASSPHRASE_KEY = "encrypted_db_passphrase"
    private const val IV_KEY = "encrypted_db_passphrase_iv"

    /**
     * Gets or creates the encryption key from Android Keystore.
     */
    fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        val existingKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existingKey != null) {
            return existingKey
        }

        return createKey()
    }

    /**
     * Creates a new AES-GCM key in Android Keystore.
     */
    private fun createKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )

        val keyGenSpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE)
            .build()

        keyGenerator.init(keyGenSpec)
        return keyGenerator.generateKey()
    }

    /**
     * Encrypts data using the Android Keystore key.
     */
    fun encrypt(data: ByteArray): EncryptedData {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)

        val iv = cipher.iv
        val encryptedData = cipher.doFinal(data)

        return EncryptedData(encryptedData, iv)
    }

    /**
     * Decrypts data using the Android Keystore key.
     */
    fun decrypt(encryptedData: EncryptedData): ByteArray {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, encryptedData.iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)

        return cipher.doFinal(encryptedData.data)
    }

    /**
     * Gets or creates the database passphrase securely using Android Keystore.
     * The passphrase is encrypted and stored in SharedPreferences.
     */
    fun getDatabasePassphrase(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encryptedPassphrase = prefs.getString(ENCRYPTED_PASSPHRASE_KEY, null)
        val iv = prefs.getString(IV_KEY, null)

        if (encryptedPassphrase != null && iv != null) {
            // Decrypt existing passphrase
            val encryptedData = EncryptedData(
                android.util.Base64.decode(encryptedPassphrase, android.util.Base64.DEFAULT),
                android.util.Base64.decode(iv, android.util.Base64.DEFAULT)
            )
            val decryptedBytes = decrypt(encryptedData)
            return String(decryptedBytes)
        }

        // Generate and store new passphrase
        val basePassphrase = generateSecurePassphrase()
        val encryptedData = encrypt(basePassphrase.toByteArray())

        prefs.edit()
            .putString(ENCRYPTED_PASSPHRASE_KEY, android.util.Base64.encodeToString(encryptedData.data, android.util.Base64.DEFAULT))
            .putString(IV_KEY, android.util.Base64.encodeToString(encryptedData.iv, android.util.Base64.DEFAULT))
            .apply()

        return basePassphrase
    }

    /**
     * Generates a cryptographically secure random passphrase.
     */
    private fun generateSecurePassphrase(): String {
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    /**
     * Container for encrypted data and initialization vector.
     */
    data class EncryptedData(val data: ByteArray, val iv: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as EncryptedData

            if (!data.contentEquals(other.data)) return false
            if (!iv.contentEquals(other.iv)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + iv.contentHashCode()
            return result
        }
    }
}
