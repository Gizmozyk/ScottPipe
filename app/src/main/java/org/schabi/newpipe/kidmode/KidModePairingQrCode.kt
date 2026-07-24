/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.kidmode

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Wire format for the QR code shown by the kid-side pairing dialog
 * (`KidModeSettingsFragment.startPairing`) and scanned from the parent side
 * (`ParentModeActivity`). Carries exactly what `ParentModeActivity.pair(host, port,
 * kidDeviceName, code)` needs -- deviceId/sharedSecret are never part of this; those still only
 * ever come back from the `/pair` HTTP response, same as manual-connect today.
 */
@Serializable
data class KidModePairingQrPayload(
    val type: String = TYPE,
    val host: String,
    val port: Int,
    val code: String
) {
    companion object {
        const val TYPE = "scottpipe-kid-mode-pairing"
    }
}

object KidModePairingQrCode {
    fun encode(payload: KidModePairingQrPayload): String = Json.encodeToString(payload)

    /**
     * Null for anything that isn't valid JSON, isn't shaped like [KidModePairingQrPayload], or
     * has the wrong [KidModePairingQrPayload.TYPE] -- e.g. the user scanned an unrelated QR code.
     */
    fun decode(text: String): KidModePairingQrPayload? {
        val payload = try {
            Json.decodeFromString<KidModePairingQrPayload>(text)
        } catch (e: SerializationException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        return payload.takeIf { it.type == KidModePairingQrPayload.TYPE }
    }
}
