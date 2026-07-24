/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.kidmode.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.reactivex.rxjava3.core.Flowable

@Dao
abstract class ParentPairingDAO {
    @Insert
    abstract fun insert(entity: ParentPairingEntity): Long

    /**
     * One-shot lookup returning `null` if unmatched -- see [ApprovalRequestDAO.getByIdOnce] for
     * why this needs to be a plain nullable query rather than a `Flowable`.
     */
    @Query("SELECT * FROM kid_mode_parent_pairings WHERE uid = :uid")
    abstract fun getByIdOnce(uid: Long): ParentPairingEntity?

    @Query("SELECT * FROM kid_mode_parent_pairings ORDER BY paired_at ASC")
    abstract fun getAll(): Flowable<List<ParentPairingEntity>>

    @Query("DELETE FROM kid_mode_parent_pairings WHERE uid = :uid")
    abstract fun delete(uid: Long): Int
}
