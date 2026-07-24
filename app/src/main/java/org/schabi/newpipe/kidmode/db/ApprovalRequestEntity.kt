/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.kidmode.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import org.schabi.newpipe.util.NO_SERVICE_ID

enum class ApprovalRequestType {
    PLAY_VIDEO,
    SUBSCRIBE_CHANNEL
}

enum class ApprovalRequestStatus {
    PENDING,
    APPROVED,
    DENIED
}

@Entity(tableName = ApprovalRequestEntity.APPROVAL_REQUEST_TABLE)
data class ApprovalRequestEntity(
    @PrimaryKey(autoGenerate = true)
    var uid: Long = 0,

    @ColumnInfo(name = APPROVAL_REQUEST_TYPE)
    var requestType: ApprovalRequestType,

    @ColumnInfo(name = APPROVAL_REQUEST_SERVICE_ID)
    var serviceId: Int = NO_SERVICE_ID,

    @ColumnInfo(name = APPROVAL_REQUEST_TARGET_URL)
    var targetUrl: String,

    @ColumnInfo(name = APPROVAL_REQUEST_TARGET_TITLE)
    var targetTitle: String,

    @ColumnInfo(name = APPROVAL_REQUEST_CHANNEL_URL)
    var channelUrl: String?,

    @ColumnInfo(name = APPROVAL_REQUEST_STATUS)
    var status: ApprovalRequestStatus = ApprovalRequestStatus.PENDING,

    @ColumnInfo(name = APPROVAL_REQUEST_CREATED_AT)
    var createdAt: Long,

    @ColumnInfo(name = APPROVAL_REQUEST_RESOLVED_AT)
    var resolvedAt: Long? = null
) {
    companion object {
        const val APPROVAL_REQUEST_TABLE: String = "kid_mode_approval_requests"
        const val APPROVAL_REQUEST_UID: String = "uid"
        const val APPROVAL_REQUEST_TYPE: String = "request_type"
        const val APPROVAL_REQUEST_SERVICE_ID: String = "service_id"
        const val APPROVAL_REQUEST_TARGET_URL: String = "target_url"
        const val APPROVAL_REQUEST_TARGET_TITLE: String = "target_title"
        const val APPROVAL_REQUEST_CHANNEL_URL: String = "channel_url"
        const val APPROVAL_REQUEST_STATUS: String = "status"
        const val APPROVAL_REQUEST_CREATED_AT: String = "created_at"
        const val APPROVAL_REQUEST_RESOLVED_AT: String = "resolved_at"
    }
}
