# cc-harness Android 客户端

Kotlin + Jetpack Compose 的 cc-harness 配套客户端：登录、会话列表、聊天（流式输出、
工具调用卡片、权限审批、`AskUserQuestion` 回答）、中断/停止、切换权限模式，以及
**选择 agent 的工作目录**（浏览服务端允许的目录、可新建文件夹）。

## 结构

| 模块 | 内容 | 依赖 |
|---|---|---|
| `core/` | 纯 Kotlin/JVM：HTTP+SSE 客户端（断线按 `Last-Event-ID` 续传）、事件归约成聊天状态、Markdown 解析、`SessionController` | OkHttp、kotlinx-serialization、coroutines |
| `app/` | Compose 界面与 `AppViewModel`（状态提升，界面组件只吃状态和回调） | Compose BOM、Material3、AndroidX lifecycle |

没有 Retrofit、Room、Hilt、图标库等额外依赖。登录后只保存 token，不保存密码。
服务端是明文 HTTP（Tailscale 内由 WireGuard 加密），所以清单里开启了
`usesCleartextTraffic`。

## 构建

```sh
. android/env.sh                 # JAVA_HOME / ANDROID_HOME（JDK 17 + Android SDK 35）
cd android
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` 里的 `sdk.dir` 不入库，按本机路径生成。

## 测试（全部无头，不需要模拟器；core 44 项、app 41 项）

```sh
./gradlew :core:test :app:testDebugUnitTest
```

- `core`：SSE 解析、Markdown、用**真实 claude 会话录制的事件**回放归约、MockWebServer
  覆盖错误映射/重连；`GoServerE2ETest` 会 `go build` 本仓库的服务端，用 Go 测试二进制
  充当假 `claude`，从 Kotlin 端到端走完登录、聊天、授权、重放、目录选择、中断。
  没有 `go` 时该类自动跳过。
- `app`：`AppViewModelTest` 用假服务验证导航与错误处理；`ScreensUiTest` 用
  Robolectric（`GraphicsMode.NATIVE`，真实文字度量）+ Compose 测试规则在 JVM 上渲染真实
  界面并点击；`ScreenshotTest` 把各界面和对话框渲染成 PNG 写到 `app/build/screenshots/`，
  方便肉眼检查布局。

本机没有 `/dev/kvm`，Android 模拟器无法硬件加速，所以不用模拟器；Robolectric 是等价的
无头方案，但不能代替真机上的布局/触控验收。
