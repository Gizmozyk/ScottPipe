/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.kidmode.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.schabi.newpipe.util.NO_SERVICE_ID

/** A channel a parent has proactively decided on, before the kid ever asked. */
enum class ChannelListStatus {
    /** The kid may play from and subscribe to this channel immediately, no approval needed. */
    WHITELISTED,

    /**
     * The kid may never play from or subscribe to this channel -- checked before the normal
     * subscribed-or-approved gate, and never creates a pending [ApprovalRequestEntity], since
     * there's nothing for a parent to act on.
     */
    BLACKLISTED
}

/**
 * A parent's proactive whitelist/blacklist decision for a channel, made before the kid ever asked
 * (see [org.schabi.newpipe.kidmode.parentmode.ParentModeChannelsActivity]). One row per channel,
 * not two separate whitelist/blacklist tables, so "whitelisted and blacklisted at once" is
 * structurally impossible rather than a bug class to guard against.
 *
 * This table previously existed as `ApprovedChannelEntity`/`kid_mode_approved_channels`, checked
 * by [org.schabi.newpipe.kidmode.KidModeGate] but never actually written to by anything --
 * Phase E gives it its first real write path.
 */
@Entity(
    tableName = ChannelRuleEntity.CHANNEL_RULE_TABLE,
    indices = [
        Index(
            value = [
                ChannelRuleEntity.CHANNEL_RULE_SERVICE_ID,
                ChannelRuleEntity.CHANNEL_RULE_URL
            ],
            unique = true
        )
    ]
)
data class ChannelRuleEntity(
    @PrimaryKey(autoGenerate = true)
    var uid: Long = 0,

    @ColumnInfo(name = CHANNEL_RULE_SERVICE_ID)
    var serviceId: Int = NO_SERVICE_ID,

    @ColumnInfo(name = CHANNEL_RULE_URL)
    var channelUrl: String,

    @ColumnInfo(name = CHANNEL_RULE_STATUS)
    var status: ChannelListStatus,

    @ColumnInfo(name = CHANNEL_RULE_SET_AT)
    var setAt: Long
) {
    companion object {
        const val CHANNEL_RULE_TABLE: String = "kid_mode_channel_rules"
        const val CHANNEL_RULE_UID: String = "uid"
        const val CHANNEL_RULE_SERVICE_ID: String = "service_id"
        const val CHANNEL_RULE_URL: String = "url"
        const val CHANNEL_RULE_STATUS: String = "status"
        const val CHANNEL_RULE_SET_AT: String = "set_at"
    }
}
