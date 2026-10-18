package io.github.gycrosskit.dynamicpage.kuikly

import io.github.gycrosskit.dynamicpage.DynamicNetworkRequest
import io.github.gycrosskit.dynamicpage.DynamicNetworkResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val networkJson = Json { encodeDefaults = true }
internal const val MAX_PENDING_REQUESTS = 8
internal const val MAX_PROTOCOL_BYTES = 512 * 1024
internal val responseStatuses = setOf("ok", "unavailable", "invalid_request", "forbidden", "failed")

@Serializable
internal data class NetworkCall(val requestId: String, val request: DynamicNetworkRequest? = null)

internal fun boundedResponse(response: DynamicNetworkResponse): String {
    val body = response.bodyJson
    val valid = (body == null || body.encodeToByteArray().size <= MAX_PROTOCOL_BYTES) && response.status in responseStatuses &&
        (response.httpStatus == null || response.httpStatus in 100..599) &&
        (body == null || runCatching { networkJson.parseToJsonElement(body) }.isSuccess)
    if (valid) {
        val encoded = networkJson.encodeToString(DynamicNetworkResponse.serializer(), response)
        if (encoded.encodeToByteArray().size <= MAX_PROTOCOL_BYTES) return encoded
    }
    return networkJson.encodeToString(DynamicNetworkResponse.serializer(), DynamicNetworkResponse("failed"))
}
