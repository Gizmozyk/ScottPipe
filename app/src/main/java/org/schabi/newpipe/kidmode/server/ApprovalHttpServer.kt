/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.kidmode.server

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import io.reactivex.rxjava3.core.Single
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.kidmode.KidModeGate
import org.schabi.newpipe.kidmode.db.ApprovalRequestEntity

/**
 * Exposes Kid Mode's pending approval requests over local HTTP, so a paired device can someday
 * (Phase C/D, not built yet) list and approve/deny them instead of only the local PIN button in
 * [org.schabi.newpipe.kidmode.ui.ApprovalWaitingDialogFragment]. Bound to loopback only for now --
 * see `wiki/adr/0001-kid-mode-architecture.md` for why, and what changes that in a later phase.
 *
 * All approve/deny logic is delegated to [KidModeGate], shared with the local dialog, so there's
 * exactly one place that performs a request's completion work (e.g. subscribing).
 */
class ApprovalHttpServer(context: Context, port: Int) : NanoHTTPD(LOOPBACK_ADDRESS, port) {
    private val appContext = context.applicationContext
    private val database = NewPipeDatabase.getInstance(appContext)
    private val kidModeGate = KidModeGate(appContext)

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        return try {
            when {
                session.method == Method.GET && uri == "/ping" ->
                    textResponse(Response.Status.OK, "pong")

                session.method == Method.GET && uri == "/pending-requests" ->
                    pendingRequestsResponse()

                session.method == Method.POST && uri.startsWith(APPROVE_PREFIX) ->
                    resolveResponse(uri.removePrefix(APPROVE_PREFIX)) { kidModeGate.approve(it) }

                session.method == Method.POST && uri.startsWith(DENY_PREFIX) ->
                    resolveResponse(uri.removePrefix(DENY_PREFIX)) { kidModeGate.deny(it) }

                else -> jsonError(Response.Status.NOT_FOUND, "not found")
            }
        } catch (e: Exception) {
            jsonError(Response.Status.INTERNAL_ERROR, e.message ?: "error")
        }
    }

    private fun pendingRequestsResponse(): Response {
        val requests = database.approvalRequestDAO().getPending().blockingFirst()
            .map(ApprovalRequestDto::from)
        return jsonResponse(Response.Status.OK, Json.encodeToString(PendingRequestsDto.serializer(), PendingRequestsDto(requests)))
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

    companion object {
        private const val LOOPBACK_ADDRESS = "127.0.0.1"
        private const val APPROVE_PREFIX = "/approve/"
        private const val DENY_PREFIX = "/deny/"
        const val DEFAULT_PORT = 46821
    }
}
