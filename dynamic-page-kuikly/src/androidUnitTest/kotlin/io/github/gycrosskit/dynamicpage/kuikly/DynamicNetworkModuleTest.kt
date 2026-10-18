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
        var onCall: ((Call) -> Unit)? = null
        val module = DynamicNetworkModule().also { it.pagerId = pageId }
        init {
            BridgeManager.init(false)
            NativeBridge.isContextThread = true
            BridgeManager.registerNativeBridge(pageId, NativeBridge().also { bridge ->
                bridge.delegate = object : NativeBridge.NativeBridgeDelegate {
                    override fun callNative(methodId: Int, arg0: Any?, arg1: Any?, arg2: Any?, arg3: Any?, arg4: Any?, arg5: Any?): Any? {
                        when (methodId) {
                            NativeMethod.CALL_MODULE_METHOD -> {
                                val call = Call(arg2 as String, arg3 as String, arg4 as? String)
                                calls.add(call)
                                onCall?.invoke(call)
                            }
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
    @Test fun capabilityInvokeSharesCancellationQuotaAndRejectsInvalidInput() {
        val renderer = Renderer("dynamic-capability")
        try {
            val results = mutableListOf<DynamicCapabilityResponse>()
            val capability = DynamicCapabilityRequest("storage", "get", "{\"key\":\"theme\"}")
            val cancel = renderer.module.invoke(capability, results::add)
            val call = renderer.calls.single()
            assertEquals("invoke", call.method)
            assertEquals(capability, networkJson.decodeFromString(NetworkCall.serializer(), call.params).capabilityRequest)
            cancel()
            renderer.reply(call)
            renderer.timeout(renderer.timers.single())
            assertTrue(results.isEmpty())
            renderer.module.invoke(capability.copy(paramsJson = "[]"), results::add)
            assertEquals("invalid_request", results.single().status)
            repeat(8) { index ->
                if (index % 2 == 0) renderer.module.invoke(capability, results::add)
                else renderer.module.request(request, results::add)
            }
            val atCapacity = renderer.calls.size
            renderer.module.invoke(capability, results::add)
            assertEquals(atCapacity, renderer.calls.size)
            assertEquals("unavailable", results.last().status)
            renderer.module.dispose()
            renderer.module.invoke(capability, results::add)
            assertEquals("unavailable", results.last().status)
        } finally { renderer.close() }
    }

    @Test fun tasksHaveIndependentQuotaNoBridgeTimerAndDropCancelledOrDisposedReplies() {
        val renderer = Renderer("dynamic-task")
        try {
            val capability = DynamicCapabilityRequest("native", "wait")
            val shortResults = mutableListOf<DynamicNetworkResponse>()
            val taskResults = mutableListOf<DynamicNetworkResponse>()
            repeat(MAX_PENDING_REQUESTS) { renderer.module.request(request, shortResults::add) }
            val cancel = renderer.module.invokeTask(capability, taskResults::add)
            repeat(MAX_PENDING_TASKS - 1) { renderer.module.invokeTask(capability, taskResults::add) }
            val tasks = renderer.calls.filter { it.method == "task" }
            assertEquals(MAX_PENDING_TASKS, tasks.size)
            assertEquals(capability, networkJson.decodeFromString(NetworkCall.serializer(), tasks.first().params).capabilityRequest)
            assertEquals(MAX_PENDING_REQUESTS, renderer.timers.size, "task 不创建 31 秒短请求 timer")
            val atCapacity = renderer.calls.size
            renderer.module.invokeTask(capability, taskResults::add)
            renderer.module.invoke(capability, shortResults::add)
            assertEquals(atCapacity, renderer.calls.size)
            assertEquals("unavailable", taskResults.single().status)
            assertEquals("unavailable", shortResults.single().status)
            taskResults.clear()
            renderer.timers.toList().forEach(renderer::timeout)
            assertTrue(taskResults.isEmpty())
            assertEquals(MAX_PENDING_REQUESTS, shortResults.count { it.status == "failed" })
            cancel(); cancel()
            val cancellation = renderer.calls.last()
            assertEquals("cancel", cancellation.method)
            assertEquals(networkJson.decodeFromString(NetworkCall.serializer(), tasks.first().params).requestId,
                networkJson.decodeFromString(NetworkCall.serializer(), cancellation.params).requestId)
            assertNotNull(cancellation.callback)
            renderer.reply(cancellation)
            renderer.reply(tasks.first())
            assertTrue(taskResults.isEmpty())
            renderer.module.invokeTask(capability, taskResults::add)
            val replacement = renderer.calls.last()
            renderer.reply(tasks[1]); renderer.reply(tasks[1])
            assertEquals(1, taskResults.size, "task 仍只交付一次最终回执")
            renderer.module.dispose()
            renderer.calls.filter { it.method == "cancel" || it.method == "dispose" }.forEach {
                assertNotNull(it.callback)
                renderer.reply(it)
            }
            tasks.forEach { renderer.reply(it) }
            renderer.reply(replacement)
            assertEquals(1, taskResults.size)
            renderer.module.invokeTask(capability, taskResults::add)
            assertEquals("unavailable", taskResults.last().status)
        } finally { renderer.close() }
    }

    @Test fun taskValidationAndSendFailureReleaseCapacityWithoutRedelivery() {
        val renderer = Renderer("dynamic-task-failure")
        try {
            val capability = DynamicCapabilityRequest("native", "wait")
            val results = mutableListOf<DynamicCapabilityResponse>()
            renderer.module.invokeTask(capability.copy(paramsJson = "[]"), results::add)
            assertEquals("invalid_request", results.single().status)
            assertTrue(renderer.calls.isEmpty())
            renderer.onCall = { if (it.method == "task") error("native bridge unavailable") }
            repeat(MAX_PENDING_TASKS + 2) { renderer.module.invokeTask(capability, results::add) }
            assertEquals(MAX_PENDING_TASKS + 2, results.count { it.status == "failed" })
            assertTrue(renderer.timers.isEmpty())
            renderer.calls.toList().forEach { renderer.reply(it) }
            assertEquals(MAX_PENDING_TASKS + 3, results.size)
            renderer.onCall = { if (it.method == "task") renderer.reply(it) }
            var deliveries = 0
            assertFailsWith<IllegalStateException> {
                renderer.module.invokeTask(capability) { deliveries++; error("business callback failed") }
            }
            assertEquals(1, deliveries)
        } finally { renderer.close() }
    }

    @Test fun nativeSendFailureReleasesCapacityAndDoesNotRedeliverThrowingBusinessCallback() {
        val renderer = Renderer("dynamic-send-failure")
        try {
            val results = mutableListOf<DynamicNetworkResponse>()
            renderer.onCall = { if (it.method == "request") error("native bridge unavailable") }
            repeat(MAX_PENDING_REQUESTS + 2) { renderer.module.request(request, results::add) }
            assertEquals(MAX_PENDING_REQUESTS + 2, results.size)
            assertTrue(results.all { it.status == "failed" }, "发送失败必须立即释放槽位，不能退化为 unavailable")
            renderer.calls.toList().forEach { renderer.reply(it) }
            renderer.timers.toList().forEach(renderer::timeout)
            assertEquals(MAX_PENDING_REQUESTS + 2, results.size, "清理后迟到回执和 timer 不能再次交付")

            renderer.onCall = null
            val before = renderer.calls.size
            repeat(MAX_PENDING_REQUESTS) { renderer.module.request(request, results::add) }
            assertEquals(before + MAX_PENDING_REQUESTS, renderer.calls.size)
            renderer.calls.drop(before).forEach { renderer.reply(it) }
            assertEquals(MAX_PENDING_REQUESTS, results.count { it.status == "ok" })

            renderer.onCall = { if (it.method == "request") renderer.reply(it) }
            var deliveries = 0
            assertFailsWith<IllegalStateException> {
                renderer.module.request(request) { deliveries++; error("business callback failed") }
            }
            assertEquals(1, deliveries, "同步业务 callback 抛出不能触发第二次 failed 回调")
            renderer.onCall = null
            renderer.module.request(request, results::add)
            renderer.reply(renderer.calls.last())
            assertEquals(MAX_PENDING_REQUESTS + 1, results.count { it.status == "ok" })

            val timedOut = mutableListOf<DynamicNetworkResponse>()
            renderer.module.request(request, timedOut::add)
            val timedCall = renderer.calls.last()
            renderer.onCall = { if (it.method == "cancel" || it.method == "dispose") error("control bridge closed") }
            renderer.timeout(renderer.timers.last())
            renderer.reply(timedCall)
            assertEquals(listOf(DynamicNetworkResponse("failed")), timedOut, "cancel 失败不能跳过超时回执")

            val disposed = mutableListOf<DynamicNetworkResponse>()
            val beforeDispose = renderer.calls.size
            repeat(MAX_PENDING_REQUESTS) { renderer.module.request(request, disposed::add) }
            val pendingCalls = renderer.calls.drop(beforeDispose).toList()
            val pendingTimers = renderer.timers.takeLast(MAX_PENDING_REQUESTS)
            renderer.module.dispose()
            val controls = renderer.calls.drop(beforeDispose).filter { it.method == "cancel" || it.method == "dispose" }
            assertEquals(MAX_PENDING_REQUESTS, controls.count { it.method == "cancel" }, "控制调用失败也须清完其他请求")
            assertEquals("dispose", controls.last().method)
            pendingCalls.forEach { renderer.reply(it) }
            pendingTimers.forEach(renderer::timeout)
            assertTrue(disposed.isEmpty(), "批量销毁后不能保留旧回调")
        } finally { renderer.close() }
    }

}
