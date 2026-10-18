package io.github.gycrosskit.dynamicpage.kuikly

import android.os.Handler
import android.os.Looper
import com.tencent.kuikly.core.render.android.IKuiklyRenderExport
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import io.github.gycrosskit.dynamicpage.DynamicNetworkTransport

class AndroidDynamicNetworkModule(private val transportFactory: () -> DynamicNetworkTransport) : KuiklyRenderBaseModule() {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var destroyed = false
    private var handler: DynamicNetworkHandler? = null

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        onMain {
            val current = handler ?: runCatching { DynamicNetworkHandler(transportFactory()) }
                .getOrNull()?.also { handler = it }
            if (current == null) {
                callback?.invoke("{\"status\":\"unavailable\"}")
                return@onMain
            }
            current.call(method, params.orEmpty()) { result -> if (!destroyed) callback?.invoke(result) }
        }
        return null
    }

    override fun onDestroy() {
        destroyed = true
        val cleanup = { handler?.dispose(); handler = null; kuiklyRenderContext = null }
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup() else main.post { cleanup() }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) { if (!destroyed) action() }
        else main.post { if (!destroyed) action() }
    }
}

fun IKuiklyRenderExport.registerDynamicNetworkModule(transportFactory: () -> DynamicNetworkTransport) {
    moduleExport(DynamicNetworkModule.NAME) { AndroidDynamicNetworkModule(transportFactory) }
}
