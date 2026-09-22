# CP1-C0｜服务端分阶段实施计划（CP1-C1 → CP1-C4）

状态：**计划冻结，未开始实现**（**待按 2026-09-21 第二轮纠正审计复核**）
日期：2026-09-21
上位决策：`claudep/08-cp1c-gateway-worker-adr.md`
本轮模型调用数：**0**；服务端代码行数：**0**

> 本文件只定义文件级任务与验收标准。**CP1-C0 不写任何 Gateway/Worker 生产代码。**

> **⚠ 2026-09-21 第二轮纠正审计的交叉引用**
> `08` §1.2/§2 中「檐岚没有 Claude Code Worker / 没有 Unix socket」为**事实错误**
> （原取证对象 `D:\yannan` 停在 Gate 6）。
> 檐岚**已有**一个经 Gate 7 真实验收的 CC Worker，其中相当一部分执行逻辑**可以复用**。
> 详见 `claudep/reports/CP1C0-correction-audit-yanlan-worker-reuse.md`。
>
> **对本计划的影响（尚未落地为修订，需用户先确认）**：
> - §3.2 与 §4.2 中 C1-22 / C2-1…C2-15 的**从零实现**假设可能需要改为「**抽取 + 裁剪**」，
>   以免违反推荐架构的优先级第 3 条（不重复实现已验证逻辑）；
> - C2-3 的 `preflight` **必须补 SHA-256**（檐岚未实现，不可照抄）；
> - C2-4/C2-5 的 argv builder **必须**在结构上不存在 MCP 分支，且补 `--disallowedTools`；
> - C2-6 的 env 白名单**必须**补 `CLAUDE_CODE_DISABLE_AUTO_MEMORY`、`DISABLE_TELEMETRY`、
>   `DISABLE_ERROR_REPORTING`、`CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC`；
> - `src/worker/` 中**不得**引入任何 PostgreSQL / Drizzle 依赖
>   （檐岚的 `src/cc-worker/session-coordinator.ts` 即因依赖 DB 而**不可**整体复用）。
>
> **在上述修订完成前，本计划的 C1/C2 文件级任务不得开工。**
> 停止点不变：仍停在**原 Codex 复审点**。

---

## 0. 为什么要把 CP1-C 拆成四段

Gate CP1（`claudep/05-implementation-gates.md`）对服务端的要求一次跨越了
「协议解析 → 子进程管理 → 真实网络 → 真实模型」四个完全不同的风险类别。
一次性做完，失败时无法区分是解析器错了、argv 错了、还是模型行为不同。

拆分原则：

1. **零模型先行。** C1–C3 全程 `模型调用数 = 0`，C4 才可能产生调用，且必须单独授权。
2. **每段产出可独立回滚的部署点。** C1 结束时就有一个能部署、能起停、能回滚的服务，
   而不是一个跑不起来的半成品。
3. **上一段不绿，不开始下一段。** 特别是 C4：C1–C3 全绿是 C4 的**必要条件**。
4. **每段都有「禁止做的事」清单**，与「要做的事」同等重要。

---

## 1. 阶段总览

| 阶段 | 主题 | 模型调用 | 公网暴露 | 关键产物 |
|---|---|---:|---|---|
| **C1** | 零模型 Gateway 骨架 | 0 | 否（仅本机/staging 内网） | 可部署的 Gateway + fake Worker 适配器 |
| **C2** | 零模型 Worker | 0 | 否 | 固定 binary/argv 的 Worker + fake Claude fixture |
| **C3** | VPS staging 假模型闭环 | 0 | 是（staging 子域） | 真机配对 + 假文本流 + 取消 + 恢复 + 撤销 |
| **C4** | Gate CP1 唯一真实模型验收 | **1 次，需单独授权** | 是（staging 子域） | 固定文本目标的一次性验收记录 |

C1 与 C2 可以并行开发（两者的接口就是私有 Unix socket 协议，先冻结该接口即可），
但**必须分别验收**，且 C3 必须等两者都绿。

---

## 2. 安全硬边界（贯穿 C1–C4，不可协商）

以下十条在整个 CP1-C 期间保持不变。任何一条被"临时放开以验证"都必须视为阶段失败。

| # | 边界 | 落地检查 |
|---|---|---|
| B1 | Android 不持有 Claude OAuth | 仓库内无 OAuth 文件路径、无 token 字段；Android 侧 grep |
| B2 | Gateway 不读取 Claude home | Gateway systemd `ProtectHome=tmpfs`；CI 断言 Gateway 进程无法 stat Claude home |
| B3 | Worker 不监听公网 | Worker 只 `listen()` 一个 Unix socket；断言无 TCP listener |
| B4 | 手机不能传 CLI binary/argv/env/cwd/MCP 配置/socket 路径 | 协议里**根本不存在**这些字段；未知字段被拒 |
| B5 | Phase 1 不启用工具/附件/后台任务/resume-fork | `--tools ""`、空 setting sources、`tool_snapshot=null` 强制拒绝 |
| B6 | dispatch 后不自动模型重试 | Gateway 无 retry 循环；receipt 查询是唯一路径 |
| B7 | Gateway 不是 OpenAI-compatible / Anthropic API 仿真层 | 没有 `/v1/chat/completions`、没有 `/v1/messages` 路由 |
| B8 | 不新增危险 Claude CLI 权限参数 | 单一 argv builder 是纯函数；测试穷举禁用参数 |
| B9 | 不读取或输出真实 Secret | 结构化日志字段白名单；secret marker 扫描 |
| B10 | 不连接生产、不改 DNS、不部署 VPS（C0 期间） | 本轮仅文档 |

---

## 3. CP1-C1：零模型 Gateway 骨架

### 3.1 范围

只实现协议与状态机。**不启动 Claude，不读取 Claude OAuth，不调用模型。**
Worker 侧用一个 fake Unix-socket adapter 代替。

### 3.2 文件级任务

路径以 ADR §4 的仓库布局为准（`claude-p-server/`，见 `08-cp1c-gateway-worker-adr.md`）。

| # | 文件 | 职责 | 备注 |
|---|---|---|---|
| C1-1 | `src/protocol/envelope.ts` | envelope 编解码；`protocol` **无默认值** | 缺失即拒，不允许"假设 v1" |
| C1-2 | `src/protocol/version.ts` | `majorVersionOf` / `acceptsServerProtocolVersion` | 与 Android `ClaudePProtocol` 逐条对齐 |
| C1-3 | `src/protocol/events.ts` | 事件类型常量 + 入站路由表 | 未知**可选事件**忽略；未知**主版本**拒绝 |
| C1-4 | `src/protocol/errors.ts` | 安全错误枚举 + 未知码折叠 | 未知 → `external_runtime_error` |
| C1-5 | `src/protocol/limits.ts` | 单帧/单字段/累计字节上限 | 常量集中，可测 |
| C1-6 | `src/pairing/ticket.ts` | 一次性 ticket 生成/哈希/单次消费 | 只存 SHA-256；TTL ≤ 5 min |
| C1-7 | `src/pairing/transcript.ts` | `rikkahub-claude-p-pairing-v1` 长度前缀转录 | **必须与 Android 字节一致** |
| C1-8 | `src/pairing/service.ts` | `/v1/claude-p/pair` HTTPS 路由 | 状态码与响应体形状冻结 |
| C1-9 | `src/registry/device.ts` | device registry（注册/查询/撤销） | 公钥以 X.509 SPKI 存储 |
| C1-10 | `src/auth/possession.ts` | `client.hello` 签名验证 | transcript 与 Android 一致 |
| C1-11 | `src/auth/access.ts` | 短期 access credential 签发与校验 | 见 ADR §8 存储表 |
| C1-12 | `src/wss/server.ts` | WSS 监听 + subprotocol 协商 | 只接受 `rikkahub.claude-p.v1` |
| C1-13 | `src/wss/hello.ts` | `client.hello` → `server.hello` | 版本/签名/设备状态任一失败即关闭 |
| C1-14 | `src/wss/sequence.ts` | 每方向严格递增序号校验 | 乱序/重放拒绝 |
| C1-15 | `src/idempotency/fingerprint.ts` | **服务端自算**请求指纹 | 见 §3.4 —— 不信任客户端 |
| C1-16 | `src/idempotency/store.ts` | request_id → {fingerprint, receipt} | 同 ID 不同指纹 → `idempotency_conflict` |
| C1-17 | `src/generation/state.ts` | 唯一终态状态机 | `accepted → active → {completed,cancelled,failed}` |
| C1-18 | `src/generation/buffer.ts` | 有界事件缓冲 + `stream.resume` 重放 | TTL + 条数双上限 |
| C1-19 | `src/generation/cancel.ts` | cancel 幂等 + `cancel_pending` | 不能谎报 cancelled |
| C1-20 | `src/generation/receipt.ts` | `receipt.query` 答案 | 只含安全终态，无正文 |
| C1-21 | `src/catalog/catalog.ts` | `catalog.get` / `catalog.result` | 编译期 allowlist；不调用模型 |
| C1-22 | `src/worker/fake-adapter.ts` | **fake** Worker Unix-socket 客户端 | 确定性脚本化事件序列 |
| C1-23 | `src/health/health.ts` | `/healthz`、`/readyz` | 见 ADR §10 |
| C1-24 | `src/log/redact.ts` | 结构化日志 + 字段白名单 | 正文永不进入 |
| C1-25 | `migrations/0001_init.sql` | ADR §8 的最小表集合 | 无正文列 |
| C1-26 | `deploy/claude-p-gateway.service` | systemd unit | ADR §12 |
| C1-27 | `deploy/sudoers.d/claude-p-worker` | （若采用 ADR §6 方案） | 单命令、无通配符 |
| C1-28 | `tests/conformance/*` | 与 Android 共享的一致性语料 | 见 §3.5 |

### 3.3 验收标准（全部必须为真）

**协议正确性**

- [ ] `protocol` 缺失的帧被拒，且**不是**被当作 v1 接受（Android 侧同款回归）；
- [ ] `rikkahub.claude-p.v2` 的帧被拒（主版本不符），即使 `type` 是认识的；
- [ ] 未知 `type`（如 `tool.requested`）被**忽略**，不结束 generation、不产生正文；
- [ ] 未知**可选字段**被容忍（前向兼容）。

**认证与配对**

- [ ] ticket 只能用一次；第二次使用返回稳定拒绝；
- [ ] ticket 过期（>5 min）被拒；
- [ ] 服务端存储中**只有** ticket 的哈希，明文 ticket 不出现在数据库、日志、内存转储断言中；
- [ ] 签名验证覆盖 `deviceId|nonce|gatewayAuthority|appVersion` 且**长度前缀**；
- [ ] 错误签名、错误 nonce、已撤销设备全部导致连接关闭且模型 dispatch 数为 0；
- [ ] 配对成功后 `access_credential` 有明确过期时间，过期后握手失败。

**幂等与终态**

- [ ] 同 `request_id` + 同规范化请求 → 返回原 receipt，**第二次 dispatch 为 0**；
- [ ] 同 `request_id` + 不同请求 → `idempotency_conflict`；
- [ ] 一个 generation 的终态**恰好出现一次**（重放/取消竞争下亦然）；
- [ ] 终态之后不再产生任何 `text.delta` / `reasoning.delta` / `usage.updated`；
- [ ] `stream.resume` **只重放缓冲**，模型 dispatch 计数不增加；
- [ ] 缓冲过期 + 已有终态 → 返回终态；无法证明 → `unknown`，**不自动 retry**。

**取消**

- [ ] cancel 幂等：重复 cancel 返回同一结果，事件数不增加；
- [ ] 已终态的 generation 收到 cancel → 返回原终态；
- [ ] Worker 无法确认子进程终止时返回 `cancel_pending`，**不返回 cancelled**。

**边界与运维**

- [ ] 超限帧、超限 prompt、超限累计字节被拒；
- [ ] `healthz` / `readyz` 语义正确，`readyz` 在 fake Worker 不可达时**不 ready**；
- [ ] 结构化日志中 grep 不到 prompt、回答、ticket、credential；
- [ ] 无任何路由形如 `/v1/chat/completions` 或 `/v1/messages`（B7）。

### 3.4 关键设计点：请求指纹由服务端自算

Android 的 `ClaudePRequestFingerprint.compute` 是**客户端本地**的幂等守卫，
它计算出的指纹**从不上线**（`WssClaudePGatewayClient.startGeneration` 只把指纹用于
本地 `idempotency` map，`ClaudePGenerationStartBody` 里没有指纹字段）。

因此 **Gateway 必须自己从规范化请求重新计算指纹**，字段集合与长度前缀方式与 Android
一致（`rikkahub-claude-p-request-fingerprint-v1` 域标签、每个字段先写标签再写
`presence` 字节再写长度前缀值）。

否则恶意客户端可以用同一个 `request_id` 发送两个不同请求，而 Gateway 无从察觉。

这条必须有一个「与 Android 向量对齐」的测试：用固定输入，断言服务端产出与
`ClaudePRequestFingerprintTest` 中相同或等价的摘要。

### 3.5 一致性语料（conformance corpus）

在 Android 仓库存放语言中立的 JSON 语料（建议 `claudep/conformance/`），
服务端仓库按 SHA-256 清单逐字节 vendored 并校验。至少包含：

- `frames/*.json`：规范帧（envelope + body 原文）；
- `expect/*.json`：每帧的期望路由结果（`event` / `ignored_unknown` / `rejected(<reason>)`）；
- `transcripts/*.json`：`client.hello` 与 pairing 转录的**规范字节**（十六进制）；
- `fingerprints/*.json`：指纹向量（输入字段 → 期望 SHA-256 十六进制）。

两侧测试套件都必须跑这份语料。这是 CP1-C1 的**交付物之一**，不是可选项 ——
它是方案 B（独立仓库）消除 DTO 语义漂移的主要手段（ADR §13）。

### 3.6 C1 明确禁止

- 启动 `claude` 二进制；
- 读取 `~/.claude`、任何 OAuth 文件或 `claude auth status`；
- 任何模型调用或对 Anthropic 端点的出站请求；
- 监听公网（C1 只在 localhost / 内网 IP）；
- 引入 C2 的 argv builder 或 process spawn 能力。

---

## 4. CP1-C2：零模型 Worker

### 4.1 范围

把「固定 binary + 类型化 argv + stream-json 解析 + 生命周期收敛」做出来，
但**不调用真实模型** —— 用 fake Claude fixture 替代子进程输出。

### 4.2 文件级任务

| # | 文件 | 职责 |
|---|---|---|
| C2-1 | `src/worker/server.ts` | 只 `listen()` 私有 Unix socket；`0660` + 专用 group |
| C2-2 | `src/worker/socket-proto.ts` | Gateway ↔ Worker 私有协议（与公网协议**分离**，ADR §7） |
| C2-3 | `src/worker/preflight.ts` | 固定 binary 路径 + `--version` + **SHA-256** 校验；`claude-pin.json` 缺失即拒绝启动 |
| C2-4 | `src/worker/argv.ts` | **单一纯函数** argv builder |
| C2-5 | `src/worker/argv.deny.ts` | 禁用参数黑名单（穷举 + 前缀匹配，见 ADR §11.2.2） |
| C2-6 | `src/worker/env.ts` | 环境变量白名单**构造**（不是继承后删除，见 ADR §11.2.3） |
| C2-7 | `src/worker/spawn.ts` | 无 shell 的参数数组启动 + 经 launcher 降权到 `claude` |
| C2-8 | `src/worker/stream-json.ts` | 受支持事件解析；未知事件安全忽略；畸形即协议失败 |
| C2-9 | `src/worker/result.ts` | **解析 `result` 事件判定终态**（不信退出码，见 ADR §11.2.5） |
| C2-10 | `src/worker/stderr.ts` | 有界 allowlist 分类；**原文不出 Worker** |
| C2-11 | `src/worker/run.ts` | active run 表、唯一终态、cancel |
| C2-12 | `src/worker/recovery.ts` | 启动时收敛孤儿 run；crash 恢复 |
| C2-13 | `src/worker/cwd.ts` | **按 `remote_thread_id` 稳定**的 cwd（见 ADR §11.2.4），Generation 子目录 |
| C2-14 | `src/worker/auth-status.ts` | 只读 `loggedIn`/`authMethod`；丢弃 `email`/`orgId`/`orgName` |
| C2-15 | `src/worker/readiness.ts` | expected/actual binary 版本上报 |
| C2-16 | `fixtures/fake-claude.mjs` | **fake** CLI：产出确定 stream-json |
| C2-17 | `fixtures/planted/` | **证伪用**：可识别标记的 `CLAUDE.md` 与 `.claude/settings.json`（含 `apiKeyHelper`） |
| C2-18 | `tests/argv.test.ts` | argv 快照 + 禁用参数穷举 |
| C2-19 | `tests/setting-sources.test.ts` | ADR §11.2.1 的证伪实验 |
| C2-20 | `tests/result-terminal.test.ts` | 空 stdout + exit 0 等退化为正确终态 |
| C2-21 | `deploy/claude-p-worker.service` | systemd unit（ADR §12） |
| C2-22 | `deploy/sudoers.d/claude-p-worker` | 单命令、无通配符、`env_reset`、`secure_path` |
| C2-23 | `claude-pin.json` | 由 preflight 生成并提交（ADR §11.1） |

### 4.3 验收标准

**argv 与权限**

- [ ] argv builder 是**纯函数**：相同入参 → 逐字节相同输出（快照测试）；
- [ ] 固定形态包含：`-p`、`--input-format stream-json`、`--output-format stream-json`、
      `--verbose`、`--include-partial-messages`、显式 model alias、`--tools ""`、
      空 setting sources、`--disable-slash-commands`、`--permission-mode dontAsk`；
- [ ] **不存在**任何路径让调用方传入 argv 片段、env、cwd、binary 路径、MCP 配置或 socket 路径；
- [ ] 黑名单穷举测试：`--dangerously-skip-permissions`、`--allow-dangerously-skip-permissions`、
      任何 `--dangerously-*`、`--add-dir`、`--settings`、`--plugin-dir`、`--agents`、
      `--append-system-prompt-file`、`--permission-prompt-tool` 在**任何**输入下都不会出现；
- [ ] 模型 alias 只接受编译期 allowlist 中的值；**任何 CLI 版本字符串都不是合法 alias**；
- [ ] env 是**构造**的：断言子进程环境中**不存在** `ANTHROPIC_API_KEY`、
      `ANTHROPIC_AUTH_TOKEN`、`CLAUDE_CODE_OAUTH_TOKEN`、`ANTHROPIC_BASE_URL`；
- [ ] env 中**存在** `DISABLE_AUTOUPDATER=1`、`DISABLE_UPDATES=1`、
      `CLAUDE_CODE_DISABLE_AUTO_MEMORY=1`。

**⚠ `--setting-sources ""` 的证伪实验（ADR §11.2.1，**阻断性**）**

- [ ] 在 Worker 的 cwd 与 `CLAUDE_CONFIG_DIR` 中放置带可识别标记的 `CLAUDE.md` 与
      `.claude/settings.json`（含 `apiKeyHelper` 或 hook）；
- [ ] 断言**未加载**：无标记副作用、无命令执行、无额外上下文出现在输出中；
- [ ] 实验**不通过 ⇒ 不得进入 C3**。若空值被证明是"未设置"，必须改用
      `--bare` / `--safe-mode` 并**重跑同一实验**取得通过证据。

**终态判定**

- [ ] 终态**只**由 `result` 事件决定，**不**由退出码决定；
- [ ] 覆盖退化情形：**空 stdout + exit 0**（已观察到的真实回归形态）必须收敛为
      `failed`，不得误判为 `completed`；
- [ ] `result.subtype` 的 `success` / `error_during_execution` / `error_max_turns` /
      `error_max_budget_usd` 各有对应测试。

**子进程生命周期**

- [ ] 单 Generation 期间 `active_run` 恰好 1；终态后回到 0；
- [ ] `cancel` 后子进程被真正终止（等待退出并回收，不留僵尸）；
- [ ] Worker 被 `SIGKILL` 后重启，孤儿 run 被收敛为
      `completed | cancelled | failed | interrupted` 之一，**不会永远 active**；
- [ ] 每 Generation 的一次性子目录在终态后被删除（含异常路径），
      **且 cwd 本身按 thread 保留**（ADR §11.2.4）；
- [ ] `stderr` 原文不出 Worker：测试断言分类结果的 `toString` 与日志中不含 stderr 字节。

**cwd / session 耦合实测（ADR §11.2.4，**必须在 C2 出结论**）**

- [ ] 实测：同一 `remote_thread_id` 的 cwd 稳定，第二次调用能命中上一次的 session；
- [ ] 实测：`--session-id <自生成 uuid>` 建立、另一进程 `--resume` 命中，是否成立；
- [ ] 若任一不成立，**停止并回到 ADR**：CP2 的 `resume` 语义需要重新设计，
      不得带着未确认的假设进入 CP2。

**preflight**

- [ ] binary 缺失 / 版本不符 / SHA-256 不符时，**模型 invocation 数为 0**，`readyz` 不 ready；
- [ ] `claude-pin.json` 未填写（占位符或空）时服务**拒绝启动**，不退化为"跳过校验"；
- [ ] `claude auth status` 只取 `loggedIn` / `authMethod`；
      断言 `email` / `orgId` / `orgName` **不出现在**日志、事件与存储中；
- [ ] `readyz` 同时报告 expected 与 actual 版本的安全字段。

**fixture 闭环**

- [ ] fake Claude 产出 4 类序列：正常完成、中途取消、协议畸形、**空输出 + exit 0**；
      四类都在 Worker 层得到唯一终态，且畸形/空输出导致正确失败而非静默成功。

### 4.4 C2 明确禁止

- 运行真实 `claude` 二进制对着真实 Anthropic 端点（fixture 必须替换真实 binary）；
- 任何工具、MCP bridge、附件挂载；
- 在 `--tools ""` 之外启用任何内置工具；
- 接受调用方传入的 cwd/env/argv；
- 使用任何 `--dangerously-*` 参数，或把 `ANTHROPIC_API_KEY` / `*_OAUTH_TOKEN` 透传进子进程 env；
- 把 `--setting-sources ""` 当作已验证的封锁手段（见 §4.3 的证伪实验）。

---

## 5. CP1-C3：VPS staging 假模型闭环

### 5.1 范围

第一次把两段合起来放进一个**隔离的** staging 环境，用真机跑通全链路，
但 Worker 仍然跑 fake Claude fixture。**不得调用 Claude/Sonnet。**

### 5.2 隔离要求（重要）

| 项 | 要求 |
|---|---|
| 服务 | 独立的 staging systemd unit 名，与任何生产服务不同名 |
| 数据库 | **独立** database/实例；不得指向生产 DB |
| 子域名 | 独立 staging 子域名，独立证书 |
| 管理员账号 | 独立，不复用生产凭证 |
| Claude home | **不存在**（本阶段不安装/不登录 Claude Code） |

### 5.3 验收标准

- [ ] 真机扫码完成**一次真实配对**（这是 Android 侧第一次连到真 Gateway）；
- [ ] `catalog.get` 返回 allowlist，且该调用**不产生任何模型请求**；
- [ ] 握手成功，`server.hello` 的版本/限制字段被 Android 接受；
- [ ] 假文本流按序到达 UI，`text.delta` 顺序与 `event_seq` 一致；
- [ ] **取消**：UI 取消后 Worker 侧 run 归零，终态为 `cancelled`；
- [ ] **断线恢复**：拔网/切网后重连，`stream.resume` 只重放，不产生第二次 dispatch；
- [ ] **撤销**：VPS 侧撤销设备后，该设备的 WSS 与 active Generation 立即失效；
- [ ] 同 `request_id` 重放**不产生**第二次 dispatch（对 staging 计数的断言）；
- [ ] `Secret marker = 0`：全量日志/DB/临时目录扫描无凭证与正文；
- [ ] `active run = 0`、`Claude child = 0`、临时目录为 0；
- [ ] **完整回滚点**：记录 staging 部署前的 Git SHA、配置哈希、DB 快照，
      并实际演练一次回滚到该点。

### 5.4 C3 明确禁止

- 安装或登录 Claude Code；
- 任何模型调用；
- 把 staging 指向生产数据库/DNS/证书；
- 用真实用户数据做测试。

---

## 6. CP1-C4：Gate CP1 唯一真实模型验收

### 6.1 前置条件（全部为真才允许申请）

- [ ] C1、C2、C3 全部验收通过，且证据留档；
- [ ] 固定 Claude binary 的版本与 SHA-256 已记录；
- [ ] `readyz` 报告 `cli_version_mismatch = 0`；
- [ ] 用户**明确选择**了模型、次数与目标文本。

### 6.2 本阶段必须向用户重新申请的四项

**不得沿用任何此前的授权。** 申请时必须逐项写明：

| 项 | 必须明确的内容 |
|---|---|
| 模型 | 具体模型别名（不是 CLI 版本字符串） |
| 次数 | 精确次数，建议 **1 次** |
| 目的 | 固定文本目标（例如：让模型复述一个给定的短句） |
| 重试 | **禁止自动重试**；失败即停，本轮授权不延续 |

### 6.3 验收标准

- [ ] 模型 dispatch 数 **恰好等于**授权次数；
- [ ] 目标文本按预期返回，且经 Android UI 呈现；
- [ ] 全程无第二次请求、无 watchdog retry；
- [ ] `active run = 0`、`Claude child = 0`；
- [ ] `Secret marker = 0`；
- [ ] 失败时如实记录失败，**不得修后同轮继续消耗授权**。

### 6.4 C4 明确禁止

- 任何工具调用、附件、后台任务；
- `resume`/`fork`（那是 CP2）；
- 超出授权次数的任何请求。

---

## 7. 各段通用退出证据

每一段（C1–C4）结束时都必须产出一份报告，包含：

- 基线祖先链与 Git 洁净（`git status --porcelain` 空、`git diff --check` 通过）；
- 构建产物哈希；
- 测试执行结果（含 skipped 的如实标注 —— skipped **不等于**通过）；
- 服务 `healthz` / `readyz` 输出；
- `active run = 0`、`Claude child = 0`、临时目录 = 0；
- Secret marker = 0；
- 中间失败与最终通过**分开记录**；
- 未授权的模型调用数 = 0；
- 测试设备/fixture/授权凭证的精确清理。

---

## 8. 与 Android 侧的接口冻结点

CP1-C 期间 Android 侧**不得**改动下列已冻结内容，除非作为独立的兼容性变更评审：

| 冻结项 | 位置 |
|---|---|
| 协议 ID / subprotocol | `ClaudePProtocol.PROTOCOL_ID` / `SUBPROTOCOL` |
| 事件类型字符串 | `ClaudePEventType` |
| 错误枚举 | `ClaudePErrorCode` |
| 握手转录 | `ClaudePHandshakeTranscript` |
| 配对转录 | `ClaudePPairingTranscript` |
| 配对线格式 | `ClaudePPairingWireRequest` / `WireResponse` |
| 流 URL / 配对路径 | `ClaudePEndpoint.STREAM_PATH` / `PAIR_PATH` |
| 请求指纹域与字段序 | `ClaudePRequestFingerprint` |

**已发现的一处真实缺口（必须在 CP1-C 期间决定，但不属于 C1–C4 的硬前置）：**

Android 侧**没有实现凭证刷新**。全仓 `refresh` 关键字只出现在 UI 状态刷新
（`ClaudePDevicePairingRepository.refreshStatus`），不存在「对 Gateway nonce 签名换取新
access credential」的流程。因此 access credential 一旦过期，客户端只能重新配对。

`claudep/01-architecture-and-trust-boundaries.md` §4.6 要求刷新必须签名 nonce，
`08-cp1c-gateway-worker-adr.md` §8 也预留了 refresh proof 存储表 ——
但**客户端尚未实现**。C1 只需把服务端侧的刷新端点设计留出位置，
**不得**在 C1–C4 期间临时把「无刷新」当成「已实现」。

---

## 9. 下一动作

本计划与 ADR 一起停在**原 Codex 复审点**。

在用户确认 ADR §1（方案选择）与 §15（未决项）之后，才允许开始 CP1-C1 的第一行服务端代码。
在此之前，`claude-p-server` 仓库不应被创建，也不应新增任何依赖。
