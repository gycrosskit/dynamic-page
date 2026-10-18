package io.github.gycrosskit.dynamicpage.kuikly

import com.tencent.kuikly.core.module.CallbackRef
import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.timer.setTimeout
import io.github.gycrosskit.dynamicpage.DynamicNetworkRequest
import io.github.gycrosskit.dynamicpage.DynamicNetworkResponse

/** 在当前页面 Context 调用；页面销毁前 dispose。发送侧不使用 kotlinx.coroutines。 */
class DynamicNetworkModule : Module() {
    private class Pending(val deliver: (DynamicNetworkResponse) -> Unit) {
        var callback: CallbackRef? = null
        var timeout: String? = null
    }
    private val pending = mutableMapOf<String, Pending>()
    private var nextId = 0L
    private var disposed = false
    override fun moduleName(): String = NAME

    fun request(request: DynamicNetworkRequest, callback: (DynamicNetworkResponse) -> Unit): () -> Unit {
        if (disposed) {
            callback(DynamicNetworkResponse("unavailable")); return {}
        }
        if (pending.size >= MAX_PENDING_REQUESTS) {
            callback(DynamicNetworkResponse("unavailable")); return {}
        }
        val id = (++nextId).toString()
        val params = networkJson.encodeToString(NetworkCall.serializer(), NetworkCall(id, request))
        if (params.encodeToByteArray().size > MAX_PROTOCOL_BYTES) {
            callback(DynamicNetworkResponse("invalid_request")); return {}
        }
        val entry = Pending(callback)
        pending[id] = entry
        entry.timeout = setTimeout(pagerId, 31_000) {
            if (pending[id] === entry) {
                remove(id, cancelNative = true)
                entry.deliver(DynamicNetworkResponse("failed"))
            }
        }
        entry.callback = toNative(false, "request", params, { response ->
            if (!disposed && pending[id] === entry) {
                remove(id, cancelNative = false)
                val encoded = response?.toString().orEmpty()
                val result = if (encoded.encodeToByteArray().size <= MAX_PROTOCOL_BYTES) {
                    runCatching { networkJson.decodeFromString(DynamicNetworkResponse.serializer(), encoded) }.getOrNull()
                } else null
                entry.deliver(result?.takeIf { it.status in responseStatuses } ?: DynamicNetworkResponse("failed"))
            }
        }, false).callbackRef
        if (pending[id] !== entry) entry.callback?.let(::removeCallback)
        return { if (pending[id] === entry) remove(id, cancelNative = true) }
    }

    private fun remove(id: String, cancelNative: Boolean) {
        val entry = pending.remove(id) ?: return
        entry.callback?.let(::removeCallback)
        // Kuikly 2.29.1 clearTimeout 也只删除 GlobalFunction；这里显式使用本 Module 的 pagerId。
        entry.timeout?.let(::removeCallback)
        if (cancelNative) control("cancel", networkJson.encodeToString(NetworkCall.serializer(), NetworkCall(id)))
    }

    private fun control(method: String, params: String) {
        // 非空 callbackId 让 OHOS SDK 可回收控制调用 context；Kotlin 不消费该回执。
        toNative(false, method, params, {}, false).callbackRef?.let(::removeCallback)
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        pending.keys.toList().forEach { remove(it, cancelNative = true) }
        control("dispose", "{}")
    }

    companion object { const val NAME = "GycDynamicNetworkModule" }
}
