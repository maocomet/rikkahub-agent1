# 04｜RikkaHub 代码接入图

本文以本地基线 `c00f6f3d` 为准，列出后续实现必须检查的接线点。文件位置可能随上游变更移动，实施时应先重新搜索 sealed `when` 和序列化类型。

## 1. 新增核心类型

建议新增：

- `ProviderSetting.ClaudeP`，序列化名 `claude_p`；
- `ClaudePProvider : Provider<ProviderSetting.ClaudeP>`；
- `ClaudePGatewayClient`；
- `ClaudePDeviceCredentialStore`（Android Keystore）；
- `ClaudePModelCatalogRepository`；
- `ClaudePProtocol` DTO 与严格 JSON；
- `ClaudePSessionBindingStore`；
- Phase 3 再新增 `ClaudePToolRelay`；
- Phase 4 再新增 `ClaudePAttachmentUploader`。

不要复用 `ProviderSetting.Claude` 或把 Claude P 塞进 `ProviderSetting.OpenAI`。不要复用 `CodexAccountRepository` 的 Token 数据模型；可以复用其页面布局和账号状态模式。

## 2. Provider 抽象

现有入口：

- `ai/.../provider/Provider.kt`
- `ai/.../provider/ProviderSetting.kt`
- `ai/.../provider/ProviderManager.kt`
- `app/.../di/DataSourceModule.kt`

接入要求：

- `ProviderManager.getProviderByType` 增加穷举分支；
- DI 以专用 OkHttp/WebSocket client 注册 `claude_p`；
- client 禁止自动 redirect 到不同 origin；
- `streamText` 返回冷、单次收集的 Flow，并正确传播 cancellation；
- `generateText` 可复用 stream 聚合，但不能触发第二次远端请求；
- Phase 1 `listModels` 读取 Gateway catalog，不调用模型。

## 3. 设置持久化与 UI

必须搜索并更新所有 `ProviderSetting` 穷举位置，当前至少包括：

- `ProviderConfigure.kt`
- `SettingProviderDetailPage.kt`
- `PreferencesStore.kt`
- `DefaultProviders.kt`
- Provider 导入/导出与同步 importer；
- Secret owner/operation handler；
- background generation host；
- memory/dreaming 的 provider identity；
- Provider connection tester 和 requirements；
- 备份恢复与旧版本 unknown subtype 处理。

`ProviderSetting.ClaudeP` 只保存：

- provider ID、显示名、enabled；
- 规范化 paired origin；
- gateway fingerprint/installation ID；
- device ID；
- 模型目录缓存与时间；
- 非 Secret 能力状态。

私钥进入 Android Keystore；短期 access credential 进入加密 credential store，不进入普通 Settings JSON、二维码导出或 WebDAV 明文备份。

## 4. Codex Provider 可复用与不可复用部分

可复用思路：

- 特殊 built-in Provider 的设置页；
- 多账号/状态卡片的 UI 结构；
- 模型目录映射；
- 流式 `MessageChunk` 映射；
- usage window 展示；
- OAuth/凭证与普通 `ProviderSetting` 分离。

不可复用：

- Codex 的 OpenAI Responses API 请求；
- Codex access token、ChatGPT account ID 和 OAuth endpoints；
- 把 Gateway 当作 OpenAI-compatible API；
- Codex 的 direct-to-provider header 结构。

## 5. Agent 循环与取消

关键文件：

- `app/.../data/ai/ProviderTurnRunner.kt`
- `app/.../data/ai/GenerationRunControl.kt`
- Generation handler 与工具执行器；
- `ai/.../ui/Message.kt`。

要求：

- Provider Flow 取消时发送一次 `generation.cancel` 并有限等待 receipt；
- watchdog 的 retry 不能对已经 dispatch 的 Claude P 请求创建第二次模型调用；Claude P 应通过自己的 receipt/reconnect 策略关闭通用 retry，或提供明确的 pre-dispatch-only retry seam；
- `MessageChunk.terminal` 映射唯一终态；
- Phase 3 `tool.requested` 映射为 `UIMessagePart.Tool`，沿用 `ToolApprovalState` 与 `executionStartedAt` 防重复机制；
- 工具结果回传 Gateway 前必须确认 Generation 仍为同一个 active run。

## 6. MCP 接线

现有手机 MCP 权威：

- `app/.../data/ai/mcp/McpManager.kt`
- `app/.../data/ai/mcp/McpOAuthCoordinator.kt`
- `app/.../data/ai/mcp/transport/*`
- `app/.../data/ai/tools/McpToolExecutionHandle.kt`
- Assistant MCP 选择页与工具 materialization。

Phase 3 不能创建第二套手机 MCP Token store。Claude P tool snapshot 应来自与普通 Provider 相同的最终 materialized tools。执行时仍调用现有 local/MCP executor；`ClaudePToolRelay` 只负责远端 call 与本地 tool execution handle 的关联。

Phase 3 必须解决：

- 工具名称从 RikkaHub 名称映射到唯一 bridge namespace；
- 两个 MCP server 同名工具的冲突；
- schema canonicalization/hash；
- write tool approval；
- 本地取消不等于远程工具已停止；
- 图片工具结果的有界传输；
- 断线后未知副作用不得重跑。

## 7. 后台任务

Claude P 在 Phase 1–4 默认不进入：

- scheduled jobs；
- dreaming/learning；
- sub-agent；
- Telegram 无人值守；
- background provider fallback。

原因是这些路径要求额外的设备离线、权限、attribution、取消和 Secret 可用性契约。所有相关 sealed `when` 对 Claude P 应明确 fail-closed，而不是落入 generic remote provider。

## 8. 网络与隐私

- 单独的 OkHttp client，只允许配对 origin；
- WSS 证书和 origin 校验；
- redirect 关闭；
- 网络日志 interceptor 禁止记录 headers/body；
- Android Network Security Config 生产禁明文；
- 配对 QR 导入前显示 hostname 和 Gateway 指纹；
- Web UI 若支持 Provider，必须使用自己的设备配对，不得从 Android 导出私钥。

## 9. 测试文件建议

- `ClaudePProtocolTest`
- `ClaudePProviderStreamTest`
- `ClaudePProviderCancellationTest`
- `ClaudePDeviceCredentialStoreTest`
- `ClaudePSessionModePlannerTest`
- `ClaudePIdempotencyTest`
- `ClaudePProviderConfigureTest`
- `ClaudePBackupRedactionTest`
- `ClaudePToolRelayTest`（Phase 3）
- `ClaudePAttachmentUploaderTest`（Phase 4）

所有网络测试使用 fake Gateway；真实 Claude 模型只在独立真机 Gate 中按次数授权。

