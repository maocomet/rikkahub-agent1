# Claude P MVP v2 执行计划

- Status: APPROVED FOR EXECUTION
- Scope: single-user personal software
- Canonical execution plan: yes
- Current authorized stage: M0 only
- DS 不得自行进入下一阶段。
- 每个阶段完成后由 Codex review，再由猫猫决定是否授权下一阶段。
- 旧 `claudep/` CP1/CP2 等文档仍作为历史设计与验证证据保留，但不再决定当前施工顺序。

## 1. 目标与边界

本计划只服务于猫猫本人单用户自用：

```text
RikkaHub Android
    ↕ 已认证 WSS
Gateway
    ↕ 私有 Unix socket
Worker + 官方 Claude Code
    ↕ native MCP
thin rikkahub-bridge
    ↕ 已认证通道
Android ToolRuntime / McpManager
```

核心原则：

- Claude Pro/Max credential 只存在于 VPS 官方 Claude Code 环境。
- Android 继续持有并使用自己的 MCP OAuth/token。
- Android RikkaHub 是工具权限、审批与实际执行的唯一 authority。
- Claude Code 只获得当前 assistant 的冻结工具目录，不获得无关内置工具。
- 不建设第二套 Anthropic `tool_use/tool_result` 协议。
- 用户未发消息且没有该消息触发的必要 tool loop 时，模型 invocation 必须为 0。
- 网络恢复只恢复连接或查询 receipt，不重新 generation。
- 模型失败必须向用户显示，不自动消耗第二次请求。
- 不使用 heartbeat、dummy prompt、cache warm 或定时模型请求。

每阶段完成后必须提交独立报告并停止，等待 Codex review 和猫猫批准。不得自动进入下一阶段。

## 2. 当前已完成基线

### Android 仓库

已具备：

- ClaudeP Provider 基础实现。
- WSS transport、签名 hello、序号、stream、cancel、replay。
- QR pairing、P-256 device key、加密 credential、撤销与 cleanup tombstone。
- Provider DI/Koin 真机验证。
- App 编译、单元测试、managed-device instrumentation 和猫猫真机启动复验。
- RikkaHub 现有 LocalTools、ToolExecutionGate、DefaultToolRuntime、approval UI、McpManager。
- 当前 assistant 工具过滤及稳定工具排序能力。

尚未具备：

- 与新 Gateway 的真实配对和聊天闭环。
- thin MCP bridge adapter。
- Claude session 的稳定 new/resume 映射。
- Local/MCP 工具在 ClaudeP Provider 下的真实闭环。

### Server 仓库

已具备：

- Gateway、WSS 鉴权、流转发、cancel、receipt、replay 和请求幂等。
- Gateway/Worker 隔离与 Unix socket IPC。
- Worker binary 路径、版本、摘要、argv、env、cwd 门禁。
- stream-json 解析及子进程生命周期管理。
- dispatch ledger、session store、崩溃后 indeterminate/fail-closed。
- fake Claude executable 的本地和 Linux CI 验证。
- 单 Worker 架构边界已有明确记录。

尚未具备：

- socket ownership R2 的正式收口。
- 官方 Claude Code 2.1.236 的兼容性终验。
- native MCP thin bridge。
- 真实 Claude session continuation。
- 与 Android ToolRuntime 的工具调用闭环。

### 保留的历史资料

旧 `claudep/` 文档、CP1-A、CP1-B、C1、C2-A 报告和 CI 证据继续保留，作为设计来源及历史验证证据。

它们不再决定当前施工顺序，也不得因为旧 Gate 未完成而把已删除的产品化能力重新带回 MVP。

## 3. 从当前 scope 删除或延后

### DELETE_FROM_SCOPE

- 多用户与租户隔离。
- 账号池、账号轮换和并发额度调度。
- 多设备 fleet 管理。
- 跨设备 Claude session 归属。
- 第二套 Anthropic tool protocol。
- 附件上传与多模态架构。
- 独立 staging 数据库。
- 通用公开 Gateway 产品能力。
- 自动恢复或自动重跑失败的 generation。

### DEFER

- access credential 自动 refresh；MVP 可通过安全的手动重新配对恢复。
- 完整设备撤销管理界面。
- 独立 staging 子域名和正式发布拓扑。
- 长期历史迁移、session 搬迁和复杂分支合并。
- cache 命中率仪表盘。
- 附件、图像及文件工具。
- 水平扩容与多 Worker。
- 自动 crash recovery；MVP 只要求 interrupted/fail-closed。

## 4. 剩余实施阶段

### M0 — 基线收口

#### DS 实施内容

仅做收口，不增加业务能力：

1. 审计 Server 当前未提交的 socket ownership R2 修改。
2. 仅收口当前已有的 R2 修改，使第二个 Worker 无法夺取活跃 socket。
3. 不借机新增 systemd 高可用、socket activation、watchdog、lease、额外 IPC 层或其他基础设施。
4. 确认单实例部署约束：
   - 一个 Worker unit；
   - 不允许滚动重叠启动；
   - 不共享 state/work/socket 路径。
5. 固定 Android 与 Server 两个仓库的可复现 HEAD。
6. 更新短报告，明确哪些证据属于 fake CLI、哪些仍未验证。
7. 保留旧文档，不重写历史结论。

#### Codex Review

- 检查现有 dirty worktree，确认没有把未提交代码冒充基线。
- 检查 socket ownership 是否真正由当前 R2 代码或部署门禁保证。
- 检查没有新增协议、功能、依赖或基础设施范围。
- 检查两个仓库的 HEAD、祖先链、工作区及远端状态。
- 检查 Linux CI 中无 skipped 测试。

#### 完成条件

- 两仓工作区洁净。
- Server socket ownership 测试在 Linux 实际执行并通过。
- 第二实例无法夺取活跃 socket。
- 现有 C1/C2-A 回归全绿。
- 无 Claude 模型调用。
- 提交基线报告后停止等待 review。

### M1 — Claude CLI compatibility gate

这一阶段只确认固定 Claude Code 是否满足 MVP，不实现业务功能。

#### DS 实施内容

对 VPS 上固定的 Claude Code `2.1.236` 做分层验证。

##### Phase A：零模型门禁

验证：

- binary 路径、版本和 SHA-256。
- 官方登录状态只读检查，不输出 credential。
- 当前既有 proxy env 可被 Worker 白名单继承，不输出其值。
- `-p`、stream-json、`--resume`、`--mcp-config`、`--strict-mcp-config`、`--tools`、`--allowedTools` 的实际 argv 接受情况。
- built-in tools 可被完全排除。
- MCP config 能严格限制为唯一测试 bridge。
- setting sources、cwd、HOME 和 Claude config 目录边界。
- 测试 bridge 不含业务工具、不访问 Android。

##### Phase B：必要时的最小真实验证

只有零模型手段无法证明以下行为时，先停止并申请猫猫明确的模型调用次数：

- stream-json 的真实事件集合。
- session ID 的产生与 `--resume`。
- native MCP tool result 是否回到同一次 generation。
- CLI 是否存在不可控的自动模型重试。
- 代理出口下的真实官方 Claude Code 行为。

不得把“计划允许最小验证”视为模型授权。

#### Codex Review

- 核对实际 VPS 版本，不以新版文档替代固定版本行为。
- 检查所有参数均来自封闭 argv builder。
- 检查无危险权限参数。
- 检查无 API key、Cookie、OAuth 模拟或 credential 输出。
- 检查 proxy 只复用既有配置。
- 检查模型调用计数与授权严格一致。
- 检查失败后没有自动 retry。

#### 完成条件

必须得到明确结论：

- 固定版本支持所需 stream-json、resume 和 native MCP 组合；或
- 存在具体、可复现的阻断点。

若存在阻断，停止重新评估 MVP，不进入 M2。

若通过，提交兼容性矩阵和零 Secret 证据后停止等待 review。

### M2 — thin MCP bridge + Android ToolRuntime adapter

原 M2 与 M3 合并为一个纵向阶段。理由：两端共享同一 catalog/invoke/result/cancel/query 契约，分开完成容易产生两套不兼容实现。

#### DS 实施内容

##### VPS

实现最小 `rikkahub-bridge` MCP server：

- 接收当前 generation 的冻结工具 catalog。
- 只注册 catalog 中的工具。
- 将 native MCP invocation 转发到已认证 Android 连接。
- 等待 Android 返回批准、拒绝、结果或失败。
- 将结果返回同一次 Claude generation。
- 支持 cancel。
- `query` 仅用于按 `toolCallId` 查询既有执行结果、支持幂等恢复。
- 不建设通用 Tool Job/Task API、任务队列或工具历史查询平台。
- Android 断线立即 fail-closed。
- 相同 `toolCallId + argsDigest` 只执行一次。
- 不保存 Android MCP token。
- 不实现 ToolRuntime、权限判断或业务工具。

Worker argv：

- 只开放精确的 `mcp__rikkahub_bridge__*`。
- 禁止 Bash、Edit、filesystem、Web 等无关工具。
- 使用严格 MCP config。
- 不增加危险权限参数。

##### Android

增加薄 adapter：

- 从现有 assistant allowlist 构建最终工具集合。
- 复用 `stableProviderToolOrder`。
- 冻结 name、description、schema、schema digest 和审批属性。
- 把 bridge invocation 映射到现有：
  - `LocalTools`
  - `ToolExecutionGate`
  - `DefaultToolRuntime`
  - `McpManager`
- 复用既有 approval UI。
- 工具来源仍按真实 LocalChat 上下文处理。
- MCP OAuth/token 只在 Android 使用。
- 将执行结果、拒绝或错误返回 bridge。
- cancel 同时作用于待审批和正在执行的工具。

#### Codex Review

- 确认没有第二套 ToolRuntime。
- 确认 VPS 只做转发，不做权限决定。
- 确认 Claude 只能看到当前 assistant 的冻结工具集合。
- 确认写工具没有伪装为 read-only。
- 确认 MCP token、OAuth grant、Secret 不进入 VPS、日志或协议。
- 确认 toolCallId、generation、conversation、assistant、schema 和参数绑定。
- 确认参数变化、重复调用、错 generation 和断线均 fail-closed。
- 确认结果回到同一次 native MCP generation。
- 确认 cancel 不产生新的 generation。
- 确认 `query` 没有扩张成通用任务系统。

#### 完成条件

零模型/fake CLI 自动化必须覆盖：

- 工具 catalog 精确过滤及稳定排序。
- 未授权工具不可见。
- 只读 Local Tool 成功。
- 写 Local Tool 进入既有审批；拒绝时不执行。
- 批准后恰好执行一次。
- Android MCP Tool 由 `McpManager` 执行。
- VPS 无 MCP token。
- 断线、cancel、重复调用、参数变化、超时均 fail-closed。
- 其他 Claude 内置工具为 0。

完成后停止，不进行真实模型调用，等待 review。

### M3 — session continuation + CACHE_SAFE_DESIGN

#### DS 实施内容

1. 建立稳定映射：

   `conversationId + branchId → Claude sessionId`

2. 首次用户消息：

   - `new`
   - 捕获并持久化 CLI 返回的 session ID

3. 后续用户消息：

   - 使用 `--resume`
   - 只发送本轮新用户内容
   - 不重新注入完整历史

4. 使用最小 config epoch 实现：

   - 将稳定配置计算为一个 config hash，并与 Claude session 绑定。
   - config hash 至少覆盖 Claude binary digest、model、effort/thinking、stable system prompt bytes、tool catalog name/schema/order 和 bridge ABI。
   - 不建设独立 epoch manager、epoch 数据库、迁移机制或复杂生命周期系统。

5. stable system prompt 不包含：

   - 时间戳
   - 随机 ID
   - 在线状态
   - approval 状态
   - 动态 memory recall
   - 当前设备状态

6. volatile memory/context 放入本轮上下文，不改写稳定 prefix。

7. 工具集合或 config hash 改变时：

   - 旧 session 不继续复用
   - 只在下一条用户消息到来时建立新 session
   - 不后台预热

8. resume 失败：

   - 显示用户可见失败
   - 不自动 fallback 到 new
   - 不自动重发历史
   - 由用户决定是否重新发送

#### Codex Review

- 检查 conversation 与 branch 不会串 session。
- 检查 config hash 为最小实现，没有独立 epoch 子系统。
- 检查 resume 不会变成第二次 generation。
- 检查 stable system prompt 的字节稳定性。
- 检查 tool schema 和顺序稳定。
- 检查 model/effort/thinking 不会在同一 session 漂移。
- 检查 volatile context 不污染稳定 prefix。
- 检查无 heartbeat、dummy prompt、prewarm 或后台模型请求。
- 检查 cache usage 只记录真实 CLI 返回值，不推测命中。

#### 完成条件

使用 fake CLI 和零模型自动化证明：

- 第一轮 new、后续轮 resume。
- 同 conversation 不同 branch 不共享 session。
- 相同 config hash 保持相同 prompt/tool bytes。
- config hash 改变只标记下一次用户请求需新 session。
- resume 失败不会产生第二个 spawn。
- 网络重连只恢复 stream/receipt。
- 空闲期间 invocation=0。
- 没有 timer/cron/cache warm。

提交证据后停止等待 review。

### M4 — 一次受控真实端到端验收

这是唯一真实验收阶段。开始前必须由猫猫明确授权可使用的 Claude 模型、用户消息次数和是否允许批准写工具。不得从计划本身推导授权。

#### DS 实施内容

先运行完整零模型 Phase A：

- 两仓 HEAD 与部署 artifact 一致。
- Gateway、Worker、bridge active/ready。
- binary version/hash 正确。
- proxy env 名称存在但值不输出。
- Claude 官方登录状态可用。
- 无 active generation、Claude child 或 pending tool。
- Android 已配对。
- 工具 catalog 与当前 assistant allowlist 一致。
- 无 heartbeat、定时任务或自动 retry 配置。

Phase A 任一不一致立即停止，不消耗模型授权。

Phase A 全绿后，由猫猫在手机完成预先规定的真实流程：

1. 第一轮正常聊天。
2. 第二轮继续同一 conversation/branch，验证 resume。
3. 调用一个只读 Local Tool。
4. 调用一个需人工批准的写 Local Tool，由猫猫亲自批准。
5. 调用一个 Android 已连接的 MCP Tool。
6. 在预定 generation 上测试 cancel。
7. 在不新增用户消息的情况下测试网络中断与恢复。

#### Codex Review

- 每条用户消息对应的 generation 数量。
- 是否存在任何自动第二次调用。
- native MCP tool call 与 ToolRuntime 审计是否一一对应。
- 写工具是否只有一次审批和一次执行。
- MCP token 是否始终留在 Android。
- 第二轮是否真实 resume 同一 session。
- cancel 后无 Claude child 和 pending tool。
- 网络恢复后没有新 generation。
- 空闲观察期间 invocation=0。
- cache 指标只按真实输出记录。

#### 完成条件

全部满足：

- 连续两轮文本和 stream 正常。
- 第二轮确认使用 resume。
- Local read tool 成功。
- Local write tool 经人工审批后恰好执行一次。
- Android MCP Tool 成功且 VPS 无其 OAuth/token。
- cancel 正常收口。
- 网络恢复不重新 generation。
- 模型失败或连接失败无自动 retry。
- 无 heartbeat、dummy prompt 或 cache warm。
- 最终无 active generation、Claude child、pending approval 或工具执行。
- Android、Gateway、Worker、bridge 均处于健康终态。

任一项失败都停止；不得在同轮修复后自动重新调用模型。

## 5. 执行纪律

每阶段 DS 必须：

1. 从经审核的精确 HEAD 开始。
2. 先做只读 preflight。
3. 只修改本阶段授权范围。
4. 明确区分：
   - 实现完成
   - fake 验证完成
   - 真实环境完成
5. 如实记录 skipped、未执行和中间失败。
6. 不把编译成功冒充运行通过。
7. 不把旧历史证据冒充当前 HEAD 证据。
8. 提交后保持工作区洁净。
9. 停在 Codex review 点。
10. 未经猫猫批准不得进入下一阶段、部署或调用模型。

## 6. 阶段总表

| 阶段 | DS 实施内容 | Codex Review 内容 | 完成证据 |
|---|---|---|---|
| M0 基线收口 | 仅收口 socket R2；固定两仓 HEAD；不新增功能或基础设施 | dirty 状态、单实例、祖先链、回归范围、无范围扩张 | 两仓洁净；Linux CI 全绿；第二实例无法夺 socket |
| M1 CLI compatibility | 验证 2.1.236、proxy、stream-json、resume、strict MCP 和工具限制 | 实际版本行为、Secret 边界、调用次数、无危险参数 | 兼容性矩阵；零模型证据；必要时另获授权的最小调用证据 |
| M2 thin bridge + Android adapter | native MCP bridge；catalog/invoke/result/cancel/有限 query；接入现有 ToolRuntime/McpManager | 无第二套 Runtime；权限仍在 Android；幂等与 fail-closed；query 未扩张 | fake/零模型工具闭环；Local read/write、MCP、approval、cancel 测试 |
| M3 session + cache | conversation/branch→session；new/resume；最小 config hash；stable prompt/tools | 无自动 fallback、无预热、prefix 稳定、branch 隔离、无 epoch 子系统 | fake CLI 证明 new→resume；失败无第二次 spawn；空闲 invocation=0 |
| M4 真实验收 | 经明确授权完成聊天、工具、审批、MCP、cancel、断网恢复 | 调用次数、session、审计、token 隔离、最终收口 | 一次受控真实验收报告；所有子项通过；无自动重试 |

当前唯一施工链：

**M0 → M1 → M2 → M3 → M4**

当前只授权 **M0**。M0 完成并经 Codex review 后，必须由猫猫另行决定是否授权 M1。
