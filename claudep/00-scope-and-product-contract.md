# 00｜范围与产品契约

## 1. 产品目标

在 RikkaHub Agent1 中新增一个独立的 `Claude P` Provider，使用户可以使用自己在 CC VPS 上已登录的 Claude Code 订阅进行对话，同时保留 RikkaHub 的聊天 UI、助手配置、历史、工具审批、手机工具和远程 MCP 能力。

Claude P 不是 Anthropic API Provider 的别名，也不是把 Claude OAuth Token 填进手机。它是一个远端运行时 Provider：Android 只连接用户自己的 Gateway，Gateway 再驱动固定版本的 Claude Code Worker。

第一版是单一 Claude 账号所有者、自有 VPS、可配对多个本人设备的产品，不是多人共享 Claude 订阅的公共中转服务。多用户托管需要独立的账号隔离、条款与计费设计，不属于本方案。

## 2. 权威数据归属

| 数据或能力 | 权威组件 |
|---|---|
| 可见聊天历史、分支、编辑、重生成 | RikkaHub Android 数据库 |
| Assistant、系统提示词、启用工具 | RikkaHub Android |
| 手机本地工具及人工批准 | RikkaHub Android |
| 现有远程 MCP 配置、OAuth 与 Token | RikkaHub Android `McpManager` |
| Claude 登录、OAuth 文件、CLI 版本 | CC VPS Claude Worker |
| Claude session ID 与运行终态 | CC VPS Gateway/Worker |
| RikkaHub 对话与 Claude session 的绑定 | 双方各保存不含提示词的最小映射 |
| Provider 流式 UI、取消操作 | Android 发起，Gateway/Worker 执行并回执 |

任何实现不得让 Claude session 成为唯一聊天副本，也不得把 RikkaHub 本地历史永久复制成服务端产品数据库。

## 3. 用户可见契约

### 3.1 Provider 设置

Provider 名称为 `Claude P`，与已有 `Claude` API Provider、`Codex` Provider 明确区分。设置页至少显示：

- Gateway 显示名称与规范化 HTTPS Origin；
- 设备配对状态、设备名称、最后成功连接时间；
- Gateway/Worker 健康状态；
- Claude Code 锁定版本；
- Claude 登录状态（只显示安全枚举，不显示账号正文或 Token）；
- 可用模型别名；
- 额度状态（若 Gateway 能安全提供）；
- 撤销本设备、重新配对按钮。

手机设置中不得出现 Claude OAuth Token、Claude 配置文件、VPS SSH 私钥或任意 Worker Unix socket 路径。

### 3.2 Phase 1 能力

- 文本输入与流式文本输出；
- 可选的安全推理摘要事件；
- 模型选择；
- 停止生成；
- 明确区分连接失败、登录失效、额度不可用、Worker 忙、版本不匹配和会话丢失；
- App 重启后能够查询上一次 Generation 的安全终态；
- 不支持工具、图片、文档、语音和后台自动任务，并在 UI 明示，而不是静默丢弃。

### 3.3 Phase 2 能力

- 对话首次请求使用 `new`；
- 连续对话使用 `resume`；
- 编辑旧消息、重新生成或分支使用 `fork` 或确定性的 `rebuild`；
- 丢失 session 时从 RikkaHub 权威历史安全重建；
- 同一远端线程同一时间最多一个 active Generation；
- 不自动重发已经跨过模型 dispatch 边界的请求。

### 3.4 Phase 3 能力

- Claude 可以调用当前 Assistant 已启用的手机本地工具和手机 MCP 工具；
- 每个写工具继续使用 RikkaHub 现有审批策略；
- Gateway 和 Claude Worker 不持有手机 MCP Token；
- 手机断线、审批超时或执行状态未知时 fail-closed；
- 相同工具调用不能因重连而重复执行。

### 3.5 Phase 4 能力

- 图片和文档采用一次 Generation 专属的临时上传；
- 服务端校验真实字节、大小、MIME 和哈希；
- 仅本次 Worker sandbox 可读取；
- 终态后按策略回收，不能进入 Claude 全局目录、日志或公共缓存。

## 4. 明确不做

- 不在 Android/proot/Termux 内安装或登录 Claude Code；
- 不复用 Codex OAuth Token、Anthropic API Key 或 Claude Web Cookie；
- 不允许 Gateway 接收任意 shell、任意 CLI 参数或任意工作目录；
- 不把 Claude P 伪装成 OpenAI-compatible 或 Anthropic Messages API；
- 不让 Claude P 绕开 RikkaHub 工具审批；
- 不默认把手机 MCP 配置搬到 VPS；
- 不在 Phase 1 开启后台定时任务、sub-agent 或无人值守写工具；
- 不承诺 Claude session 永久存在；
- 不支持 HTTP 明文生产连接。

## 5. 能力声明

Provider 必须按阶段诚实声明模型能力：

| 阶段 | TEXT | REASONING | TOOL | IMAGE 输入 | DOCUMENT 输入 | 后台运行 |
|---|---:|---:|---:|---:|---:|---:|
| Phase 1 | 是 | 仅安全摘要 | 否 | 否 | 否 | 否 |
| Phase 2 | 是 | 仅安全摘要 | 否 | 否 | 否 | 否 |
| Phase 3 | 是 | 仅安全摘要 | 是 | 否 | 否 | 默认否 |
| Phase 4 | 是 | 仅安全摘要 | 是 | 按模型 | 按模型 | 默认否 |

能力必须来自 Gateway 的版本化 catalog，并与本地允许集求交。远端返回未知能力时默认关闭。

## 6. 错误和降级

- 不支持的输入在 dispatch 前拒绝，不能剥离后偷偷发送纯文本。
- session 丢失只允许显式 rebuild；不得 resume 到其他对话。
- 额度无法读取显示“不可用”，不能显示为 0。
- Gateway 版本不兼容时停止请求并提示升级；不能猜测协议字段。
- 工具桥不可用时 Provider 降级为当前请求失败，不能让 Claude 自行改用内置工具。
- 终态未知时显示“状态待确认”，查询 receipt 后才能允许用户决定是否重试。
