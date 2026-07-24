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

    /**
     * One-shot lookup, unlike [getById]: a `Flowable<T>` query with zero matching rows never
     * emits (it waits indefinitely for a future write, it doesn't error), so `.blockingFirst()`
     * on [getById] hangs forever for an id that doesn't exist. Use this instead whenever the id
     * might not exist -- e.g. resolving an approve/deny request from the HTTP server.
     */
    @Query("SELECT * FROM kid_mode_approval_requests WHERE uid = :requestId")
    abstract fun getByIdOnce(requestId: Long): ApprovalRequestEntity?

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
