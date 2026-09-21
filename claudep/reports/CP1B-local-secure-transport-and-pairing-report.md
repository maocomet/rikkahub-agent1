# CP1-B｜安全联网传输与一次性配对 — 本地实施报告

状态：**CP1-B R4 收口 —— 两关 CI 全绿 + 真机复验通过**（见 §20）
日期：2026-09-20（§20 于 2026-09-21 追加）
分支：`codex/claudep-cp1b-local`（本地与远端一致，**本轮未 push**）
Worktree：`D:\rikkahub-agent1.worktrees\claudep-cp1b`
CI：R1–R4 共 7 个 run 已执行（逐轮结果见 `CP1B-ci-evidence.md`）
PR / Tag / Release / master：**均未触碰**

> §1–§19 是**按当时时点**写的阶段记录，其「未触发 / 未验证」表述在当时为真，
> 一律保留不回填。最终状态以 §20 与 `CP1B-ci-evidence.md` §7–§8 为准。
>
> 本报告**仍不使用**「CP1-B 已完成 Gateway 配对」或「Claude P 可对话」这类表述：
> 本仓库内不存在 Gateway 服务端，也没有发生过任何模型调用。

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
| **R0 最终 HEAD（旧）** | `10ad17ba2e485030c02ec74603417d47494f0960` |
| **R1 代码最终 HEAD** | `059fc2f3`（`fix(claudep): serialize pairing and revocation`，见 §14） |
| **R1 最终 HEAD（含报告）** | `0efbc7d6` |
| **R1.1–R1.4（生产接线 + 测试 + 本报告）** | `25f47633` / 见 §15 |
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


---

## 14. R1 返修（静态复审后）

R0 的静态复审发现 6 项安全边界缺陷。**R0 的全部结论已被本节取代**；§6.2 的
「163 tests」只属于 R0 那个 HEAD，不得沿用。

### 14.1 修复项

| 项 | 缺陷 | 修复 |
|---|---|---|
| **P1-1** | Claude P 使用共享 OkHttpClient；`newBuilder()` **会复制 interceptor 列表**，因此共享的 request-logging、debug header-logging、AI interceptor 都会作用于携带 credential / ticket / proof 的流量。R0 中"没有继承日志拦截器"的注释不成立 | 新增 `ClaudePOkHttp`：`newIsolated()` 从**全新** `OkHttpClient.Builder()` 构建；`hardened()` 额外**清空**两个 interceptor 列表（错误注入也无法记录凭证）。两个 connector 改走 `hardened()`。DI 注册具名 `claude_p` client |
| **P1-2** | 运行期 handshake 调 `loadOrCreate`：私钥丢失/恢复失败/被删后会**生成新钥匙**，而不是进入未配对 | 契约拆为 `createFresh`（仅 pairing）/ `loadExisting`（仅 runtime，**绝不创建**）/ `delete` 返回 Boolean。运行期路径不再能创建或替换设备密钥 |
| **P1-3** | Repository 每次 `pair()` 新建 PairingClient，其 Mutex 与 consumed-ticket guard **不跨请求生效** | `ClaudePPairingClient` 改为**单例复用**，接收按 endpoint 解析的 transport **工厂**；每次 attempt 使用**独立 key alias**，一个失败 attempt 无法删除另一个的 key |
| **P1-4** | 凭证写入后 settings 写入失败会留下可用 credential；"原子写入"实为 `copyTo()`；`clear()` 吞掉删除失败；AES wrapping key 未删除；unpair 后 dispatch 路径仍直接读 credential | 见 §14.2 |
| **P1-5** | 只更新 `_connectionState`，未重新派生 `_status` | connection 变化经同一个 `refresh()` 重新派生；旧 client 的事件被身份比对丢弃 |
| **P2** | `sanitizedAfterImport()` 绑在 `ShareSheet.kt`（需要 `android.util.Base64` + Compose），只能静态复审 | 规则移入 `ai`，不依赖 Android/Compose；Base64 解码仍留在 app 层。**新增 5 条直接单元测试** |

### 14.2 P1-4 的 fail-closed 协议

```text
pair() 成功交换后：
  1. credentialStore.write()  → 暂存到 .tmp，renameTo 原子替换
       替换失败 → 旧记录保持原样，临时文件清除，返回失败（不覆盖有效旧配对）
  2. 写入失败 → 补偿：清除新 credential + 删除新 device key
              → PAIRING_NOT_PERSISTED（不谎报成功）
  3. settings 写入失败 → 同样补偿 → PAIRING_NOT_PERSISTED
  4. 全部成功 → 关闭旧 client（新身份取代旧身份）

unpair()：
  1. settings → REVOKED 且 enabled=false     ← 先剥夺可调度性
  2. 关闭 socket；派生状态
  3. credentialStore.clear()  → 删密文文件 + 删 AES wrapping key，失败**返回**不吞
  4. deviceKeyStore.delete(alias) → 返回 Boolean；alias 不可知时报告 KEY_ALIAS_UNKNOWN
  5. settings → NOT_PAIRED
  返回 ClaudePUnpairResult(failures)；空列表才代表收口完成

gatewayClientOrNull()（唯一获得 transport 的途径）：
  必须同时满足，任一不满足即返回 null（不构建 client）：
    · settings.pairingState == PAIRED（NOT_PAIRED / REVOKED 一律拒绝）
    · credential Present 且未过期
    · pairedOrigin / gatewayFingerprint / gatewayInstallationId / deviceId 与设置一致
    · keyAlias 非空且 loadExisting 可加载、公钥可读
  client 按 identity(deviceId|keyAlias|origin|credential) 缓存；
  身份变化或 unpair 一律销毁旧 client
```

### 14.3 本机实际执行（R1 最终 HEAD）

在 R1 最终 HEAD（工作区洁净）上重新完整执行：

```
bash /c/Users/hp/.claudep-buildcheck/run.sh
JUnit version 4.13.2
OK (176 tests)
```

| 类别 | 类数 | 执行 | 通过 | 失败 | 跳过 |
|---|---:|---:|---:|---:|---:|
| CP1-B 新增（`ai`，harness 可编译） | 6 | 135 | 135 | 0 | 0 |
| CP1-A 真正复跑 | 2 | 41 | 41 | 0 | 0 |
| **harness 合计** | **8** | **176** | **176** | **0** | **0** |

CP1-B 新增分模块：`ClaudePWssTransportTest` 36、`ClaudePEndpointTest` 33、
`ClaudePPairingFlowTest` 25、`ClaudePCredentialStoreTest` 21、
`ClaudePUiStatusTest` 14、`ClaudePOkHttpTest` 6。

### 14.4 本机**未**编译的测试（不得计入）

| 类 | `@Test` | 原因 |
|---|---:|---|
| `ClaudePImportSanitizerTest` | 5 | 与 `ClaudePImportSanitizer.kt` 同因：`ProviderSetting` 依赖 `androidx.compose.runtime`，超出本地 harness 的编译集合。harness 已按显式文件列表**排除**该文件，并在 `compile.sh` 中写明排除原因 |
| CP1-A 的 `ClaudePProviderStreamTest` / `ClaudePProviderCancellationTest` / `ClaudePSettingTest` / `ProviderManagerClaudePTest`（`:ai`） | 49 | 需要 `:ai` 全模块（Compose） |
| CP1-A 的 `ClaudePBackgroundExclusionTest` / `ClaudePProviderConfigureTest`（`:app`） | 11 | `:app` 未编译 |

**CP1-A 的 101 条中，本机真正复跑 41 条，未运行 60 条。**
**CP1-B 新增 140 条中，本机执行 135 条，未编译 5 条。**

### 14.5 仍未验证（app 侧）

§7 的风险清单在 R1 后**全部仍然有效**，并新增：

| # | 风险 |
|---|---|
| 9 | Koin `single(named("claude_p"))` 与 `get(named("claude_p"))` 的解析需 CI 确认 |
| 10 | `ClaudePOkHttp.newIsolated()` 在 Android 上的实际 interceptor 列表（`ClaudePOkHttpTest` 用 JVM OkHttp 验收，逻辑相同但非 Android 运行时） |
| 11 | `renameTo` 在 `noBackupFilesDir` 上的原子性（Android/Linux 上应为原子；未在真机验证） |
| 12 | `ClaudePUnpairResult` / `ClaudePUnpairFailure` / `ClaudePPairingCleanupFailure` 的 app 层调用点（`SettingProviderDetailPage` 目前忽略 unpair 结果——**已知缺口**：清理失败尚未呈现给用户） |

### 14.6 P1-4 覆盖缺口（如实标注）

以下要求项**无法由本机 JVM 测试覆盖**，因为实现位于 `:app`（依赖 `android.util.Log`、
`Context`、`noBackupFilesDir`）：

- credential write 成功 / settings write 失败的补偿；
- 临时文件写入失败与替换失败；
- 密文删除失败、AES key 删除失败、device key 删除失败；
- unpair 后旧 Provider 即使仍 enabled 也不能 dispatch；
- 旧 credential 残留时 REVOKED 优先；
- re-pair 后旧 client 不再可用；
- app/process restart 后仍 fail-closed。

其中「凭证缺失 / 密钥缺失 / 身份不一致 → 不构建 client」的**纯逻辑部分**已由
`ClaudePCredentialStoreTest` 与 `ClaudePWssTransportTest` 在 `ai` 层覆盖；
`ClaudePPairingClient` 的 attempt 级清理与 `cleanupFailures` 上报亦已覆盖。
**但 Repository 与两个 Android store 的行为本身，未经任何自动化验证。**

### 14.7 R1 提交

| # | Commit |
|---|---|
| 1 | `9a6a95b3` `fix(claudep): isolate credential transport` |
| 2 | `059fc2f3` `fix(claudep): serialize pairing and revocation` |
| 3 | `0efbc7d6` `docs(claudep): record CP1-B R1 evidence` |

### 14.8 状态

- 分支 `codex/claudep-cp1b-local`，**未 push**；CI **未触发**；PR / tag / master 均未触碰。
- 工作区洁净，`git diff --check` 通过。
- 依赖**零变更**。
- **停在静态复审点，等待 CI 授权。**


---

## 15. R1.1–R1.4：生产接线、UI 与最终静态复审准备

R1 之后又进行了四轮返修。**§14 的 R1 结论已被本节取代**；§6.2 的「163 tests」、§14.3 的「176 tests」
均只属于各自的 HEAD，不得沿用。

### 15.1 生产接线现已完成

`ClaudePPairingCoordinator` 是 **pair / unpair / retryCleanup 的唯一生命周期编排者**。
Repository 中原有的第二套撤销、补偿、密钥删除与状态切换实现**已被删除**（不是弃用），因此不存在
可以独立漂移的第二条路径。Repository 现在只负责 coordinator 不该管的：transport 生命周期、
派生 UI 状态、以及决定"能否存在 transport"的一致性校验。

`gatewayClientOrNull()` 首先检查 `mustNotDispatch()`：REVOKED、任何非 `Absent` 的 tombstone
（含 malformed / 未知版本）一律**不建立也不返回 client**。

### 15.2 Android 实现

| 文件 | 内容 |
|---|---|
| `FileClaudePCleanupTombstoneStore` | `noBackupFilesDir` 私有目录；`Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`；**不自行判断**，只把字节交给 codec 并返回其结论 |
| `SettingsClaudePPairingGateway` | Claude P settings 的**唯一写入者**；`REVOKED + enabled=false` 先于破坏性步骤；`NOT_PAIRED` 仅在全部删除 + tombstone 确认后写 |
| `ClaudePCleanupTombstoneCodec`（`ai`） | 编码、校验、判定；记录长度、版本、字段集合、alias 形状与长度 |

**数据存储的事务性**：DataStore 与 Keystore/文件系统之间**没有跨介质事务**。这一点在代码与提交中
都写明，没有伪称原子。替代方案是"每一步都安全"的顺序 —— 任何时刻崩溃都停在 REVOKED + tombstone，
下次启动可恢复。

### 15.3 干净设备语义

`settings=NOT_PAIRED` + 无 credential + 无 tombstone + 无可定位 key 时，unpair/retry **幂等成功**，
保持 `NOT_PAIRED`，不返回 `DEVICE_KEY_ALIAS_UNKNOWN`。但只要存在 REVOKED、**任何** tombstone 记录
（含不可解析的）或 credential 残留，就绕不过清理。

### 15.4 UI

`SettingProviderDetailPage` 不再忽略 unpair 结果：部分失败显示脱敏提示、提供 "Retry cleanup"、
尝试期间禁用按钮防重复点击、清理未完成时禁止扫码。异常或取消**不会**重新启用任何东西。
不渲染 alias、路径、异常正文、credential 或内部枚举。`hasPendingCleanup()` 读持久状态，
所以重试入口**跨进程重启仍在**。

### 15.5 本机实际执行的测试（最终 HEAD）

在最终 HEAD 上重新完整执行：

```
bash /c/Users/hp/.claudep-buildcheck/run.sh
JUnit version 4.13.2
OK (222 tests)
```

| 类别 | 执行 | 通过 | 失败 | 跳过 |
|---|---:|---:|---:|---:|
| CP1-A **真正复跑**（`ClaudePProtocolTest` + `ClaudePRequestFingerprintTest` + `ClaudePFakeGatewayTest`） | 41 | 41 | 0 | 0 |
| CP1-B 新增（`ai`，harness 可编译） | 181 | 181 | 0 | 0 |
| **合计** | **222** | **222** | **0** | **0** |

CP1-B 新增分模块：`ClaudePWssTransportTest` 36、`ClaudePEndpointTest` 33、
`ClaudePPairingCoordinatorTest` 24、`ClaudePPairingFlowTest` 25、
`ClaudePCleanupTombstoneCodecTest` 17、`ClaudePCredentialStoreTest` 21、
`ClaudePUiStatusTest` 14、`ClaudePOkHttpTest` 7、`ClaudePRevocationTest` 7。

### 15.6 本机**未**编译的测试（不得计入）

| 项 | 数量 | 原因 |
|---|---:|---|
| `ClaudePImportSanitizerTest` | 5 | 依赖 `ProviderSetting` → `androidx.compose.runtime`，超出本地 harness 编译集合；`compile.sh` 已按显式文件列表排除并写明原因 |
| CP1-A `ClaudePProviderStreamTest` / `ClaudePProviderCancellationTest` / `ClaudePSettingTest` / `ProviderManagerClaudePTest` | 49 | 需要 `:ai` 全模块（Compose） |
| CP1-A `ClaudePBackgroundExclusionTest` / `ClaudePProviderConfigureTest`（`:app`） | 11 | `:app` 未编译 |

**CP1-A 的 101 条中真正复跑 41 条，未运行 60 条。**

### 15.7 尚未编写 / 未执行的测试（如实标注）

本轮**没有**编写以下测试（用户 R1.4 清单中要求）：

- Repository 委托同一 coordinator 的行为测试；
- DI 构造图 / 绑定测试；
- Android 文件 store 与 DataStore gateway 的测试；
- Compose UI 五态测试。

**原因是本轮上下文预算耗尽**，不是这些测试不重要或无法编写。它们**并非"已写待 CI 执行"** ——
**它们不存在**。请勿把本节理解为"已覆盖，只是没跑"。

### 15.8 高风险编译点的静态核对结果

这些点是**静态阅读**结论，**不是编译通过**，且 `:app` 从未被编译过。

| 检查点 | 结论 |
|---|---|
| Repository 构造签名 ↔ DI call site | 已核对：DI 传 `credentialStore` / `deviceKeyStore` / `tombstoneStore` / `settingsGateway` / `scope` / `appVersion`，与签名一致 |
| `LaunchedEffect` 内 suspend 调用 | 已核对：`pairingRepository.hasPendingCleanup()` 在 `LaunchedEffect`（协程作用域）内调用，合法 |
| `hasPendingCleanup()` 取消处理 | 它是普通 suspend 函数，由 Compose 作用域承载；取消随作用域传播 |
| kotlinx.serialization 注解 | `@Serializable` / `@SerialName` 在 `ai` 与 `app` 均可用（`ai` 提供 `api(libs.kotlinx.serialization.json)`，`app` 已依赖） |
| `Files.move` / `java.nio.file` 在 minSdk 26 | **已核对但需注意**：`java.nio.file` 自 **API 26** 起可用，项目 `minSdk = 26`，因此**无需 API guard**。若未来下调 minSdk，此处会静默失效 —— 已在代码注释中标注 |
| DataStore `update` 返回/异常类型 | 已核对：`SettingsStore.update(fn: (Settings) -> Settings)` 为 suspend、返回 Unit；调用点已用 try/catch 包裹 |
| Compose 文案 / 按钮状态 / imports | 已核对 `Button`/`OutlinedButton`/`MaterialTheme` 等 import 存在；**未编译核实** |

### 15.9 依赖

**零变更。** `gradle/`、`*.gradle.kts`、`settings.gradle.kts` 相对 CP1-A 基线无改动。

### 15.10 状态

- 分支 `codex/claudep-cp1b-local`，**未 push**；CI **未触发**；PR / tag / master 未触碰。
- 工作区洁净，`git diff --check` 通过。
- **`:app` 仍无任何真实 Android 编译证据。**
- **停在静态复审点，等待是否消耗唯一 Android CI。**


---

## 16. R1.5：CI 前收口

**§15 中「§15.7 尚未编写的测试」一节仅对该 HEAD 有效**；本轮新增了 UI 状态测试并完成了 CI 可执行性核对。

### 16.1 CI 可执行性核对（未触发 workflow，只读取）

| 项 | 结论 |
|---|---|
| `build-debug-apk.yml` 是否编译 `:app` | **是**（`assembleDebug`） |
| 是否编译 `:ai` | **是**（新增 step 显式运行 `:ai:testDebugUnitTest`） |
| 是否编译新增 Android unit test | **是**（`:app:testDebugUnitTest` 会编译整个 test 源集） |
| **`ClaudePImportSanitizerTest` 是否进入 CI** | **是。无需修改 workflow** —— CI 使用 `--tests "me.rerere.ai.provider.claudep.*"`，该测试与 CP1-A/CP1-B 其余测试同包，已被通配符覆盖。它被排除的只是**本地 harness**，不是 Android CI |
| 是否运行 instrumentation | **`build-debug-apk.yml` 明确不运行**，且它的注释说明了原因。另有独立的 `migration-instrumentation.yml` 使用 managed device 运行 androidTest |
| 本轮是否新增 workflow 任务 | **否**。未修改任何 workflow |

**结论：唯一一次 CI 已足以覆盖 `:ai`/`:app` 编译与全部 Claude P JVM 测试，无需改动 workflow。**

### 16.2 本轮新增（本地实际运行）

`ClaudePConfigureUi` + `ClaudePConfigureUiTest`：13 条测试，全部由本地 harness 实际执行。
Compose 页面改为渲染该受测状态，不再自行推导规则 —— 原先 `cleanupPending || unpairCleanupFailures.isNotEmpty()`
就是第二套判断。

**最终 HEAD 本机实际执行：**

```
bash /c/Users/hp/.claudep-buildcheck/run.sh
JUnit version 4.13.2
OK (235 tests)
```

| 类别 | 执行 | 通过 | 失败 | 跳过 |
|---|---:|---:|---:|---:|
| CP1-A 真正复跑 | 41 | 41 | 0 | 0 |
| CP1-B 新增（harness 可编译） | 194 | 194 | 0 | 0 |
| **合计** | **235** | **235** | **0** | **0** |

### 16.3 本机**未运行**的测试（逐项列表）

| 文件 | `@Test` | 状态 |
|---|---:|---|
| `ai/.../claudep/ClaudePImportSanitizerTest.kt` | 5 | **已写，本地未编译**（需 `ProviderSetting` → `androidx.compose.runtime`）。**Android CI 会执行它** |
| `ai/.../providers/ClaudePProviderStreamTest.kt` | 17 | CP1-A，本地未运行（需 `:ai` 全模块） |
| `ai/.../providers/ClaudePProviderCancellationTest.kt` | 13 | 同上 |
| `ai/.../provider/ClaudePSettingTest.kt` | 11 | 同上 |
| `ai/.../provider/ProviderManagerClaudePTest.kt` | 8 | 同上 |
| `app/.../background/ClaudePBackgroundExclusionTest.kt` | 6 | CP1-A，`:app` 未编译 |
| `app/.../setting/components/ClaudePProviderConfigureTest.kt` | 5 | 同上 |
| **合计** | **65** | |

### 16.4 本轮**未编写**的测试 —— 技术阻塞（如实报告）

用户 R1.5 要求的三组测试中，以下两组**无法在现有测试框架下构造**，属于真实技术阻塞，**不是**"已写待 CI"：

**（一）Repository 测试。** `ClaudePDevicePairingRepository` 位于 `:app`。它虽然只依赖接口，
但其实现路径要经由 Android（`android.util.Log` 等），且构造它需要 Koin 作用域。
本机无 Android SDK，无法编译或运行 `:app` 的任何 JVM 测试。
**没有以 fake 冒充**：我没有写"只测 fake coordinator 自己"的替代品。

**（二）Android tombstone store 与 Settings gateway / DI 测试。** 分别依赖
`Context`、`noBackupFilesDir`、DataStore 与 Koin 装配，同样需要 Android SDK。

**已做的替代（有限的、已标注的）**：把这两者中**可以脱离 Android 的判定逻辑**提取到 `ai` 并测试 ——
即 `ClaudePCleanupTombstoneCodec`（17 条）与 `ClaudePConfigureUi`（13 条）。
它们覆盖的是**规则**，不是 Android 实现本身；`FileClaudePCleanupTombstoneStore` 的文件 I/O、
原子替换与 `noBackupFilesDir` 位置，以及 `SettingsClaudePPairingGateway` 的 DataStore 写入，
**仍然没有任何自动化证据**。

### 16.5 静态复审

上述新增测试均在 `ai`，由 harness 实际执行；其断言在回退对应生产接线后会失败（例如
`canScanPairingQr` 若不再考虑 `cleanupIncomplete`，`a pending cleanup blocks scanning` 立即失败）。

**生产代码本轮只做了一处非测试改动**：Compose 页面改为消费受测 reducer。未发现需要修复的真实缺陷。

### 16.6 状态

- 分支 `codex/claudep-cp1b-local`，**未 push**；CI **未触发**；PR / tag / master 未触碰。
- 工作区洁净，`git diff --check` 通过；依赖零变更；未修改任何 workflow。
- **`:app` 仍无任何真实 Android 编译证据。**
- **停在最终静态复审点，等待是否消耗唯一 Android CI。**


---

## 17. R1.6：生产 adapter 测试与 CI 精确门禁

### 17.1 纠正 §16.1 的宽泛表述

§16.1 说「`:app:testDebugUnitTest` 会编译整个 test 源集」是对的，但「Claude P 的 app 测试会被执行」
**只有在类名出现在 `--tests` 白名单里时才成立**。此前该白名单只列了两个既有 app 类，新增 app 测试
**只会被编译、不会被执行**。本节把这件事写清楚，并已修正白名单。

### 17.2 新增 `:app` JVM 测试

| 文件 | 内容 |
|---|---|
| `app/.../data/claudep/ClaudePDevicePairingRepositoryTest.kt` | 直接实例化**生产** Repository，经真实 `pair` / `unpair` / `retryCleanup` 驱动 |

关键前提：`ClaudePDevicePairingRepository` **没有任何 Android import** —— 它只经由接口访问
settings、credential、key 与 tombstone，因此可以在 `:app` 的普通 JVM 单元测试中构造。

为使其可测，新增两个**生产缺省** seam（不是测试专用分支）：

- `FileClaudePCleanupTombstoneStore`：私有构造接受 base directory；**生产构造仍只取 `context.noBackupFilesDir`**。
- `ClaudePDevicePairingRepository`：`pairingTransportFor` / `webSocketConnectorFor`，缺省即真实的 OkHttp 实现。

覆盖：未配对 / REVOKED / 有效 tombstone / malformed tombstone / 未知版本 tombstone 一律不返回 client；
pair/unpair/retry 走 coordinator 状态机；部分清理后不可调度；重建 Repository（模拟重启）仍阻止 dispatch
且可完成 retry；干净设备重复 unpair 幂等；settings 或 credential 写失败绝不进入 paired。
**删除 coordinator 委托或恢复第二条清理路径会使这些测试失败。**

### 17.3 CI 白名单与门禁

- `ClaudePDevicePairingRepositoryTest` 已加入 `:app:testDebugUnitTest --tests` 列表。
  **未扩大为全量测试**，未改动触发条件、签名、构建、上传步骤。
- XML 门禁由「至少找到一个 ClaudeP XML」改为**逐项确认**：列出全部 **21 个必需类**，
  按每个 XML 自身的 `name=` 属性比对（不是文件名），缺任何一个即 `exit 1`。
  理由：`--tests` 只在**组合过滤整体为空**时才报错，单个类被改名或被删掉是静默的。

### 17.4 本机实际执行（最终 HEAD）

```
bash /c/Users/hp/.claudep-buildcheck/run.sh
JUnit version 4.13.2
OK (235 tests)
```

| 类别 | 执行 | 通过 |
|---|---:|---:|
| CP1-A 真正复跑 | 41 | 41 |
| CP1-B 新增（`ai`，harness 可编译） | 194 | 194 |
| **合计** | **235** | **235** |

### 17.5 只能由 Android CI 验证的类

| 类 | `@Test` | 本机状态 |
|---|---:|---|
| `app:...ClaudePDevicePairingRepositoryTest` | 13 | **已写，从未编译或运行** |
| `app:...ClaudePProviderConfigureTest`（CP1-A） | 5 | 同上 |
| `app:...ClaudePBackgroundExclusionTest`（CP1-A） | 6 | 同上 |
| `ai:...ClaudePImportSanitizerTest` | 5 | 已写；harness 排除，**CI 会执行** |
| `ai:...ClaudePProviderStreamTest` / `ClaudePProviderCancellationTest` / `ClaudePSettingTest` / `ProviderManagerClaudePTest` | 49 | CP1-A，需 `:ai` 全模块 |

### 17.6 未编写：准确阻塞

`FileClaudePCleanupTombstoneStore` 的 JVM 文件测试**未编写**。原因：
该类的失败路径调用 `android.util.Log`，在普通 JVM 单元测试中会抛
`Method w in android.util.Log not mocked`，除非启用
`testOptions.unitTests.isReturnDefaultValues`。这是**构建配置决定**，不是可以单方面更改的；
因此**没有写一个注定失败的测试**。

`SettingsClaudePPairingGateway` 依赖 DataStore，同样需要 Android runtime。

两者的**判定规则**已由 `ClaudePCleanupTombstoneCodec`（17 条）覆盖；**Android 实现本身仍无自动化证据**。

### 17.7 状态

- 分支 `codex/claudep-cp1b-local`，**未 push**；CI **未触发**；PR / tag / master 未触碰。
- 工作区洁净，`git diff --check` 通过；依赖零变更。
- **`:app` 仍无任何真实 Android 编译证据。** 新增的 13 条 app 测试首次得到编译与运行证据将是 CI。
- **停在最终 CI 授权点。**


---

## 18. R2：首次 CI 结果与返修

### 18.1 首次 CI（已消耗的授权）

| 项 | 值 |
|---|---|
| Run ID | **35563308861** |
| URL | https://github.com/maocomet/rikkahub-agent1/actions/runs/35563308861 |
| SHA | `95c42dea1ea5d978a1cb643d48811771c12a83ea` |
| attempt | **1**（从未 rerun） |
| 结论 | **failure** |

**第一个可信失败：step 9 `Build debug APK` → `:app:compileDebugKotlin` 编译失败。**

```
DataSourceModule.kt:1545:31  No value passed for parameter 'resolve'.
DataSourceModule.kt:1545:61  Argument type mismatch: actual type is
                             '() -> ClaudePGatewayClient?', but 'ClaudePGatewayClient' was expected.
DataSourceModule.kt:1546:40  Suspend function 'gatewayClientOrNull()' can only be called
                             from a coroutine or another suspend function.
DataSourceModule.kt:1548:42  Return type mismatch: expected 'String', actual 'String?'.
```

**共 4 个编译错误，全部位于同一处 DI 注册块**（R1.3 引入）。

**级联，非根因**：step 12 与 14（两个 `if: always()` 的 JUnit XML 报告步）同样显示 failure —— 编译未通过
⇒ 无 XML ⇒ 门禁正确报告"什么都没跑"。**未将其计为测试失败或门禁失败。**
Step 10（签名校验）、11、13（两批测试）、16（APK 上传）被 skip。

**无 artifact 产出**，故无 APK 名称/大小/SHA-256。

### 18.2 根因一：尾随 lambda 绑定

`ResolvingClaudePGatewayClient` 的 `resolve` 是**首参**、`fallback` 是**末参**。
`ResolvingClaudePGatewayClient { ... }` 的尾随 lambda 被绑定到 `fallback`（非函数类型），
于是 `resolve` 缺参、lambda 类型不符，并连带产生"suspend 函数不能在非挂起上下文调用"。

修复：改为命名参数 `resolve = { ... }`。**fail-closed fallback 未改动。**

### 18.3 根因二：nullable 被违反的契约掩盖

`currentDeviceIdOrNull()` 返回 `String?`，而 `deviceIdProvider: (() -> String)?` 要求非空。
**没有用 `"unpaired-device"`、空串或其他占位值去满足类型** —— 改为修正契约本身（见 §18.4）。

### 18.4 动态设备身份契约（本轮发现的问题）

三处缺陷：

1. **身份分裂**：`streamText` 的 fingerprint 取动态 device id，而 `ensureHandshake()` 的
   `client.hello` 取构造器字段。一次请求可能以 `"unpaired-device"` 握手，却把 fingerprint 绑到真实设备。
2. **缓存跨重新配对**：单一 `cachedServerHello` 在 unpair 后仍保留，重新配对后的请求可能复用**上一个身份**协商的 hello。
3. **陈旧的 volatile 身份**：`currentDeviceIdOrNull()` 直接返回 `lastKnownDeviceId`，该值在撤销后仍然存在。

修复：

- `deviceIdProvider` 改为 `(suspend () -> String?)?`。**已配置但返回 null/blank ⇒ 在任何 dispatch 之前
  以 `NOT_PAIRED` fail-closed**，绝不用构造器占位值回退。未配置 ⇒ 保持 CP1-A 静态行为与缓存。
- `streamText` **只解析一次**身份，hello 与 fingerprint 严格共用同一值。
- `currentDeviceIdOrNull()` 改为 `suspend`，经与 transport 相同的 `resolveDevice()` 重新校验
  durable 状态（settings 一致、credential 存在未过期、key 可加载），不再返回缓存。
- 动态 resolver **不复用 hello 缓存**，每次请求重新协商。仅按 device id 缓存也不充分 ——
  没有证据表明重新配对必然产生新 id，缓存键可能跨两次配对碰撞。代价是每请求多一次有界 `client.hello`。

未扩大 wire protocol，未新增危险 fallback，未改动持久化 credential 格式或引入 migration。

### 18.5 本机实际执行（最终 HEAD）

```
bash /c/Users/hp/.claudep-buildcheck/run.sh
JUnit version 4.13.2
OK (239 tests)
```

| 类别 | 执行 | 通过 |
|---|---:|---:|
| CP1-A 真正复跑 | 41 | 41 |
| CP1-B 新增（harness 可编译） | 198 | 198 |
| **合计** | **239** | **239** |

### 18.6 本机**未**编译（R2 新增）

| 类 | `@Test` | 说明 |
|---|---:|---|
| `ai:...providers.ClaudePProviderIdentityTest` | 8 | **已写**；`ClaudePProvider` 位于本地 harness 编译集合之外（依赖 Compose），因此**本机未编译、未运行** |

**并且：本轮的 `ClaudePProvider` 改动与 `DataSourceModule` 修复本身也没有本机编译证据。**
它们分别位于 harness 编译集合之外与 `:app` 内。第二次 CI 是它们第一次被编译。

### 18.7 静态复核（`DataSourceModule`）

| 检查点 | 结论 |
|---|---|
| `resolve` 使用命名参数 | **是**（`resolve = { claudePPairing.gatewayClientOrNull() }`） |
| suspend lambda 类型正确 | 命名到 `resolve`，其类型即 `suspend () -> ClaudePGatewayClient?`，与 `gatewayClientOrNull()` 一致 |
| nullable 身份未被占位值掩盖 | **是** —— 无占位值；`deviceIdProvider` 已改为 nullable suspend，由 provider 侧 fail-closed |
| fallback 未改动 | 仍是 `UnpairedClaudePGatewayClient` |
| 触发条件/签名/上传步骤 | 未改动 |

**这是静态阅读结论，不是编译通过。**

### 18.8 CI 门禁

REQUIRED 数组由 **21 → 23**：新增 `ClaudePResolvingClientTest` 与 `ClaudePProviderIdentityTest`。
`ClaudePProviderIdentityTest` 同时加入 `:ai` 的 `--tests` 白名单（它在 `providers` 包，不受
`claudep.*` 通配符覆盖）。YAML 语法已校验。

### 18.9 状态

- 分支 `codex/claudep-cp1b-local`，**未 push**，**未触发第二次 CI**；PR / tag / master 未触碰。
- 工作区洁净，`git diff --check` 通过；依赖零变更。
- **停在第二次 CI 授权点。**


---

## 19. R3：Koin 启动崩溃

### 19.1 结论（按要求措辞）

> **这是已经源码确认的 Koin 注册缺陷，且与手机上的 ChatVM 创建崩溃高度一致；由于原始日志在回滚旧包后无法完整取回，不能把它描述为由完整 `Caused by` 日志绝对证明的唯一根因。**

崩溃日志**未能取得**：`adb devices` 为空，LDPlayer 模拟器进程未运行，5554/5555 均被拒绝，
在跑的 `adb.exe`（PID 7432）是 2026-09-17 遗留的 server。未卸载、未清数据、未改数据库。

### 19.2 只读追踪的解析路径

```
ChatVM            (chatService: ChatService)
  └─ ChatService  (providerManager: ProviderManager, ChatService.kt:602)
       └─ ProviderManager 的 single（DataSourceModule 内 get<ClaudePDevicePairingRepository>()）
            └─ ClaudePDevicePairingRepository 的 single
                 ├─ tombstoneStore  = get()   ← 无定义
                 └─ settingsGateway = get()   ← 无定义
```

`ChatVM` 在打开聊天页时构造，与"进入聊天页立即崩溃"吻合。

### 19.3 缺陷

`DataSourceModule` 只注册了**具体类**：

```kotlin
single { FileClaudePCleanupTombstoneStore(context = get()) }
single { SettingsClaudePPairingGateway(settingsStore = get()) }
```

而 Repository 请求的是**接口**。Koin 不会自动把实现绑定到接口。

### 19.4 修复

两处改为 `single<接口> { ... }`。**不建立重复实例**，不额外注册第二套 singleton。
**具体类型消费者扫描结果：无** —— 两个类名仅出现在各自文件与 DI 注册处，因此不需要受控别名绑定
（加了反而会造出本修复要避免的第二份状态对象）。

未改动协议、wire format、credential、DataStore schema、Keystore alias、配对状态或依赖版本。

### 19.5 测试与门禁

`app/src/androidTest/.../ClaudePKoinGraphTest.kt`（**7 条，instrumentation**）：加载**真实生产 Koin 图**，
**不 override** 被测的两个接口。覆盖：两接口解析为预期实现；重复解析为同一 singleton；
`ClaudePDevicePairingRepository` 构造完成；Repository 为 single；沿崩溃链解析到 `ProviderManager`。

**测试止于 `ProviderManager`。** `ChatVM` 需要运行时参数、只能在聊天页构造，**未被验证，也不得声称已验证**。

**为什么不是 JVM 测试**：两个定义分别需要 Android `Context`（读 `noBackupFilesDir`）与 DataStore 的
`SettingsStore`；JVM 造不出，除非引入 Robolectric 或 mock 框架 —— 本轮禁止。因此没有新增依赖，
也没有复制一套简化 module。

**workflow 门禁**：`migration-instrumentation.yml` 用**显式类名白名单**（`-Pandroid...class=$CLASSES`），
新类若不加入就会"编译但不执行"。已加入该列表；并新增 step
`Verify required Claude P Koin graph tests executed`，按 XML 中的 `name=` 断言该类确实执行，
缺失即 `exit 1`。这与 JVM 侧的白名单陷阱是同一类问题。

### 19.6 本机执行与未执行

| 项 | 状态 |
|---|---|
| 本地 harness（239 条） | **全绿** |
| 静态追踪 ChatVM 解析路径 | **已执行**（只读） |
| `ClaudePKoinGraphTest` 编译 / 执行 | **未执行** —— 需 managed-device instrumentation |
| `:app` 编译（含本次修复） | **未执行** —— 本机无 Android SDK；需下一次 CI |

### 19.7 状态

- 分支 `codex/claudep-cp1b-local`，**未 push**；未触发普通 CI，未触发 instrumentation；未创建 PR。
- 工作区洁净，`git diff --check` 通过；依赖零变更。
- **停在静态复审点。**


---

## 20. R3.1 / R3.2 / R4 收口（2026-09-21 追加）

本节关闭 §19 的静态复审点。§19 当时列出的「未执行」项，其最终结果如下。

### 20.1 R3.1：测试方法名与 DEX 合法性

run 35601743353 因 D8 拒绝含空格的 DEX 方法名而**未能编译** —— 这是**编译期**失败，
连「未执行」都算不上：`ClaudePKoinGraphTest` 的测试方法名必须满足 `\w` 字符集。
R3.1 提交 `486bce24 test(claudep): give the Koin graph tests DEX-legal method names` 重命名后，
该类才第一次**真正在设备上执行**。

### 20.2 R3.2：第三个「按接口请求、按具体类注册」缺陷

run 35610800900 在 managed device 上真实执行 `ClaudePKoinGraphTest`：
`tests=7 failures=3 errors=0 skipped=0`。3 条失败的共同根因是：

```
InstanceCreationException: Could not create instance for '[Singleton: ClaudePDevicePairingRepository]'
Caused by: NoDefinitionFoundException:
  No definition found for type 'kotlinx.coroutines.CoroutineScope' on scope '_root_'
```

`AppModule` 注册的是具体类 `single { AppScope() }`，而 `ClaudePDevicePairingRepository` 的
调用点按接口 `CoroutineScope` 取值。修复只改 DI 调用点一处：`scope = get()` → `scope = get<AppScope>()`。
**未**新增宽泛的 `single<CoroutineScope>`，**未**新增第二个 scope，**未**改生命周期 ——
依据是全仓生产 module 有 **9 处** `get<AppScope>()` 而**零处** `get<CoroutineScope>()`。

§19 已经指出：R3 的修复**必要但不充分**，`ChatVM` 当时仍会崩溃。R3.2 之后才补齐。

### 20.3 R4：两关串行验证（同一精确 SHA）

| 关 | Run | 结论 |
|---|---|---|
| 普通 CI（`Build Debug APK`） | [35613938145](https://github.com/maocomet/rikkahub-agent1/actions/runs/35613938145) | **success** |
| managed-device instrumentation | [35614890304](https://github.com/maocomet/rikkahub-agent1/actions/runs/35614890304) | **success** |

两 run 同为 `head_sha = 95624b6db94bd6c03f6b505096e161d35231020c`、同分支
`codex/claudep-cp1b-local`、均 `workflow_dispatch`、均 `run_attempt = 1`（**无 rerun**）。

`ClaudePKoinGraphTest`：**`tests=7 failures=0 errors=0 skipped=0`**，7 条逐条 PASS，
整份日志 `FAIL` 出现 0 次。逐项取证记录见 `CP1B-ci-evidence.md` §8.2。

### 20.4 真机覆盖安装复验（用户执行）

用户在实体手机上覆盖安装 debug APK 后实际操作：

| 检查项 | 结果 |
|---|---|
| 覆盖安装后原有数据保留 | 通过 |
| 目标聊天页首次进入 | 正常 |
| 返回后二次进入 | 正常 |
| 冷启动后再次进入 | 正常 |
| Claude P 设置页 | 正常 |
| 崩溃 / 白屏 / 错误页 | 均未出现 |

这一层补上了自动化无法覆盖的部分：CI 与 managed-device **都没有构造 `ChatVM`**，
真机才是「用户点进聊天页不崩」的第一个直接证据。取证等级与其局限见
`CP1B-ci-evidence.md` §8.3。

### 20.5 结论（按要求措辞）

- R3 `ChatVM` / Koin 启动崩溃返修：**完成**。
- Android 端 CP1-B 的**编译、测试、真实 Koin 图与启动真机复验**：**完成**。

### 20.6 明确不得声称

- **未**完成真实 Gateway 配对；
- **未**完成 Claude P 文本生成；
- **未**通过 Gate CP1；
- **未**验证 CP2 连续会话、CP3 工具、CP4 附件。

### 20.7 下一阶段

服务端（Gateway + Worker）的仓库归属、语言、权限边界、存储与协议兼容矩阵，
由 `claudep/08-cp1c-gateway-worker-adr.md` 决策；文件级任务与验收标准见
`claudep/reports/CP1C0-server-implementation-plan.md`。
**本轮不写服务端生产代码。**

### 20.8 R4 边界

- 本轮仅追加文档：未改动任何 `.kt` / `.kts` / `.yml`，未改依赖，未改协议、凭证或线格式。
- **未 push**；未触发任何 CI；未创建 PR / tag / Release；`master` 未触碰。
- 模型调用数 **0**；真实 Gateway 连接数 **0**。
