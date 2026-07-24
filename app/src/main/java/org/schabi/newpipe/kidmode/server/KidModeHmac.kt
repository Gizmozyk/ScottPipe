/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.kidmode.server

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signs/verifies requests to [ApprovalHttpServer] for a paired device (see
 * [org.schabi.newpipe.kidmode.KidModePairingManager]). Pure `javax.crypto`, no Android
 * dependency, so it can be exercised with a plain JVM test rather than needing a device/emulator.
 *
 * No nonce or timestamp is included in the signed message -- a captured signed request could be
 * replayed later. Accepted for now given the low blast radius (replaying an old approve/deny on a
 * same-LAN family network); see `wiki/adr/0001-kid-mode-architecture.md`.
 */
object KidModeHmac {
    private const val ALGORITHM = "HmacSHA256"

    fun message(method: String, path: String, body: String): String = "$method\n$path\n$body"

    fun sign(secret: ByteArray, message: String): String {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(secret, ALGORITHM))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).toHex()
    }

    fun verify(secret: ByteArray, message: String, signatureHex: String): Boolean {
        val expected = sign(secret, message)
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            signatureHex.trim().lowercase().toByteArray(Charsets.UTF_8)
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
