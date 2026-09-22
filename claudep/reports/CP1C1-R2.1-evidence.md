# CP1-C1 R2.1｜本地取证与返修报告

状态：**仅本地。未 push、未触发 CI、未修改 Server。**
日期：2026-09-22
基线：Android `ddd6618c`（授权 HEAD），Server `21e068b`（**本轮未触碰**）
本轮模型调用数：**0**；Claude 进程：**0**

---

## 1. 结论摘要

| 项 | 结果 |
|---|---|
| 分歧 frame | **`server-hello-empty-body`**（首个），以及同一原因的 **`server-hello-absent-body`** |
| 哪一方有错 | **corpus 错**。Kotlin 实现正确，规范未被违反 |
| 证据 | 运行**生产 decoder**（非重写实现）得到 `MissingFieldException: Field 'protocol_version' is required` |
| vectors bytes 是否变化 | transcripts 与 fingerprints **逐字节未变**；frames/expect **已变**（就是这次修正） |
| 新 manifest | `77fc4ff2077cdfcf7272bc5f5c47d59bbc8f95426ca4ceecee8d1d655cb3cedc` |
| revision | **`v1-r3`** |
| 本轮 Android 测试 | **未运行**（按指令） |

---

## 2. 分歧定位（第一部分）

### 2.1 上一轮 CI 的证据限制

失败 run `35689623102`（SHA `ddd6618c`，attempt 1，event `workflow_dispatch`）只给出：

```
ClaudePConformanceCorpusTest > every frame routes to its expected outcome FAILED
    java.lang.AssertionError at ClaudePConformanceCorpusTest.kt:350
```

**无法据以定位**，原因是可复现的：

- Gradle 测试日志项目级关闭 → 控制台只有异常类型 + 行号，**没有断言消息**；
- 该 run 的 **artifacts = 0** → 唯一含断言消息的 JUnit XML **没有上传**；
- check-run annotations 中也没有测试消息。

因此本轮先补可观性（§4），再用**可执行证据**定位（§2.3），而不是再猜一次。

### 2.2 源码路径追踪（7 个 event 向量）

行号基于 `ddd6618c` 的生产源码。公共路径：`ClaudePProtocol.parseInbound`
（`ClaudePProtocol.kt:112`）→ envelope 解码（`:113`，`ignoreUnknownKeys` 见 `:42`）
→ `protocol` trim/空判定（`:120-123`）→ `majorVersionOf`（`:124-128`）
→ `type` trim/空判定（`:130-132`）→ `KNOWN_SERVER_EVENT_TYPES`（`:90,134`）
→ `fromEnvelope`（`ClaudePDto.kt:434`）。

| frame ID | wire（body 部分） | corpus expected | Kotlin 路径 | 一致？ | 证据 |
|---|---|---|---|---|---|
| `valid-server-hello` | `{"protocol_version":"v1","gateway_build":"c1","max_frame_bytes":262144}` | `event:server.hello` | `:437` → `ClaudePServerHelloBody`（`ClaudePDto.kt:30`） | ✅ | probe |
| `valid-server-hello-empty-body` | `{}` | `event:server.hello` | `:437`，**`protocol_version` 无默认值**（`ClaudePDto.kt:31`）→ 抛错 → `:141` MALFORMED_EVENT_BODY | ❌ **分歧** | probe |
| `valid-server-hello-absent-body` | （无 body） | `event:server.hello` | envelope 用 `body` 默认值（`ClaudePProtocol.kt:418`）→ 同上，`protocol_version` 仍缺失 | ❌ **分歧** | probe |
| `valid-text-delta` | `{"message_id":"m1","index":0,"text":"hello"}` | `event:text.delta` | `:467` → `ClaudePTextDeltaBody`（`:193`，全字段有默认） | ✅ | probe |
| `valid-generation-failed` | `{"error_code":"worker_busy","message":"ignored on purpose"}` | `event:generation.failed` | `:487` → `ClaudePFailedBody`（`:251`；未知键被忽略） | ✅ | probe |
| `order-insensitive-fields-reordered` | 同上 hello + `unknown_extra`，键序打乱 | `event:server.hello` | 键序不影响解码；含 `protocol_version` | ✅ | probe |
| `order-sensitive-envelope-protocol-first` | 同上 hello，规范键序 | `event:server.hello` | 同上 | ✅ | probe |

**只有两条不一致，同一个原因。**

### 2.3 用生产实现定位（不靠再跑 CI）

`claudep/conformance/tools/kotlin-probe/` 编译**仓库里的** `ClaudePProtocol.kt` 与
`ClaudePDto.kt`（**逐字节未改**，仅行尾不同，已核对）并调用**真实** `parseInbound`。

首次运行（`ddd6618c` 的 corpus）：

```
frames=23 routed_as_event=5 mismatches=2

MISMATCH  valid-server-hello-empty-body    expected=event:server.hello    actual=rejected:MALFORMED_EVENT_BODY
MISMATCH  valid-server-hello-absent-body   expected=event:server.hello    actual=rejected:MALFORMED_EVENT_BODY
```

进一步最小复现：

```
serializer present: me.rerere.ai.provider.claudep.ClaudePServerHelloBody
decode {} with the production Json instance
  THREW kotlinx.serialization.MissingFieldException:
        Field 'protocol_version' is required for type ... 'ClaudePServerHelloBody', but it was missing
decode a NON-empty body with the production Json instance
  decoded: ClaudePServerHelloBody(protocolVersion=v1, gatewayBuild=, ...)
```

### 2.4 判决：corpus 错，Kotlin 对

`ClaudePDto.kt:31`：

```kotlin
@SerialName("protocol_version") val protocolVersion: String,   // ← 无默认值
```

其余每个字段都有默认值，**只有它没有**。corpus 的这两条期望来自一个
「把每个声明字段都当作可选」的校验思路——**该思路同时被复制进了 Server 仓库的
TypeScript 镜像**，所以两端互相一致、却都与实现不一致。

**Kotlin 拒绝它们是正确的**：`claudep/02` §3 把 `server.hello` 定义为「冻结所选协议版本」
的帧；不携带版本的 handshake 无法履行该职责，客户端也不应把它读作成功。

因此按规则处理：**corpus 与规范不一致 → 修 corpus**，并保留为拒绝用例
（而非换成合法帧——那会删掉这条规则的覆盖）。

### 2.5 明确没有做的事

未放宽断言、未跳过任何 frame、未把未知输入当 event、未改 expected 去迎合实现。
被改的只有那两条**本身就是错的**期望及其描述文字。

---

## 3. 规范摘要的跨平台行尾缺陷（第二部分）

### 3.1 根因

r2 的 `protocol_spec_sha256` 哈希的是**工作区原始字节**，于是值取决于运行生成器的机器：

| | sha256 |
|---|---|
| git blob（LF，CI 检出的形式） | `91250e70245753c9f1927150dc0202f9a7ae949aec35bac21a32775b8779b817` |
| 工作区（CRLF，`core.autocrlf=true`） | `bd83e4970da9a3e0b48dd7734ea5cf8f016204171cba5b5e19d680cff2f98151` |
| corpus 中记录的 | `bd83e497…` ← CRLF 形式 |

**任何 LF 检出上都不可能通过。** 语料文件本身没受影响只是运气——它们是生成器自己写的 LF。

### 3.2 新规则（规范正文 §12.11）

1. 按 **UTF-8 严格解码**，非法 UTF-8 **拒绝**（不得用 U+FFFD 替换）；
2. `CRLF` → `LF`；
3. **裸 `CR` 拒绝**；
4. **除此之外不做任何变换**（不 trim、不重排、不做 Unicode 规范化）；
5. 对规范化后的 UTF-8 字节计算 SHA-256。

实现：`tools/canonical-hash.mjs`；写进 `claudep/02` §12.11 与
`claudep/conformance/README.md`、`tools/README.md`（**不藏在脚本里**）。

### 3.3 验证

| 断言 | 结果 |
|---|---|
| LF 形式 / CRLF 形式 / 本检出 三者 canonical 摘要相同 | ✅ 均 `8ff5e6a5…`（`02`）、`a2534659…`（`01`） |
| 该值等于 git blob（LF）的原始 sha256 | ✅ `a2534659…` 正是 LF blob 的原始哈希 |
| LF 与 CRLF 输入产生相同摘要 | ✅ |
| 裸 CR 被拒绝（含行尾裸 CR） | ✅ |
| 非法 UTF-8 被拒绝 | ✅ |
| 正文真实变化改变摘要 | ✅ |
| 增/删末尾换行改变摘要 | ✅（末尾换行是内容） |
| 行内空白不被规范化 | ✅ |
| 生成两次逐字节一致 | ✅ |

`tools/selftest.mjs`：**11 条全部通过**（输入全部在内存构造，不读文件——读文件的测试会继承
它本要消除的检出依赖性）。

### 3.4 `.gitattributes`

新增 `claudep/conformance/** text eol=lf` 等三条。**它是工作树防护，不是正确性来源**：
即使没有它，canonical 摘要依然正确；它保护的是 **`MANIFEST.sha256`**——那个摘要按设计
保持**原始字节**（`sha256sum -c` 比的就是磁盘上的字节），其跨平台稳定性只能靠
`.gitattributes`。

### 3.5 revision 与不变量

revision → **`v1-r3`**。**wire framing 与 vectors bytes 未变**——见 §6 的逐文件对比：
transcripts 与 fingerprints 逐字节不变。

---

## 4. CI 可观测性与门禁（第三部分）

### 4.1 上传 JUnit XML

新增 `Upload unit test results`，`if: always()`，artifact 名含 `run_id` 与 `sha`（可唯一
追溯，不含 Secret）。**上一轮就是因为没有它而学不到任何东西。**

### 4.2 修掉弱门禁

旧门禁只读 `tests` 与 `skipped`，**不读 `failures` / `errors`**——于是在 2 个测试失败的
情况下仍打印「executed exactly 14 tests, none skipped」。整体构建仍然失败（per-class 步骤
查 failures），但**该步骤自身的声明强于它实际验证的内容**，这比缺少检查更糟，因为它读起来
像一个通过的检查。

新门禁检查：文件存在、**恰好一个 `<testsuite>` 元素**、`failures=0`、`errors=0`、
`skipped=0`、`tests=14`；并把 revision 钉为 `v1-r3`。

### 4.3 合成 fixture 自测

门禁先把判定函数对**合成的** suite 跑一遍，**8 条**用例：14/0/0/0 接受；
tests=0、skipped=1、count 不符、**failures=1**、**errors=1**、文件缺失、多个 suite 元素
全部拒绝。后两者正是旧版本会**静默接受**的情况。

### 4.4 本地实测（本轮未运行 CI）

从 workflow 中抽出该步骤实际执行：

```
Conformance corpus verified against specification revision v1-r3.
Conformance XML gate self-test: 8 cases behaved as expected.
no JUnit XML at ai/build/... (预期：本地没有测试产物)
exit=1
```

放入一个合成的合法 suite 后再跑：`exit=0`，输出
`executed exactly 14 tests, none skipped and none failing.`

YAML 可解析（17 steps）、无控制字节、`sed` 反向引用完好。

---

## 5. 本轮本地真正执行了什么

| 检查 | 结果 |
|---|---|
| `tools/selftest.mjs`（canonical hash） | **11/11 通过** |
| **生产 Kotlin decoder probe**（`kotlin-probe`） | **23 帧，mismatches=0**，exit 0 |
| 生成器确定性（两次生成逐字节比对） | IDENTICAL |
| corpus manifest 校验 | 7 个文件全部 OK |
| revision 校验 | `v1-r3` |
| CI 门禁自测（含 failures/errors 用例） | 8/8 |
| `git diff --check` | clean |
| 控制字节扫描 | clean |

**生产 decoder probe 是真的执行了生产实现**：`ClaudePProtocol.kt` 与 `ClaudePDto.kt`
逐字节取自仓库（与 git blob 比对仅行尾不同），配以 Kotlin 2.4.0 与
kotlinx-serialization 1.11.0，**不是**重写版 decoder。

---

## 6. vectors bytes 是否变化

| 文件 | 相对 `ddd6618c` |
|---|---|
| `transcripts/handshake.json` | **未变** `cdce2dfd…` |
| `transcripts/pairing.json` | **未变** `7bc6f924…` |
| `fingerprints/vectors.json` | **未变** `eec4db9b…` |
| `frames/envelope.json` | **已变** → `53ed9769…`（两条期望修正） |
| `expect/routing.json` | **已变** → `015816f6…`（同上） |
| `MANIFEST.sha256` | **已变** → `77fc4ff2…` |

帧总数仍为 **23**；kind 分布由 `rejected:13 / ignored:3 / event:7`
变为 **`rejected:15 / ignored:3 / event:5`**——两条从 event 改为 rejected，**没有删除任何用例**。

---

## 7. 尚未验证（不得提前宣称）

1. **Android conformance 本轮未运行。** 14 条测试是否全绿**没有本轮证据**。上一次真实执行
   （`35689623102`）是 **14 执行 / 2 失败 / 0 skipped**，本轮修正的正是那 2 条失败中的一条的
   根因（另一条是行尾摘要）。
2. **Server 未修改、未 push、CI run 数仍为 0。** Server 仓库的 TypeScript 镜像**仍有同一个
   `protocol_version` 假设错误**，且其 vendored corpus 仍是旧值——**下一轮（Android CI 全绿后）
   必须同步**，本轮按指令禁止触碰。
3. **VPS Node 版本、固定 Claude binary、代理出口、真实 Worker、真实模型**——**全部仍未验证**，
   属 C2/C3。本轮未连接任何 VPS、未调用任何模型。

---

## 8. 提交清单

| # | commit | 内容 |
|---|---|---|
| 1 | `eebdcbb6` | 让 routing 失败自我描述（frame id / expected / actual / raw） |
| 2 | `514d0276` | canonical 规范摘要、`v1-r3`、corpus 再生成、`.gitattributes` |
| 3 | `66b11b2a` | CI 检查 failures/errors、上传 JUnit XML、门禁自测扩到 8 条 |
| 4 | `3dc7074a` | 修正两条 `server.hello` 期望 + 新增 `kotlin-probe` |
| 5 | 本文件 | 证据文档 |

**下一步需要单独授权**：新的 Android CI 运行。本轮不自行触发。
