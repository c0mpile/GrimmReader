package com.c0mpile.grimmreader.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.c0mpile.grimmreader.core.model.Appearance
import com.c0mpile.grimmreader.core.model.EinkTint
import com.c0mpile.grimmreader.core.model.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/** Reversible stand-in for the Keystore cipher (Robolectric has no AndroidKeyStore). */
private class XorCipher : SecretCipher {
    override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()

    override fun decrypt(sealed: ByteArray) = encrypt(sealed)
}

private class BrokenCipher : SecretCipher {
    override fun encrypt(plain: ByteArray) = plain

    override fun decrypt(sealed: ByteArray): ByteArray = throw javax.crypto.AEADBadTagException()
}

@RunWith(AndroidJUnit4::class)
class StoresTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun TestScope.store(name: String) =
        PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "$name.preferences_pb") }

    @Test fun appearanceRoundTrip() =
        runTest {
            val prefs = AppPreferences(store("app"))
            assertEquals(Appearance(), prefs.appearance.first())
            val eink = Appearance(ThemeMode.EINK, EinkTint.COOL, einkFlashEvery = 10, einkGrain = true)
            prefs.setAppearance(eink)
            assertEquals(eink, prefs.appearance.first())
            assertEquals(SetupState.NOT_DONE, prefs.setupState.first())
        }

    @Test fun secretsAreStoredEncryptedAndCleared() =
        runTest {
            val file = File(tmp.root, "secrets.preferences_pb")
            val secrets = SecretStore(store("secrets"), XorCipher())
            secrets.put("server.1.refresh", "refresh-token-value")
            assertEquals("refresh-token-value", secrets.get("server.1.refresh"))
            assertFalse(file.readText(Charsets.ISO_8859_1).contains("refresh-token-value"))
            secrets.clear("server.1.")
            assertNull(secrets.get("server.1.refresh"))
        }

    @Test fun undecryptableSecretReadsAsAbsent() =
        runTest {
            val secrets = SecretStore(store("secrets2"), BrokenCipher())
            secrets.put("token", "x")
            assertNull(secrets.get("token"))
        }
}
