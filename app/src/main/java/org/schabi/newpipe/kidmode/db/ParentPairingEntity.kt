/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.kidmode.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A kid device this (parent) instance completed the `/pair` handshake with (see
 * [org.schabi.newpipe.kidmode.server.ApprovalHttpServer]). The mirror image of
 * [PairedDeviceEntity] -- that one is what the *kid* device stores about a parent; this is what
 * the *parent* device stores about a kid. [sharedSecret] is stored Keystore-AES-wrapped the same
 * way, and for the same reason: it must be recoverable in plaintext to sign outgoing requests.
 *
 * No `revoked` flag: the kid side keeps a soft-revoke for its own audit trail, but this side has
 * no symmetric need for one, so "unpairing" here is a hard delete.
 */
@Entity(tableName = ParentPairingEntity.PARENT_PAIRING_TABLE)
data class ParentPairingEntity(
    @PrimaryKey(autoGenerate = true)
    var uid: Long = 0,

    @ColumnInfo(name = PARENT_PAIRING_KID_DEVICE_ID)
    var kidDeviceId: String,

    @ColumnInfo(name = PARENT_PAIRING_KID_DEVICE_NAME)
    var kidDeviceName: String,

    @ColumnInfo(name = PARENT_PAIRING_HOST)
    var host: String,

    @ColumnInfo(name = PARENT_PAIRING_PORT)
    var port: Int,

    @ColumnInfo(name = PARENT_PAIRING_SHARED_SECRET)
    var sharedSecret: String,

    @ColumnInfo(name = PARENT_PAIRING_PAIRED_AT)
    var pairedAt: Long
) {
    companion object {
        const val PARENT_PAIRING_TABLE: String = "kid_mode_parent_pairings"
        const val PARENT_PAIRING_UID: String = "uid"
        const val PARENT_PAIRING_KID_DEVICE_ID: String = "kid_device_id"
        const val PARENT_PAIRING_KID_DEVICE_NAME: String = "kid_device_name"
        const val PARENT_PAIRING_HOST: String = "host"
        const val PARENT_PAIRING_PORT: String = "port"
        const val PARENT_PAIRING_SHARED_SECRET: String = "shared_secret"
        const val PARENT_PAIRING_PAIRED_AT: String = "paired_at"
    }
}
