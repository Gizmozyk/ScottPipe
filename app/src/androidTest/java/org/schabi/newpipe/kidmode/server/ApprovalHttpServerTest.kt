package org.schabi.newpipe.kidmode.server

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.kidmode.db.ApprovalRequestEntity
import org.schabi.newpipe.kidmode.db.ApprovalRequestStatus
import org.schabi.newpipe.kidmode.db.ApprovalRequestType
import org.schabi.newpipe.kidmode.db.ChannelListStatus
import org.schabi.newpipe.testUtil.TestDatabase

class ApprovalHttpServerTest {
    private lateinit var server: ApprovalHttpServer
    private lateinit var pairingSession: KidModePairingSession
    private lateinit var baseUrl: String
    private val client = OkHttpClient()

    @Before
    fun setUp() {
        TestDatabase.createReplacingNewPipeDatabase()
        val context = ApplicationProvider.getApplicationContext<Context>()
        pairingSession = KidModePairingSession()
        // Port 0: let the OS pick a free port, so this never collides with a real
        // KidModeServerService instance that might also be running on the device.
        server = ApprovalHttpServer(context, 0, pairingSession).apply { start() }
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

    private fun get(path: String, headers: Map<String, String> = emptyMap()): okhttp3.Response {
        val builder = Request.Builder().url("$baseUrl$path")
        headers.forEach { (name, value) -> builder.addHeader(name, value) }
        return client.newCall(builder.build()).execute()
    }

    private fun post(path: String, body: String = "", headers: Map<String, String> = emptyMap()): okhttp3.Response {
        val builder = Request.Builder()
            .url("$baseUrl$path")
            .post(body.toRequestBody("application/json".toMediaTypeOrNull()))
        headers.forEach { (name, value) -> builder.addHeader(name, value) }
        return client.newCall(builder.build()).execute()
    }

    /** Completes a real `/pair` handshake and returns headers that authenticate as that device. */
    private fun pairAndGetAuthHeaders(method: String, path: String, body: String = ""): Map<String, String> {
        val code = pairingSession.start(System.currentTimeMillis())
        val response = post("/pair", """{"deviceName": "Test Parent", "code": "$code"}""")
        val json = JSONObject(response.body!!.string())
        val deviceId = json.getString("deviceId")
        val secret = Base64.decode(json.getString("sharedSecret"), Base64.NO_WRAP)
        val signature = KidModeHmac.sign(secret, KidModeHmac.message(method, path, body))
        return mapOf("X-Kid-Mode-Device-Id" to deviceId, "X-Kid-Mode-Signature" to signature)
    }

    @Test
    fun pingReturnsOk() {
        val response = get("/ping")

        assertEquals(200, response.code)
        assertEquals("pong", response.body!!.string())
    }

    @Test
    fun pairingWithAValidCodeSucceeds() {
        val code = pairingSession.start(System.currentTimeMillis())

        val response = post("/pair", """{"deviceName": "Test Parent", "code": "$code"}""")

        assertEquals(200, response.code)
        val json = JSONObject(response.body!!.string())
        assertNotNull(json.getString("deviceId"))
        assertNotNull(json.getString("sharedSecret"))
    }

    @Test
    fun pairingWithTheWrongCodeFails() {
        pairingSession.start(System.currentTimeMillis())

        val response = post("/pair", """{"deviceName": "Test Parent", "code": "000000"}""")

        assertEquals(401, response.code)
    }

    @Test
    fun pairingCodeIsSingleUse() {
        val code = pairingSession.start(System.currentTimeMillis())
        post("/pair", """{"deviceName": "First", "code": "$code"}""")

        val response = post("/pair", """{"deviceName": "Second", "code": "$code"}""")

        assertEquals(401, response.code)
    }

    @Test
    fun pendingRequestsWithoutAuthHeadersIsRejected() {
        val response = get("/pending-requests")

        assertEquals(401, response.code)
    }

    @Test
    fun pendingRequestsWithBadSignatureIsRejected() {
        val headers = pairAndGetAuthHeaders("GET", "/pending-requests") +
            mapOf("X-Kid-Mode-Signature" to "0000")

        val response = get("/pending-requests", headers)

        assertEquals(401, response.code)
    }

    @Test
    fun pendingRequestsListsInsertedRequestWhenAuthenticated() {
        val uid = database().approvalRequestDAO().insert(newRequest())
        val headers = pairAndGetAuthHeaders("GET", "/pending-requests")

        val response = get("/pending-requests", headers)
        val requests = JSONObject(response.body!!.string()).getJSONArray("requests")

        assertEquals(200, response.code)
        assertEquals(1, requests.length())
        assertEquals(uid, requests.getJSONObject(0).getLong("id"))
    }

    @Test
    fun approveWithoutAuthHeadersIsRejected() {
        val uid = database().approvalRequestDAO().insert(newRequest())

        val response = post("/approve/$uid")

        assertEquals(401, response.code)
        val updated = database().approvalRequestDAO().getById(uid).blockingFirst()
        assertEquals(ApprovalRequestStatus.PENDING, updated.status)
    }

    @Test
    fun approveMarksRequestApprovedWhenAuthenticated() {
        val uid = database().approvalRequestDAO().insert(newRequest())
        val headers = pairAndGetAuthHeaders("POST", "/approve/$uid")

        val response = post("/approve/$uid", headers = headers)

        assertEquals(200, response.code)
        val updated = database().approvalRequestDAO().getById(uid).blockingFirst()
        assertEquals(ApprovalRequestStatus.APPROVED, updated.status)
    }

    @Test
    fun denyMarksRequestDeniedWhenAuthenticated() {
        val uid = database().approvalRequestDAO().insert(newRequest())
        val headers = pairAndGetAuthHeaders("POST", "/deny/$uid")

        val response = post("/deny/$uid", headers = headers)

        assertEquals(200, response.code)
        val updated = database().approvalRequestDAO().getById(uid).blockingFirst()
        assertEquals(ApprovalRequestStatus.DENIED, updated.status)
    }

    @Test
    fun approveUnknownIdReturnsNotFoundWhenAuthenticated() {
        val headers = pairAndGetAuthHeaders("POST", "/approve/999999")

        val response = post("/approve/999999", headers = headers)

        assertEquals(404, response.code)
    }

    @Test
    fun unknownPathReturnsNotFound() {
        val response = get("/nope")

        assertEquals(404, response.code)
    }

    @Test
    fun setChannelRuleWithoutAuthHeadersIsRejected() {
        val response = post(
            "/channels/rule",
            """{"serviceId": 0, "channelUrl": "https://youtube.com/channel/1", "status": "WHITELISTED"}"""
        )

        assertEquals(401, response.code)
    }

    @Test
    fun setChannelRuleStoresTheRuleWhenAuthenticated() {
        val body = """{"serviceId": 0, "channelUrl": "https://youtube.com/channel/1", "status": "WHITELISTED"}"""
        val headers = pairAndGetAuthHeaders("POST", "/channels/rule", body)

        val response = post("/channels/rule", body, headers)

        assertEquals(200, response.code)
        val rule = database().channelRuleDAO().getRule(0, "https://youtube.com/channel/1")
        assertEquals(ChannelListStatus.WHITELISTED, rule?.status)
    }

    @Test
    fun settingARuleAgainFlipsIt() {
        val whitelistBody = """{"serviceId": 0, "channelUrl": "https://youtube.com/channel/1", "status": "WHITELISTED"}"""
        post("/channels/rule", whitelistBody, pairAndGetAuthHeaders("POST", "/channels/rule", whitelistBody))

        val blacklistBody = """{"serviceId": 0, "channelUrl": "https://youtube.com/channel/1", "status": "BLACKLISTED"}"""
        post("/channels/rule", blacklistBody, pairAndGetAuthHeaders("POST", "/channels/rule", blacklistBody))

        val rule = database().channelRuleDAO().getRule(0, "https://youtube.com/channel/1")
        assertEquals(1, database().channelRuleDAO().getAllRules().blockingFirst().size)
        assertEquals(ChannelListStatus.BLACKLISTED, rule?.status)
    }

    @Test
    fun unlistChannelRemovesTheRuleWhenAuthenticated() {
        val setBody = """{"serviceId": 0, "channelUrl": "https://youtube.com/channel/1", "status": "WHITELISTED"}"""
        post("/channels/rule", setBody, pairAndGetAuthHeaders("POST", "/channels/rule", setBody))

        val unlistBody = """{"serviceId": 0, "channelUrl": "https://youtube.com/channel/1"}"""
        val response = post("/channels/unlist", unlistBody, pairAndGetAuthHeaders("POST", "/channels/unlist", unlistBody))

        assertEquals(200, response.code)
        assertNull(database().channelRuleDAO().getRule(0, "https://youtube.com/channel/1"))
    }

    @Test
    fun getChannelsWithoutAuthHeadersIsRejected() {
        val response = get("/channels")

        assertEquals(401, response.code)
    }

    @Test
    fun getChannelsListsSetRulesWhenAuthenticated() {
        val setBody = """{"serviceId": 0, "channelUrl": "https://youtube.com/channel/1", "status": "WHITELISTED"}"""
        post("/channels/rule", setBody, pairAndGetAuthHeaders("POST", "/channels/rule", setBody))

        val response = get("/channels", pairAndGetAuthHeaders("GET", "/channels"))
        val rules = JSONObject(response.body!!.string()).getJSONArray("rules")

        assertEquals(200, response.code)
        assertEquals(1, rules.length())
        assertEquals("WHITELISTED", rules.getJSONObject(0).getString("status"))
    }
}
