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
import org.schabi.newpipe.kidmode.db.ApprovalRequestStatus
import org.schabi.newpipe.kidmode.db.ApprovalRequestType
import org.schabi.newpipe.local.subscription.SubscriptionManager
import org.schabi.newpipe.util.ExtractorHelper

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

    /**
     * Approves a pending request: performs whatever completion action the request type needs
     * (currently, subscribing -- re-fetching the channel's current info rather than trusting
     * whatever was cached when the request was made) and only then marks it `APPROVED`, so
     * anything observing the row reactively (e.g. [org.schabi.newpipe.kidmode.ui.ApprovalWaitingDialogFragment])
     * never needs to repeat the completion work itself -- it can just react to the result. Used
     * both by the local same-device approval button and, in a later phase, the embedded HTTP
     * server: approval always happens on the kid's own device, however it was triggered.
     */
    fun approve(requestId: Long): Single<ApprovalRequestEntity> {
        return requireRequest(requestId)
            .flatMap { request ->
                when (request.requestType) {
                    ApprovalRequestType.PLAY_VIDEO -> Single.just(Unit)
                    ApprovalRequestType.SUBSCRIBE_CHANNEL -> completeSubscribeApproval(request).map {}
                }.flatMap { markStatus(requestId, ApprovalRequestStatus.APPROVED) }
            }
            .subscribeOn(Schedulers.io())
    }

    fun deny(requestId: Long): Single<ApprovalRequestEntity> {
        return requireRequest(requestId)
            .flatMap { markStatus(requestId, ApprovalRequestStatus.DENIED) }
            .subscribeOn(Schedulers.io())
    }

    private fun requireRequest(requestId: Long): Single<ApprovalRequestEntity> {
        return Single.fromCallable {
            database.approvalRequestDAO().getByIdOnce(requestId)
                ?: throw NoSuchElementException("No approval request with id $requestId")
        }
    }

    private fun markStatus(requestId: Long, status: ApprovalRequestStatus): Single<ApprovalRequestEntity> {
        return Single.fromCallable {
            database.approvalRequestDAO().updateStatus(requestId, status, System.currentTimeMillis())
            database.approvalRequestDAO().getByIdOnce(requestId)!!
        }
    }

    private fun completeSubscribeApproval(request: ApprovalRequestEntity): Single<SubscriptionEntity> {
        return ExtractorHelper.getChannelInfo(request.serviceId, request.targetUrl, false)
            .map(SubscriptionEntity::from)
            .doOnSuccess { subscription -> SubscriptionManager(appContext).insertSubscription(subscription) }
    }
}
