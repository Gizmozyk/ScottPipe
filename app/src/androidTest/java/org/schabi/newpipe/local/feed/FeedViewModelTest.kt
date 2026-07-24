package org.schabi.newpipe.local.feed

import android.app.Application
import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.database.feed.model.FeedEntity
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.kidmode.db.ChannelListStatus
import org.schabi.newpipe.kidmode.db.ChannelRuleEntity
import org.schabi.newpipe.local.feed.service.FeedEventManager
import org.schabi.newpipe.testUtil.TestDatabase

/**
 * Verifies that Phase E's channel filtering is actually wired into [FeedViewModel]'s real Rx
 * pipeline -- [org.schabi.newpipe.kidmode.KidModeContentFilterTest] only proves the filtering
 * *logic* is correct in isolation; this drives the real ViewModel (real `feed`/`streams`/
 * `subscriptions` table joins, real `FeedEventManager` singleton, real `LiveData`) so a future
 * refactor that accidentally drops the filter step from `FeedViewModel`'s `.map` fails here
 * automatically instead of only being caught by manual testing.
 */
class FeedViewModelTest {
    private lateinit var context: Context
    private lateinit var application: Application

    private val serviceId = ServiceList.YouTube.serviceId
    private val channelUrl = "https://youtube.com/channel/1"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        application = context.applicationContext as Application
        TestDatabase.createReplacingNewPipeDatabase()
        // FeedEventManager is a process-wide singleton -- reset it to a known IdleEvent baseline
        // so a previous test's leftover event can't influence this one.
        FeedEventManager.reset()
        setKidModeEnabled(true)

        val database = NewPipeDatabase.getInstance(context)
        val subscription = database.subscriptionDAO().upsertAll(
            listOf(SubscriptionEntity(serviceId = serviceId, url = channelUrl, name = "a channel"))
        )[0]
        val streamUid = database.streamDAO().upsert(
            StreamEntity(
                serviceId = serviceId,
                url = "https://youtube.com/watch?v=1",
                title = "a video",
                streamType = StreamType.VIDEO_STREAM,
                duration = 60L,
                uploader = "a channel",
                uploaderUrl = channelUrl
            )
        )
        database.feedDAO().insert(FeedEntity(streamId = streamUid, subscriptionId = subscription.uid))
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

    private fun blacklistChannel() {
        NewPipeDatabase.getInstance(context).channelRuleDAO().upsertRule(
            ChannelRuleEntity(
                serviceId = serviceId,
                channelUrl = channelUrl,
                status = ChannelListStatus.BLACKLISTED,
                setAt = 1L
            )
        )
    }

    /** Constructs the real [FeedViewModel] and blocks until its first [FeedState.LoadedState]. */
    private fun awaitLoadedState(): FeedState.LoadedState {
        val latch = CountDownLatch(1)
        var result: FeedState.LoadedState? = null

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val viewModel = FeedViewModel(
                application,
                FeedGroupEntity.GROUP_ALL_ID,
                initialShowPlayedItems = true,
                initialShowPartiallyPlayedItems = true,
                initialShowFutureItems = true
            )
            viewModel.stateLiveData.observeForever { state ->
                if (state is FeedState.LoadedState && result == null) {
                    result = state
                    latch.countDown()
                }
            }
        }

        assertTrue("Timed out waiting for FeedViewModel to emit a LoadedState", latch.await(10, TimeUnit.SECONDS))
        return result!!
    }

    @Test
    fun feedIncludesTheStreamWhenNothingIsBlacklisted() {
        val loaded = awaitLoadedState()

        assertEquals(1, loaded.items.size)
    }

    @Test
    fun feedExcludesAStreamFromAChannelBlacklistedAfterSubscribing() {
        blacklistChannel()

        val loaded = awaitLoadedState()

        assertEquals(0, loaded.items.size)
    }
}
