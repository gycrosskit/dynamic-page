package io.github.gycrosskit.dynamicpage

import kotlinx.serialization.Serializable

@Serializable
data class DynamicCapabilityRequest(
    val capability: String,
    val operation: String,
    val paramsJson: String = "{}",
)

/** 沿用已发布的回执结构，保持 iOS 薄接收端和旧网络调用兼容。 */
typealias DynamicCapabilityResponse = DynamicNetworkResponse

/** 能力授权和实现归宿主；组件不持有网络、存储或导航状态。 */
fun interface DynamicCapabilityTransport {
    suspend fun execute(request: DynamicCapabilityRequest): DynamicCapabilityResponse

    /** 每个 Renderer 的接收端释放自身监听与句柄；进程级无状态能力无需实现。 */
    fun close() {}
}
