@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package io.github.gycrosskit.dynamicpage.kuikly

import io.github.gycrosskit.dynamicpage.DynamicCapabilityTransport
import io.github.gycrosskit.dynamicpage.DynamicNetworkResponse
import io.github.gycrosskit.dynamicpage.DynamicNetworkTransport
import io.github.gycrosskit.dynamicpage.render.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlin.native.ref.createCleaner
import platform.posix.*

private var transportFactory: (() -> DynamicNetworkTransport)? = null
private var capabilityFactory: (() -> DynamicCapabilityTransport)? = null

/** 在宿主 initKuikly、创建 Renderer 前调用一次；Ktor 和凭据仍归宿主。 */
fun registerOhosDynamicNetworkModule(createTransport: () -> DynamicNetworkTransport) =
    registerOhosDynamicNetworkModule(createTransport) { DynamicCapabilityTransport { DynamicNetworkResponse("unavailable") } }

fun registerOhosDynamicNetworkModule(
    createTransport: () -> DynamicNetworkTransport,
    createCapabilities: () -> DynamicCapabilityTransport,
) {
    if (transportFactory != null) return
    transportFactory = createTransport
    capabilityFactory = createCapabilities
    KRRenderModuleRegisterV2(DynamicNetworkModule.NAME, staticCFunction(::construct),
        staticCFunction(::destruct), staticCFunction(::call), null)
}

private class OhosNetworkOwner {
    private val mutex = nativeHeap.alloc<pthread_mutex_t>().also { value ->
        memScoped {
            val attr = alloc<pthread_mutexattr_t>()
            check(pthread_mutexattr_init(attr.ptr) == 0)
            check(pthread_mutexattr_settype(attr.ptr, PTHREAD_MUTEX_RECURSIVE) == 0)
            check(pthread_mutex_init(value.ptr, attr.ptr) == 0)
            pthread_mutexattr_destroy(attr.ptr)
        }
    }
    // Owner 仍被在途 coroutine 持有时，不能随 StableRef 一起释放 mutex。
    private val cleaner = createCleaner(mutex.ptr) { pointer ->
        pthread_mutex_destroy(pointer)
        nativeHeap.free(pointer)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var handler: DynamicNetworkHandler? = null
    private var closed = false

    fun call(method: String, params: String, callback: KRRenderModuleCallbackContext?) {
        scope.launch {
            locked {
                if (!closed) {
                    val current = handler ?: runCatching {
                        DynamicNetworkHandler(
                            checkNotNull(transportFactory).invoke(), checkNotNull(capabilityFactory).invoke(),
                        )
                    }.getOrNull()?.also { handler = it }
                    if (current == null) {
                        if (callback != null) KRRenderModuleDoCallback(callback, "{\"status\":\"unavailable\"}")
                        return@locked
                    }
                    current.call(method, params) { result ->
                        locked { if (!closed && callback != null) KRRenderModuleDoCallback(callback, result) }
                    }
                }
            }
        }
    }

    fun dispose() {
        locked { closed = true }
        scope.launch {
            try { handler?.dispose() }
            finally { handler = null; scope.cancel() }
        }
    }

    private inline fun locked(action: () -> Unit) {
        pthread_mutex_lock(mutex.ptr)
        try { action() } finally { pthread_mutex_unlock(mutex.ptr) }
    }
}

private fun construct(moduleName: CPointer<ByteVar>?): COpaquePointer? =
    try { StableRef.create(OhosNetworkOwner()).asCPointer() } catch (_: Throwable) { null }

private fun destruct(instance: COpaquePointer?) {
    if (instance == null) return
    val reference = instance.asStableRef<OhosNetworkOwner>()
    try { reference.get().dispose() } catch (_: Throwable) { }
    finally { reference.dispose() }
}

private fun call(instance: COpaquePointer?, moduleName: CPointer<ByteVar>?, sync: Int,
                 method: CPointer<ByteVar>?, param: KRAnyData?, callback: KRRenderModuleCallbackContext?): KRAnyData? {
    try {
        // KRAnyData 和字符串只在这次 C 调用内有效，先复制再异步执行。
        val request = if (param != null && KRAnyDataIsString(param)) KRAnyDataGetString(param)?.toKString().orEmpty() else ""
        instance?.asStableRef<OhosNetworkOwner>()?.get()?.call(method?.toKString().orEmpty(), request, callback)
    } catch (_: Throwable) { }
    return KRAnyDataCreate()
}
