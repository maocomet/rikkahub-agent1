# M2-B — Android ToolRuntime adapter: implementation report

- Status: **INCOMPLETE — foundation only. NOT ready for review as M2-B.**
- Base: `e833dbd6` (verified ancestor of HEAD; the worktree was clean at that revision)
- Branch / HEAD: `codex/claudep-cp1b-local` @ `e73a4d54`
- Server contract of record: `rikkahub-claude-p-server` `4fbd76444ca2892fe08ac9945f64f8decc06a2fa`
- Working tree at time of writing: clean

## 0. Read this first

The M2-B completion conditions are **not met**. What follows is a verified foundation — the
byte contract, the catalog builder, the adapter's identity and lifecycle rules, and the tests
for all three — and an exact statement of what is missing.

The missing part is not incidental. **No Claude P tool frame is wired into the app.** The
protocol still has no `tool.*` events, `ClaudePProvider` still refuses `params.tools`, and
nothing calls `DefaultToolRuntime`, `ToolExecutionGate`, the approval UI or `McpManager` on
behalf of a bridge invocation. The three execution paths the gate requires — Local tool,
write-through-approval, MCP tool — are therefore **not implemented**, and the tests that would
prove they go through the existing runtime do not exist.

Everything claimed as verified below was verified. Everything not claimed was not.

## 1. What was built

### 1.1 Vendored conformance corpus

`claudep/conformance-bridge/vectors/` — `canonical.json`, `catalog.json`, `bindings.json`,
`lifecycle.json`, `MANIFEST.sha256`, `README.md`, copied byte-for-byte from the Server's
`test/bridge/vectors/` at the contract SHA. `sha256sum -c MANIFEST.sha256` passes over the
bytes on disk, and `.gitattributes` carries an `eol=lf` guard for the directory.

The Server's own README anticipates exactly this: the vectors are authored there, and M2-B
implements the Kotlin half against them. The corpus is **not** regenerated here, and a failing
manifest is never resolved by rewriting it.

### 1.2 The Kotlin half of the byte contract

`ai/src/main/java/me/rerere/ai/provider/claudep/bridge/`:

| File | What it is |
|---|---|
| `BridgeContract.kt` | Frozen vocabulary: ABI, server name, every bound, the nine-state tool-call vocabulary, and the split between Android's five reportable states and the Server's four own verdicts. |
| `BridgeCanonical.kt` | Canonical JSON and digests. Code-unit key order, `-0` refused, lone surrogate escaped, ECMAScript shortest-round-trip numbers. |
| `BridgeCatalog.kt` | Freezing: normalization, closed key sets, schema bounds, sorted-name order, `schemaDigest` / `catalogDigest`. |
| `BridgeBinding.kt` | Generation/invocation bindings, `invocationDigest`, and `invocationKey` — the tool call id and nothing else. |
| `BridgeArguments.kt` | Argument canonicalization and digest. |
| `BridgeLedger.kt` | The idempotency rules as pure functions (`BridgeRules`) plus the one-generation map. |
| `BridgeToolCatalog.kt` | Builds the catalog Claude P sends from the assistant's already-allowed tools. |
| `BridgeToolAdapter.kt` | The frame adapter: one instance per generation, with the invoke/cancel/settle/query rules. |

### 1.3 Tests

- `BridgeConformanceCorpusTest.kt` — 5 methods: manifest integrity (both directions), and the
  canonical / catalog / binding+arguments / lifecycle vector families.
- `ClaudePToolBridgeTest.kt` — 30 methods, each named for the M2-B requirement it comes from.

## 2. How it was verified, and the limits of that verification

There is no Android SDK on this machine and the task forbids triggering CI, so the app could
not be built at all. The pure modules were nonetheless compiled and executed:

- **Compiler**: `kotlin-compiler-embeddable-2.3.0.jar` from the local Gradle 9.4.1
  distribution, invoked as `K2JVMCompiler`.
- **Libraries**: the Gradle-bundled `kotlin-stdlib-2.3.0`, `kotlinx-serialization-*-jvm-1.9.0`.
- **Results**: 148 conformance checks and 52 scenario checks via standalone harnesses, then the
  35 real test methods compiled and executed → **0 failures**.

**Three caveats, stated because they bound what the above proves:**

1. **Version delta.** The project declares Kotlin `2.4.0` and `kotlinx-serialization-json`
   `1.11.0`; this was compiled against `2.3.0` / `1.9.0`. Only long-stable APIs are used, but
   the *project's* build has not compiled this code. That is CI's job.
2. **The JUnit run used a local stub.** No JUnit artifact is cached on this machine, so the
   test bodies were run through a minimal `org.junit` surface (`@Test`, `Assert.assertEquals/
   assertTrue/assertFalse/assertNull`) whose signatures mirror JUnit 4.13.2. The test bodies
   and their assertions genuinely executed; the *runner* is not the real one.
3. **Nothing Android was compiled.** Not one line of app-side code was built.

## 3. Requirement coverage

### 3.1 Implemented and verified

| # | Requirement | Where |
|---|---|---|
| 1 | Catalog stable ordering and stable digest | `BridgeToolCatalog`, `BridgeConformanceCorpusTest` catalog family, `catalog order and digest do not depend on input order` |
| 2 | Local and MCP name mapping | `local and mcp tools map to their bridged names and keep their source` |
| 3 | Refuse on any device/assistant/generation/toolCall mismatch | `an adapter serves exactly one generation`, `a call recorded under one generation is invisible to another`, bindings vectors |
| 4 | Unfrozen tool refused | `a tool that was never frozen is refused and runs nothing` |
| 5 | Same invoke replays exactly once | `a repeated invoke executes once` |
| 6 | Same toolCallId, different arguments → conflict, still one execution | `the same call id with different arguments conflicts and still executes once` |
| 7 | Query returns exactly the existing call | `a terminal result stays queryable...`, lifecycle `queries` corpus |
| 8 | Query never triggers execution | `a query never triggers an execution` |
| 9 | Terminal result repeatably queryable | `a terminal result stays queryable and repeated queries are identical` |
| 21 | Repeated/late terminal does not change a settled state | `a repeated terminal is not re-announced and a different one is dropped` |
| — | Cancel idempotent; honest reporting when a stop cannot be guaranteed | `cancel is idempotent and propagates at most once`, `a call that completed after its cancel is reported completed, not cancelled` |
| — | Deadline settles; late result dropped | `a deadline settles a call and a late result for it is dropped` |
| — | No re-dispatch after rebuild / reconnect | `a rebuilt adapter holds no record, so it re-runs nothing it cannot prove` |
| — | Android may not report the Server's own verdicts | `a state Android may not report is dropped rather than turned into a claim` |
| — | Fail-closed catalog: drop, never rename; empty, never "all tools" | `an empty catalog is the text path and never means all tools`, and the two drop tests |

### 3.2 **Not** implemented — the gap

| # | Requirement | Status |
|---|---|---|
| 10 | pending approval → approve → execute → completed | **Not implemented.** No approval path is wired. |
| 11 | pending approval → reject | **Not implemented.** |
| 12 | pending approval → cancel, and a later approve must not execute | **Not implemented.** |
| 13 | running cancel | **Not implemented.** The adapter models the request; nothing propagates it to a running tool. |
| 14 | disconnect does not re-dispatch | Rules verified in the pure adapter; **the transport path does not exist**, so the end-to-end claim is unproven. |
| 15 | reconnect does not re-dispatch | Same. |
| 16 | Local read-only tool end to end | **Not implemented.** |
| 17 | Local write tool through approval, end to end | **Not implemented.** |
| 18 | Connected MCP tool end to end (fake MCP, no real OAuth) | **Not implemented.** |
| 19 | MCP credentials/tokens never enter a frame, log or snapshot | **Not testable yet** — no frame carries a tool call. (No credential is read or written by any code added here.) |
| 20 | Error bodies and local paths not leaked | Partially: the pure modules never log or persist a body, and result bodies are size-bounded and opaque. No test asserts it end to end. |
| 22 | The old text-only Claude P chat path unchanged | **Unchanged** — no existing file was modified except `.gitattributes`. Nothing was wired, so nothing could regress; equally, nothing was proven. |
| 23 | cancel and ordinary stream regressions still pass | Not run (no CI). |

Additionally absent:

- `tool.*` event types in `ClaudePProtocol` / `ClaudePDto` / `ClaudePServerEvent`, and
  `ClaudePInboundRouter` routing for them. **`tool.invoke` frames are not decoded today.**
- `ClaudePProvider` sending `tool_snapshot` and accepting `params.tools`.
- The app-side adapter that would supply `BridgeToolCandidate`s from the real assistant tool
  set (LocalTools + `McpManager.getAvailableToolsForAssistant`) and execute through
  `DefaultToolRuntime` with `ToolExecutionGate` as `preExecutionGate`.
- Any test proving the adapter reaches the real runtime, gate or `McpManager`.
- Instrumentation tests, and therefore any edit to the CI workflow's explicit execution gate.

## 4. Design decisions worth reviewing

1. **The adapter is constructed with the generation it serves.** The ledger is its own and dies
   with it, so "find the first call matching this id across generations" — the search M2-B
   forbids — cannot be *written* rather than merely being avoided. A frame for another
   generation is refused, not ignored.
2. **`readOnly` is supplied by the caller and never inferred here.** The safe default is
   `false`: asserting a write tool is read-only is the one direction of error that
   misrepresents what a call may do. The caller must derive it from the existing policy
   resolver — which is part of the unwired half.
3. **Two different fail-closed rules, deliberately.** A tool that cannot be frozen is *dropped*
   and recorded; a catalog that cannot be built is *empty* (the text path). Degrading a failed
   catalog to a wider set would hand Claude tools the user did not authorize.
4. **A cancel request is not a cancellation.** The adapter propagates at most one cancel per
   call and reports what happened. A tool that completed after its cancel is reported
   `completed`, because reporting `cancelled` would claim a stop that did not occur.
5. **`conflict` cannot be reported by Android**, so a local conflict is refused with
   `INVOCATION_BINDING_MISMATCH` rather than answered with a state the contract reserves for
   the Server's own ledger verdicts.

## 5. Boundary compliance

- No Server file was modified; the Server repository was read only, via `git show` at the
  contract SHA.
- No connection to the VPS, no model call, no tool side effect, no real MCP OAuth/token read.
- No new dependency: nothing was added to any `build.gradle.kts` or the version catalog.
  (The compiler and jars used for local verification came from the already-installed Gradle
  distribution and are not part of the project.)
- No push, no CI dispatch, no PR, no tag, no release, no deploy.
- No M3 work: no session mapping, no config hash, no cache design.
- `git diff --check` passes; the working tree is clean.

## 6. Commits

```
e73a4d54 test(claudep): cover tool bridge identity and lifecycle
57f2b1c2 feat(claudep): freeze the Android tool catalog and adapt bridge calls
5b162d39 feat(claudep): implement the Kotlin half of the M2 bridge contract
```

## 7. What remains

1. `tool.*` frames: event types, DTOs, `ClaudePServerEvent` variants, `KNOWN_SERVER_EVENT_TYPES`,
   and `ClaudePInboundRouter` routing — including promoting `tool.*` out of the "unknown
   optional" path.
2. `ClaudePProvider`: send `tool_snapshot`, stop rejecting `params.tools`, surface
   `UIMessagePart.Tool`.
3. The app-side executor: build `BridgeToolCandidate`s from the existing tool assembly and run
   admitted invocations through `DefaultToolRuntime` with `ToolExecutionGate` and the existing
   approval UI, and through `McpManager` for MCP tools.
4. The wiring tests the M2-B gate names — 10–20 and 22–23 above — plus instrumentation tests,
   added to the workflow's explicit execution gate rather than assumed to run.
5. CI proof that the project's own toolchain (Kotlin 2.4.0, serialization 1.11.0) compiles all
   of the above.

## 8. Requested verdict

This is **not** `M2-B complete`. It is a verified foundation and an accurate inventory of the
gap. If the foundation is accepted, the remaining work is items 1–5 above; if the approach in
§4 is wrong, it is cheaper to say so now, before the app-side wiring is built on it.
