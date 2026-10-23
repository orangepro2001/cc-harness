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
| GET/POST | `/api/sessions` | 列表 / 新建 `{title,cwd,model,permissionMode,appendSystemPrompt}` |
| GET/PATCH/DELETE | `/api/sessions/{id}` | 详情 / 改标题、模式、模型 / 删除 |
| POST | `/api/sessions/{id}/messages` `{text}` | 发送消息（202，可排队） |
| GET | `/api/sessions/{id}/events` | SSE 事件流，支持 `Last-Event-ID` / `?after=` |
| GET | `/api/sessions/{id}/history?after=&limit=` | JSON 历史 |
| POST | `/api/sessions/{id}/permissions/{rid}` `{allow,remember,message,updatedInput}` | 回应授权请求 |
| POST | `/api/sessions/{id}/interrupt` / `stop` | 中断回合 / 结束进程（可恢复） |

```sh
curl -u tony:PASSWORD -X POST http://HOST:8765/api/sessions -d '{"title":"demo"}'
```

## 安全说明

- 隔离是**数据与会话命名空间**级别，不是 OS 沙箱：`claude` 以服务运行用户的权限执行，
  知道密码即等同于获得该用户的 shell 权限（受权限模式约束）。
- 传输为明文 HTTP：Tailscale 内由 WireGuard 加密；局域网内密码会以明文传输。
- 登录有防爆破：同 IP 连续失败 5 次锁 5 分钟，全局 10 分钟内 30 次失败则暂停登录。
- 子进程环境会剥离所有 `HARNESS_*` 变量，避免密码泄漏给 agent 的 shell。
- `bypassPermissions` 默认禁用，需 `HARNESS_ALLOW_BYPASS=1`。

## 开发

```sh
go vet ./... && go test -race ./...   # 测试用二进制自身伪装成 claude，不消耗额度
```
