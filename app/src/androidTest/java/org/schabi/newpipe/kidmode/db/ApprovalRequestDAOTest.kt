package org.schabi.newpipe.kidmode.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.extractor.ServiceList

class ApprovalRequestDAOTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: ApprovalRequestDAO

    private val serviceId = ServiceList.YouTube.serviceId

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.approvalRequestDAO()
    }

    @After
    fun closeDb() {
        db.close()
    }

    private fun newRequest(
        type: ApprovalRequestType = ApprovalRequestType.PLAY_VIDEO,
        url: String = "https://youtube.com/watch?v=1"
    ) = ApprovalRequestEntity(
        requestType = type,
        serviceId = serviceId,
        targetUrl = url,
        targetTitle = "a video",
        channelUrl = "https://youtube.com/channel/1",
        createdAt = 1L
    )

    @Test
    fun insertedRequestStartsPending() {
        val uid = dao.insert(newRequest())
        val request = dao.getById(uid).blockingFirst()

        assertEquals(ApprovalRequestStatus.PENDING, request.status)
        assertEquals(uid, request.uid)
    }

    @Test
    fun getPendingOnlyReturnsPendingRequests() {
        val pendingUid = dao.insert(newRequest(url = "https://youtube.com/watch?v=pending"))
        val approvedUid = dao.insert(newRequest(url = "https://youtube.com/watch?v=approved"))
        dao.updateStatus(approvedUid, ApprovalRequestStatus.APPROVED, 2L)

        val pending = dao.getPending().blockingFirst()

        assertEquals(1, pending.size)
        assertEquals(pendingUid, pending[0].uid)
    }

    @Test
    fun updateStatusSetsStatusAndResolvedAt() {
        val uid = dao.insert(newRequest())

        val rowsUpdated = dao.updateStatus(uid, ApprovalRequestStatus.DENIED, 42L)

        assertEquals(1, rowsUpdated)
        val request = dao.getById(uid).blockingFirst()
        assertEquals(ApprovalRequestStatus.DENIED, request.status)
        assertEquals(42L, request.resolvedAt)
    }

    @Test
    fun subscribeRequestIsPersistedWithItsType() {
        val uid = dao.insert(newRequest(type = ApprovalRequestType.SUBSCRIBE_CHANNEL))

        val request = dao.getById(uid).blockingFirst()

        assertTrue(request.requestType == ApprovalRequestType.SUBSCRIBE_CHANNEL)
    }
}
