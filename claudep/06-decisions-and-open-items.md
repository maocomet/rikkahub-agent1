# 06｜决策记录与未决部署参数

## 已冻结决策

### D-001｜Claude Code 不在手机运行

决定：Claude Code 只运行在 CC VPS。

原因：Android/proot 不是目标受支持运行面；后台存活、OAuth、binary 锁定、进程清理和文件权限难以可靠验收。

### D-002｜Claude OAuth 不进入 Android

决定：手机只持有 Gateway 设备身份。

原因：降低凭证外泄面，避免把 Claude CLI 私有认证误当普通 Provider API Key。

### D-003｜独立 Gateway，不直接公开 Worker

决定：公网 Gateway 负责设备协议，Worker 只暴露私有 Unix socket。

原因：Worker 的运行接口不应承担公网认证、重放、配额和多设备隔离。

### D-004｜RikkaHub 历史是权威

决定：Claude session 是可失效的运行状态。

原因：支持手机历史、编辑、分支、备份和迁移；避免服务端 session 丢失导致聊天不可恢复。

### D-005｜现有 MCP 默认由手机拥有

决定：MCP OAuth/Token 和实际执行仍由 `McpManager` 管理，Claude P 通过桥请求。

原因：不复制凭证，复用现有审批和工具 UI，手机本地工具也能采用相同路径。

### D-006｜WSS 从第一阶段启用

决定：不用单向 SSE 作为长期协议。

原因：Phase 3 的工具审批、结果、取消和重连需要可靠双向通道；从 WSS 起步避免协议重写。

### D-007｜不自动重试已 dispatch 的模型请求

决定：先查询 receipt，未知状态交给用户。

原因：防止双计费、双回答和重复工具副作用。

### D-008｜分阶段开启能力

决定：文本、session、工具、附件、发布分别 Gate。

原因：每阶段可独立测试、回滚，避免一次把两个 Agent loop 和多模态耦合。

### D-009｜不复用 Codex 传输

决定：可复用 UI/状态设计，但 Claude P 不走 OpenAI Responses API。

原因：Claude P 的会话、CLI、MCP 和权限语义不同，伪装协议会隐藏关键终态。

### D-010｜后台无人值守默认关闭

决定：Phase 1–4 不进入 schedule/dreaming/sub-agent/Telegram 后台路径。

原因：需要独立的离线设备、权限、attribution 和取消契约。

### D-011｜第一版是单账号自托管，不是公共中转

决定：一个 Gateway installation 只对应一个 Claude 账号所有者，可配对该用户的多个设备。

原因：避免共享订阅、租户串线、凭证共用和条款风险。未来多用户支持必须作为全新的安全与产品 Gate。

### D-012｜遵守 AGPL 与上游产品标识

决定：仓库内派生实现继续遵守 AGPL-3.0，并把 Claude P 标为非官方自托管集成。

原因：Gateway 与客户端协议属于产品功能的一部分，不能通过拆分服务规避许可或让用户误认官方背书。

## 实施前不必确定的部署参数

以下值尚未选择，但不阻塞 CP1 本地 fake 实现：

- Gateway 正式子域名；
- TLS 证书/反向代理的具体部署方式；
- VPS 数据目录的最终绝对路径；
- systemd unit 名称；
- Claude Code 首个锁定版本；
- receipt/session binding 的具体数据库（SQLite/PostgreSQL）；
- quota endpoint 是否在首版展示。

这些参数必须在首次 staging 部署前形成部署 ADR，不能在代码中散落默认值。

## 后续需要用户明确选择的产品项

1. 是否只支持一个自有 Gateway，还是允许用户添加多个自托管 Gateway；建议第一版只支持一个。
2. 删除手机 Provider 时，默认只删除本机配对，还是同时撤销 VPS 设备；建议弹窗分别选择。
3. 多设备是否共享同一 Claude session；建议第一版每设备独立，聊天历史可同步但 session 不共享。
4. 是否展示 Claude 账号邮箱；建议不展示，只显示 `logged_in` 和订阅/额度安全分类。
5. 是否允许服务器端 MCP；建议第一版不允许，未来作为与“手机 MCP”明确分开的高级功能。
6. 附件保留时间；建议终态后尽快删除，失败/断线最长保留短 TTL 供恢复。
7. Gateway 是否单独建仓；建议协议与 Android adapter 留在本仓库，服务端若独立建仓则锁定兼容矩阵并保持许可证说明。

## Phase 0 复审清单

- [x] 产品边界与非目标明确
- [x] 组件和信任边界明确
- [x] Claude/MCP/设备凭证归属明确
- [x] 会话权威与 new/resume/fork/rebuild 规则明确
- [x] v1 双向协议、幂等、终态、取消、重连明确
- [x] 工具桥和附件后置 Gate
- [x] RikkaHub 代码接入面已盘点
- [x] 安全门禁与真实模型授权规则明确
- [x] 每阶段完成条件明确
- [x] 未决项均为部署/产品选择，没有隐藏的架构空洞

结论：可以进入 Gate CP1 的独立实现计划与本地 fake Gateway 开发；不得跳过 CP1/CP2 直接开启工具或附件。
