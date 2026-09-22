# CP1-C1 R2.2｜Android CI 终验证据

状态：**Android conformance 由 CI 真实执行并全绿。**
日期：2026-09-22
授权 HEAD：`63c650d71768af1a842ae699221a59958710ac36`
Server：`21e068b`（**本轮未修改、未 push**）
本轮模型调用数：**0**；Claude 进程：**0**；VPS 连接：**0**

---

## 1. 结论（三项必须分开）

| # | 结论 |
|---|---|
| 1 | **Android conformance 真正全绿。** 由 CI 执行、由 JUnit XML 直接证明：suite 唯一、tests=14、failures=0、errors=0、skipped=0 |
| 2 | **Server 仍未同步、未验证。** 其 vendored corpus 仍是 v1-r2 旧值，其 TypeScript 镜像仍带 `protocol_version` 可选假设——**Android 正确拒绝的 `server.hello`，Server 会接受**。本轮按指令禁止触碰 |
| 3 | **VPS、固定 Claude binary、代理出口、真实 Claude Worker、真实模型全部仍未验证**，属 C2/C3 |

---

## 2. Run 标识

| 项 | 值 |
|---|---|
| run ID | **`35694384947`** |
| URL | https://github.com/maocomet/rikkahub-agent1/actions/runs/35694384947 |
| `head_sha` | `63c650d71768af1a842ae699221a59958710ac36`（**与授权 HEAD 完整一致**） |
| `run_attempt` | **1** |
| event | `workflow_dispatch` |
| status / conclusion | completed / **success** |
| 起止 | 2026-09-22T06:20:24Z → 06:31:02Z（约 10.6 分钟） |
| 该 SHA 的 run 总数 | **1**（未 rerun） |
| push 是否触发 workflow | **否**（推送前后最新 run 均为 `35689623102`；该 SHA 的 run 数由 0 变为 1） |

---

## 3. 关键步骤（全部 OK）

```
[OK]  9 Build debug APK
[OK] 10 Verify .agenttest APKs use the fixed signing key
[OK] 11 Run regression unit tests
[OK] 12 Report executed regression test classes
[OK] 13 Run Claude P provider unit tests (CP1-A)
[OK] 14 Report executed Claude P test classes
[OK] 15 Verify the conformance corpus gate
[OK] 16 Upload unit test results
[skip] 17 Diagnose web-ui build (on failure)   ← if: failure()，成功时按设计跳过
[OK] 18 Upload debug APKs
```

> 第 17 步的 `skip` 是 `if: failure()` 在成功 run 上的正常行为，**不是测试跳过**。
> 测试层面的 skipped 见 §5，为 **0**。

---

## 4. Conformance 门禁输出（CI 直接输出，非转述）

第 14 步：

```
executed: me.rerere.ai.provider.claudep.ClaudePConformanceCorpusTest (14 tests, 0 skipped)
All 24 required Claude P test classes executed.
```

第 15 步：

```
README.md: OK
SPEC_REVISION.json: OK
expect/routing.json: OK
fingerprints/vectors.json: OK
frames/envelope.json: OK
transcripts/handshake.json: OK
transcripts/pairing.json: OK
Conformance corpus verified against specification revision v1-r3.
Conformance XML gate self-test: 8 cases behaved as expected.
ClaudePConformanceCorpusTest executed exactly 14 tests, none skipped and none failing.
```

**该门禁对 tests / failures / errors / skipped 四项全部实际检查**：
`failures:-0`、`errors:-0`、`skipped:-0`、`tests:-0` 四个判定在 log 中均存在且被执行
（R2.1 修掉的正是旧版本只查后两项的问题）。

---

## 5. JUnit XML 精确计数（artifact 直接解析）

artifact 名称：**`unit-test-results-35694384947-63c650d71768af1a842ae699221a59958710ac36`**
（24 073 bytes，未过期，id `10680211827`）。名称含 run id 与 commit，可唯一追溯，不含 Secret。
artifact 下载并解压后共 **24 个 XML**（`:ai` 21 + `:app` 3），与「All 24 required」一致。

目标 suite：

```xml
<testsuite name="me.rerere.ai.provider.claudep.ClaudePConformanceCorpusTest"
           tests="14" skipped="0" failures="0" errors="0"
           timestamp="2026-09-22T06:30:32.251Z" hostname="runnervmlun5p" time="0.09">
```

| 项 | 值 |
|---|---|
| suite 元素数量 | **1**（存在且唯一） |
| `tests` | **14** |
| `failures` | **0** |
| `errors` | **0** |
| `skipped` | **0** |
| `<failure>` / `<error>` / `<skipped>` 子元素 | **0 / 0 / 0** |
| `<testcase>` 元素 | **14**（逐一列出，非空壳） |

14 条实际执行的用例（XML 中逐一可见）：manifest 校验、握手转录、配对转录、字段序绑定、
指纹、absent-vs-empty、部分边界、帧路由、未知事件与主版本、键序、协议标识、**规范修订绑定**、
已知事件类型覆盖、星面字符计数。

全部上传的 24 个 suite 汇总：**tests=347, skipped=0, failures=0, errors=0**。

---

## 6. manifest 与 revision

| 项 | 值 |
|---|---|
| manifest 摘要 | `77fc4ff2077cdfcf7272bc5f5c47d59bbc8f95426ca4ceecee8d1d655cb3cedc` |
| 授权值 | 同上，**逐字符一致**（push 前本地核验） |
| revision | `v1-r3` |
| CI 校验方式 | 第 15 步 `sha256sum -c MANIFEST.sha256` 对**磁盘上的文件**逐条通过（7 个文件全部 OK） |

**关于摘要的来源**：manifest 的摘要不可能从 CI 日志里读到——CI 校验的是「manifest 与文件一致」，
不是「manifest 的哈希是多少」。该值来自 push 前对**同一 commit**（`63c650d7`）的本地核验；
git 内容寻址保证 CI 检出的正是该 blob，而第 15 步证明它与磁盘文件一致。

---

## 7. 上两次失败如何收敛

| run | SHA | 失败 | 根因 | 本轮如何收敛 |
|---|---|---|---|---|
| `35688185952` | `e7b5a4c3` | `:ai:compileDebugUnitTestKotlin` | `ClaudePConformanceCorpusTest` **16 处编译错误**：helper 声明在 `JsonObject` 上，却以 `Map<String, JsonObject>` 为接收者调用 | R2 泛化为 `Map<String, V>.mustGet`；本轮该步骤 OK |
| `35689623102` | `ddd6618c` | 2 条测试失败 | ① 路由：corpus 两条 `server.hello` 期望错误（`protocol_version` 无默认值）② 规范摘要：记录了 CRLF 工作区字节的哈希，LF 检出上不可能匹配 | R2.1 修正 corpus 两条期望并改为拒绝用例；摘要改为 canonical；本轮两者均 OK |

**中间还暴露了两处观测性缺陷，均已修复**：断言消息不含足够诊断信息（R2.1 补齐）；
JUnit XML 从未上传（R2.1 新增，本轮第 16 步 OK，XML 因而可用于取证）。

---

## 8. 尚未验证（不得提前宣称）

1. **Server 未同步、未验证。** Server 仓库 `21e068b` 的 vendored corpus 仍是 **v1-r2** 旧值，
   其 TypeScript `routeServerFrame` 仍把 `server.hello` 的**所有**字段当作可选——
   即 **Android 已正确拒绝的 `server.hello`，Server 侧会接受**。这需要一次单独的 Server 授权。
2. **VPS Node 运行时**：未连接、未核验。固定 Node 版本要求（`>=24`，因 `node:sqlite`）与目标机实际版本
   均**未验证**。
3. **固定 Claude binary**：未安装、未核验版本与 SHA-256。**Claude 进程数 = 0**。
4. **代理出口**：未配置、未验证。
5. **真实 Claude Worker**：CP1-C1 只有 fake worker；真实 Worker 属 **C2**。
6. **真实模型**：本轮模型调用 **0**；任何真实模型验收属 **C4**，须单独授权。

---

## 9. 本轮边界核查

| 项 | 状态 |
|---|---|
| 代码修改 | **0**（本轮未改任何源码/测试/语料） |
| Server 仓库 | 未修改、未 push，HEAD 仍 `21e068b`，远端仅 `origin/main`，run 数 **0** |
| Yanlan | 未改动，HEAD 仍 `5a221096` |
| 模型 / Claude 进程 / Anthropic 出站 | **0 / 0 / 0** |
| VPS / DNS / 代理 / systemd | **0** |
| PR / Tag / Release | **0** |
| rerun | **0** |
| 证据提交 | 仅本地，**未 push** |

---

## 10. 停止点

停在**原 Codex 复审点**。

下一步需要**单独授权**：Server 仓库的 v1-r3 corpus 同步与 `protocol_version` 严格性修正
（在没有它之前，两端对同一个 `server.hello` 会给出不同结论）。
