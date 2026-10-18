package io.github.gycrosskit.dynamicpage

import kotlinx.serialization.Serializable

@Serializable
data class DynamicNetworkRequest(
    val method: String,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val bodyJson: String? = null,
)

/** status 为 ok/unavailable/invalid_request/forbidden/failed；bodyJson 不承载异常或凭据。 */
@Serializable
data class DynamicNetworkResponse(
    val status: String,
    val httpStatus: Int? = null,
    val bodyJson: String? = null,
)

/** 由宿主注入现有网络能力；组件不创建 HttpClient，也不读 Token。 */
fun interface DynamicNetworkTransport {
    suspend fun execute(request: DynamicNetworkRequest): DynamicNetworkResponse
}
