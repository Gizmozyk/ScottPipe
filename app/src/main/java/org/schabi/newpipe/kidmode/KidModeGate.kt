/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.kidmode

import android.content.Context
import androidx.preference.PreferenceManager
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.kidmode.db.ApprovalRequestEntity
import org.schabi.newpipe.kidmode.db.ApprovalRequestType

/**
 * Central gate for the two Kid Mode-restricted actions: playing a video from a channel the kid
 * isn't subscribed to, and subscribing to a new channel. See `wiki/features/kid-mode.md` and
 * `wiki/adr/0001-kid-mode-architecture.md` for the feature's design.
 */
class KidModeGate(context: Context) {
    private val appContext = context.applicationContext
    private val database = NewPipeDatabase.getInstance(appContext)
    private val defaultPreferences = PreferenceManager.getDefaultSharedPreferences(appContext)

    fun isEnabled(): Boolean {
        return defaultPreferences.getBoolean(
            appContext.getString(R.string.kid_mode_enabled_key),
            false
        )
    }

    /**
     * Whether playback may proceed immediately, without a parent's approval: Kid Mode is off,
     * [channelUrl] is unknown, the channel is subscribed, or a parent already approved it.
     */
    fun canPlay(serviceId: Int, channelUrl: String?): Single<Boolean> {
        if (!isEnabled() || channelUrl.isNullOrEmpty()) {
            return Single.just(true)
        }

        return database.subscriptionDAO().getSubscription(serviceId, channelUrl)
            .isEmpty()
            .flatMap { notSubscribed ->
                if (!notSubscribed) {
                    Single.just(true)
                } else {
                    database.approvedChannelDAO().isApproved(serviceId, channelUrl)
                        .isEmpty()
                        .map { notApproved -> !notApproved }
                }
            }
            .subscribeOn(Schedulers.io())
    }

    fun requestPlayApproval(
        serviceId: Int,
        targetUrl: String,
        targetTitle: String,
        channelUrl: String?
    ): Single<Long> {
        return Single.fromCallable {
            database.approvalRequestDAO().insert(
                ApprovalRequestEntity(
                    requestType = ApprovalRequestType.PLAY_VIDEO,
                    serviceId = serviceId,
                    targetUrl = targetUrl,
                    targetTitle = targetTitle,
                    channelUrl = channelUrl,
                    createdAt = System.currentTimeMillis()
                )
            )
        }.subscribeOn(Schedulers.io())
    }

    /** Whether a brand new subscription may be created immediately, without approval. */
    fun canSubscribe(): Boolean = !isEnabled()

    fun requestSubscribeApproval(subscription: SubscriptionEntity): Single<Long> {
        val url = requireNotNull(subscription.url) { "Subscription must have a url" }
        return Single.fromCallable {
            database.approvalRequestDAO().insert(
                ApprovalRequestEntity(
                    requestType = ApprovalRequestType.SUBSCRIBE_CHANNEL,
                    serviceId = subscription.serviceId,
                    targetUrl = url,
                    targetTitle = subscription.name ?: url,
                    channelUrl = url,
                    createdAt = System.currentTimeMillis()
                )
            )
        }.subscribeOn(Schedulers.io())
    }
}
