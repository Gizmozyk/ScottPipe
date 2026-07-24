/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.kidmode.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.reactivex.rxjava3.core.Maybe

@Dao
abstract class ApprovedChannelDAO {
    @Insert
    abstract fun insert(entity: ApprovedChannelEntity): Long

    @Query(
        "SELECT * FROM kid_mode_approved_channels WHERE url = :channelUrl AND service_id = :serviceId"
    )
    abstract fun isApproved(serviceId: Int, channelUrl: String): Maybe<ApprovedChannelEntity>
}
