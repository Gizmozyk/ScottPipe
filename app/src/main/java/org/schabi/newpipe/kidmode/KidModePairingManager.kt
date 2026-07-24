/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.kidmode

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.kidmode.db.PairedDeviceEntity
import org.schabi.newpipe.kidmode.server.KidModeHmac

/**
 * Completes the `/pair` handshake (see [org.schabi.newpipe.kidmode.server.ApprovalHttpServer])
 * and authenticates subsequent requests from paired devices.
 *
 * [PairedDeviceEntity.sharedSecret] is stored Keystore-AES-wrapped, the same technique
 * [KidModePinManager] uses for the PIN hash -- duplicated here rather than shared, since unlike
 * the PIN this secret must be recoverable in plaintext to verify request signatures, so it can't
 * reuse that class's one-way-hash-only API.
 */
class KidModePairingManager(context: Context) {
    private val appContext = context.applicationContext
    private val database = NewPipeDatabase.getInstance(appContext)

    data class PairingResult(val deviceId: String, val sharedSecretBase64: String)

    /**
     * Stores a new paired device and returns the plaintext secret to hand back to the caller --
     * it's never retrievable in plaintext again after this, only used to verify signatures.
     */
    fun pair(deviceName: String): PairingResult {
        val deviceId = UUID.randomUUID().toString()
        val secret = ByteArray(SECRET_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        val (iv, ciphertext) = encrypt(secret)

        database.pairedDeviceDAO().insert(
            PairedDeviceEntity(
                deviceId = deviceId,
                deviceName = deviceName,
                sharedSecret = "${iv.toBase64()}:${ciphertext.toBase64()}",
                pairedAt = System.currentTimeMillis()
            )
        )

        return PairingResult(deviceId, secret.toBase64())
    }

    /** Verifies an incoming request's signature against [deviceId]'s stored secret, if any. */
    fun authenticate(deviceId: String, method: String, path: String, body: String, signatureHex: String): Boolean {
        val device = database.pairedDeviceDAO().getActiveByDeviceId(deviceId) ?: return false
        val secret = decryptSecret(device.sharedSecret)
        return KidModeHmac.verify(secret, KidModeHmac.message(method, path, body), signatureHex)
    }

    fun revoke(deviceId: String): Boolean = database.pairedDeviceDAO().revoke(deviceId) > 0

    private fun decryptSecret(stored: String): ByteArray {
        val (ivB64, ciphertextB64) = stored.split(":", limit = 2)
        return decrypt(ivB64.fromBase64(), ciphertextB64.fromBase64())
    }

    private fun getOrCreateKeystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEYSTORE_KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return keyGenerator.generateKey()
    }

    private fun encrypt(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKeystoreKey())
        return cipher.iv to cipher.doFinal(plaintext)
    }

    private fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKeystoreKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun ByteArray.toBase64(): String = android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)
    private fun String.fromBase64(): ByteArray = android.util.Base64.decode(this, android.util.Base64.NO_WRAP)

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEYSTORE_KEY_ALIAS = "kid_mode_pairing_key"
        private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val SECRET_LENGTH_BYTES = 32
    }
}
