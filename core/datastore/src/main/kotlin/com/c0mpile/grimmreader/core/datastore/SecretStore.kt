package com.c0mpile.grimmreader.core.datastore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts small secrets. Output = IV ‖ ciphertext ‖ tag. */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray

    fun decrypt(sealed: ByteArray): ByteArray
}

/** AES-256-GCM with a non-exportable key in the Android Keystore. */
class KeystoreCipher(
    private val alias: String = "grimm_secrets",
) : SecretCipher {
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
        const val IV_BYTES = 12
    }
}

/**
 * Encrypted key/value store for tokens and catalog passwords. Values are never logged; a value that no
 * longer decrypts (key lost after a restore or reinstall) reads as absent, so the user signs in again.
 */
class SecretStore(
    private val store: DataStore<Preferences>,
    private val cipher: SecretCipher,
) {
    suspend fun get(name: String): String? {
        val sealed = store.data.first()[stringPreferencesKey(name)] ?: return null
        return runCatching { String(cipher.decrypt(Base64.getDecoder().decode(sealed)), Charsets.UTF_8) }.getOrNull()
    }

    suspend fun put(
        name: String,
        value: String?,
    ) {
        store.edit { prefs ->
            val key = stringPreferencesKey(name)
            if (value == null) {
                prefs.remove(key)
            } else {
                prefs[key] = Base64.getEncoder().encodeToString(cipher.encrypt(value.toByteArray(Charsets.UTF_8)))
            }
        }
    }

    suspend fun clear(prefix: String) {
        store.edit { prefs ->
            prefs
                .asMap()
                .keys
                .filter { it.name.startsWith(prefix) }
                .forEach { prefs.remove(it) }
        }
    }
}
