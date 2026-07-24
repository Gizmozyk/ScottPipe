/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.kidmode.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.reactivex.rxjava3.core.Flowable

@Dao
abstract class ApprovalRequestDAO {
    @Insert
    abstract fun insert(entity: ApprovalRequestEntity): Long

    @Query("SELECT * FROM kid_mode_approval_requests WHERE uid = :requestId")
    abstract fun getById(requestId: Long): Flowable<ApprovalRequestEntity>

    @Query("SELECT * FROM kid_mode_approval_requests WHERE status = 'PENDING' ORDER BY created_at ASC")
    abstract fun getPending(): Flowable<List<ApprovalRequestEntity>>

    @Query(
        "UPDATE kid_mode_approval_requests SET status = :status, resolved_at = :resolvedAt " +
            "WHERE uid = :requestId"
    )
    abstract fun updateStatus(
        requestId: Long,
        status: ApprovalRequestStatus,
        resolvedAt: Long
    ): Int
}
