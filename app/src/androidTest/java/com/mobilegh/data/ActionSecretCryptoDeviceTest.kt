package com.mobilegh.data

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.utils.Base64MessageEncoder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActionSecretCryptoDeviceTest {
    @Test fun sealedSecretRoundTripsWithRealSodium() {
        val lazySodium = LazySodiumAndroid(SodiumAndroid(), StandardCharsets.UTF_8, Base64MessageEncoder())
        val keyPair = lazySodium.cryptoBoxKeypair()
        val publicKey = Base64.encodeToString(keyPair.publicKey.asBytes, Base64.NO_WRAP)
        val plaintext = "device-sealed-secret"
        val encrypted = LazySodiumActionSecretSealer().seal(plaintext.toByteArray(), publicKey)

        assertNotEquals(plaintext, encrypted)
        assertTrue(Base64.decode(encrypted, Base64.NO_WRAP).isNotEmpty())
        assertEquals(plaintext, lazySodium.cryptoBoxSealOpenEasy(encrypted, keyPair))
    }
}
