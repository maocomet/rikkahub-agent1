# M2-B — Android ToolRuntime adapter: implementation report

- Status: **INCOMPLETE. NOT ready for review as M2-B.**
- Base: `e833dbd6` (verified ancestor of HEAD)
- Branch / HEAD: `codex/claudep-cp1b-local` @ `87e6a994`
- Server contract of record: `rikkahub-claude-p-server` `4fbd76444ca2892fe08ac9945f64f8decc06a2fa`
- Working tree at time of writing: clean

## 0. Read this first

Two things changed since the previous report, and one did not.

**Changed.** The registry's close now proves what it reports (§1.6), and the provider has the
seam a tool call is answered through (§1.7). The second of those also repairs a build break the
first report shipped: commit `f02417b1` extended the sealed `ClaudePServerEvent` with three tool
variants and left the terminal gate's exhaustive `when` without branches for them, so
`ClaudePProvider.kt` did not compile. That is now fixed, and the fix is the routing itself
rather than three `else` branches.

**Not changed.** The three real Android execution paths the gate requires — Local tool,
write-through-approval, MCP tool — are **still not implemented**. The seam they will be
implemented behind now exists and is inert: its default host returns an empty catalog, which
means no `tool_snapshot` is sent, which means the Server registers no bridge tool and the
execution method is unreachable rather than merely unused.

The single most important finding is still **§3**: the provider catalog (B) **cannot** be
shipped before the runtime wiring (C). That is a finding, not an excuse, and it reorders the
remaining work.

## 1. What is implemented and verified

### 1.1 Vendored conformance corpus (unchanged from the previous report)

`claudep/conformance-bridge/vectors/` — byte-for-byte from the Server's `test/bridge/vectors/`,
`sha256sum -c MANIFEST.sha256` passing, `.gitattributes` `eol=lf` guard.

### 1.2 The Kotlin half of the byte contract

`ai/src/main/java/me/rerere/ai/provider/claudep/bridge/` — `BridgeContract`, `BridgeCanonical`,
`BridgeCatalog`, `BridgeBinding`, `BridgeArguments`, `BridgeLedger`, `BridgeToolCatalog`,
`BridgeToolAdapter`, `BridgeGenerationRegistry`.

### 1.3 Codex ruling 1 — generation lifecycle (commit `097ec9ca`)

`BridgeGenerationRegistry` keys adapters by the exact generation id and looks them up by nothing
else, so the forbidden cross-generation search has no expression. `open` is idempotent: a
reconnect returns the adapter that is already there, ledger intact — a fresh one would hold an
empty ledger and a re-delivered invoke would execute a second time. The capacity bound refuses a
new generation rather than evicting a live one, because an eviction discards a ledger and can
therefore cause a second execution. `close` concludes outstanding calls *before* dropping the
adapter.

### 1.4 Codex ruling 2 — `readOnly` cannot be forged (commit `097ec9ca`)

`BridgeToolCandidate` has a private constructor and no boolean parameter. Two factories name the
claim: `tool` (the default, effectful) and `provenReadOnly`, which refuses an MCP source because
Android cannot inspect a remote implementation to hold that proof. The ruling asks MCP tools to
*default* to false; refusing outright is stricter on purpose.

A reflective test asserts the shape, and it earned its place immediately: Kotlin emits a public
**synthetic** constructor carrying a `DefaultConstructorMarker` for the companion, so the
assertion had to be written against what the compiler actually emits rather than what
`private constructor` reads like.

### 1.5 `tool.*` frames and routing (commit `f02417b1`)

`tool.invoke`, `tool.cancel`, `tool.query.result` now have bodies, `ClaudePServerEvent` variants,
and membership in `KNOWN_SERVER_EVENT_TYPES`. They were previously left on the unknown-optional
path, which exists for forward compatibility — a tool invocation is not that, it is a request the
app is expected to answer.

The generation comes from the envelope and nowhere else. A tool frame whose envelope has no
generation is a named violation (`MISSING_TOOL_BINDING`) rather than a silent drop.

`ClaudePToolCallState` is the wire spelling and `ToolCallState` is the contract value; they are
deliberately two types. `ClaudePToolFrames` holds the rules for frames Android **sends** — a body
is legal only on `completed`, only the five reportable states are sendable, and an unreportable
outcome produces no frame at all.

### 1.6 The close proves what it reports (commit `e1f35115`)

The reordering is now real rather than cosmetic. `BridgeGenerationRegistry.close` stops admitting
invokes; closes pending approvals *before* anything is stopped, so an `approve` tap cannot race
the stop; asks the runtime to stop through its own cancellation capability; waits, bounded, for
real conclusions; settles each call by what was proven; and only then releases the adapter.

Two things changed in substance rather than in order:

- **`cancelled` now means one thing.** A stop was requested *and the runtime proved the call is
  over*. No handle, a lost handle, an interrupted lifecycle and an elapsed wait all report
  `failed` with a stable local reason instead. The old code recorded `cancelled` for everything
  pending on the reasoning that the turn had ended, which would have described a write that was
  still running as one that did not happen.
- **A closed generation is tombstoned.** Dropping the adapter was not enough: the id is
  peer-supplied, and a *fresh* adapter would hold an empty ledger, so a re-delivered call would
  look new and run again. The tombstone lives for `MAX_DEADLINE_MS`, which is exactly the window
  in which a re-delivery is possible at all — the Server abandons a call at that call's deadline.

The reconnect identity also gained `catalogDigest`. The catalog participates in every
invocation's binding digest, so a reconnect presenting a different one was asking to serve calls
under rules they were not admitted under; that is now a fail-closed refusal. `requestId` and
`timeoutMs` stay out of the comparison, and a reconnect still cannot extend a deadline.

### 1.7 The provider's seam (commit `87e6a994`)

`ClaudePGatewayClient` gains `sendToolResult` and `queryToolCall` — the two frames Android sends
back — implemented for the WSS transport, the fake, the resolving delegate and the fail-closed
singleton. Both are fire-and-forget: `tool.result` has no reply, and `tool.query`'s answer
arrives as a `tool.query.result` *event on the generation's stream*, so that a reconnect replaying
frames cannot deliver an answer that skipped the replay.

`ClaudePToolBridgeHost` is the whole surface between the bridge and the app: `prepare`,
`execute`, and the `BridgeExecutionHost` the close asks for real stops. Its default returns an
empty catalog, so the seam is inert by construction rather than by a flag — no snapshot, no
registered tool, and `execute` unreachable. With no host and no tools the bytes of
`generation.start` are unchanged, and a caller that declares tools is still refused rather than
silently dropped.

Where the provider does wire, the order is the one the lifecycle requires: catalog frozen before
dispatch (it has to travel in `generation.start`); ledger opened before the first frame is
pumped (so an invoke in the first batch already has records); close in a `finally` (so a
terminal, a cancellation and a disconnect all settle). A `tool.cancel` reaches the runtime's real
cancellation capability and answers nothing — a cancel is not a conclusion.

### 1.8 What the harness now type-checks

The local harness was extended to compile the whole `ai` main source tree, excluding only files
that genuinely need the Android SDK, okhttp or the serialization plugin (with small stubs for
`kotlinx-datetime`'s three used symbols and Compose's `@Composable`). Two compile errors survive
that are artifacts of the exclusions, not of the code: `ToolResultReplayPlan.kt` (needs
`android.util.Base64` via the excluded `FileEncoder.kt`) and `ProviderRequestContextPolicy.kt`
(needs the serialization plugin). **Everything else in `ai/src/main` compiles**, including the
provider, the gateway surface and the whole `bridge` package.

## 2. How it was verified, and the limits

Same local harness as before: the Kotlin compiler bundled in the local Gradle distribution, the
Gradle-bundled stdlib and serialization libraries, no Android SDK, no CI.

**65 test methods pass, 0 failures**, across four suites. All files, including the decoding suite
that cannot run locally, compile.

Four caveats, stated because they bound what that proves:

1. **Version delta.** The project declares Kotlin `2.4.0` / `kotlinx-serialization-json` `1.11.0`;
   this was compiled against `2.3.0` / `1.9.0`.
2. **The JUnit run uses a local stub** of `org.junit` (`@Test`, the `Assert` methods). The test
   bodies and assertions genuinely execute; the runner is not the real one.
3. **The serialization compiler plugin is not available here**, so any test that decodes an
   `@Serializable` DTO cannot be *executed* locally — only compiled. `ClaudePToolFrameTest`
   (decoding and routing, 11 methods) is in that category: **written and compiling, not
   executed.** `ClaudePToolWireRulesTest` was split out precisely so the outbound rules — the
   security-relevant half — do run anywhere, including where the plugin is absent.
4. **No Android code was compiled at all.**

## 3. The ordering finding: B cannot precede C

I implemented the provider catalog (B) — building the frozen catalog from `params.tools`, sending
`tool_snapshot`, binding it into the fingerprint as canonical JSON — and then **reverted it**,
because wiring it now is a regression.

The reason is concrete. Sending a `tool_snapshot` makes the Server create a bridge and register
those tools with Claude Code. Claude will then call them. With no executor on Android, each
invocation reaches the device and is never answered, and the Server's `handleToolInvoke` waits on
`admission.wait` until the call's deadline — **30 minutes** (`MAX_DEADLINE_MS`) — before it times
out. Today, with no snapshot, Claude Code has no bridge tools and answers in text, which works.

So B is not "safe to land early". B is safe to land **only together with C**, or behind a seam
that is absent until C provides it. I chose to revert rather than ship either a regression or an
inert flag, and to report it instead.

**Revised ordering for the remaining work:** C's executor seam first, then B's wiring in the same
change.

## 4. Requirement coverage

### 4.1 Implemented and verified

| # | Requirement | Where |
|---|---|---|
| 1 | Catalog stable ordering and stable digest | `BridgeToolCatalog`, corpus catalog family |
| 2 | Local and MCP name mapping | `local and mcp tools map to their bridged names and keep their source` |
| 3 | Refuse on any device/assistant/generation/toolCall mismatch | adapter + registry suites, bindings vectors |
| 4 | Unfrozen tool refused | `a tool that was never frozen is refused and runs nothing` |
| 5 | Same invoke replays exactly once | `a repeated invoke executes once` |
| 6 | Same toolCallId, different arguments → conflict | `the same call id with different arguments conflicts and still executes once` |
| 7, 8, 9 | Query exact, inert, repeatable | `query` suite + lifecycle corpus |
| 13 | Cancel idempotent; honest when a stop cannot be guaranteed | `cancel is idempotent and propagates at most once`, `a call that completed after its cancel is reported completed, not cancelled` |
| — | Close: no `cancelled` without a proven stop | `a call that cannot be proven stopped is reported failed, never cancelled`, `a stop the runtime proves is reported as cancelled`, `a tool that completed while the generation ended is reported completed`, `a call the user refused is reported denied`, `a host that reports a state Android may not send is ignored and the call is failed` |
| — | Close order: approvals, then stop, then wait, then settle, then release | `closing abandons approvals, then requests a stop, then waits`, `a call whose cancel was already propagated is not stopped twice`, `closing one generation stops only that generation's calls` |
| — | During the close the generation is found and refuses new work | `during the close the generation is found, refuses new invokes, and admits no second adapter` |
| — | A closed generation id cannot be reopened | `a closed generation cannot be reopened and a late invoke runs nothing`, `a closed generation id is forgotten once no call of it can still be re-delivered` |
| — | Reconnect identity includes the catalog; a reconnect cannot extend a deadline | `a reconnect presenting a different catalog is refused and the original is not replaced`, `the bridge abi is part of the reconnect identity`, `a reconnect does not extend a call's deadline` |
| 14, 15 | No re-dispatch after rebuild / reconnect | registry suite |
| 21 | Late/repeated terminal never changes a settled state | `a repeated terminal is not re-announced and a different one is dropped` |
| — | `readOnly` unforgeable; MCP never claimed read-only | ruling-2 suite |
| — | Generation lifecycle: keyed, idempotent open, no eviction, conclude-then-release | registry suite |
| — | Tool frames decode, route, and fail closed | `ClaudePToolFrameTest` (compiled; not executed locally) |
| — | Outbound frames: reportable states only, body only on completed, size bound | `ClaudePToolWireRulesTest` (executed) |

### 4.2 **Not** implemented

| # | Requirement | Status |
|---|---|---|
| 10 | pending approval → approve → execute → completed | **Not implemented.** |
| 11 | pending approval → reject | **Not implemented.** |
| 12 | pending approval → cancel, later approve executes zero times | **Not implemented.** |
| 16 | Local read-only tool end to end | **Not implemented.** |
| 17 | Local write tool through approval, end to end | **Not implemented.** |
| 18 | Connected MCP tool end to end via the real `McpManager` | **Not implemented.** |
| 19 | MCP credentials never enter a frame, log or snapshot | **Not testable yet** — no frame carries a tool call. |
| 22 | Old text-only chat path unchanged | **Unchanged, and now structural.** No bridge host means an empty catalog means `toolSnapshot = null` — byte-for-byte the frame the provider sent before the bridge existed. A caller that declares tools with no host behind them is still refused rather than silently dropped. Not yet *proven* by a running test (see §2). |
| 23 | cancel and stream regressions still pass | Not run (no CI). The provider now compiles, which it did not before. |

Also absent: the app-side `ClaudePToolBridgeHost`; the approval path; the execution host over
`GenerationRunControl`; DI wiring for any of it; the wiring tests; instrumentation tests and
their explicit class-name gate in the workflow. `tool_snapshot` is still never sent, because the
only host in the tree returns an empty catalog.

## 5. Boundary compliance

- No Server file modified (read only, via `git show` at the contract SHA).
- No VPS connection, no model call, no tool side effect, no real MCP OAuth/token read.
- No new runtime dependency; nothing added to any build file or the version catalog.
- No push, CI dispatch, PR, tag, release or deploy. No M3 work.
- `git diff --check` passes; working tree clean; `e833dbd6` still an ancestor.

## 6. Commits

```
87e6a994 feat(claudep): give the provider a seam it can answer tool calls through
e1f35115 fix(claudep): make a generation's close prove what it reports
f02417b1 feat(claudep): add the tool.* frames and their routing rules
097ec9ca feat(claudep): make generation lifecycle structural and readOnly unforgeable
28f502ed docs(claudep): record M2-B implementation evidence and its gap
e73a4d54 test(claudep): cover tool bridge identity and lifecycle
57f2b1c2 feat(claudep): freeze the Android tool catalog and adapt bridge calls
5b162d39 feat(claudep): implement the Kotlin half of the M2 bridge contract
e833dbd6 (base)
```

## 7. What remains

The seam exists. What is behind it does not.

1. **The app-side `ClaudePToolBridgeHost`** (`app/`), returning `BridgeToolPreparation` and
   running admitted invocations. It must build candidates from the current assistant's real
   allowed tool set (`ChatService`'s `localToolDefinitions` + `mcpManager`), claim `readOnly`
   only where the assembly layer can prove it, re-assess on invoke with
   `DefaultToolRuntime.assess()` against the **real** arguments, and route MCP tools through the
   existing `McpManager`. Nothing here may copy a runtime, an approval state or an MCP
   credential.
2. **The approval path**, which is the hard half and the reason this was not rushed. A Claude P
   tool call arrives *inside* a live stream with the Server blocked on it, so the app cannot use
   the GenerationHandler loop's "break the turn, wait for a tap, resume" shape — the host's
   `execute` must surface the call as a `UIMessagePart.Tool` in `Pending` state, let
   `SecondUserApprovalLifecycle` and the existing UI resolve it, and suspend until they do, with
   no second model generation. A denial must produce `denied`; a cancel while pending must
   produce zero executions.
3. **The execution host** behind `BridgeExecutionHost`, over `GenerationRunControl`'s real
   `ToolExecutionHandle` (`requestCancel` + `awaitTermination`) so a close can prove a stop
   rather than assume one.
4. **Wiring tests** for requirements 10–20 and 22–23 — Local read-only, Local write through
   approval, fake MCP, invoke/result/cancel/query, reconnect-does-not-re-run, no
   credential/path/exception-body leakage, and the text path unchanged — plus the production-DI
   resolution test and an instrumentation test that a Claude P pending tool appears in the
   existing approval UI, with explicit class names added to the workflow's execution gate rather
   than assumed.
5. **Enabling B in the same change**, once 1–4 are guarded by tests.
6. **CI proof** that the project's own toolchain compiles all of the above, and that
   `ClaudePToolFrameTest` executes somewhere the serialization plugin is present. Neither is
   available here: this machine has no Android SDK, and CI is out of scope for this stage.

Until 1–4 exist and pass, this is **not** `M2-B complete`, and the catalog must stay unsent —
which it is, because the default host's catalog is empty.

## 8. Requested verdict

Still **not** `M2-B complete`. The Kotlin half is verified, the close now proves what it reports,
and the seam a tool call is answered through exists, compiles, and is inert. The gap is the app
behind that seam — the executor, the approval path and the execution host — and §3 says they must
come before the catalog rather than after it.

They are also the half that **cannot be verified on this machine at all**: there is no Android
SDK here, so no `app/` code can be compiled, let alone run. That is why they were left unstarted
rather than half-written: a large body of unchecked code wired into the approval lifecycle is
exactly the kind of "looks finished" change that fails at the worst possible moment.
