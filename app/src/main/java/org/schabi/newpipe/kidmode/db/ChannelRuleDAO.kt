/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.kidmode.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.reactivex.rxjava3.core.Flowable

@Dao
abstract class ChannelRuleDAO {
    /**
     * Sets (or replaces) a channel's rule. `REPLACE` on the unique (service_id, url) index means
     * flipping an existing whitelist to a blacklist (or vice versa) is just calling this again --
     * a channel can never end up in both states at once.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun upsertRule(entity: ChannelRuleEntity): Long

    /**
     * One-shot lookup returning `null` if unmatched -- see [ApprovalRequestDAO.getByIdOnce] for
     * why this needs to be a plain nullable query rather than a `Flowable`.
     */
    @Query("SELECT * FROM kid_mode_channel_rules WHERE service_id = :serviceId AND url = :channelUrl")
    abstract fun getRule(serviceId: Int, channelUrl: String): ChannelRuleEntity?

    @Query("SELECT * FROM kid_mode_channel_rules ORDER BY set_at ASC")
    abstract fun getAllRules(): Flowable<List<ChannelRuleEntity>>

    @Query("DELETE FROM kid_mode_channel_rules WHERE service_id = :serviceId AND url = :channelUrl")
    abstract fun deleteRule(serviceId: Int, channelUrl: String): Int
}
