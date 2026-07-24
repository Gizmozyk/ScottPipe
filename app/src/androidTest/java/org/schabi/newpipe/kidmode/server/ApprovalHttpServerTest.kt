package org.schabi.newpipe.kidmode.server

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.kidmode.db.ApprovalRequestEntity
import org.schabi.newpipe.kidmode.db.ApprovalRequestStatus
import org.schabi.newpipe.kidmode.db.ApprovalRequestType
import org.schabi.newpipe.testUtil.TestDatabase

class ApprovalHttpServerTest {
    private lateinit var server: ApprovalHttpServer
    private lateinit var baseUrl: String
    private val client = OkHttpClient()

    @Before
    fun setUp() {
        TestDatabase.createReplacingNewPipeDatabase()
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Port 0: let the OS pick a free port, so this never collides with a real
        // KidModeServerService instance that might also be running on the device.
        server = ApprovalHttpServer(context, 0).apply { start() }
        baseUrl = "http://127.0.0.1:${server.listeningPort}"
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private fun database() = NewPipeDatabase.getInstance(ApplicationProvider.getApplicationContext())

    private fun newRequest() = ApprovalRequestEntity(
        requestType = ApprovalRequestType.PLAY_VIDEO,
        serviceId = 0,
        targetUrl = "https://youtube.com/watch?v=1",
        targetTitle = "a video",
        channelUrl = "https://youtube.com/channel/1",
        createdAt = 1L
    )

    private fun post(path: String) = client.newCall(
        Request.Builder().url("$baseUrl$path").post("".toRequestBody("text/plain".toMediaTypeOrNull())).build()
    ).execute()

    @Test
    fun pingReturnsOk() {
        val response = client.newCall(Request.Builder().url("$baseUrl/ping").build()).execute()

        assertEquals(200, response.code)
        assertEquals("pong", response.body!!.string())
    }

    @Test
    fun pendingRequestsListsInsertedRequest() {
        val uid = database().approvalRequestDAO().insert(newRequest())

        val response = client.newCall(Request.Builder().url("$baseUrl/pending-requests").build()).execute()
        val requests = JSONObject(response.body!!.string()).getJSONArray("requests")

        assertEquals(1, requests.length())
        assertEquals(uid, requests.getJSONObject(0).getLong("id"))
    }

    @Test
    fun approveMarksRequestApproved() {
        val uid = database().approvalRequestDAO().insert(newRequest())

        val response = post("/approve/$uid")

        assertEquals(200, response.code)
        val updated = database().approvalRequestDAO().getById(uid).blockingFirst()
        assertEquals(ApprovalRequestStatus.APPROVED, updated.status)
    }

    @Test
    fun denyMarksRequestDenied() {
        val uid = database().approvalRequestDAO().insert(newRequest())

        val response = post("/deny/$uid")

        assertEquals(200, response.code)
        val updated = database().approvalRequestDAO().getById(uid).blockingFirst()
        assertEquals(ApprovalRequestStatus.DENIED, updated.status)
    }

    @Test
    fun approveUnknownIdReturnsNotFound() {
        val response = post("/approve/999999")

        assertEquals(404, response.code)
    }

    @Test
    fun unknownPathReturnsNotFound() {
        val response = client.newCall(Request.Builder().url("$baseUrl/nope").build()).execute()

        assertEquals(404, response.code)
    }
}
