/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.kidmode

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores and verifies the Kid Mode PIN as a salted PBKDF2 hash, never the PIN itself. There's no
 * need to ever recover the PIN, only to check a guess against the stored hash, so this needs no
 * reversible encryption -- an attacker able to read the app's private storage already has
 * broader access to the device than this PIN protects against.
 *
 * The hash+salt pair is additionally wrapped with an Android Keystore-backed AES key so the
 * stored value on disk isn't a bare hash usable for offline brute-forcing if the file alone
 * leaks (e.g. via a backup).
 */
class KidModePinManager(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isPinSet(): Boolean = prefs.contains(KEY_WRAPPED_HASH)

    fun setPin(pin: String) {
        val salt = ByteArray(SALT_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2(pin, salt)
        val (iv, ciphertext) = encrypt(salt + hash)

        prefs.edit()
            .putString(KEY_WRAPPED_HASH, ciphertext.toBase64())
            .putString(KEY_IV, iv.toBase64())
            .apply()
    }

    fun verifyPin(pin: String): Boolean {
        val wrappedHashB64 = prefs.getString(KEY_WRAPPED_HASH, null) ?: return false
        val ivB64 = prefs.getString(KEY_IV, null) ?: return false

        val saltAndHash = decrypt(ivB64.fromBase64(), wrappedHashB64.fromBase64())
        val salt = saltAndHash.copyOfRange(0, SALT_LENGTH_BYTES)
        val storedHash = saltAndHash.copyOfRange(SALT_LENGTH_BYTES, saltAndHash.size)

        return pbkdf2(pin, salt).contentEquals(storedHash)
    }

    fun clearPin() {
        prefs.edit().remove(KEY_WRAPPED_HASH).remove(KEY_IV).apply()
    }

    private fun pbkdf2(pin: String, salt: ByteArray): ByteArray {
        val spec = javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_LENGTH_BITS)
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
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
        private const val PREFS_NAME = "kid_mode_pin"
        private const val KEY_WRAPPED_HASH = "wrapped_hash"
        private const val KEY_IV = "iv"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEYSTORE_KEY_ALIAS = "kid_mode_pin_key"
        private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val SALT_LENGTH_BYTES = 16
        private const val PBKDF2_ITERATIONS = 120_000
        private const val PBKDF2_KEY_LENGTH_BITS = 256
    }
}
