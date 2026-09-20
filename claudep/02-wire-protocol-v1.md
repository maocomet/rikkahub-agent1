# 02｜线协议与状态机（v1）

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

