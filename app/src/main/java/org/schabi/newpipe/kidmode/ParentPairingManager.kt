/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.kidmode

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.reactivex.rxjava3.core.Flowable
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.kidmode.db.ParentPairingEntity

/**
 * Stores the kid devices this (parent) instance has completed the `/pair` handshake with (see
 * [org.schabi.newpipe.kidmode.client.KidModeApiClient]). The mirror image of
 * [KidModePairingManager], which is what the *kid* device uses to store a parent's pairing.
 *
 * [ParentPairingEntity.sharedSecret] is stored Keystore-AES-wrapped, the same technique
 * [KidModePairingManager] uses -- duplicated here rather than shared, under its own key alias,
 * following that class's own precedent (it duplicated this same boilerplate from
 * [KidModePinManager] for the same reason: this secret must be recoverable in plaintext to sign
 * outgoing requests, unlike the PIN's one-way hash).
 */
class ParentPairingManager(context: Context) {
    private val appContext = context.applicationContext
    private val database = NewPipeDatabase.getInstance(appContext)

    /** Stores a newly completed pairing, encrypting [secretPlaintext] before it touches disk. */
    fun save(kidDeviceId: String, kidDeviceName: String, host: String, port: Int, secretPlaintext: ByteArray): Long {
        val (iv, ciphertext) = encrypt(secretPlaintext)
        return database.parentPairingDAO().insert(
            ParentPairingEntity(
                kidDeviceId = kidDeviceId,
                kidDeviceName = kidDeviceName,
                host = host,
                port = port,
                sharedSecret = "${iv.toBase64()}:${ciphertext.toBase64()}",
                pairedAt = System.currentTimeMillis()
            )
        )
    }

    /** Decrypts a stored pairing's secret, needed on every outgoing request to sign it. */
    fun decryptSecret(pairing: ParentPairingEntity): ByteArray {
        val (ivB64, ciphertextB64) = pairing.sharedSecret.split(":", limit = 2)
        return decrypt(ivB64.fromBase64(), ciphertextB64.fromBase64())
    }

    fun delete(uid: Long): Boolean = database.parentPairingDAO().delete(uid) > 0

    fun getAll(): Flowable<List<ParentPairingEntity>> = database.parentPairingDAO().getAll()

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
        private const val KEYSTORE_KEY_ALIAS = "kid_mode_parent_pairing_key"
        private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}
