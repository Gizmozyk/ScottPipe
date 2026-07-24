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
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.kidmode.db.ChannelListStatus
import org.schabi.newpipe.kidmode.db.ChannelRuleEntity
import org.schabi.newpipe.testUtil.TestDatabase

class KidModeGateTest {
    private lateinit var context: Context
    private lateinit var gate: KidModeGate

    private val serviceId = ServiceList.YouTube.serviceId
    private val channelUrl = "https://youtube.com/channel/1"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        TestDatabase.createReplacingNewPipeDatabase()
        gate = KidModeGate(context)
        setKidModeEnabled(true)
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

    private fun insertRule(status: ChannelListStatus) {
        NewPipeDatabase.getInstance(context).channelRuleDAO().upsertRule(
            ChannelRuleEntity(serviceId = serviceId, channelUrl = channelUrl, status = status, setAt = 1L)
        )
    }

    @Test
    fun playAllowedWhenKidModeDisabled() {
        setKidModeEnabled(false)
        assertEquals(GateDecision.ALLOWED, gate.canPlay(serviceId, channelUrl).blockingGet())
    }

    @Test
    fun playBlockedForBlacklistedChannel() {
        insertRule(ChannelListStatus.BLACKLISTED)
        assertEquals(GateDecision.BLOCKED, gate.canPlay(serviceId, channelUrl).blockingGet())
    }

    @Test
    fun playAllowedForWhitelistedChannelWithoutApproval() {
        insertRule(ChannelListStatus.WHITELISTED)
        assertEquals(GateDecision.ALLOWED, gate.canPlay(serviceId, channelUrl).blockingGet())
    }

    @Test
    fun playNeedsApprovalWhenNoRuleAndNotSubscribed() {
        assertEquals(GateDecision.NEEDS_APPROVAL, gate.canPlay(serviceId, channelUrl).blockingGet())
    }

    @Test
    fun playAllowedWhenNoRuleButAlreadySubscribed() {
        NewPipeDatabase.getInstance(context).subscriptionDAO().upsertAll(
            listOf(SubscriptionEntity(serviceId = serviceId, url = channelUrl, name = "Test channel"))
        )
        assertEquals(GateDecision.ALLOWED, gate.canPlay(serviceId, channelUrl).blockingGet())
    }

    @Test
    fun blacklistedChannelNeverCreatesAPendingRequest() {
        insertRule(ChannelListStatus.BLACKLISTED)
        gate.canPlay(serviceId, channelUrl).blockingGet()
        val pending = NewPipeDatabase.getInstance(context).approvalRequestDAO().getPending().blockingFirst()
        assertEquals(0, pending.size)
    }

    @Test
    fun subscribeAllowedWhenKidModeDisabled() {
        setKidModeEnabled(false)
        assertEquals(GateDecision.ALLOWED, gate.canSubscribe(serviceId, channelUrl))
    }

    @Test
    fun subscribeBlockedForBlacklistedChannel() {
        insertRule(ChannelListStatus.BLACKLISTED)
        assertEquals(GateDecision.BLOCKED, gate.canSubscribe(serviceId, channelUrl))
    }

    @Test
    fun subscribeAllowedForWhitelistedChannel() {
        insertRule(ChannelListStatus.WHITELISTED)
        assertEquals(GateDecision.ALLOWED, gate.canSubscribe(serviceId, channelUrl))
    }

    @Test
    fun subscribeNeedsApprovalWhenNoRule() {
        assertEquals(GateDecision.NEEDS_APPROVAL, gate.canSubscribe(serviceId, channelUrl))
    }
}
