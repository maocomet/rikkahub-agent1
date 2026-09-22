# CP1-C0 纠正审计｜檐岚 CC Worker 的真实形态与最小安全复用边界

状态：**只纠正事实并重新决策；本轮不写任何服务端代码**
日期：2026-09-21
触发原因：上一轮 ADR（`08-cp1c-gateway-worker-adr.md`）§1.2 基于**错误的 Yanlan checkout**（`D:\yannan`，Gate 6 时点）得出「檐岚没有 Claude Code Worker」，该结论**错误**。
本轮模型调用数：**0**；本轮服务端代码：**0 行**；本轮生产文件修改：**0 个**

---

## 0. 结论速览

| 上一轮结论 | 本轮裁定 | 依据 |
|---|---|---|
| 「Yanlan 没有 Claude Code Worker」 | **SUPERSEDED —— 错误** | §2.2 |
| 「Yanlan 没有 Unix socket 实现」 | **SUPERSEDED —— 错误** | §2.2、§3.1 |
| 「只能复用思想，不能复用现有执行结构」 | **部分 SUPERSEDED**：执行核心可复用；**MCP/工具/DB 耦合部分不可复用** | §3.6、§5 |
| 「必须立即创建全新 `claude-p-server` 仓库」 | **结论仍成立，但理由全部更换** | §6.1 |
| 拒绝方案 C / 方案 4（手机直连檐岚后端） | **仍成立**，且已在最新 worktree 逐条复验 | §5.4、§7 |
| 拒绝方案 A（单仓） | **仍成立** | 未受本次纠正影响 |

**本轮最终答案**：不是「必须新建 Worker」，而是——
**Claude P 应当复用檐岚已被 Gate 7 验证的「Claude CLI 进程执行核心」，但必须作为 `src/cc-worker/` 中一个明确可切分的子集被抽取出去，运行在独立的第二个 Worker 进程里。整体复制 `src/cc-worker/` 是危险的，因为该目录按构造总是加载檐岚 MCP 与工具面。**

---

## 1. 工作区与 Git 真实状态（先取证，后结论）

### 1.1 Claude P 侧

| 项 | 实测值 | 命令 |
|---|---|---|
| 工作区 | `D:\rikkahub-agent1.worktrees\claudep-cp1b` | `pwd` |
| HEAD | `ae2ac91f6190ed264183e77e7b6abc4acd9e14df` | `git rev-parse HEAD` |
| 分支 | `codex/claudep-cp1b-local` | `git rev-parse --abbrev-ref HEAD` |
| 工作区洁净 | **洁净**（`git status --porcelain` 空） | — |
| 远端对应分支 | `origin/codex/claudep-cp1b-local` = `1bed639978c78946b2466dec759e1eb6b8dfaa17` | `git rev-parse --verify` |
| 推送状态 | **`ae2ac91f` 未 push**（本地领先远端 1 个提交） | `git branch -vv` 显示 `[origin/…: ahead 1]`；`git merge-base --is-ancestor ae2ac91f origin/…` 返回**非零** |
| 祖先链 | `1bed6399` **是** `ae2ac91f` 的祖先；`c00f6f3d`（master 设计基线）**是** `ae2ac91f` 的祖先 | `git merge-base --is-ancestor` |

**核验结论**：提示中「当前 Claude P 本地文档提交可能包含未 push 的 `ae2ac91f`」**属实**。
本轮**不 reset、不 rebase、不 amend、不丢弃任何既有文档历史**；本轮新增提交将直接以 `ae2ac91f` 为父提交，使 `ae2ac91f` 继续留在祖先链上（并因此成为不可丢弃的一环）。

### 1.2 Yanlan 侧

| 项 | 实测值 |
|---|---|
| **指定依据（正确）** | `D:\yannan.worktrees\v0.2-gate5-gate6` |
| HEAD | `5a2210962f590d15a030d3db49e4678196e7fbea` |
| 分支 | `codex/v0.2-gate5-gate6-integration` |
| 工作区洁净 | **洁净** |
| 最近提交 | `5a22109 docs: record Gate 8 attachment validation` |

| **错误依据（上一轮 ADR 实际使用的）** | `D:\yannan` |
|---|---|
| HEAD | `4f7408b5f7fe6016a1fa767999d09378b013c938`（Gate 6 时点） |
| 分支 | `dev`（领先 `origin/dev` **17** 个提交） |
| `src/cc-worker/` | **不存在**（`ls` 报 `No such file or directory`） |

### 1.3 上一轮错误的根因（可复现）

上一轮 ADR §1.2 的表格逐行标题写作「`D:\yannan`（檐岚）现状」，
其中的取证命令在该检出上执行，得到「没有 Worker / 没有 Unix socket」——
这些观察**对 `D:\yannan` 本身是真的**，但 `D:\yannan` 停在 Gate 6，**不是完成 Gate 7/8 的最新集成 worktree**。

因此：**上一轮不是取证不严，而是取证对象错误。** `D:\yannan` 必须在本项目内被明确标记为**不得作为 Yanlan 当前实现依据**。

---

## 2. 事实纠正：檐岚 CC Worker 确实存在

### 2.1 存在的直接证据

| 证据 | 位置 |
|---|---|
| npm script | `package.json:18` → `"start:cc-worker": "node dist/cc-worker/main.js"` |
| Worker 源码目录 | `src/cc-worker/`（14 个文件） |
| 进程入口 | `src/cc-worker/main.ts` |
| 私有服务 | `src/cc-worker/server.ts`（20743 字节） |
| 生产 systemd 模板 | `deploy/systemd/yanlan-cc-worker.service.example` |
| Gate 7 staging 模板 | `deploy/systemd/yanlan-cc-worker-gate7-staging.service.example` |
| 部署约定文档 | `ops/README.md:100` |

`src/cc-worker/` 文件清单：`adapter.ts`、`cli-adapter.ts`、`events.ts`、`fake-adapter.ts`、`index.ts`、`main.ts`、`ndjson.ts`、`quota.ts`、`server-coordinator.ts`（实为 `session-coordinator.ts`）、`session.ts`、`state-machine.ts`、`server.ts`、`stderr.ts`。

### 2.2 提示中「已知纠正事实」的逐条复核结果

| 提示所述 | 复核裁定 | 证据 |
|---|---|---|
| `package.json` 中的 `start:cc-worker` | ✅ **属实** | `package.json:18` |
| `src/cc-worker/` 目录存在 | ✅ **属实** | 目录清单 |
| `src/cc-worker/main.ts` | ✅ **属实** | 文件存在 |
| `src/cc-worker/server.ts` | ✅ **属实** | 文件存在 |
| Unix socket Worker | ✅ **属实** | `main.ts:64` `CC_WORKER_SOCKET`；`server.ts:43-57` `listen(socketPath)`；`server.ts:48` chmod `0o660` |
| 固定 Claude binary / version 门禁 | ⚠ **部分属实**：**版本有，SHA-256 没有** | `main.ts:87-92` 只做 `--version` 子串比对；全目录无 binary 哈希校验（见 §3.3） |
| stream-json 解析 | ✅ **属实** | `ndjson.ts` + `events.ts` + `cli-adapter.ts:191-235` |
| cancel | ✅ **属实** | `server.ts:208-215`；`cli-adapter.ts:184-189` |
| active run | ✅ **属实** | `server.ts:26,31,133,202` |
| 终态处理 | ✅ **属实** | `state-machine.ts`；`cli-adapter.ts:237-244,288-297` |
| Tool Runtime / MCP Bridge | ✅ **属实** | `src/mcp-bridge/`；`deploy/claude-code/mcp.strict.example.json` |
| systemd 部署约定 | ✅ **属实** | `deploy/systemd/yanlan-cc-worker.service.example` |
| `HTTP_PROXY`/`HTTPS_PROXY`/`NO_PROXY` 向 CLI 受控传递 | ✅ **属实** | 单元 `:32-34` 定义；`main.ts:59-62` 仅白名单三项透传 |

**逐条复核的意义**：提示给出的 13 条中 12 条完全属实、1 条（SHA 门禁）被高估。
本审计**未因提示而直接采信任何一条**，全部回到源码与单元文件重新取证。

---

## 3. 十五项实际实现审计（只读，含文件与行号）

### 3.1 Worker 运行用户、Group、socket owner/mode

`deploy/systemd/yanlan-cc-worker.service.example`：

| 项 | 值 | 行 |
|---|---|---|
| `User=` | `claude`（独立 nologin 用户） | `:8` |
| `Group=` | `yanlan` | `:9` |
| `WorkingDirectory` | `/opt/yanlan/current` | `:10` |
| `RuntimeDirectory` | `yanlan`，mode `0770` | `:37-38` |
| `StateDirectory` | `yanlan-cc`，mode `0700` | `:39-40` |
| socket 路径 | `/run/yanlan/cc-worker.sock` | `:18` |

socket 属主/模式：由 worker 进程创建后 **chmod `0o660`**（`server.ts:48` `this.options.chmodSocket?.(socketPath, 0o660)`，注入点 `main.ts:144`）。
Gate 7 真实环境已实测该值：**Worker socket `claude:yanlan 0660`**、Tool Runtime socket `yanlan:yanlan 0660`，均可连接
（`reports/v0.2开发/Gate7-R3-真实环境验收报告-2026-08-25.md:197-199`）。
后端以 `User=yanlan` / `Group=yanlan` 运行（`ops/yanlan.service:26-27`），靠**主组**取得 socket 访问权。
全仓**无任何 unit 使用 `SupplementaryGroups=`**（grep 无命中）。
`ops/README.md:100` 明确：独立 `claude` nologin 用户、`Group=yanlan`、不开放公网端口。

Worker **无 TCP 面**：`listen()` 只接受 socket 路径（`server.ts:43-57`），
且 `CC_WORKER_SOCKET` 必经 `absolute()`（`main.ts:32-36`）——
`host:port` 形式**无法通过**该校验，生产入口**不可能**绑定 TCP。

> **⚠ 生产环境存在一处真实的、与 staging 不同步的共享目录缺陷（重要发现）**
>
> 檐岚**已经诊断并修复过**这个问题，但**只修了 staging**：
> `Gate7-R3:189-206`（§12.1）记录根因——「Backend 单独拥有 `RuntimeDirectory=yanlan-gate7`，
> 而 Worker 在同一路径创建 socket；Backend 单独 restart 时 systemd 可删除目录，
> 使仍运行的 Worker 丢失可达 socket」，修复提交 `e9f8cca` 改用
> `/etc/tmpfiles.d/yanlan-gate7-staging.conf` 创建稳定目录（`yanlan:yanlan 0770`），两个 unit 均不再拥有该目录。
> 报告 `:205` 明确该修复**只覆盖隔离 staging**：「只更新隔离 staging 的两个 unit 和一个 tmpfiles 配置」。
>
> **生产侧未同步**：
> - `deploy/tmpfiles.d/` 目录下**只有** `yanlan-gate7-staging.conf`，**没有**生产版；
> - 生产两个 unit **仍然各自声明**同一个 `RuntimeDirectory=yanlan`，且模式**冲突**：
>   `ops/yanlan.service:58-59` = `0750`，`yanlan-cc-worker.service.example:37-38` = `0770`。
>
> 因此生产环境**同时保留**了 staging 已修复的两个问题：
> ① 后端单独 restart 可能删除 `/run/yanlan`，使仍在运行的 Worker 丢失 socket；
> ② `0750` 生效时组**无写位**，`claude` 用户无法在该目录内创建 socket。
>
> **本轮不做任何修改**（生产 systemd 属明令禁止范围）。列入 §8 复核清单，
> 并作为「**Claude P 必须使用自己的 socket 目录、不得与檐岚共用 `/run` 路径**」这一设计要求的直接证据。
> 注：以上为**仓库文本**证据，实际部署状态需在目标机 `systemctl show` / `stat` 确认。

### 3.2 Claude OAuth / Token 如何进入 Worker（不输出任何 Secret 值）

链路：**root-owned EnvironmentFile → systemd → 进程环境 → 构造式 CLI env → 子进程**。

1. `EnvironmentFile=/etc/yanlan/cc-worker.secret.env`（`yanlan-cc-worker.service.example:35`）；
2. 单元注释 `:36` 声明：该文件 `root:claude 0640`，**只含三个变量**：`CC_SERVICE_TOKEN`、`CLAUDE_CODE_OAUTH_TOKEN`、`YANLAN_TOOL_RUNTIME_TOKEN`；
3. `main.ts:51` `const oauthToken = required('CLAUDE_CODE_OAUTH_TOKEN')`；
4. `main.ts:52-54` **显式拒绝** `ANTHROPIC_API_KEY`（非空即抛错），理由写在错误串里：与订阅 OAuth 身份冲突；
5. `main.ts:55` `cliEnv.CLAUDE_CODE_OAUTH_TOKEN = oauthToken` —— 进入**构造**出的 `cliEnv`，不是 `process.env` 展开；
6. `cli-adapter.ts:57-63` 以 `{ ...this.options.env, … }` 传入 `spawn`。

`.env.example:106` 同样声明三个 token「仅放 root-owned EnvironmentFile，绝不提交」。

**本审计未读取、未输出、未记录任何 Secret 值；仅记录变量名与文件路径。**

### 3.3 固定 binary 路径、expected version、SHA / 版本检查

| 项 | 值 | 位置 |
|---|---|---|
| binary 绝对路径 | `/opt/yanlan/runtime/claude/2.1.236/claude` | 单元 `:20`（环境变量 `CLAUDE_BINARY`） |
| 期望版本 | `2.1.236` | 单元 `:24`（`CLAUDE_CODE_VERSION`） |
| 路径必须绝对 | 是 | `main.ts:32-36` `absolute()`；`main.ts:65` |
| 存在性检查 | 是 | `main.ts:82-84` |
| 版本检查 | `--version` 输出**子串**匹配 | `main.ts:87-92` |
| **SHA-256 校验** | **不存在** | 全 `src/cc-worker/` 无 binary 哈希逻辑 |

`main.ts:87-92` 原文要点：以 `execFileSync(binaryPath, ['--version'], { timeout: 5_000, windowsHide: true })`
取输出并 `output.includes(expectedClaudeVersion)`，不符即抛错。
注释 `:86` 声明该探针**不发送 prompt、不做模型请求**。

`createHash('sha256')` 在本目录内**只出现两处**，均与 binary 无关：
`server.ts:219-220`（service token 常量时间比较）、`session-coordinator.ts:17`（config hash）。

> **结论**：提示所述「binary/version/SHA 门禁」中，**SHA 部分不成立**。
> 檐岚实现的是「**固定路径 + 精确版本字符串 + 目录 root-owned 不可写**」三重约束，
> 而非 `08` §11.1 所要求的「官方发布 SHA-256 清单比对」。复用时应补齐，不应照抄现状。

### 3.4 Claude CLI env 如何构造，是否只传 allowlist

**是构造式 allowlist，不是「继承后删除」。** 构造点 `main.ts:44-62`：

| 来源 | 变量 | 行 |
|---|---|---|
| `process.env` 校验后转入 | `PATH`（每段必须是绝对路径，`main.ts:39-42`） | `:39-45` |
| `required` | `HOME` | `:46` |
| `required` | `CLAUDE_CONFIG_DIR` | `:43,47` |
| 常量 | `DISABLE_AUTOUPDATER=1`、`DISABLE_UPDATES=1` | `:48-49` |
| `required` | `CLAUDE_CODE_OAUTH_TOKEN` | `:51,55` |
| `required` | `YANLAN_TOOL_RUNTIME_TOKEN` | `:56` |
| `required` | `YANLAN_TOOL_RUNTIME_SOCKET` | `:57-58` |
| 白名单透传 | `HTTP_PROXY` / `HTTPS_PROXY` / `NO_PROXY`（非空才传） | `:59-62` |

`spawn` 时再覆盖/追加（`cli-adapter.ts:57-63`）：`DISABLE_AUTOUPDATER`、`DISABLE_UPDATES`、
`YANLAN_GENERATION_ID`、`YANLAN_AGENT_ID`。

**结构上不可能出现的变量**（因为 env 是构造的，不是继承的）：
`ANTHROPIC_API_KEY`（且被 `main.ts:52-54` 显式拒绝）、`ANTHROPIC_AUTH_TOKEN`、`ANTHROPIC_BASE_URL`、
`CLAUDE_CODE_USE_BEDROCK` 等。

> **与 `08` §11.2.3 的差距**：檐岚 env **未**包含
> `CLAUDE_CODE_DISABLE_AUTO_MEMORY=1`、`DISABLE_TELEMETRY=1`、`DISABLE_ERROR_REPORTING=1`、
> `CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1`。复用时应按本 ADR 的白名单补齐。

### 3.5 `HTTP_PROXY` / `HTTPS_PROXY` / `NO_PROXY` 的来源与传递方式

- **来源**：systemd unit 的 `Environment=` 行，**不是**代码默认值、**不是** `EnvironmentFile`。
  生产 `:32-34` 与 staging 同名三行，值均为 loopback 形式（本审计**不转录任何可能带凭据的代理 URL**）。
- **传递**：`main.ts:59-62` 逐项判断「存在且非空」才写入 `cliEnv`，随后随构造 env 进入 `spawn`。
- **边界**：只在**启动 Claude CLI 子进程**这一条路径上生效；worker 自身不据此配置任何 HTTP dispatcher。
- **`NO_PROXY` 覆盖范围**：`localhost,127.0.0.1`。

> **⚠ 两点必须复核的边界问题**
> 1. `NO_PROXY` **未包含 Unix socket 路径**。本实现中 worker、tool runtime、MCP bridge 全部走 `AF_UNIX`，
>    Node 的 `http.request({ socketPath })` **不经**代理，因此当前无实际影响；
>    但若将来引入任何 `http://localhost` 形式的内部调用，现有 `NO_PROXY` 已够用，**无需**扩展。
>    真正需要确认的是：**不得**出现任何需要绕过代理的、非 loopback 的内部主机名。
> 2. 全仓 `grep` 未发现任何 `ProxyAgent` / `setGlobalDispatcher`（见 §3.5 复核命令）。
>    这意味着代理是**对子进程的环境约定**，由 Claude CLI 自行读取。
>    `quota.ts:199-200` 使用全局 `fetch`（undici），**undici 默认不读 `HTTP_PROXY`**；
>    该路径由 `CC_QUOTA_ENDPOINT_ENABLED=0` 关闭（单元 `:26`），当前无实际影响，
>    但**若将来启用 quota 端点，必须显式配置 dispatcher 或确认其可达性**。

### 3.6 当前 argv 是否总是加载 Yanlan MCP config / allowed tools

**是，且无可关闭的路径。** 三处均为**无条件**：

| 位置 | 内容 |
|---|---|
| `cli-adapter.ts:53` | `buildClaudeStreamArgv({ …, mcpConfigPath: this.options.mcpConfigPath, … })` —— 必然传入 |
| `session.ts:57-62` | `if (input.mcpConfigPath !== undefined) argv.push('--mcp-config', …)` —— 必然命中 |
| `session.ts:48` | `'--allowedTools', buildYanlanMcpAllowedTool(YANLAN_MCP_SERVER_NAME)` —— **无条件追加** |
| `session.ts:52` | `'--strict-mcp-config'` —— **无条件追加** |

`mcpConfigPath` 来自**进程级**环境变量 `YANLAN_MCP_CONFIG`（`main.ts:67`），
在启动时经 `required()` + `absolute()` 强校验（`main.ts:38-79`），并由
`validateStrictMcpConfig`（`main.ts:96-113`）**fail-closed** 校验：

- 必须恰好 **1** 个 server，且名字必须是 `yanlan-mcp-bridge`（`main.ts:102`）；
- `command` 必须是绝对路径（`:106-108`）；
- 出现 `url` / `httpUrl` / `sseUrl` 即拒绝 —— 只允许 stdio（`:109`）。

`buildYanlanMcpAllowedTool`（`session.ts:19-23`）同样硬编码：server 名不等于
`YANLAN_MCP_SERVER_NAME` 即抛错，禁止第二个 MCP 命名空间。

> **这是本轮最关键的结构性发现。**
> 檐岚 Worker **在构造上无法以「无 MCP」形态运行**：它是「必须挂载且只能挂载檐岚 MCP bridge」的进程。
> 任何形式的「整体复用该 Worker」都会把**檐岚工具面**一并交给调用方。

### 3.7 是否存在真正的「tools disabled」执行配置

**部分存在，且不足以满足 Claude P Phase 1。**

| 层级 | 状态 | 位置 |
|---|---|---|
| 内置工具 | **已禁用** —— `'--tools', ''` | `session.ts:46`（注释 `:45` 说明空值关闭全部内置工具） |
| 斜杠命令 | 已禁用 —— `--disable-slash-commands` | `session.ts:50` |
| 用户/项目 settings | `--setting-sources ''`（语义**未验证**，见 `08` §11.2.1） | `session.ts:50` |
| 内置 MCP 自动发现 | 已阻断 —— `--strict-mcp-config` | `session.ts:52` |
| **`--disallowedTools`** | **不存在** | 全目录 grep 无命中 |
| **`--bare` / `--safe-mode`** | **不存在** | 全目录 grep 无命中 |
| **檐岚 MCP 工具面** | **始终开启，无法关闭** | `session.ts:48,57-62` |

结论：檐岚有「**关闭内置工具**」的配置，但**没有**「**关闭工具**」的配置。
`08` §11.2.1 要求的 `--disallowedTools` 纵深防御在檐岚中**未实施**（因其 MCP 是既定能力，不是待封锁对象）。

### 3.8 Gateway/Backend 能否向 Worker 传入任意 argv、env、cwd、binary 或 MCP 路径

**不能。** `POST /v1/generate` 的可接受字段穷举（`server.ts:96-131`）：

| 字段 | 校验 | 去向 |
|---|---|---|
| `generation_id` / `conversation_id` / `agent_id` | 必须匹配 ULID（`server.ts:374-377`） | 仅作 active run 键 |
| `params.cc_session` | `parseSession`（`server.ts:308-313`）只接受 `new` / `resume` / `fork` + sessionId | 映射为 `--resume` / `--fork-session` |
| `params.timeout_ms` / `first_byte_timeout_ms` / `idle_timeout_ms` | `finite()` 仅接受正有限数 | 仅作本地定时器 |
| `model` | `isClaudeCliVersionModel` 拒绝版本标签（`server.ts:104-114`） | 映射为 `--model` |
| `messages` / `system_prompt` | 字符串化 / 长度上限 | prompt 正文与临时 system prompt 文件 |
| 其他任意字段 | **被忽略** | — |

**不存在**的字段：`argv`、`env`、`cwd`、`binary`、`mcp_config`、`socket_path`、`allowed_tools`、`permission_mode`。
cwd 固定在进程级 `CC_WORKDIR`（`main.ts:66`）；binary 固定在 `CLAUDE_BINARY`（`:65`）；
MCP 路径固定在 `YANLAN_MCP_CONFIG`（`:67`）。三者都在**启动时**由 `absolute()` 校验，**运行期不可变**。

> **⚠ 唯一残留的调用方影响力：`model` 不是 allowlist 校验。**
> `assertClaudeModel`（`session.ts:30-34`）只拒绝两种情况：空/以 `-` 开头、形如 CLI 版本号。
> **任意其他字符串**（例如任意模型别名、任意长字符串）都会被原样送入 `--model`。
> 这不是理论风险 —— **檐岚已经在真实环境因该字段发生过一次事故**：
> `reports/v0.2开发/Gate7-R3-真实环境验收报告-2026-08-25.md:441-467` 记录 CLI 版本标签被当作 model
> 传入真实调用，事后在 `session.ts` 补充版本标签识别作为 fail-fast，报告 `:467` 同时声明
> 「**没有**新增全局 Claude 模型白名单」。
> 复用该代码时，Claude P 侧**必须**在 `08` §11.2.2 要求的 allowlist 上补齐这一步。

### 3.9 active run、cancel、terminal、crash recovery 与并发限制

| 机制 | 实现 | 位置 |
|---|---|---|
| active run 表 | `Map<generationId, {conversationId, handle}>` | `server.ts:31,133,202` |
| 会话互斥 | 同 `conversation_id` 已有活跃 run → **409** | `server.ts:116` |
| 全局并发上限 | `active.size >= maxActive` → **503 `provider_overloaded`** | `server.ts:115` |
| **并发默认值** | **`2`**（`options.maxActiveProcesses ?? 2`） | `server.ts:38` |
| 并发是否被生产收紧 | **否** —— `main.ts:139-152` 未传 `maxActiveProcesses`，生产即取默认 `2` | — |
| cancel | `POST /v1/abort` → `handle.abort()`；未知 id 返回 `status:'error'` 而非误报 | `server.ts:208-215` |
| cancel 幂等 | `abort()` 在已有 terminal/requested 时直接返回 | `cli-adapter.ts:184-189` |
| 终止升级链 | `SIGINT` → `terminateGraceMs` → `SIGTERM` → `SIGKILL` | `cli-adapter.ts:246-255` |
| 唯一终态 | `finish()` 只生效一次（`if (this.terminal) return`） | `cli-adapter.ts:237-244` |
| 终态判定 | **由 `result` 事件决定，不由退出码决定** | `cli-adapter.ts:257-262,288-297` |
| 退化情形 | `!validSession \|\| !completedResultSeen` → `invalid_completed_session` | `cli-adapter.ts:295` |
| 三种超时 | run / first-byte / idle | `cli-adapter.ts:168-174,273-286` |
| 关闭收敛 | `close()` 先 abort 全部 handle，再等 completion，再关 server | `server.ts:59-64` |
| **crash recovery** | **不在 Worker 进程内** —— 由后端 `CcSessionCoordinator.finish()` 写 DB | `session-coordinator.ts:74-90`（`:86` 将非 completed/aborted 映射为 `interrupted`） |

> **重点**：`maxActive = 2` 是**每个 Worker 进程**的上限。
> 若采用两个 Worker 进程，则同一 Claude 账号的**真实并发上限变成 4**，
> 而不是任何单一进程所声明的数字。这一点必须在 §6 的推荐方案里显式收口。

### 3.10 session ID 与 cwd 的关系

**檐岚使用单一固定 cwd。** `CC_WORKDIR=/var/lib/yanlan-cc/workspace`（单元 `:21`），
在 `main.ts:66` 经 `absolute()` 校验后传给 `ClaudeCliAdapter`，**不随 conversation / thread 变化**。

session 血缘完全交给 Claude Code 自己：

- 新建：`session_id: ''`（`cli-adapter.ts:179`）；
- `--resume <id>` / `--fork-session`（`session.ts:69-73`），id 受 `SESSION_ID` 正则约束（`session.ts:13`）；
- init 事件捕获 session id，并**双向校验**：`resume` 必须相同、`fork` 必须不同
  （`cli-adapter.ts:207-217`，`server.ts:365-370`）；
- 不一致即 `invalid_completed_session` 并终止（`cli-adapter.ts:288-297`）。

**后果（对复用决策至关重要）**：由于 Claude 把 session 持久化在
`<config dir>/projects/<cwd 编码后的目录名>/<uuid>.jsonl`，
**cwd 是 session 命名空间的天然分隔符**。
两个系统若使用**不同的 cwd**，其 session 存储目录**结构性不重叠**，即使共用同一个 `CLAUDE_CONFIG_DIR`。

`session-coordinator.ts:20-38` 另有一条**配置轮换**规则：`configHash` 变化时
强制执行 `mode:'new'` 并清除旧绑定（`:27-29`），注释 `:25-26` 说明理由为
「不得让新配置继承在旧授权下创建的 session」。这条规则对 Claude P 同样适用，且**必须保留**。

### 3.11 service token 是全局还是可按 client/profile 隔离

**全局单一 token，无任何 client 身份概念。**

- 单一 `CC_SERVICE_TOKEN`（`main.ts:71`），长度下限 16（`server.ts:37`）；
- 校验函数 `authorized()` 返回 **boolean**，不返回身份（`server.ts:217-222`）；
- 比较方式：两侧 SHA-256 后 `timingSafeEqual`（`:219-221`），无提前返回；
- 所有已鉴权路由共用同一判断（`server.ts:76`）。

→ **`08` §7 所设想的「按 client identity 绑定 profile」在檐岚中完全不存在**，
这是 §5.3（方案 3）被拒绝的直接技术依据。

### 3.12 Worker 私有协议能否增加版本化 Claude P surface，而不暴露 Yanlan 业务 API

**技术上可以，但当前协议没有版本轴。**

现状（`server.ts:66-94`）是一个 **HTTP-over-Unix-socket** 服务，路由穷举为：

| 路由 | 鉴权 | 行 |
|---|---|---|
| `GET /healthz`、`GET /v1/status` | **不需要** | `:69-71` |
| `GET /readyz` | **不需要** | `:72-75` |
| `GET /v1/usage` | 需要 | `:77-79` |
| `GET /v1/quota` | 需要 | `:80-85` |
| `POST /v1/abort` | 需要 | `:86` |
| `POST /v1/generate` | 需要 | `:87` |

响应体自报 `service: 'yanlan-cc-worker'`（`:70,74`）。

**未暴露**任何檐岚业务 API：无 conversation、无 provider、无 MCP、无 auth、无 sync、无附件路由。
因此从「不泄露业务 API」角度看，这个私有面是**干净**的。

**但**：`08` §7 设想的 `worker.hello` / `abi: "claude-p-worker/1"` **握手并不存在** ——
本实现只有 `/healthz` 里一个 `version` 字符串（`CC_WORKER_VERSION`，默认 `'0.2-gate7'`，`main.ts:73`），
没有 ABI 协商、没有版本不匹配即拒绝。

而 `serializeCcContext`（`server.ts:325-361`）输出的 `YANLAN_CONTEXT_SEQUENCE_V2`
是**檐岚专有的 prompt 协议**，`parseSession`、`selectCcPromptMessages`（`:315-317`）同样带檐岚语义。

> **结论**：可以新增 `/v1/claude-p/*` 路由而不触碰业务 API，
> **但**该 surface 会与 Yanlan surface 共享同一个进程、同一个 token 判断、同一个
> `CC_WORKDIR`、同一个 MCP 配置 —— 即 `08` §7 要求「两条协议不得混用」的前提被破坏。
> 这正是 §5.3 拒绝方案 3 的第二条依据。

### 3.13 同一 Claude 账号被两个调用方使用的风险

| 风险 | 机制 | 现状 |
|---|---|---|
| **额度** | 同一 OAuth 身份 → 账号级 rate limit 共享，worker 无法感知对方 | `main.ts:51-55` 单一 token；`quota.ts:184-210` 的额度端点默认关闭（单元 `:26`） |
| **并发** | 每进程上限 `2`（`server.ts:38`）→ 两进程实际可达 **4** | 无跨进程协调机制 |
| **session 串线** | 若共用 `CLAUDE_CONFIG_DIR` **且**共用 cwd，则 `projects/<编码 cwd>/` 同目录；session id 为 UUID 本身不冲突，但**目录内容混放**，且 `--resume` 一旦拿到对方 id 即可命中 | 檐岚单 cwd（`:21`/`main.ts:66`）→ 若 Claude P 复用同一 cwd 即**无隔离**；**使用不同 cwd 即可结构性隔离**（§3.10） |
| **`.claude.json` 争用** | 账号元数据写在 **HOME 根**的 `.claude.json`，**不在** `CLAUDE_CONFIG_DIR` 内；檐岚 `HOME=/var/lib/yanlan-cc`（单元 `:30`） | 两进程若共用 HOME 会并发写同一文件 |

### 3.14 Gate 7 已验证的不变量；哪些改动会迫使重新做真实模型验收

**Gate 7 状态**：`Gate7-R3` 报告 `:1350`「Gate 7 可标记为**完成，等待原 Codex 总复审**」、
`:1404`「R4 唯一硬门禁现已通过…停在原 Codex 最终复审点」；
`檐岚-v0.2-开发任务书.md:57` 记为 `[x] 完成`。

#### 3.14.1 已验证的不变量（真实 VPS + 真实模型）

**基础设施 / 进程边界**

1. **socket 属主与生命周期**：Worker `claude:yanlan 0660`、Tool Runtime `yanlan:yanlan 0660`；
   初始 / Backend-only restart / Worker-only restart 三种场景下均恢复且可连接，`active_runs=0`、Claude 子进程=0
   —— `Gate7-R3:197-203`；
2. **空闲态 crash recovery**：`active_runs=0` 门禁后 SIGKILL unit MainPID，systemd 在 30 秒内以新 PID 恢复 active/ready
   —— `Gate7-R3:915`；
3. **活跃态 crash recovery**（`active_runs=1`，Worker 与 Backend 各自）：PID 变更、Generation `failed/stream_interrupted`、
   run 收敛为合法终态、binding `interrupted` 且 active generation 为空、`terminal=1`、其他 active=0 —— `Gate7-R3:1275`。

**真实模型生命周期（唯一授权调用）**

4. **new-session**：SSE 序列 `generation.started,message.started,text.delta,message.completed,generation.completed`，
   唯一终态，最终文本严格等于 `SEED`，binding 回到 `idle` —— `Gate7-R3:527`；
5. **resume / fork**：resume `requested_matches_seed=true`、`captured_matches_requested=true`，输出严格 `RESUME`；
   fork 为不同 conversation、`captured_is_distinct=true`、源 binding 未被覆盖，输出严格 `FORK` —— `Gate7-R3:534-535`；
6. **cancel**：终态 `Generation=aborted`、事件恰好 `…generation.aborted`、`terminal=1` 且无重复终态；
   重复 abort 返回 200 `status=aborted` 且前后 DB 快照逐字节相同、`generation.aborted` 仍恰好 1 条 —— `Gate7-R3:573,641-647`。

**自主 MCP 闭环（Gate 7 的头号结论）**

7. **端到端**：`Phase B success=true`、`Generation completed`、`CC run completed`、`binding idle`、
   `fixture call=1`、`tool.started=1`、`tool.completed=1`、`terminal=1`、其他工具=0、最终输出严格匹配 `MCP_OK`
   —— `Gate7-R3:1241`；
8. **独立只读 DB 审计全部为真**：冻结快照恰为两个头像工具 + 单个 echo 工具；echo 的归属、`parameters` schema
   与只读标注正确；`tool_call` 唯一且参数精确、状态 `completed`；持久化结果与事件结果一致；
   `tool.started` 先于 `tool.completed`；唯一终态 `generation.completed`；唯一输出 `MCP_OK` —— `Gate7-R3:1243`；
9. **完整链路可核查**：`Backend→External Runtime→Worker→Claude assistant.tool_use→Yanlan Bridge tools/call→Tool Runtime→fixture→tool_result→MCP_OK`
   —— `Gate7-R3:1263`；
10. **零模型 Bridge 确定性收口**：`tool count=3`、`echo present=true`、`fixture call=1`；
    未知工具 / 错误 Generation / 错误 Agent / 同 external ID 不同指纹**全部 fail-closed** —— `Gate7-R3:870`。

**额度、OAuth 与只读召回**

11. **额度分类不伪造**：对官方 `/api/oauth/usage` 真实得到 `usage_unauthorized`（401/403）→
    `supported=false, available=false, stale=false, windows=0`，**不输出 token / Authorization / 响应体**，
    **不把未知显示为 0** —— `Gate7-R3:911`；开关关闭时为 `reason=endpoint_disabled` 且 `upstream requested=false` —— `:876`；
12. **Backend 侧独立 OAuth 与只读召回**：`oauth=true, connected=true`；上游 `tool_count=13` 被策略收窄为唯一 `breath_search`
    （`readonly=true`）；唯一真实召回 `status=completed`、`untrusted_reference=true`、公开内容全部 `[redacted]`
    —— `Gate7-R3:1310,1312,1318`。

**运维卫生（每次真实运行的收口条件）**

13. 每轮结束 `active_runs=0`、`Claude 子进程=0`、`active Generation=0`、非 idle DB 会话=0、journal `Secret marker=0`
    —— `Gate7-R3:521,576,1302`；临时 `_test` 库 / 脚本 / fixture 精确删除 —— `:61,1402`。

**结论 4–6 与 1–3、11–13 是「真实模型无关」的不变量**（零模型即可验证）；
**7–10、12 依赖真实的 `assistant.tool_use` 与 MCP 往返**，是**必须真实模型**才能取得证据的部分。

#### 3.14.2 哪些改动会迫使重新做真实模型验收

**必须如实说明：檐岚文档中不存在一份统一的「失效矩阵」。** 实际存在的是三条**成文规则**，
其余为本审计据 Gate 7 实践作出的**推断**（已明确标注，不得当作已文档化的约定）：

**成文规则（有据可查）**

| # | 规则 | 出处 |
|---|---|---|
| R1 | **锁定已验证的 Claude Code 版本；升级前必须跑协议 smoke，不跟随自动更新上线** | `檐岚-v0.2-开发任务书.md:278` |
| R2 | **任何后续模型请求必须重新取得用户明确授权**（逐次，不延续） | `檐岚-v0.2-开发任务书.md:718,758` |
| R3 | **已通过的历史证据不因后续失败或未重跑而回滚** | `Gate7-R3:905,1376` |

**R1 已被真实触发过一次，且触发它的恰好是「CLI 版本漂移」本身**：
`Gate7-R3:1001` 记录整个运行因 `claude --version` 返回 `2.1.236 (Claude Code)`
而**中止**，并明确「该结果是 staging CLI 版本漂移，不是 Claude、Prompt、allowedTools、permission、
Bridge 或工具解析失败证据」；`:1005` 要求「下一次模型复验须先由原 Codex 决定恢复锁定 `2.1.231`，
或对 `2.1.236` 完成独立审计，并重新取得用户授权」；
`:1007-1027` 记录了 2.1.232–2.1.236 的**零模型兼容性审计与重新锁定**（提交 `ed48d9b fix: pin Claude worker to 2.1.236`）。

> **对复用的直接价值**：这条规则给出了一个**已被验证有效的降级路径**——
> **版本漂移时先用「零模型兼容性审计」取得结论，再决定是否重做真实模型验收**，
> 而不是无条件重跑真实调用。Claude P 抽取 Worker 时应沿用同样的纪律。

**推断（本审计的合理外推，非文档化约定）**

- 改动 `src/cc-worker/` 中参与 **argv 构造 / env 构造 / 终态判定 / session 捕获**的源文件，
  以及改动 `CLAUDE_BINARY` / `CLAUDE_CODE_VERSION` / `YANLAN_MCP_CONFIG` / `CC_WORKDIR`，
  逻辑上都落在 R1 的适用范围内；
- 改动 `yanlan-cc-worker.service` 的 `User` / `Group` / `Environment` / `ReadWritePaths`，
  或 MCP bridge / tool runtime 的协议与 socket 路径，同理。

> **对复用的直接约束（此条不受上述不确定性影响）**：
> **不得就地修改 `src/cc-worker/`。**
> 无论重验门槛的确切边界在哪里，**把改动做在檐岚之外**都能让 Gate 7 的全部结论（含 R1–R3 的适用前提）
> 保持有效——这是优先级第 1 条（不破坏已验收的 Gate 7）唯一可接受、且**不需要额外真实验收**的落地方式。
> 抽取必须是「**复制到新仓库后在新仓库内改造**」，檐岚侧保持逐字节不变。

### 3.15 源码著作权 / 许可归属

**事实（已复验）**：

| 项 | 值 | 证据 |
|---|---|---|
| `LICENSE` / `COPYING` / `NOTICE` | **均不存在** | 根目录 `ls` |
| `package.json` | `"private": true`，**无 `license` 字段** | `package.json:2-4` |
| README 许可声明 | **无** | `README.md` 头部 |
| `vendor/` / `third_party/`（**后端**） | **不存在** | 根目录与 `src/` 检查 |
| **`ui/vendor/`（前端，存在）** | `dompurify`、`fonts`、`highlight`、`katex`、`licenses`、`lucide`、`markdown-it` —— **均带许可证文本** | `ui/vendor/README.md:5-17`；`ui/vendor/licenses/` 下 9 份许可证 |
| `ui/vendor/README.md:17` 声明 | 「完整许可证文本位于 `licenses/`。**本实现未复制或改写 RikkaHub 的 AGPL 源码。**」 | `ui/vendor/README.md:17` |
| **檐岚的 AGPL 规避是明文的项目约束** | 「**不直接复制 RikkaHub 的 AGPL-3.0 源码**；只研究交互和测试行为，使用许可证兼容依赖或自行实现」 | `檐岚-v0.2-开发任务书.md:23`（另见 `:167,177,985`） |
| **全仓作者** | **仅 `yanlan-dev <yanlan-dev@local>`，289 个提交** | `git log --format='%an <%ae>' \| sort \| uniq -c` |
| **`src/cc-worker/` 作者** | **仅 `yanlan-dev`，14 个提交** | `git log --format='%an <%ae>' -- src/cc-worker/` |
| 该目录首次引入 | `c3e9900 feat(gate7): add local Claude Code worker foundation`（2026-08-25） | `git log --diff-filter=A` |

**必须区分的三层，不能简化成「没有 LICENSE 所以谁都不能复用」**：

1. **权利人**：仓库无 LICENSE 意味着**未向他人授予**任何权利，
   但它**不剥夺权利人本人**使用自己作品的能力。全仓 289 个提交、`cc-worker` 14 个提交
   **全部出自单一作者**，且工作区无任何 vendored / third-party 代码。
   → 就本仓库现状而言，**不存在需要向第三方清理的权利主张**。
   **（待用户确认的一环）**：本审计只能观测到「单一作者、local 域邮箱、零外部贡献」，
   **无法从仓库本身证明 `yanlan-dev` 与用户为同一法律主体**。该确认只能由用户作出。
2. **外部贡献 / 第三方代码**：
   - **提交作者层面**：无外部贡献 —— 289 个提交全部出自单一作者，无 co-author、无 bot 身份。
   - **后端代码层面**：**未发现** vendored / third-party 代码。`src/cc-worker/` 是纯一手 TypeScript。
   - **前端层面**：**存在** vendored 代码（`ui/vendor/`），但**均已附许可证文本**且已在
     `ui/vendor/README.md:5-17` 登记归属。**与 `src/cc-worker/` 无关**，抽取后端执行核心不触及它。
   - 若未来引入，则必须单独登记来源与许可。

   **一条与复用方向有关的重要事实**：檐岚把「不直接复制 RikkaHub 的 AGPL-3.0 源码」
   写成了**明文项目约束**（`檐岚-v0.2-开发任务书.md:23`）。因此两个方向的许可后果**不对称**：
   - **檐岚 → Claude P（AGPL-3.0 目标仓）**：檐岚代码是无第三方负担的一手代码，
     进入 AGPL 仓库**不产生许可冲突**，只需按第 3 点做来源标注；
   - **RikkaHub AGPL → 檐岚**：这是檐岚**刻意避免**的方向，本审计**不涉及**，
     但抽取时**不得**顺手把 Android 仓的 Kotlin/AGPL 代码反向带入。
3. **放入 AGPL 仓库时的来源与许可保留**：Claude P 侧的目标仓库
   （`08` §3.1 已定 **AGPL-3.0**，依据 `06` D-012）若包含抽取自檐岚的代码，
   则**必须**：
   - 在该文件/目录标注**来源**（`yanlan` 仓库 URL + 抽取时的 commit SHA `5a221096…`）；
   - 标注**无第三方许可负担**这一事实及依据（单作者、无 vendor）；
   - 保留原作者的著作权声明；
   - 不得因「自己写的」而省略来源标注 —— AGPL 对**下游**用户的可追溯性要求，
     与权利人是否可以复用是两个独立问题。

> **裁定**：从著作权角度，**复用路径没有被阻塞**；但**来源标注是必做项**，
> 且「`yanlan-dev` = 用户本人」这一点必须由用户显式确认后才能作为 §6 推荐方案的成立前提。

---

## 4. 代理出口结论

**明确回答四个问题：**

**(1) Claude P Gateway 接收手机 WSS，本身是否需要 Anthropic 代理？**
**不需要。** Gateway 的职责是「公网 WSS 终结 + 协议状态机 + 存储」，
按 `08` §7 它**不得**读 Claude home、不得构造 argv、不得 spawn 子进程。
它**没有任何**到 Anthropic 端点的出站请求（`08` §10 的 `readyz` 只报告枚举与布尔）。
给 Gateway 配代理只会扩大其出站面，收益为零。

**(2) 实际启动 Claude Code 的进程必须如何复用檐岚现有代理出口？**
**通过同一台 VPS 上的同一 loopback 代理端点，以「每个 unit 各自注入」的方式复用，
而不是通过共享文件、共享进程或共享环境变量继承。**
檐岚的现状正是这个形态：代理值写在 **unit 的 `Environment=` 行**（`:32-34`），
由 `main.ts:59-62` 以**白名单三项**透传给 CLI 子进程。
Claude P 的 Worker unit 应采用完全相同的写法，指向同一个代理端点。

**(3) 若运行两个 Worker，代理环境如何通过各自 root-owned EnvironmentFile 注入？**
**必须区分两类值，且只有一类可以进 EnvironmentFile：**

| 类别 | 内容 | 注入方式 |
|---|---|---|
| 非秘密配置 | `HTTP_PROXY` / `HTTPS_PROXY` / `NO_PROXY` | 可直接写在 unit 的 `Environment=` 行（檐岚现状），**或**各自 unit 的 root-owned `EnvironmentFile` |
| 秘密 | `CC_SERVICE_TOKEN`、`CLAUDE_CODE_OAUTH_TOKEN` | **必须**在**各自独立的** root-owned `EnvironmentFile` 中，权限 `0640`、属主 `root:<该 worker 用户>` |

两个 Worker **各自持有独立的 `CC_SERVICE_TOKEN`**（这是 §6 的结构性隔离要求之一），
因此两个 `EnvironmentFile` **内容不同、不得共用**。
`CLAUDE_CODE_OAUTH_TOKEN` 是同一账号，可指向同一凭据来源，但**仍应各自注入**，
以便任一 Worker 的凭据轮换不影响另一个。

**(4) `NO_PROXY` 应覆盖哪些 localhost / Unix-socket 相关通信？**
- **Unix socket 通信不需要 `NO_PROXY` 覆盖**：Node 的 `http.request({ socketPath })`
  根本不经过 HTTP 代理栈（`src/external/unix-fetch.ts:18` 即此形态）。
  把 socket 路径写进 `NO_PROXY` **既无效果也无必要**。
- **必须保留的是 loopback**：`localhost,127.0.0.1`（檐岚现状，unit `:34`）。
  它确保任何 loopback HTTP 调用（健康检查、本地诊断）不被代理截走。
- **必须显式确认的是**：`NO_PROXY` 中**不得**出现任何非 loopback 的主机名，
  否则等于把内部主机名写进一个会被日志与进程环境暴露的变量。
- 若 Claude P 的 Gateway↔Worker 也走 loopback TCP（而非 socket），则其地址**必须**在 `NO_PROXY` 内。

**(5) 代理凭据不得进入 Android / 二维码 / SQLite / Git / 日志 / 协议事件。**
落地约束（与 `08` §9 一致）：
- 代理 URL 若含 `user:pass@`，则**只能**出现在 root-owned EnvironmentFile 或 unit 行；
- **禁止**进入：Android 任何源码或资源、配对二维码内容、Gateway SQLite 任何列、
  任何提交到 Git 的文件（含 `.env.example` —— 檐岚的 `.env.example` 确实**未**写代理值，是正确做法）、
  结构化日志字段白名单、任何公网协议事件、`readyz` 响应；
- 本审计在所有文档中**只记录变量名与边界，不转录任何带凭据的代理 URL**。

---

## 5. 四种复用层级的逐项评估

### 5.1 方案 1｜只复用设计与测试方法

**可行性**：**100%，零风险。**
**评价**：这是**保底方案**，但它在本次纠正之后**不再是最优解** ——
因为它主动丢弃了檐岚已经用真实模型验收过的执行结构（违反优先级第 3 条）。

可借鉴项（檐岚确已验证）：
binary/version 门禁思路、env **构造式** allowlist、proxy 白名单透传、
单一纯函数 argv builder、`IncrementalNdjsonParser` 残行/多字节处理、
`StderrFailureClassifier` 有界分类、`WorkerRunStateMachine` 唯一终态、
`--tools ''` + `--strict-mcp-config` 组合、systemd 加固集合、原子发布 + 回滚实践。

**裁定**：作为**兜底**保留；不作为推荐。

### 5.2 方案 2｜复用代码核心，运行两个独立 Worker

**可行性**：**可行，但必须「抽取子集」而非「整体复制」。**

**为什么不能整体复制 `src/cc-worker/`**（三条硬理由）：

1. **该目录总是加载檐岚 MCP 与工具面** —— `session.ts:48,57-62` 无条件；
   `validateStrictMcpConfig`（`main.ts:96-113`）fail-closed 强制恰好一个 `yanlan-mcp-bridge`。
   整体复制 = 把檐岚工具面交给 Claude P（违反优先级第 2 条）。
2. **该目录混合了两个进程的代码** —— `src/cc-worker/index.ts` 统一 re-export，
   而檐岚**后端**从该目录导入 DB 相关代码：
   `src/server/app.ts:129`（`CcSessionCoordinator`）、
   `src/chat/generation-create.ts:5`（`planCcSession` / `computeCcConfigHash`）、
   `src/chat/types.ts:3`（`FrozenCcSessionPlan` 类型）、
   `src/mcp-bridge/stdio.ts:1`（`sanitizeStderr`）。
   其中 `session-coordinator.ts:2-4` **直接依赖** `../repository/index.js` 与 `../db/db.js`
   ——即 **Drizzle / PostgreSQL**。
   整体复制会把 Postgres 依赖拖进一个本应「无持久化」的 Worker（违反 `08` §8「Worker 无持久化」）。
3. **该目录含檐岚专有 prompt 协议** —— `serializeCcContext` 产出 `YANLAN_CONTEXT_SEQUENCE_V2`
   （`server.ts:349-360`），Claude P 不应继承。

**可抽取的安全执行核心（明确清单）**：

| 文件 | 抽取判定 | 说明 |
|---|---|---|
| `adapter.ts` | ✅ 整体 | 纯接口，零依赖 |
| `ndjson.ts` | ✅ 整体 | 纯解析器 |
| `events.ts` | ⚠ 需裁剪 | `classifyCcEvent` 可留；檐岚特有的字段名摘要需复核 |
| `stderr.ts` | ✅ 整体 | 但 `:1` 依赖 `../log/redact.js`，需一并抽取或替换 |
| `state-machine.ts` | ✅ 整体 | 纯状态机 |
| `cli-adapter.ts` | ⚠ **需改造** | 进程生命周期/超时/终态保留；**必须删除 MCP 形参并把 `--mcp-config` 分支整个删掉**（不是参数化） |
| `session.ts` | ⚠ **需改造** | `buildClaudeStreamArgv` 保留骨架；**删除 `buildYanlanMcpAllowedTool` 与 `--allowedTools`/`--strict-mcp-config`/`--mcp-config`**；**新增 model allowlist**（补 `08` §11.2.2） |
| `quota.ts` | ⚠ 可选 | 与 Claude P 的额度展示相关，但涉及额外出站面，Phase 1 建议**不要** |
| `server.ts` | ⚠ **仅作模板** | 鉴权/SSE/active run 结构可参考；**服务名、token 语义必须重写** |
| `main.ts` | ❌ **不抽取** | 檐岚专有配置装配 |
| `session-coordinator.ts` | ❌ **不抽取** | 依赖 DB，属后端层 |
| `fake-adapter.ts` | ✅ 整体 | 零模型测试必须 |
| `index.ts` | ❌ | 不抽取（它是混合目录的 re-export） |

**运行时形态**：两个进程、两个 socket、两个 service token、两个状态目录；
可共享固定 binary、Claude 登录、代理出口。

**裁定**：**推荐**（见 §6），但以「抽取子集」形态落地。

### 5.3 方案 3｜同一个 Worker，两个严格隔离的 client profile

**裁定：明确拒绝。** 四项能力**全部缺失**，且补齐后隔离仍是**代码级**的：

| 要求 | 檐岚现状 | 位置 |
|---|---|---|
| 按 client identity 绑定 | **完全没有身份概念**，`authorized()` 只返回 boolean | `server.ts:217-222` |
| 调用方不可选择或升级 profile | **无 profile 概念**；单一全局 token | `main.ts:71` |
| 独立 socket / token / namespace | 单一 socket（`main.ts:64`）、单一 token（`:71`） | — |
| 独立 cwd / session 命名 | cwd 为**进程级**常量，运行期不可变 | `main.ts:66`；`cli-adapter.ts:56` |
| 并发隔离 | 单一 `active` 表 + `maxActive=2`，**不分 profile** | `server.ts:31,38` |

**Why 即使补齐也不该做**（对应优先级第 2 条）：

1. **argv 是进程级常量，不是请求级变量。**
   `--mcp-config` 与 `--allowedTools` 来自 `ClaudeCliAdapterOptions`（`cli-adapter.ts:11-23`），
   在 `main.ts:126-132` **一次性**注入。要让 profile A 无 MCP、profile B 有 MCP，
   必须把 `mcpConfigPath` 从**构造期**下推到**每请求**——
   即把「是否有工具」变成一个**运行期分支**。这正是 `08` §7 第 1 条禁止的形态：
   「公网协议里不存在能够影响私有协议的字段…所有映射都是 Gateway 内的编译期常量」。
2. **单一 token 判断是唯一的越权点。** 一个 `authorized()` 的 bug（或 token 泄漏）
   在方案 2 下只能影响**一个系统**；在方案 3 下可跨 profile 升级。
   `08` §7 第 2 条要求「私有协议里的错误不直接外泄」，而方案 3 让两个信任域共用同一条边界。
3. **「无工具」保证会与「有工具」路径同进程。** 优先级第 2 条要求
   「Claude P 无法越权到 Yanlan 工具」，方案 3 只能提供「**大概率不会**越权」，
   方案 2 提供「**结构上无法**越权」（不同用户、不同 socket、不同 token、不同进程）。
4. **回滚耦合。** 一个进程同时服务两个系统 → 升级/重启任一系统即影响另一个（违反优先级第 4 条）。

**图 2（见 §6.4）展示这条越权路径。**

### 5.4 方案 4｜手机直接复用 Yanlan Backend / API

**裁定：明确拒绝，且本轮已在「正确」的 worktree 上逐条复验。**

**同 origin / 同进程 / 同库 —— 三条全部成立：**

| 断言 | 复验结果 | 位置 |
|---|---|---|
| 同进程 | ✅ 所有业务路由注册在**同一个 Fastify 实例** | `src/server/app.ts:107-125`（auth / conversation / message / provider-connection / mcp-server / sync / generation / attachment / tool-call / tool-approval / runtime-config / avatar / agent …） |
| 同库 | ✅ 且 `app.ts:129` **从 `cc-worker` 导入 `CcSessionCoordinator`** —— Worker 的 session 状态与业务数据同库 | `src/server/app.ts:129` |
| 同 origin | ✅ 单实例单 origin | 同上 |
| **限流默认关闭** | ✅ **仍成立**：`rateLimitEnabled: parseBoolean(env.RATE_LIMIT_ENABLED, false)` | `src/config/load.ts:114` |
| **非浏览器客户端绕过 origin-check** | ✅ **仍成立**：`if (origin === undefined) return true; // 非浏览器客户端 → 放行` | `src/server/plugins/origin-check.ts:24-25`（整体 `:36-56`） |

**ADD 一条上一轮未写的理由（本轮新发现）**：
`src/server/app.ts:129` 让**后端进程本身**持有 `cc-worker` 的代码依赖。
这意味着「手机直连后端」与「Worker 执行引擎」之间的边界在后端进程内**已经被打破过一次**。
把手机再接到同一个 origin，等于把 §3.12 中那个「干净的私有面」重新暴露在公网协议同一位置。

**结论**：用路由前缀（如 `/v1/claude-p/*`）做隔离是**伪装**——
前缀不改变 process / origin / database / rate-limit 默认值 / origin-check 放行策略中的任何一个。
**拒绝，且不得以路由前缀形式变相实施。**

---

## 6. 推荐架构

### 6.1 推荐：**方案 2（抽取子集）**，而非「新建一切」或「同一 Worker 双 profile」

| 优先级 | 该方案如何满足 |
|---|---|
| 1. 不破坏已验收的 Gate 7 | **不就地修改 `src/cc-worker/`**；抽取 = 复制到新仓库后在新仓库内改造；檐岚 unit / socket / 环境变量 / 源码逐字节不变（§3.14） |
| 2. Claude P 无法越权到 Yanlan 工具或业务 API | 独立 Linux 用户（不在 `yanlan` 组）、独立进程、独立 socket、独立 token、argv builder **无 MCP 分支**（§6.2） |
| 3. 不重复实现已验证的执行逻辑 | 复用 `ndjson` / `events` / `stderr` / `state-machine` / 进程生命周期 / 终态判定（§5.2 清单） |
| 4. 独立回滚 | 独立仓库、独立 unit、独立 release 目录、独立 `claude-pin.json` |
| 5. 实现成本 | 抽取 + 裁剪 > 从零重写；但**低于**方案 3 所需的 profile 体系重构 |

### 6.2 推荐架构必需回答的九个问题

| 问题 | 决定 |
|---|---|
| 是否创建新 GitHub 仓库？ | **是** —— `maocomet/claude-p-server`（名称沿用 `08` §15 未决项 1） |
| 是否保留 `rikkahub-agent1` 作为 Android/协议规范仓库？ | **是，不变。** 协议规范 `claudep/02` 仍在 Android 仓，服务端以它为准 |
| Gateway 代码放在哪里？ | **新仓库** `src/gateway/` |
| Worker 代码是复用、抽取还是独立实现？ | **抽取**（§5.2 清单）：复制到新仓库 → 删除 MCP 分支 → 补 model allowlist → 补 env 白名单缺失项 |
| 同一 Worker 进程还是两个？ | **两个进程**（拒绝方案 3，§5.3） |
| 是否共享 Claude 登录、固定 binary 和代理？ | **共享代理出口与 binary 版本线；Claude 登录共享账号但各自注入凭据**（§4） |
| SQLite/WAL 放在哪一层？ | **仅 Gateway**（`08` §8 不变）。Worker **无持久化**，且**不得**依赖 Postgres（这正是 `session-coordinator.ts` 不抽取的原因） |
| 怎样避免两个系统同时调用同一 Claude 账号造成并发/session 冲突？ | **① cwd 结构性隔离**（§3.10）：Claude P 使用自己的 `CC_WORKDIR`，session 落在不同的 `projects/<编码 cwd>/` 下；**② 跨进程并发收口**：见 §6.3；**③ 配置轮换仍强制 `mode:'new'`**（保留 `planCcSession` 规则，但在新仓库内以无 DB 方式实现） |
| 怎样保证 Claude P Phase 1 永远无法启用 Yanlan MCP/工具？ | 五重**结构**保证，见 §6.2.1 |

#### 6.2.1 Claude P Phase 1 无法启用 Yanlan MCP/工具的**结构**保证

不依赖「记得不要传 `--mcp-config`」，而是依赖**代码路径不存在**：

1. **argv builder 中不存在 MCP 参数**。Claude P 的 builder 不接收 `mcpConfigPath` 形参，
   源码中**不出现** `--mcp-config`、`--strict-mcp-config`、`--allowedTools` 字面量。
   → 不是「默认关闭」，是**没有这个分支**。
2. **`--tools ''` 保持**，且**新增 `--disallowedTools`**（补 `08` §11.2.2 对 `--allowedTools` 不是可见性白名单的修正）。
3. **OS 用户层**：Claude P Worker 以独立用户运行，**不属于 `yanlan` 组**；
   檐岚工具面 `/run/yanlan/tool-runtime.sock` 位于 `RuntimeDirectory=yanlan`（`ops/yanlan.service:58-59`），
   Claude P 用户**无权限打开**。
4. **systemd 层**：Claude P 的 unit **不声明** `YANLAN_TOOL_RUNTIME_SOCKET` / `YANLAN_MCP_CONFIG`，
   且 `ReadWritePaths` **不包含** `/run/yanlan`。
5. **协议层**：Claude P Worker 的进程级配置中不存在 `mcpConfigPath` 字段
   → 运行期无法通过任何请求把它打开。

### 6.3 并发收口（跨进程）

真实约束：两个 Worker 各自 `maxActive = 2`（`server.ts:38`），但**共享同一个 Claude 账号**。

| 层级 | 措施 | 阶段 |
|---|---|---|
| Phase 1 | Claude P Worker 自身 `maxActiveProcesses = 1`（显式传入，**不要**沿用默认 2） | C2 |
| Phase 1 | Claude P Phase 1 是**单设备、纯文本、无工具**，天然低并发 | C1–C4 |
| Phase 1 | 账号级限流失败**如实**映射为 `rate_limit` 安全错误（`stderr.ts:16` 已有该分类），**不自动重试**（`08` B6） | C2 |
| Phase 2（若需要） | 引入一个 root-owned 的最小互斥（如 `flock` 文件或 systemd `Conflicts=`），两个 Worker 各自在 dispatch 前获取 | 后续独立评审 |

> **必须写清楚的取舍**：Phase 1 **不**引入跨进程互斥。
> 理由：引入它需要两个系统协调部署，违反优先级第 1 条（不碰檐岚）与第 4 条（独立回滚）。
> 代价是「用户同时在檐岚与本机 Claude P 发起请求时，可能收到 `rate_limit` 失败」——
> 该失败是**安全的、可观测的、不越权的**，且用户可感知。
> 这是**刻意的能力让步**，与 `08` §6.2 对 setuid 让步的处理方式相同：写明代价，不隐藏。

### 6.4 架构图

#### 图 1｜推荐方案的进程、socket、用户、状态目录、代理与 binary 关系

```text
                        ┌─────────────────────────── 手机 (Android CP1-B) ───────────────────────────┐
                        │  EC P-256 设备密钥（Keystore，不可导出）；无 Claude 凭证；无代理凭据        │
                        └───────────────────────────────────┬────────────────────────────────────────┘
                                                            │ WSS  wss://<origin>/v1/claude-p/stream
                                                            │ subprotocol: rikkahub.claude-p.v1
                                                            ▼
   ┌────────────────────────────────────────────────────────────────────────────────────────────────┐
   │ T1  claude-p-gateway.service     User=cp-gateway  Group=cp-gateway                              │
   │     ├─ StateDirectory=claude-p-gateway   →  SQLite + WAL（唯一持久化层）                         │
   │     ├─ 无到 Anthropic 的出站请求；不读 Claude home；不 spawn 子进程        （§4(1)）              │
   │     └─ 监听 /run/claude-p/gateway.sock（0660 cp-worker:cp-bridge）                              │
   └───────────────────────────────────────┬────────────────────────────────────────────────────────┘
                                           │ 私有 ABI（HTTP-over-Unix-socket + Bearer）
                                           ▼
   ┌────────────────────────────────────────────────────────────────────────────────────────────────┐
   │ T2  claude-p-worker.service      User=cp-worker   Group=cp-worker  ※ 不属于 yanlan 组           │
   │     ├─ StateDirectory=claude-p-worker   →  无持久化（不含 Postgres 依赖）                        │
   │     ├─ socket: /run/claude-p/gateway.sock      token: CP_SERVICE_TOKEN（独立）                   │
   │     ├─ cwd: /var/lib/claude-p/work/<thread>/    ← 与檐岚 cwd 不同 ⇒ session 目录结构性隔离        │
   │     ├─ argv builder 中【不存在】--mcp-config / --allowedTools 分支          （§6.2.1 第 1 条）    │
   │     └─ spawn（shell:false，无降权 setuid）                                                       │
   └───────────────────────────────────────┬────────────────────────────────────────────────────────┘
                                           │ 固定 argv（纯函数构造，shell:false）
                                           ▼
   ┌────────────────────────────────────────────────────────────────────────────────────────────────┐
   │ T3  固定 Claude Code binary                                                                     │
   │     /opt/claude-p/claude/<version>/claude     root:root 0755，服务用户不可写                     │
   │     CLAUDE_CONFIG_DIR=<共享凭据目录>（只读为主）   HOME=<claude-p 自己的 home>                    │
   │     env: 构造式 allowlist（PATH/HOME/CLAUDE_CONFIG_DIR/DISABLE_*/CLAUDE_CODE_OAUTH_TOKEN）       │
   └───────────────────────────────────────┬────────────────────────────────────────────────────────┘
                                           │  HTTP(S)_PROXY（白名单三项透传）
                                           ▼
                            ┌──────────────────────────────────────────┐
                            │  本机 loopback 代理出口（与檐岚同一端点）  │
                            └──────────────────────────────────────────┘

   ┌──────────────────────── 檐岚（本轮完全不改动，Gate 7 结论继续有效）────────────────────────────┐
   │ yanlan.service        User=yanlan Group=yanlan   ← 业务 API / MCP / Provider / auth / sync      │
   │ yanlan-cc-worker.service  User=claude Group=yanlan                                              │
   │     ├─ /run/yanlan/cc-worker.sock（0660 claude:yanlan）                                         │
   │     ├─ /run/yanlan/tool-runtime.sock   ← Claude P 用户【无权打开】        （§6.2.1 第 3 条）      │
   │     └─ argv【始终】含 --mcp-config + --allowedTools mcp__yanlan-mcp-bridge__*                    │
   └─────────────────────────────────────────────────────────────────────────────────────────────────┘
```

**共享物**：固定 binary 的**版本线**、Claude 账号、代理出口端点。
**不共享物**：进程、socket、service token、状态目录、cwd、argv builder、部署 unit、发布节奏。

#### 图 2｜为什么「同一 Worker + 两个 client profile」（方案 3）被拒绝

```text
                    ┌──────────────────────┐        ┌──────────────────────┐
                    │  Yanlan Backend      │        │  Claude P Gateway    │
                    │  （信任域 A）         │        │  （信任域 B）         │
                    └──────────┬───────────┘        └──────────┬───────────┘
                               │                               │
                               └───────────┬───────────────────┘
                                           │  两条协议【混用】同一 socket
                                           │  ✗ 违反 08 §7「不得混用」
                                           ▼
                        ┌──────────────────────────────────────────┐
                        │        同一个 cc-worker 进程              │
                        │                                          │
                        │   authorized(req) -> boolean   ← 唯一边界 │
                        │   ▲ 单一全局 CC_SERVICE_TOKEN             │
                        │   │  没有 client identity                 │
                        │   │  一个 bug / 一次泄漏 = 跨域升级        │
                        │   │                     ✗ 违反 §7 第 2 条  │
                        │   │                                      │
                        │   ├── Profile A: --mcp-config  ────────► │──► 檐岚 MCP bridge
                        │   │              --allowedTools          │        │
                        │   │                                       │        ▼
                        │   └── Profile B: 无 MCP（期望）           │   檐岚 Tool Runtime
                        │                  ▲                        │   （web-search / avatar /
                        │                  │                        │    memory-recall…）
                        │                  └── ✗ 但 argv 是【进程级常量】，
                        │                        main.ts:126-132 构造期注入，
                        │                        要按 profile 分叉 ⇒ 必须把
                        │                        mcpConfigPath 下推到【每请求】
                        │                        ⇒ 把「有无工具」变成【运行期分支】
                        │                        ✗ 违反 08 §7 第 1 条
                        │                          （所有映射必须是编译期常量）
                        └──────────────────────────────────────────┘

    结论：方案 3 的隔离是【代码级】的；Claude P 只能获得「大概率不会越权」，
          而方案 2 提供「结构上无法越权」。故拒绝方案 3。
          对比：方案 2 下，同样的 bug 只能影响一个系统 —— 因为压根没有第二个系统在同一个进程里。
```

### 6.5 对 `08` §15 未决项的更新

| # | 原未决项 | 本轮状态 |
|---|---|---|
| 1 | 服务端仓库名与归属 | **结论不变**（新建 `maocomet/claude-p-server`），但**理由全部更换**（§6.1） |
| 3 | T2→T3 降权机制（sudo launcher vs 两层） | **建议简化**：Claude P Worker **不需要** setuid 降权。檐岚的 Worker 以 `claude` 用户直接运行（unit `:8`），**没有** sudo launcher。→ 建议**两层**（worker 用户直接运行 CLI），去掉 `08` §6.2 的 setuid 让步 |
| 11 | cwd 稳定性与 session 恢复 | **檐岚已给出实测答案**：单一固定 cwd + Claude 自管 session lineage 可用（Gate 7 已验证 resume/fork），**且 cwd 是 session 命名空间的天然分隔符**（§3.10） |
| 12 | 是否接受 §6.2 的 setuid 让步 | 见 #3 —— 可直接回避 |

> **#3 的说明**：`08` §6 设想的三层边界（gateway / worker / claude 三用户 + sudo 降权）
> 在檐岚的真实实现中是**两层**（`claude` 用户直接 spawn，无 launcher，unit `:8,12`）。
> 既然优先级第 3 条要求复用已验证逻辑，且檐岚的形态已被 Gate 7 验证，**建议采纳两层**。
> 这同时消除了 `08` §6.2 中「T2 不能设 `NoNewPrivileges`」的让步。

---

## 7. 本轮未做与不得做的事（合规记录）

- ❌ 未修改任何 `.ts` / `.kt` / `.kts` / `.yml` / `.service` 生产文件；
- ❌ 未创建任何仓库；❌ 未新增任何依赖；
- ❌ 未连接 VPS；❌ 未读取任何 Secret 值；❌ 未修改代理 / DNS / systemd / 部署；
- ❌ 未调用 Claude / Sonnet（本轮模型调用数 **0**）；
- ❌ 未 push、未触发 CI、未开 PR、未打 tag、未发 Release；
- ❌ 未 reset / rebase / amend / 丢弃任何既有文档历史；
- ✅ Yanlan worktree `D:\yannan.worktrees\v0.2-gate5-gate6` **只读**，工作区保持洁净。

---

## 8. 必须在目标机复核、本机无法验证的清单

本审计遵守 `08` §11.1 的纪律：**不写一个本机无法核验的值**。

| # | 项 | 为何本机不能定论 |
|---|---|---|
| 1 | 生产 `/run/yanlan` 的**实际生效 owner/mode**，以及它是否仍由两个 unit 各自声明（§3.1 的共享目录缺陷） | 取决于 systemd 应用顺序，需 `systemctl show` + `stat`；**staging 已修、生产未见对应修复**，须确认是否有未入库的部署侧变更 |
| 1b | 生产 `/run/yanlan` 在后端单独 restart 后是否仍存在 | staging 已用 tmpfiles.d 验证修复；生产无对应文件 |
| 2 | 代理端点是否**确实**被 Claude CLI 读取并生效 | 全仓无 `ProxyAgent`；由 CLI 自行处理，需目标机实测 |
| 3 | 若启用 `CC_QUOTA_ENDPOINT`，其出站是否经代理 | 使用全局 `fetch`（undici），**默认不读 `HTTP_PROXY`** |
| 4 | Claude CLI 在**共享 `CLAUDE_CONFIG_DIR` + 不同 cwd** 下的 session 隔离是否如预期 | 需在目标机以两个 cwd 实测 `projects/<编码>` 目录分离 |
| 5 | `~/.claude.json` 并发写的影响面 | 需确认两个 Worker 的 HOME 是否分离 |
| 6 | `--setting-sources ''` 的语义证伪实验 | `08` §11.2.1 已列为阻断性实验，`claudep/03` 顶部亦有警示 |
| 7 | 抽取后的 core 在新仓库中的逐字节行为一致性 | 需以 `fake-adapter` 跑零模型回归（`08` C2 范畴） |
| 8 | `yanlan-dev` 与用户是否同一法律主体（§3.15） | **只能由用户确认**，不可由仓库推断 |

---

## 9. 对 `08` ADR 的逐条失效裁定

本节与 `08-cp1c-gateway-worker-adr.md` 新增的
「Superseded after auditing the latest Yanlan integration worktree」小节互为索引。

| `08` 位置 | 原文结论 | 裁定 |
|---|---|---|
| §1.2 标题「`D:\yannan`（檐岚）现状」 | 以 `D:\yannan` 为檐岚现状 | **SUPERSEDED** —— 对象错误，应为 `D:\yannan.worktrees\v0.2-gate5-gate6` |
| §1.2 「是否已有 Claude Code Worker？」= **没有** | — | **SUPERSEDED —— 事实错误** |
| §1.2 「Unix socket 监听」= **没有** | 引 `ops/yanlan.service:56,59` 的预留 | **SUPERSEDED —— 事实错误**（`server.ts:43-57` 已实现） |
| §1.2 「许可证」= 无任何许可证 | 无 LICENSE、无 license 字段 | **仍成立**（本轮复验一致） |
| §1.2 「技术栈」= Node/Fastify/Drizzle/PostgreSQL | — | **仍成立** |
| §1.2 「是否已有 Gateway」= 没有 | REST + SSE，无 WebSocket | **仍成立** |
| §1.2 「主后端是否已决定不放 Claude」 | 引任务书与 release 文档 | **仍成立** |
| §1.2 「必须视为反模式、不得借鉴的两处」 | `run-submodel.sh:9`、集成方案 `:232-241` | **仍成立**（且与 Gate 7 修复后的生产代码**不同**，两者不应混淆） |
| §1.2 「可独立重写的安全思想（不是代码）」 | 7 条 | **部分 SUPERSEDED** —— 不再是「只有思想」，执行核心可复用（§5.2） |
| §2.1 表「与现有 Claude Worker 的复用」= **无生产代码可复用（檐岚根本没有 Worker）** | — | **SUPERSEDED —— 事实错误** |
| §2.1 表 C 列「檐岚已有 Fastify/systemd，但**没有 WSS**、没有 Unix socket」 | — | **一半 SUPERSEDED**：确无 WSS；**有** Unix socket |
| §2.3 拒绝方案 C 的 4 条理由 | 同进程 / 同库 / 同 origin / 违反檐岚自身决定 | **全部仍成立**（本轮在正确 worktree 复验，见 §5.4） |
| §2.4 拒绝方案 A | — | **仍成立**（不受本次纠正影响） |
| §3.1 推荐方案 B（独立仓库） | — | **结论仍成立，理由全部更换**（§6.1） |
| §3.1 「从 `claudep/02` 这一规范**独立实现**，不复制 Android Kotlin 代码」 | — | **仍成立**（且现在**不再**隐含「也不能复用檐岚 TS 代码」） |
| §6 三层权限边界 + §6.2 setuid 让步 | — | **建议修订** —— 檐岚实测形态是两层，无 launcher（§6.5 #3） |
| §7 私有协议 `worker.hello` / `abi` | 设想握手 | **需修订** —— 檐岚实测为 HTTP-over-Unix-socket + Bearer，无 ABI 协商（§3.12） |
| §11.1 binary SHA-256 清单校验 | 要求 | **保留要求** —— 檐岚**未**实现，复用时应补齐（§3.3） |
| §11.2.1 对该 CLI flag 的两处修正 | — | **仍成立**，且被檐岚现状**印证**（檐岚正是用了 `--setting-sources ''` 且未做证伪实验） |
| §11.2.2 禁用参数黑名单 | 含 `--disallowedTools` | **保留** —— 檐岚未实施该纵深防御（§3.7） |
| §11.2.3 env 白名单 | — | **保留** —— 檐岚缺 4 个变量（§3.4） |
| §11.2.4 cwd 与 session 耦合 | 提出按 thread 稳定 cwd | **仍成立**，且檐岚的「单 cwd」实践给出另一种可行形态（§3.10、§6.5 #11） |
| §13 三方兼容矩阵 | — | **仍成立**（Worker ABI 一轴需按 §3.12 改写为实际形态） |

---

## 10. 本轮交付物与复审点

**新增**
- 本文件：`claudep/reports/CP1C0-correction-audit-yanlan-worker-reuse.md`

**修订（仅新增标注，不删除历史）**
- `claudep/08-cp1c-gateway-worker-adr.md`：新增 Superseded 小节 + 逐条失效标注
- `claudep/reports/CP1C0-server-implementation-plan.md`：新增指向本审计的交叉引用
- `claudep/reports/CP1B-ci-evidence.md`：修正指向 `08` §2 的交叉引用
- `claudep/README.md`：索引新增本审计

**未修改**：`claudep/00`–`07` 设计包正文（除上述交叉引用外逐字保留）。

**停止点**：停在**原 Codex 复审点**。在用户回答下列三项之前，
**不得**创建 `claude-p-server` 仓库、不得新增依赖、不得开始 CP1-C1 的第一行服务端代码：

1. 是否接受「方案 2 抽取子集 + 两个 Worker 进程」（§6.1）；
2. 是否确认 §3.15 的权利人问题（`yanlan-dev` 与用户为同一主体）；
3. 是否接受 §6.5 #3 的简化（去掉 setuid 降权，采用两层边界）。
