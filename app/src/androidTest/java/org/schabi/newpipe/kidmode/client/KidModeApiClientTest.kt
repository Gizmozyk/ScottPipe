package org.schabi.newpipe.kidmode.client

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.kidmode.db.ApprovalRequestEntity
import org.schabi.newpipe.kidmode.db.ApprovalRequestType
import org.schabi.newpipe.kidmode.server.ApprovalHttpServer
import org.schabi.newpipe.kidmode.server.KidModePairingSession
import org.schabi.newpipe.testUtil.TestDatabase

/**
 * Exercises [KidModeApiClient] against a real, in-process [ApprovalHttpServer] -- the
 * counterpart to `ApprovalHttpServerTest`, which exercises the same server with raw OkHttp calls
 * the way a manual curl session would. This one drives the actual production client, so a
 * protocol mismatch between the two sides (e.g. the NanoHTTPD `Content-Type` gotcha documented in
 * wiki/testing.md, which previously only surfaced via manual pairing) fails here automatically.
 */
class KidModeApiClientTest {
    private lateinit var server: ApprovalHttpServer
    private lateinit var pairingSession: KidModePairingSession
    private val client = KidModeApiClient()

    private val host = "127.0.0.1"
    private val port get() = server.listeningPort

    @Before
    fun setUp() {
        TestDatabase.createReplacingNewPipeDatabase()
        val context = ApplicationProvider.getApplicationContext<Context>()
        pairingSession = KidModePairingSession()
        // Port 0: let the OS pick a free port, matching ApprovalHttpServerTest's convention.
        server = ApprovalHttpServer(context, 0, pairingSession).apply { start() }
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private fun pair(): KidModeApiClient.PairResult {
        val code = pairingSession.start(System.currentTimeMillis())
        return client.pair(host, port, "Test Parent", code).blockingGet()
    }

    private fun insertPendingRequest(): Long {
        return NewPipeDatabase.getInstance(ApplicationProvider.getApplicationContext())
            .approvalRequestDAO()
            .insert(
                ApprovalRequestEntity(
                    requestType = ApprovalRequestType.PLAY_VIDEO,
                    serviceId = 0,
                    targetUrl = "https://youtube.com/watch?v=1",
                    targetTitle = "a video",
                    channelUrl = "https://youtube.com/channel/1",
                    createdAt = 1L
                )
            )
    }

    @Test
    fun pairWithValidCodeSucceeds() {
        val result = pair()

        assertTrue(result.deviceId.isNotBlank())
        assertTrue(result.sharedSecretBase64.isNotBlank())
    }

    @Test
    fun pairWithWrongCodeFails() {
        pairingSession.start(System.currentTimeMillis())

        val observer = client.pair(host, port, "Test Parent", "000000").test()
        observer.awaitDone(5, TimeUnit.SECONDS)

        observer.assertError { it is KidModeApiClient.KidModeApiException && it.status == 401 }
    }

    @Test
    fun pendingRequestsReturnsEmptyListWhenNoneExist() {
        val result = pair()
        val secret = Base64.decode(result.sharedSecretBase64, Base64.NO_WRAP)

        val requests = client.pendingRequests(host, port, result.deviceId, secret).blockingGet()

        assertEquals(0, requests.size)
    }

    @Test
    fun pendingRequestsWithUnknownDeviceFailsWithUnauthorized() {
        val observer = client.pendingRequests(host, port, "unknown-device", ByteArray(32)).test()
        observer.awaitDone(5, TimeUnit.SECONDS)

        observer.assertError { it is KidModeApiClient.KidModeApiException && it.status == 401 }
    }

    @Test
    fun pendingRequestsListsAnInsertedRequest() {
        val result = pair()
        val secret = Base64.decode(result.sharedSecretBase64, Base64.NO_WRAP)
        val uid = insertPendingRequest()

        val requests = client.pendingRequests(host, port, result.deviceId, secret).blockingGet()

        assertEquals(1, requests.size)
        assertEquals(uid, requests[0].id)
    }

    @Test
    fun approveMarksRequestApproved() {
        val result = pair()
        val secret = Base64.decode(result.sharedSecretBase64, Base64.NO_WRAP)
        val uid = insertPendingRequest()

        val approved = client.approve(host, port, result.deviceId, secret, uid).blockingGet()

        assertEquals("APPROVED", approved.status)
    }

    @Test
    fun denyMarksRequestDenied() {
        val result = pair()
        val secret = Base64.decode(result.sharedSecretBase64, Base64.NO_WRAP)
        val uid = insertPendingRequest()

        val denied = client.deny(host, port, result.deviceId, secret, uid).blockingGet()

        assertEquals("DENIED", denied.status)
    }
}
