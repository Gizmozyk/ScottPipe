package org.schabi.newpipe.kidmode

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.database.stream.StreamWithState
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.kidmode.db.ApprovalRequestEntity
import org.schabi.newpipe.kidmode.db.ApprovalRequestStatus
import org.schabi.newpipe.kidmode.db.ApprovalRequestType
import org.schabi.newpipe.kidmode.db.ChannelListStatus
import org.schabi.newpipe.kidmode.db.ChannelRuleEntity
import org.schabi.newpipe.testUtil.TestDatabase

class KidModeContentFilterTest {
    private lateinit var context: Context

    private val serviceId = ServiceList.YouTube.serviceId
    private val blockedChannelUrl = "https://youtube.com/channel/blocked"
    private val allowedChannelUrl = "https://youtube.com/channel/allowed"
    private val deniedVideoUrl = "https://youtube.com/watch?v=denied"
    private val allowedVideoUrl = "https://youtube.com/watch?v=allowed"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        TestDatabase.createReplacingNewPipeDatabase()
        setKidModeEnabled(true)

        val database = NewPipeDatabase.getInstance(context)
        database.channelRuleDAO().upsertRule(
            ChannelRuleEntity(
                serviceId = serviceId,
                channelUrl = blockedChannelUrl,
                status = ChannelListStatus.BLACKLISTED,
                setAt = 1L
            )
        )
        database.approvalRequestDAO().insert(
            ApprovalRequestEntity(
                requestType = ApprovalRequestType.PLAY_VIDEO,
                serviceId = serviceId,
                targetUrl = deniedVideoUrl,
                targetTitle = "Denied video",
                channelUrl = allowedChannelUrl,
                status = ApprovalRequestStatus.DENIED,
                createdAt = 1L
            )
        )
    }

    @After
    fun tearDown() {
        setKidModeEnabled(false)
    }

    private fun setKidModeEnabled(enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(context.getString(R.string.kid_mode_enabled_key), enabled)
            .apply()
    }

    private fun streamItem(url: String, uploaderUrl: String) = StreamInfoItem(serviceId, url, "a video", StreamType.VIDEO_STREAM).apply {
        this.uploaderUrl = uploaderUrl
    }

    private fun channelItem(url: String) = ChannelInfoItem(serviceId, url, "a channel")

    private fun newStreamEntity(uploaderUrl: String) = StreamEntity(
        serviceId = serviceId,
        url = allowedVideoUrl,
        title = "a video",
        streamType = StreamType.VIDEO_STREAM,
        duration = 60L,
        uploader = "an uploader",
        uploaderUrl = uploaderUrl
    )

    @Test
    fun keepsEverythingWhenKidModeDisabled() {
        setKidModeEnabled(false)
        val items = listOf(streamItem(deniedVideoUrl, blockedChannelUrl))

        assertEquals(1, KidModeContentFilter.filterItems(context, items).items.size)
    }

    @Test
    fun dropsAStreamFromABlacklistedChannel() {
        val items = listOf(
            streamItem(allowedVideoUrl, blockedChannelUrl),
            streamItem(allowedVideoUrl, allowedChannelUrl)
        )

        val filtered = KidModeContentFilter.filterItems(context, items).items

        assertEquals(1, filtered.size)
        assertEquals(allowedChannelUrl, (filtered[0] as StreamInfoItem).uploaderUrl)
    }

    @Test
    fun dropsAnIndividuallyDeniedVideoEvenFromAnAllowedChannel() {
        val items = listOf(
            streamItem(deniedVideoUrl, allowedChannelUrl),
            streamItem(allowedVideoUrl, allowedChannelUrl)
        )

        val filtered = KidModeContentFilter.filterItems(context, items).items

        assertEquals(1, filtered.size)
        assertEquals(allowedVideoUrl, (filtered[0] as StreamInfoItem).url)
    }

    @Test
    fun dropsABlacklistedChannelItself() {
        val items = listOf(channelItem(blockedChannelUrl), channelItem(allowedChannelUrl))

        val filtered = KidModeContentFilter.filterItems(context, items).items

        assertEquals(1, filtered.size)
        assertEquals(allowedChannelUrl, (filtered[0] as ChannelInfoItem).url)
    }

    @Test
    fun filtersStreamsWithStateForTheFeedTheSameWay() {
        val streams = listOf(
            StreamWithState(newStreamEntity(uploaderUrl = blockedChannelUrl), null),
            StreamWithState(newStreamEntity(uploaderUrl = allowedChannelUrl), null)
        )

        val filtered = KidModeContentFilter.filterStreamsWithState(context, streams)

        assertEquals(1, filtered.size)
        assertEquals(allowedChannelUrl, filtered[0].stream.uploaderUrl)
    }

    @Test
    fun keepsFeedStreamsWhenKidModeDisabled() {
        setKidModeEnabled(false)
        val streams = listOf(StreamWithState(newStreamEntity(uploaderUrl = blockedChannelUrl), null))

        assertEquals(1, KidModeContentFilter.filterStreamsWithState(context, streams).size)
    }

    @Test
    fun aStreamWithNoUploaderUrlIsNeverBlockedAndDoesNotCrash() {
        val itemWithNoUploaderUrl = StreamInfoItem(serviceId, allowedVideoUrl, "a video", StreamType.VIDEO_STREAM)

        val filtered = KidModeContentFilter.filterItems(context, listOf(itemWithNoUploaderUrl)).items

        assertEquals(1, filtered.size)
    }

    @Test
    fun nonGatedInfoItemTypesPassThroughUnfiltered() {
        // Comments aren't videos or channels, so the channel-rule/denied-video checks never
        // apply to them -- this is a documented, deliberate no-op, not an oversight (see
        // wiki/features/kid-mode.md's Phase E section).
        val items = listOf(CommentsInfoItem(serviceId, "https://youtube.com/comment/1", "a comment"))

        val filtered = KidModeContentFilter.filterItems(context, items).items

        assertEquals(1, filtered.size)
    }

    @Test
    fun aNeutralChannelsStreamIsPending() {
        val neutralChannelUrl = "https://youtube.com/channel/neutral"
        val items = listOf(streamItem(allowedVideoUrl, neutralChannelUrl))

        val pending = KidModeContentFilter.filterItems(context, items).pendingChannelKeys

        assertEquals(setOf(KidModeContentFilter.pendingKeyOf(serviceId, neutralChannelUrl)), pending)
    }

    @Test
    fun aWhitelistedChannelsStreamIsNotPending() {
        val whitelistedChannelUrl = "https://youtube.com/channel/whitelisted"
        NewPipeDatabase.getInstance(context).channelRuleDAO().upsertRule(
            ChannelRuleEntity(
                serviceId = serviceId,
                channelUrl = whitelistedChannelUrl,
                status = ChannelListStatus.WHITELISTED,
                setAt = 1L
            )
        )
        val items = listOf(streamItem(allowedVideoUrl, whitelistedChannelUrl))

        val pending = KidModeContentFilter.filterItems(context, items).pendingChannelKeys

        assertEquals(emptySet<String>(), pending)
    }

    @Test
    fun anAlreadySubscribedChannelsStreamIsNotPending() {
        val subscribedChannelUrl = "https://youtube.com/channel/subscribed"
        NewPipeDatabase.getInstance(context).subscriptionDAO().upsertAll(
            listOf(SubscriptionEntity(serviceId = serviceId, url = subscribedChannelUrl, name = "a channel"))
        )
        val items = listOf(streamItem(allowedVideoUrl, subscribedChannelUrl))

        val pending = KidModeContentFilter.filterItems(context, items).pendingChannelKeys

        assertEquals(emptySet<String>(), pending)
    }

    @Test
    fun aBlacklistedChannelsStreamIsNotPendingSinceItsAlreadyDropped() {
        val items = listOf(streamItem(allowedVideoUrl, blockedChannelUrl))

        val pending = KidModeContentFilter.filterItems(context, items).pendingChannelKeys

        assertEquals(emptySet<String>(), pending)
    }
}
