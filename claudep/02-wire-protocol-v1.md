# 02｜线协议与状态机（v1）

> **规范修订：`v1-r2`（2026-09-22）。**
> r2 相对 r1 只做**补充**：新增 §12 字节级契约，把 transcript 与 request fingerprint
> 的编码从「只存在于 Android 实现中」提升为规范正文（见 §12.0）。
> **不改变任何既有语义**——新增文字描述的是已经冻结、且已被语料固定的行为。
> 修订标识同时记录在 `claudep/conformance/SPEC_REVISION.json` 的 `spec_revision` 字段，
> 两仓测试都对其漂移 fail-closed。

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

```json
{
  "type": "generation.start",
  "request_id": "uuid-v4",
  "body": {
    "remote_thread_id": "uuid-v4",
    "remote_branch_id": "uuid-v4",
    "mode": "new",
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

`mode` 只能为：

- `new`：没有合法 binding；
- `resume`：必须命中相同 thread/branch 的 idle binding；
- `fork`：必须提供已完成的 source binding 与新的 branch ID；
- `rebuild`：session 不可恢复时，由 Android 提供经过预算限制的权威历史。

服务端不得根据“看起来像连续对话”自行把 new 改为 resume。

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

终态必须且只能出现一次。`completed | cancelled | failed` 后禁止正文、工具或 usage 增量。

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

请求指纹至少绑定设备、thread、branch、mode、model、system prompt hash、turn/history hash、tool snapshot hash 和附件 manifest hash。

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

标签本身**永远存在**，`len32` 前缀对标签同样适用。

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

注意第 12 与第 13 的顺序：每一轮先写 `role`，再写该轮的全部部分。

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

