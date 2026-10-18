package io.github.gycrosskit.dynamicpage.kuikly

import com.tencent.kuikly.core.global.GlobalFunctions
import com.tencent.kuikly.core.module.CallbackRef
import com.tencent.kuikly.core.module.Module
import com.tencent.kuikly.core.timer.setTimeout
import io.github.gycrosskit.dynamicpage.DynamicCapabilityRequest
import io.github.gycrosskit.dynamicpage.DynamicCapabilityResponse
import io.github.gycrosskit.dynamicpage.DynamicNetworkRequest
import io.github.gycrosskit.dynamicpage.DynamicNetworkResponse

/** 在当前页面 Context 调用；页面销毁前 dispose。发送侧不使用 kotlinx.coroutines。 */
class DynamicNetworkModule : Module() {
    private class Pending(val task: Boolean, val deliver: (DynamicNetworkResponse) -> Unit) {
        var callback: CallbackRef? = null
        var timeout: String? = null
    }
    private val pending = mutableMapOf<String, Pending>()
    private var nextId = 0L
    private var disposed = false
    override fun moduleName(): String = NAME

    fun request(request: DynamicNetworkRequest, callback: (DynamicNetworkResponse) -> Unit): () -> Unit =
        send("request", request, null, callback)

    fun invoke(request: DynamicCapabilityRequest, callback: (DynamicCapabilityResponse) -> Unit): () -> Unit =
        send("invoke", null, request, callback)

    /** 长交互不套用短请求超时；调用方须随页面取消，并由业务或原生能力限定等待期限。 */
    fun invokeTask(request: DynamicCapabilityRequest, callback: (DynamicCapabilityResponse) -> Unit): () -> Unit =
        send("task", null, request, callback)

    private fun send(
        method: String,
        request: DynamicNetworkRequest?,
        capabilityRequest: DynamicCapabilityRequest?,
        callback: (DynamicNetworkResponse) -> Unit,
    ): () -> Unit {
        if (disposed) {
            callback(DynamicNetworkResponse("unavailable")); return {}
        }
        if (capabilityRequest != null && !validCapabilityRequest(capabilityRequest)) {
            callback(DynamicNetworkResponse("invalid_request")); return {}
        }
        val task = method == "task"
        val limit = if (task) MAX_PENDING_TASKS else MAX_PENDING_REQUESTS
        if (pending.values.count { it.task == task } >= limit) {
            callback(DynamicNetworkResponse("unavailable")); return {}
        }
        val id = (++nextId).toString()
        val params = networkJson.encodeToString(NetworkCall.serializer(), NetworkCall(id, request, capabilityRequest))
        if (params.encodeToByteArray().size > MAX_PROTOCOL_BYTES) {
            callback(DynamicNetworkResponse("invalid_request")); return {}
        }
        val entry = Pending(task, callback)
        pending[id] = entry
        // Kuikly 2.29.1 先创建 GlobalFunction 再调用 native；同一 Context 预存 ref，升级 SDK 须复核顺序。
        try {
            if (!task) {
                entry.timeout = GlobalFunctions.peekNextRef().toString()
                entry.timeout = setTimeout(pagerId, 31_000) {
                    if (pending[id] === entry) {
                        remove(id, cancelNative = true)
                        entry.deliver(DynamicNetworkResponse("failed"))
                    }
                }
            }
            entry.callback = GlobalFunctions.peekNextRef().toString()
            entry.callback = toNative(false, method, params, { response ->
                if (!disposed && pending[id] === entry) {
                    remove(id, cancelNative = false)
                    val encoded = response?.toString().orEmpty()
                    val result = if (encoded.encodeToByteArray().size <= MAX_PROTOCOL_BYTES) {
                        runCatching { networkJson.decodeFromString(DynamicNetworkResponse.serializer(), encoded) }.getOrNull()
                    } else null
                    entry.deliver(result?.takeIf { it.status in responseStatuses } ?: DynamicNetworkResponse("failed"))
                }
            }, false).callbackRef
        } catch (error: Exception) {
            // 同步回执已移除 pending 时，异常可能来自业务 callback，不能再次交付 failed。
            if (pending[id] !== entry) throw error
            remove(id, cancelNative = false)
            entry.deliver(DynamicNetworkResponse("failed"))
            return {}
        }
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
        val callback = GlobalFunctions.peekNextRef().toString()
        try { toNative(false, method, params, {}, false) }
        catch (_: Exception) {
            // SDK 已关闭也须继续清理本地回调；原生 owner 最终由 onDestroy/onDestruct 回收。
        } finally { removeCallback(callback) }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        pending.keys.toList().forEach { remove(it, cancelNative = true) }
        control("dispose", "{}")
    }

    companion object { const val NAME = "GycDynamicNetworkModule" }
}
