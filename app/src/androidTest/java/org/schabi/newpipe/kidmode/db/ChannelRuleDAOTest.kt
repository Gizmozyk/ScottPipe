package org.schabi.newpipe.kidmode.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.extractor.ServiceList

class ChannelRuleDAOTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: ChannelRuleDAO

    private val serviceId = ServiceList.YouTube.serviceId
    private val channelUrl = "https://youtube.com/channel/1"

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.channelRuleDAO()
    }

    @After
    fun closeDb() {
        db.close()
    }

    private fun newRule(status: ChannelListStatus) = ChannelRuleEntity(
        serviceId = serviceId,
        channelUrl = channelUrl,
        status = status,
        setAt = 1L
    )

    @Test
    fun unknownChannelReturnsNoRule() {
        assertNull(dao.getRule(serviceId, channelUrl))
    }

    @Test
    fun whitelistedChannelIsFound() {
        dao.upsertRule(newRule(ChannelListStatus.WHITELISTED))

        val rule = dao.getRule(serviceId, channelUrl)

        assertEquals(ChannelListStatus.WHITELISTED, rule?.status)
    }

    @Test
    fun upsertingAgainFlipsTheStatusInsteadOfCreatingASecondRow() {
        dao.upsertRule(newRule(ChannelListStatus.WHITELISTED))
        dao.upsertRule(newRule(ChannelListStatus.BLACKLISTED))

        val all = dao.getAllRules().blockingFirst()

        assertEquals(1, all.size)
        assertEquals(ChannelListStatus.BLACKLISTED, all[0].status)
    }

    @Test
    fun upsertingAgainReplacesTheRowRatherThanUpdatingInPlace() {
        // Pins down the specific REPLACE-conflict mechanism (delete+reinsert, new uid) rather
        // than just its observable effect (status flipped) -- Room's OnConflictStrategy.REPLACE
        // is a real delete+insert, not an UPDATE, which matters if this DAO is ever extended
        // with a column (e.g. a future "rule history") that an in-place UPDATE would preserve
        // but a REPLACE would not.
        val firstUid = dao.upsertRule(newRule(ChannelListStatus.WHITELISTED))
        val secondUid = dao.upsertRule(newRule(ChannelListStatus.BLACKLISTED))

        assertNotEquals(firstUid, secondUid)
    }

    @Test
    fun ruleIsScopedToServiceId() {
        dao.upsertRule(newRule(ChannelListStatus.WHITELISTED))

        val rule = dao.getRule(ServiceList.SoundCloud.serviceId, channelUrl)

        assertNull(rule)
    }

    @Test
    fun deleteRuleReportsHowManyRowsChanged() {
        dao.upsertRule(newRule(ChannelListStatus.WHITELISTED))

        assertEquals(1, dao.deleteRule(serviceId, channelUrl))
        assertEquals(0, dao.deleteRule(serviceId, channelUrl))
        assertNull(dao.getRule(serviceId, channelUrl))
    }

    @Test
    fun getAllRulesListsEveryRule() {
        dao.upsertRule(newRule(ChannelListStatus.WHITELISTED))
        dao.upsertRule(
            ChannelRuleEntity(
                serviceId = serviceId,
                channelUrl = "https://youtube.com/channel/2",
                status = ChannelListStatus.BLACKLISTED,
                setAt = 2L
            )
        )

        assertEquals(2, dao.getAllRules().blockingFirst().size)
    }
}
