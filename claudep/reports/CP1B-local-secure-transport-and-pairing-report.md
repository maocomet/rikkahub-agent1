# CP1-B｜安全联网传输与一次性配对 — 本地实施报告

状态：**CP1-B 本地实现完成，Android CI 待验证**
日期：2026-09-20
分支：`codex/claudep-cp1b-local`（**未 push**）
Worktree：`D:\rikkahub-agent1.worktrees\claudep-cp1b`
CI：**未触发**（授权冻结，等待人工复审后另行授权）
PR / Tag / Release / master：**均未触碰**

> 本报告不使用「CP1-B 已完成」这一表述。`:app` 从未在本机编译过，Android CI 从未运行。

---

## 1. 基线与提交

| 项目 | 值 |
|---|---|
| CP1-A 已验证代码 SHA | `c655fc938f85c6e32de3cf48fa57d432432a6ea8` |
| CP1-A 文档收口提交（解析后的完整 SHA） | `876a814bdee3687039d8c48832d3701b31d69354` |
| 本地/远端 CP1-A HEAD | `876a814b…` / `876a814b…`（一致） |
| `c655fc93…` 是否为祖先 | **是**（`git merge-base --is-ancestor` 退出码 0） |
| CP1-A 收口提交是否仅文档 | **是**（`git diff --name-only c655fc93..876a814b` 仅 1 个 `.md`） |
| 本阶段基线 | `876a814bdee3687039d8c48832d3701b31d69354` |
| **最终 HEAD** | `10ad17ba2e485030c02ec74603417d47494f0960` |
| 工作区 | **洁净**（`git status --porcelain` 为空） |
| `git diff --check` | **通过** |

### 提交清单（4 个，全部在 CP1-A 之后）

| # | Commit | 说明 |
|---|---|---|
| 1 | `559129d6` | `feat(claudep): add secure wss transport, pairing and device credentials` |
| 2 | `4c68cff8` | `test(claudep): cover transport and pairing boundaries` |
| 3 | `91dc8a24` | `feat(claudep): add pairing settings ui and harden provider import` |
| 4 | `10ad17ba` | `fix(claudep): keep the import sanitiser next to the import path` |

源仓库 `D:\rikkahub-agent1` 全程未被修改，未跟踪文件原样保留。

---

## 2. 变更文件清单

### 2.1 新增 — `ai` 主源码（15 个文件）

| 文件 | 作用 |
|---|---|
| `claudep/ClaudePEndpoint.kt` | 端点校验与规范化；派生唯一的 `wss://` URL |
| `claudep/ClaudePPairingInvitation.kt` | 配对二维码载荷的严格解析与指纹规范化 |
| `claudep/ClaudePTransport.kt` | WebSocket 接缝、事件/失败枚举、界值、重连策略、连接状态机 |
| `claudep/OkHttpClaudePWebSocketConnector.kt` | 真实 OkHttp WebSocket 适配器（唯一接触 socket 的类） |
| `claudep/ClaudePBoundedGenerationStream.kt` | 每 Generation 有界缓冲 + 序号单调性 + 本地安全终态 |
| `claudep/ClaudePInboundRouter.kt` | 帧关联、未知可选/必需事件判定、终态记忆 |
| `claudep/WssClaudePGatewayClient.kt` | 生产 WSS 客户端（协商、签名 hello、幂等、取消、重连重放） |
| `claudep/ClaudePDeviceIdentity.kt` | 设备密钥/凭证接口、握手与配对 transcript、随机数 |
| `claudep/ClaudePPairingTransport.kt` | 配对 HTTP 接缝、线协议 DTO、配对 transcript |
| `claudep/ClaudePPairingClient.kt` | 配对编排（过期、单次消费、占用证明、取消清理） |
| `claudep/ClaudePPairingResolver.kt` | fail-closed 配对状态判定表 |
| `claudep/ClaudePUiStatus.kt` | UI 状态派生（纯函数） |
| `claudep/OkHttpClaudePPairingTransport.kt` | 真实配对 HTTPS 传输（禁重定向、限响应大小） |
| `claudep/ResolvingClaudePGatewayClient.kt` | 按需解析传输；未配对时 fail-closed |
| `claudep/FakeClaudePWebSocket.kt`、`FakeClaudePDeviceIdentity.kt` | 确定性 fake（无 socket、无时钟） |

### 2.2 修改 — `ai`

| 文件 | 变更 |
|---|---|
| `provider/providers/ClaudePProvider.kt` | 新增 `deviceIdProvider`（尾部可选参数，默认沿用 `deviceId`）；指纹改用已配对 device id |
| `provider/claudep/FakeClaudePGatewayClient.kt` | 移除文件内私有的 `wireName()`，改用共享 `internal` 定义（行为不变） |

### 2.3 新增 — `app`（3 个文件）

| 文件 | 作用 |
|---|---|
| `data/claudep/AndroidKeystoreClaudePDeviceKeyStore.kt` | 不可导出 EC P-256 设备签名密钥 |
| `data/claudep/EncryptedClaudePDeviceCredentialStore.kt` | AES/GCM + Keystore 密钥 + `noBackupFilesDir` |
| `data/claudep/ClaudePDevicePairingRepository.kt` | 配对状态唯一 owner；派生 UI 状态；提供传输 |

### 2.4 修改 — `app`

| 文件 | 变更 |
|---|---|
| `di/DataSourceModule.kt` | 注册配对仓库；把 `claude_p` 绑定从 `UnpairedClaudePGatewayClient` 换成 `ResolvingClaudePGatewayClient` |
| `ui/pages/setting/components/ClaudePProviderConfigure.kt` | 状态展示、扫描配对二维码、解除配对；开关随派生状态启用 |
| `ui/pages/setting/SettingProviderDetailPage.kt` | Claude P 分支接入二维码 launcher 与配对仓库 |
| `ui/components/ui/ShareSheet.kt` | 导入加固：`decodeProviderSetting` 解码后强制重置 Claude P |

### 2.5 新增 — 测试（`ai`，5 个文件，122 条 `@Test`）

| 文件 | `@Test` |
|---|---:|
| `ClaudePWssTransportTest.kt` | 35 |
| `ClaudePEndpointTest.kt` | 33 |
| `ClaudePPairingFlowTest.kt` | 22 |
| `ClaudePCredentialStoreTest.kt` | 18 |
| `ClaudePUiStatusTest.kt` | 14 |
| **合计** | **122** |

---

## 3. app 接线数据流

```text
设置页 (SettingProviderDetailPage)
  │  koinInject<ClaudePDevicePairingRepository>()
  │  status / lastFailure  ← collectAsStateWithLifecycle
  │  扫码 → ScanQRCode launcher → repository.pair(payload, deviceName)
  │  解除配对 → repository.unpair()
  ▼
ClaudePDevicePairingRepository        ← 配对状态的唯一 owner
  ├─ SettingsStore            ProviderSetting.ClaudeP（仅非敏感字段）
  ├─ ClaudePDeviceCredentialStore → EncryptedClaudePDeviceCredentialStore
  │                                   └─ AES/GCM, noBackupFilesDir/claude_p_device.enc
  ├─ ClaudePDeviceKeyStore    → AndroidKeystoreClaudePDeviceKeyStore
  └─ gatewayClientOrNull()    → WssClaudePGatewayClient（按需构建）
  ▲
  │  ClaudePUiStatusMapper.map(settings, credentialRead, connectionState, pairingInFlight)
  │
ProviderManager
  └─ registerProvider("claude_p", ClaudePProvider(
         gateway = ResolvingClaudePGatewayClient { repository.gatewayClientOrNull() },
         deviceIdProvider = { repository.currentDeviceIdOrNull() },
     ))
         │
         ▼
     WssClaudePGatewayClient → OkHttpClaudePWebSocketConnector → wss://<paired-origin>/v1/claude-p/stream
```

**未配对/凭证不可用时的行为**：`gatewayClientOrNull()` 返回 `null` → `ResolvingClaudePGatewayClient` 落回 `UnpairedClaudePGatewayClient` → 每个调用抛 `NOT_PAIRED`。Provider 可见但发不出任何请求。

---

## 4. Keystore alias、凭证生命周期与清除路径

| 项 | 值 |
|---|---|
| 设备签名密钥 alias | `rikkahub_claude_p_device_key_v1`（`ClaudePPairingClient.DEFAULT_KEY_ALIAS`） |
| 密钥算法 | EC P-256 (`secp256r1`)，`PURPOSE_SIGN`，`SHA256withECDSA`，`setUserAuthenticationRequired(false)` |
| 凭证加密密钥 alias | `rikkahub_claude_p_credential_v1`（AES-256-GCM，`PURPOSE_ENCRYPT or DECRYPT`） |
| 凭证文件 | `<noBackupFilesDir>/claude_p_device.enc`（12 字节 IV 前缀） |

### 生命周期

```text
未配对
  └─ pair() 成功 → Keystore 生成设备密钥
                  + 凭证写入 claude_p_device.enc
                  + Settings: pairingState=PAIRED, pairedOrigin/fingerprint/installationId/device
配对中 (PAIRING)
  └─ 失败或被取消 → finally(NonCancellable) 删除设备密钥；凭证从未写入
已配对 (PAIRED / ONLINE / OFFLINE)
  └─ 凭证过期 / 解密失败 / 密钥失效 → Unusable → 解析为「未配对(凭证失效)」
解除配对 unpair()
  1. gatewayClient.shutdown()      —— 先断开 socket
  2. 置空 gatewayClient
  3. credentialStore.clear()       —— 删除 .enc 文件 + 删除设备密钥 alias
  4. Settings: pairingState=NOT_PAIRED
```

**清除路径的完整性**：`clear()` 同时删除密文与 Keystore 私钥。只删其一都会留下可被后续同 alias 配对静默复用的材料。

**备份恢复**：凭证位于 `noBackupFilesDir`，且 `res/xml/backup_rules.xml` / `data_extraction_rules.xml` 已是 deny-by-default（仅备份 `files/upload/`）。双层保证下，恢复后 `credentialStore.read()` 返回 `Absent`，而 Settings 可能仍写着 `PAIRED` —— `ClaudePPairingResolver` 明确把这一组合判定为 **未配对（`CREDENTIAL_MISSING`）**，绝不误认为仍已配对。

---

## 5. 配对 / 解除配对状态机

```text
                 扫码
  NOT_PAIRED ──────────────► PAIRING
      ▲                         │
      │                         ├─ 解析失败/过期/非 TLS 源/协议不符 → NOT_PAIRED + lastFailure
      │                         ├─ 传输失败                       → NOT_PAIRED（密钥已销毁）
      │                         ├─ state 不匹配 / 网关指纹不符     → NOT_PAIRED（密钥已销毁）
      │                         └─ 成功 → CREDENTIAL store 写入 → Settings 写入 → PAIRED
      │
      │  unpair()
      ├──────────────────────── PAIRED / ONLINE / OFFLINE / CONNECTING
      │
      └─ CREDENTIAL_INVALID（凭证缺失/不可用/过期/已撤销）

  READY ── ONLINE
  PROTOCOL_ERROR（稳定，不自动重连）
```

UI 状态与允许 dispatch 的关系：**仅 `PAIRED` 与 `ONLINE` 允许**（`ClaudePUiStatus.allowsDispatch`）。已由 `ClaudePUiStatusTest` 穷举验证。

---

## 6. 本机实际执行的验证

### 6.1 本地 Kotlin 编译/测试台（说明）

| 项 | 值 |
|---|---|
| 位置 | `C:\Users\hp\.claudep-buildcheck\`（**仓库外**，见 §8） |
| Kotlin 编译器 | `2.4.0`（与 `gradle/libs.versions.toml` 的 `kotlin = "2.4.0"` 一致） |
| 序列化插件 | `kotlin-serialization-compiler-plugin-embeddable:2.4.0` |
| 依赖来源 | Maven Central 直连下载（HTTP 200，无代理） |
| 依赖版本 | `kotlinx-serialization-json 1.11.0`、`kotlinx-coroutines-core 1.11.0`、`okhttp-jvm 5.3.2`、`okio-jvm 3.16.4`、`junit 4.13.2`、`hamcrest-core 1.3` |
| 编译范围 | `ai/src/main/java/me/rerere/ai/provider/claudep/*.kt` 与同名测试目录 |
| 执行命令 | `bash /c/Users/hp/.claudep-buildcheck/run.sh`（编译 + `org.junit.runner.JUnitCore`） |
| `-Xfriend-paths` | 对测试源集传入 `out-main`，复现 Gradle 的 friend-path 语义，使 `internal` 符号可见 |

**证据等级**：这是**编译检查 + JVM 单元测试执行**，**不是** Gradle/AGP 构建。不经过 AGP、看不到真实模块依赖图、不编译 `:app`、不运行 Android instrumentation。

### 6.2 最终 HEAD 上重新执行的测试

在最终 HEAD（`10ad17ba`，工作区洁净）上**重新完整执行**，非引用中途版本：

```
bash run.sh
Running: ClaudePCredentialStoreTest ClaudePEndpointTest ClaudePFakeGatewayTest
         ClaudePPairingFlowTest ClaudePProtocolTest ClaudePRequestFingerprintTest
         ClaudePUiStatusTest ClaudePWssTransportTest
JUnit version 4.13.2
OK (163 tests)
```

| 模块/类别 | 类数 | 执行 | 通过 | 失败 | 跳过 |
|---|---:|---:|---:|---:|---:|
| CP1-B 新增（`ai`） | 5 | 122 | 122 | 0 | 0 |
| CP1-A 复跑 —— `ClaudePProtocolTest` + `ClaudePRequestFingerprintTest` | 1 文件 2 类 | 22 | 22 | 0 | 0 |
| CP1-A 复跑 —— `ClaudePFakeGatewayTest` | 1 | 19 | 19 | 0 | 0 |
| **合计** | **8** | **163** | **163** | **0** | **0** |

### 6.3 CP1-A 回归：真正复跑 vs 本测试台无法覆盖

| CP1-A 测试类 | `@Test` | 本机状态 |
|---|---:|---|
| `ClaudePProtocolTest`（含 `ClaudePRequestFingerprintTest`） | 22 | **已真正复跑，通过** |
| `ClaudePFakeGatewayTest` | 19 | **已真正复跑，通过** |
| `ClaudePProviderStreamTest` | 17 | **未运行** —— 依赖 `:ai` 全模块（Compose） |
| `ClaudePProviderCancellationTest` | 13 | **未运行** —— 同上 |
| `ClaudePSettingTest` | 11 | **未运行** —— 依赖 `ProviderSetting.kt`（Compose） |
| `ProviderManagerClaudePTest` | 8 | **未运行** —— 同上 |
| `ClaudePBackgroundExclusionTest`（`:app`） | 6 | **未运行** —— `:app` 未编译 |
| `ClaudePProviderConfigureTest`（`:app`） | 5 | **未运行** —— `:app` 未编译 |
| **合计** | **101** | **真正复跑 41 / 未运行 60** |

CP1-A 的 101 条中，本机只复跑了 41 条。**其余 60 条未被本机任何手段验证过。**

### 6.4 过程中被真实执行捕获的缺陷

| # | 缺陷 | 处理 |
|---|---|---|
| 1 | `AndroidKeystoreClaudePDeviceKeyStore.publicKeyDer()` 恒返回 `null`，会使每次配对都以 `KEY_UNAVAILABLE` 失败 | 改为从 Keystore 条目的自签证书取公钥 |
| 2 | Router 在终态送达后即将 stream 从 map 移除，导致 `cancel()` 对已终态 Generation 拿不到原终态、错报 `Pending`（违反文档 §8） | 新增有界 `recentTerminals` 记忆；由测试捕获 |
| 3 | `WebSocket.request(1)` 在 OkHttp 5 中已被移除（`javap` 核实接口只剩 `request/queueSize/send/close/cancel`） | 改写为「有界缓冲 + 溢出即断开」，并在 KDoc 中如实标注「有界但不节流」 |
| 4 | 测试自带的两条无效断言（恒真/方向写反） | 删除/修正；不保留空测试 |

另有 8 个编译错误由测试台在编写期捕获（类型不匹配、`buildJsonArray` 缺 import、`wireName` 误用于 `ClaudePTerminalKind` 等）。

---

## 7. `:app` 未编译 —— 明确风险清单

`:app` **一行都没有在本机编译过**，也没有被任何本机工具静态分析过。以下是已知的具体风险点，按严重度排序：

| # | 风险 | 说明 |
|---|---|---|
| 1 | **Koin `scope = get()`** | `DataSourceModule` 中配对仓库的 `scope = get()` 期望解析到 `AppScope`。若该 `single` 未导出为 `CoroutineScope` 类型，会解析失败 |
| 2 | **`BuildConfig.VERSION_NAME`** | 使用全限定名 `me.rerere.rikkahub.BuildConfig`。若该 build type 关闭了 `buildConfig` 特性则不存在 |
| 3 | **quickie API 形状** | `ScanQRCode()`、`QRResult.QRSuccess`、`result.content.rawValue` 依据 `SettingProviderPage.kt` 现有用法推断，未编译核实 |
| 4 | **`localFailure` 语义** | `ClaudePProviderConfigure` 的 `status.allowsDispatch` 控制 `Switch.checked`，可能与 `useEditState` 的既有约定冲突（该文件其余分支使用 `internalProvider`） |
| 5 | **`noBackupFilesDir` 与 `setUserAuthenticationRequired(false)`** | 依赖 `CodexCredentialStore` 的既有形状；未在真机验证 |
| 6 | **`Signature("SHA256withECDSA")` 与 Keystore EC 密钥** | 需要 `setDigests(DIGEST_SHA256)`；已在代码中设置，未验证 |
| 7 | **Compose 预览/测试编译** | 新增的 `Card`/`OutlinedButton`/`CardDefaults` 用法未编译核实 |
| 8 | **`ClaudePProvider` 新增尾部参数** | 已确认 CP1-A 全部调用点均不传该参数，默认值保持行为不变；未编译核实 |

**结论：`:app` 的首次真实编译证据将是 Android CI，而非本机。**

---

## 8. 依赖与仓库外产物

### 8.1 新增/变更依赖

**无。** `gradle/libs.versions.toml`、各 `build.gradle.kts`、`settings.gradle.kts` 均未修改。

- 未新增 `kotlinx-coroutines-test`（该仓库没有，测试通过 `Dispatchers.Unconfined` + 同步 fake 实现确定性）
- 未新增 `androidx.security-crypto`（沿用仓库既有的 `AndroidKeyStore` + `Cipher` 约定）
- 未升级 OkHttp / Kotlin / AGP / Compose / SDK

### 8.2 仓库外文件

| 路径 | 内容 | 是否进入仓库 |
|---|---|---|
| `C:\Users\hp\.claudep-buildcheck\libs\` | 从 Maven Central 下载的编译/测试依赖 jar 与 Kotlin 2.4.0 编译器 | **否** |
| `C:\Users\hp\.claudep-buildcheck\out-main\`、`out-test\` | 编译产物 | **否** |
| `C:\Users\hp\.claudep-buildcheck\compile.sh`、`run.sh` | 测试台脚本 | **否** |

已核实：`git status --porcelain --ignored` 中无被忽略文件，`.gitignore` 无需新增条目；工作区洁净。

---

## 9. CI 预期执行的 Gradle 任务（**本轮未触发**）

授权冻结。若日后授权，CP1-B 的最终 SHA 需要在**既有的** `Build Debug APK` workflow 上验证。该 workflow 现有 CP1-A 步骤为：

```bash
./gradlew :ai:testDebugUnitTest \
  --tests "me.rerere.ai.provider.claudep.*" \
  --tests "me.rerere.ai.provider.providers.ClaudePProviderStreamTest" \
  --tests "me.rerere.ai.provider.providers.ClaudePProviderCancellationTest" \
  --tests "me.rerere.ai.provider.ClaudePSettingTest" \
  --tests "me.rerere.ai.provider.ProviderManagerClaudePTest"
./gradlew :app:testDebugUnitTest \
  --tests "me.rerere.rikkahub.data.ai.background.ClaudePBackgroundExclusionTest" \
  --tests "me.rerere.rikkahub.ui.pages.setting.components.ClaudePProviderConfigureTest"
```

`--tests "me.rerere.ai.provider.claudep.*"` **通配符已覆盖本轮新增的 5 个测试类**（它们与 CP1-A 的
`ClaudePProtocolTest`/`ClaudePFakeGatewayTest` 同包），因此：

- **不需要修改 workflow 即可收集并执行 CP1-B 新测试。**
- 但既有 `Report executed Claude P test classes` 步骤按 `*ClaudeP*.xml` 匹配，仍会正确列出新增类。
- 预期测试数从 101 增至 **101 + 122 = 223**（若 `:app` 编译通过）。

**尚未验证的 CI 前置条件**：`:app` 与 `:ai` 主源码能否编译。这是本轮唯一可能使 CI 失败的因素，且本机没有任何手段能提前排除。

---

## 10. 零调用与残留声明

| 项目 | 状态 |
|---|---|
| 连接 CC VPS / 真实 Gateway | **未执行** |
| 安装/登录/执行 Claude Code | **未执行** |
| 读取或搬运 Claude OAuth / API key / Cookie | **未执行** |
| 调用任何模型 | **0 次** |
| 扫描或生成真实可用二维码 | **未执行**（测试中只有字面量 JSON 载荷） |
| 部署 / 反向代理 / DNS / 证书 | **未执行** |
| push 到任何远端 | **未执行** |
| 触发 GitHub CI | **未执行** |
| 创建 PR / Tag / Release | **未执行** |
| 修改 master | **未执行** |
| 修改源仓库 `D:\rikkahub-agent1` | **未执行** |
| 进入 CP2 / MCP / 附件 / resume/fork | **未执行** |

Git 残留：无。进程残留：本轮未启动 Gradle daemon、未运行 `adb`、未启动服务器进程。测试台进程均为一次性 `java` 调用，已退出。

---

## 11. 未执行项

1. **`:app` 编译与单元测试** —— 无 Android SDK，`gradlew` 连配置阶段都无法通过。
2. **Android Keystore 真实行为** —— 非导出性、密钥失效、`noBackupFilesDir`、GCM 解密失败路径。
   **JVM fake 不能冒充此项证据**；需要 instrumentation。
3. **QR 导入加固的行为测试** —— 见 §12。
4. **真机/模拟器 UI** —— 扫码 launcher、状态渲染、开关禁用。
5. **CP1-A 的 60 条测试**（§6.3）。
6. **任何真实网络连接**。

---

## 12. 已知测试覆盖缺口（如实标注）

1. **导入加固无自动化测试。** `sanitizedAfterImport` 依赖 `android.util.Base64`（JVM 无实现），且
   项目无 Robolectric；尝试移入 `ai` 会引入 `ProviderSetting`（依赖 Compose），使本地测试台无法编译
   整个 `claudep` 包，故已回退。该规则目前**仅经静态复审**，KDoc 中已标注。

2. **`WssClaudePGatewayClient.resume()` 的返回帧。** 冻结的 `stream.resume.result` DTO 无 `frames`
   字段，因此真实传输的重放帧经 Generation 流推送，`Replayed.frames` 为空；fake 则内联返回。
   两者满足同一接口，但差异已记录为 **CP2 集成事项**。

3. **OkHttp 5 无 `request(n)`。** socket 级只能「有界 + 溢出断开」，不能节流对端。
   每 Generation 的可恢复界在 `ClaudePBoundedGenerationStream`，恢复路径是 `stream.resume`。

4. **`AndroidKeystoreClaudePDeviceKeyStore` 的 JVM fake 与生产实现不共享代码。**
   `InMemoryClaudePDeviceKeyStore` 用真实 ECDSA P-256 但密钥可导出。它验证的是**上层逻辑**，
   不是 Keystore 语义。

---

## 13. 复审点状态

- 分支 `codex/claudep-cp1b-local`，HEAD = `10ad17ba2e485030c02ec74603417d47494f0960`，**未 push**。
- 工作区洁净，`git diff --check` 通过。
- **CI 未触发**，授权按用户指示冻结。
- 源仓库、master、远端均未被触碰。
- **停在静态复审点，等待 CI 授权。**
