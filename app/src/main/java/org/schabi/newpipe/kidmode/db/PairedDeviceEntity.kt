/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.kidmode.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A device that completed the `/pair` handshake (see
 * [org.schabi.newpipe.kidmode.server.ApprovalHttpServer]) and may authenticate requests with
 * [sharedSecret]. [sharedSecret] is stored Keystore-AES-wrapped (see
 * [org.schabi.newpipe.kidmode.KidModePinManager] for the same technique applied to the Kid Mode
 * PIN) -- unlike the PIN, it must be recoverable in plaintext to verify request signatures, so it
 * can't be a one-way hash, but it still shouldn't sit on disk in the clear.
 */
@Entity(
    tableName = PairedDeviceEntity.PAIRED_DEVICE_TABLE,
    indices = [
        Index(value = [PairedDeviceEntity.PAIRED_DEVICE_DEVICE_ID], unique = true)
    ]
)
data class PairedDeviceEntity(
    @PrimaryKey(autoGenerate = true)
    var uid: Long = 0,

    @ColumnInfo(name = PAIRED_DEVICE_DEVICE_ID)
    var deviceId: String,

    @ColumnInfo(name = PAIRED_DEVICE_DEVICE_NAME)
    var deviceName: String,

    @ColumnInfo(name = PAIRED_DEVICE_SHARED_SECRET)
    var sharedSecret: String,

    @ColumnInfo(name = PAIRED_DEVICE_PAIRED_AT)
    var pairedAt: Long,

    @ColumnInfo(name = PAIRED_DEVICE_REVOKED)
    var revoked: Boolean = false
) {
    companion object {
        const val PAIRED_DEVICE_TABLE: String = "kid_mode_paired_devices"
        const val PAIRED_DEVICE_UID: String = "uid"
        const val PAIRED_DEVICE_DEVICE_ID: String = "device_id"
        const val PAIRED_DEVICE_DEVICE_NAME: String = "device_name"
        const val PAIRED_DEVICE_SHARED_SECRET: String = "shared_secret"
        const val PAIRED_DEVICE_PAIRED_AT: String = "paired_at"
        const val PAIRED_DEVICE_REVOKED: String = "revoked"
    }
}
