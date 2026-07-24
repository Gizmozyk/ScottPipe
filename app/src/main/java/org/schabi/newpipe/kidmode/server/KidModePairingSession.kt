/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.kidmode.server

import java.security.SecureRandom

/**
 * The currently-displayed pairing code (see [org.schabi.newpipe.settings.KidModeSettingsFragment]'s
 * "Pair a parent device" action), held in memory only -- never persisted, single active session,
 * single-use. A fresh call to [KidModeServerService.startPairingSession] invalidates whatever
 * code was showing before.
 */
class KidModePairingSession {
    @Volatile
    private var active: Code? = null

    private data class Code(val value: String, val expiresAtMs: Long)

    /** Generates a new 6-digit code, replacing any previous session, valid for [TTL_MILLIS]. */
    fun start(nowMs: Long): String {
        val code = (0 until CODE_LENGTH).joinToString("") { SecureRandom().nextInt(10).toString() }
        active = Code(code, nowMs + TTL_MILLIS)
        return code
    }

    /** Single-use: a successful match clears the session so the same code can't be reused. */
    fun consumeIfValid(candidate: String, nowMs: Long): Boolean {
        val code = active ?: return false
        if (nowMs > code.expiresAtMs || code.value != candidate) {
            return false
        }
        active = null
        return true
    }

    companion object {
        private const val CODE_LENGTH = 6
        const val TTL_MILLIS = 5 * 60 * 1000L
    }
}
