package io.github.gycrosskit.dynamicpage

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DynamicPageTest {
    private val request = DynamicPageRequest("demo", "Page")
    private fun runtime(action: suspend () -> DynamicPageOpenResult = { DynamicPageOpenResult.OPENED }) =
        object : DynamicPageRuntime {
            override val provider = "test"
            override suspend fun open(request: DynamicPageRequest) = action()
        }

    @Test fun disabledAndMissingRuntimeDoNotOpen() = runTest {
        assertEquals(DynamicPageOpenResult.UNAVAILABLE, DynamicPageConfiguration().open(request, runtime { error("must not open") }))
        assertEquals(DynamicPageOpenResult.UNAVAILABLE, DynamicPageConfiguration("test").open(request))
        assertEquals(DynamicPageOpenResult.UNAVAILABLE, DynamicPageConfiguration("unknown").open(request, runtime()))
    }

    @Test fun validatesBeforeDispatch() = runTest {
        val configuration = DynamicPageConfiguration("test")
        val runtime = runtime()
        assertEquals(DynamicPageOpenResult.OPENED, configuration.open(request, runtime))
        for (invalid in listOf(request.copy(moduleName = "../module"), request.copy(pageName = ""),
            request.copy(paramsJson = "[]"), request.copy(paramsJson = "{broken"),
            request.copy(paramsJson = "{\"v\":\"" + "中".repeat(22000) + "\"}"))) {
            assertEquals(DynamicPageOpenResult.INVALID_REQUEST, configuration.open(invalid, runtime))
        }
    }

    @Test fun loaderPreservesDisabledValidationAndCancellationBoundaries() = runTest {
        var calls = 0
        val prepared = object : PreparedDynamicPage {
            override fun open() = error("preparation must not mount")
            override fun close() = Unit
        }
        val loader = object : DynamicPageLoader {
            override val provider = "test"
            override suspend fun prepare(request: DynamicPageRequest): PreparedDynamicPage { calls++; return prepared }
        }
        assertNull(DynamicPageConfiguration().prepare(request, loader))
        assertNull(DynamicPageConfiguration("other").prepare(request, loader))
        assertNull(DynamicPageConfiguration("test").prepare(request, null))
        assertNull(DynamicPageConfiguration("test").prepare(request.copy(paramsJson = "[]"), loader))
        assertEquals(0, calls)
        assertSame(prepared, DynamicPageConfiguration("test").prepare(request, loader))
        val failing = object : DynamicPageLoader {
            override val provider = "test"
            override suspend fun prepare(request: DynamicPageRequest): PreparedDynamicPage? = throw CancellationException()
        }
        assertFailsWith<CancellationException> { DynamicPageConfiguration("test").prepare(request, failing) }
    }

    @Test fun propagatesCancellation() = runTest {
        assertFailsWith<CancellationException> {
            DynamicPageConfiguration("test").open(request, runtime { throw CancellationException() })
        }
    }
}
