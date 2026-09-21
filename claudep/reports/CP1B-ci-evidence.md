# CP1-B｜Android CI 证据

状态：**CI 全绿**（run 5）
日期：2026-09-21
分支：`codex/claudep-cp1b-local`（未合并、未创建 PR）

---

## 1. 五次 CI run 的逐级结果

全部为 `workflow_dispatch`、`attempt = 1`、**无一 rerun**。workflow：`build-debug-apk.yml`。

| # | Run | SHA | 结论 | 首个可信失败 | 类别 |
|---|---|---|---|---|---|
| 1 | [35563308861](https://github.com/maocomet/rikkahub-agent1/actions/runs/35563308861) | `95c42dea` | **failure** | step 9 `:app:compileDebugKotlin`，4 个错误 | 生产编译 |
| 2 | [35565646744](https://github.com/maocomet/rikkahub-agent1/actions/runs/35565646744) | `7ba4373c` | **failure** | step 11 `:app:compileDebugUnitTestKotlin`，2 个错误 | 测试编译 |
| 3 | [35575283820](https://github.com/maocomet/rikkahub-agent1/actions/runs/35575283820) | `88a55da7` | **failure** | step 13 `:ai:testDebugUnitTest` — `301 tests completed, 1 failed` | 测试断言 |
| 4 | [35577279073](https://github.com/maocomet/rikkahub-agent1/actions/runs/35577279073) | `02dc1dd3` | **failure** | step 13 `:app:testDebugUnitTest` — `32 tests completed, 1 failed` | 测试断言 |
| 5 | [35578830186](https://github.com/maocomet/rikkahub-agent1/actions/runs/35578830186) | `62a5ff01` | **success** | — | — |

逐步收敛（✅ 通过、❌ 失败、– 跳过）：

| Step | run1 | run2 | run3 | run4 | run5 |
|---|---|---|---|---|---|
| 9 `Build debug APK` | ❌ | ✅ | ✅ | ✅ | ✅ |
| 10 固定签名校验 | – | ✅ | ✅ | ✅ | ✅ |
| 11 regression unit tests | – | ❌ | ✅ | ✅ | ✅ |
| 12 regression XML 报告 | ❌ | ❌ | ✅ | ✅ | ✅ |
| 13 Claude P unit tests | – | – | ❌ | ❌ | ✅ |
| 14 Claude P XML 门禁 | ❌ | ❌ | ❌ | ✅ | ✅ |

**早期失败全部保留，从未 rerun。** step 12/14 出现在 step 9/11/13 失败之后时，是
`if: always()` 报告步的**级联**，不是根因 —— 它们断言 JUnit XML 存在，而构建未通过时本就不会有 XML。
run5 中唯一 skipped 的是 step 15 `Diagnose web-ui build (on failure)`，由其自身 `if: failure()` 决定。

### 各轮根因

1. **run1** — `DataSourceModule.kt:1545–1548` 四个错误：`ResolvingClaudePGatewayClient { ... }` 的尾随 lambda
   绑定了 `fallback`（非函数类型的末参）而非首参 `resolve`；外加 `currentDeviceIdOrNull()` 返回
   `String?` 而契约要求非空。
2. **run2** — app 测试源集两个错误：`allowsDispatch` 扩展属性缺 import；
   `InMemoryClaudePPairingSettingsGateway.metadata` 为 `private set`。第二个**未**通过放宽生产 API 解决，
   改为测试文件内的 `MetadataOverrideGateway`（只 override `pairedMetadata()`，其余全部委托）。
3. **run3** — `ClaudePImportSanitizerTest` 期望 `PAIRED`，实际 `NOT_PAIRED`：该断言写在
   `ClaudePPairingResolver` 改为 settings 权威**之前**，已被同轮修复作废。
4. **run4** — `ClaudePDevicePairingRepositoryTest` 期望 `OFFLINE`，实际 `CREDENTIAL_INVALID`：
   部分清理失败后 settings 为 `REVOKED`，mapper 对 `REVOKED` 优先。
5. **run5** — 全绿。

run2–run4 的三次失败**全部是测试期望值过时，而非生产缺陷**，且全部位于本机无法编译的测试源集
（`:app`，或需要 Compose 的 `:ai`）。生产实现本身的缺陷只在 run1 出现。

---

## 2. CI 提供的证据（run 5）

| 项 | 结果 |
|---|---|
| `assembleDebug`（step 9） | **success** |
| 固定 `.agenttest` 签名校验（step 10） | **success** |
| regression unit tests + XML 报告（11/12） | **success** |
| Claude P `:ai` + `:app` 测试命令（step 13） | **success**（exit 0） |
| Claude P XML 门禁（step 14） | **success** — `All 23 required Claude P test classes executed.` |
| APK 上传（step 16） | **success** |

**这是 `:app` 第一次获得真实 Android 编译证据。** `DataSourceModule.kt`、`ClaudePDevicePairingRepository.kt`、
`ClaudePProvider.kt` 与新增的 app 测试，均由真实 Android/Kotlin 编译器编译并通过。

### 23/23 必需测试类逐项执行

`ClaudePCleanupTombstoneCodecTest`、`ClaudePConfigureUiTest`、`ClaudePCredentialStoreTest`、
`ClaudePEndpointTest`、`ClaudePFakeGatewayTest`、`ClaudePImportSanitizerTest`、`ClaudePOkHttpTest`、
`ClaudePPairingCoordinatorTest`、`ClaudePPairingFlowTest`、`ClaudePProtocolTest`、
`ClaudePRequestFingerprintTest`、`ClaudePRevocationTest`、`ClaudePResolvingClientTest`、
`ClaudePUiStatusTest`、`ClaudePWssTransportTest`、`ClaudePProviderCancellationTest`、
`ClaudePProviderStreamTest`、`ClaudePProviderIdentityTest`、`ClaudePSettingTest`、
`ProviderManagerClaudePTest`、`ClaudePBackgroundExclusionTest`、`ClaudePDevicePairingRepositoryTest`、
`ClaudePProviderConfigureTest` —— 全部产出匹配的 JUnit XML。

本轮首次真实执行并通过的四个类：
`ClaudePImportSanitizerTest`(5)、`ClaudePProviderIdentityTest`(8)、`ClaudePResolvingClientTest`(4)、
`ClaudePDevicePairingRepositoryTest`(21)。

### 测试数量 —— 及其取证限制（重要）

**本 run 的日志中不存在 `N tests completed` 汇总行。** Gradle 只在**测试失败时**打印该行，
run5 全绿，因此没有。该 workflow 只上传 APK，**从不上传 JUnit XML**，所以 XML 层面的
`tests / failures / errors / skipped` **无法取证**。

下表由**两种互相独立的方式**得出并彼此吻合：

- 对 23 个必需类的 `@Test` 静态计数；
- 与前两轮 CI 自己打印的数字对照 —— run3 报告 `:ai` **301**，run4 报告 `:app` **32**。

| 模块 | tests | 取证方式 |
|---|---:|---|
| `:ai` Claude P | **301** | 静态计数 = 301；与 run3 日志的 `301 tests completed` 吻合 |
| `:app` Claude P | **32** | 静态计数 = 32；与 run4 日志的 `32 tests completed` 吻合 |
| **合计** | **333** | 推论，非本 run 直读 |

**failures / errors / skipped = 0**，依据是 step 13 的 Gradle 任务 exit 0 —— Gradle 在任何测试失败时必然失败 ——
以及 run5 无 skip 报告。**这不是从 XML 属性直读的数字。**

### APK artifact

| 项 | 值 |
|---|---|
| Artifact 名称 | `rikkahub-agent-debug-apk` |
| Artifact ID | `10628954063` |
| Artifact 大小（API 报告，未压缩） | **353,553,275 bytes** |
| 包含 | `app-arm64-v8a-debug.apk`、`app-universal-debug.apk`、`app-x86_64-debug.apk` |
| 固定签名密钥 SHA-256（step 10 逐 APK 打印，三者一致） | `2f1965cf7447301f857ec222fb1996ac179b07c771d9b3a636bd0116fefffcc3` |

**APK 文件级 SHA-256 未取得。** 通过本地代理下载 artifact 时传输在 **20,348,928 / 353,553,275 字节**处中断，
zip 不完整（`End-of-central-directory signature not found`），因此无法计算文件哈希。
**CI 内部的固定签名校验（step 10）已通过**，那是对签名身份的验证；文件级哈希这一项如实记为未执行。
APK 未被提交进仓库，部分下载物位于仓库外 `C:\Users\hp\.claudep-cicd\`。

---

## 3. 仍然没有专门自动化测试的部分（不得因 app 编译成功而视为已覆盖）

- **`FileClaudePCleanupTombstoneStore` 的文件 I/O** —— 原子替换、`noBackupFilesDir` 位置、
  临时文件清理、删除确认。其**判定规则**由 `ClaudePCleanupTombstoneCodec` 的 17 条测试覆盖，
  但 Android 文件层本身没有专门测试：该类的失败路径调用 `android.util.Log`，
  在普通 JVM 单测中会抛 `Method w not mocked`，除非启用
  `testOptions.unitTests.isReturnDefaultValues`（属构建配置决定，未单方面更改）。
- **`SettingsClaudePPairingGateway` 的 DataStore 行为** —— `REVOKED + enabled=false` 的持久化、
  写入失败处理、`NOT_PAIRED` 的写入时机。依赖 Android runtime 与 DataStore，无专门测试。
- **Android Keystore 的真实语义** —— 密钥不可导出、失效、GCM 解密失败路径。JVM fake 不能替代。

## 4. 边界

- 零模型调用、零真实 Gateway/VPS 连接、零真实 Claude 凭证。
- 依赖零变更；协议、凭证格式、Gateway wire format 未改动；未引入 migration。
- `master` 未触碰；未创建 PR / tag / Release。
- 本报告本身不触发 CI。


---

## 5. R3：Koin 绑定修复的普通 CI 验证

| 项 | 值 |
|---|---|
| Run | [35593444804](https://github.com/maocomet/rikkahub-agent1/actions/runs/35593444804) |
| SHA | `8687dcda1d2b9bf787426fe0f0f99c180365bac7` |
| attempt / event | **1** / `workflow_dispatch` |
| 结论 | **success** |

全部执行步骤 success；唯一 skipped 是 step 15 `Diagnose web-ui build (on failure)`（`if: failure()` 决定）。

| 项 | 结果 |
|---|---|
| 编译错误数 | **0** |
| `assembleDebug` | success |
| 固定签名校验 | success — 三个 APK 均为 `2f1965cf7447301f857ec222fb1996ac179b07c771d9b3a636bd0116fefffcc3` |
| regression unit tests + XML | success |
| Claude P 必跑类 XML 门禁 | **`All 23 required Claude P test classes executed.`** |
| Artifact | `rikkahub-agent-debug-apk`，id `10635343152`，**353,554,103 bytes** |

### 这次 CI 证明了什么、没证明什么

**证明了**：本次 Koin DI 改动（`DataSourceModule` 两处接口注册）与相关 Android 源码
**能够编译**；`assembleDebug` 通过；两批测试与 23 类 XML 门禁继续通过。

**没有证明**：**真实 Koin 图能否解析。** 该 workflow **既不编译也不运行 `androidTest`**，
`ClaudePKoinGraphTest` 未执行。而且这两个被重新绑定的具体类在上一轮 CI 里**已经编译通过**
（`assembleDebug` 成功），所以本次新增的编译证据只覆盖 DI 那两行。

**Koin 图仍须由 managed-device instrumentation 验证**，本轮未触发 `migration-instrumentation.yml`。

### 关于「测试数量」

与 §2 相同：本 run 全绿，日志中**没有** `N tests completed` 汇总行（Gradle 只在失败时打印），
workflow 也不上传 JUnit XML。因此测试数量仍无法从本 run 直读取证。


---

## 6. R3.1 / R3.2：managed-device 结果与 scope 返修

### 6.1 R3 的接口绑定：已由真实设备证明有效

Run 35610800900 在 managed device 上真实执行了 `ClaudePKoinGraphTest`：
`tests=7 failures=3 errors=0 skipped=0`。

**7 条全部真实执行**，其中两条接口解析测试（`tombstoneStoreResolvesThroughItsInterface`、
`pairingSettingsGatewayResolvesThroughItsInterface`）**通过** —— R3 的两个接口绑定**有效，且已被真机验证**。

（run 35601743353 因 D8 拒绝含空格的 DEX 方法名而未能编译，R3.1 重命名后本 run 才真正执行。）

### 6.2 三个失败共享同一真实根因

```
InstanceCreationException: Could not create instance for '[Singleton: ClaudePDevicePairingRepository]'
Caused by: NoDefinitionFoundException:
  No definition found for type 'kotlinx.coroutines.CoroutineScope' on scope '_root_'
  at DataSourceModuleKt.dataSourceModule$lambda$0$232(DataSourceModule.kt:4027)
```

失败的 3 条为 `repositoryDefinitionCompletesConstruction`、`repositoryIsASingle`、
`graphResolvesUpToProviderManager` —— 全部依赖 Repository 的构造。

`AppModule:558` 是 `single { AppScope() }`（具体类）；`SettingsStore` 能工作是因为其参数类型本就是
`AppScope`。而已修复的调用点参数类型是 `CoroutineScope`（接口），无定义。

**这与 R3 修掉的两个缺陷是同一类问题，位于同一段代码 —— 第一次扫描漏掉了它**，
因为 `CoroutineScope` 来自协程库而非 `claudep` 包。

### 6.3 当时聊天页仍会崩溃

`ClaudePDevicePairingRepository` 当时仍无法构造，因此
`InstanceCreationException: Could not create instance for ChatVM` **依然会发生**。
R3 的修复是**必要但不充分**的。本节不声称崩溃已修复。

### 6.4 R3.2 的修复：调用点类型对齐

`scope = get()` → `scope = get<AppScope>()`，**只改 DI 调用点一个地方**。

只读审计依据：

- `AppScope : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Main + …)` —— 委托，故**是** `CoroutineScope`；
- 全仓生产 module **不存在** `get<CoroutineScope>()` 或 `single<CoroutineScope>`；
  `AppModule` 中有 **9 处** `get<AppScope>()` —— 约定明确；
- 亦**不应**新增宽泛的 `single<CoroutineScope>`：`AppScope` 有特定生命周期，且存在其他生命周期的
  scope（`AppModule:408` 的 `parentScope`）。

因此这是**调用点类型对齐**，不是扩大全局 Koin 图；未新增第二个 scope，未改生命周期。

### 6.5 完整构造参数审计

| 参数 | 声明类型 | DI 取值方式 | 已注册 | 可解析 |
|---|---|---|---|---|
| `credentialStore` | `ClaudePDeviceCredentialStore` | 内联构造 | 不涉及 | ✅ |
| `deviceKeyStore` | `ClaudePDeviceKeyStore` | 内联构造 | 不涉及 | ✅ |
| `tombstoneStore` | `ClaudePCleanupTombstoneStore` | `get()` | 接口（R3） | ✅ 真机已证 |
| `settingsGateway` | `ClaudePPairingSettingsGateway` | `get()` | 接口（R3） | ✅ 真机已证 |
| `scope` | `CoroutineScope` | `get()` | `AppScope` 具体类 | ❌ → R3.2 已修 |
| `appVersion` | `String` | `BuildConfig` | 不涉及 | ✅ |

后续路径：`ProviderManager(client = get(), context = get())` —— `OkHttpClient`、`Context` 均已注册；
`ResolvingClaudePGatewayClient` 与 `ClaudePProvider` 内联构造。`ChatService ← ProviderManager`。
**未发现第四个 Claude-P 专属缺失注册。**

### 6.6 状态

- R3.2 提交 HEAD `fed70f3c`，**未 push**；未触发任何 CI。
- 本地 harness 239 全绿；工作区洁净，`git diff --check` 通过。
- **Repository 能否构造仍待下一次 managed-device run 验证**，不得据本机静态检查断言。
