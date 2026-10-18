package io.github.gycrosskit.dynamicpage.kuikly

import io.github.gycrosskit.dynamicpage.DynamicNetworkResponse
import io.github.gycrosskit.dynamicpage.DynamicNetworkTransport
import kotlinx.coroutines.*

/** 每个 Renderer 独占；原生 receiver 将 call/dispose 串行投递到 Main。 */
class DynamicNetworkHandler(private val transport: DynamicNetworkTransport) {
    private class Pending(val callback: (String) -> Unit) { lateinit var job: Job }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pending = mutableMapOf<String, Pending>()
    private var disposed = false

    fun call(method: String, params: String, callback: (String) -> Unit) {
        if (disposed) { callback(boundedResponse(DynamicNetworkResponse("unavailable"))); return }
        if (method == "dispose") { dispose(); callback(boundedResponse(DynamicNetworkResponse("ok"))); return }
        val input = if (params.encodeToByteArray().size <= MAX_PROTOCOL_BYTES) {
            runCatching { networkJson.decodeFromString(NetworkCall.serializer(), params) }.getOrNull()
        } else null
        if (input == null || !Regex("[A-Za-z0-9_-]{1,128}").matches(input.requestId)) {
            callback(boundedResponse(DynamicNetworkResponse("invalid_request"))); return
        }
        val id = input.requestId
        if (method == "cancel") {
            pending[id]?.let { entry ->
                entry.job.cancel()
                complete(id, entry, DynamicNetworkResponse("unavailable"))
            }
            callback(boundedResponse(DynamicNetworkResponse("ok")))
            return
        }
        val request = input.request
        if (method != "request" || request == null ||
            request.method !in setOf("GET", "POST") || request.path.isBlank() ||
            (request.bodyJson?.let { body -> runCatching { networkJson.parseToJsonElement(body) }.isFailure } == true) ||
            pending.containsKey(id)
        ) { callback(boundedResponse(DynamicNetworkResponse("invalid_request"))); return }
        if (pending.size >= MAX_PENDING_REQUESTS) { callback(boundedResponse(DynamicNetworkResponse("unavailable"))); return }
        val entry = Pending(callback)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val response = try {
                withTimeoutOrNull(30_000) { transport.execute(request) } ?: DynamicNetworkResponse("failed")
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
        entry.callback(boundedResponse(response))
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        pending.toMap().forEach { (id, entry) ->
            entry.job.cancel()
            complete(id, entry, DynamicNetworkResponse("unavailable"))
        }
        scope.cancel()
    }
}
