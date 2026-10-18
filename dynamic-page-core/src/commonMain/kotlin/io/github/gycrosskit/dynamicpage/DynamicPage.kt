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
    if (!request.isValid()) return DynamicPageOpenResult.INVALID_REQUEST
    return try {
        runtime.open(request)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        DynamicPageOpenResult.UNAVAILABLE
    }
}

/** 下载/校验可挂起；返回值只持有已准备版本，不在准备期间改变导航。 */
interface DynamicPageLoader {
    val provider: String
    suspend fun prepare(request: DynamicPageRequest): PreparedDynamicPage?
}

/** open 在最终导航提交点同步挂载；成功后资源归页面，其余情况由调用者 close。 */
interface PreparedDynamicPage {
    fun open(): DynamicPageOpenResult
    fun close()
}

fun DynamicPageRequest.isValid(): Boolean {
    val identifier = Regex("[A-Za-z_][A-Za-z0-9_.-]{0,127}")
    return identifier.matches(moduleName) && identifier.matches(pageName) &&
        paramsJson.encodeToByteArray().size <= 64 * 1024 &&
        runCatching { Json.parseToJsonElement(paramsJson) }.getOrNull() is JsonObject
}

/** 与既有 open 共用 provider/请求边界；none 不触发 adapter 或下载。 */
suspend fun DynamicPageConfiguration.prepare(
    request: DynamicPageRequest,
    loader: DynamicPageLoader?,
): PreparedDynamicPage? {
    if (provider == "none" || provider.isBlank() || loader == null || loader.provider != provider || !request.isValid()) return null
    return try { loader.prepare(request) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }
}
