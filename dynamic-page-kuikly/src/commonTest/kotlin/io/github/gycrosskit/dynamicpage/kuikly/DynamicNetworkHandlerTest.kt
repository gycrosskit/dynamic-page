package io.github.gycrosskit.dynamicpage.kuikly

import io.github.gycrosskit.dynamicpage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DynamicNetworkHandlerTest {
    private fun params(id: String) = networkJson.encodeToString(NetworkCall.serializer(), NetworkCall(id, DynamicNetworkRequest("GET", "/test")))

    @Test fun disposedOwnerCannotCompleteIntoAnotherRenderer() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val oldResponse = CompletableDeferred<DynamicNetworkResponse>()
            val old = DynamicNetworkHandler { oldResponse.await() }
            val fresh = DynamicNetworkHandler { DynamicNetworkResponse("ok", 200, "{}") }
            val oldResults = mutableListOf<String>()
            val newResults = mutableListOf<String>()
            old.call("request", params("1"), oldResults::add)
            runCurrent()
            old.dispose()
            fresh.call("request", params("1"), newResults::add)
            oldResponse.complete(DynamicNetworkResponse("ok", 200, "{}"))
            runCurrent()
            assertEquals("unavailable", networkJson.decodeFromString<DynamicNetworkResponse>(oldResults.single()).status)
            assertEquals(1, newResults.size)
            fresh.dispose()
        } finally { Dispatchers.resetMain() }
    }

    @Test fun cancelDuplicateLimitTimeoutAndErrors() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val handler = DynamicNetworkHandler { awaitCancellation() }
            val results = mutableListOf<String>()
            handler.call("request", params("1"), results::add)
            handler.call("request", params("1"), results::add)
            assertTrue(results.single().contains("invalid_request"))
            handler.call("cancel", "{\"requestId\":\"1\"}", results::add)
            for (id in 2..9) handler.call("request", params(id.toString()), results::add)
            handler.call("request", params("10"), results::add)
            assertTrue(results.last().contains("unavailable"))
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(12, results.size)
            handler.dispose()
        } finally { Dispatchers.resetMain() }
    }

    @Test fun cancelAndDisposeSettleNativeContextsWithoutRetainingOldRequests() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val outstandingContexts = mutableSetOf<String>()
            fun context(id: String): (String) -> Unit {
                assertTrue(outstandingContexts.add(id))
                return { assertTrue(outstandingContexts.remove(id), "原生 context 只能结算一次") }
            }
            val handler = DynamicNetworkHandler { awaitCancellation() }
            repeat(32) { i ->
                val id = i.toString()
                handler.call("request", params(id), context("request-$id"))
                runCurrent()
                handler.call("cancel", "{\"requestId\":\"$id\"}", context("cancel-$id"))
                runCurrent()
                assertTrue(outstandingContexts.isEmpty(), "取消必须释放原 request 和控制调用两份 context")
            }
            handler.call("request", params("pending"), context("pending"))
            runCurrent()
            handler.call("dispose", "{}", context("dispose"))
            handler.call("request", params("closed"), context("closed"))
            assertTrue(outstandingContexts.isEmpty())
            val cancelled = DynamicNetworkHandler { throw CancellationException("会话已失效") }
            cancelled.call("request", params("session"), context("session"))
            runCurrent()
            assertTrue(outstandingContexts.isEmpty())
            cancelled.dispose()
        } finally { Dispatchers.resetMain() }
    }

    private fun capabilityParams(id: String, request: DynamicCapabilityRequest) =
        networkJson.encodeToString(NetworkCall.serializer(), NetworkCall(id, capabilityRequest = request))

    @Test fun capabilityProtocolDefaultsValidationAndOldWireCompatibility() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val request = DynamicCapabilityRequest("app.context", "get", "{\"key\":\"theme\"}")
            val results = mutableListOf<String>()
            val legacy = DynamicNetworkHandler { DynamicNetworkResponse("ok") }
            legacy.call("invoke", capabilityParams("default", request), results::add)
            runCurrent()
            assertEquals("unavailable", networkJson.decodeFromString<DynamicNetworkResponse>(results.single()).status)
            legacy.call("task", capabilityParams("default-task", request), results::add)
            runCurrent()
            assertEquals("unavailable", networkJson.decodeFromString<DynamicNetworkResponse>(results.last()).status)
            legacy.dispose()
            val received = mutableListOf<DynamicCapabilityRequest>()
            val handler = DynamicNetworkHandler(
                DynamicNetworkTransport { error("invoke 不能进入旧网络入口") },
                DynamicCapabilityTransport { received += it; DynamicCapabilityResponse("ok", bodyJson = "{\"value\":\"dark\"}") },
            )
            handler.call("invoke", capabilityParams("valid", request), results::add)
            runCurrent()
            assertEquals(listOf(request), received)
            assertEquals("ok", networkJson.decodeFromString<DynamicNetworkResponse>(results.last()).status)
            val invalid = listOf(
                request.copy(capability = "a".repeat(129)), request.copy(operation = "../get"),
                request.copy(capability = ""), request.copy(paramsJson = "[]"),
                request.copy(paramsJson = "{\"key\":\"" + "a".repeat(MAX_PROTOCOL_BYTES) + "\"}"),
            )
            invalid.forEachIndexed { index, value ->
                for (method in listOf("invoke", "task")) {
                    handler.call(method, capabilityParams("bad-$index", value), results::add)
                    assertEquals("invalid_request", networkJson.decodeFromString<DynamicNetworkResponse>(results.last()).status)
                }
            }
            handler.call("invoke", params("wrong-kind"), results::add)
            assertEquals("invalid_request", networkJson.decodeFromString<DynamicNetworkResponse>(results.last()).status)
            handler.call("task", params("wrong-task-kind"), results::add)
            assertEquals("invalid_request", networkJson.decodeFromString<DynamicNetworkResponse>(results.last()).status)
            handler.call("request", capabilityParams("wrong-method", request), results::add)
            assertEquals("invalid_request", networkJson.decodeFromString<DynamicNetworkResponse>(results.last()).status)
            val mixed = NetworkCall("mixed", DynamicNetworkRequest("GET", "/test"), request)
            handler.call("invoke", networkJson.encodeToString(NetworkCall.serializer(), mixed), results::add)
            assertEquals("invalid_request", networkJson.decodeFromString<DynamicNetworkResponse>(results.last()).status)
            handler.call("task", networkJson.encodeToString(NetworkCall.serializer(), mixed), results::add)
            assertEquals("invalid_request", networkJson.decodeFromString<DynamicNetworkResponse>(results.last()).status)
            assertEquals(1, received.size)
            assertFalse(params("legacy").contains("capabilityRequest"), "旧接收端不认识新字段，旧 request wire 必须兼容")
            handler.dispose()
        } finally { Dispatchers.resetMain() }
    }

    @Test fun capabilitiesShareNetworkQuotaAndSettleCancelledContextsOnce() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val handler = DynamicNetworkHandler(
                DynamicNetworkTransport { awaitCancellation() }, DynamicCapabilityTransport { awaitCancellation() },
            )
            val outstanding = mutableSetOf<String>()
            fun context(id: String): (String) -> Unit {
                assertTrue(outstanding.add(id))
                return { assertTrue(outstanding.remove(id), "context 必须恰好结算一次") }
            }
            val request = DynamicCapabilityRequest("navigation", "open", "{}")
            repeat(8) { index ->
                if (index % 2 == 0) handler.call("invoke", capabilityParams(index.toString(), request), context("call-$index"))
                else handler.call("request", params(index.toString()), context("call-$index"))
            }
            val overflow = mutableListOf<String>()
            handler.call("invoke", capabilityParams("overflow", request), overflow::add)
            assertEquals("unavailable", networkJson.decodeFromString<DynamicNetworkResponse>(overflow.single()).status)
            runCurrent()
            handler.call("cancel", "{\"requestId\":\"0\"}", context("cancel"))
            assertEquals(7, outstanding.size)
            handler.call("invoke", capabilityParams("replacement", request), context("replacement"))
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()
            assertTrue(outstanding.isEmpty(), "网络与能力均须在同一 30 秒超时后释放 context")
            handler.call("invoke", capabilityParams("dispose", request), context("dispose"))
            runCurrent()
            handler.dispose()
            runCurrent()
            assertTrue(outstanding.isEmpty())
        } finally { Dispatchers.resetMain() }
    }

    @Test fun tasksHaveIndependentQuotaSurviveShortTimeoutAndSettleContextsOnce() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val taskResponses = (0..MAX_PENDING_TASKS).associate { "wait$it" to CompletableDeferred<DynamicNetworkResponse>() }
            val cancelled = mutableSetOf<String>()
            val handler = DynamicNetworkHandler(
                DynamicNetworkTransport { awaitCancellation() },
                DynamicCapabilityTransport { request ->
                    try { taskResponses.getValue(request.operation).await() }
                    finally { if (!currentCoroutineContext().isActive) cancelled += request.operation }
                },
            )
            val outstanding = mutableSetOf<String>()
            val results = mutableMapOf<String, DynamicNetworkResponse>()
            fun context(id: String): (String) -> Unit {
                assertTrue(outstanding.add(id))
                return { encoded ->
                    assertTrue(outstanding.remove(id), "context 只能结算一次")
                    assertNull(results.put(id, networkJson.decodeFromString<DynamicNetworkResponse>(encoded)))
                }
            }
            fun task(index: Int) = capabilityParams("task$index", DynamicCapabilityRequest("native", "wait$index"))
            repeat(MAX_PENDING_REQUESTS) { handler.call("request", params("short$it"), context("short$it")) }
            repeat(MAX_PENDING_TASKS) { handler.call("task", task(it), context("task$it")) }
            handler.call("task", task(MAX_PENDING_TASKS), context("task-overflow"))
            handler.call("invoke", capabilityParams("short-overflow", DynamicCapabilityRequest("native", "wait0")), context("short-overflow"))
            handler.call("request", params("task0"), context("duplicate"))
            assertEquals("unavailable", results.getValue("task-overflow").status)
            assertEquals("unavailable", results.getValue("short-overflow").status)
            assertEquals("invalid_request", results.getValue("duplicate").status)
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()
            repeat(MAX_PENDING_REQUESTS) { assertEquals("failed", results.getValue("short$it").status) }
            assertEquals(MAX_PENDING_TASKS, outstanding.size, "task 不应受 30 秒短请求超时影响")
            taskResponses.getValue("wait0").complete(DynamicNetworkResponse("ok"))
            runCurrent()
            assertEquals("ok", results.getValue("task0").status)
            handler.call("cancel", "{\"requestId\":\"task1\"}", context("cancel"))
            runCurrent()
            assertEquals("unavailable", results.getValue("task1").status)
            assertTrue("wait1" in cancelled)
            handler.call("task", task(MAX_PENDING_TASKS), context("replacement"))
            handler.call("request", params("fresh-short"), context("fresh-short"))
            runCurrent()
            assertFalse(results.containsKey("replacement"))
            assertFalse(results.containsKey("fresh-short"))
            handler.call("dispose", "{}", context("dispose"))
            runCurrent()
            assertTrue(outstanding.isEmpty(), "销毁须同时结算长任务、短请求及控制调用")
            assertTrue(cancelled.containsAll((1..MAX_PENDING_TASKS).map { "wait$it" }))
            taskResponses.values.forEach { it.complete(DynamicNetworkResponse("ok")) }
            runCurrent()
            handler.call("task", task(0), context("closed"))
            assertEquals("unavailable", results.getValue("closed").status)
            assertTrue(outstanding.isEmpty())
        } finally { Dispatchers.resetMain() }
    }

    @Test fun disposeCallbackAndCapabilityCloseFailuresStillSettleOtherContextsAndFailedControlAck() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var cancelled = 0
            var closed = 0
            var throwingDeliveries = 0
            val remaining = mutableListOf<DynamicNetworkResponse>()
            val controls = mutableListOf<DynamicNetworkResponse>()
            val handler = DynamicNetworkHandler(
                DynamicNetworkTransport { try { awaitCancellation() } finally { cancelled++ } },
                object : DynamicCapabilityTransport {
                    override suspend fun execute(request: DynamicCapabilityRequest): DynamicCapabilityResponse =
                        try { awaitCancellation() } finally { cancelled++ }
                    override fun close() { closed++; error("native cleanup failed") }
                },
            )
            val capability = DynamicCapabilityRequest("native", "wait")
            handler.call("task", capabilityParams("throwing", capability)) { throwingDeliveries++; error("SDK callback failed") }
            handler.call("task", capabilityParams("remaining", capability)) { remaining += networkJson.decodeFromString<DynamicNetworkResponse>(it) }
            handler.call("request", params("short")) { remaining += networkJson.decodeFromString<DynamicNetworkResponse>(it) }
            runCurrent()
            handler.call("dispose", "{}") { controls += networkJson.decodeFromString<DynamicNetworkResponse>(it) }
            runCurrent()
            assertEquals(1, throwingDeliveries)
            assertEquals(2, remaining.size)
            assertTrue(remaining.all { it.status == "unavailable" })
            assertEquals("failed", controls.single().status)
            assertEquals(3, cancelled)
            assertEquals(1, closed)
            handler.dispose()
            handler.dispose()
            assertEquals(1, closed)
            assertEquals(1, throwingDeliveries)
            handler.call("task", capabilityParams("closed", capability)) { controls += networkJson.decodeFromString<DynamicNetworkResponse>(it) }
            assertEquals("unavailable", controls.last().status)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun cancelWithThrowingNativeCallbackStillReturnsFailedAckAndKeepsOtherRequestsAlive() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val result = CompletableDeferred<DynamicCapabilityResponse>()
            val cancelled = mutableSetOf<String>()
            val handler = DynamicNetworkHandler(DynamicNetworkTransport { awaitCancellation() },
                DynamicCapabilityTransport { request ->
                    try { result.await() } finally { if (!currentCoroutineContext().isActive) cancelled += request.operation }
                })
            var throwingDeliveries = 0
            val controls = mutableListOf<DynamicNetworkResponse>()
            val results = mutableListOf<DynamicNetworkResponse>()
            handler.call("task", capabilityParams("cancel", DynamicCapabilityRequest("native", "first"))) {
                throwingDeliveries++; error("SDK callback failed")
            }
            handler.call("task", capabilityParams("keep", DynamicCapabilityRequest("native", "second"))) {
                results += networkJson.decodeFromString<DynamicNetworkResponse>(it)
            }
            runCurrent()
            handler.call("cancel", "{\"requestId\":\"cancel\"}") { controls += networkJson.decodeFromString<DynamicNetworkResponse>(it) }
            runCurrent()
            assertEquals("failed", controls.single().status)
            assertEquals(setOf("first"), cancelled)
            assertEquals(1, throwingDeliveries)
            assertTrue(results.isEmpty())
            result.complete(DynamicCapabilityResponse("ok"))
            runCurrent()
            assertEquals("ok", results.single().status)
            assertEquals(1, throwingDeliveries)
            handler.dispose()
        } finally { Dispatchers.resetMain() }
    }

    @Test fun cancelledJobCompletionAndImmediateControlCallbacksCannotThrowAcrossNativeBoundary() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var calls = 0
            var closed = 0
            val handler = DynamicNetworkHandler(DynamicNetworkTransport { awaitCancellation() },
                object : DynamicCapabilityTransport {
                    override suspend fun execute(request: DynamicCapabilityRequest): DynamicCapabilityResponse =
                        throw CancellationException("native operation cancelled")
                    override fun close() { closed++; error("native cleanup failed") }
                })
            handler.call("task", capabilityParams("cancelled", DynamicCapabilityRequest("native", "wait"))) {
                calls++; error("SDK callback failed")
            }
            runCurrent() // 原实现会由 invokeOnCompletion 抛 CompletionHandlerException。
            assertEquals(1, calls)
            handler.call("cancel", "{\"requestId\":\"unknown\"}") { error("control callback failed") }
            handler.dispose() // Kotlin/Native 的 Swift void teardown 不能收到未声明异常。
            assertEquals(1, closed)
            handler.call("dispose", "{}") { error("closed callback failed") }
            handler.dispose()
            assertEquals(1, calls)
            assertEquals(1, closed)
        } finally { Dispatchers.resetMain() }
    }

}
