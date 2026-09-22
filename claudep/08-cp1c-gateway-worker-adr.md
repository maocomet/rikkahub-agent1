# 08｜CP1-C 服务端 ADR：Claude P Gateway 与 Claude Worker

状态：**已确认，可进入 CP1-C1**（2026-09-21 用户确认；最终决定见 `claudep/09-cp1c0-confirmed-decisions.md`）
日期：2026-09-21
上位设计：`claudep/00`–`07`（Phase 0 设计包，基线 `c00f6f3d`）
配套计划：`claudep/reports/CP1C0-server-implementation-plan.md`
本轮模型调用数：**0**；本轮服务端代码：**0 行**

> **生效范围**：本 ADR 的**推荐方案（§3.1 独立仓库）、§7 协议边界、§8 存储、§9 日志规则、
> §10 health/ready、§11 binary/argv/env、§13 兼容矩阵、§14 回滚**均已生效。
> 但其中三处已被后续文档**取代或修订**，以新文档为准：
> - **§6 / §6.2 三层 + sudo 权限边界 → 作废**，改为**两层**（无 sudo、无 setuid），见 `09` §6；
> - **§1.2 / §2 的「檐岚没有 Worker」一组事实结论 → 已 superseded**，见下方 Superseded 小节；
> - **§15 未决项已裁决**（仓库名、许可证、存储、降权），见 `09` §9。

---

## ⚠ Superseded after auditing the latest Yanlan integration worktree

> 追加于 2026-09-21（第二轮）。**本节不删除、不改写下方任何历史结论**，
> 只标注哪些已失效、哪些仍成立。
> 完整证据见 `reports/CP1C0-correction-audit-yanlan-worker-reuse.md`。

**失效原因**：下方 §1.2 的取证对象是 `D:\yannan`，而该检出停在 **Gate 6**（HEAD `4f7408b`，分支 `dev`），
**不是**完成 Gate 7/8 的最新集成 worktree。
正确的取证对象是 **`D:\yannan.worktrees\v0.2-gate5-gate6`**（HEAD `5a221096`，分支 `codex/v0.2-gate5-gate6-integration`）。

**`D:\yannan` 不得再作为 Yanlan 当前实现依据。**

### 已失效的结论（`SUPERSEDED`）

| 位置 | 原文 | 现状 |
|---|---|---|
| §1.2 表头 | 「`D:\yannan`（檐岚）现状」 | 取证对象错误 |
| §1.2 | 「是否已有 Claude Code Worker？= **没有**」 | **错误**。`src/cc-worker/` 存在（14 个文件）；`package.json:18` 有 `start:cc-worker` |
| §1.2 | 「Unix socket 监听 = **没有**」 | **错误**。`server.ts:43-57` 已实现；`main.ts:64`；Gate 7 实测 socket `claude:yanlan 0660` |
| §1.2 | 「可独立重写的安全思想（**不是代码**）」 | **部分失效**：执行核心（argv builder、NDJSON 解析、stderr 分类、状态机、进程生命周期）**可复用代码**，不只是思想 |
| §2.1 表「与现有 Claude Worker 的复用」 | 「**无生产代码可复用**（檐岚根本没有 Worker）」 | **错误**。可复用但有边界，见纠正审计 §5.2 |
| §2.1 表 C 列 | 「檐岚…**没有** Unix socket」 | **一半失效**：确无 WSS，但**有** Unix socket |
| §11.1 隐含假设 | 檐岚已实现 SHA-256 门禁 | **未实现**。檐岚只做「固定路径 + 精确版本 + 目录不可写」（`main.ts:87-92`） |

### 仍然成立的结论（`STANDS`）

| 位置 | 结论 | 说明 |
|---|---|---|
| §1.2 | 檐岚**无任何许可证**（无 `LICENSE`/`COPYING`/`NOTICE`，`"private": true`，无 `license` 字段） | 本轮复验一致 |
| §1.2 | 檐岚**没有 Gateway**（REST + SSE，无 WebSocket） | 复验一致 |
| §1.2 | 主后端**已决定不放 Claude**（任务书写死） | 复验一致 |
| §1.2 | **反模式两处**（`run-submodel.sh:9`、集成方案 `:232-241`）仍**不得借鉴** | 与 Gate 7 **修复后**的生产代码不同，两者不可混淆 |
| §2.3 | **拒绝方案 C**：同进程 / 同库 / 同 origin / 违反檐岚自身决定 | **四条全部复验为真**（含 `load.ts:114` 限流默认关、`origin-check.ts:24-25` 非浏览器放行） |
| §2.4 | **拒绝方案 A** | 不受本次纠正影响 |
| §3.1 | **推荐独立仓库 `claude-p-server`** | **结论不变，但理由全部更换**：不再是「檐岚没有东西可复用」，而是「檐岚的 Worker 按构造总是挂载其 MCP 与工具面，且与后端共享 Postgres 依赖，因此不能整体复用」（纠正审计 §5.2、§6.1） |
| §3.1 | 服务端许可证 **AGPL-3.0**（依 `06` D-012） | 不变；且檐岚侧「不复制 RikkaHub AGPL 源码」是明文约束（任务书 `:23`），方向不冲突 |
| §7 | 公网协议与私有协议**不得混用** | 不变，且是拒绝「同一 Worker 双 profile」的核心依据 |
| §9 | Secret / prompt / 回答 / stderr 的日志白名单规则 | 不变 |
| §11.2.1 | 对 CLI flag 的两处修正（`--setting-sources ""` 语义未确认、`--allowedTools` 不是可见性白名单） | 不变，且**被檐岚现状印证**（檐岚正是用了 `--setting-sources ''` 且未做证伪实验） |
| §11.2.2 | 禁用参数黑名单 | 不变；檐岚**未实施** `--disallowedTools` 纵深防御，复用时应补 |
| §11.2.3 | env 白名单**构造**（不是继承后删除） | 不变；檐岚已按此实现（`main.ts:44-62`），但缺 4 个变量 |
| §11.2.4 | cwd 与 session 的耦合 | 不变；且檐岚「单一固定 cwd」的实践给出另一种已验证形态，并证明 **cwd 是 session 命名空间的天然分隔符** |
| §13 | 三方兼容矩阵 | 不变；Worker ABI 一轴需按檐岚实际形态（HTTP-over-Unix-socket + Bearer，无 `worker.hello` 握手）改写 |

### 建议修订（`REVISE`）

| 位置 | 建议 |
|---|---|
| §6 / §6.2 | 檐岚实测为**两层**（`claude` 用户直接 spawn，无 sudo launcher，unit `:8,12`）。建议采纳两层，**取消 setuid 让步**（不再需要「T2 不设 `NoNewPrivileges`」） |
| §7 | `worker.hello` / `abi: "claude-p-worker/1"` 握手在檐岚中**不存在**（只有 `/healthz` 的 `version` 字符串）。ABI 协商需自行设计 |
| §11.1 | SHA-256 清单校验要求**保留**，但须知檐岚未实现，**不可照抄现状** |
| §15 未决项 3、12 | 见上一条，可直接回避 |

**结论：本 ADR 的推荐方案（§3.1 方案 B / 独立仓库）与全部安全约束保持有效；
失效的只有「檐岚没有 Worker，因此无代码可复用」这一组事实前提。
方案选择本身不变，但复用边界必须按纠正审计 §5.2 的「抽取子集」清单重新界定。**

---

## 0. 本 ADR 的地位与存放位置

`claudep/06-decisions-and-open-items.md` 明确要求：

> 「这些参数必须在首次 staging 部署前形成部署 ADR，不能在代码中散落默认值。」

本文件即该 ADR。它不只填部署参数，还决定**服务端放在哪个仓库、用什么语言、权限怎么分**——
因为这些决定一旦写进代码就极难更改。

**存放位置说明（可复审的取舍）**：本仓库全局 ADR 约定是 `docs/adr/YYYY-MM-DD-slug.md`。
本 ADR 放在 `claudep/` 下，理由是 Claude P 设计包（`00`–`07` + `reports/`）已自包含，
且本 ADR 与 `06` 的未决部署参数一一对应；拆到两个目录会让「未决项 → 决定」的对应关系断开。
若用户更看重全局 ADR 索引的一致性，把本文件改为 `docs/adr/2026-09-21-claude-p-server.md`
并在 `claudep/08` 留一行指针即可，内容不变。

---

## 1. 决策依据：只读审计结论

### 1.1 `rikkahub-agent1` 现状（只读，未修改）

| 问题 | 结论 | 证据 |
|---|---|---|
| 是否已有可承载 Gateway/Worker 的非 Android 服务端模块？ | **没有** | `settings.gradle.kts` 的 12 个 `include()` 全部是 Android module |
| 是否存在非 Android 的 JVM/服务端产物？ | **没有** | `common/` 亦为 `com.android.library`，编译期依赖 Android SDK |
| 构建系统是否支持纯 JVM 模块？ | **不支持** | `gradle/libs.versions.toml` 的 `[plugins]` 无 `kotlin-jvm`，只有 AGP 系插件 |
| 是否有部署约定（Dockerfile / systemd / compose）？ | **没有** | 全仓无 `Dockerfile`、`*.service`、`docker-compose*`（`highlight` 里的命中是语法高亮词法文件） |
| 是否有服务端 CI？ | **没有** | `.github/workflows/` 6 个 workflow 全部是 Android 构建 / instrumentation / release |
| 触发策略 | `build-debug-apk.yml` 为 `workflow_dispatch` + **仅 `push: master`** | 特性分支 push 不触发 CI |
| 非 Android 的既有子项目 | 仅 `web-ui/`（React Router + Vite 前端，pnpm），且它是**打包进 App 的前端资源**，不是被部署的服务 | `web-ui/package.json` |
| 上游关系 | fork-of-fork：`rikkahub/rikkahub` → `ExTV/rikkahub-agent` → 本仓 | `README.md:32,247-248`；`docs/upstream-2.4.5-feature-audit.md` 记录了逐项移植而不合并上游分支的实践 |

**结论：本仓库没有任何服务端承载面，且它的构建、CI 与发布节奏全部围绕 Android APK 组织。**

### 1.2 `D:\yannan`（檐岚）现状（只读，未修改）

> **⚠ 本节已 SUPERSEDED（2026-09-21 第二轮纠正审计）。请先读本文档顶部
> 「Superseded after auditing the latest Yanlan integration worktree」小节。**
>
> 本节使用的取证对象 `D:\yannan` 停在 **Gate 6**（HEAD `4f7408b`，分支 `dev`），
> 不是完成 Gate 7/8 的最新集成 worktree。
> 下表中「是否已有 Claude Code Worker？= 没有」「Unix socket 监听 = 没有」两条**为事实错误**；
> 「檐岚没有 Gateway」「无许可证」「技术栈」等条目**仍然成立**。
>
> 正确的取证对象：**`D:\yannan.worktrees\v0.2-gate5-gate6`**（HEAD `5a221096`）。
> 逐条裁定见 `reports/CP1C0-correction-audit-yanlan-worker-reuse.md` §9。
>
> **以下原文保留不改，作为当时的记录。**

| 问题 | 结论 | 证据 |
|---|---|---|
| 许可证 | **无任何许可证**：无 `LICENSE`/`COPYING`，`package.json` 只有 `"private": true`、无 `license` 字段，README 无声明 | 已复验：根目录无许可证文件 |
| 技术栈 | Node ≥22 + TypeScript 5.6 + Fastify 5 + Drizzle ORM + **PostgreSQL** + pino | `package.json:6-29`；`drizzle/meta/_journal.json` `"dialect": "postgresql"` |
| 是否已有 Claude Code Worker？ | **没有**。生产代码零 `claude-p` / `stream-json` / CLI 子进程 | 全仓 grep：`src/` 中唯一的 `child_process` 是 `src/types/build.ts:20` 跑 `git rev-parse` |
| 是否已有 Gateway？ | **没有**。生产传输是 REST + SSE，无 WebSocket 依赖 | `src/sse/`、`src/server/index.ts` |
| Unix socket 监听 | **没有**（unit 里预留了 `AF_UNIX` 与 `RuntimeDirectory`，但无代码创建） | `ops/yanlan.service:56,59` |
| 是否计划做？ | **计划做，但明确要求隔离** | `reports/v0.2开发/檐岚-v0.2-开发任务书.md:269` |
| 主后端是否已决定不放 Claude？ | **是，且写进了任务书** | `docs/release-v0.1-backend.md:107`「主项目零 `claude-p` / `stream-json` 代码」 |

檐岚自己的 v0.2 任务书（`檐岚-v0.2-开发任务书.md:269-279`）独立得出了与本设计几乎相同的约束：

> - 新增独立 `yanlan-cc-worker`；**主后端不得直接拼 shell 命令或读取 Claude 凭证**。
> - worker 仅监听 `127.0.0.1` 或 Unix socket，使用服务间认证；**绝不暴露公网端口**。
> - worker 与主后端使用不同 Linux 用户；cwd、文件权限、环境变量和可访问目录最小化。
> - 锁定已验证的 Claude Code 版本；升级前运行协议 smoke，不跟随自动更新直接上线。
> - 使用参数数组调用进程，不通过拼接字符串进入 shell；模型、cwd、配置路径都做 allowlist 校验。

**这是一份独立的、针对同一问题的第二意见，结论是「必须拆出去、必须换用户、必须锁版本」——
与 `claudep/01` §2、`claudep/03` §3–§4 一致。**

檐岚中**必须视为反模式、不得借鉴**的两处：

- `reports/v0.2开发/prompts/run-submodel.sh:9`：`claude -p --dangerously-skip-permissions`；
- `reports/v0.2开发/檐岚-ClaudeCode集成方案.md:232-241`：
  `spawn('claude', ['-p', text, '--stream-json'], { env: { ...process.env, CLAUDE_CODE_OAUTH_TOKEN: ... } })`
  —— 无 binary 锁定、无 argv 白名单、把 OAuth token 透传进子进程环境、无生命周期与取消控制。

**可独立重写的安全思想（不是代码）**，檐岚已在生产中验证过：

1. **数据库终态 CAS 决定取消**：`UPDATE ... WHERE status = 'active'` 让恰好一个终态胜出，
   重复 abort 幂等，败者不追加事件（`src/chat/abort.ts:22-30,85-107`）；
2. **DB 是事件真相、实时总线只是通知层**：先订阅 → 回放 `(afterSeq, Hdb]` → 转 live，
   带 seq 去重与**序号缺口回补**（`src/sse/routes.ts:270-333`）；
3. **单活跃 generation 的部分唯一索引**（`src/db/schema/index.ts:219-221`）；
4. **事件缓冲表唯一 `(generation_id, seq)`**（`:500-514`）；
5. **密文与引用分离**：只把 `*_ref` 写进 DTO / 日志 / 事件（`README.md:45`）；
6. **设备会话只存 token 哈希 + 撤销墓碑**（`:670-691`）；
7. **systemd 加固集合**与**原子发布 + healthz/readyz 门禁 + 自动回滚**（`ops/yanlan.service:44-63`、`ops/deploy.sh`）。

### 1.3 Android v1 客户端契约（已冻结，是服务端的**输入**而非输出）

CP1-B 已经交付了一个完整、经过 CI 与真机验证的**客户端**。服务端必须匹配它，
而不是反过来。已冻结的事实：

| 项 | 值 | 位置 |
|---|---|---|
| 协议 ID / subprotocol | `rikkahub.claude-p.v1` | `ClaudePProtocol.PROTOCOL_ID` / `SUBPROTOCOL` |
| 流路径 | `/v1/claude-p/stream` | `ClaudePEndpoint.STREAM_PATH` |
| 配对路径 | `/v1/claude-p/pair`（**服务端尚不存在**） | `ClaudePEndpoint.PAIR_PATH` |
| 客户端 RPC（全部已实现） | `client.hello`、`catalog.get`、`generation.start`、`generation.cancel`、`receipt.query`、`stream.resume` | `ClaudePEventType` |
| 握手签名转录 | `rikkahub-claude-p-handshake-v1`，长度前缀覆盖 `deviceId, nonce, gatewayAuthority, appVersion` | `ClaudePHandshakeTranscript` |
| 配对签名转录 | `rikkahub-claude-p-pairing-v1`，长度前缀覆盖 `domain, origin, ticket, publicKey, state, challenge, appVersion` | `ClaudePPairingTranscript` |
| 配对请求/响应字段 | 见 `ClaudePPairingWireRequest` / `WireResponse`（8 个字段，无默认值） | `ClaudePPairingTransport.kt` |
| 设备密钥 | EC P-256，`SHA256withECDSA`，Android Keystore 不可导出 | `ClaudePDeviceIdentity.kt` |
| **请求指纹** | **客户端本地计算，从不上线** | `ClaudePRequestFingerprint` |
| 当前模式 | 仅 `mode = "new"`，`tool_snapshot = null`，`rebuild_history = null` | `ClaudePProvider.kt:149-163` |

**一处必须记录的缺口**：Android 侧**没有实现凭证刷新**。全仓 `refresh` 关键字只命中
UI 状态刷新（`ClaudePDevicePairingRepository.refreshStatus`），不存在「对 Gateway nonce 签名换取新
access credential」的流程。`claudep/01` §4.6 要求过它，本 ADR §8 也为 refresh proof 留了表——
但**客户端尚未实现**。这不阻塞 C1–C4，但**不得**被当作"已实现"。

---

## 2. 方案比较

三个候选方案，按 `claudep/00` §4（明确不做）、`claudep/03`（安全与运维）和本 ADR 的要求逐维度比较。

### 方案 A｜在 `rikkahub-agent1` 单仓新增服务端模块

- **A1**：新增 Kotlin/JVM 模块，接入现有 Gradle 构建，抽出 `:claudep-protocol` 供 App 与服务端共用；
- **A2**：在仓库内新增独立目录（如 `claude-p-server/`），自带 Node/pnpm 工具链，**不进 Gradle 构建图**。

### 方案 B｜新建独立 Gateway/Worker 仓库，锁定协议兼容矩阵

服务端单独建仓；Android 仓库继续作为**协议规范与客户端**的所在地；
两仓通过规范修订号 + 一致性语料 + 兼容矩阵绑定。

### 方案 C｜在现有檐岚服务端增加隔离的 Claude P Gateway surface

在 `D:\yannan` 的 Fastify 进程/主机内新增一组路由与 WSS upgrade，复用其 Drizzle/审计/限流/部署。

### 2.1 逐维度比较

| 维度 | A1（单仓 Kotlin 共用 DTO） | A2（单仓独立工具链） | **B（独立仓库）** | C（檐岚内隔离 surface） |
|---|---|---|---|---|
| **DTO 共享 / 漂移风险** | 字段级漂移**结构性不可能**；但语义漂移（转录字节、指纹规范化、终态唯一）**照样会发生** | 与 B 相同 | 字段与语义都可能漂移 → **用一致性语料消除**（§13） | 与 B 相同，且**跨语言**（Kotlin↔TS）漂移面更大 |
| **AGPL 源码义务** | 服务端必须 AGPL-3.0 | 服务端必须 AGPL-3.0（同仓） | 从规范独立实现 → 可自选；**本 ADR 仍选 AGPL-3.0**（D-012），并写明理由 | 檐岚**无许可证**，且 AGPL 的传染路径与檐岚自身许可不清 |
| **运行时供应链** | JVM + Gradle + 服务端依赖，VPS 需 JRE | Node + pnpm | Node + pnpm | 复用檐岚栈，但**扩大**檐岚供应链的攻击面 |
| **实现成本（WSS / 存储 / socket / systemd）** | WSS 需 Ktor/Netty；SQLite 需 JDBC；Unix socket 需 JNI 或 Netty native | 与 B 相同 | Node 原生 `ws` / `net` / `child_process`；SQLite 成熟 | 檐岚已有 Fastify/systemd，但**没有 WSS**、没有 Unix socket、PostgreSQL 偏重 |
| **与现有 Claude Worker 的复用** | 无（檐岚是 TS，语言不通） | 无代码可复用（檐岚无许可证） | 同 A2 | **无生产代码可复用**（檐岚根本没有 Worker）；只有思想可借鉴 |
| **凭证边界** | 服务端代码与 Android 代码同仓 → VPS 部署脚本、路径、unit 可能混进 App 仓库 | 同 A1，但目录隔离 | **结构性隔离** | 与檐岚 Secret（`SECRET_MASTER_KEY`、MCP token、Provider key）**同进程/同库** |
| **独立部署与回滚** | 与 App 同版本号、同 tag → 回滚粒度被绑死 | 可独立 | **完全独立** | 与檐岚同发布 → 回滚檐岚即回滚 Claude P |
| **测试与 CI 难度** | 服务端测试要跑在 Android 构建里 / 或新增独立 job；Android CI 当前**只在 master push 或手动触发** | 需在单仓内维护两套 CI | **独立 CI，可每 push 跑**，快且与 App 解耦 | 复用檐岚 CI，但**必须新增独立测试面**且不得污染其回归 |
| **是否可能把檐岚业务 API 暴露给手机** | 不涉及 | 不涉及 | 不涉及 | **是——这是 C 的主要风险**，见 §2.3 |
| **上游合并摩擦** | `settings.gradle.kts` / `libs.versions.toml` 每次上游同步都要处理 | 目录不冲突，但仓库整体仍是 fork-of-fork | **零摩擦** | 不涉及 |

### 2.2 决策方法说明（避免"写起来快"）

按任务要求，成本高**不是**选择理由，成本低也不是。真正区分三个方案的只有四件事：

1. **一个公开暴露的、安全关键的服务，能否独立于 Android App 发布与回滚？**
2. **凭证边界能否由结构保证，而不是由纪律保证？**
3. **服务端 CI 能否高频、快速、独立运行？**
4. **手机客户端有没有可能通过服务端意外触达不相关的业务 API？**

A1 在第 1、3 项弱，A2 在第 1 项弱，C 在四项全弱。

### 2.3 为什么**拒绝方案 C**

除了上表，C 有四个无法通过"隔离 surface"消除的问题：

1. **同进程**。Fastify 应用内的路由隔离是**代码级**隔离：一个 WSS upgrade handler 的 bug
   与业务路由共享事件循环、共享崩溃面。Architecture doc `01` §2 要求 Gateway 是
   「公网唯一入口，但不是 shell gateway」，而檐岚的进程里同时住着 Provider API Key、
   MCP token、`SECRET_MASTER_KEY` 与用户聊天正文。
2. **同数据库**。设备注册表、事件缓冲、receipt 会与 `conversation`、`message`、
   `provider_connection`（含密文）同库。`03` §1.6 要求「一个设备不能访问另一个设备/用户的
   session、事件或附件」——跨租户隔离在单用户库内靠 WHERE 子句保证，弱于物理隔离。
3. **同 origin**。手机端被配对的 origin 就是檐岚业务的 origin。手机只要拿到该 origin，
   就与 `provider-connections`、`mcp-servers`、`external-runtimes`、`auth/devices`、
   `sync` 等路由**同源**。这些路由由 Bearer/`yanlan_session` 保护，但：檐岚的
   **限流默认关闭**（`src/config/load.ts:114`），且其 **origin-check 对不带 `Origin`
   头的请求放行**（`src/server/plugins/origin-check.ts:24-55`）——一个非浏览器客户端
   可以绕过浏览器类防护。把手机指向该 origin，等于把一个移动客户端放进业务 API 的同源位置。
4. **檐岚自己已经决定不这么做**。`檐岚-v0.2-开发任务书.md:269` 与
   `docs/release-v0.1-backend.md:107` 都写明主后端不得包含 `claude-p` 代码。
   在檐岚内新增 Claude P surface 会**直接违反檐岚自己的架构决定**。

**结论：方案 C 拒绝。**

### 2.4 为什么**拒绝方案 A**

A1 有一个真实且诚实的优点：字段级漂移在结构上不可能。但：

- **它并不能消除真正的风险。** 本方案的漂移风险不在字段名，而在**语义**：
  长度前缀转录的确切字节、指纹的域标签与字段序、终态唯一、resume 只重放不重发。
  共用 `data class` 一个都不会自动保证。
- **代价是真实的。** 现有 DTO 住在 `:ai`——一个 `com.android.library` 模块。
  要共用，必须新建纯 JVM 模块并把 DTO 搬出去，这会给一个**刚刚经历四轮编译/DI 失败、
  拥有 333 条测试**的 Android 构建图新增 `kotlin-jvm` 插件与跨模块依赖，
  并把这份风险前移到 CP1-C1。
- **它与第 1、3 项决策依据相冲突**：服务端的发布与 CI 会被绑进 Android 构建。

A2 去掉了 A1 的共用优点，只剩下"在同一个仓库里"——那就不值得为它承担上游合并摩擦
（`README.md:247-248`、`docs/upstream-2.4.5-feature-audit.md` 显示本仓长期以逐项移植方式跟随上游）
与 AGPL 义务的边界模糊。

**结论：方案 A 拒绝；但若用户明确要求服务端用 Kotlin/JVM 且愿意先做 `:claudep-protocol`
抽取重构，A1 是唯一可接受的单仓形态**（见 §3.3）。

---

## 3. 决策

### 3.1 推荐：方案 B —— 独立 Gateway/Worker 仓库

**`maocomet/claude-p-server`**（名称待定，见 §15）：

- 同时容纳 **Gateway** 与 **Worker** 两个可独立部署的服务；
- TypeScript / Node；
- 自带 CI、自带部署脚本、自带 systemd unit；
- 从 `claudep/02-wire-protocol-v1.md` 这一**规范**独立实现，**不复制** Android Kotlin 代码；
- 许可证：**AGPL-3.0**（理由见下）。

**为什么仍然选 AGPL-3.0，即使独立实现后可以自选**：

- `claudep/06` D-012 已经把「Gateway 与客户端协议属于产品功能的一部分，不能通过拆分服务规避许可」
  定为冻结决策。选 B 之后如果改用宽松许可，就是在事实上规避 D-012 的意图。
- 服务端实现的是同一个协议，面向的是同一个用户群，且用户可能同时持有两仓。
  许可一致消除了「哪个文件属于哪份许可」的判定成本。
- 独立实现意味着**没有** `rikkahub-agent1` 的代码被复制，因此 AGPL 是**主动选择**而不是被动传染——
  这一点本身就值得写清楚。

### 3.2 拒绝其他方案

- **方案 C 拒绝**：见 §2.3（同进程、同库、同 origin、且违反檐岚自身架构决定）。
- **方案 A 拒绝**：见 §2.4（不消除真正风险，且把服务端绑进 Android 构建与发布）。

### 3.3 若用户坚持单仓

本 ADR 的**载荷不是仓库边界，而是下面四条约束**。若用户选择 A，这四条必须逐字保留：

1. 服务端**不进入** Android Gradle 构建图（独立工具链、独立 lockfile、独立 CI job）；
2. 服务端有自己的版本号与发布节奏，可单独回滚；
3. Gateway/Worker 的部署脚本、unit、路径、密钥**不得**出现在 Android App 仓库；
4. §13 的一致性语料与兼容矩阵照旧执行。

在满足这四条的前提下 A2 可以接受。A1（Kotlin 共用 DTO）只有在用户同时接受
「先做 `:claudep-protocol` 抽取重构并重跑全部 Android 验证」时才可接受。

---

## 4. 仓库归属与目录布局

**Android 仓库继续持有**（不变）：

- 协议规范：`claudep/02-wire-protocol-v1.md`（**规范性**，服务端以它为准）；
- 客户端实现与客户端测试；
- **一致性语料**：`claudep/conformance/`（新增，见 §13）——语言中立的 JSON，
  是两仓之间的共享契约。

**服务端新仓库持有**：

```text
claude-p-server/
├─ package.json / pnpm-lock.yaml     # 精确版本 + 提交 lockfile
├─ .nvmrc                            # 精确 Node 版本
├─ tsconfig.json
├─ src/
│  ├─ gateway/                       # 进程入口：只监听公网 WSS
│  │  ├─ index.ts                    #   启动、优雅退出、readyz 门禁
│  │  ├─ protocol/                   #   envelope / version / events / errors / limits
│  │  ├─ auth/                       #   possession proof / access credential
│  │  ├─ pairing/                    #   ticket / transcript / HTTPS 路由
│  │  ├─ registry/                   #   device registry
│  │  ├─ generation/                 #   状态机 / 缓冲 / cancel / receipt
│  │  ├─ idempotency/                #   指纹（服务端自算）/ store
│  │  ├─ catalog/                    #   编译期 allowlist
│  │  ├─ worker-client/              #   私有 socket 客户端（对公网协议**不可见**）
│  │  ├─ health/  log/  db/
│  │  └─ routes/                     #   只有 /v1/claude-p/*，没有别的
│  └─ worker/                        # 进程入口：只监听私有 Unix socket
│     ├─ index.ts
│     ├─ socket/                     #   私有 ABI
│     ├─ preflight/                  #   binary 路径 + 版本 + SHA-256
│     ├─ argv/                       #   纯函数 builder + 禁用参数黑名单
│     ├─ exec/                       #   spawn（无 shell）/ 用户降权 / 最小 env
│     ├─ stream-json/                #   NDJSON 解析（残行、多字节、未知事件）
│     ├─ run/                        #   active run / 唯一终态 / crash recovery
│     ├─ stderr/  tmpdir/  readiness/
├─ migrations/                       # 顺序编号 SQL
├─ conformance/                      #   vendored 语料 + SHA-256 清单
├─ fixtures/fake-claude.mjs          #   零模型 fixture
├─ tests/
├─ deploy/                           #   systemd units、sudoers 片段、目录初始化
└─ docs/                             #   部署手册、回滚手册、兼容矩阵
```

**规范的修订绑定**：服务端仓库根目录放一份 `SPEC_REVISION`，记录
`(rikkahub-agent1 commit SHA, claudep/02-wire-protocol-v1.md SHA-256)`。
CI 在两者与 vendored 语料清单不一致时失败。

---

## 5. 服务语言、锁定版本与包管理

| 项 | 决定 | 理由 |
|---|---|---|
| 语言 | **TypeScript** | Claude Code CLI 本身是 Node 程序，`--output-format stream-json` 是它的原生 schema；子进程 NDJSON 解析在该运行时最直接；VPS 运维面已有 Node 经验 |
| 运行时 | **Node.js 22 LTS**，`.nvmrc` + `engines` 锁到**精确 patch** | 与已验证的 VPS 运行时同线；精确 patch 让回滚点可复现 |
| 包管理 | **pnpm 10**，提交 `pnpm-lock.yaml`，CI/部署一律 `pnpm install --frozen-lockfile` | 与 `build-debug-apk.yml:32` 的 `pnpm@10` 约定一致；frozen lockfile 让构建可复现 |
| 依赖版本 | `package.json` 中**精确版本**（不用 `^` / `~`） | 一个公网安全服务的依赖漂移不应在部署时发生 |
| 运行时依赖上限 | 目标 **≤ 8 个直接依赖**（HTTP/WSS、SQLite、结构化日志、配置解析） | 每个直接依赖都是供应链面 |
| 自动更新 | **关闭**。Node、pnpm、依赖、Claude CLI 全部通过受控部署升级 | `03` §6 |

**不选 Kotlin/JVM 的原因**（不是"慢"，而是三条具体成本）：
VPS 需要 JRE 与 Gradle 构建链；Unix socket 与进程管理需要 JNI 或 Netty native；
现有 DTO 住在 Android 库模块中，共用需要先做模块抽取重构（§2.4）。

**不选 Rust/Go 的原因**：本机无工具链（`rustc`/`cargo`/`go` 均缺失），
两仓与 VPS 都无该运行时的运维经验，而收益（内存安全）在本场景可由
「无 shell、无动态求值、单一 argv 纯函数」的结构性约束取得。

---

## 6. 三层权限边界（Gateway / Worker / Claude）

> **⚠ 本节与 §6.1、§6.2 已作废（2026-09-21 用户确认）。**
> 最终采用**两层**边界：**所有 unit 保留 `NoNewPrivileges=true`，无 sudoers、无 setuid launcher**。
> 理由：檐岚实测形态本就是两层且已过 Gate 7；两层同时消除了 §6.2 的能力让步。
> **以 `claudep/09-cp1c0-confirmed-decisions.md` §6 为准。**
> 以下原文保留不改，作为当时的设计记录。

三个**独立的 Linux 用户**、三个**独立的 systemd unit**、**不共享**可写目录。

| 层 | 用户 | 能做什么 | **绝对不能** |
|---|---|---|---|
| **T1 Gateway** | `cp-gateway` | 监听公网 WSS；读写自己的 SQLite；读 `/run/claude-p/gateway.sock` | 读 Claude home / OAuth 文件；`fork`+`exec` 任意程序；读 Worker 的可写目录 |
| **T2 Worker** | `cp-worker` | 监听 `/run/claude-p/gateway.sock`（`0660 root:cp-bridge`）；构造 argv；spawn 子进程；解析 NDJSON | 监听任何 TCP 端口；读 Claude home / OAuth 文件；被手机直连 |
| **T3 Claude** | `claude` | 运行固定的 Claude Code binary；读写自己的 `CLAUDE_CONFIG_DIR` | 被 T1/T2 读取其配置目录；监听端口 |

### 6.1 边界如何被**结构**保证，而不是靠纪律

1. **Claude 配置不放在 `/home`**：`CLAUDE_CONFIG_DIR=/var/lib/claude-p/claude-home`，
   `0700 claude:claude`。这样 T1/T2 的 `ProtectHome=tmpfs` 与"配置文件在 home 里"
   两个问题一次解决，且**任何其他服务用户都读不到**（文件权限，不是 namespace）。
2. **socket 属主**：`/run/claude-p/gateway.sock` 由 `cp-worker` 创建，
   `0660 cp-worker:cp-bridge`；只有 `cp-gateway` 在 `cp-bridge` 组里。
3. **T2 → T3 的降权**：`cp-worker` 通过一条**只允许单个命令、无通配符**的 sudoers 规则
   调用 root-owned 的 `/usr/local/libexec/claude-p-exec`，由它 `setuid` 到 `claude`。
   `env_reset`、`secure_path`、`!requiretty`、命令路径写死。

### 6.2 这一处是**有代价的，必须写清楚**

`sudo` 是 setuid-root 的，因此 **T2 的 unit 不能设置 `NoNewPrivileges=true`**
（那会禁用 setuid），也不能设 `RestrictSUIDSGID=true`。这是一个**刻意的、局部的能力让步**，
用以下措施补偿：

- sudoers 规则**恰好一条**，命令路径 root-owned 且 0755、不可被 `cp-worker` 写；
- 该 launcher **不接受调用方提供的 argv**：它只接受经由私有 socket 传来的、
  已由 T2 的纯函数 builder 构造并校验过的参数数组；
- launcher 自身做第二次校验（长度、白名单前缀、无 `--dangerously-*`）；
- T2 的其余加固全部保留（`ProtectSystem=strict`、`ProtectHome=tmpfs`、空
  `CapabilityBoundingSet`、`RestrictAddressFamilies=AF_UNIX`、`MemoryMax`、`TasksMax`）。

**替代方案（备选，见 §15 未决项 3）**：把 T2 与 T3 合并为同一个 `claude` 用户，
接受两层边界。更简单、无 setuid 让步，但违反 `03` §4「Gateway、Worker、
Claude 子进程使用不同的服务/权限边界」的字面要求。

---

## 7. 公网 WSS 与私有 Unix socket 的协议边界

**这是两个完全不同的协议，不得混用。**

| | 公网协议 | 私有协议 |
|---|---|---|
| 端点 | `wss://<origin>/v1/claude-p/stream` | `/run/claude-p/gateway.sock`（`AF_UNIX`） |
| 协商 | WebSocket subprotocol `rikkahub.claude-p.v1` | 连接后首帧交换 `worker.hello` / ABI 版本 |
| 版本标识 | `protocol: "rikkahub.claude-p.v1"` | `abi: "claude-p-worker/1"` |
| 编码 | UTF-8 JSON，每帧一个 envelope | NDJSON，**单行硬上限** |
| 谁可发起 | 任意已配对设备 | **只有** T1 Gateway |
| 内容 | 设备可见的事件 | run 生命周期 + 已解析的文本增量 + 安全分类 |
| 认证 | 设备密钥签名 + access credential | **文件权限**（socket 属主/组/模式）+ 可选对端 uid 校验（`SO_PEERCRED`） |

**必须成立的三条**：

1. **公网协议里不存在**能够影响私有协议的字段。手机侧的 `generation.start`
   没有任何字段可以映射成 worker ABI 的选项——所有映射都是 Gateway 内的编译期常量。
2. **私有协议里的错误不直接外泄。** Worker 的失败被 Gateway 折叠成 `02` §6 的安全枚举；
   Worker 的内部错误码、路径、stderr 分类**永不**进入公网事件。
3. **Worker 不实现任何公网概念**：不知道 device、不知道 ticket、不知道 WSS。

---

## 8. 最小存储表（SQLite / WAL）

存储只属于 **T1 Gateway**（`StateDirectory=claude-p-gateway`）。Worker **无持久化**。

| 表 | 关键列 | 约束 |
|---|---|---|
| `gateway_meta` | `installation_id`, `gateway_public_key`, `fingerprint`, `schema_version` | 单行 |
| `pairing_ticket` | `ticket_sha256` (PK), `issued_at`, `expires_at`, `consumed_at`, `consumed_device_id` | **只存哈希**；TTL ≤ 300s；`consumed_at` 置位后不可再消费 |
| `device` | `device_id` (PK), `display_name`, `public_key_spki`, `state`, `paired_at`, `last_seen_at`, `revoked_at` | `state ∈ {active, revoked}`；撤销后 `revoked_at` 非空 |
| `device_credential` | `credential_sha256` (PK), `device_id`, `issued_at`, `expires_at`, `revoked_at` | **只存哈希**；短期 |
| `refresh_proof` | `proof_id` (PK), `device_id`, `nonce_sha256`, `used_at`, `expires_at` | §1.3 缺口：**客户端未实现，表先建不用** |
| `request_fingerprint` | `device_id`, `request_id`, `fingerprint`, `generation_id`, `created_at` | PK `(device_id, request_id)`；指纹**服务端自算** |
| `generation` | `generation_id` (PK), `device_id`, `remote_thread_id`, `remote_branch_id`, `mode`, `model_alias`, `state`, `last_event_seq`, `terminal_kind`, `error_code`, `started_at`, `finished_at` | 部分唯一索引：**每 `(device_id, remote_thread_id, remote_branch_id)` 至多一个非终态行** |
| `generation_event` | `generation_id`, `event_seq`, `event_type`, `payload`, `created_at` | 唯一 `(generation_id, event_seq)`；**有界**：按 TTL 与条数双限清理 |
| `receipt` | `generation_id` (PK), `terminal_kind`, `error_code`, `stop_reason`, `usage_json`, `last_event_seq`, `finished_at` | **只存安全终态与计数，无正文** |
| `audit` | `id`, `at`, `actor_kind`, `action`, `target_ref`, `result` | 只存 `03` §7 允许的事件类型；关联 ID 用 scoped opaque 或 HMAC |

**Phase 1 最小 generation 状态机**：

```text
accepted ──> active ──┬──> completed
                      ├──> cancelled
                      ├──> failed
                      └──> (crash) ──> interrupted  → 由恢复程序归一化
```

**唯一终态**由数据库 CAS 保证（借檐岚 `abort.ts` 的思想）：
`UPDATE generation SET state=?, terminal_kind=? WHERE generation_id=? AND terminal_kind IS NULL`，
受影响行数为 0 即表示已有终态，调用方返回原终态且**不追加任何事件**。

**不存**：prompt 正文、模型回答正文、推理正文、stderr 原文、OAuth、任何 Secret、
附件字节、Claude session 正文。`payload` 只承载已解析的事件体，且受 TTL 约束。

---

## 9. Secret / prompt / 回答 / stderr 的禁止落盘与日志规则

1. **日志是白名单，不是黑名单。** `log(level, event, fields)` 只接受在
   `log/redact.ts` 中**显式声明过**的字段；调用方无法传任意对象。
   允许的字段：事件类型、安全枚举、scoped opaque ID 的**短哈希**、
   字节数、计数、时长、版本字符串。
2. **禁止的记录项**（`03` §7）：prompt、回答、推理、工具参数与结果正文、
   文件名原文、OAuth、Authorization / Cookie、完整 session / device / ticket ID。
3. **`toString` / 序列化默认脱敏。** 任何承载 Secret、ticket、prompt 的类型
   （对齐 Android 侧 `redactedRef()` 的做法）必须覆写 `toString` 为长度或短哈希；
   代码审查把"未脱敏的 `toString`"视为缺陷。
4. **错误对象不携带正文。** 抛出的错误只有安全枚举 + 可选的安全字段；
   上游异常（含可能引用请求体的解析异常）**必须**在边界处被折叠。
5. **stderr 不出 Worker。** Worker 只做有界内存中的 allowlist 分类，
   分类结果是一个枚举与计数；stderr 原文在任何情况下都不写日志、不进事件、不入库。
6. **磁盘上的 Secret 只有一个**：Claude 自己的 OAuth 文件，位于
   `/var/lib/claude-p/claude-home`（`0700 claude:claude`）。Gateway、
   `/etc` 下的配置文件、数据库、日志、备份中**都不存在** Claude 凭证。
7. **CI 门禁**：secret-marker 扫描（固定的一组标记字符串）在源码、
   测试产物、日志样例、迁移文件中必须为 0。

---

## 10. health / ready 与安全状态枚举

### 10.1 端点

| 端点 | 语义 | 内容 |
|---|---|---|
| `GET /healthz` | 进程活着 | `{"status":"ok"}`；**不含**版本或配置细节 |
| `GET /readyz` | 能接受并完成一次 generation | 见下 |
| `GET /v1/claude-p/status`（鉴权后） | 给已配对设备的安全状态 | 见 §10.3 |

`readyz` **不是** `healthz` 的别名：Gateway 在 Worker 不可达、binary 版本不符、
存储不可写、active run 超限时必须 **不 ready**，让负载均衡与部署门禁能真正拦住它。

### 10.2 `readyz` 字段（全部为安全枚举 / 布尔 / 计数）

```json
{
  "ready": false,
  "gateway": { "build": "<semver>", "protocol_major": 1, "schema_version": 1 },
  "storage": { "ok": true },
  "worker": {
    "reachable": true,
    "abi": "claude-p-worker/1",
    "claude_version_expected": "<pinned>",
    "claude_version_actual": "<observed>",
    "binary_sha256_match": true,
    "auth_state": "logged_in"
  },
  "limits": { "active_runs": 0, "max_active_runs": 1 },
  "reasons": []
}
```

`reasons` 只允许 `02` §6 的安全枚举子集，例如 `cli_version_mismatch`、
`worker_busy`、`quota_unavailable`、`authentication_required`、`external_runtime_error`。

### 10.3 安全状态枚举（不泄密的那些）

| 枚举 | 取值 | 说明 |
|---|---|---|
| Claude 登录状态 | `logged_in` / `logged_out` / `expired` / `unknown` | **不显示账号邮箱、订阅名或 Token** |
| 额度状态 | `available` / `unavailable` / `unknown` | `unknown` **不得**显示为 0（`00` §6） |
| 设备状态 | `active` / `revoked` | |
| Generation 终态 | `completed` / `cancelled` / `failed` / `interrupted` / `unknown` | `unknown` 交给用户决定，不自动 retry |

**`readyz` 不得**返回：文件系统路径、binary 绝对路径、Claude 版本以外的构建指纹、
任何设备标识、任何 prompt 长度以外的请求信息。

---

## 11. 固定 Claude binary、版本、SHA-256 与安全 argv

### 11.1 binary 锁定

| 项 | 决定 |
|---|---|
| 安装形态 | 官方 **native/standalone** 安装到 `/opt/claude-p/claude/<version>/`，**不用** npm 全局安装、不用自动更新器 |
| 所有权 | `root:root`，binary `0755`，**目录不可被 `claude` / `cp-worker` 写** |
| 版本 | **精确版本**，写入服务端仓库的 `claude-pin.json` |
| 完整性 | 记录**该版本官方发布的 SHA-256 清单**与实际 binary 的 SHA-256，两者都必须匹配 |
| 升级 | 独立评审 + 重新跑协议 smoke + 更新 `claude-pin.json`；**自动更新关闭** |
| preflight | 每次 Worker 启动时校验路径、版本、SHA-256；不匹配则 `readyz` 不 ready 且 **模型 invocation = 0** |

**关于具体版本号与 SHA-256**：本 ADR **不写一个我此刻无法亲自核验的哈希**。
`03` §6 要求「明确版本与 SHA-256」，`07` §3 要求「CP1 开始前必须重新验证
Claude Code 当前稳定版本、目标 VPS 架构和 binary SHA-256」——
这两条只能在目标 VPS 上对真实发布产物执行后才成立。

C0 期间检索到的版本信息**相互不完全一致**（检索结果指向的最新版本与本机已安装版本不同），
因此**版本号本身就是 CP1-C2 的第一个实测项**，不得从本 ADR 抄一个数字下去。

官方安装/校验路径（**需在目标机复核**）：官方发布的
`manifest.json` 带 GPG 分离签名，签名密钥指纹为
`31DD DE24 DDFA B679 F42D 7BD2 BAA9 29FF 1A7E CACE`（分离签名自某个版本起才存在）。
C2 的 preflight 必须：导入并校验该公钥指纹 → 下载 `manifest.json` + `.sig` → `gpg --verify`
→ 与 `sha256sum` 实测值比对。**任一环节的细节均需在目标机复核**，
不得以"文档里写着"替代实测。

因此交付物是一个**格式固定、内容待填**的清单文件，由 CP1-C2 的 preflight 步骤生成并提交：

```json
{
  "claude_code_version": "<exact semver, filled at CP1-C2>",
  "platform": "linux-x64",
  "install_path": "/opt/claude-p/claude/<version>/claude",
  "expected_sha256": "<sha256 of the shipped binary>",
  "manifest_sha256": "<sha256 of the vendor manifest it was verified against>",
  "verified_at": "<ISO-8601>",
  "verified_by": "<operator>"
}
```

**未填写的 `claude-pin.json` 必须让服务拒绝启动**，而不是退回"跳过校验"。

### 11.2 安全 argv

由**单一纯函数**构造，输入只有类型化字段（model alias、prompt、session 选项、
limits），输出是**参数数组**（**不是**字符串，永不进 shell）。

Phase 1 形态：

```text
<fixed-binary-absolute-path>
  -p
  --input-format stream-json
  --output-format stream-json
  --verbose                      # 与 -p + stream-json 组合时实际必需
  --include-partial-messages     # 逐 token 增量；需 -p + --output-format stream-json
  --model <allowlisted-alias>
  --tools ""
  --setting-sources ""
  --disable-slash-commands
  --permission-mode dontAsk
```

Phase 3 **只新增**：`--strict-mcp-config` + 指向本 Generation 受控临时配置的 `--mcp-config`。

#### 11.2.1 ⚠ 对 `claudep/03` §3 的两处修正（本节**覆盖**该文档）

CP1-C0 期间对 CLI 做了一次只读核对。核对方式是：在本机已安装的
`@anthropic-ai/claude-code` **2.1.226** 二进制中检索字面量，并辅以检索到的文档片段。
**必须说清楚置信度**：字面量存在 ⟹ 该 flag/env 存在；**不蕴含**其语义正确。
下列两处是**语义**层面的结论，因此是**必须用实验证伪**的设计前提，而不是可以直接依赖的保证。

**修正一：`--setting-sources ""` 可能不是关闭，而是"未设置"。**

- 未找到关于空字符串语义的官方文档；
- Agent SDK 侧存在一节直接题为「`setting_sources=[]` 没有禁用文件系统设置」，
  合理的失败模式是**空值被当作未设置 ⇒ 加载全部**；
- 而 user/project settings 可携带 **hooks** 与 **`apiKeyHelper`**——后者会**执行一条 shell 命令**
  来取 key。这正是"调用方可注入任意命令"的入口。若 `--setting-sources ""` 是空操作，
  那么整个 argv 的隔离就建立在一个未验证的假设上。

**处理**（写进 CP1-C2 的验收，不只是记在文档里）：

1. argv 中保留 `--setting-sources ""`，但**不再把它当作唯一防线**；
2. 追加更强杠杆（字面量均已在本机二进制中确认存在）：**`--bare`**（最小模式：跳过 hooks、
   LSP、plugin sync、auto-memory、keychain 读取、CLAUDE.md 自动发现）与/或 **`--safe-mode`**；
   两者语义同属**未官方确认**，因此与第 3 条绑定；
3. 增加环境变量 `CLAUDE_CODE_DISABLE_AUTO_MEMORY=1`；
4. **必须有一个证伪实验**：在 Worker 的 cwd 与 `CLAUDE_CONFIG_DIR` 中**故意放置**
   带可识别标记的 `CLAUDE.md`、`.claude/settings.json`（含 `apiKeyHelper` 或 hook），
   断言它们**未被加载**（无标记副作用）。实验不通过 ⇒ **不得进入 C3**；
5. 由于 hooks/apiKeyHelper 走的是"执行命令"这条路，§11.2.3 的 env 白名单与
   `--bare`/`--safe-mode` 必须同时成立才允许进入 C3。

**修正二：`--allowedTools` 是"自动批准名单"，不是"可见性白名单"。**

未被列出的工具**未必**不可达，除非被 permission mode 或 `--disallowedTools` 明确拒绝。
因此 Phase 3 的 `--allowedTools mcp__rikkahub_bridge__*` **单独不构成封锁**。

**处理**：`--tools ""` 的不变量（`03` 已声明"不得移除"）继续是主防线；
Phase 3 另加 **`--disallowedTools`** 逐一列出最关心的内置工具名（尤其只读文件工具）
作为纵深防御；`--permission-mode dontAsk` 保证"需要询问的一律拒绝"。

#### 11.2.2 禁用参数黑名单

穷举 + 前缀匹配，**任一输入下都不得出现**（字面量均已在本机 2.1.226 中确认存在）：

- `--dangerously-skip-permissions`、`--allow-dangerously-skip-permissions`，及任何 `--dangerously-*`；
- `--add-dir`（扩大可写根）；
- `--settings` / `--plugin-dir` / `--agents` / `--append-system-prompt-file`
  （加载任意文件 → hook / 插件 / 提示词注入）；
- `--permission-prompt-tool`（把审批委派给 MCP 工具）；
- 任何不经 allowlist 的 `--allowedTools` / `--disallowedTools` / `--permission-mode` 值；
- 任何 `--mcp-config` / `--mcp-server` 的任意路径（Phase 3 只能指向本 Generation 的受控文件）；
- 任何让调用方指定 cwd、binary 路径或 env 的形态。

> 反例警示（檐岚仓库中真实存在，**不得**借鉴）：
> `reports/v0.2开发/prompts/run-submodel.sh:9` 直接使用
> `claude -p --dangerously-skip-permissions`；
> `reports/v0.2开发/檐岚-ClaudeCode集成方案.md:232-241` 用
> `spawn('claude', ['-p', text, '--stream-json'], {env: {...process.env, CLAUDE_CODE_OAUTH_TOKEN}})`。
> 两者都无 binary 锁定、无 argv 白名单、把 OAuth token 透传进子进程环境。

#### 11.2.3 环境变量：白名单**构造**，不是继承后删除

只放：

```text
PATH=<固定最小路径>
HOME=<claude 用户的 home>
CLAUDE_CONFIG_DIR=/var/lib/claude-p/claude-home
LANG / LC_ALL
DISABLE_AUTOUPDATER=1          # 关闭后台更新检查
DISABLE_UPDATES=1              # 阻断全部更新路径（含手动 claude update）
CLAUDE_CODE_DISABLE_AUTO_MEMORY=1
DISABLE_TELEMETRY=1
DISABLE_ERROR_REPORTING=1
CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1
```

**必须显式清空/不传**（这些都会实质性改变行为）：

- `ANTHROPIC_API_KEY`、`ANTHROPIC_AUTH_TOKEN`、`CLAUDE_CODE_OAUTH_TOKEN`
  —— 登录态由 CLI 自己的配置文件提供；在 `-p` 模式下 `ANTHROPIC_API_KEY`
  **只要存在就会被使用并覆盖订阅**，透传等于换掉计费主体；
- `ANTHROPIC_BASE_URL` —— 可重定向整个 API 端点；
- `CLAUDE_CODE_SIMPLE`、`CLAUDE_CODE_USE_BEDROCK`、`BASH_DEFAULT_TIMEOUT_MS`、
  `BASH_MAX_TIMEOUT_MS`、`MCP_TIMEOUT`、`MAX_THINKING_TOKENS`。

因为 env 是**构造**出来的，上述变量在结构上不可能出现 —— 不依赖"记得删除"。

#### 11.2.4 cwd 与 session 的耦合（**影响 CP2，现在就要定**）

CLI 把 session 持久化在 `<config dir>/projects/<把 cwd 编码后的目录名>/<uuid>.jsonl`，
其中 cwd **按绝对路径逐字符编码**。这意味着：

> **如果每 Generation 用一个随机的临时 cwd，`--resume` 将永远找不到上一次的 session。**

因此 §12 的目录设计必须修正为：

- **cwd 按 `remote_thread_id` 稳定**：`/var/lib/claude-p/work/<remote_thread_id>/`，
  `0700`，属主 `claude`；同一 thread 的多次 Generation 使用**同一个** cwd；
- 每 Generation 的一次性文件放在该目录下的**子目录**，
  终态后精确删除子目录，**不删除 cwd 本身**；
- `remote_thread_id` 是随机 UUID、不含用户信息，符合 `01` §5。

**这是 CP1-C2 必须实测确认的项**（`--session-id` 自行指定 + 后续进程 `--resume` 能否命中，
官方文档层面未确认）。若实测不成立，CP2 的 `resume` 语义需要重新设计，
**且必须在 CP2 之前**得到结论 —— 不能等到 CP2 才发现。

#### 11.2.5 退出码**不是**成功信号

已观察到过"stdout 为空但 exit code 为 0"的回归。Worker **必须**解析 `result` 事件
（`is_error`、`subtype`、非空 `result`）来判定终态，**不得**只凭退出码。
`result` 事件的 `subtype` 取值包括 `success`、`error_during_execution`、
`error_max_turns`、`error_max_budget_usd`。

#### 11.2.6 `claude auth status` 的使用边界

`claude auth status` 登录时退出码 0、未登录 1；输出含 `loggedIn` / `authMethod`
及 **`email` / `subscriptionType` / `orgId` / `orgName`**。它**不**打印 OAuth token。

Worker **只读取** `loggedIn` 与 `authMethod` 两个字段，映射到 §10.3 的安全枚举；
`email` / `orgId` / `orgName` 一律**丢弃**，不进入事件、日志或存储。
（JSON 是否为默认输出格式在检索到的资料中互相矛盾，**需实测**。）

---

## 12. systemd sandbox 与目录权限

### 12.1 目录

```text
/opt/claude-p/claude/<version>/      root:root  0755   # 固定 binary，服务用户不可写
/opt/claude-p/current -> releases/…  root:root  0755   # 服务端代码的原子发布点
/var/lib/claude-p/gateway/           cp-gateway:cp-gateway  0700  # SQLite + WAL
/var/lib/claude-p/claude-home/       claude:claude          0700  # CLAUDE_CONFIG_DIR
/var/lib/claude-p/work/<thread_id>/  claude:claude          0700  # 每 remote_thread 一个稳定 cwd
/run/claude-p/                       root:cp-bridge  0750 # socket 所在
/etc/claude-p/gateway.env            root:root  0600   # 非 Secret 配置；无 Claude 凭证
/usr/local/libexec/claude-p-exec     root:root  0755   # 唯一的降权 launcher
```

`/var/lib/claude-p/work/` 本身 `root:root 0711`（可穿越、不可列举），
其下每个 `<thread_id>` 子目录 `0700 claude:claude`，与 §11.2.4 的 cwd 规则一致。

**关于 `~/.claude.json`（易漏项）**：Claude 的账号元数据写在 **home 根目录的 `.claude.json`**，
**不在** `CLAUDE_CONFIG_DIR` 里面。因此只挂载/备份 `claude-home` 会漏掉它。
本设计把 `claude` 用户的整个 home 也保持 `0700`，并把它一并纳入备份范围。

### 12.2 `claude-p-gateway.service`（T1）

```ini
[Service]
User=cp-gateway
Group=cp-gateway
SupplementaryGroups=cp-bridge
StateDirectory=claude-p-gateway
RuntimeDirectory=claude-p
NoNewPrivileges=true
PrivateTmp=true
PrivateDevices=true
ProtectSystem=strict
ProtectHome=tmpfs                 # 连 /home 与 /root 都看不见
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectKernelLogs=true
ProtectControlGroups=true
ProtectClock=true
ProtectHostname=true
ProtectProc=invisible
LockPersonality=true
RestrictRealtime=true
RestrictSUIDSGID=true
RestrictNamespaces=true
RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6
CapabilityBoundingSet=
SystemCallFilter=@system-service
SystemCallArchitectures=native
MemoryMax=512M
TasksMax=64
ReadWritePaths=/var/lib/claude-p/gateway
UMask=0077
```

`ProtectHome=tmpfs` + `ReadWritePaths` 只列自己的状态目录 ⇒
**Gateway 在结构上无法读取 Claude 配置**。

### 12.3 `claude-p-worker.service`（T2）

与 T1 相同的加固集合，**除以下三处刻意的差异**：

```ini
User=cp-worker
Group=cp-worker
SupplementaryGroups=cp-bridge
StateDirectory=claude-p-worker
RuntimeDirectory=claude-p
RestrictAddressFamilies=AF_UNIX          # ← 无网络族：Worker 不能监听公网
ReadWritePaths=/var/lib/claude-p/tmp     # ← 只多这一个
# 注意：本 unit 不设 NoNewPrivileges / RestrictSUIDSGID
#       因为需要 setuid 到 claude（见 §6.2 的代价说明）
MemoryMax=1G
TasksMax=128
```

### 12.4 门禁

部署后逐项检查：`systemctl show` 的加固字段实际生效值、
socket 的 `stat`（属主/组/模式）、`/var/lib/claude-p/claude-home` 的 `0700`、
`cp-gateway` 用户**实际无法** `stat` 该目录、`active_runs=0`、`claude child=0`、
临时目录为空、secret marker=0。

---

## 13. 三方协议兼容矩阵

### 13.1 三个接口，三个独立的版本轴

| 接口 | 版本标识 | 谁定义 | 兼容规则 |
|---|---|---|---|
| Android ↔ Gateway | `rikkahub.claude-p.v1` + WSS subprotocol | `claudep/02`（Android 仓） | **主版本必须相等**；同一主版本内**只允许增量**；未知**可选事件**忽略；未知**主版本**立即停止 |
| Gateway ↔ Worker | `claude-p-worker/1` | 服务端仓 | `worker.hello` 交换 ABI；不匹配则 Gateway **不 ready**，模型 invocation = 0 |
| Gateway/Worker ↔ Claude CLI | `claude-pin.json` 的精确版本 + SHA-256 | 服务端仓 | 版本或哈希不符 → 拒绝启动；`system/init.capabilities` 按**字段**探测，不按版本字符串猜 |

### 13.2 兼容矩阵

| Android | Gateway | Worker | Claude Code | 结果 |
|---|---|---|---|---|
| v1 | v1 | abi/1 | pinned | ✅ 正常 |
| v1 | v1 | abi/2 | pinned | ✅ Gateway 只用 abi/1 子集（向后兼容） |
| v1 | v1 | abi/0（更旧） | pinned | ❌ `readyz` 不 ready；`cli_version_mismatch` 类安全错误 |
| v1 | **v2** | any | any | ❌ 主版本不符 → 客户端停止（`protocol_mismatch`） |
| **v2** | v1 | any | any | ❌ 服务端拒绝 `client.hello` 的 `protocol_versions` |
| v1 | v1 | abi/1 | **drifted** | ❌ 拒绝启动；**模型调用 0** |

**升级顺序（必须遵守）**：**Worker → Gateway → Android**。
理由：Worker 先升级时旧 Gateway 仍只用旧 ABI 子集；Gateway 后升级时旧 Android
只会收到未知**可选事件**，按 `02` §1 规则忽略；Android 最后升级。
反向顺序会让新 Gateway 撞上旧 Worker ABI。

### 13.3 一致性语料（两仓之间的实际共享物）

`claudep/conformance/`（Android 仓，规范性）：

```text
conformance/
├─ frames/*.json           # 规范帧原文
├─ expect/*.json           # 路由期望：event | ignored_unknown | rejected(<enum>)
├─ transcripts/*.json      # handshake / pairing 转录的规范字节（hex）
├─ fingerprints/*.json     # 指纹向量（字段 → 期望 SHA-256）
└─ MANIFEST.sha256         # 每个文件的 SHA-256
```

服务端仓库 `conformance/` 是**逐字节 vendored 副本**，CI 校验 `MANIFEST.sha256`，
并在 `SPEC_REVISION` 与 Android 仓当前修订不一致时**失败**。

**这是方案 B 消除 DTO 漂移的主要手段**——不是共享代码，而是共享**可执行的一致性证据**。
两侧测试套件都必须跑这份语料。

---

## 14. 回滚与清理策略

### 14.1 回滚点

每个阶段（C1–C4）部署前必须记录并**实际演练**一次回滚。回滚点 = 四件东西的快照：

1. Git SHA（服务端仓）+ `claude-pin.json` 内容 + `SPEC_REVISION`；
2. SQLite 的**在线备份**（`VACUUM INTO` 或 WAL checkpoint 后的文件副本），
   文件名带 Git SHA 与时间戳；
3. `/etc/claude-p/*.env` 的哈希；
4. 当前 systemd unit 文件与加固字段实际生效值。

回滚 = 切回上一个 `releases/` 目录 + 恢复对应 SQLite 备份 + `daemon-reload` + 重启。
**回滚不移除 schema 变更**：迁移必须向后兼容一个版本（新增列可空、不删列、不收紧约束）。

### 14.2 清理

| 对象 | 时机 | 方式 |
|---|---|---|
| 每 Generation 临时目录 | 终态**立即** | 精确路径删除（不做通配符扫描）；启动时再扫一遍残留 |
| `generation_event` | 终态后短 TTL | 定时清理；条数与 TTL 双上限 |
| `receipt` | 保留较久（用户要查终态） | 定时清理；不含正文 |
| 过期 `pairing_ticket` | TTL 到 | 调用时判定 + 定时清理 |
| 撤销的 `device` / `device_credential` | 撤销即失效 | 立即断开 WSS、取消 active Generation、清除工具等待 |
| Worker 重启后的孤儿 run | 启动时 | 归一化为 `completed/cancelled/failed/interrupted` 之一；**不得**重跑任何可能已产生副作用的操作 |
| staging 测试数据 | 阶段结束 | 精确删除 fixture、测试设备、授权凭证；报告记录已清理 |

### 14.3 撤销语义（`03` §10）

手机可撤销自身设备；VPS 管理员可撤销任意设备。撤销后：access credential 失效、
refresh proof 失效、WSS 断开、active Generation 收敛、工具等待清除。
Claude OAuth 失效率**不**自动删除手机配对。

### 14.4 删除 Provider 的默认行为

按 `06` 建议：默认**只删除手机端配对信息**，是否同时撤销 VPS 设备由弹窗分别选择。

---

## 15. 未决项（必须由用户选择后才能开始 CP1-C1）

> **⚠ 本表已于 2026-09-21 裁决完毕。最终决定见
> `claudep/09-cp1c0-confirmed-decisions.md` §9。**
> 要点：#1 仓库名 `maocomet/rikkahub-claude-p-server`（private）；
> #2 SQLite + WAL；#3 **取消降权机制（改两层，无 sudo）**；#12 随之不再需要。
> #4–#7 仍未定，但**不阻塞 C1**。以下原文保留不改。

| # | 项 | 建议 |
|---|---|---|
| 1 | 服务端仓库名与归属（`maocomet/claude-p-server`？私有还是公开？） | 私有起步；若公开则 AGPL-3.0 |
| 2 | 存储选型最终确认（SQLite/WAL vs PostgreSQL） | **SQLite/WAL**——单账号单 VPS、写量小、无 DB 守护进程、备份即一个文件、回滚点天然 |
| 3 | T2→T3 降权机制（sudo launcher vs 合并为两层） | **sudo launcher**（保住三层边界，代价见 §6.2）；若用户不愿在 T2 放开 setuid，则退化为两层 |
| 4 | Gateway 正式子域名、证书方式、反代 | 部署参数，CP1-C3 前定 |
| 5 | 是否只支持一个 Gateway 实例（`06` 产品项 1） | 建议第一版只支持一个 |
| 6 | 多设备是否共享同一 Claude session（`06` 产品项 3） | 建议每设备独立 |
| 7 | 附件保留时间（`06` 产品项 6） | Phase 4 再定 |
| 8 | Android 侧凭证刷新何时补（§1.3 缺口） | 建议作为 CP1-C 之外的独立小阶段，不塞进 C1–C4 |
| 9 | 本 ADR 是否改为 `docs/adr/2026-09-21-claude-p-server.md`（§0） | 用户偏好 |
| 10 | `--setting-sources ""` 证伪实验失败时，是否改用 `--bare` 或 `--safe-mode`（§11.2.1） | 由 C2 的实验结果决定；两者语义同属未确认，需同样证伪 |
| 11 | cwd 稳定性与 session 恢复的最终方案（§11.2.4） | C2 实测后定；**结果会影响 CP2 设计，必须在 C2 出结论** |
| 12 | 是否接受 §6.2 的 setuid 让步以保住三层用户边界 | 用户选择；不接受则退化为两层 |

---

## 16. 复审清单

- [x] 三种方案按 9 个维度逐项比较，未以"实现成本低"为选择理由
- [x] 推荐方案给出可复核的依据（§2.2 的四个决策问题）
- [x] 拒绝方案 A / C 的理由具体到文件、进程、origin 与许可
- [x] 仓库归属与目录布局明确，含规范修订绑定方式
- [x] 语言、锁定版本与包管理方式明确，并说明为何不用 Kotlin/JVM、Rust、Go
- [x] Gateway / Worker / Claude 三层 OS 用户边界明确，含**代价说明**与替代方案
- [x] 公网 WSS 与私有 Unix socket 协议边界明确，且互不泄露
- [x] 存储表最小集合覆盖 ticket 哈希、设备注册表、凭证/刷新证明、
      指纹幂等、有界事件缓冲、终态 receipt、Phase 1 generation 状态
- [x] Secret / prompt / 回答 / stderr 的禁止落盘与日志规则为白名单式
- [x] health/ready 与安全状态枚举明确，`readyz` 语义与 `healthz` 区分
- [x] binary 版本、SHA-256 与安全 argv 明确；**未编造哈希**，改为强制填写且缺失即拒绝启动
- [x] 对 CLI 关键 flag 做了独立核对，并按**置信度**区分「字面量存在」与「语义已确认」；
      两处未确认的语义假设被显式标出，并绑定**证伪实验**与阻断条件（§11.2.1）
- [x] systemd sandbox 与目录权限逐项列出，含 T2 的三处刻意差异与 `~/.claude.json` 易漏项
- [x] 三方协议兼容矩阵与升级顺序明确，一致性语料作为漂移防线
- [x] 回滚点四要素与清理策略明确，含撤销语义

**结论：本 ADR 自洽，可以提交用户确认。在确认 §15 的 1–3 项之前，不得创建服务端仓库、
不得新增依赖、不得开始 CP1-C1。**

### 16.1 本 ADR 中**未经实测**因而不得当作既成事实的内容

如实标注，避免后续实现把"文档写了"当成"验证过了"：

| 内容 | 状态 |
|---|---|
| CLI flag 的**存在性** | 已在本机 2.1.226 二进制中核对字面量 |
| CLI flag 的**语义**（`--tools ""`、`--setting-sources ""`、`--bare`、`--safe-mode`、`--disable-slash-commands`） | **未确认**，需 C2 实测 |
| `--input-format stream-json` 的 stdin 形状与是否可与 prompt 参数共存 | **未确认**，需 C2 实测 |
| `--session-id` 自生成 + 跨进程 `--resume` | **未确认**，需 C2 实测（§11.2.4） |
| Claude Code 当前最新稳定版本与目标平台 SHA-256 | **未在目标机核验**，需 C2 preflight 生成 `claude-pin.json` |
| `claude auth status` 的默认输出格式 | **未确认**，需实测 |
| 本 ADR 引用的官方文档 | 本轮 WebFetch 被环境拦截，来源为检索摘要与本地二进制字面量；**实施前须复跑 `07` §3 的复核清单** |
