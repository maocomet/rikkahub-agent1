# 01｜架构与信任边界

## 1. 总体架构

```text
┌──────────────────────── Android ────────────────────────┐
│ RikkaHub UI / Room / Assistant                         │
│   ├─ ClaudePProvider                                   │
│   ├─ ClaudePGatewayClient (WSS)                        │
│   ├─ Android Keystore device key                       │
│   ├─ Tool approval / local tools                       │
│   └─ McpManager (remote MCP OAuth + tools/call)        │
└───────────────────────────┬─────────────────────────────┘
                            │ TLS 1.2+ / WSS
                            │ device-bound short-lived auth
┌──────────────────────── CC VPS ─────────────────────────┐
│ Claude P Gateway (unprivileged service user)            │
│   ├─ pairing/device registry                            │
│   ├─ protocol + idempotency + receipts                  │
│   ├─ remote-thread/session binding                      │
│   └─ per-generation tool relay                         │
│                         │ private Unix socket           │
│ Claude Worker (separate service boundary)               │
│   ├─ fixed Claude Code binary/version                   │
│   ├─ strict argv builder                                │
│   ├─ new/resume/fork/cancel                             │
│   ├─ stream-json parser                                 │
│   └─ ephemeral per-generation MCP bridge (Phase 3)      │
│                         │                               │
│ Claude Code OS account + OAuth file                     │
└─────────────────────────────────────────────────────────┘
```

## 2. 服务拆分

### Android `ClaudePProvider`

适配现有 `Provider<ProviderSetting>`：

- 将 RikkaHub 的消息与参数编译成版本化 Gateway 请求；
- 将 Gateway 事件映射成 `MessageChunk`；
- 通过 Kotlin `Flow` 传播取消；
- 不解析 Claude 原始 stderr；
- 不保存 Claude session 正文，只保存不透明绑定标识和最后确认终态。

### Claude P Gateway

Gateway 是公网唯一入口，但不是 shell gateway：

- 只接受固定协议消息；
- 验证设备身份、请求序号、幂等键和权限；
- 限制每设备/线程并发和消息大小；
- 保存最小 session binding、终态 receipt 和审计；
- 不读取 Claude OAuth 文件；
- 不接受客户端传入 CLI 路径、argv、环境变量、cwd、MCP 配置路径或 socket 路径。

### Claude Worker

- 只监听私有 Unix socket；
- 固定 root-owned Claude Code binary 与 expected version；
- 由类型化请求构造 argv；
- 以专用 `claude` OS 用户运行子进程；
- 解析受支持的 stream-json 事件，未知事件安全忽略或协议失败；
- stderr 只做有界 allowlist 分类，不进入客户端事件或日志；
- 子进程结束后必须清空 active run 与临时目录。

### Phase 3 MCP 工具桥

工具桥只存在于单个 Generation 生命周期内：

1. 手机把已启用工具的版本化 schema snapshot 发送给 Gateway；
2. Gateway 验证名称、数量、大小、schema 深度和能力；
3. Worker 为该 Generation 生成唯一 MCP 配置，只暴露 `mcp__rikkahub_bridge__*`；
4. Claude 发出 tools/call；
5. Bridge 把调用以 `tool.requested` 事件发回手机；
6. 手机按现有审批策略批准/拒绝并执行本地或 MCP 工具；
7. 手机返回绑定 call ID 和参数指纹的结果；
8. Bridge 返回 Claude 并销毁该调用状态。

Gateway 不获得远程 MCP 的 Authorization Header 或 OAuth Token。

## 3. 信任域

| 域 | 信任内容 | 不信任内容 |
|---|---|---|
| Android UI/数据库 | 用户可见历史与显式操作 | Provider/远程返回的文本与工具参数 |
| Android Keystore | 设备私钥不可导出 | Root 设备或被篡改客户端仍需服务端约束 |
| 公网 | TLS 机密性和完整性 | 所有重放、乱序、断线和恶意包 |
| Gateway | 自己的设备表、receipt、绑定 | Android 声称的模型能力、CLI 参数、工具安全性 |
| Worker | 固定构建与 argv policy | prompt、工具参数、模型输出、stderr |
| Claude 子进程 | 无 | 一切输出和工具请求均是不可信输入 |
| 远程 MCP | 无 | schema、工具结果、OAuth 错误和图片正文 |

## 4. 凭证流

### Claude 登录

用户仅在 VPS 上以 `claude` OS 用户完成官方交互登录。OAuth 文件由 root/claude 权限保护；Android、Gateway API、日志和数据库均看不到正文。

### 手机配对

1. VPS 管理命令生成 128-bit 以上的一次性 pairing ticket，TTL 不超过 5 分钟；服务端仅存哈希。
2. QR 包含规范化 HTTPS Origin、Gateway 公钥指纹、ticket 和协议版本；不包含 Claude Token。
3. Android 在 Keystore 创建不可导出的 Ed25519/P-256 设备密钥。
4. 手机提交公钥、ticket 和 possession proof。
5. Gateway 单次消费 ticket，返回 device ID 和短期 access credential。
6. 后续刷新必须对 Gateway nonce 签名；单独窃取 bearer credential 不能长期续期。

生产环境禁止用户手工粘贴任意 HTTP endpoint。自托管 HTTPS Origin 必须通过配对 QR 导入并由用户确认主机名与指纹。

### MCP 登录

RikkaHub 现有 MCP OAuth 仍在手机 Custom Tab 中完成，Token 由手机管理。工具桥只接收调用结果。未来若增加 VPS-owned MCP，必须作为另一类明确标注的工具来源，不得自动迁移手机凭证。

## 5. 会话归属

Android 为每个可同步的对话分配随机 `remote_thread_id`，为每个分支头分配 `remote_branch_id`。这些 ID 不等于 Room 主键，不编码用户名。

Gateway binding 至少包含：

- device/account scope；
- remote thread/branch ID；
- Claude session ID（加密或严格受限保存）；
- CLI protocol/version；
- model alias；
- last completed request sequence；
- binding state：`idle | active | interrupted | invalidated`。

只有 `idle` binding 可 resume。active/interrupted 必须先通过 receipt/recovery 收敛。

## 6. 生命周期序列

### 普通连续对话

```text
Android              Gateway              Worker/Claude
   | generation.start   |                       |
   |------------------->| validate + receipt    |
   |                    | resume(session)       |
   |                    |---------------------->|
   |<------ event stream: started/delta/... ----|
   |<---------------- completed + binding ------|
```

### 工具调用

```text
Claude -> local Bridge -> Gateway -> Android approval
Android -> local/MCP tool -> Gateway -> Bridge -> Claude
```

### 断线

- Android 断线不等于取消；Gateway 保留有界事件缓冲和 receipt。
- 重连携带最后确认序号，只重放事件，不重发模型请求。
- 用户显式取消才向 Worker 发送 cancel。
- 缓冲过期后客户端查询终态；无法证明终态时标记 unknown，不自动 retry。

