package io.github.gycrosskit.dynamicpage

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
data class DynamicPageRequest(val moduleName: String, val pageName: String, val paramsJson: String = "{}")

@Serializable
data class DynamicPageConfiguration(val provider: String = "none")

enum class DynamicPageOpenResult { OPENED, UNAVAILABLE, INVALID_REQUEST }

interface DynamicPageRuntime {
    val provider: String
    suspend fun open(request: DynamicPageRequest): DynamicPageOpenResult
}

/** provider 由宿主安装的 runtime 声明；none 永远关闭，不隐式启动下载或 SDK。 */
suspend fun DynamicPageConfiguration.open(
    request: DynamicPageRequest,
    runtime: DynamicPageRuntime? = null,
): DynamicPageOpenResult {
    if (provider == "none" || provider.isBlank() || runtime == null || runtime.provider != provider) {
        return DynamicPageOpenResult.UNAVAILABLE
    }
    val identifier = Regex("[A-Za-z_][A-Za-z0-9_.-]{0,127}")
    if (!identifier.matches(request.moduleName) || !identifier.matches(request.pageName) ||
        request.paramsJson.encodeToByteArray().size > 64 * 1024 ||
        runCatching { Json.parseToJsonElement(request.paramsJson) }.getOrNull() !is JsonObject
    ) return DynamicPageOpenResult.INVALID_REQUEST
    return try {
        runtime.open(request)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DynamicPageOpenResult.UNAVAILABLE
    }
}
