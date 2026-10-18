package io.github.gycrosskit.dynamicpage.kuikly

import com.tencent.kuikly.core.global.GlobalFunctions
import com.tencent.kuikly.core.manager.BridgeManager
import com.tencent.kuikly.core.manager.KotlinMethod
import com.tencent.kuikly.core.manager.NativeMethod
import com.tencent.kuikly.core.nvi.NativeBridge
import io.github.gycrosskit.dynamicpage.*
import kotlin.test.*

/** 使用真实 Module/GlobalFunctions；只替换 Renderer native 调用和计时到期信号。 */
class DynamicNetworkModuleTest {
    private class Renderer(val pageId: String) {
        data class Call(val method: String, val params: String, val callback: String?)
        val calls = mutableListOf<Call>()
        val timers = mutableListOf<String>()
        val module = DynamicNetworkModule().also { it.pagerId = pageId }
        init {
            BridgeManager.init(false)
            NativeBridge.isContextThread = true
            BridgeManager.registerNativeBridge(pageId, NativeBridge().also { bridge ->
                bridge.delegate = object : NativeBridge.NativeBridgeDelegate {
                    override fun callNative(methodId: Int, arg0: Any?, arg1: Any?, arg2: Any?, arg3: Any?, arg4: Any?, arg5: Any?): Any? {
                        when (methodId) {
                            NativeMethod.CALL_MODULE_METHOD -> calls.add(Call(arg2 as String, arg3 as String, arg4 as? String))
                            NativeMethod.SET_TIMEOUT -> timers.add(arg2 as String)
                        }
                        return null
                    }
                }
            })
        }
        fun reply(call: Call, response: DynamicNetworkResponse = DynamicNetworkResponse("ok", 200, "{}")) {
            GlobalFunctions.invokeFunction(pageId, checkNotNull(call.callback),
                networkJson.encodeToString(DynamicNetworkResponse.serializer(), response))
        }
        fun timeout(timer: String) = GlobalFunctions.invokeFunction(pageId, timer, null)
        fun close() {
            module.dispose()
            GlobalFunctions.destroyGlobalFunction(pageId)
            BridgeManager.callKotlinMethod(KotlinMethod.DESTROY_INSTANCE, pageId, null, null, null, null, null)
            NativeBridge.isContextThread = false
        }
    }
    private val request = DynamicNetworkRequest("POST", "/page", mapOf("kind" to "news"), "{}")

    @Test fun sendsRequestAndSettlesOnlyOnce() {
        val renderer = Renderer("dynamic-success")
        try {
            val results = mutableListOf<DynamicNetworkResponse>()
            renderer.module.request(request, results::add)
            val call = renderer.calls.single()
            assertEquals("request", call.method)
            assertEquals(request, networkJson.decodeFromString(NetworkCall.serializer(), call.params).request)
            renderer.reply(call)
            renderer.reply(call)
            renderer.timeout(renderer.timers.single())
            assertEquals(listOf(DynamicNetworkResponse("ok", 200, "{}")), results)
            assertEquals(1, renderer.calls.size)
        } finally { renderer.close() }
    }

    @Test fun cancellationCannotCompleteOrCancelAnotherRequest() {
        val renderer = Renderer("dynamic-cancel")
        try {
            val cancelled = mutableListOf<DynamicNetworkResponse>()
            val remaining = mutableListOf<DynamicNetworkResponse>()
            val cancel = renderer.module.request(request, cancelled::add)
            renderer.module.request(request, remaining::add)
            val calls = renderer.calls.toList()
            cancel(); cancel()
            val cancellation = renderer.calls.last()
            assertEquals("cancel", cancellation.method)
            assertNotNull(cancellation.callback, "控制调用也需一次性回执以释放 OHOS 原生 context")
            renderer.reply(cancellation)
            assertEquals(networkJson.decodeFromString(NetworkCall.serializer(), calls[0].params).requestId,
                networkJson.decodeFromString(NetworkCall.serializer(), cancellation.params).requestId)
            renderer.reply(calls[0])
            renderer.timeout(renderer.timers[0])
            renderer.reply(calls[1])
            assertTrue(cancelled.isEmpty())
            assertEquals(1, remaining.size)
            assertEquals(3, renderer.calls.size)
            repeat(8) { renderer.module.request(request, remaining::add) }
            val atCapacity = renderer.calls.size
            renderer.module.request(request, remaining::add)
            assertEquals(atCapacity, renderer.calls.size)
            assertEquals("unavailable", remaining.last().status)
        } finally { renderer.close() }
    }

    @Test fun timeoutAndDisposeDropLateRepliesAndRejectNewRequests() {
        val renderer = Renderer("dynamic-timeout")
        try {
            val timedOut = mutableListOf<DynamicNetworkResponse>()
            val disposed = mutableListOf<DynamicNetworkResponse>()
            renderer.module.request(request, timedOut::add)
            val oldCall = renderer.calls.last()
            renderer.timeout(renderer.timers.last())
            renderer.reply(oldCall)
            assertEquals(listOf(DynamicNetworkResponse("failed")), timedOut)
            renderer.module.request(request, disposed::add)
            val pendingCall = renderer.calls.last()
            val pendingTimer = renderer.timers.last()
            renderer.module.dispose()
            renderer.calls.filter { it.method == "cancel" || it.method == "dispose" }.forEach {
                assertNotNull(it.callback)
                renderer.reply(it)
            }
            renderer.reply(pendingCall)
            renderer.timeout(pendingTimer)
            assertTrue(disposed.isEmpty())
            val callCount = renderer.calls.size
            renderer.module.request(request, disposed::add)
            assertEquals(listOf(DynamicNetworkResponse("unavailable")), disposed)
            assertEquals(callCount, renderer.calls.size)
        } finally { renderer.close() }
    }
}
