# cc-harness

常驻的 Claude Code 后台服务：Go 标准库实现（零第三方依赖），驱动 `claude` CLI 的
stream-json 协议（Agent SDK 底层使用的同一协议），对外提供 HTTP API 与一个极简网页前端。

- **常驻 + 恢复**：每个会话一个常驻 `claude` 子进程；空闲回收，下一条消息自动 `--resume`，
  服务重启后会话与历史完整保留。浏览器关闭、网络断开不影响后台运行。
- **断线续传**：事件以 JSONL 落盘，SSE 通过 `Last-Event-ID` 从任意位置继续。
- **多用户隔离**：数据、工作区、会话按用户命名空间隔离；可为用户设置独立密码。
- **网页授权**：工具权限请求（含 `AskUserQuestion`）在网页/客户端上批准。
- **工作目录选择**：`/api/fs` 浏览目录，仅限个人工作区和 `HARNESS_ROOTS`。
- **网络**：只监听 Tailscale 与局域网地址（`HARNESS_BIND=auto`，每 15 秒重新探测）。

## 部署

```sh
cp deploy/env.example ~/.config/cc-harness/env && chmod 600 ~/.config/cc-harness/env  # 编辑密码
deploy/install.sh        # 测试、编译、安装并启动 systemd 用户服务
journalctl --user -u cc-harness -f
```

## API（Basic 认证 `user:password`，或登录后的 Cookie / Bearer token）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/login` `{user,password}` | 返回 token 并设置 Cookie |
| GET | `/api/me` | 用户、工作区、可选根目录 |
| GET | `/api/fs?path=&hidden=1` | 列目录（无 path = 根目录列表） |
| POST | `/api/fs/mkdir` `{parent,name}` | 新建文件夹 |
| GET/POST | `/api/sessions` | 列表 / 新建 `{title,cwd,model,effort,permissionMode,appendSystemPrompt}` |
| GET/PATCH/DELETE | `/api/sessions/{id}` | 详情 / 改标题、模式、模型、`effort`（low/medium/high/xhigh/max）/ 删除 |
| POST | `/api/sessions/{id}/messages` `{text,attachments:[id]}` | 发送消息（202，可排队）；只发附件也可以 |
| POST | `/api/sessions/{id}/uploads?name=` | 原始请求体即文件，返回 `{id,name,kind,mime,size}`；默认上限 25 MB |
| GET | `/api/sessions/{id}/uploads/{id}` | 取回附件（图片内联，其余下载） |
| GET | `/api/sessions/{id}/commands` | 斜杠命令列表（`/` 补全用，来自 claude 启动时的 init） |
| GET | `/api/usage` | 套餐用量（5 小时会话 / 7 天 / 按模型额度，如 Fable）及重置时间，缓存 60 秒 |
| GET | `/api/local-sessions` | 本机 Claude Code（终端 / VS Code）里可接管的会话 |
| POST | `/api/local-sessions/{id}/import` `{force}` | 接管：沿用同一个会话 ID；刚被修改过的会话需 `force` |
| POST | `/api/sessions/{id}/sync` | 把在终端 / VS Code 里继续聊出的新内容补进历史 |
| GET | `/api/client/android/latest` / `download` | 最新 Android 版本信息 / APK（支持 Range） |
| GET | `/api/sessions/{id}/events` | SSE 事件流，支持 `Last-Event-ID` / `?after=` |
| GET | `/api/sessions/{id}/history?after=&limit=` | JSON 历史 |
| POST | `/api/sessions/{id}/permissions/{rid}` `{allow,remember,message,updatedInput}` | 回应授权请求 |
| POST | `/api/sessions/{id}/interrupt` / `stop` | 中断回合 / 结束进程（可恢复） |

```sh
curl -u tony:PASSWORD -X POST http://HOST:8765/api/sessions -d '{"title":"demo"}'
```

## 单用户模式

个人使用时设 `HARNESS_USER=tony`：任何登录都是这个用户，输入什么用户名都一样，只校验密码；网页和 App 的登录页
不再有用户名输入框，所有设备看到同一批会话。已发出的旧令牌继续有效（统一映射到该用户）。与
`HARNESS_USER_PASSWORDS` 互斥。不设置时仍是原来的多用户模式（各用户的会话和工作区互相隔离）。
Claude 的 OAuth 凭据属于服务器上的操作系统用户（`~/.claude`），与这里的用户名无关。

## 会话接管（本机 CLI / VS Code ⇄ 网页 / 手机）

会话的唯一事实来源是 claude 写的 `<claude home>/projects/<cwd>/<id>.jsonl`，终端、VS Code 插件和本服务
读写的是同一个文件，所以会话可以来回交接：

- **本机 → Harness**：网页「导入本地会话…」/ Android「接管本机会话」，沿用同一个会话 ID。
- **Harness → 终端 / VS Code**：先「停止进程」（或等空闲回收），再 `claude --resume <id>`（在该会话的目录下）。
- **回到 Harness**：打开会话和下一条消息都会先同步（`/sync`）：按字节偏移只补转录里新增的完整行，
  历史里出现「已同步 N 条新内容」，不重复、不丢。
- **网页 ⇄ 手机**：同一用户的会话本来就在服务端，两端实时互看，谁都能继续发消息、批准权限。
- **限制**：两边别同时写同一个会话。Harness 的进程在跑时不会同步（它自己是写入方）；导入刚被修改过
  的会话会先确认。VS Code「Web」页签里的是 claude.ai 云端会话，不在本机转录里，无法接管。
- **用量**读取服务器上 claude 登录的 OAuth 凭据（只读，不刷新令牌；令牌由 claude 自己续期），
  是账号级数字而非按 harness 用户区分；`HARNESS_USAGE_URL` 可覆盖接口地址。

## 上传、命令与更新

- **图片/文件**：Web 与 Android 都能上传。≤5 MB 的 png/jpeg/gif/webp 作为内联图片直接给模型看，
  其余文件存到会话目录，把路径写进消息，agent 用 Read 读取（启动时 `--add-dir` 该目录，无需授权弹窗）。
  文件类型按内容嗅探而非客户端声明；单文件上限 `HARNESS_MAX_UPLOAD_MB`（默认 25），每会话总量 1 GiB。
- **`/` 命令补全**：输入 `/` 弹出当前会话可用的命令（含 skills），选中后补全；命令列表来自 claude
  的 init 消息，新会话在首次对话前沿用最近一次的列表。
- **Effort**：新建会话和会话内都能选（模型旁）。CLI 只在启动时接受 `--effort`，所以空闲时改动会停掉
  后台进程，下一条消息自动 `--resume` 并带上新值。
- **Android 自更新**：`deploy/publish-android.sh` 构建 APK、读取 versionCode，写入
  `<data>/releases/android.json`（`HARNESS_RELEASES` 可改位置）。App 登录后自动检查，也可手动点
  「检查更新」；下载后校验 SHA-256，再交给系统安装器。发布前在 `android/gradle.properties`
  递增 `appVersionCode`；所有版本必须用同一把签名密钥。
  发布走 release 构建，密钥在仓库之外：`~/.config/cc-harness/cc-harness-release.jks` 与同目录的
  `android-signing.properties`（`storeFile/storePassword/keyAlias/keyPassword`，路径可用
  `HARNESS_SIGNING` 覆盖），**请备份这两个文件**，丢失后已安装的 App 只能卸载重装。
  发布脚本会校验签名并把证书指纹记在 `<data>/android-signer.sha256`，证书变化时拒绝发布。
  生成密钥：`keytool -genkeypair -keystore cc-harness-release.jks -alias cc-harness -keyalg RSA -keysize 4096 -validity 36500`。

## 终端（仅 Android App，默认关闭）

App 里可以打开服务器上的**真实 shell**（会话列表的「＞_ 终端」），也可以在聊天页菜单里「在终端中接管」，
把该会话用真正的 `claude --resume` 命令行接着聊。网页端没有终端入口。

- **开启**：`HARNESS_TERMINAL=1`（单用户模式）或 `HARNESS_TERMINAL=用户名[,用户名]`（`*` = 所有用户），重启服务。需要服务器装有 tmux。
  不设置就完全关闭：接口返回 403，`/api/me` 的 `terminal` 为 false，App 不显示入口。
- **来源限制**：只接受本机和 Tailscale 来源的请求（`HARNESS_TERMINAL_NETS`，默认
  `127.0.0.0/8,::1/128,100.64.0.0/10,fd7a:115c:a1e0::/48`），按 TCP 对端地址判断、不信任代理头。
  局域网地址上的 HTTP 是明文，按键会被嗅探，所以默认不放行；确实需要时设 `any`。
- **持久**：每个终端是一个独立 tmux 服务（`-L cch`）里的会话，每次连接只是一个临时的 `tmux attach`。
  手机锁屏、断网后 shell 仍在运行，重连会重画原屏幕；在 App 里点「结束」才会关闭。每用户最多
  `HARNESS_TERMINAL_MAX`（默认 8）个。
- **传输**：沿用 SSE 下行 + POST 上行，没有新增依赖。接口：`GET/POST /api/terminals`、
  `DELETE /api/terminals/{id}`、`GET /api/terminals/{id}/stream?cols=&rows=`（SSE，首条事件给出连接 id）、
  `POST /api/term-attachments/{aid}/input`（原始字节）、`POST /api/term-attachments/{aid}/resize`。
- **手机端模拟器**：手写的 VT100/xterm 子集（`android/core/.../term/`，纯 Kotlin，有单元测试）：光标、滚动区、
  256 色/真彩色、备用屏、鼠标上报（tmux 滚轮翻页）、括号粘贴、OSC 标题与剪贴板、中文宽字符。
  额外按键行（Esc/Tab/Ctrl/Alt/方向键等）、双指缩放字号、选择/复制屏幕文字。
- **字体**：终端用系统等宽字体显示文字；Powerline 分隔符、框线、块字符、盲文（旋转指示器）由 App 自己画成几何图形，
  保证和格子严丝合缝；Nerd Font 图标（私有区字符，powerlevel10k、`ls --icons` 等用）用打包进 App 的
  Symbols Nerd Font Mono（MIT，约 2.6 MB，`android/app/src/main/assets/fonts/`）显示。
- **审计与隔离**：创建、结束、连接、断开都写入服务日志（`journalctl --user -u cc-harness`）；shell 的环境里
  去掉了所有 `HARNESS_*` 变量（密码不会泄露给 shell）。终端只能列出、连接、结束自己的会话。
- **风险说明**：这等于以服务运行用户的身份在服务器上开 shell，权限高于带权限模式的 Claude 会话。
  目前的"仅手机"靠 UI 入口和来源网络限制；用密码登录的任何客户端（包括网页的 Cookie）在允许的网络内
  都能调用接口。后续计划：设备绑定（Android Keystore 密钥签名挑战）。

## 反向代理（Tailscale Serve）

本机的反向代理是 Tailscale Serve，已加路由：`https://tony-ai-pc-1.tail9f7f1f.ts.net/claude` →
`http://100.101.146.117:8765`（HTTPS，仅 tailnet 可达）。服务通过 `HARNESS_BASE_PATH=/claude`
识别前缀：页面的 `<base>`、静态资源、API、Cookie 路径都按它走，代理剥不剥前缀都行。
重建路由：`tailscale serve --bg --https=443 --set-path /claude http://100.101.146.117:8765`。

## 安全说明

- 隔离是**数据与会话命名空间**级别，不是 OS 沙箱：`claude` 以服务运行用户的权限执行，
  知道密码即等同于获得该用户的 shell 权限（受权限模式约束）。
- 传输为明文 HTTP：Tailscale 内由 WireGuard 加密；局域网内密码会以明文传输。
- 登录有防爆破：同 IP 连续失败 5 次锁 5 分钟，全局 10 分钟内 30 次失败则暂停登录。
- 子进程环境会剥离所有 `HARNESS_*` 变量，避免密码泄漏给 agent 的 shell。
- `bypassPermissions` 默认禁用，需 `HARNESS_ALLOW_BYPASS=1`。

## Android 客户端

`android/`（Kotlin + Compose，见 `android/README.md`）：会话、聊天、权限审批、工作目录选择、图片/文件上传、`/` 命令补全、模型与 Effort、应用内检查更新。

## 开发

```sh
go vet ./... && go test -race ./...   # 测试用二进制自身伪装成 claude，不消耗额度
```
