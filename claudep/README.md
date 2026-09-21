# RikkaHub Claude P 集成：Phase 0 设计包

状态：Phase 0 设计冻结；Android 端 CP1-A / CP1-B 已完成（CI + 真机），
**服务端（CP1-C）待用户确认 ADR 后开始**

设计基线：`c00f6f3d`（以提交为准；分支名不作为协议依据）

最后更新：2026-09-21

> Gate CP1 **尚未通过**。本仓库内**不存在** Gateway/Worker 服务端，也未发生过任何模型调用。
> Android 侧的完成范围与证据见 `reports/CP1B-ci-evidence.md` §8。

本目录定义如何把 Claude Code 非交互模式（下称 Claude P）作为 RikkaHub Agent1 的独立 Provider 接入。Phase 0 只冻结架构、协议、安全边界和验收门禁，不包含生产代码、VPS 部署、Claude 登录或模型调用。

## 已冻结的核心结论

1. Claude Code 只运行并登录在用户控制的 CC VPS；Android 不安装 Claude Code，也不持有 Claude OAuth 凭证。
2. Android 通过独立的、可撤销的设备身份连接 Claude P Gateway；Gateway 与 Claude Worker 通过私有 Unix socket 通信。
3. RikkaHub 是可见聊天记录、工具审批和手机/MCP 工具执行的权威；Claude session 只是服务端连续生成状态，不是用户数据的唯一副本。
4. RikkaHub 现有远程 MCP 默认仍由手机直连和授权。Claude P 仅通过一次生成专属的工具桥请求手机执行，不接触 MCP Token。
5. 第一版仅支持文本聊天、流式输出、取消和会话连续性。工具桥、附件和后台任务必须通过后续独立 Gate 才能开启。
6. 连接从第一版起使用双向 WSS 协议，避免以后为工具审批重新设计传输层。
7. 禁止危险 Claude 权限参数、任意内置工具、任意 MCP namespace、自动模型重试和把 CLI 版本字符串当作模型名。

## 文档索引

- [00-范围与产品契约](./00-scope-and-product-contract.md)
- [01-架构与信任边界](./01-architecture-and-trust-boundaries.md)
- [02-线协议与状态机](./02-wire-protocol-v1.md)
- [03-安全、凭证与运维](./03-security-and-operations.md)
- [04-RikkaHub 代码接入图](./04-rikkahub-integration-map.md)
- [05-实施阶段与验收门禁](./05-implementation-gates.md)
- [06-决策记录与未决部署参数](./06-decisions-and-open-items.md)
- [07-设计依据与实施复核清单](./07-sources-and-revalidation.md)
- [08-CP1-C 服务端 ADR：Gateway 与 Worker](./08-cp1c-gateway-worker-adr.md)（2026-09-21 追加，**待用户确认**）
- [CP1-C0 服务端分阶段实施计划](./reports/CP1C0-server-implementation-plan.md)（2026-09-21 追加）

## Phase 0 完成定义

- Provider、Gateway、Worker、MCP、会话、取消、审批和附件的责任归属无冲突。
- 凭证从创建到撤销的边界明确，Claude OAuth 不经过手机或 Gateway API。
- new/resume/fork/rebuild 的选择规则明确，不会同时把手机历史和 Claude session 当作双重权威。
- 线协议具有版本、幂等键、序号、终态、取消和断线恢复语义。
- 后续每一阶段都可独立交付、测试和回滚。
- 所有尚未确定的值均被标为部署参数，而不是在实现中猜测。

满足以上条件后，Phase 1 才允许开始写代码。
