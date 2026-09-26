# 02｜线协议与状态机（v1）

> **规范修订：`v1-r4`（2026-09-26）。**
>
> **r4 引入 M3 的 continuation 形状，并扩展 request fingerprint 的尾部。** 三件事：
>
> 1. **§5 新增 `mode: "auto"` 与正交字段 `binding_intent`**，把「这一轮是 new 还是 resume」
>    的决定权从客户端移交给 **Worker**。`new` 保留为 legacy 形状且语义不变；
>    `resume` / `fork` / `rebuild` 在 public wire 上**一律拒绝**。
> 2. **§6.1 新增 two-phase `session.bind` / `session.bind.result`**，使 `deferred`
>    generation 产生的 candidate 必须由客户端显式提交一次才能成为可 resume 的 binding。
> 3. **§12.4 的指纹字段序追加两个 present-only 条件尾字段**（`assistant_id`、`binding_intent`）。
>    既有 15 个字段的**顺序、编码与 presence 语义逐字节未变**，因此 v1-r3 语料中
>    每一个不携带新字段的指纹摘要**零漂移**；只有携带新字段的请求才写出新的尾部。
>
> **r4 不改变任何既有线上语义。** envelope、事件归属、错误枚举、取消、重放与 §12 的既有
> framing 均未改动；`fingerprint-minimal` 等既有向量的 `sha256Hex` 在 r4 下与 r3 完全相同，
> 这正是「条件追加」而非「插入」的原因。
>
> 修订标识同时记录在 `claudep/conformance/SPEC_REVISION.json` 的 `spec_revision` 字段，
> 两仓测试都对其漂移 fail-closed。
>
> 历史：r1 为初版；r2 新增 §12 字节级契约，把 transcript 与 request fingerprint 的编码
> 从「只存在于 Android 实现中」提升为规范正文（见 §12.0），不改变语义；r3 修正
> `SPEC_REVISION.json` 的 `*_sha256` 计算方式为 canonical（见 §12.11），不改变被摘要的内容。
> r2 与 r3 均**不改变任何线上语义**；r3 修的缺陷由 CI 实际捕获
> （`corpus revision is bound to the specification it was derived from` 失败）。

## 1. 传输

- 生产：`wss://<paired-origin>/v1/claude-p/stream`；
- WebSocket subprotocol：`rikkahub.claude-p.v1`；
- UTF-8 JSON，每帧一个完整 envelope；
- 单帧、单字段和单 Generation 累计字节均有限制；
- 禁止压缩炸弹、未知二进制帧和无限事件缓冲；
- 客户端与服务端都必须容忍未知可选事件，但不能容忍未知协议主版本。

WSS 从 Phase 1 起使用，避免 Phase 3 为工具结果和审批另建反向通道。

## 2. 公共 envelope

```json
{
  "protocol": "rikkahub.claude-p.v1",
  "type": "generation.start",
  "connection_id": "opaque",
  "request_id": "uuid-v4",
  "generation_id": "uuid-v4",
  "sequence": 1,
  "sent_at": "2026-09-20T00:00:00Z",
  "body": {}
}
```

规则：

- `request_id` 是客户端幂等标识，创建后不可复用到不同请求；
- `generation_id` 由 Gateway 在 accepted 回执中确认；
- `sequence` 在一个连接方向内严格递增；
- 服务端事件包含单调递增的 `event_seq`；
- 日志只记录类型、ID 的短哈希、安全枚举和大小，不记录 body 正文。

## 3. 握手

客户端先发送：

```json
{
  "type": "client.hello",
  "body": {
    "app_version": "string",
    "protocol_versions": ["v1"],
    "device_id": "opaque",
    "nonce": "base64url",
    "signature": "base64url",
    "capabilities": ["text_stream", "cancel", "receipt_query"]
  }
}
```

服务端返回 `server.hello`，冻结：

- 选择的协议版本；
- Gateway build/ABI；
- Worker ABI 与 Claude Code 版本；
- 最大帧、最大 prompt、并发限制；
- 本次连接允许的功能；
- server time 和 connection ID。

版本、签名或设备状态失败时立即关闭，不进入 Provider dispatch。

## 4. 模型目录

`catalog.get` 不调用模型。`catalog.result` 仅返回服务器 allowlist 中的别名：

```json
{
  "models": [
    {
      "alias": "sonnet",
      "display_name": "Claude Sonnet",
      "input": ["text"],
      "features": ["streaming", "reasoning_summary"],
      "enabled": true
    }
  ]
}
```

禁止把 `claude-code-<version>`、任意 CLI 版本或客户端字符串接受为模型 alias。

## 5. 创建 Generation

M3 `immediate` 形状（`remote_branch_id` 在**它原来的位置**，只出现一次）：

```json
{
  "type": "generation.start",
  "request_id": "uuid-v4",
  "body": {
    "remote_thread_id": "uuid-v4",
    "remote_branch_id": "<64 位小写十六进制>",
    "mode": "auto",
    "binding_intent": "immediate",
    "assistant_id": "uuid-v4",
    "model_alias": "sonnet",
    "system_prompt": "...",
    "turn": {
      "role": "user",
      "parts": [{"type": "text", "text": "..."}]
    },
    "rebuild_history": null,
    "tool_snapshot": null,
    "limits": {"max_output_tokens": 4096}
  }
}
```

M3 `deferred` 形状与上面**只有两处不同**：`"binding_intent": "deferred"`，且
`remote_branch_id` **整个键不出现**（不是 `""`，也不是 `null`）。

legacy `new` 形状：`"mode": "new"`，`remote_branch_id` 是原有的必填 opaque value，
**不带** `binding_intent`。

> JSON 对象是**无序**的，因此 `binding_intent` 写在 `mode` 旁边只是可读性选择，
> **不参与任何语义**（§12.8）。指纹里这两个尾字段的顺序由 §12.4 固定为
> 「`attachment_manifest` 之后」，与 body 里的键序**无关**。

### 5.1 `mode` 是**请求形状**，不是 continuation 决定

`mode` 只能为：

- `new`：**legacy**。没有持久 continuation；不读取也不写入 M3 SessionStore；
- `auto`：**由 Worker 解析**。客户端不知道、也不需要知道 binding 是否存在。

`resume`、`fork`、`rebuild` 在 public wire 上**一律拒绝**。

> **客户端不得决定 resume。** Android 看不到 Claude session id，也无法观察 Worker 的
> SessionStore，因此它既不能请求 resume，也不能断言 binding 不存在。`auto` 只表示
> 「这一轮属于一条可延续的 branch」；new 与 resume 由 **Worker** 依据自己的 SessionStore
> 精确查询 binding 决定。

`new` 与 `auto` 是**两个不同的语义，不得合并**：`new` 表示「强制无 continuation」，
`auto` 表示「由 Worker 解析」。客户端不得用 `new` 去表达 `auto` 的意思。

### 5.2 `binding_intent`

`binding_intent` 是**独立的正交字段，不并入 `mode`**。只允许在 `mode: "auto"` 时出现：

- `immediate`：branch 已提交，`remote_branch_id` **必须存在**（64 位小写十六进制）；
- `deferred`：本轮 generation 成功之后才会产生新的 branch variant
  （例如 assistant regenerate），`remote_branch_id` **必须 absent**。

`deferred` 之下 Worker **必须**选择 new 并禁止读取或 resume 该 branch 的任何 binding；
成功后只产生**未提交的 candidate**，等待 `session.bind`。candidate 在 Android 权威消息图
提交之前**不得**成为可 resume 的 binding。

### 5.3 `remote_branch_id` 承载 branch identity（不新增字段）

协议**已经**用 `remote_branch_id` 表达 branch identity，因此 v1-r4 **不新增第二个 branch 字段**。
M3 immediate 请求把 M3-0 的 64 位小写十六进制 canonical selected-variant digest
**直接**作为 `remote_branch_id`：

- **legacy**：`remote_branch_id` 是原有的必填 opaque value，语义与 v1-r3 完全一致；
- **immediate**：`remote_branch_id` 是 64 位小写十六进制 digest；
- **deferred**：`remote_branch_id` **absent** —— 用该字段既有的 presence 语义编码缺失，
  **不得**用空字符串冒充 absent。

该 digest **不是** Claude session id，也不能由它推出 session：它标识的是「哪条 branch」，
而不是「哪次 CLI 会话」。

`deferred` 的「不存在」在指纹里由 `remote_branch_id` **既有的 1 字节 presence 标志**编码
（absent → `0x00`，见 §12.3），因此 absent 与 `""` 得到**不同摘要**。两者不可互换：
`""` 是一个**存在但为空**的 branch identity，在本协议中不是一个合法值。

### 5.4 合法形状（封闭集合）

| # | `mode` | `binding_intent` | `remote_branch_id` | `assistant_id` | 结果 |
|---:|---|---|---|---|---|
| 1 | `new` | absent | 必填（opaque） | 可选 | legacy new，不读写 M3 SessionStore |
| 2 | `auto` | `immediate` | 必须存在（64 hex） | 必须存在 | Worker 解析 new/resume |
| 3 | `auto` | `deferred` | 必须 absent | 必须存在 | Worker 强制 new |
| 4 | `resume` / `fork` / `rebuild` | — | — | — | **拒绝** |
| 5 | `auto` | `immediate` | absent | — | **拒绝** |
| 6 | `auto` | `deferred` | present | — | **拒绝** |
| 7 | `auto` | 任意 | — | absent | **拒绝** |
| 8 | `new` | present | — | — | **拒绝**（legacy 与 M3 形状不得混用） |
| 9 | 任意 | 任意 | — | — | 出现 `claude_session_branch_id` 字段即**拒绝** |

第 9 行是刻意的：`claude_session_branch_id` **不属于 v1-r4**。closed decoder 必须把它当作
**unknown field** 拒绝，而不是忽略——否则一个按旧草案写出的客户端会得到一个看似正常的请求，
而它携带的 branch 语义从未被任何一层读取。

其余规则：

- **任何** Claude session id 都不得出现在 public wire 的任何一个方向；它只存在于 Worker。
- **config hash 不在 public wire 上**。它由 Worker 自行计算，并且只写入 Worker 自己的
  binding 记录；客户端不得提交、也不得信任自己计算的结果。
- 客户端不得为了推断 binding 状态而采样任何 Store；Gateway 也不得代替 Worker 决定
  new/resume，只能做 shape 校验。
- Worker 解析出的 **resolved mode 必须记录在该 generation 的 Worker/ledger 记录中**，
  供审计与 replay 使用。
- **replay 必须复用当时记录的 resolved mode**，不得因为之后 SessionStore 状态变化而重新解析，
  也不得因此重新 spawn。
- 服务端不得根据「看起来像连续对话」这类启发式自行判断延续关系；`auto` 的解析依据是
  一次精确的 binding 查询，不是相似度猜测。
- **Gateway 不得决定 resume。** Gateway 只做形状校验，然后把**未解析**的
  `legacy_new | auto` 连同 `binding_intent` 原样交给 Worker；`new | resume` 的解析
  只发生在 **Worker** 内部，解析结果写入该 generation 的 dispatch ledger 记录。

**部署门槛（过渡期规则，非永久 wire 语义）：** `auto` 的解析要求 Worker 持有 M3
SessionStore。在 SessionStore 落地之前，**Worker 收到 `auto` 必须稳定 fail-closed**——
它**不得**把 `auto` 暂时当作 `new`，也**不得**为此 spawn CLI 或发起模型请求。
把它当作 `new` 会让一个本该 resume 的请求静默地开出一段新会话，而客户端无从察觉；
fail-closed 只是拒绝，不会产生一个错误的会话。

## 6. 服务端事件

最小事件集合：

- `generation.accepted`
- `generation.started`
- `message.started`
- `reasoning.delta`
- `text.delta`
- `tool.requested`（Phase 3）
- `tool.waiting_approval`（Phase 3）
- `tool.completed` / `tool.failed`（Phase 3）
- `usage.updated`
- `generation.completed`
- `generation.cancelled`
- `generation.failed`
- `session.bind.result`（见 §6.1，是对 `session.bind` 的应答）

终态必须且只能出现一次。`completed | cancelled | failed` 后禁止正文、工具或 usage 增量。

### 6.1 会话绑定（two-phase bind）

`deferred` generation 成功后只产生 **Worker 进程内的未提交 candidate**。正式 binding 必须由
客户端显式提交一次 bind 才写入，因此 candidate 在 Android 权威消息图提交之前**不可 resume**。

```json
{
  "type": "session.bind",
  "request_id": "uuid-v4",
  "body": {
    "generation_id": "uuid-v4",
    "remote_thread_id": "uuid-v4",
    "remote_branch_id": "<提交后权威图产生的 64 位小写十六进制 digest>",
    "assistant_id": "uuid-v4"
  }
}
```

```json
{
  "type": "session.bind.result",
  "body": {
    "generation_id": "uuid-v4",
    "state": "bound"
  }
}
```

`state` 是**封闭词表**：

| `state` | 含义 |
|---|---|
| `bound` | candidate 逐字段匹配，正式 binding 已原子写入 |
| `already_bound` | 完全相同的 bind 重送；没有新写入，也没有产生 CLI child |
| `conflict` | 同一 generation 但 branch / assistant / device / conversation / config identity 不一致；不覆盖、不移动旧 binding |
| `candidate_unavailable` | candidate 不存在，或已随 Worker 重启丢失；**不自动重跑 generation** |
| `refused` | 该 generation 不满足 bind 前提（未成功结束／未捕获 session id／并非 deferred） |

规则：

- bind **必须逐字段**匹配 candidate 与原 generation，缺一即拒绝；
- 完全相同的 bind 重送是**幂等**的（`already_bound`）；
- 同一 generation 改动任一身分 → `conflict` 并 fail-closed；
- bind **不产生** CLI child、模型请求或 heartbeat；
- disconnect **不触发**自动 bind、自动 generation 或 heartbeat；
- candidate **有界**：容量满时**拒绝新的 deferred admission**，不得淘汰一个仍可合法 bind 的
  candidate——那会开出一个错误绑定窗口；
- bind 的幂等身分与 generation 身分完全绑定；**bind 不得改写原请求**；
- 不提供 bind-query API、任务队列或 Session Job 系统。恢复手段只有**重送同一条 bind**。

### 错误安全枚举

公开错误仅允许版本化枚举，例如：

- `authentication_required`
- `device_revoked`
- `protocol_mismatch`
- `cli_version_mismatch`
- `model_not_allowed`
- `session_missing`
- `session_conflict`
- `worker_busy`
- `quota_unavailable`
- `timeout`
- `cancelled`
- `stream_interrupted`
- `tool_bridge_unavailable`
- `external_runtime_error`

原始 stderr、OAuth 响应、文件路径、prompt 或工具 Secret 不能进入错误正文。

## 7. 幂等与 dispatch 边界

Gateway 为每个 `request_id` 保存请求指纹和 receipt：

- 相同 ID + 相同规范化请求：返回原 accepted/终态并重放事件；
- 相同 ID + 不同指纹：`idempotency_conflict`；
- dispatch 前失败：客户端可使用新 request ID 重试；
- dispatch 后终态未知：先查 receipt，禁止自动创建第二个模型请求。

请求指纹至少绑定设备、thread、branch、mode、model、system prompt hash、turn/history hash、
tool snapshot hash 和附件 manifest hash。

r4 起，**凡请求携带的字段都必须进入指纹**，否则该字段的变化会被静默当作重放。
因此指纹另外绑定（**条件追加**，见 §12.4）：

- `assistant_id`——仅当请求携带时；
- `binding_intent`——仅当请求携带时。

**解析结果不是指纹的一部分，也不得回写指纹。** `mode: "auto"` 的请求指纹记录的是
`auto` 本身；`new` 还是 `resume` 是 Worker 的**解析结果**，它写入该 generation 的
dispatch ledger 记录，并**原样参与 replay**：重放必须复用当时记录的 resolved mode，
不得因为 SessionStore 之后的变化而重新解析，更不得因此重新 spawn 一个 CLI child。

## 8. 取消

```json
{
  "type": "generation.cancel",
  "request_id": "new-uuid",
  "body": {
    "generation_id": "target",
    "reason": "user_requested"
  }
}
```

- cancel 本身幂等；
- 已终态返回原终态，不新增事件；
- Gateway 先确认目标归属，再通知 Worker；
- Worker 必须终止 Claude 子进程、工具等待和临时目录；
- 收口后 active binding 清空；无法确认子进程终止时返回 `cancel_pending`，不能谎报 cancelled。

## 9. 重连和重放

客户端发送 `stream.resume`，携带 `generation_id` 和 `last_event_seq`。Gateway 只能重放已持久化/有界缓冲事件，不能重新调用模型。

若事件已过期：

- 已有终态 receipt：返回终态及最终安全摘要；
- active：恢复 live stream；
- unknown：返回 `state_unknown`，交由用户决定，不自动 retry。

## 10. 工具协议（Phase 3）

`tool.requested` 绑定：

- device/account；
- generation ID；
- Claude session；
- tool call ID；
- 工具 namespace/name；
- canonical arguments SHA-256；
- tool snapshot SHA-256；
- deadline。

手机响应 `tool.approve_once`、`tool.deny` 或 `tool.result`。写工具必须先有批准事件；批准仅对上述完整绑定生效。参数变化、重复 ID 冲突、过期、撤销或错误设备全部拒绝。

工具结果限制大小和内容类型；超大结果先保存在手机受控存储并返回一次性引用。Gateway 不解析或长期保存完整敏感结果。

## 11. 附件协议（Phase 4）

附件先走独立的分块上传协议，完成后返回一次性 `attachment_handle`。Generation 只引用 handle 与 manifest hash。handle 必须绑定设备、Generation、真实 SHA-256、大小、服务端 MIME、过期时间和 ready 状态。

上传未完成、校验不符、跨 Generation、已过期或非 ready 时必须在模型 dispatch 前失败。

## 12. 字节级契约（transcript 与 request fingerprint）

### 12.0 本节的地位

§3 的握手和 §7 的指纹此前只规定了**绑定哪些字段**，没有规定这些字段的**字节级编码**。
在那之前，这些字节的唯一定义是 Android 实现本身——一个用 Kotlin 之外的语言写服务端的人，
没有任何东西可以据以自检。

本节把这些编码写进规范正文，使第二个实现无需阅读 Kotlin 即可复现，并可对照语料自证。

**本节是补充，不是变更。** 它描述的是**已经冻结、且已被 Gate 7 之前各阶段固定下来的行为**；
若本节文字与现有实现有任何冲突，以**实现与语料**为准，并应视为规范缺陷上报，
**不得**为了让某一方通过而修改协议。

本节内容经三方交叉核对：**现有 Kotlin 实现**（`ai/src/main/java/me/rerere/ai/provider/claudep/`）、
**独立 JVM 复算**（`claudep/conformance/tools/VerifyVectors.java`）、以及
**语料**（`claudep/conformance/`）。三者逐字节一致。

> **诚实的边界**：Kotlin 实现本身**从未被执行过**——本仓库的 Android 测试需要 Android SDK，
> 在产生本节的环境里不可用。所谓「三方核对」中的 Kotlin 一方，是**逐行阅读其源码**
> 并据此写出独立的 JVM 复算程序，再由该程序实际执行。这与「运行了 Kotlin」
> 是两件事，不得混为一谈。真正执行 Kotlin 的是 `ClaudePConformanceCorpusTest`，
> 它已写好但**尚未运行**。

### 12.1 存在两套 framing，且它们**不同**

这是本协议最容易出错的地方，因此先说结论：

| 用于 | framing |
|---|---|
| 握手 transcript、配对 transcript | 十进制 ASCII 计数 + 冒号前缀，计数单位是 **UTF-16 码元** |
| request fingerprint | **4 字节大端**长度前缀，计数单位是 **UTF-8 字节**，值另有 1 字节 presence 标志 |

两者**不可互换**。一个星面字符（emoji）在第一种下计 **2**，在第二种下计 **4 字节**。
写错任何一种都会得到一个「看起来完全正常」的摘要——这正是语料中存在
`handshake-astral-emoji` 与 `fingerprint-unicode` 两个向量的原因。

### 12.2 transcript framing

对每个字段 `v`，写入：

```text
decimal(v.length) + ":" + v
```

- `v.length` 是 **UTF-16 码元**个数（不是码点数，不是 UTF-8 字节数）；
- 十进制 ASCII，**无前导零**（零写作 `0`），**无填充**，随后一个 `:`（U+003A）；
- **字段之间没有其他分隔符**；长度前缀本身就是唯一的边界信息；
- 所有字段按 §12.4 的顺序**直接串接**，得到一个字符串，再把**整个字符串**按 UTF-8 编码；
- 空字符串编码为 `0:`（长度前缀仍然存在，不是省略）。

长度前缀不是装饰：没有它，`("ab","c")` 与 `("a","bc")` 会串接出相同字节，
签名就无法覆盖它声称覆盖的值。

**不做的变换**：不做 trim、不做 Unicode 规范化（NFC/NFD）、不折叠大小写、不加 BOM。

### 12.3 request fingerprint framing

指纹是对一个**标签/值流**取 SHA-256。每个字段依次写入：

1. `len32(标签的 UTF-8 字节)` + 标签的 UTF-8 字节；
2. 然后是值：
   - 值为 **absent 或 null**：写入单个字节 `0x00`，**到此为止**；
   - 值存在：写入单个字节 `0x01`，再写 `len32(值的 UTF-8 字节)` + 值的 UTF-8 字节。

其中 `len32(x)` = `x` 的字节长度，作为 **4 字节大端无符号整数**（高位在前）。

因此 absent 的值贡献 3 字节（标签部分之外），空字符串贡献 4 字节（`0x01` + 四个 `0x00`）。
**两者的摘要必然不同**——这正是 presence 字节存在的理由。

标签本身**永远存在**，`len32` 前缀对标签同样适用——**唯一的例外是 §12.12 定义的条件尾字段**。


### 12.4 完整字段序

字段顺序**是输入的一部分**：交换任意两个标签都会改变摘要。

**握手 transcript**（`rikkahub-claude-p-handshake-v1`）：

| # | 字段 | 值来源 |
|---:|---|---|
| 1 | 域标签 | 字面量 `rikkahub-claude-p-handshake-v1` |
| 2 | deviceId | `client.hello.body.device_id` |
| 3 | nonce | `client.hello.body.nonce` |
| 4 | gatewayAuthority | 客户端实际拨号的 authority |
| 5 | appVersion | `client.hello.body.app_version` |

**配对 transcript**（`rikkahub-claude-p-pairing-v1`）：

| # | 字段 | 值来源 |
|---:|---|---|
| 1 | 域标签 | 字面量 `rikkahub-claude-p-pairing-v1` |
| 2 | origin | 客户端配对时使用的 `https://<authority>` |
| 3 | ticket | 一次性票据明文 |
| 4 | devicePublicKeyBase64Url | base64url 的 X.509 SPKI |
| 5 | state | 每次尝试的随机值 |
| 6 | challenge | 每次尝试的随机值，被 proof 覆盖 |
| 7 | appVersion | 请求体中的 `app_version` |

**request fingerprint**（`rikkahub-claude-p-request-fingerprint-v1`），标签逐字如下：

| # | 标签 | 说明 |
|---:|---|---|
| 1 | `domain` | 字面量 `rikkahub-claude-p-request-fingerprint-v1` |
| 2 | `device_id` | |
| 3 | `remote_thread_id` | |
| 4 | `remote_branch_id` | |
| 5 | `mode` | |
| 6 | `model_alias` | |
| 7 | `system_prompt` | **可 absent**，见 §12.5 |
| 8 | `turn_role` | |
| 9 | `turn_parts` | 部分数量的**十进制字符串**（不是数字） |
| 10 | `turn_part_{i}.type`、`turn_part_{i}.text` | `i` 从 **0** 开始，逐部分 |
| 11 | `rebuild_history_turns` | 历史轮数的**十进制字符串** |
| 12 | `rebuild_{i}.role` | `i` 从 **0** 开始 |
| 13 | `rebuild_{i}.{j}.type`、`rebuild_{i}.{j}.text` | 轮内部分，`j` 从 **0** 开始 |
| 14 | `tool_snapshot` | **可 absent** |
| 15 | `attachment_manifest` | **可 absent** |
| 16 | `assistant_id` | **条件追加**，见 §12.12 |
| 17 | `binding_intent` | **条件追加**，见 §12.12 |

注意第 12 与第 13 的顺序：每一轮先写 `role`，再写该轮的全部部分。

**第 16、17 项的位置与第 1–15 项的性质不同。** 前 15 项是**定长字段序**：每一项都**必须**
写出（值为 absent 时写 `0x00`）。第 16、17 项是**条件尾字段**：只在请求携带该字段时才写，
不携带时**整个字段块（标签 + 长度前缀 + presence 字节）都不出现**。

> 因此 `"assistant_id" 会不会写到别的位置" 是一个有唯一答案的问题：**不会**。
> 它只能追加在 `attachment_manifest` 之后，且只能是第 16 项。把一个尾字段插进前 15 项
> 之间会改变**每一个**携带该字段的请求的摘要，而它的目的恰恰是**不改变**任何既有摘要。

r4 之后的请求可能长这样（`assistant_id` 与 `binding_intent` 都在，`remote_branch_id` absent）：

```text
… len32("attachment_manifest") 0x00
  len32("assistant_id")        0x01 len32("uuid-v4") "uuid-v4"
  len32("binding_intent")      0x01 len32("deferred") "deferred"
```

而一个 legacy 请求到最后一行之前就结束了：

```text
… len32("attachment_manifest") 0x01 len32("<opaque>") "<opaque>"
```

两种写法**没有任何一个字节是共用的**，这正是「存在但为空」与「不存在」不会被混淆的原因。

### 12.5 absent / null / 空字符串

| 情形 | fingerprint 编码 | transcript 编码 |
|---|---|---|
| 字段 absent | presence `0x00` | 不适用——transcript 的每个字段都必须是字符串 |
| 字段为 `null` | presence `0x00` | 不适用 |
| 字段为 `""` | presence `0x01` + `len32(0)` = 四个 `0x00` | `0:` |

**absent 与 `null` 在 fingerprint 中编码相同**（都写 `0x00`）。
这是刻意的：两者的语义都是「没有这个值」。

`system_prompt` 的 absent 与空串**必须**产生不同摘要——否则客户端可以用同一个
`request_id` 把一个请求当作另一个重放，而这正是指纹要阻止的事情。

本节描述的是 §12.4 第 1–15 项的**定长字段**。第 16、17 项是条件尾字段：它们**不遵循**
「标签永远存在」这一条，absent 时写出的是**零个字节**而不是 `0x00`。区别是刻意的——
定长字段的 absent 必须与「空值」区分，而条件尾字段的 absent 必须与 **r3 的整个字节流**
区分。见 §12.12。

### 12.6 字符串与 UTF-8

- 字符串按 **UTF-8** 编码，无 BOM；
- **不做 Unicode 规范化**：视觉相同但规范化形式不同的文本（NFC vs NFD）产生不同字节，
  因而产生不同签名。发送方必须原样发送它要签名的字节；
- **不做 trim、不做大小写折叠**；
  （注意：§2 的 envelope 解析对 `protocol` 和 `type` 做 trim，那是**路由**规则，
  与本节无关——transcript 的字段值不被 trim。）
- 未配对的代理项（ill-formed UTF-16）按 U+FFFD 替换编码。
  Kotlin、JavaScript 与 JVM 在此行为一致，但它是实现细节，不应被依赖。

### 12.7 SHA-256

- 输入是 §12.3 描述的**完整字节流**，无一字节遗漏，无额外前缀或分隔；
- 输出是 **64 个字符的小写十六进制**，没有 `0x`、没有 base64、没有分隔符；
- 摘要本身即指纹值，直接进入 §7 的幂等判定。

### 12.8 JSON 对象字段顺序：哪里**不能**依赖，哪里无所谓

这条容易搞反，明确写清：

- **路由不得依赖 JSON 对象键顺序。** JSON 对象是无序的；帧里键的顺序对 envelope 解析、
  事件类型判定、拒绝原因**没有任何影响**。语料中的
  `order-insensitive-fields-reordered` 与 `order-sensitive-envelope-protocol-first`
  是一对内容相同、键序不同的向量，用来钉死这一点。
- **但 transcript 与 fingerprint 的字段序必须固定。** 它们的字节流是**按名字提取字段、
  再按 §12.4 的固定顺序串接**得到的——不是对原始帧字节做的变换。
  因此帧里的键序**不影响**摘要；**字段名的顺序**（即 §12.4）**决定**摘要。

一句话：**键序不参与语义；字段序是语义的一部分。**

### 12.9 必须逐字节保留的原始帧

**没有任何协议值是对原始 JSON 帧字节计算的**——transcript 与 fingerprint 都来自提取后的字段值。

**但语料中的 `frames/envelope.json` 的 `raw` 字段必须逐字节保留**，原因与协议语义无关，
而在于**测试的有效性**：这些字符串是被原样喂给解析器的输入，
重新序列化会改变被测对象。例如 `malformed-json` 一旦被重新格式化就成了合法 JSON，
一个「拒绝」用例会静默变成「接受」用例。

因此：**vendor 语料时不得重新格式化、重新序列化或重新缩进任何 `raw` 字符串。**
`MANIFEST.sha256` 是这条要求的机器强制手段。

### 12.10 参考与语料

| 产物 | 位置 |
|---|---|
| 语料（权威） | `claudep/conformance/` |
| 生成与复算工具 | `claudep/conformance/tools/` |
| Kotlin 实现 | `ai/src/main/java/me/rerere/ai/provider/claudep/` |
| 规范修订标识 | `claudep/conformance/SPEC_REVISION.json` |

实现方应以语料为验收依据：语料通过即编码正确，语料不通过即编码错误。
**不得**通过修改语料来迁就实现。

### 12.11 规范摘要（canonical spec digest）

`claudep/conformance/SPEC_REVISION.json` 记录的 `protocol_spec_sha256` 与
`trust_boundaries_sha256` 让读者能判断：手上这份语料描述的是不是眼前这份文档。

**这两个摘要按 canonical 形式计算，而不是按某个检出的原始字节。** 规则如下：

| # | 步骤 |
|---:|---|
| 1 | 按 **UTF-8 严格解码**；非法 UTF-8 **拒绝**（fail-closed），不得用 U+FFFD 替换 |
| 2 | 把所有 `CRLF` 规范化为 `LF` |
| 3 | **裸 `CR`（其后不跟 `LF`）拒绝**（fail-closed） |
| 4 | **除此之外不做任何变换**：不 trim、不去 BOM、不重排、不折叠空行、不做 Unicode 规范化 |
| 5 | 对规范化后文本的 UTF-8 字节计算 **SHA-256**，小写十六进制 |

**为什么必须有这条规则。** 早期版本直接哈希工作区原始字节。该值随后取决于
**运行生成器的那台机器**：`core.autocrlf=true` 会把文本检出为 CRLF，于是 Windows 上
记录的是 CRLF 形式的摘要，而 CI 检出 LF、重算 LF 形式的摘要，**任何 LF 检出上都
不可能匹配**。这不是容差问题——一个随检出变化的哈希不是文档的哈希，而是某台机器
渲染结果的哈希。

**两条刻意的推论**（由 `tools/selftest.mjs` 断言）：

- 同一文档的 LF 形式与 CRLF 形式产生**相同**摘要；
- **增删末尾换行会改变摘要**——它是真实内容变化，不是排版细节。

**`.gitattributes` 是工作树防护，不是正确性来源。** 仓库为语料与规范文件声明 `eol=lf`，
使原始字节在检出时稳定；但 `*_sha256` 的正确性**不依赖**该声明，即使在 CRLF 工作区
重新生成也得到同一摘要。

**`MANIFEST.sha256` 不适用本规则**：它钉的是语料文件的**逐字节**内容，由 `sha256sum -c`
原样校验，因此保持原始字节哈希；其跨平台稳定性由 `.gitattributes` 的 `eol=lf` 提供。

规范摘要规则只定义**如何计算哈希**，不定义文档内容；每次给该规则增加例外，
都是一种让两份不同文档共享同一摘要的方式。

### 12.12 条件尾字段（r4 新增）

r4 给指纹追加两个字段：`assistant_id`（第 16 项）与 `binding_intent`（第 17 项）。
它们**只能追加在末尾**，且**只在携带时写出**。

**规则。**

| 条件 | 写出的字节 |
|---|---|
| 请求携带该字段 | `len32(标签)` + 标签 + `0x01` + `len32(值)` + 值 |
| 请求不携带该字段 | **什么都不写**（零字节） |

顺序固定为 `assistant_id` 在 `binding_intent` 之前。两者都携带时，`assistant_id` 先写。

**为什么是「追加」而不是「插入」。** 字段序是输入的一部分，因此把一个字段插进第 1–15 项
之间，会改变**每一个**携带该字段的请求的摘要——包括那些在 r3 下已经冻结、已经写进
dispatch ledger 的请求。追加则相反：一个不携带新字段的请求写出的字节流与 r3 **完全相同**，
它的摘要**一个 bit 都不变**。语料里 `fingerprint-minimal` 等 v1-r3 向量在 r4 下摘要不变，
就是这条规则的验收依据；那 7 个摘要**必须**保持原值，任何变化都说明实现把字段插错了位置。

**为什么 absent 是零字节，而不是 `0x00`。** §12.5 的定长字段用 `0x00` 表示 absent，
因为那里必须区分「没有值」与「空值」这两个**同类**情形——两者都可能有意义。
条件尾字段要区分的是另一对情形：**「这个请求没有携带该字段」与「这个请求携带了它」**。
它要保证的是 r3 字节流的**逐字节不变**：只要写一个 `0x00`，r3 的字节流就会被追加一个字节，
v1-r3 的每一个摘要都会漂移。零字节是唯一能做到「不携带 == 与 r3 相同」的写法。

**为什么 `remote_branch_id` 不是尾字段。** `deferred` 请求里 branch 是 absent 的，但
`remote_branch_id` **留在原位（第 4 项）**，用既有的 presence 字节编码 absent。
不新增第二个 branch 字段，也不把第 4 项挪到尾部：挪动一个既有字段会改变**所有**请求的摘要，
而 branch 是**所有**请求都要绑定的东西。`assistant_id` 与 `binding_intent` 是唯一两个例外，
因为它们是 r4 才存在的**可选**字段。

**验收向量。** 语料中以下向量专门钉住本节：

| 向量 | 钉住的性质 |
|---|---|
| `fingerprint-minimal` 等 7 个 v1-r3 向量 | 不带尾字段时摘要**零漂移** |
| `fingerprint-auto-immediate` | `auto` / `immediate` / branch present / assistant present |
| `fingerprint-auto-deferred` | `auto` / `deferred` / branch **absent** / assistant present |
| `fingerprint-auto-branch-absent` | 仅 branch 的 presence 不同 → 摘要必须不同 |
| `fingerprint-auto-second-branch` | 仅 branch 的值不同 → 摘要必须不同 |
| `fingerprint-auto-assistant-changed` | 仅 `assistant_id` 不同 → 摘要必须不同 |
| `fingerprint-auto-intent-changed` | 仅 `binding_intent` 不同 → 摘要必须不同 |

**关于 `fingerprint-auto-branch-absent` 与 `fingerprint-auto-intent-changed` 的诚实说明。**
这两个向量钉的是**编码**，不是**合法形状**：`auto` + `immediate` + branch absent，
以及 `auto` + `deferred` + branch present，都被 §5.4 在第 1 行或第 5、6 行**拒绝**。
把编码与形状分开测是刻意的——如果只测合法形状，§5.4 的拒绝就只是「拒绝了一个恰好
没被编码器覆盖的组合」，而无法证明编码器确实把这几个字段绑了进去。
语料测的是**编码器**；**形状**由 §5.4 的封闭校验器在 wire 层拒绝。两者不可互相替代。

**跨语言一致性的证据边界。** 这 7 个新增向量与 7 个既有向量一样，由
`tools/gen-vectors.mjs` 与 `tools/VerifyVectors.java` **两次独立推导**、逐字节一致后才写入。
Kotlin 一侧由 `ClaudePConformanceCorpusTest` 执行真实实现来核对；Server 一侧 vendored
同一份字节并重算。**两仓各自断言，而不是互相引用**——这正是「逐字节一致」在这里的含义。

