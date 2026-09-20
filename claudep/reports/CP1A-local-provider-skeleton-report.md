# CP1-A｜本地 Provider 骨架与 fake Gateway — 实施报告

状态：**待复审（未推送、未触发 CI）**
日期：2026-09-20
分支：`codex/claudep-cp1a-local`
Worktree：`D:\rikkahub-agent1.worktrees\claudep-cp1a`

---

## 1. 基线与提交

| 项目 | 值 |
|---|---|
| 权威基线 | `c00f6f3d916ca94468a13e13e15fdffe5e81db1e` |
| 代码与测试的最终提交 | `76b38483` |
| 证据提交（本报告） | 位于上述提交之上；精确值以 `git log --oneline -1` 为准（本文件无法记录包含自身的哈希） |
| 基线是否为 HEAD 祖先 | **是**（`git merge-base --is-ancestor` 退出码 0） |
| 源仓库 `D:\rikkahub-agent1` | 未被修改，仍为 `c00f6f3d`，未跟踪文件原样保留 |
| 分支已推送 | **否** |
| Tag / Release | 未创建 |

### 提交清单

| # | Commit | 说明 |
|---|---|---|
| 1 | `d43658b3` | `docs(claudep): freeze phase 0 design` — 9 份 Phase 0 文档从源仓库未跟踪目录复制而来 |
| 2 | `9be999b5` | `feat(claudep): add local provider protocol skeleton` |
| 3 | `cfcc649f` | `test(claudep): cover fake gateway and provider boundaries` |
| 4 | `a0159631` | `ci: run Claude P unit tests in the debug APK workflow` |
| 5 | `1a4ebcb0` | `docs(claudep): record CP1-A local evidence` |
| 6 | `73c215bc` | `fix(claudep): correct two compile errors in the Claude P tests`（复审判定） |
| 7 | `6ec9a706` | `docs(claudep): record CP1-A review findings` |
| 8 | `76b38483` | `fix(claudep): fix compile errors and a fail-open protocol default`（复审判定） |
| 9 | *(本报告再修订)* | `docs(claudep): record second review round` |

Phase 0 文档为**逐字节复制**；源目录 `D:\rikkahub-agent1\claudep` 未被删除、移动或修改（复制后源目录仍为 9 个文件）。

---

## 2. 修改文件清单

相对基线的 diffstat：**43 files changed, 6652 insertions(+), 16 deletions(-)**（含 9 份 Phase 0 文档、本报告与验证脚本）。

### 2.1 新增 — 协议与传输（`ai` 模块）

| 文件 | 行数 | 作用 |
|---|---:|---|
| `ai/.../provider/claudep/ClaudePProtocol.kt` | 417 | 协议常量、envelope、严格解析器、错误/状态安全枚举、终态门 |
| `ai/.../provider/claudep/ClaudePDto.kt` | 506 | 全部 v1 DTO 与类型化服务端事件 |
| `ai/.../provider/claudep/ClaudePGatewayClient.kt` | 254 | 传输抽象、请求指纹、未配对 fail-closed 实现 |
| `ai/.../provider/claudep/ClaudePSettingTypes.kt` | 56 | 配对状态、目录缓存条目、设备描述符 |
| `ai/.../provider/claudep/FakeClaudePGatewayClient.kt` | 574 | 确定性假 Gateway |
| `ai/.../provider/providers/ClaudePProvider.kt` | 683 | Provider 实现、输入门禁、取消、重连 |

### 2.2 修改 — 接线（`ai` 模块）

| 文件 | 变更 |
|---|---|
| `provider/ProviderSetting.kt` | +93：新增 `@SerialName("claude_p")` 的 `ClaudeP` 子类型；新增 `CLAUDEP_PROVIDER_ID` |
| `provider/ProviderManager.kt` | 提取纯函数 `providerRegistryKeyOf`；新增 `claude_p` 注册与分支 |

### 2.3 修改 — 接线（`app` 模块）

| 文件 | 变更 |
|---|---|
| `data/ai/background/SettingsBackedBackgroundGenerationHost.kt` | 分类器排除、resolver 返回 null、`typeTag`、`providerConfiguration`、`publicProviderPolicyApplicability`、两处 claim 期显式拒绝 |
| `learning/model/LearningModelResolver.kt` | 新增 `CLAUDEP_EXCLUDED` 失败原因 |
| `data/ai/GenerationPolicyContext.kt` | `generationProviderIdentity` 分支（仅非敏感字段） |
| `memory/dreaming/synthesis/ProviderDreamSynthesizer.kt` | `dreamProviderKind` 分支 |
| `data/datastore/PreferencesStore.kt` | 模型去重穷举分支 |
| `data/datastore/DefaultProviders.kt` | 种子默认 Provider（disabled、builtIn、稳定 UUID） |
| `security/SecretOwnerOperationHandler.kt` | secret inventory 类型与 base URL；凭证状态报 `NOT_APPLICABLE` |
| `owner/OwnerSettingsOperationHandler.kt` | `typeName()` 显式返回 `claude_p`（复审修复 #9） |
| `data/sync/importer/ChatboxImporter.kt` | `providerTypeName` 分支 |
| `data/sync/importer/CherryStudioProviderImporter.kt` | 导入去重键 |
| `ui/pages/setting/doctor/DoctorChecks.kt` | "已配置"判定改为要求已配对 |
| `ui/pages/setting/SettingProviderDetailPage.kt` | 隐藏分享、专用配置页、未配对时跳过目录请求 |
| `ui/pages/setting/components/ProviderConfigure.kt` | 6 处穷举分支 |
| `ui/pages/setting/components/ProviderRequirements.kt` | 显示"需要设备配对" |
| `ui/pages/setting/components/ClaudePProviderConfigure.kt` | **新增**：最小诚实设置页 |

### 2.4 新增 — 测试（8 个类，101 个 `@Test`）

| 文件 | `@Test` |
|---|---:|
| `ai/.../claudep/ClaudePProtocolTest.kt` | 22 |
| `ai/.../claudep/ClaudePFakeGatewayTest.kt` | 19 |
| `ai/.../providers/ClaudePProviderStreamTest.kt` | 17 |
| `ai/.../providers/ClaudePProviderCancellationTest.kt` | 13 |
| `ai/.../provider/ClaudePSettingTest.kt` | 11 |
| `ai/.../provider/ProviderManagerClaudePTest.kt` | 8 |
| `app/.../background/ClaudePBackgroundExclusionTest.kt` | 6 |
| `app/.../setting/components/ClaudePProviderConfigureTest.kt` | 5 |
| **合计** | **101** |

`ClaudePBackgroundExclusionTest` 原为 8 条，其中 3 条经复审判定为"永不失败"的空测试被删除，替换为 1 条带阳性对照的 host 级测试，净减 2 条（见 §8 #8）。

---

## 3. 架构选择与理由

### 3.1 协议解析集中在一处

`ClaudePGenerationHandle.frames()` 返回**原始 JSON 帧**而非解析后的事件。传输层永远不产生"已验证"的对象；解析只在 `ClaudePProtocol.parseInbound` 发生。这样"未知事件不得成为正文或终态"是由**类型系统**保证的——`ClaudePServerEvent` 是 sealed 接口，编译期就无法把未知事件映射成文本。

### 3.2 协议主版本先于事件类型校验

`parseInbound` 的顺序是：解码 → 协议字段 → **主版本** → 事件类型。
先查类型（更自然的写法）会静默接受一个"类型恰好同名但语义已变"的 v2 帧。已有测试 `protocol major is checked before the event type is trusted` 固定这一顺序。

### 3.3 终态由本地门禁裁定，而非信任远端

`ClaudePTerminalGate` 是终态的唯一本地权威。重连重放、cancel 与 completed 竞争都可能送入第二个终态；`tryAccept` 只放行第一个，并且 `acceptsDeltas` 同时关闭正文与 usage 增量。因此"终态唯一"是**本地不变量**，不是对 Gateway 的信任。

### 3.4 取消的"恰好一次"是客户端的责任

`ClaudePGenerationAttempt` 用 `AtomicBoolean` 保证每个 Generation 至多一次 cancel RPC。此外：**已观察到终态则不发送 cancel**——cancel 与 completed 竞争时，completed 已经赢了，再发 cancel 只会招来第二个终态。

一处易错细节已修正：终态标志在**终态 chunk 被 emit 之前**写入。否则下游因看到终态而停止收集（`take(n)`、上游超时）会被误判为用户取消。

### 3.5 生产环境绑定的是"未配对"而非 fake

`ProviderManager` 注册 `claude_p` 时绑定 `UnpairedClaudePGatewayClient`（每个调用返回 `NOT_PAIRED`），**不是** fake。fake 只存在于源码树中供测试使用，不会被任何 DI 路径装配。这保证"本地骨架阶段"在运行时是诚实的：Provider 存在、可见、状态明确，但发不出任何请求。

### 3.6 Fail-closed 用两层，而不是一层

后台路径的排除既在**分类器**（`officialBackgroundRemoteKindOrNull()` 返回 null，使其永不成为授权候选），也在**claim 期**显式拒绝（`CLAUDEP_EXCLUDED`）。仅靠"默认 disabled"是弱保证——测试专门在**已启用且已配对**的前提下验证排除仍然成立。

### 3.7 `Type dispatch` 提取为纯函数

`ProviderManager` 需要 Android `Context`，JVM 单元测试无法构造。因此类型→注册键的映射被提取为纯函数 `providerRegistryKeyOf`，并加了一个 `internal` 的注册表注入构造器。这样"Claude P 是否会落进 OpenAI/Claude 分支"是被**测试**覆盖的，而不是靠人工复审。

---

## 4. 验证结果

### 4.1 本机实际执行并通过的检查

| 检查 | 命令 | 结果 | Exit |
|---|---|---|---|
| 空白/冲突标记 | `git diff --check` | 干净（修复了 1 处 EOF 空行） | 0 |
| 基线祖先链 | `git merge-base --is-ancestor c00f6f3d HEAD` | 通过 | 0 |
| 工作区洁净 | `git status --short` | 空 | 0 |
| Workflow YAML 语法 | `python -c "yaml.safe_load(...)"` | 通过，15 个 step，新增 2 个 | 0 |
| 无网络/进程 API | 对新增源码 grep `java.net`/`okhttp3`/`Socket`/`ProcessBuilder`/`Runtime` | 仅注释命中，**0 处真实调用** | 0 |
| 无调试残留 | 对改动文件 grep `TODO`/`FIXME`/`println` | 0 处（唯一的 `printStackTrace` 属既有代码） | 0 |
| 源码仓库未被触碰 | `git -C D:\rikkahub-agent1 status --short` | 与开始时一致 | 0 |

### 4.2 因缺少 Android SDK 而**未执行**的检查

本机**没有** Android SDK，且用户明确要求本轮不安装。实测证据：

```
> Configure project :ai
FAILURE: Build failed with an exception.
* What went wrong:
A problem occurred configuring project ':workspace'.
> SDK location not found. Define a valid SDK location with an ANDROID_HOME environment
  variable or by setting the sdk.dir path in your project's local properties file at
  'D:\rikkahub-agent1.worktrees\claudep-cp1a\local.properties'.
BUILD FAILED in 49s
EXIT=1
```

Gradle 连**配置阶段**都无法通过，因此下列项目**一律未执行，不得记为通过**：

| 未执行项 | 应有命令 |
|---|---|
| `:ai` 编译 | `./gradlew :ai:compileDebugKotlin` |
| `:app` 编译 | `./gradlew :app:compileDebugKotlin` |
| `:ai` 单元测试（全部 90 个 ClaudeP 测试在此） | `./gradlew :ai:testDebugUnitTest` |
| `:app` 单元测试 | `./gradlew :app:testDebugUnitTest` |
| lint / format | `./gradlew lint` |
| Gradle task 枚举 | `./gradlew :ai:tasks --all` |

**因此：本报告不包含任何编译或测试通过结论。101 个测试已编写但从未运行过。**

### 4.3 替代验证：人工编译复审

由于无法编译，实现完成后进行了三轮独立复审（全仓穷举分支完整性 + 主源码编译 + 测试编译）。**后两轮均使用了本机真实 Kotlin 2.3.0 编译器与项目真实依赖进行实际编译验证**，据此共发现并修复 **5 个真实编译错误 + 1 个 fail-open 缺陷**（详见 §7、§8）。

需强调：这只提升了「编译正确性」这一项的置信度，**不构成任何测试执行证据**。§4.2 的未执行清单不受影响。

---

## 5. 后续 GitHub CI 必须执行的检查

### 5.1 应当由哪个 workflow 验证

**`Build Debug APK`**（`.github/workflows/build-debug-apk.yml`）——它是唯一构建 `:app` 并跑 JVM 单元测试的 workflow。

**但原状态下它无法验证 CP1-A**，存在两个真实缺口：

1. **没有任何 workflow 运行 `:ai` 的单元测试。** 全部 8 个 ClaudeP 测试类中有 6 个在 `:ai`，即 90/101 个测试永远不会执行（`:ai` 的 90 条完全不会运行，`:app` 的 11 条会编译但不会执行）。
2. **`:app:testDebugUnitTest` 使用 `--tests` 白名单**（约 40 个固定模式）。新增的 `:app` 测试会被**编译**但永不**运行**。

### 5.2 本轮对 CI 的修改（唯一一处，最小化）

用户授权为"确实发现 CP1-A 必需的验证缺口"时可改 CI。上述属于该情形。改动仅**新增 2 个 step**，未修改任何既有 step、未改动触发条件、未改动 `--tests` 白名单：

- `Run Claude P provider unit tests (CP1-A)`：运行 6 个 `:ai` 类 + 2 个 `:app` 类，**按类名钉死**，因此 `:ai` 中其他既有失败不会波及本 job。
- `Report executed Claude P test classes`：打印实际执行的 JUnit XML。原因见下。

**该修改易于回退**：删除这 2 个 step 即可恢复原状；若复审认为不应改 CI，请直接否决该 commit。

### 5.3 必须执行的命令

```bash
# 编译（assembleDebug 也会覆盖，但这两条定位更准）
./gradlew :ai:compileDebugKotlin
./gradlew :app:compileDebugKotlin

# Claude P 聚焦测试
./gradlew :ai:testDebugUnitTest \
  --tests "me.rerere.ai.provider.claudep.*" \
  --tests "me.rerere.ai.provider.providers.ClaudePProviderStreamTest" \
  --tests "me.rerere.ai.provider.providers.ClaudePProviderCancellationTest" \
  --tests "me.rerere.ai.provider.ClaudePSettingTest" \
  --tests "me.rerere.ai.provider.ProviderManagerClaudePTest"

./gradlew :app:testDebugUnitTest \
  --tests "me.rerere.rikkahub.data.ai.background.ClaudePBackgroundExclusionTest" \
  --tests "me.rerere.rikkahub.ui.pages.setting.components.ClaudePProviderConfigureTest"

# 回归：受影响范围的全量套件
./gradlew :ai:testDebugUnitTest
./gradlew :app:testDebugUnitTest
```

可复现脚本：`claudep/reports/verify-cp1a.sh`（`--full` 追加两个全量套件，无 SDK 时以 exit 2 明确失败而非静默通过）。

### 5.4 为什么必须打印 JUnit XML

Gradle 的 `--tests` 过滤器**只在组合过滤整体匹配为空时**才报错；单个模式匹配不到任何类时是静默的。本仓库项目级关闭了测试日志，运行日志从不列出测试类。因此"编译成功"不等于"测试运行"——列出 `test-results/**/*.xml` 是唯一能证明某个类真的跑过的证据。

### 5.5 预期与已知不确定项

- **预期**：101 个测试全绿；编译无错误。
- **不确定**：`ClaudePProviderCancellationTest` 中两处依赖 `Flow.take(n)` 触发上游取消（`AbortFlowException` 语义）的测试。该机制是 kotlinx.coroutines 的标准行为，但**未能在本机验证**。若 CI 失败，这两条最可能是首因。
- **不确定**：CI 上 `:ai:testDebugUnitTest` 的任务名。AGP 对 Android library 模块的标准任务名，本机无法通过 `gradlew tasks` 确认。
- **重要缺口**：两次「真实编译器验证」跑的是**修复之前**的代码。复审发现的问题修完之后，**修复后的最终状态没有被重新编译过**。修复本身都是机械性的（改名传参、删除未使用的类型参数、移除默认值——三处构造点均已逐一核对），风险低，但"编译验证过的版本"与"最终 HEAD"严格来说不是同一个版本。**这正是必须由 CI 复核的第一个理由。**

---

## 6. 未执行项与零调用声明

### 6.1 本轮明确未执行（符合范围约束）

| 项目 | 状态 |
|---|---|
| 连接 CC VPS | 未执行 |
| 运行/安装/登录 Claude Code | 未执行 |
| 读取 Claude OAuth | 未执行 |
| 调用任何模型 | **0 次** |
| 实现真实 WSS | 未执行（CP1-B） |
| 真实 Android Keystore 配对 | 未执行（CP1-B） |
| new/resume/fork/rebuild | 未执行（CP2）；本轮硬编码 `mode = "new"` |
| MCP 工具桥 | 未执行（CP3） |
| 附件上传 | 未执行（CP4） |
| 开启后台任务 | 未执行（且已显式排除） |
| 修改 production / DNS / 网络 / 证书 | 未执行 |
| push / tag / Release | 未执行 |
| 修改源工作区 | 未执行 |
| 删除/整理用户未跟踪文件 | 未执行 |
| 升级依赖/Gradle/Kotlin/Compose/SDK | 未执行（无任何版本变更） |

### 6.2 零调用证据

- **模型调用：0。** 本机无 SDK 无法运行测试；源码中无任何模型客户端。唯一被装配的传输是 `UnpairedClaudePGatewayClient`，它对每个调用抛 `NOT_PAIRED`。
- **网络调用：0。** 对全部新增源码 grep `java.net` / `okhttp3` / `okio` / `Socket` / `URLConnection` / `InetAddress` / `ProcessBuilder` / `Runtime.getRuntime`，**0 处真实引用**（仅 3 处出现在 KDoc 注释中）。
- **Secret 读取：0。** 无 Keystore、无文件读取、无环境变量读取。`ClaudePProvider` 的 `deviceId` 默认值为字面量 `"unpaired-device"`。
- **持久化的 Secret：0。** `ProviderSetting.ClaudeP` 的序列化键集合被测试固定为封闭集合（见 `the serialized form contains only non-secret fields`）。

---

## 7. 复审记录

实现完成后执行了三轮独立复审（因无法编译，这是唯一的正确性检查手段）。第一次派出的编译复审**因 API 错误中途失败、未产出任何结论**，已重新派出并拆分范围，故实际有效复审如下：

1. **全仓接线与穷举分支复审**——扫描所有模块的 `when (ProviderSetting...)`，核对新增分支完整性与 `else` 分支的语义正确性。
2. **`ai` 主源码编译复审**——逐符号核对导入、签名、可见性、sealed 穷举。
3. **`ai` 测试编译复审**——逐符号核对测试中调用/构造的每个符号。
4. **`ai` 主源码编译复审**——对 6 个主源码文件做实际编译（provider 文件配合其真实依赖的精确副本）。

第 3、4 轮均**使用本机真实的 Kotlin 2.3.0 编译器与项目真实依赖（kotlinx-serialization 1.9.0、JUnit 4.13.2）做了实际编译**，而非仅人工阅读，因此给出了带编译器诊断的确定性结论，并**合计发现 5 个真实编译错误**（见 §8 #11–#15）。这两轮的证据强度高于纯人工复审——但仍低于本仓库自己的 Gradle 构建，因为它们不是在真实模块依赖图与 AGP 配置下编译的。

复审结论：

- **穷举性：干净。** 全仓所有以 `ProviderSetting` 为 subject 的 `when` 要么已有 ClaudeP 分支，要么有语义正确的 `else`。`DoctorChecks.kt` 使用全限定名写法（`me.rerere.ai.provider.ProviderSetting.ClaudeP`），已单独确认覆盖。对 `LearningModelResolutionFailure` 枚举不存在穷举 `when`，因此新增 `CLAUDEP_EXCLUDED` 是安全的。
- **改动文件自洽性：干净。** 新增符号均已导入或全限定；`ClaudeP` 上被引用的每个属性都确实存在。

复审发现的问题已全部修复，见 §8。

---

## 8. 中间失败与修复

实施过程中发现并修复的问题（均已包含在最终 HEAD 中）：

| # | 问题 | 处理 |
|---|---|---|
| 1 | 请求指纹把 `null` 与 `""` 折叠成相同摘要，"无 system prompt"与"空 system prompt"会碰撞 | 改为显式编码字段存在性（`0`/`1` 前缀），并加测试固化 |
| 2 | 终态标志在终态 chunk emit **之后**写入，导致下游因终态而停止收集时被误判为用户取消 | 移到 emit **之前** |
| 3 | fake 的 cancel 在收集前发生时丢弃了 `generation.accepted`，不真实 | 改为先 emit 当前帧再响应取消 |
| 4 | `ClaudePProtocol.kt` 文件末尾多余空行，`git diff --check` 失败 | 已清除 |
| 5 | 测试中残留一个无意义的占位断言 | 替换为真实的内容泄漏断言 |
| 6 | 测试引用了不存在的假类名 | 已修正为 `FakeClaudePGatewayClient` |

第二轮（人工复审）发现并修复：

| # | 问题 | 处理 |
|---|---|---|
| 7 | **`ClaudePSettingTest` 的 `Json` 用了 `explicitNulls = false`，与生产 `JsonInstant` 不一致**，导致两条"序列化键集合封闭"断言实际断言的是生产**永不使用**的配置，且会因 null 字段被省略而失败 | 改为与 `JsonInstant` 完全一致（`ignoreUnknownKeys` + `encodeDefaults`，`explicitNulls` 保持默认 true），并加注释说明为何不能用更严格的配置 |
| 8 | **`ClaudePBackgroundExclusionTest` 中 3 条测试是"永不失败"的空测试**：`listLocalAuthorizationCandidates()` 对任何非 LiteRT 恒为空；`listAuthorizationCandidates()` 从不调用 `providerResolver`，故"resolver 未被调用"恒真；分类器排除与通用 `backgroundAdapterReady` 过滤在该断言层级不可区分 | 删除这 3 条。替换为**阳性对照**：同一个 host 快照中官方 OpenAI Provider **必须**入选，而 ClaudeP **必须**不入选——这才排除"host 只是返回空列表"。并在类 KDoc 中**明确写出该断言的证明边界**，避免后人高估它 |
| 9 | `OwnerSettingsOperationHandler.typeName()` 的 `else` 让 ClaudeP 报成 `"claudep"`，与其余所有面（`claude_p`）不一致，且 `providerCreate` 无法回环 | 新增显式分支返回 `"claude_p"` |
| 10 | `SecretOwnerOperationHandler` 的凭证清单对 ClaudeP 报 `UNBOUND`，会诱导 owner/agent 去绑定一个永远不适用的 secret | 新增显式分支报 `NOT_APPLICABLE`，并说明 ClaudeP 使用设备身份而非 API key |

第三轮（编译复审，经真实编译器验证）发现并修复：

| # | 问题 | 处理 |
|---|---|---|
| 11 | `ClaudePFakeGatewayTest` 以全限定名调用 `kotlinx.serialization.json.put(...)`。`put` 是 `JsonObjectBuilder` 的**扩展函数**，全限定调用形式即使有隐式接收者也不解析 → `unresolved reference 'put'` | 增加 `import`，改为非全限定调用（同行的 `buildJsonObject` 是顶层函数，全限定调用合法） |
| 12 | `ClaudePProviderStreamTest` 中 `assertNotNull(instance.negotiatedServerHello).claudeCodeVersion` —— JUnit 4 的 `assertNotNull` 返回 **void**，无法链式取成员 → `unresolved reference` | 改为 `requireNotNull(...)` 后单独断言 |

同轮复审确认为**非问题**（均已用真实编译器/javap 验证）：`assertThrows` 的非 Unit lambda 体（SAM + Unit 强制转换）、`Json.decodeFromString` reified 重载无需 import、`ClaudeP` 序列化出的 14 个键与 `JsonInstant` 实际写入完全一致、禁止子串扫描无误伤、测试源集可见 `internal` 符号。

第四轮（主源码编译复审，经真实编译器验证）发现并修复：

| # | 问题 | 处理 |
|---|---|---|
| 13 | `FakeClaudePGatewayClient` 两处以 `acceptedSeq =` 传参，而 `FakeGenerationHandle` 的形参名是 `acceptedEventSeq` → `no parameter with name 'acceptedSeq' found` | 改为 `acceptedEventSeq =` |
| 14 | `ClaudePProvider.pumpFrames` 声明了未被任何参数使用、也无法推断的类型参数 `<T>` → K2 `cannot infer type for type parameter 'T'`（两处调用点都失败） | 删除 `<T>` |
| 15 | **fail-open 缺陷（比编译错误更严重）**：`ClaudePEnvelope.protocol` 有默认值 `PROTOCOL_ID`，于是**完全省略** `protocol` 字段的帧会解码成 v1 并被接受——正是解析器"绝不假定 v1"规则要防的情况。原有全部拒绝测试都只发送"字段存在但值错误"，因此这条路径一直未被覆盖 | 移除默认值：缺字段即解码失败 → `MALFORMED_FRAME`。新增回归测试 `a frame with no protocol field at all fails closed instead of assuming v1`，它发送的是**没有 protocol 键**的帧 |

#15 是本轮最有价值的发现，也说明了一个方法论问题：**"拒绝路径有测试"不等于"缺字段路径有测试"**。若第一轮复审没有失败、或第四轮没有被派出，这个 fail-open 会直接进入 CI 之后的下一个阶段。

其中 #8 是**测试有效性**问题而非测试失败问题——空测试会通过，但什么也保护不了；这正是"绿色 CI 不等于有效验证"的典型情形，故按缺陷处理。

**无任何中间失败被记录为通过。**

### 复审机制自身的一次失败

第一次派出的 `ai` 模块编译复审因 API 连接中断而**未产出任何结论**。该失败已如实记录，未计为"已复审"；重新派出并拆分范围后才得到上述 #11、#12。这一点值得列出，因为它说明：本轮的所有编译正确性结论都来自复审，而复审本身也会失败——这正是必须由 CI 复核的原因。

---

## 9. 当前限制（必须随本阶段一起理解）

1. **没有任何东西被编译或运行过。** §4.2 列出的全部项目仍未执行。本阶段的证据等级仅为**静态代码复审**（`claudep/07-sources-and-revalidation.md` 第 4 节的第 1 级）。第 2 级（单元/fake 协议测试）**尚未达成**。
2. **无真实传输。** 无 WSS、无 TLS、无端点校验、无重定向策略——这些属于 CP1-B，本轮一行未写。
3. **无设备配对。** `ClaudePPairingState.PAIRED` 从不可达；设置页开关因 `enabled = paired` 而永远不可交互，属**有意为之**（宁可不可开启，也不要一个开启后必然失败的 Provider）。CP1-B 落地配对后需同步放开。
4. **会话模式仅有 `new`。** `mode` 硬编码，resume/fork/rebuild 属 CP2。
5. **`remoteThreadId`/`remoteBranchId` 为构造期默认值**（`"local-thread"`/`"local-branch"`），尚未接到 Room 会话绑定。这是 CP2 的入口。
6. **`streamText` 在调用时（而非收集时）执行握手。** 一个创建后丢弃的 Flow 仍会握手一次。对 fake 无害，接入真实 WSS 后应重新评估。
7. **`generateText` 的 `id` 取自新建的 `UIMessage` UUID**，而非 Gateway 的 `generation_id`。这与仓库其他 Provider 的惯例一致，但值得在 CP2 绑定会话时复核。
8. **二维码导入路径未加固。** 分享入口已对 ClaudeP 隐藏，但外部 QR 仍可解码出一个 `enabled=true` 的 ClaudeP。本轮无传输因此无实际风险；CP1-B 应在导入时强制重置为未配对。
9. **设置页未本地化。** 文案为字面量（与 `DefaultProviders.kt` 既有做法一致），未新增 string resource。
10. **`SecondUserSecretAdapters.kt` 与 `OwnerSettingsOperationHandler.kt` 保留 `else` 分支（未新增 ClaudeP 分支）。** 已逐一核对 `else` 语义对 ClaudeP 是正确的：`legacyApiKeyOrNull()` → `null`（无 API key，不参与 vault bridge）、`clearLegacyApiKey()` → 原样返回、`withVaultApiKey()` → `null`（不会注入密钥）、`withSafeFields()` → `copyProvider(name, enabled)`，**拒绝设置 baseUrl**（正是 `01` §4 所要求的"端点不得手工编辑"）。两处均非安全门禁，`else` 已是 fail-closed，故未为形式统一而增分支。
11. **`typeName()` 对 ClaudeP 返回 `"claudep"`**（`simpleName.lowercase()`），而协议与其他标识使用 `"claude_p"`。该值仅用于诊断显示，不持久化、不参与判定，故本轮未改。
12. **宠物对话生成（`PetDialogueGenerator`）未显式排除 ClaudeP。** 该路径不在 `04` §7 的 fail-closed 清单内，且 `isPetGenerationProviderUsable` 已检查 `enabled`；ClaudeP 默认 disabled，即便开启其传输也返回 `NOT_PAIRED`，因此是"优雅失败"而非静默放行。建议 CP1-B 评估是否纳入排除清单。

---

## 10. CP1-B 建议输入（本轮不启动）

| 项 | 建议 |
|---|---|
| 传输 | 新建 `WssClaudePGatewayClient` 实现 `ClaudePGatewayClient`；绑定进 `ProviderManager` 时**替换** `UnpairedClaudePGatewayClient`，不改接口 |
| 协议 | 接口已按帧流设计，WSS 只需把文本帧映射进 `frames()`；解析仍留在 `ClaudePProtocol` |
| 配对 | `ClaudePDeviceCredentialStore` 接口本轮已定义但仅有 in-memory 测试实现；生产实现走 Android Keystore，**不得**写入普通 Settings |
| 端点校验 | 单独 OkHttp client；关闭重定向；仅允许配对 origin；生产禁明文 |
| 设置页 | 放开 `enabled` 开关（`paired` 时）；填充 `pairedOrigin`/`gatewayFingerprint`/`claudeCodeVersion`；补撤销与重新配对 |
| 导入加固 | QR 导入 ClaudeP 时强制重置 `pairingState = NOT_PAIRED` |
| 先决条件 | 必须先让 §5.3 的命令在 CI 上全绿，把证据等级从第 1 级提到第 2 级 |

---

## 11. 复审点状态

- 分支 `codex/claudep-cp1a-local` 停在本地，**未推送**。
- 工作区洁净，`git diff --check` 通过。
- 未创建 tag / Release / PR，未触发任何 CI。
- 无 Gradle daemon / Java 进程残留。§4.2 那次失败的尝试确实拉起过一个 Gradle daemon（PID 28760），已通过 `./gradlew --stop` 干净停止并核实（`Get-Process java` → 无结果）。
- 说明：本机存在一个 `adb` 进程（PID 7432，启动于 2026-09-17 01:30），**早于本次会话且非本轮启动**。本轮从未执行过 `adb`、未连接任何设备，故未终止该进程——它是用户其他工作的进程，不属于本轮的清理范围。
- **等待复审。** 未进入 CP1-B / CP2 / CP3 / CP4。
