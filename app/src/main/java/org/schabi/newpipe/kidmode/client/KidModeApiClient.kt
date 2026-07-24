/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.kidmode.client

import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.kidmode.server.KidModeHmac

/**
 * Calls a kid device's embedded HTTP server (see
 * [org.schabi.newpipe.kidmode.server.ApprovalHttpServer]) from a paired parent device. No
 * Android/Keystore/DB dependency -- storage of the pairing itself is
 * [org.schabi.newpipe.kidmode.ParentPairingManager]'s job, this class only speaks the wire
 * protocol. Reuses [KidModeHmac] as-is (it's pure `javax.crypto`, already Android-independent) to
 * sign requests exactly the way the server verifies them.
 *
 * Short timeouts are deliberate: this only ever talks to a device on the same Wi-Fi, so a slow
 * response almost always means the address is stale (the kid device's DHCP lease changed) rather
 * than a slow network -- failing fast keeps the UI responsive instead of hanging for the usual
 * long default HTTP client timeouts.
 */
class KidModeApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    data class PairResult(val deviceId: String, val sharedSecretBase64: String)

    data class RemoteApprovalRequest(
        val id: Long,
        val type: String,
        val status: String,
        val title: String,
        val channelUrl: String?,
        val createdAt: Long
    )

    data class RemoteChannelRule(val serviceId: Int, val channelUrl: String, val status: String)

    /** Thrown for any non-2xx response; [status] is the HTTP status code. */
    class KidModeApiException(val status: Int, message: String) : Exception(message)

    fun pair(host: String, port: Int, deviceName: String, code: String): Single<PairResult> {
        return Single.fromCallable {
            val body = Json.encodeToString(PairRequestDto.serializer(), PairRequestDto(deviceName, code))
            val request = Request.Builder()
                .url(url(host, port, "/pair"))
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val response = Json.decodeFromString<PairResponseDto>(execute(request))
            PairResult(response.deviceId, response.sharedSecret)
        }.subscribeOn(Schedulers.io())
    }

    fun pendingRequests(host: String, port: Int, deviceId: String, secret: ByteArray): Single<List<RemoteApprovalRequest>> {
        return Single.fromCallable {
            val path = "/pending-requests"
            val request = signedRequest(host, port, "GET", path, "", deviceId, secret).build()
            Json.decodeFromString<PendingRequestsDto>(execute(request)).requests.map { it.toRemoteApprovalRequest() }
        }.subscribeOn(Schedulers.io())
    }

    fun approve(host: String, port: Int, deviceId: String, secret: ByteArray, id: Long): Single<RemoteApprovalRequest> {
        return resolve(host, port, "/approve/$id", deviceId, secret)
    }

    fun deny(host: String, port: Int, deviceId: String, secret: ByteArray, id: Long): Single<RemoteApprovalRequest> {
        return resolve(host, port, "/deny/$id", deviceId, secret)
    }

    fun setChannelRule(
        host: String,
        port: Int,
        deviceId: String,
        secret: ByteArray,
        serviceId: Int,
        channelUrl: String,
        status: String
    ): Single<RemoteChannelRule> {
        return Single.fromCallable {
            val body = Json.encodeToString(
                ChannelRuleRequestDto.serializer(),
                ChannelRuleRequestDto(serviceId, channelUrl, status)
            )
            val request = signedRequest(host, port, "POST", "/channels/rule", body, deviceId, secret)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            Json.decodeFromString<ChannelRuleDto>(execute(request)).toRemoteChannelRule()
        }.subscribeOn(Schedulers.io())
    }

    fun unlistChannel(
        host: String,
        port: Int,
        deviceId: String,
        secret: ByteArray,
        serviceId: Int,
        channelUrl: String
    ): Single<Boolean> {
        return Single.fromCallable {
            val body = Json.encodeToString(
                ChannelUnlistRequestDto.serializer(),
                ChannelUnlistRequestDto(serviceId, channelUrl)
            )
            val request = signedRequest(host, port, "POST", "/channels/unlist", body, deviceId, secret)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            Json.decodeFromString<UnlistResponseDto>(execute(request)).removed
        }.subscribeOn(Schedulers.io())
    }

    fun channelRules(host: String, port: Int, deviceId: String, secret: ByteArray): Single<List<RemoteChannelRule>> {
        return Single.fromCallable {
            val request = signedRequest(host, port, "GET", "/channels", "", deviceId, secret).build()
            Json.decodeFromString<ChannelRulesResponseDto>(execute(request)).rules.map { it.toRemoteChannelRule() }
        }.subscribeOn(Schedulers.io())
    }

    private fun resolve(host: String, port: Int, path: String, deviceId: String, secret: ByteArray): Single<RemoteApprovalRequest> {
        return Single.fromCallable {
            val request = signedRequest(host, port, "POST", path, "", deviceId, secret)
                .post("".toRequestBody(null))
                .build()
            Json.decodeFromString<ApprovalRequestDto>(execute(request)).toRemoteApprovalRequest()
        }.subscribeOn(Schedulers.io())
    }

    private fun signedRequest(
        host: String,
        port: Int,
        method: String,
        path: String,
        body: String,
        deviceId: String,
        secret: ByteArray
    ): Request.Builder {
        val signature = KidModeHmac.sign(secret, KidModeHmac.message(method, path, body))
        return Request.Builder()
            .url(url(host, port, path))
            .addHeader(DEVICE_ID_HEADER, deviceId)
            .addHeader(SIGNATURE_HEADER, signature)
    }

    private fun execute(request: Request): String {
        client.newCall(request).execute().use { response ->
            val bodyString = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                val message = try {
                    Json.decodeFromString<ErrorDto>(bodyString).error
                } catch (e: Exception) {
                    bodyString.ifBlank { "HTTP ${response.code}" }
                }
                throw KidModeApiException(response.code, message)
            }
            return bodyString
        }
    }

    private fun url(host: String, port: Int, path: String): String = "http://$host:$port$path"

    private fun ApprovalRequestDto.toRemoteApprovalRequest() = RemoteApprovalRequest(id, type, status, title, channelUrl, createdAt)

    private fun ChannelRuleDto.toRemoteChannelRule() = RemoteChannelRule(serviceId, channelUrl, status)

    @Serializable
    private data class PairRequestDto(val deviceName: String, val code: String)

    @Serializable
    private data class PairResponseDto(val deviceId: String, val sharedSecret: String)

    @Serializable
    private data class ApprovalRequestDto(
        val id: Long,
        val type: String,
        val status: String,
        val title: String,
        val channelUrl: String?,
        val createdAt: Long
    )

    @Serializable
    private data class PendingRequestsDto(val requests: List<ApprovalRequestDto>)

    @Serializable
    private data class ErrorDto(val error: String)

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
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val DEVICE_ID_HEADER = "X-Kid-Mode-Device-Id"
        private const val SIGNATURE_HEADER = "X-Kid-Mode-Signature"
    }
}
