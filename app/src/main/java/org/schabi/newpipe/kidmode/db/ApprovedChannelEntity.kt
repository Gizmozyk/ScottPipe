/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.kidmode.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.schabi.newpipe.util.NO_SERVICE_ID

/**
 * A channel a parent has approved the kid to watch, even though the kid isn't (and may never be)
 * subscribed to it. Kept separate from [org.schabi.newpipe.database.subscription.SubscriptionEntity]
 * so approving a single video's playback never silently subscribes the kid to the channel.
 */
@Entity(
    tableName = ApprovedChannelEntity.APPROVED_CHANNEL_TABLE,
    indices = [
        Index(
            value = [
                ApprovedChannelEntity.APPROVED_CHANNEL_SERVICE_ID,
                ApprovedChannelEntity.APPROVED_CHANNEL_URL
            ],
            unique = true
        )
    ]
)
data class ApprovedChannelEntity(
    @PrimaryKey(autoGenerate = true)
    var uid: Long = 0,

    @ColumnInfo(name = APPROVED_CHANNEL_SERVICE_ID)
    var serviceId: Int = NO_SERVICE_ID,

    @ColumnInfo(name = APPROVED_CHANNEL_URL)
    var channelUrl: String,

    @ColumnInfo(name = APPROVED_CHANNEL_APPROVED_AT)
    var approvedAt: Long
) {
    companion object {
        const val APPROVED_CHANNEL_TABLE: String = "kid_mode_approved_channels"
        const val APPROVED_CHANNEL_UID: String = "uid"
        const val APPROVED_CHANNEL_SERVICE_ID: String = "service_id"
        const val APPROVED_CHANNEL_URL: String = "url"
        const val APPROVED_CHANNEL_APPROVED_AT: String = "approved_at"
    }
}
