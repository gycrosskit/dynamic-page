# Dynamic Page

## 日常构建入口

日常编译由项目维护命令，CI 只调用 `bash scripts/build.sh compile --platform android|ios|harmony`，不串联 MR、测试或独立消费者。平台参数每次选择一个；这里只编译生产模块，iOS/ArkTS 原生消费与设备验收分别记录。

iOS compile 先编译本库 Kotlin 生产目标，再用本库 Pod/SPM 生产 target 编译 Swift/ObjC；只执行 `xcodebuild build`，不运行测试或远程消费者。

Maven 打包沿用项目脚本，通过 `VERSION=<不可变版本> bash scripts/build.sh package --platform maven` 调用，不上传、不创建 Tag；本库没有独立 HAR 打包渠道。发布任务复用既有 publisher、版本保护与 journal，未做消费时明确记录未消费，不把打包成功视为宿主验收。下文测试及消费命令按需要单独执行，不进入日常构建。


Kuikly 由宿主统一选择版本；组件构建基线、允许范围及消费验证边界见[功能与平台差异](docs/功能与平台差异.md#kuikly-版本与宿主选择)。

当前已发布 Maven 为 [`1.0.1`](https://github.com/gycrosskit/dynamic-page/releases/tag/1.0.1)，iOS Git Pod 为 `1.0.2`（`native-1.0.2`）；本库无独立 HAR/SPM。本轮只准备 Maven `1.0.2` 候选，与现有 Git Pod 版本对齐，尚未发布；旧 Tag、归档和摘要保持不变。源码、SDK、发布与精确消费分别验收。

通用动态页面契约与 Kuikly 宿主能力 Module；现有 Maven 包含通用能力 `invoke` / `invokeTask` 与两阶段页面 loader。默认
`DynamicPageConfiguration(provider = "none")`，不会下载页面、启动动态 SDK 或改变现有路由。
Maven 通过 JitPack 消费，iOS 薄接收端通过配套 Git Pod 消费；不包含 Shiply 或动态包执行。

- `dynamic-page-core`：页面请求、provider 分发和宿主注入的网络与通用能力契约；不创建 Ktor Client、不读取凭据。
- `dynamic-page-kuikly`：JSON callback Module、每个 Renderer 独占的 native handler 与 Android/OHOS 接收端。
- `ios/Sources/GycDynamicPageNative`：Swift 薄接收端，闭包接宿主已有 Shared；没有第二份 Kotlin runtime，也没有独立 CMP UI 模块。

功能、默认值、取消和平台验证状态统一见[功能与平台差异](docs/功能与平台差异.md)。

## 本地接入

以下为 Maven `1.0.2` 未发布候选坐标；发布前继续使用现有 `1.0.1`，JitPack 解析与独立消费须另行核验：

```kotlin
implementation("com.github.gycrosskit.dynamic-page:dynamic-page-core:1.0.2")
implementation("com.github.gycrosskit.dynamic-page:dynamic-page-kuikly:1.0.2")
```

宿主配置 JitPack 仓库后使用以上坐标。源码联调可显式使用 Gradle `includeBuild`，但不能作为远程消费证明。Kotlin 固定
`2.2.21-1.0.0`，Kuikly `2.29.1-2.0.21-ohos`，serialization `1.9.1-1.0.0`，
native handler 的 coroutines `1.10.2-1.0.0`（core 无生产 coroutine 依赖）。目标 Android API 24+、iOS 14+、OpenHarmony arm64；不含 JS target。
Kuikly 发送侧使用 SDK callback/timer，不需要调用方启动 kotlinx.coroutines。

```kotlin
val request = DynamicPageRequest("example", "ExamplePage", "{}")
val result = DynamicPageConfiguration().open(request) // UNAVAILABLE
```

既有 `DynamicPageRuntime.open` 保留 API 兼容。需要避免异步加载完成后误跳转时，宿主导航使用
`DynamicPageLoader.prepare`（可取消下载/校验）和 `PreparedDynamicPage.open/close`（最终同步挂载/释放）；
配置 provider 必须精确匹配。none、空值、缺 adapter、不匹配或非法请求均不调用 loader；普通加载异常返回 null，取消继续抛出。
prepare 不得提前挂载；adapter 负责交出资源前的失败/取消清理。OPENED 后资源归页面，其余由导航 close，
导航负责登录/隐私/功能与 owner 复核及同一页面随包版本回退。组件不建立第二份路由或返回栈。
provider 的版本、动态 SDK、许可、下载、验签、缓存和回滚均留给未来具体接入；当前没有 Shiply adapter。

## 宿主能力 Module

页面在 `createExternalModules()` 返回 `DynamicNetworkModule.NAME to DynamicNetworkModule()`，
请求使用 `request(DynamicNetworkRequest(...)) { response -> ... }`，返回的函数取消本次请求。
页面销毁前调用 `dispose()`；取消和销毁后不交付旧请求的业务 callback，关闭后新 request/invoke/invokeTask 同步返回 unavailable。
原生请求与控制调用仍各终结一次回执，用于释放 SDK callback context。

同一页面的随包版本与未来在线更新版本都使用同一个 `DynamicNetworkModule`；历史 Module 名称 `GycDynamicNetworkModule`
保持不变，以复用已发布 iOS Git Pod 的任意 method 转发和原生接线。旧网络 `request` 保留兼容；
宿主可以通过 `invoke(http.execute)` 和 `invokeTask(http.read/release)` 承载通用 HTTP，由业务包自行解析。
其他能力走 `invoke(DynamicCapabilityRequest("app.context", "get", "{}")) { response -> ... }`。
`invoke` 回执类型是 `DynamicNetworkResponse` 的别名，继续使用 status/httpStatus/bodyJson；
capability/operation 为 1..128 字符标识，支持 `app.context`，paramsJson 必须为 JSON object。
两种调用共用 8 个在途额度、512 KiB 协议上限及取消/超时/销毁管理；默认未注入能力返回 unavailable。

长交互使用 `invokeTask(DynamicCapabilityRequest(...)) { response -> ... }`，wire method 为 `task`，
仍复用 capabilityRequest，并且只交付一次最终回执；不是多回调或业务订阅协议。它独立最多 16 个在途任务，
不占 request/invoke 的 8 个槽位，也不套 native 30 秒或 sender 31 秒短请求超时；512 KiB envelope 和输入验证不变。
调用方须在页面离开、收集停止或销毁时执行返回的取消函数，页面最终销毁时仍须 dispose；
等待期限由业务或原生能力限定，不能将缺少全局超时理解为允许永久占槽。
持续事件可由宿主提供一次 wait 能力，上层按自己的生命周期循环 invokeTask 并在停止时取消；组件不创建订阅或自动重试。
Android/iOS/OHOS 继续使用相同 handler 与既有单次 callback，取消/dispose 仍结算 SDK context，原生接收端无需新增业务实现。

`DynamicCapabilityTransport` 只定义通道；网络、存储和导航的实现、能力白名单、隐私/登录态、品牌与环境策略由宿主复用已有能力。
宿主 Koin 只装配 native 能力；随动态包变化的业务 Repo/ViewModel 及其依赖装配由业务包自己持有，
组件不传递宿主 Koin 或 native 对象，也不新增远程 DI 容器。当前未接入包下载、执行或更新。

原生网络接收端依赖 `DynamicNetworkTransport`。宿主实现 transport 时必须注入现有 Ktor
HttpClient，并负责可信路由、方法和 query 白名单、隐私/登录态、敏感字段、品牌/环境及标准业务协议。
组件没有任意 URL、请求 header、Token 或 SDK 凭据接口；不能把接口存在当作网络安全策略已配置。

Android 在 `registerExternalModule` 调用：

```kotlin
// 旧单 factory 调用仍可用。
kuiklyRenderExport.registerDynamicNetworkModule(
    transportFactory = { hostTransport },
    capabilityFactory = { hostCapabilities },
)
```

iOS 在 Podfile 使用 `pod 'GycDynamicPageNative', :git => 'https://github.com/gycrosskit/dynamic-page.git', :tag => 'native-1.0.2'`；[该 Tag](https://github.com/gycrosskit/dynamic-page/tree/native-1.0.2) 与 Maven 独立版本化，Git Pod 消费须单独核验。
宿主已有 Shared framework 需 export `dynamic-page-kuikly`，并在 Main 创建每 Renderer 一个
`DynamicNetworkHandler(hostTransport, hostCapabilities)`，将其 call/dispose 闭包装入 `GycDynamicNetworkHandler`，通过
`GycDynamicNetworkModule.register(makeHandler:)` 安装工厂。同版 Pod 只含薄接收端，不包含第二份 Shared。

OHOS 在业务 runtime 已建立、首次调用 Module 前执行
`registerOhosDynamicNetworkModule(createTransport = { hostTransport }, createCapabilities = { hostCapabilities })`。
旧单 factory 调用仍可用；此进程注册入口幂等，首次注册决定两份 factory，需在创建 Renderer 前完整注入。
网络完全留在宿主 KMP，
不新增 ArkTS HTTP 或业务 NAPI。最终应用须继续链接 `render::kuikly`，并验证新增 C 符号。

## 验证与许可

以下保留 rc.3 的定向测试与 Kotlin 编译记录，历史候选通过临时源码替换与宿主联调；
新 API 不能用旧发布版本验证；每轮最终源码的实际结果和未覆盖项记录在任务中：

```bash
bash gradlew :dynamic-page-core:testDebugUnitTest :dynamic-page-kuikly:testDebugUnitTest
bash gradlew :dynamic-page-kuikly:compileKotlinIosSimulatorArm64 :dynamic-page-kuikly:compileKotlinOhosArm64
```

宿主源码联调另已完成 iOS Simulator / OHOS Native 链接。实际执行结果及未覆盖范围见功能文档；
Maven `1.0.1` 已有[正式 Release](https://github.com/gycrosskit/dynamic-page/releases/tag/1.0.1)；归档发布、JitPack 解析与独立消费分别判断，远程消费结果由精确版本回执与宿主记录确认，不代替设备行为或真实热更新验收。
组件自有代码使用 [Apache-2.0](LICENSE)；Kuikly ABI 声明的许可见[第三方声明](THIRD_PARTY_NOTICES.md)。

## 发布

Jenkins 以 `VERSION=1.0.2 bash scripts/archive-prebuilt.sh` 构建完整 Maven 候选归档，
复用共用 metadata/checker 验证 13 个 publication、四个 Native 目标、POM 及四种摘要。冻结摘要写入
`release-checksums.txt`，同名 Tag 与 GitHub Release 归档不可覆盖；JitPack 仅安装已验证字节。
发布后使用 `scripts/check-public-maven.py` 检查全部公开变体，再由宿主独立验证三端正式依赖。

鸿蒙 C interop 仅在发布阶段作为真实传递 artifact 独立发布，避免 JitPack 把两个 KLIB 的 classifier 改写为同一 URL；宿主仍只依赖原有两个产品。
iOS receiver 实现不变，Git Pod 为 `1.0.2`（`native-1.0.2`），Maven `1.0.2` 为未发布候选；历史已经验收的 Git Pod `0.1.0-rc.1` 保留，不能代算当前 Git Pod 消费通过。Maven `rc.1` 的鸿蒙元数据不完整，不用于宿主接入。

依赖缓存沿用调用方提供的 `GRADLE_USER_HOME`、`KONAN_DATA_DIR`、`CP_CACHE_DIR`、`GRADLE_RO_DEP_CACHE`，编译与打包使用同一组目录。项目入口不清理这些缓存，也不强制刷新依赖；Maven、Google 与插件仓库镜像由本项目 `settings.gradle.kts` 和已有 `scripts/ci-repositories.gradle`（如存在）维护。CI 仅统一 GitHub 出站代理。
