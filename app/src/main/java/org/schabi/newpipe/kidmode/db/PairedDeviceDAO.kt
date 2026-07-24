/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.kidmode.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.reactivex.rxjava3.core.Flowable

@Dao
abstract class PairedDeviceDAO {
    @Insert
    abstract fun insert(entity: PairedDeviceEntity): Long

    /**
     * One-shot lookup returning `null` if unmatched -- see [ApprovalRequestDAO.getByIdOnce] for
     * why this needs to be a plain nullable query rather than a `Flowable`.
     */
    @Query("SELECT * FROM kid_mode_paired_devices WHERE device_id = :deviceId AND revoked = 0")
    abstract fun getActiveByDeviceId(deviceId: String): PairedDeviceEntity?

    @Query("SELECT * FROM kid_mode_paired_devices WHERE revoked = 0 ORDER BY paired_at ASC")
    abstract fun getActive(): Flowable<List<PairedDeviceEntity>>

    @Query("UPDATE kid_mode_paired_devices SET revoked = 1 WHERE device_id = :deviceId AND revoked = 0")
    abstract fun revoke(deviceId: String): Int
}
