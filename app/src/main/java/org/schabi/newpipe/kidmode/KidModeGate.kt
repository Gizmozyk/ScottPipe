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
import org.schabi.newpipe.kidmode.db.ChannelListStatus
import org.schabi.newpipe.local.subscription.SubscriptionManager
import org.schabi.newpipe.util.ExtractorHelper

/**
 * A channel's blacklist status is always checked before anything else, so it also serves as the
 * decision for whatever chokepoint asked (play or subscribe) -- there's no path where a
 * blacklisted channel needs approval instead of being blocked outright.
 */
enum class GateDecision {
    /** Kid Mode is off, the channel is whitelisted/subscribed/already approved, etc. */
    ALLOWED,

    /** The channel is blacklisted -- never creates a pending [ApprovalRequestEntity]. */
    BLOCKED,

    /** Needs a parent's approval (local PIN or Parent Mode) before proceeding. */
    NEEDS_APPROVAL
}

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
     * Whether playback may proceed immediately, is blocked outright, or needs a parent's
     * approval. A channel's rule (see [org.schabi.newpipe.kidmode.db.ChannelRuleEntity]) is
     * checked before subscription/approval status: a blacklisted channel is always [GateDecision.BLOCKED]
     * even if somehow already subscribed or previously approved, and a whitelisted one is always
     * [GateDecision.ALLOWED] without needing a separate per-video approval.
     */
    fun canPlay(serviceId: Int, channelUrl: String?): Single<GateDecision> {
        if (!isEnabled() || channelUrl.isNullOrEmpty()) {
            return Single.just(GateDecision.ALLOWED)
        }

        return Single.defer {
            when (database.channelRuleDAO().getRule(serviceId, channelUrl)?.status) {
                ChannelListStatus.BLACKLISTED -> Single.just(GateDecision.BLOCKED)

                ChannelListStatus.WHITELISTED -> Single.just(GateDecision.ALLOWED)

                null -> database.subscriptionDAO().getSubscription(serviceId, channelUrl)
                    .isEmpty()
                    .map { notSubscribed -> if (notSubscribed) GateDecision.NEEDS_APPROVAL else GateDecision.ALLOWED }
            }
        }.subscribeOn(Schedulers.io())
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

    /**
     * Whether a brand new subscription may proceed immediately, is blocked outright, or needs a
     * parent's approval -- synchronous (unlike [canPlay]) since its one call site
     * ([org.schabi.newpipe.fragments.list.channel.ChannelFragment]'s `mapOnSubscribe`) already
     * runs on [Schedulers.io] as part of an existing blocking Rx chain.
     */
    fun canSubscribe(serviceId: Int, channelUrl: String): GateDecision {
        if (!isEnabled()) {
            return GateDecision.ALLOWED
        }
        return when (database.channelRuleDAO().getRule(serviceId, channelUrl)?.status) {
            ChannelListStatus.BLACKLISTED -> GateDecision.BLOCKED
            ChannelListStatus.WHITELISTED -> GateDecision.ALLOWED
            null -> GateDecision.NEEDS_APPROVAL
        }
    }

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
