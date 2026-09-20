# 07｜设计依据与实施复核清单

## 1. 本地代码基线

本设计基于 `D:\rikkahub-agent1` 的本地提交 `c00f6f3d`，重点核对：

- `ai/src/main/java/me/rerere/ai/provider/Provider.kt`：Provider 的 models、text、stream、tool 参数接口；
- `ai/src/main/java/me/rerere/ai/provider/ProviderSetting.kt`：sealed Provider 设置与 Codex 特殊类型；
- `ai/src/main/java/me/rerere/ai/provider/ProviderManager.kt`：按设置类型选择 Provider；
- `ai/src/main/java/me/rerere/ai/ui/Message.kt`：`MessageChunk`、工具、审批与 interrupted execution；
- `app/src/main/java/me/rerere/rikkahub/data/codex/*`：非 API-key Provider 的账号、OAuth、catalog、usage 与 streaming 范例；
- `app/src/main/java/me/rerere/rikkahub/data/ai/ProviderTurnRunner.kt`：取消、watchdog、retry 与 dispatch observer；
- `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpManager.kt`：手机端 MCP Client、OAuth、tools/list、tools/call 与重连；
- `app/src/main/java/me/rerere/rikkahub/data/ai/tools/McpToolExecutionHandle.kt`：本地取消与远端停止确认的区别；
- `app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt`：特殊 Provider DI 注册；
- Provider 设置 UI、Preferences、默认 Provider、导入导出、备份、后台任务与 memory identity 中所有 `ProviderSetting` 穷举分支。

实施分支建立后必须再次搜索 `ProviderSetting.Codex`、`when (provider)`、`getProviderByType` 和 Provider 序列化名称，避免因上游更新漏接新分支。

## 2. 外部规范入口

实施时只以当时的官方文档和实际锁定版本为准：

- Claude Code 安装、支持平台与版本管理：<https://code.claude.com/docs/en/setup>
- Claude Code CLI reference：<https://code.claude.com/docs/en/cli-reference>
- Claude Code headless/automation：<https://code.claude.com/docs/en/headless>
- Claude Agent SDK streaming output：<https://platform.claude.com/docs/en/agent-sdk/streaming-output>
- MCP Authorization：<https://modelcontextprotocol.io/specification/2025-06-18/basic/authorization>
- MCP Lifecycle：<https://modelcontextprotocol.io/specification/2025-06-18/basic/lifecycle>
- MCP Tools：<https://modelcontextprotocol.io/specification/2025-06-18/server/tools>
- RikkaHub Agent1 许可证：仓库根目录 `LICENSE`。

链接仅是复核入口，不代表未来版本仍保持相同参数或事件结构。

## 3. CP1 开始前必须重新验证

- Claude Code 当前稳定版本、目标 VPS 架构和 binary SHA-256；
- `-p`、stream-json 输入/输出、partial messages、model、resume/fork/session 参数；
- `--tools`、`--allowedTools`、`--setting-sources`、`--disable-slash-commands`、`--strict-mcp-config`、`--mcp-config` 与 permission mode 的实际语义；
- Claude auth status 的安全字段和退出码；
- CLI 是否产生新的自动更新、配置源或默认工具行为；
- stream-json event schema 和未知事件策略；
- RikkaHub 当前 Provider/Message/MCP/backup schema；
- Android 当前最低 API、Keystore 算法与 WebSocket 库能力；
- Claude Code 与订阅的当前使用条款。

任何一项与本设计假设不符时，先更新 ADR 和测试，不能用兼容猜测继续。

## 4. 证据等级

后续报告必须区分：

1. 静态代码复审；
2. 单元/fake 协议测试；
3. 隔离 Gateway/Worker 零模型测试；
4. 锁定 CLI 的本地 fake runtime；
5. 经用户逐次授权的真实模型 smoke；
6. 实体手机跨网络真机闭环；
7. 长时间观察与发布证据。

较低等级不能替代较高等级。真实模型成功也不能覆盖自动化、安全边界或失败路径缺失。

