package com.mobilegh.data

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.utils.Base64MessageEncoder
import com.goterl.lazysodium.utils.Key
import java.nio.charset.StandardCharsets
import java.util.Base64

fun interface ActionSecretSealer {
    fun seal(plaintext: ByteArray, publicKeyBase64: String): String
}

class ActionSecretCryptoException : Exception("Unable to encrypt secret")

class LazySodiumActionSecretSealer : ActionSecretSealer {
    override fun seal(plaintext: ByteArray, publicKeyBase64: String): String = try {
        val publicKey = Base64.getDecoder().decode(publicKeyBase64)
        val lazySodium = LazySodiumAndroid(
            SodiumAndroid(),
            StandardCharsets.UTF_8,
            Base64MessageEncoder(),
        )
        lazySodium.cryptoBoxSealEasy(String(plaintext, StandardCharsets.UTF_8), Key.fromBytes(publicKey))
    } catch (_: Exception) {
        throw ActionSecretCryptoException()
    } catch (_: LinkageError) {
        throw ActionSecretCryptoException()
    }
}

fun sealedActionSecretRequest(
    name: String,
    plaintext: ByteArray,
    publicKey: ActionPublicKey,
    sealer: ActionSecretSealer,
): ActionRequest = try {
    ActionsRequests.putSecret(name, sealer.seal(plaintext, publicKey.key), publicKey.keyId)
} catch (_: Exception) {
    throw ActionSecretCryptoException()
}
