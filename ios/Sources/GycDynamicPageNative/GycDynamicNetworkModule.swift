import Foundation
import OpenKuiklyIOSRender

/// 闭包接宿主已有 Shared handler，不再打包一份 Kotlin runtime。
public struct GycDynamicNetworkHandler {
    let call: (String, String, @escaping (String) -> Void) -> Void
    let dispose: () -> Void

    public init(call: @escaping (String, String, @escaping (String) -> Void) -> Void,
                dispose: @escaping () -> Void) {
        self.call = call
        self.dispose = dispose
    }
}

@objc(GycDynamicNetworkModule)
public final class GycDynamicNetworkModule: KRBaseModule {
    private static var makeHandler: (() -> GycDynamicNetworkHandler)?
    private let lifecycleLock = NSRecursiveLock()
    private var invalidated = false
    private var handler: GycDynamicNetworkHandler?

    @MainActor
    public static func register(makeHandler: @escaping () -> GycDynamicNetworkHandler) {
        self.makeHandler = makeHandler
        precondition(NSClassFromString("GycDynamicNetworkModule") == GycDynamicNetworkModule.self)
    }

    public override func hrv_call(withMethod method: String, params: Any?, callback: KuiklyRenderCallback?) -> Any? {
        let work = { [weak self] in
            guard let self else { return }
            self.lifecycleLock.lock()
            defer { self.lifecycleLock.unlock() }
            guard !self.invalidated else { return }
            MainActor.assumeIsolated {
                if self.handler == nil { self.handler = Self.makeHandler?() }
                guard let handler = self.handler else {
                    self.deliver("{\"status\":\"unavailable\"}", callback: callback)
                    return
                }
                handler.call(method, params as? String ?? "") { [weak self] result in
                    self?.deliver(result, callback: callback)
                }
            }
        }
        if Thread.isMainThread { work() } else { DispatchQueue.main.async(execute: work) }
        return nil
    }

    private func deliver(_ result: String, callback: KuiklyRenderCallback?) {
        lifecycleLock.lock()
        defer { lifecycleLock.unlock() }
        guard !invalidated, hr_rootView != nil else { return }
        // SDK callback 已负责投递 Context，避免额外排队；sender 仍会丢弃已撤销的业务回执。
        callback?(result)
    }

    public override func invalidate() {
        lifecycleLock.lock()
        guard !invalidated else { lifecycleLock.unlock(); return }
        invalidated = true
        let closing = handler
        handler = nil
        lifecycleLock.unlock()
        // SDK Context 可能正被 Main 等待；不反向同步等待 Main。
        let cleanup: () -> Void = { MainActor.assumeIsolated { closing?.dispose() } }
        if Thread.isMainThread { cleanup() } else { DispatchQueue.main.async(execute: cleanup) }
        super.invalidate()
    }
}
