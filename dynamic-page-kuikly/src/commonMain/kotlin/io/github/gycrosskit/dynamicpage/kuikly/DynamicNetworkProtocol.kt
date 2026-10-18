package io.github.gycrosskit.dynamicpage.kuikly

import io.github.gycrosskit.dynamicpage.DynamicCapabilityRequest
import io.github.gycrosskit.dynamicpage.DynamicNetworkRequest
import io.github.gycrosskit.dynamicpage.DynamicNetworkResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal val networkJson = Json { encodeDefaults = true }
internal const val MAX_PENDING_REQUESTS = 8
// 配置、会话、Auth、音效及页面事件各自等待；仍与八个短请求隔离并保持每 Renderer 有界。
internal const val MAX_PENDING_TASKS = 16
internal const val MAX_PROTOCOL_BYTES = 512 * 1024
internal val responseStatuses = setOf("ok", "unavailable", "invalid_request", "forbidden", "failed")

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class NetworkCall(
    val requestId: String,
    val request: DynamicNetworkRequest? = null,
    // 旧接收端拒绝未知字段；旧 request/cancel 不能多发这个 null 字段。
    @EncodeDefault(EncodeDefault.Mode.NEVER) val capabilityRequest: DynamicCapabilityRequest? = null,
)

internal fun validCapabilityRequest(request: DynamicCapabilityRequest): Boolean {
    val identifier = Regex("[A-Za-z_][A-Za-z0-9_.-]{0,127}")
    return identifier.matches(request.capability) && identifier.matches(request.operation) &&
        request.paramsJson.encodeToByteArray().size <= MAX_PROTOCOL_BYTES &&
        runCatching { networkJson.parseToJsonElement(request.paramsJson) }.getOrNull() is JsonObject
}

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
