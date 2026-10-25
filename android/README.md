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

## 功能补充

- **上传**：聊天输入框左侧 `＋` 用系统文件选择器（可多选，每条消息最多 10 个、单个 ≤25 MB），
  上传完成后随消息发送；消息里图片显示缩略图，其它文件显示名称和大小。
- **`/` 命令**：输入 `/` 弹出可用命令（服务端从 claude 的 init 取得），点选补全。
- **模型 / Effort**：新建会话时可选；聊天菜单 ⋮ →「模型 / Effort…」可随时更改。
- **检查更新**：登录后自动检查，会话列表也有「检查更新」。有新版本时弹窗显示更新说明，下载并校验
  SHA-256 后调起系统安装器；首次需在系统设置里允许本应用「安装未知应用」。发布流程见根目录 README。

## 构建

```sh
. android/env.sh                 # JAVA_HOME / ANDROID_HOME（JDK 17 + Android SDK 35）
cd android
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` 里的 `sdk.dir` 不入库，按本机路径生成。

## 测试（全部无头，不需要模拟器；core 53 项、app 以 `./gradlew :app:testDebugUnitTest` 的实际数量为准）

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

## 多主机

服务实例可以部署在不同机器上,App 里同时保存多台并随时切换:

- 会话列表左上角的标题下显示当前主机名(`名称 ▾`),点开列表:点某台即切换,「编辑」可改名称/地址或移除,「＋ 添加主机」登录新的一台。
- 每台主机各自保存地址和登录 token(不存密码);切换时关闭当前聊天/终端,重新载入目标主机的会话。下次启动打开上次使用的主机。
- 改了地址等于换了服务器,该主机的登录会被清掉并要求重新登录;移除主机会尽力在服务端退出登录。
- 主机连不上时登录页有「用已保存的登录重试」和「切换主机」。
- 地址可写 `host:port`(默认 `http://`)或完整的 `https://…`;跨公网部署建议放在 HTTPS 反代或 Tailscale 之后,明文 HTTP 只适合内网。
- 旧版单主机配置首次启动时自动迁移为一台主机。

## 会话接管与用量

- 会话列表顶部显示套餐用量（5 小时会话 / 每周 / 按模型额度，含重置倒计时），进列表时取一次，之后约每分钟刷新。
- 「接管本机会话」列出本机 Claude Code（终端 / VS Code）里的会话，点一下导入并打开；刚被修改过的会话会先确认。
- 打开任何会话都会先让服务端同步终端 / VS Code 里新增的内容。
