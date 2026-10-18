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

}
