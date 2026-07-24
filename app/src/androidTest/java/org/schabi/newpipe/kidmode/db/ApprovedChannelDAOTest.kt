package org.schabi.newpipe.kidmode.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.extractor.ServiceList

class ApprovedChannelDAOTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: ApprovedChannelDAO

    private val serviceId = ServiceList.YouTube.serviceId
    private val channelUrl = "https://youtube.com/channel/1"

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.approvedChannelDAO()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun unapprovedChannelReturnsNothing() {
        val result = dao.isApproved(serviceId, channelUrl).blockingGet()
        assertNull(result)
    }

    @Test
    fun approvedChannelIsFound() {
        dao.insert(ApprovedChannelEntity(serviceId = serviceId, channelUrl = channelUrl, approvedAt = 1L))

        val result = dao.isApproved(serviceId, channelUrl).blockingGet()

        assertNotNull(result)
        assertEquals(channelUrl, result!!.channelUrl)
    }

    @Test
    fun approvalIsScopedToServiceId() {
        dao.insert(ApprovedChannelEntity(serviceId = serviceId, channelUrl = channelUrl, approvedAt = 1L))

        val result = dao.isApproved(ServiceList.SoundCloud.serviceId, channelUrl).blockingGet()

        assertNull(result)
    }
}
