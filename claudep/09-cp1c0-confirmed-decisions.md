# 09｜CP1-C0 最终决定（用户已确认）

状态：**已确认，可进入 CP1-C1**
日期：2026-09-21
上位文档：`claudep/08-cp1c-gateway-worker-adr.md`（本文件为其 §15 未决项的**最终裁决**）
纠正依据：`claudep/reports/CP1C0-correction-audit-yanlan-worker-reuse.md`
本轮模型调用数：**0**

> 本文件记录用户对 CP1-C 服务端架构的**最终决定**。
> `08` 的 §1.2 / §2 中「檐岚没有 Claude Code Worker」一组结论**已被 superseded**，
> 但 `08` 原文保留不改，以保留决策过程记录。凡本文件与 `08` 冲突处，**以本文件为准**。

---

## 1. 九项最终决定

| # | 决定 | 说明 |
|---|---|---|
| 1 | **用户确认拥有 `yanlan-dev` 相关 Yanlan 代码的权利**，可授权选定安全执行子集复用，并以 AGPL-3.0 发布 | 见 §4。本文档不记录任何身份信息 |
| 2 | 创建**新的私有 GitHub 仓库** `maocomet/rikkahub-claude-p-server` | 见 §5 |
| 3 | 新仓库**自第一次提交起采用 AGPL-3.0** | 见 §5 |
| 4 | 技术栈 **Node.js + TypeScript** | 见 §5 |
| 5 | Gateway 状态存储采用 **SQLite + WAL** | 见 §5 |
| 6 | Yanlan Worker 与 Claude P Worker **保持两个独立进程** | 见 §5.2 |
| 7 | Claude P 自身采用**两层权限边界**，不使用 sudo / setuid launcher | 见 §6 |
| 8 | 可共享同一 CC VPS、root-owned 固定 binary 与代理出口；**socket / service token / cwd / MCP config / 临时目录 / 运行状态 / SQLite / systemd unit 必须独立** | 见 §5.3 |
| 9 | **CP1-C1 严格零模型**：不启动 Claude Code、不读 OAuth、不连 Anthropic、不部署 VPS | 见 §7 |

### 1.1 决定 6 的三条否定式（同等重要，不可只读肯定式）

- **不整体复用 Yanlan Worker**；
- **不实现同进程双 profile**（`08` 方案 3，已拒绝）；
- **不让手机访问 Yanlan Backend**（`08` 方案 C / 方案 4，已拒绝）。

---

## 2. 已被 superseded 的旧结论（重申）

`claudep/08-cp1c-gateway-worker-adr.md` §1.2 使用的取证对象 `D:\yannan` 停在 **Gate 6**
（HEAD `4f7408b`，分支 `dev`），**不是**完成 Gate 7/8 的最新集成 worktree。
由此得出的下列结论**失效**：

| 旧结论 | 现状 |
|---|---|
| 「Yanlan 没有 Claude Code Worker」 | **错误**。`src/cc-worker/` 存在 |
| 「Yanlan 没有 Unix socket 实现」 | **错误**。`server.ts:43-57` 已实现 |
| 「只能复用思想，不能复用现有执行结构」 | **部分失效**。通用执行核心**可复用**（C2 抽取） |
| 「无生产代码可复用（檐岚根本没有 Worker）」（§2.1） | **错误** |

**檐岚当前实现的唯一有效依据**：`D:\yannan.worktrees\v0.2-gate5-gate6`
（HEAD `5a221096`，分支 `codex/v0.2-gate5-gate6-integration`）。

仍然成立的旧结论：檐岚**无许可证**、**无 Gateway**、主后端已决定不放 Claude、
拒绝方案 A / 方案 C 的全部理由、以及 §7 / §9 / §11.2 / §13 的各项安全约束。

---

## 3. Yanlan binary 门禁的事实（必须记录，避免照抄）

**檐岚目前只有版本字符串检查，没有 binary SHA-256 门禁。**

- 实现：`src/cc-worker/main.ts:87-92` 以 `execFileSync(binaryPath, ['--version'])`
  取输出并做 `output.includes(expectedClaudeVersion)` 子串比对，不符即抛错；
- `CLAUDE_CODE_VERSION=2.1.236`、`CLAUDE_BINARY=/opt/yanlan/runtime/claude/2.1.236/claude`
  （`deploy/systemd/yanlan-cc-worker.service.example:20,24`）；
- 全 `src/cc-worker/` **无任何 binary 哈希逻辑**。该目录内 `createHash('sha256')` 仅两处，
  均与 binary 无关：`server.ts:219-220`（service token 常量时间比较）、
  `session-coordinator.ts:17`（config hash）。

**因此**：`08` §11.1 要求的「官方发布 SHA-256 清单比对」**不得**被视为已由檐岚实现，
Claude P 的 C2 preflight **必须自行补齐**，不能照抄檐岚现状。

---

## 4. 权利确认记录（不含身份信息）

**用户已确认**：其对 `yanlan-dev` 相关的 Yanlan 代码拥有权利，
并授权 Claude P 项目**选定安全执行子集**进行复用，且该复用产物以 **AGPL-3.0** 发布。

**本记录刻意不包含**：用户真实姓名、邮箱、账号、组织或其他个人信息。
仓库侧可核验的客观事实（仅此三项，作为可追溯依据）：

1. `maocomet/yanlan` 全部提交的历史作者标识为**单一** `yanlan-dev <yanlan-dev@local>`；
2. 后端 `src/cc-worker/` 的 14 个提交**全部**出自该同一标识；
3. 后端**无** `vendor/` / `third_party/` 目录，即**无第三方代码**需要单独清理
   （前端 `ui/vendor/` 的 vendored 代码与本次复用无关）。

**复用时的义务**（AGPL-3.0 仓库必须履行）：

- 在抽取所得文件/目录标注**来源**：`yanlan` 仓库 URL 与抽取时的 commit SHA；
- 保留原作者的著作权声明；
- 不得因「权利人本人复用」而省略来源标注——AGPL 对**下游**用户的可追溯性要求，
  与权利人能否复用是两个独立问题。

---

## 5. 仓库、技术栈与进程边界

### 5.1 仓库

| 项 | 决定 |
|---|---|
| 服务端仓库 | **新建** `maocomet/rikkahub-claude-p-server`（**private**） |
| 默认分支 | `main` |
| 许可证 | **AGPL-3.0**，自第一次提交起 |
| 语言 / 运行时 | TypeScript / Node.js |
| 存储 | **SQLite + WAL**（仅 Gateway 层） |
| prompt 内容 | **不落盘**（`08` §8 / §9 不变） |

`rikkahub-agent1`（本仓库）**继续**持有：协议规范 `claudep/02`、Android 客户端、
以及**语言中立的 conformance corpus** `claudep/conformance/`（权威来源）。

### 5.2 两个独立 Worker 进程

- 运行 **`yanlan-cc-worker`** 与 **`rikkahub-claude-p-worker`** 两个进程；
- **不整体复用** Yanlan Worker（其 argv 按构造总是挂载檐岚 MCP，见纠正审计 §3.6）；
- **不实现**同进程双 profile（拒绝理由见 `08` §7 与纠正审计 §5.3）；
- 手机**不得**访问 Yanlan Backend。

### 5.3 共享 / 不共享清单

| 可共享 | 必须独立 |
|---|---|
| 同一台 CC VPS | socket |
| root-owned 固定 binary | service token |
| 代理出口 | cwd |
| Claude 账号（凭据各自注入） | MCP config |
| | 临时目录 |
| | 运行状态 |
| | SQLite |
| | systemd unit |

---

## 6. 权限边界：两层（本文件**取代** `08` §6 与 §6.2）

`08` §6 设想的「Gateway / Worker / Claude 三用户 + sudo setuid launcher」**作废**，
改为**两层**。依据：檐岚的真实形态本就是两层
（`deploy/systemd/yanlan-cc-worker.service.example:8,12`——`User=claude` 直接 spawn，
无 launcher），且已被 Gate 7 验收。

| 层 | 用户 | 职责 | 绝对禁止 |
|---|---|---|---|
| **T1 Gateway** | `cp-gateway` | 监听公网 WSS；读写自己的 SQLite；通过私有 socket 调用 Worker | 读取 Claude 配置 / OAuth；spawn 任意程序；读 Worker 可写目录 |
| **T2 Worker + CLI** | `cp-claude` | 直接运行 Claude P Worker，并由其 spawn 固定 binary 的 Claude Code 子进程 | 监听任何 TCP 端口；被手机直连；读取 Yanlan 的 tool runtime socket |

**硬性要求**：

1. **所有 unit 保留 `NoNewPrivileges=true`**（两层结构下不再需要 setuid，故无 `08` §6.2 的让步）；
2. **无 sudoers 规则、无 setuid launcher、无 `claude-p-exec` 之类的中介程序**；
3. 因此 `08` §6.2「T2 不能设 `NoNewPrivileges`」的代价说明**一并作废**，
   `RestrictSUIDSGID=true` 也可正常启用；
4. `08` §12.1 的目录布局中 `/usr/local/libexec/claude-p-exec` **不再需要**；
5. `08` §12.3 worker unit 中「本 unit 不设 NoNewPrivileges / RestrictSUIDSGID」的注释**作废**。

> **代价说明（如实记录）**：两层意味着 Worker 进程与其 spawn 的 Claude 子进程
> **同属一个 Linux 用户**，二者之间没有 OS 级隔离。这是**刻意的取舍**，
> 代价是以「Worker 代码本身不得被攻陷」为前提。补偿措施：
> 单一纯函数 argv builder（无 shell）、构造式 env 白名单、
> `--tools ""`、固定 binary、以及 Gateway 侧不持有任何 Claude 凭据。

---

## 7. CP1-C1 的严格零模型范围

**允许**：协议解析、配对、设备注册表、WSS、序号、generation 状态机、幂等、receipt、
SQLite/WAL、结构化日志、**fake** Worker adapter、conformance 语料。

**禁止**（任一被「临时放开以验证」即视为本轮失败）：

- 启动或探测真实 Claude binary；
- 读取 Claude OAuth 或 Claude 配置；
- 连接 Anthropic（出站请求数必须为 **0**）；
- 接入代理；
- 工具、MCP、附件、resume / fork；
- 任意 shell、任意 argv / env / cwd / binary path；
- OpenAI-compatible 或 Anthropic Messages 兼容路由；
- TCP 形式的 Worker；
- VPS / DNS / TLS / systemd 部署；
- 复制 Yanlan 的 MCP、Tool Runtime、专属 prompt、Drizzle/Postgres coordinator；
- **复制 Yanlan Worker 代码**——通用核心抽取属于 **C2**，不属于 C1。

---

## 8. Gate CP1 状态（不得误记）

- **Gate CP1 仍未完成**；
- CP1-A（Android Provider 骨架）、CP1-B（Android 安全传输与配对）**已完成**（CI + 真机）；
- CP1-C 分为 **C1 → C2 → C3 → C4**，本轮只做 **C1**；
- **C1 / C2 / C3 / C4 均不得标记为完成**；
- C4 是 Gate CP1 的唯一真实模型验收，必须**单独授权**（`08` 与实施计划 §6）。

**升级顺序**（不变）：Worker → Gateway → Android。

---

## 9. 对 `08` §15 未决项的最终裁决

| # | 未决项 | 裁决 |
|---|---|---|
| 1 | 服务端仓库名与归属 | **`maocomet/rikkahub-claude-p-server`，private** |
| 2 | 存储选型 | **SQLite + WAL** |
| 3 | T2→T3 降权机制 | **取消**——改为两层，无 sudo（§6） |
| 4 | Gateway 子域名 / 证书 / 反代 | 仍未决；**C3 前**必须定 |
| 5 | 是否只支持一个 Gateway 实例 | 建议第一版一个；**未在本次授权范围内裁决** |
| 6 | 多设备是否共享同一 Claude session | 建议每设备独立；**未在本次授权范围内裁决** |
| 7 | 附件保留时间 | Phase 4 再定 |
| 8 | Android 侧凭证刷新 | 独立小阶段；**客户端仍未实现**，不得当作已实现 |
| 9 | ADR 是否移到 `docs/adr/` | **维持现状**（`claudep/08`） |
| 10 | `--setting-sources ""` 证伪实验 | 保留为 **C2 阻断性实验**（`08` §11.2.1） |
| 11 | cwd 稳定性与 session 恢复 | 保留为 **C2 实测项**；檐岚「单一固定 cwd」提供另一种已验证形态 |
| 12 | 是否接受 setuid 让步 | **不需要了**（§6） |

---

## 10. 下一动作

1. 本轮执行 **CP1-C0 收口 + CP1-C1（零模型 Gateway）**；
2. C1 全部代码提交在 `rikkahub-claude-p-server` 的 `codex/cp1-c1-gateway` 分支，**不 push**；
3. Android 侧 conformance 语料与测试独立提交，**不 push**；
4. 完成后停在**原 Codex 复审点**；C2 之前不得抽取任何 Yanlan 代码。
