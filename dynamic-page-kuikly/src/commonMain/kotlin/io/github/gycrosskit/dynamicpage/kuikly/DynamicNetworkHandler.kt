package io.github.gycrosskit.dynamicpage.kuikly

import io.github.gycrosskit.dynamicpage.DynamicCapabilityTransport
import io.github.gycrosskit.dynamicpage.DynamicNetworkResponse
import io.github.gycrosskit.dynamicpage.DynamicNetworkTransport
import kotlinx.coroutines.*

/** 每个 Renderer 独占；原生 receiver 将 call/dispose 串行投递到 Main。 */
class DynamicNetworkHandler(
    private val transport: DynamicNetworkTransport,
    private val capabilities: DynamicCapabilityTransport,
) {
    // 保留 DynamicNetworkHandler { ... } 的旧 trailing lambda 源码调用。
    constructor(transport: DynamicNetworkTransport) : this(
        transport, DynamicCapabilityTransport { DynamicNetworkResponse("unavailable") },
    )
    private class Pending(val task: Boolean, val callback: (String) -> Unit) { lateinit var job: Job }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pending = mutableMapOf<String, Pending>()
    private var disposed = false

    fun call(method: String, params: String, callback: (String) -> Unit) {
        if (disposed) { deliver(callback, DynamicNetworkResponse("unavailable")); return }
        if (method == "dispose") { deliver(callback, disposeInternal()); return }
        val input = if (params.encodeToByteArray().size <= MAX_PROTOCOL_BYTES) {
            runCatching { networkJson.decodeFromString(NetworkCall.serializer(), params) }.getOrNull()
        } else null
        if (input == null || !Regex("[A-Za-z0-9_-]{1,128}").matches(input.requestId)) {
            deliver(callback, DynamicNetworkResponse("invalid_request")); return
        }
        val id = input.requestId
        if (method == "cancel") {
            val settled = pending[id]?.let { cancelPending(id, it) } ?: true
            deliver(callback, DynamicNetworkResponse(if (settled) "ok" else "failed"))
            return
        }
        val request = input.request
        val capabilityRequest = input.capabilityRequest
        val valid = when (method) {
            "request" -> request != null && capabilityRequest == null &&
                request.method in setOf("GET", "POST") && request.path.isNotBlank() &&
                (request.bodyJson?.let { body -> runCatching { networkJson.parseToJsonElement(body) }.isSuccess } != false)
            "invoke", "task" -> request == null && capabilityRequest != null && validCapabilityRequest(capabilityRequest)
            else -> false
        }
        if (!valid || pending.containsKey(id)) { deliver(callback, DynamicNetworkResponse("invalid_request")); return }
        val task = method == "task"
        val limit = if (task) MAX_PENDING_TASKS else MAX_PENDING_REQUESTS
        if (pending.values.count { it.task == task } >= limit) {
            deliver(callback, DynamicNetworkResponse("unavailable")); return
        }
        val entry = Pending(task, callback)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val response = try {
                if (task) capabilities.execute(checkNotNull(capabilityRequest))
                else withTimeoutOrNull(30_000) {
                    if (method == "invoke") capabilities.execute(checkNotNull(capabilityRequest))
                    else transport.execute(checkNotNull(request))
                } ?: DynamicNetworkResponse("failed")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                DynamicNetworkResponse("failed")
            }
            if (!disposed && isActive) complete(id, entry, response)
        }
        entry.job = job
        pending[id] = entry
        // sender 已撤销业务回调，原生仍须结算一次才能释放 OHOS SDK 的 callback context。
        job.invokeOnCompletion { cause -> if (cause != null) complete(id, entry, DynamicNetworkResponse("unavailable")) }
        job.start()
    }

    private fun complete(id: String, entry: Pending, response: DynamicNetworkResponse) {
        if (pending[id] !== entry) return
        pending.remove(id)
        deliver(entry.callback, response)
    }

    // SDK 可能已接受回执后才抛错；只尝试一次，不让异常越过 K/N 或 Job completion 边界。
    private fun deliver(callback: (String) -> Unit, response: DynamicNetworkResponse): Boolean =
        runCatching { callback(boundedResponse(response)) }.isSuccess

    private fun cancelPending(id: String, entry: Pending): Boolean {
        if (pending[id] !== entry) return true
        // 先摘除，避免 cancel 同步触发 invokeOnCompletion 后再次交付。
        pending.remove(id)
        val cancelled = runCatching { entry.job.cancel() }.isSuccess
        val delivered = deliver(entry.callback, DynamicNetworkResponse("unavailable"))
        return cancelled && delivered
    }

    private fun disposeInternal(): DynamicNetworkResponse {
        if (disposed) return DynamicNetworkResponse("ok")
        disposed = true
        var failed = false
        try {
            pending.toMap().forEach { (id, entry) ->
                if (!cancelPending(id, entry)) failed = true
            }
        } finally {
            if (runCatching { scope.cancel() }.isFailure) failed = true
            if (runCatching { capabilities.close() }.isFailure) failed = true
        }
        return DynamicNetworkResponse(if (failed) "failed" else "ok")
    }

    // Swift/Native teardown 没有结果通道；wire dispose 则明确返回清理失败。
    fun dispose() { disposeInternal() }
}
