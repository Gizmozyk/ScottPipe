/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.kidmode.server

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import io.reactivex.rxjava3.core.Single
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.kidmode.KidModeGate
import org.schabi.newpipe.kidmode.KidModePairingManager
import org.schabi.newpipe.kidmode.db.ApprovalRequestEntity
import org.schabi.newpipe.kidmode.db.ChannelListStatus
import org.schabi.newpipe.kidmode.db.ChannelRuleEntity

/**
 * Exposes Kid Mode's pending approval requests over local HTTP, so a paired device can (Phase D,
 * not built yet) list and approve/deny them instead of only the local PIN button in
 * [org.schabi.newpipe.kidmode.ui.ApprovalWaitingDialogFragment]. Bound to all interfaces now that
 * requests are authenticated -- see `wiki/adr/0001-kid-mode-architecture.md` for why Phase B kept
 * this loopback-only and what changed for Phase C.
 *
 * All approve/deny logic is delegated to [KidModeGate], shared with the local dialog, so there's
 * exactly one place that performs a request's completion work (e.g. subscribing). All pairing/
 * auth logic is delegated to [KidModePairingManager] likewise.
 */
class ApprovalHttpServer(
    context: Context,
    port: Int,
    private val pairingSession: KidModePairingSession
) : NanoHTTPD(BIND_ADDRESS, port) {
    private val appContext = context.applicationContext
    private val database = NewPipeDatabase.getInstance(appContext)
    private val kidModeGate = KidModeGate(appContext)
    private val pairingManager = KidModePairingManager(appContext)

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method.name

        return try {
            val body = if (session.method == Method.POST) readBody(session) else ""
            when {
                session.method == Method.GET && uri == "/ping" ->
                    textResponse(Response.Status.OK, "pong")

                session.method == Method.POST && uri == "/pair" ->
                    handlePair(body)

                session.method == Method.GET && uri == "/pending-requests" ->
                    withAuth(session, method, uri, body) { pendingRequestsResponse() }

                session.method == Method.POST && uri.startsWith(APPROVE_PREFIX) ->
                    withAuth(session, method, uri, body) {
                        resolveResponse(uri.removePrefix(APPROVE_PREFIX)) { kidModeGate.approve(it) }
                    }

                session.method == Method.POST && uri.startsWith(DENY_PREFIX) ->
                    withAuth(session, method, uri, body) {
                        resolveResponse(uri.removePrefix(DENY_PREFIX)) { kidModeGate.deny(it) }
                    }

                session.method == Method.POST && uri == "/channels/rule" ->
                    withAuth(session, method, uri, body) { handleSetChannelRule(body) }

                session.method == Method.POST && uri == "/channels/unlist" ->
                    withAuth(session, method, uri, body) { handleUnlistChannel(body) }

                session.method == Method.GET && uri == "/channels" ->
                    withAuth(session, method, uri, body) { channelRulesResponse() }

                else -> jsonError(Response.Status.NOT_FOUND, "not found")
            }
        } catch (e: Exception) {
            jsonError(Response.Status.INTERNAL_ERROR, e.message ?: "error")
        }
    }

    private fun withAuth(session: IHTTPSession, method: String, uri: String, body: String, handler: () -> Response): Response {
        val deviceId = session.headers[DEVICE_ID_HEADER]
        val signature = session.headers[SIGNATURE_HEADER]
        if (deviceId.isNullOrBlank() || signature.isNullOrBlank()) {
            return jsonError(Response.Status.UNAUTHORIZED, "missing auth headers")
        }
        if (!pairingManager.authenticate(deviceId, method, uri, body, signature)) {
            return jsonError(Response.Status.UNAUTHORIZED, "invalid signature")
        }
        return handler()
    }

    private fun handlePair(body: String): Response {
        val request = try {
            Json.decodeFromString<PairRequestDto>(body)
        } catch (e: Exception) {
            return jsonError(Response.Status.BAD_REQUEST, "invalid request")
        }

        if (!pairingSession.consumeIfValid(request.code, System.currentTimeMillis())) {
            return jsonError(Response.Status.UNAUTHORIZED, "invalid or expired code")
        }

        val result = pairingManager.pair(request.deviceName)
        return jsonResponse(
            Response.Status.OK,
            Json.encodeToString(PairResponseDto.serializer(), PairResponseDto(result.deviceId, result.sharedSecretBase64))
        )
    }

    private fun readBody(session: IHTTPSession): String {
        val files = mutableMapOf<String, String>()
        session.parseBody(files)
        return files["postData"] ?: ""
    }

    private fun pendingRequestsResponse(): Response {
        val requests = database.approvalRequestDAO().getPending().blockingFirst()
            .map(ApprovalRequestDto::from)
        return jsonResponse(Response.Status.OK, Json.encodeToString(PendingRequestsDto.serializer(), PendingRequestsDto(requests)))
    }

    private fun handleSetChannelRule(body: String): Response {
        val request = try {
            Json.decodeFromString<ChannelRuleRequestDto>(body)
        } catch (e: Exception) {
            return jsonError(Response.Status.BAD_REQUEST, "invalid request")
        }
        val status = try {
            ChannelListStatus.valueOf(request.status)
        } catch (e: Exception) {
            return jsonError(Response.Status.BAD_REQUEST, "invalid status")
        }

        database.channelRuleDAO().upsertRule(
            ChannelRuleEntity(
                serviceId = request.serviceId,
                channelUrl = request.channelUrl,
                status = status,
                setAt = System.currentTimeMillis()
            )
        )
        return jsonResponse(
            Response.Status.OK,
            Json.encodeToString(
                ChannelRuleDto.serializer(),
                ChannelRuleDto(request.serviceId, request.channelUrl, status.name)
            )
        )
    }

    private fun handleUnlistChannel(body: String): Response {
        val request = try {
            Json.decodeFromString<ChannelUnlistRequestDto>(body)
        } catch (e: Exception) {
            return jsonError(Response.Status.BAD_REQUEST, "invalid request")
        }
        val removed = database.channelRuleDAO().deleteRule(request.serviceId, request.channelUrl) > 0
        return jsonResponse(
            Response.Status.OK,
            Json.encodeToString(UnlistResponseDto.serializer(), UnlistResponseDto(removed))
        )
    }

    private fun channelRulesResponse(): Response {
        val rules = database.channelRuleDAO().getAllRules().blockingFirst()
            .map { ChannelRuleDto(it.serviceId, it.channelUrl, it.status.name) }
        return jsonResponse(
            Response.Status.OK,
            Json.encodeToString(ChannelRulesResponseDto.serializer(), ChannelRulesResponseDto(rules))
        )
    }

    private fun resolveResponse(
        idParam: String,
        resolve: (Long) -> Single<ApprovalRequestEntity>
    ): Response {
        val id = idParam.toLongOrNull() ?: return jsonError(Response.Status.BAD_REQUEST, "invalid id")
        return try {
            val request = resolve(id).blockingGet()
            jsonResponse(Response.Status.OK, Json.encodeToString(ApprovalRequestDto.serializer(), ApprovalRequestDto.from(request)))
        } catch (e: Exception) {
            jsonError(Response.Status.NOT_FOUND, e.message ?: "not found")
        }
    }

    private fun textResponse(status: Response.Status, text: String): Response = newFixedLengthResponse(status, "text/plain", text)

    private fun jsonResponse(status: Response.Status, body: String): Response = newFixedLengthResponse(status, "application/json", body)

    private fun jsonError(status: Response.Status, message: String): Response = jsonResponse(status, Json.encodeToString(ErrorDto.serializer(), ErrorDto(message)))

    @Serializable
    private data class ApprovalRequestDto(
        val id: Long,
        val type: String,
        val status: String,
        val title: String,
        val channelUrl: String?,
        val createdAt: Long
    ) {
        companion object {
            fun from(entity: ApprovalRequestEntity) = ApprovalRequestDto(
                id = entity.uid,
                type = entity.requestType.name,
                status = entity.status.name,
                title = entity.targetTitle,
                channelUrl = entity.channelUrl,
                createdAt = entity.createdAt
            )
        }
    }

    @Serializable
    private data class PendingRequestsDto(val requests: List<ApprovalRequestDto>)

    @Serializable
    private data class ErrorDto(val error: String)

    @Serializable
    private data class PairRequestDto(val deviceName: String, val code: String)

    @Serializable
    private data class PairResponseDto(val deviceId: String, val sharedSecret: String)

    @Serializable
    private data class ChannelRuleRequestDto(val serviceId: Int, val channelUrl: String, val status: String)

    @Serializable
    private data class ChannelUnlistRequestDto(val serviceId: Int, val channelUrl: String)

    @Serializable
    private data class ChannelRuleDto(val serviceId: Int, val channelUrl: String, val status: String)

    @Serializable
    private data class ChannelRulesResponseDto(val rules: List<ChannelRuleDto>)

    @Serializable
    private data class UnlistResponseDto(val removed: Boolean)

    companion object {
        private const val BIND_ADDRESS = "0.0.0.0"
        private const val APPROVE_PREFIX = "/approve/"
        private const val DENY_PREFIX = "/deny/"
        private const val DEVICE_ID_HEADER = "x-kid-mode-device-id"
        private const val SIGNATURE_HEADER = "x-kid-mode-signature"
        const val DEFAULT_PORT = 46821
    }
}
