# Dynamic Page

通用动态页面契约与 Kuikly 宿主网络 Module，首个预发布版本 `0.1.0-rc.1`。默认
`DynamicPageConfiguration(provider = "none")`，不会下载页面、启动动态 SDK 或改变现有路由。
Maven 通过 JitPack 消费，iOS 薄接收端通过同版 Git Pod 消费；不包含 Shiply 或动态包执行。

- `dynamic-page-core`：页面请求、provider 分发和宿主注入的网络契约；不创建 Ktor Client、不读取凭据。
- `dynamic-page-kuikly`：JSON callback Module、每个 Renderer 独占的 native handler 与 Android/OHOS 接收端。
- `ios/Sources/GycDynamicPageNative`：Swift 薄接收端，闭包接宿主已有 Shared；没有第二份 Kotlin runtime，也没有独立 CMP UI 模块。

功能、默认值、取消和平台验证状态统一见[功能与平台差异](docs/功能与平台差异.md)。

## 本地接入

固定 Maven 坐标：

```kotlin
implementation("com.github.gycrosskit.dynamic-page:dynamic-page-core:0.1.0-rc.1")
implementation("com.github.gycrosskit.dynamic-page:dynamic-page-kuikly:0.1.0-rc.1")
```

宿主配置 JitPack 仓库后使用以上坐标。源码联调可显式使用 Gradle `includeBuild`，但不能作为远程消费证明。Kotlin 固定
`2.2.21-1.0.0`，Kuikly `2.29.1-2.0.21-ohos`，serialization `1.9.1-1.0.0`，
native handler 的 coroutines `1.10.2-1.0.0`（core 无生产 coroutine 依赖）。目标 Android API 24+、iOS 14+、OpenHarmony arm64；不含 JS target。
Kuikly 发送侧使用 SDK callback/timer，不需要调用方启动 kotlinx.coroutines。

```kotlin
val request = DynamicPageRequest("example", "ExamplePage", "{}")
val result = DynamicPageConfiguration().open(request) // UNAVAILABLE
```

宿主实现 `DynamicPageRuntime` 后，配置 provider 必须与 runtime.provider 精确匹配。
provider 的版本、动态 SDK、许可、下载、验签、缓存和回滚均留给未来具体接入；当前没有 Shiply adapter。

## 网络 Module

页面在 `createExternalModules()` 返回 `DynamicNetworkModule.NAME to DynamicNetworkModule()`，
请求使用 `request(DynamicNetworkRequest(...)) { response -> ... }`，返回的函数取消本次请求。
页面销毁前调用 `dispose()`；取消和销毁后不交付旧请求的业务 callback，关闭后新 request 同步返回 unavailable。
原生请求与控制调用仍各终结一次回执，用于释放 SDK callback context。

原生接收端统一依赖 `DynamicNetworkTransport`。宿主实现 transport 时必须注入现有 Ktor
HttpClient，并负责可信路由、方法和 query 白名单、隐私/登录态、敏感字段、品牌/环境及标准业务协议。
组件没有任意 URL、请求 header、Token 或 SDK 凭据接口；不能把接口存在当作网络安全策略已配置。

Android 在 `registerExternalModule` 调用：

```kotlin
kuiklyRenderExport.registerDynamicNetworkModule { hostTransport }
```

iOS 在 Podfile 使用 `pod 'GycDynamicPageNative', :git => 'https://github.com/gycrosskit/dynamic-page.git', :tag => '0.1.0-rc.1'`。
宿主已有 Shared framework 需 export `dynamic-page-kuikly`，并在 Main 创建每 Renderer 一个
`DynamicNetworkHandler`，将其 call/dispose 闭包装入 `GycDynamicNetworkHandler`，通过
`GycDynamicNetworkModule.register(makeHandler:)` 安装工厂。同版 Pod 只含薄接收端，不包含第二份 Shared。

OHOS 在业务 runtime 已建立、首次调用 Module 前执行
`registerOhosDynamicNetworkModule { hostTransport }`。此进程注册入口幂等；网络完全留在宿主 KMP，
不新增 ArkTS HTTP 或业务 NAPI。最终应用须继续链接 `render::kuikly`，并验证新增 C 符号。

## 验证与许可

本地命令：

```bash
bash gradlew :dynamic-page-core:testDebugUnitTest :dynamic-page-kuikly:testDebugUnitTest
bash gradlew :dynamic-page-kuikly:compileKotlinIosSimulatorArm64 :dynamic-page-kuikly:compileKotlinOhosArm64
```

实际执行结果及未覆盖范围见功能文档；这些命令不证明 Pod/Native 链接、设备行为或真实热更新。
组件自有代码使用 [Apache-2.0](LICENSE)；Kuikly ABI 声明的许可见[第三方声明](THIRD_PARTY_NOTICES.md)。

## 发布

`VERSION=0.1.0-rc.1 bash scripts/archive-prebuilt.sh` 在独占 macOS Worktree 构建完整 Maven 归档，
复用共用 metadata/checker 验证 12 个 publication、四个 Native 目标、POM 及四种摘要。冻结摘要写入
`release-checksums.txt`，同名 Tag 与 GitHub Release 归档不可覆盖；JitPack 仅安装已验证字节。
发布后使用 `scripts/check-public-maven.py` 检查全部公开变体，再由宿主独立验证三端正式依赖。
