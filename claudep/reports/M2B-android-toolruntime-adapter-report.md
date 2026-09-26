# M2-B — Android ToolRuntime adapter: implementation report

- Status: **INCOMPLETE. NOT ready for review as M2-B.**
- Base: `e833dbd6` (verified ancestor of HEAD)
- Branch / HEAD: `codex/claudep-cp1b-local` @ `5a01fc22`
- Pushed / CI baseline: `84fb7d7d` (verified ancestor of HEAD)
- Server contract of record: `rikkahub-claude-p-server` `4fbd76444ca2892fe08ac9945f64f8decc06a2fa`
- Working tree at time of writing: clean but for the pre-existing untracked `web-ui/bun.lock`

> Sections 0–8 below were written against HEAD `87e6a994`. Everything from **§9** on is the
> later C1–C4 batch. Where the two disagree, §9 wins and §0–§8 stand as the record of what was
> true then.

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

### 1.9 CI evidence for the five M2-B suites

The workflow's `REQUIRED` list names five M2-B test classes. Earlier revisions of this report
described them without ever recording what CI actually reported for each, which is how a claim
about five classes reads as if it were about four — the counts were never written down anywhere.
They are here.

Run `35979657240`, attempt 1, `workflow_dispatch`, conclusion **success**, on
`ca7e75a7a86e1f20fd1c48b1f0fcbb32030d4a59`. The figures below come from the run's
`unit-test-results-…` artifact (the JUnit XMLs), not from the log's summaries:

| class | tests | failures | errors | skipped |
|---|---|---|---|---|
| `claudep.bridge.BridgeGenerationRegistryTest` | 25 | 0 | 0 | 0 |
| `claudep.bridge.ClaudePToolBridgeTest` | **36** | 0 | 0 | 0 |
| `claudep.bridge.BridgeConformanceCorpusTest` | 5 | 0 | 0 | 0 |
| `claudep.ClaudePToolFrameTest` | 10 | 0 | 0 | 0 |
| `claudep.ClaudePToolWireRulesTest` | 12 | 0 | 0 | 0 |

The workflow's own gate agrees, and printed each one:

```
executed: me.rerere.ai.provider.claudep.bridge.BridgeGenerationRegistryTest (25 tests, 0 skipped)
executed: me.rerere.ai.provider.claudep.bridge.ClaudePToolBridgeTest         (36 tests, 0 skipped)
executed: me.rerere.ai.provider.claudep.bridge.BridgeConformanceCorpusTest    ( 5 tests, 0 skipped)
executed: me.rerere.ai.provider.claudep.ClaudePToolFrameTest                 (10 tests, 0 skipped)
executed: me.rerere.ai.provider.claudep.ClaudePToolWireRulesTest             (12 tests, 0 skipped)
All 29 required Claude P test classes executed.
```

`ClaudePToolBridgeTest`'s 36 is the number this report previously failed to state at all. Two of
these five — `ClaudePToolFrameTest` and `ClaudePToolWireRulesTest` — had **never executed
anywhere** before this run: this machine cannot run them (no serialization compiler plugin), so
CI was their first real execution. The same run also carried `ClaudePWssTransportTest` at
42 tests, 0 failures, 0 skipped.

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

---

# 9. The C1–C4 batch

- Base of this batch: `84fb7d7d` (verified ancestor of HEAD)
- HEAD after this batch: `5a01fc22`
- Local commits this batch: `719815da` (C1), `bb1b0ad0` (C2), `8e044f63` (C3), `5a01fc22` (C4)
- Pushed / CI baseline: unchanged at `84fb7d7d`. Nothing in this batch was pushed, no CI run was
  triggered, no deployment, no VPS connection, no model call, no tool side effect.

## 9.1 Preflight

Read-only, before touching anything. All of it held:

| Claim | Result |
|---|---|
| Branch is `codex/claudep-cp1b-local` | yes |
| HEAD is `8e044f63` | yes |
| `84fb7d7d` is an ancestor of HEAD | yes |
| Exactly three local commits since `84fb7d7d` | yes — `719815da`, `bb1b0ad0`, `8e044f63` |
| No unknown modifications | yes — only `web-ui/bun.lock`, pre-existing and untracked |

`web-ui/bun.lock` was not read, modified, staged or deleted.

## 9.2 Phase A — audit of C1–C3

All three were reviewed against the checklist before any code was written. **No P1 defect was
found**, so no corrective commit was made and C4 was allowed to proceed.

### C1 — `InFlightApprovalWaiters` (`719815da`)

| Requirement | Verdict |
|---|---|
| Exact approval identity | Yes. `InFlightApprovalIdentity` is the same four fields `PendingToolApprovalDao.getExact` matches on — `approvalId`, `executionId`, `conversationId`, `toolCallId` — verified against the DAO. |
| approval-before-await | Yes. `signalDecided` with no waiter records into `decidedAhead`; a later `await` consumes it first. |
| await-before-approval | Yes. The waiter is completed by `signalDecided`. |
| No polling | Yes. No database read, no flow, no timer; `withTimeoutOrNull` is the caller's own deadline. |
| Decision released exactly once | Yes for the paths that matter — see §9.2.1. |
| timeout / close / lost waiter are stable Abandoned | Yes. `TIMEOUT`, `GENERATION_CLOSED`, `DISCONNECTED`, `REGISTRY_CLOSED`, `CANCELLED`, each with a stable `localReason`. |
| `IN_FLIGHT` creates no resume command | Yes. Both `ChatService` resolve sites suppress `ensureApprovalResumeInCurrentTransaction` when `projection.isInFlightContinuation()`. |
| `RESUME_COMMAND` behaviour unchanged | Yes. Both barrier writers take `continuationMode` defaulted to `RESUME_COMMAND`, and the suppression is `!canResume \|\| isInFlight`, so a `RESUME_COMMAND` row is unaffected. `isInFlightContinuation` treats an unreadable value as **not** in-flight, which is the pre-v52 behaviour. |

`releaseDecision` is called after the commit returns, reading the decision from the **record**
rather than the caller's argument, so an idempotent repeat releases the same decision the commit
actually wrote.

#### 9.2.1 Two hardening observations (not defects; no fix made)

Both fail **closed** — the worst outcome either can produce is a tool the user approved not
running, never a second execution. Neither was fixed, because neither is a P1 and both sit in
code that already has CI evidence behind it.

1. **A duplicate decision after a claimed decision leaves a consumable entry.**
   If `signalDecided` finds a waiter it completes it and returns without writing
   `decidedAhead`; a *second* `signalDecided` for the same identity therefore finds no waiter
   and *does* write `decidedAhead`. A subsequent `await` on that identity would then return
   `Decided` immediately. In practice the bridge ledger admits one execution per `toolCallId`
   and `execute` awaits once per admitted call, so this is unreachable — but the class comment
   ("a second call for an identity whose decision is already claimed changes nothing") is
   stronger than what the code does on that one path.

2. **A narrow race between the two `synchronized` blocks in `await`.**
   `await` reads the settled maps in one lock and registers its waiter in another. A
   `signalDecided` landing between them finds no waiter, records into `decidedAhead`, and the
   waiter then blocks to its timeout with the decision sitting unclaimed. This is exactly the
   "fast user, slow frame" case the design exists to handle, and merging the two blocks into one
   would remove it. Failing closed, and the window is two adjacent synchronized blocks — but it
   is a real gap in a guarantee the class claims outright.

Neither should be fixed without the tests that would prove the fix; the driver evidence in C1
does not cover either, and the JUnit suite should.

### C2 — generation context (`bb1b0ad0`)

| Requirement | Verdict |
|---|---|
| Six values from authoritative context | Yes — `runId` ← `runControl.runId`; `commandId` ← `authoritativeCommandId` (which the parameter documents as never falling back to the run id); `conversationId` ← the resolved conversation; `assistantId` ← `assistant.id` from this conversation; `branchId` ← the durable command row's `branchAnchorMessageId`, read once and reused; `callOrigin` ← the resolved `ToolCallOrigin`. |
| fail-closed when missing | Yes. Absent fields become blank via `orEmpty()`, `isComplete` is false, and the host must refuse. |
| Never reaches wire / prompt / log / fingerprint | Yes. `@Transient` on `TextGenerationParams.claudePToolGenerationContext`; `toString` is redacted with `callOrigin` present-but-unstated (deliberately, because a digest of a small closed vocabulary is not a redaction). |

### C3 — catalog assembly (`8e044f63`)

| Requirement | Verdict |
|---|---|
| Local `readOnly` only from `InternalToolSecurityCatalog.READ_ONLY` | Yes. `provenReadOnly` requires `source == ToolSource.LOCAL` **and** membership in that set; it is the only producer of `true`. |
| MCP may never claim proven read-only | Yes, structurally. `BridgeToolCandidate.provenReadOnly` `require`s `LOCAL`, so an MCP source is not merely defaulted but refused. |
| Stable schema / order / digest | Yes. Entries are sorted by frozen name before `bodyOf` and before the digest; `frozenNameOf` normalises to lowercase and rejects anything outside the frozen alphabet. |
| Unencodable tools refused or dropped, never silently repaired | Yes. `BridgeToolCatalog` records a `BridgeToolExclusion` and drops; nothing is trimmed or rewritten. If `BridgeCatalog.freeze` rejects, the whole catalog is emptied rather than partially sent. |
| missing / null / empty kept distinct | Yes. No context → `NO_GENERATION_CONTEXT`; incomplete → `INCOMPLETE_GENERATION_CONTEXT`; unreadable origin → `UNKNOWN_CALL_ORIGIN`; a genuinely tool-less assistant → empty catalog with `refusal == null`, which is `isTextPath`. A tool declaring no schema yields `JsonNull` and is **dropped**, not repaired. |

## 9.3 Phase B — generation execution binding (`5a01fc22`)

### What was missing

The provider was handed a generation *identity* but never a generation *id*. `tool.invoke`
arrives naming the id the Server assigned; nothing on this side turned that id into the app's
authoritative context for the generation. `execute(invocation)` would have had a tool call and
no way to know whose it was — and every way of guessing picks *a* generation rather than *the*
generation.

### The shape, and an honest limitation

A generation's id **does not exist** when its plan must be built: the catalog travels in
`generation.start`, so the plan must be ready before that frame leaves, and the id only comes
back in the answer. There is no instant at which both are in hand.

So this is two steps:

- `prepare` builds the plan and stages it under a token of the host's own (`ClaudePToolPreparation.executionRef`, opaque, process-local, never parsed, never sent).
- `openGeneration(generationId, preparation)` redeems that token for the real id.

**Where it sits is what makes it safe**: the provider calls `openGeneration` immediately after
`generation.start` returns and *before the first frame is pumped*, so no `tool.invoke` can be
read for a generation whose plan is not yet in hand. A host that cannot bind fails the
generation loudly (`PROTOCOL_MISMATCH`, with the registry closed so the id is tombstoned) rather
than running tools it cannot answer.

The literal reading of "the provider completes the binding before sending any non-empty tool
snapshot" is unsatisfiable by any implementation, because the snapshot *is* the frame the id
comes back in. This is stated rather than glossed: the operational guarantee above is what is
actually provided, and a reviewer should judge that, not the phrasing.

### `BridgeExecutionBindings`

One lookup, taking a generation id. There is no method that accepts a conversation, an
assistant, a branch or a tool call id and returns *a* plan, so the forbidden cross-generation
search has no expression and cannot be added by accident on a later pass.

- **Exact keying.** `lookup` is the only read, and a sibling id, a prefix and a longer id are all misses.
- **Staged → opened → closed.** Staged by `prepare`, opened by `openGeneration`, released by `closeGeneration` from the same `finally` that closes the tool registry — so a terminal, a cancellation, a disconnect and a provider failure all release it. `closeGeneration` is deliberately **not** suspending, because it runs on a path that may already be cancelled.
- **Redeemed once.** An opened token is consumed; a second `open` with it refuses, so one plan cannot serve two generations.
- **Identity witness.** A re-open with a different identity is refused and the original stands. The registry has already judged that case, but a table that silently kept whichever arrived first would be correct only because another component was.
- **Bounded, and still fail-closed.** Both maps evict oldest-first; an evicted staged plan makes `openGeneration` answer `false`, which the provider turns into a failed generation.
- **Not a second registry.** It holds no ledger, no deadline and no tombstone, and manages no lifecycle of its own: it is driven by the provider at exactly the points `BridgeGenerationRegistry` is opened and closed. The registry remains the only thing that decides whether a generation may accept work.

### What did not change

`ClaudePToolBridgeHost.NONE` is still the only host in the tree. Its catalog is still empty, so
`toolFrames` is still `null`, no ledger is opened, no binding is created, and `generation.start`
is byte-for-byte what it was. The two new interface members have default bodies precisely so the
inert host stays inert without acquiring a stub.

### Evidence

Local, on this machine. No Android SDK; `:app` cannot be compiled here at all.

- **`:ai` type-checks.** The whole `ai/src/main` tree compiles against Kotlin 2.4.0 with the serialization plugin, reproducing exactly the two `ToolResultReplayPlan.kt` android-util artifacts §1.8 recorded and **no new ones**. That covers `ClaudePToolBridgeHost.kt`, `BridgeExecutionBindings.kt` and `ClaudePProvider.kt`, including the new `suspend` on `openToolGeneration` and the new `finally` ordering.
- **`BridgeExecutionBindingsTest`: 14 tests, 14 passed, 0 failed.** Executed, not merely compiled.
- **A 31-check driver over the same compiled class: 31/31.**
- Both are **driver/stub-runner evidence, not JUnit-runner evidence**: the test bodies and assertions genuinely run, but the runner is a local `org.junit` stub, not the real one. This is not CI evidence and does not substitute for it.

The new class was added to the workflow's `REQUIRED` list in the same commit, so the existing
`--tests "me.rerere.ai.provider.claudep.*"` filter plus the XML gate will cover it. CI was **not**
triggered.

## 9.4 What remains, and the design work already done

Phases C–G are **not** implemented. The snapshot is still closed, so the tree is in the safe
state the plan requires: it is not possible for Claude to see a tool Android cannot answer.

The next session should not have to rediscover the following. It was established by reading the
real code, and two of the four points change the shape of the work.

### 9.4.1 The approval ordering problem — a required interface change

`ClaudePToolFrameHandler.onInvoke` currently does:

```kotlin
val execution = host.execute(decision.invocation)
execution.part?.let { emit(toolCallChunk(it)) }   // emitted only AFTER execute returns
```

For a `RESUME_COMMAND` approval that ordering is fine, because the generation ends and the user
taps later. **It is wrong for `IN_FLIGHT`**: `execute` must *suspend* until the user decides, so
the `Pending` card would not reach the conversation until after the decision — and the user
would have nothing to tap. `execute` therefore has to be able to surface the part **before** it
blocks, e.g.

```kotlin
suspend fun execute(
    invocation: BridgeInvocation,
    onPart: suspend (UIMessagePart.Tool) -> Unit = {},
): BridgeToolExecution
```

with the frame handler passing `{ emit(toolCallChunk(it)) }`. `Tool.merge` keys on `toolCallId`,
so the `Pending` part and the final part merge the way the ordinary agent loop's do.

### 9.4.2 The run control has to be reachable by `runId`

`DefaultToolRuntime.execute` registers its `ToolExecutionHandle` with `request.runControl`, and
`GenerationRunControl.requestCancelTool` / `awaitToolTermination` are the app's real
"stop this and tell me what you can prove" API — which is exactly what Phase D needs. But the
host is a long-lived singleton and the control is created per run in
`ConversationRuntime.startRun`, with **no existing `runId` → control registry**.

`runId` is `envelope.id`, i.e. the durable command id — the same value C2 already puts in
`ClaudePToolGenerationContext.runId`. So a small `runId` → `GenerationRunControl` registry
published at the one construction site and withdrawn when the run finishes closes the gap
without touching the runtime's semantics. Reusing the control is strictly better than holding
handles in the host, which would mean re-implementing `ToolRuntime`'s handle construction.

### 9.4.3 Everything else the host must reuse, and where it lives

| Need | Existing mechanism |
|---|---|
| Run a tool, gate it, assess it | `DefaultToolRuntime.execute(ToolExecutionPlanRequest(...))`; re-assess with the **real** arguments via `assess(ToolAssessmentRequest(toolName, args, context))` |
| Policy gate | `ToolExecutionGate.evaluate(...)` passed as `preExecutionGate` |
| Approval barrier | `SecondUserApprovalLifecycle.persistPendingBarrier(..., continuationMode = IN_FLIGHT)`, then `InFlightApprovalWaiters.await(identity, timeoutMs)` |
| Approval identity | the four fields of the returned `PendingToolApprovalRecord` |
| Cancel / prove a stop | `GenerationRunControl.requestCancelTool` + `awaitToolTermination(grace)` → `ToolTerminationState.StoppedConfirmed` is the only value that may become `cancelled` |
| MCP tools | `McpManager.callTool(serverId, toolName, args)`; OAuth/token never leaves Android |
| Tool execution context | `ToolExecutionContext(runId, conversationId, assistantId, callOrigin, commandId, toolCallId, ...)` |
| Where the tool list comes from | `ChatService`'s `tools = buildList { ... }` block (≈`ChatService.kt:3721-3908`), already filtered and `stableProviderToolOrder`-sorted |

### 9.4.4 A pre-existing gap worth naming

`ClaudePProvider.resumeStream` pumps replayed frames **without** a `ClaudePToolFrameHandler` and
without calling `prepare`/`openGeneration`, so a `tool.invoke` re-delivered by a reconnect
replay is dropped rather than answered. This is a real idempotent-recovery hole on the reconnect
path. It is not a regression introduced by this batch and it was deliberately left alone rather
than widened into scope, but it must be settled before the snapshot is enabled.

## 9.5 Boundary compliance

- No Server file read or written; the contract of record is unchanged.
- No Room schema, version, migration or workflow-gate semantics changed; `app/schemas/**/52.json` untouched. The only workflow edit is one added line to the `REQUIRED` array.
- No new runtime dependency; no build file or version catalog touched.
- No push, CI dispatch, PR, tag, release or deploy. No VPS connection. No model call. No tool side effect. No phone.
- No M3 work.
- `git diff --check` passes; the working tree is clean but for the pre-existing untracked `web-ui/bun.lock`; `84fb7d7d` is still an ancestor.

## 9.6 Commits

```
5a01fc22 feat(claudep): bind a tool call to the generation that is running it   (C4, Phase B)
8e044f63 feat(claudep): freeze the assistant's own tools for the bridge         (C3)
bb1b0ad0 feat(claudep): bind a tool call to the generation that is actually running (C2)
719815da feat(claudep): let an approval release the waiter that is already there    (C1)
84fb7d7d ci: preserve required app test results in Claude P gate                (pushed baseline)
```

## 9.7 Requested verdict

**Not** `M2-B complete`, and this batch does not claim it. Phase A passed with two
non-blocking hardening observations (§9.2.1); Phase B landed, type-checks, and has executed
test evidence. Phases C–G remain, and the one thing that must not be rushed — the `IN_FLIGHT`
approval path — now has its two structural obstacles written down in §9.4.1 and §9.4.2 rather
than waiting to be discovered.

The snapshot remains closed, which is the only safe state until C–F all answer.

---

# 10. The C5–C8 batch: the load-bearing seams before the production host

- Base of this batch: `5069a7c2` (chosen after the preflight discrepancy in §10.1)
- Tip at the end of the batch: `c6f84fc4`, which added the C2 threading fix in §10.10 on top of the
  seam commits
- Pushed: yes, fast-forward from `84fb7d7d` throughout. `origin/codex/claudep-cp1b-local` is
  `c6f84fc4`.
- `84fb7d7d` remains an ancestor and was never rewritten.
- **CI: green.** Run `36225522688` on `c6f84fc4`, attempt 1 — §10.10. An earlier run
  (`36224200862` on `0c87d2e0`) failed on a compile error this batch did not introduce and then
  fixed; §10.9 keeps that record.

Scope, stated up front: this batch builds the seams the production host will sit on. It does
**not** run a single tool. The default host is still `ClaudePToolBridgeHost.NONE`, its catalog is
still empty, `tool_snapshot` is still never sent, and `execute` is unreachable rather than merely
unused. No Local tool, no write approval, no MCP tool, no snapshot activation.

**The production host itself is not implemented and was not started.** No `ClaudePToolProductionHost`,
no DI binding for one, and no path by which a non-empty snapshot could be produced. The snapshot
remains closed, which is the only safe state until the execution, approval and cancel paths all
answer.

## 10.1 Preflight, and the one discrepancy

Read-only, before anything was written.

| Claim | Result |
|---|---|
| Branch is `codex/claudep-cp1b-local` | yes |
| HEAD is `5a01fc22` | **no** — HEAD was `5069a7c2` |
| `84fb7d7d` is the origin tip | yes, and untouched |
| `5a01fc22` is an ancestor of HEAD | yes |
| Only `web-ui/bun.lock` untracked | yes |

`5069a7c2` is the previous batch's report commit — the one that batch's own instructions listed as
commit `7. report`. The delta was therefore fully accounted for and not an unknown change, but the
instruction is to stop on a mismatch, so I stopped and asked rather than deciding. The ruling was
to proceed from `5069a7c2` with no reset, no rebase, no amend and no history rewrite, which is what
was done.

## 10.2 A — the two approval-waiter races, and how each is proven fixed

Both were recorded in §9.2.1 as non-blocking because both failed closed. They are fixed here
because the production host is about to depend on exactly these two guarantees.

### A1 — a duplicate decision could be consumed twice

`signalDecided` completed a live waiter and returned without recording that the identity had been
settled. A second tap then found no waiter, saw nothing in `decidedAhead`, and **wrote** a decision
that the next `await` would take and act on.

The delivery maps cannot be the exactly-once record, because a delivery *consumes* the decision —
the guard would be erased by the act of honouring it. So `settled` is now a record of its own,
written **before** the delivery and removed by nothing. A second signal, a second abandon, and a
decision arriving after an abandonment are all complete no-ops. That last one is a fix in its own
right: such a decision used to become executable again, for a generation that was already gone.

`abandonAll` deliberately still does not clear `settled`, and `forget` still does not either: a
shutdown does not make a decision that already happened un-happen, and clearing the guard is
precisely the state in which a duplicate tap becomes a second execution.

### A2 — the registration was two critical sections

A decision landing between "no decision is waiting for me" and "I am registered" found no waiter,
was remembered as undelivered, and the waiter registering a moment later blocked to its deadline
with the decision sitting right there, unclaimed, for a call the user had already approved. They
are one critical section now.

### How this is proven rather than argued

The registration boundary is exercised **deterministically**, not by racing threads. A new
`internal` seam, `onAwaitRegistration`, runs inside the critical section on the caller's thread and
therefore reenters the lock. A correct implementation has the waiter installed by then, so a signal
injected there finds it and the wait returns the decision immediately; the two-section version had
nothing registered yet, so the same signal would be remembered and the wait would time out. No
sleeps, no retries, no timing assumptions.

`InFlightApprovalWaitersTest` covers both orderings, the boundary, duplicates before and after a
claim, approve and deny, the deadline, abandonment before and during a wait,
abandon-never-overwrites-a-decision, decision-after-abandonment, `abandonAll`, `forget`,
exact-identity isolation, a duplicate registration stranding the first waiter, and the bounded
settled record.

**The A1 fix is also shown to discriminate.** The same sequence run against the pre-fix class,
extracted from `5069a7c2` with `git show`, prints:

```
PRE-FIX   await after duplicate = Decided(decision=APPROVED)   <- re-consumed
FIXED     await after duplicate = Abandoned(reason=TIMEOUT)    <- no trace
```

**The A2 fix is not provable the same way**, and that limit is stated rather than papered over: the
old structure has no seam to inject at, because there the corresponding point is *between* the two
sections. What backs A2 is the deterministic test passing plus the diff showing one `synchronized`
block. No dynamic evidence of the old behaviour exists for it.

## 10.3 B — the publication seam, and the order it forces

A Claude P tool call arrives inside a live stream with the peer blocked on it. A call that needs
approval cannot run until the user taps — and the card they tap must be in the conversation
**before** the wait starts, which means it must be published from *inside* `execute`, not returned
from it. `BridgeToolExecution.part` is the *result*, and a result that only arrives after the
decision is a decision nobody could make.

`execute` now takes a `ClaudePToolStatusSink`. The seam carries no `UIMessagePart`, no Room and no
Compose: it carries a status, an identity and the arguments.

### The real order, as implemented

1. create the approval's exact identity;
2. publish `PENDING_APPROVAL` through the sink and **require `Accepted`**;
3. only then register with (or obtain) the `InFlight` waiter;
4. wait for the decision;
5. only after an approval, run the tool.

A `Refused` answer means nothing was shown, so nothing can be tapped, so the caller must run
nothing. It is a value the host has to handle rather than a failure it can ignore. The order is
written on the vocabulary rather than left to the implementation.

### Where the mapping lives, and why

The vocabulary is deliberately **not** the wire's `ToolCallState`: it has `PENDING_APPROVAL`, which
the Server has no word for because an approval is Android's business, and it lacks the Server's own
verdicts, because nothing here may pronounce one. That separation is what stops a later
convenience from turning an approval into something the peer can name.

Rendering a status into the conversation is the provider's, because the conversation stream is the
provider's — putting it in the app would mean the app writing to a stream it does not own. The
**approval lifecycle** stays entirely in the app. That split is a decision, recorded as one.

The terminal statuses map to no interim part at all: their outcome part comes back through
`BridgeToolExecution`, shaped by the app that ran the call. A mapping that rendered them would put
a tap target on screen for a call that is already over.

## 10.4 C — the run-control registry and its lifecycle

The host that needs a run's control is a long-lived singleton; the control is created per run.
`ClaudePToolRunControls` is the one thing that closes that gap, and it is only that: it holds a
reference the app already created, returns it by exact id and drops it when the run ends. It creates
nothing, persists nothing and schedules nothing, and cancellation stays `GenerationRunControl`'s —
the registry has no opinion about what cancelling means and must never grow one.

Lifecycle, precisely:

- **Registered in `ConversationRuntime.startRun`**, after the control is constructed and before the
  job starts, so there is no instant in which a run could be serving a tool call while its control
  is undiscoverable.
- **Removed in `job.invokeOnCompletion`** — the only "finally" a run is guaranteed to reach, since
  the run-finished handler can decline a run it no longer considers active, and a control left
  behind by a superseded run would be found by nothing and leak. The removal is identity-checked,
  so a late completion cannot withdraw a newer run's control.
- **A duplicate id is refused, not replaced.** Replacing would move an in-flight call's
  cancellation onto a control that is not running it.
- **Nothing is evicted.** Dropping a live run's entry makes an in-flight tool uncancellable, which
  is worse than a map one entry too large. The real bound is the number of concurrent runs.
- **One Koin `single`.** `ConversationRuntime`'s parameter is defaulted to `null`, so the ~45
  existing test constructions are untouched; with `null` nothing is published and nothing is
  discoverable, which is the fail-closed default.

## 10.5 D — how `resumeStream` avoids a second execution

`resumeStream` pumped the replay buffer with **no tool handler at all**, so a re-delivered
`tool.invoke` was silently discarded and then sat until its deadline — while the answer was already
recorded a few lines away in the generation's own ledger.

It now builds the **same** `ClaudePToolFrameHandler` the live stream uses, found by this exact
generation id. Using the same handler is what makes it safe rather than a second execution path:

- a call that **settled** → `Replay`, and the recorded outcome is re-sent. Nothing runs.
- a call still **running** → `Await`, and nothing is sent. Nothing runs.
- a frame for an **unknown generation or call id** → `lookup` returns `null`, the frame is dropped,
  and no adapter is invented. The Server's own deadline concludes it.

So a reconnect cannot buy a second tool side effect: there is no path from a replayed frame to the
runtime that does not go through the ledger. And nothing here calls `generation.start`, so a
reconnect still cannot buy a model request. A tool frame after a terminal is refused by the
adapter's own closing flag rather than by the adapter's absence.

## 10.6 E — the binding's guaranteed semantics, and its literal limit

The two-step design from `5a01fc22` is kept.

**The literal limit, stated plainly:** it is **impossible** to know the Server's `generationId`
before sending a non-empty `tool_snapshot`, because that snapshot travels *in* `generation.start`
and the id only comes back in the answer. No implementation can satisfy a literal "binding before
the snapshot". This report does not claim it.

**What is guaranteed instead:** binding is completed before any returned frame is *consumed*. The
provider performs `openGeneration` in the window between `generation.start`'s answer and the first
`pumpFrames` call, so no `tool.invoke` can be read for a generation whose plan is not yet in hand;
and a failed binding closes the registry (tombstoning the id) and throws, so the generation fails
loudly rather than running with tools it cannot answer.

Tested at the level where it can be tested:

- a token redeemed for one generation cannot be redeemed for another;
- re-opening an already-bound generation is idempotent and keeps the one binding;
- a token that would bind a *different* plan is refused, and left staged so the refusal repeats
  rather than being satisfied by a spent entry;
- an unknown token on a *new* generation refuses and binds nothing;
- reopening a **closed** generation is refused by `BridgeGenerationRegistry`, which tombstones the
  id — the test says so explicitly rather than implying this table holds that guarantee.

**Not proven by a test, and marked as such:** the provider-level ordering itself. No test this
batch drives a real provider through a queued replay buffer. That ordering is evidenced by the code
and its call sites — a statement about the source, not about an execution.

## 10.7 F — tests and gates

Three new classes, and none of them would have run without wiring:

| Class | Module | Wired into |
|---|---|---|
| `InFlightApprovalWaitersTest` | `:app` | last `:app:testDebugUnitTest` `--tests` + REQUIRED |
| `ClaudePToolRunControlsTest` | `:app` | same |
| `ClaudePToolStatusMappingTest` | `:ai` | `:ai:testDebugUnitTest` `--tests` + REQUIRED |

`BridgeExecutionBindingsTest` was already in REQUIRED from the previous batch and is covered by the
existing `me.rerere.ai.provider.claudep.*` filter.

The `:app` entries are in the **last** `:app:testDebugUnitTest` invocation for the reason that step's
own comment already gives: re-running the task with a different filter replaces
`app/build/test-results/testDebugUnitTest`, so a class named only in an earlier step has its XML
discarded and the gate reports "compiled but did not run" for a class that ran and passed. That is
the specific failure the previous CI fix (`84fb7d7d`) addressed, and it is not repeated here.

The REQUIRED gate requires each entry to produce an XML with `tests>0`, `failures=0`, `errors=0`,
`skipped=0`.

No instrumentation whitelist change: none of the new classes is an instrumentation test.

## 10.8 Evidence: what is CI, what is JUnit, what is a local driver

| Claim | Kind of evidence |
|---|---|
| CI build, regression, Claude P suites, conformance, APK/signature | **CI** — §10.9 (failed) and §10.10 (green) |
| The four new/expanded test classes executed | **CI** — §10.10, with per-class counts |
| Waiter races fixed; boundary atomic | local executed driver + a pre/post discrimination run; **now also CI-executed** (20 tests) |
| Registry exactness, duplicate refusal, concurrency | local executed driver; **now also CI-executed** (11 tests) |
| Status-to-part mapping | local executed driver; **now also CI-executed** (5 tests) |
| Binding re-open semantics | local executed driver; **now also CI-executed** (18 tests) |
| Provider-level binding ordering | **static audit of source and call sites only** — unchanged, still no test |
| `:ai` main tree compiles | local compile with bytecode emitted, **and** real CI compilation |
| `:app` compiles at all | **CI only** — this machine has no Android SDK |

The local runs use a stub `org.junit` and a small reflective runner. The test bodies and assertions
genuinely execute; the runner is not the real one. Where §10.9 reports the same classes again, the
CI figures are the ones to trust.

## 10.9 CI run 1 — **this run failed, and the failure was not in this batch's code**

> **Resolved.** The error below was fixed in `c6f84fc4` and a second run
> (`36225522688`) is green — see §10.10 for the fix and the verification. This section is
> kept as the record of what happened, not rewritten, because the failure is what the fix
> is answerable to.

| | |
|---|---|
| Run ID | `36224200862` |
| URL | https://github.com/maocomet/rikkahub-agent1/actions/runs/36224200862 |
| Workflow | `build-debug-apk.yml`, `workflow_dispatch` |
| SHA | `0c87d2e0e1908f23a4a3f6bac6d2e6d5203616db` |
| Attempt | 1 (no rerun) |
| Conclusion | **failure** |

Steps that failed: **9** `Build debug APK`, and then **12** `Report executed regression test
classes`, **14** `Report executed Claude P test classes`, **15** `Verify the conformance corpus
gate`. Steps 12/14/15 are the known cascade: they read JUnit XML that only exists if the build and
its tests ran, so a build failure fails them too. **Step 9 is the root cause.**

### The root cause, exactly

```
e: app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt:3596:44
   Unresolved reference 'claudePToolGenerationContext'.
* What went wrong:
Execution failed for task ':app:compileDebugKotlin'.
```

One error, and it is **not** from this batch. `GenerationHandler.kt` has not changed in this batch
at all, and it is not in the C5–C8 diff.

`claudePToolGenerationContext` is declared as a parameter of `generateText` (signature at line 623,
the parameter at 689) and **used** at line 3596 — which is inside `generateInternal`, whose
signature ends at line 2749 and which declares neither that parameter nor anything that carries it.
`generateText` never threads it down, so the name is simply not in scope where it is read.

**Provenance: `bb1b0ad0` (C2, the *previous* batch).** That commit added the parameter to
`generateText` and the use inside `generateInternal` and did not connect the two. It has been a
compile error in the pushed history ever since, because the previous batch neither pushed nor ran
CI — this run is the **first time `:app` has been compiled** since that commit.

### Why local verification did not catch it, and what that says about §10.8

The local harness compiles `:ai` only. `GenerationHandler.kt` lives in `:app`, and this machine has
no Android SDK, so the file was never compiled here — the previous report said so in as many words
("`ChatService` and `GenerationHandler` cannot be compiled on this machine at all"). What that
disclosure did not do is gate the change on the one thing that could have caught it. A parameter
added in one function and read in another is precisely the class of error that only a real compile
finds, and it was shipped on the strength of a type-check that could not see the file.

**Consequence for §10.8**, as it stood for this run: no row marked "CI" was satisfied. No test of
any kind executed — the build failed before them — so the four test classes this batch adds and
wires had not been executed in CI, and the REQUIRED gate was moot until `:app` compiled. The only
CI-backed statement this run could make was the negative one above. **All of that was then
satisfied by run 2 — see §10.10.**

One positive fact this run did establish, which is easy to lose: **`:ai` compiled.** The only
failed task was `:app:compileDebugKotlin`, and `:ai:compileDebugKotlin` ran to completion before
it. So the `:ai` half of this batch — `BridgeExecutionBindings`, `ClaudePToolStatus`,
`ClaudePProvider`, `ClaudePToolBridgeHost` — has real CI compilation behind it, not just the local
harness.

### What was done about it

**Nothing, deliberately.** The instruction for a failed CI run is to stop immediately: no fix, no
additional commit, no rerun, and no progress toward the production host. That is what happened.

The consequence to be aware of: **`0c87d2e0` is pushed and is not buildable**, and it is not the
only such commit — the break originates in `bb1b0ad0`, so every commit from there to the tip fails
`:app:compileDebugKotlin`. A fix belongs in a reviewed change of its own, not appended to a run
that already failed.

### Note on the report commit itself

This report was first left uncommitted, because the instruction for a failed run says not to append
commits to it. It was committed afterwards as `de287c51`, at `0c87d2e0` — the exact SHA that run
tested — under a separate authorisation. That commit is docs-only: no production code, test,
workflow, schema or migration is in it.

---

## 10.10 The fix, and the second CI run

### The defect and what was wrong with it

`generateText` declared `claudePToolGenerationContext` and `generateInternal` read it. Nothing
connected the two, so the name was out of scope where it was used and `:app` did not compile — from
`bb1b0ad0` (the previous batch's C2) until this fix.

### The fix — `c6f84fc4`

- `generateInternal` takes the parameter.
- The **generation path** passes the object `generateText` was already handed: the same instance,
  not a copy and not a rebuild. The six identities are the authority's values, and re-deriving them
  would be a second chance to disagree about which generation is running.
- The **final-answer recovery dispatch** passes an explicit `null`. It is a text-only recovery call
  with `tools = emptyList()`, so it offers Claude nothing to call and there is no tool call there to
  bind. Passing the context anyway would assert a binding that dispatch does not have, and would do
  it *by default* — which is how a later change that gave recovery a tool surface would quietly
  inherit the wrong generation.

Neither site guesses, and neither fills the value from a global, a ThreadLocal, the conversation,
the assistant or a re-construction.

### Why the audit found no second instance

Searched the whole `app/` and `ai/` chain for the four shapes worth worrying about:

| Shape | Finding |
|---|---|
| Declared outside, referenced inside | The one case, fixed. The compiler is the strongest witness: run 1 reported **exactly one** Kotlin error and one failed task, and kotlinc lists every unresolved reference in a module before failing |
| A copy that drops fields | None — there is no `.copy(...)` on the context anywhere |
| A default overriding a real value | None — no call site passes a literal in place of the value it holds |
| Re-derivation from current state | None — exactly one construction in the app (`ChatService.kt:3582`), reading the run control, the durable command row, this conversation's assistant and the resolved origin. Every other `TextGenerationParams` site (background, vision, OCR, pet, memory, connection tests) omits the field and takes the null default, which is the correct fail-closed answer for a call that is not a tool generation |

### Test coverage added

`ClaudePToolGenerationContextTest` gained three cases, each asserting something the threading
depends on rather than restating Kotlin:

- parameters that name no generation carry **null**, not an empty context — an empty one is the
  shape that would fail closed for the wrong reason;
- an identity placed on the parameters comes back as the **same object** (`===`, not `==`:
  equality would pass for a rebuild that happened to produce the same six strings, and the rebuild
  is the hazard) with all six fields;
- `copy` for an unrelated field neither drops nor remakes it.

The class was already in REQUIRED and covered by the `me.rerere.ai.provider.claudep.*` filter, so
these execute without a workflow change. The wire-byte and identity-leak claims were already
covered by `ClaudePToolFrameTest`.

### CI run 2

| | |
|---|---|
| Run ID | `36225522688` |
| URL | https://github.com/maocomet/rikkahub-agent1/actions/runs/36225522688 |
| SHA | `c6f84fc41c1944fc8af124b025f92891935f7df1` |
| Attempt | 1 (no rerun) |
| Conclusion | **success** |

Every step succeeded. Step 17 (`Diagnose web-ui build (on failure)`) was correctly skipped.

Each gate, read from the run's own output rather than the summary:

1. **Compilation.** Zero Kotlin errors in the log. `:ai:compileDebugKotlin` and
   `:app:compileDebugKotlin` both ran to completion. This is the first real compile of `:app` since
   `bb1b0ad0`.
2. **APK and fixed signature.** All three APKs verified against the fixed agent-test key:
   ```
   app-arm64-v8a-debug.apk -> fixed agent-test key sha256: 2f1965cf7447301f857ec222fb1996ac179b07c771d9b3a636bd0116fefffcc3
   app-universal-debug.apk -> fixed agent-test key sha256: 2f1965cf7447301f857ec222fb1996ac179b07c771d9b3a636bd0116fefffcc3
   app-x86_64-debug.apk  -> fixed agent-test key sha256: 2f1965cf7447301f857ec222fb1996ac179b07c771d9b3a636bd0116fefffcc3
   ```
   `apksigner` was found, so this is a real comparison and not the skip path.
3. **Regression tests.** 38 classes executed, including `ApprovalContinuationModeTest`.
4. **Required Claude P classes.**
   `All 35 required Claude P test classes executed.` Every listed class reported `0 skipped`, and
   the step succeeded, which its own gate only does when every class has `tests>0`, `failures=0`,
   `errors=0` and `skipped=0`.
5. **This batch's new tests, with the counts CI reported:**

   | Class | Claim it backs | Tests | Skipped |
   |---|---|---|---|
   | `InFlightApprovalWaitersTest` | approval waiter atomicity | **20** | 0 |
   | `ClaudePToolRunControlsTest` | run-control registry | **11** | 0 |
   | `ClaudePToolStatusMappingTest` | publication seam | **5** | 0 |
   | `BridgeExecutionBindingsTest` | resume / binding ordering | **18** | 0 |
   | `ClaudePToolGenerationContextTest` | context threading (extended) | **12** | 0 |

   The last of those is the class extended by the fix; the other four are the batch's own.
6. **Conformance.** `SPEC_REVISION.json: OK`; `Conformance XML gate self-test: 8 cases behaved as
   expected.`; `ClaudePConformanceCorpusTest executed exactly 14 tests, none skipped and none
   failing.`
7. **Conclusion:** `success`.

### What is still *not* covered by any of this

Two things, unchanged from §10.8 and worth keeping in view:

- **The provider-level binding ordering has no test.** That no tool frame is *consumed* before the
  binding completes is guaranteed by the shape of the provider — the `openGeneration` call sits
  between `generation.start`'s answer and the first `pumpFrames` — and is evidenced by reading the
  code. It is not evidenced by an execution.
- **The threading itself has no test.** CI proves it *compiles*; nothing yet proves the value
  reaches the provider. The gap between "compiles" and "runs" is exactly where the original defect
  lived, and a test for it needs a `GenerationHandler` harness that does not exist.

Neither is claimed as verified.

### Boundary compliance for the fix

No Room schema, version or migration changed; no workflow change; no Server change; no dependency
added; `web-ui/bun.lock` untouched and untracked. `tool_snapshot` remains empty and the production
host was not started.

---

# 11. The A batch: the two execution-level gaps §10.11 named

- Base of this batch: `5fceaf27` (the docs-only CI-evidence commit; kept, not rewritten)
- Tip after this batch: `a232730f`
- Commits this batch: `ef223b90` (A1 + A2 + the one pure seam they need), `a232730f` (the approval
  subject correction in §11.5, and the gate entries the three new classes need)
- Pushed: **no**. No CI dispatch, no deployment, no VPS connection, no model call, no tool side
  effect, no phone. Server remains read-only.
- `c6f84fc4` and `5fceaf27` both remain ancestors.

§10.11 listed two things that "are not evidenced by an execution", and both are now closed. This
batch is deliberately narrow: it adds the tests, and the single pure seam one of them needs. **No
tool runs, the default host is still `NONE`, the catalog is still empty, and `tool_snapshot` is
still never sent.**

## 11.1 Preflight, and what it found

The stated starting point was verified before anything was changed:

- branch `codex/claudep-cp1b-local`, HEAD `5fceaf27`, `c6f84fc4` its direct parent and ancestor;
- the only working-tree entry was the pre-existing untracked `web-ui/bun.lock`;
- `5fceaf27` touches exactly one file, the report — so it is docs-only, as described.

No reset, rebase, amend or discard was performed on it.

**One correction to §7 and §10, and it matters for the rest of the plan.** Both sections state that
this machine has no Android SDK and that no `app/` code can be compiled here. That is no longer
true: `local.properties` in this worktree points at `D:\Android\Sdk`, `platforms/android-37.0` is
present, and both of this batch's suites were **compiled and executed locally**, not merely
reasoned about. Every claim below is a local execution, and the limits of that are stated where
they arise (§11.2, §11.4) rather than left implicit.

## 11.2 A1 — the identity reaches the provider, and cannot be reinvented on the way

`ClaudePToolGenerationContextFactory` (`app/…/data/claudep/`) is a pure function from the six
authorities to the identity. It is an **extraction, not a redesign**: the mapping is
character-for-character what `ChatService` already did inline, including the `.orEmpty()` on each
nullable authority. What changes is that the mapping became testable, and that the construction
site became singular — `ChatService` now contains exactly one occurrence of
`ClaudePToolGenerationContext` and it is the factory call.

That `.orEmpty()` is kept rather than replaced by a `null` return, and the test says why: a blank
field already fails `isComplete`, the bridge already refuses on it, and returning `null` instead
would change which local refusal reason is recorded for a case that is already handled. A mapping
stays a mapping.

`ClaudePToolGenerationContextWiringTest` — **9 tests, 0 skipped, 0 failures** — asserts:

- each authority lands in its own field, using six **pairwise-distinct** sentinels, because the
  failure being caught is a swap (`commandId` filled from `runId`, `branchId` from the
  conversation) and a test with one shared value passes for every permutation;
- a missing command id is blank rather than filled from the run id — the substitution the
  surrounding code calls out by name, and the one several paths (cron, workflow, recovery) would
  produce;
- an absent authority leaves its field blank **and** makes the identity incomplete;
- every `ToolCallOrigin` entry survives as the enum spells it — no trimming, no folding, no
  alias, which is what makes the bridge's exact-match rule meaningful;
- the threading, and the absence of any rebuild, at all four hops.

### The honest limit on the threading half

`GenerationHandler.generateText` cannot be instantiated in a JVM test — it needs an Android
`Context` and the full Koin graph, which is the same reason `GenerationHandlerTurnBudgetTest`
tests the invariants it relies on rather than the handler. So the threading half is asserted
**against the source**, not by running a turn: that `ChatService` builds through the factory and
constructs one nowhere else; that `GenerationHandler` contains no construction of the type at all,
only the parameter and the pass-throughs; that the recovery dispatch passes an explicit `null`;
and that the provider reads the field off the params rather than rebuilding it.

This is a weaker claim than a behavioural test, it is the same technique
`LearningArchitectureBoundaryTest` already uses for boundaries that are likewise unreachable from a
unit test, and the test's own doc comment says so. It closes the specific hazard §10.11 named — a
rebuild is what would silently disagree about which generation is running — without pretending to
close more.

## 11.3 A2 — the binding completes before any tool frame is consumed

`ClaudePProviderBindingOrderTest` — **7 tests, 0 skipped, 0 failures** — drives the real provider
against the deterministic fake gateway. The fake builds its entire script, `tool.invoke` included,
**inside `startGeneration`**, which is what makes "the frame is already buffered" a fact about the
implementation under test rather than a staging trick in the test.

The claim is stated carefully, because the obvious version of it is false. The provider does not
know a generation id before `generation.start` and must never pretend to: the catalog travels
*inside* that request, so the preparation is built first and the id only comes back afterwards.
There is no instant at which both are in hand. What the test asserts is the guarantee that is
actually available — **by the time any `tool.invoke` is read off the stream, the generation it
names resolves to exactly one plan** — and it asserts it at the instant it would be violated:

- the catalog is in the start frame (without it the Server would never send an invoke, and every
  later assertion would be vacuous);
- `prepare` ran before the request left;
- **no binding had happened when the request left** — checked *inside* `generation.start`, since
  reading the counter afterwards says nothing about it;
- the binding happens exactly once, and **no frame was consumed before it**;
- the executed call belonged to the generation that was bound;
- the outcome was sent once.

Plus the failure and replay directions: a refused binding fails the generation and executes
nothing; a replayed frame for a closed generation executes nothing, re-binds nothing and sends no
second result; and a second provider over the same gateway executes nothing for a generation its
registry never bound — the process-restart shape.

`BridgeExecutionBindingsTest` already covers the token-once semantics at the table level
(18 tests), so this class deliberately does not re-test them; it tests the *provider's* sequencing,
which had no test at all.

## 11.4 Evidence

Both suites were run locally on 2026-09-26:

| Suite | Result |
|---|---|
| `ClaudePProviderBindingOrderTest` | 7 tests, 0 skipped, 0 failures, 0 errors |
| `ClaudePToolGenerationContextWiringTest` | 9 tests, 0 skipped, 0 failures, 0 errors |
| `:ai` Claude P filter (whole module) | 449 tests, 1 failure — see below |

The single `:ai` failure is `ClaudePConformanceCorpusTest > corpus revision is bound to the
specification it was derived from`, and it is **not** this batch's code and **not** a regression.
It hashes `claudep/02-wire-protocol-v1.md` from the working tree and compares that digest to
`SPEC_REVISION.json`. This machine has `core.autocrlf=true`, so the checked-out file is CRLF while
the committed blob is LF:

```
recorded : 8ff5e6a5a6e494b7fe7546a918d55567c918623853f5fa7367ddb23094ba3e85
LF (git) : 8ff5e6a5a6e494b7fe7546a918d55567c918623853f5fa7367ddb23094ba3e85   <- matches
CRLF (wt): 6d53aa6393f63dbbfab28c28987ef14a47605aba1fb61b14fbb1ae5a8122ad66
```

The recorded digest equals the LF blob byte for byte, verified by hashing the blob directly. CI
checks out LF on Linux and this class is green there. It is recorded here rather than quietly
excluded, because a local suite that is "449 tests, 1 failure" and a CI suite that is green must
be reconcilable, and this is the reconciliation.

## 11.5 The approval subject constraint: audit, and the correction

**Superseded within this batch.** This section was first written as a *blocking* design constraint
on C2. The product rule was then corrected, and the correction is commit `a232730f`. What follows
is the audit, the rule and the fix — in that order, because the audit is what makes the fix
minimal.

### The product rule

Which provider an assistant uses, and whether that assistant is a second user, are **two
independent settings**. An ordinary assistant configured with Claude P must not lose its
approval-gated tools for the sole reason that it is not a `LOCAL_SECOND_USER`. `RESUME_COMMAND`
keeps its historical semantics, including its historical limitation; `IN_FLIGHT` must not require
a second user. The fix is explicitly *not* to make the assistant a second user.

### What the audit found

Exactly three layers referenced `LOCAL_SECOND_USER` anywhere near approval:

| Layer | Where | In the barrier write path? |
|---|---|---|
| `persistPendingBarrier` | `SecondUserApprovalLifecycle` | **Yes** — a `require` |
| `persistPendingBarrierInCurrentAuthorityTransaction` | `SecondUserApprovalLifecycle` | **Yes** — the same `require` |
| `isSecondUser` gating `pendingTools` | `ChatService` | Yes, but on the **`RESUME_COMMAND`** path only |

and every other candidate was checked rather than assumed:

- **DAO** — `PendingToolApprovalDao` has no subject filter in *any* query. `getExact`,
  `getLatestForToolCall`, `getPendingForConversation`, `observePending` and `getAllPending` are all
  keyed on conversation, tool call and approval identity.
- **Pending UI** — the card renders from `approvalState is ToolApprovalState.Pending` plus a
  non-null `onToolApproval`. That callback is wired **unconditionally** in `ChatPage`.
  `SecondUserPresentationRuntime` does filter, but it is a *different* surface — the desktop pet
  and system-assistant sessions — not the chat approval card.
- **`resolve`** — no subject precondition; only the exact-identity checks.
- **Capability policy** — `DefaultCapabilityPolicyEngine` returns **`Abstain`** for
  `LOCAL_ASSISTANT`, so the capability layer raises no objection for an ordinary assistant.
- **`ToolExecutionGate`** — its two `LOCAL_SECOND_USER` branches *grant extra* autonomy to a second
  user or deny a *stale* one. Neither gates an ordinary assistant.
- **`GenerationHandler`** — its two `LOCAL_SECOND_USER` checks are secret-egress and
  provider-binding concerns, not approval.

So the restriction was never systemic. It was one precondition, duplicated at two call sites — and
the third row belongs to the flow whose behaviour was required to stay unchanged.

### The fix

One named predicate replaces both inline `require`s:

```kotlin
owner.subjectType == SubjectType.LOCAL_SECOND_USER ||
    continuationMode == ApprovalContinuationMode.IN_FLIGHT
```

`RESUME_COMMAND` returns exactly what the old `require` returned, including the same
`second_user_approval_owner_required` message, so that flow is untouched. `IN_FLIGHT` is admitted
for any subject type, because what it needs is the exact approval identity, not a second-user
profile.

Nothing about *what may run* widens: approval is still required, the gate and the assessor still
run, the exact-identity checks on the approve path still apply, a decision that never arrives still
ends the call without executing it, and local reads still go through the existing policy. No
provider-specific UI, no second approval database, no Room schema or version change.

### The honest limit on its evidence

`persistPendingBarrier` needs an `AppDatabase` and five collaborators, and this module's unit tests
have no in-memory Room or Robolectric harness, so its *body* is not exercised. What is exercised is
the predicate that was doing the blocking — `ApprovalOwnerAdmissibilityTest`, 6 tests, covering
every `SubjectType` entry rather than a spot-check, both modes, and that the owner's identity
fields are neither required to change nor discarded. The persistence that follows the predicate is
**not** verified by that test, and the test says so rather than implying otherwise. Closing that gap
needs a Room test harness that does not exist yet.

### What this means for C2

C2 no longer has to distinguish "needs approval" from "can publish a card". For an ordinary Claude P
assistant it publishes through the existing card and approval UI, binds the exact identity, waits on
`InFlightApprovalWaiters`, continues in the same generation on approval, creates no resume command,
and fails closed on deny, cancel or timeout. **The subject type decides nothing.**

## 11.6 What remains

Phases B–F are **not** implemented. The snapshot is still closed and the production host is still
`NONE`, which is the safe state the plan requires: Claude still cannot see a tool Android cannot
answer.

1. **B — the production `ClaudePToolBridgeHost` and DI.** Catalog assembly, the bindings, the
   run-control registry, the status mapping, the publication seam and the execution host all
   exist; what does not exist is the one production object that composes them, and the Koin
   binding that makes it the only host. It must fail closed on a missing context, catalog or
   binding, and keep the text path byte-identical.
2. **C1/C2/C3 — the three execution paths**, through `DefaultToolRuntime`, the existing approval
   lifecycle and the existing `McpManager`. C2's subject constraint was removed in `a232730f`
   (§11.5): an ordinary Claude P assistant uses the same card, the same approval UI and the same
   `IN_FLIGHT` waiter as any other, with no second-user requirement.
3. **D — lifecycle and query**, including the four cancel phases, timeout, disconnect and replay,
   over the real `ToolExecutionHandle`. `ClaudePToolRunControls` already exists for this.
4. **E — snapshot activation**, only after B–D are tested. Commit order must keep the snapshot
   closed in every intermediate commit, as this batch does.
5. **F — tests and the workflow gate.** Every new class must be added to the `--tests` filter, the
   `REQUIRED` XML list and the instrumentation whitelist, under the existing
   tests>0 / failures=0 / errors=0 / skipped=0 gate.

The local toolchain now being available (§11.1) materially changes the cost of 1–5: they can be
compiled and executed here, iteratively, instead of being written blind. That is the single largest
change in this batch to what the next session can do, and it is why the correction in §11.1 is
recorded as prominently as it is.

## 11.7 Boundary compliance

- No Server file read or written; the contract of record is unchanged.
- No Room schema, version, migration or workflow change; `app/schemas/**/52.json` untouched. The
  workflow file was not touched at all in this batch.
- No new runtime dependency; no build file or version catalog touched.
- No push, CI dispatch, PR, tag, release or deploy. No VPS connection. No model call. No tool side
  effect. No phone. No M3 work.
- `git diff --check` passes; the only working-tree entry is the pre-existing untracked
  `web-ui/bun.lock`; `c6f84fc4` and `5fceaf27` remain ancestors and neither was rewritten.

## 11.8 Requested verdict

**Not** `M2-B complete`, and this batch does not claim it. It closes the two gaps §10.11 named —
both of them, with executions rather than arguments — and adds one pure seam to do it. Phases B–F
remain, one blocking design constraint is now recorded that would otherwise have been discovered
mid-implementation, and the environment can now compile and run the work that is left.

---

# 12. The B batch, and the integration map C needs

- Base of this batch: `7eff85f0`
- Commits this batch: `5dec9798` (the production host, surface closed)
- Pushed: **no**. No CI dispatch, no deploy, no VPS, no model call, no phone.
- Snapshot: **still closed.** `offerCatalog = false` at the one construction site.

## 12.1 What landed

`ClaudePToolBridgeHostImpl` composes what the earlier batches built — the catalog assembly and
`BridgeExecutionBindings` — and is registered as the single
`single<ClaudePToolBridgeHost>`, so the provider resolves it instead of keeping its `NONE` default.
`ClaudePToolBridgeHostImplTest` runs 13 tests, 0 skipped, 0 failures, 0 errors.

`prepare` fails closed on every missing identity — no context, an incomplete one, an unrecognised
origin token, a device that cannot name itself — each a distinct locally-recorded refusal. The
origin mapping is an exact match on the enum's own `name`; `"localchat"`, `"LOCALCHAT"` and
`" LocalChat "` are all refused, which is what makes the bridge's exact-match rule meaningful.

The one test worth calling out is that the closed surface is asserted **against an open host that
really does produce a catalog and stage a plan**. Without that pairing, "closed offers nothing"
would pass for the wrong reason — a broken assembly — and the activation commit could silently
still offer nothing while every test stayed green.

## 12.2 The integration map for C, established by reading the real code

This is the part worth keeping. It was gathered by reading the actual call sites, and it is what
made the obstacle in §12.3 visible.

### The template the host must copy, not reinvent

`GenerationHandler`'s single-tool site (`GenerationHandler.kt:1984-2031`) is the canonical shape:
`DefaultToolRuntime.execute(ToolExecutionPlanRequest(...))` with

- `executionContext = ToolExecutionContext(runId, conversationId, assistantId, callOrigin,
  commandId, toolCallId, workspaceId, workspaceCwd, capabilitySubject,
  selectedPrivilegedConversation)`;
- `startableTool = startableTools[name] ?: toolStartableResolver.resolve(toolDef, owner)`, and
  `legacyExecute = { element -> toolDef.execute(element.jsonObject) }` always supplied;
- `wallClockBudgetMs` = the remaining turn budget;
- `preExecutionGate = { when (gate.evaluate(...)) { Allowed -> Allow; is Denied -> Deny("tool_blocked", reason) } }`;
- `toolSchemaFingerprint = ToolCatalogSnapshot.fromDefinitions(listOf(toolDef)).entry(name)?.schemaFingerprint`.

The result is consumed as `runtimeResult.output` and copied onto the existing `UIMessagePart.Tool`
via `copy(output = ...)`; `ToolExecutionPlanResult` never carries `approvalState`.

### C3 is nearly free, and that is the good news

**An MCP tool's `Tool.execute` already calls `McpManager.callTool(serverId, tool.name, args)`**
(`ChatService.kt:3889-3891`). The assembled list the host already receives in `prepare` therefore
carries a working MCP dispatcher, and the host can run MCP tools through the *same* `legacyExecute`
path as every other tool. It never needs a reference to `McpManager`, never sees a server id it did
not receive as a closure, and never touches OAuth state or a token. The "OAuth/token/config must
never reach the catalog, arguments, Server, Worker, Claude Code or logs" requirement is satisfied
by *not handling them at all* rather than by filtering them — which is the stronger form.

`invocation.toolNameForRuntime` is the raw app tool name: `BridgeToolCatalog` sets
`displayName = candidate.name`, and the adapter binds `toolNameForRuntime = entry.displayName`. So
the lookup key for the plan's tool list is exact and already correct.

### The two things that are not free

**Subject resolution.** `ToolExecutionContext.capabilitySubject` must be the *real* subject, and
passing `null` is not a safe default: `ToolExecutionGate` skips the whole capability branch when it
is null (`ToolExecutionGate.kt:452`), which for a second-user conversation would skip the
selected-privileged-conversation check and allow *more*, not less. The app's rule lives in
`ChatService.capabilitySubjectFor` (`ChatService.kt:1055-1095`) and is private. The host needs it,
so either it is exposed or the host takes a provider wired to the same rule — and it must consult
`SecondUserAuthorityRegistry` for the privileged case rather than re-deriving a second-user subject
from a string, because that is precisely the "make the assistant a second user" substitution the
brief forbids.

**Wiring weight.** The host will need `DefaultToolRuntime`, `ToolExecutionGate`,
`ToolStartableResolver`, `ClaudePToolRunControls` (already registered at `DataSourceModule:1252`),
the approval lifecycle, the in-flight waiters and the conversation repository. The provider is
registered inside a `single { ProviderManager(...).also { ... } }` block, so any cycle between
those and `ProviderManager` surfaces as a Koin failure at startup, not at compile time.

## 12.3 The obstacle in C2, stated before writing it rather than after

C2's ordering is: publish the `Pending` card → the user can see it → `persistPendingBarrier(...,
IN_FLIGHT)` → await → execute on approve. The card is *not* written by the host: the host publishes
it through `ClaudePToolStatusSink`, whose implementation in the provider turns it into a
`MessageChunk` and emits it into the stream (`ClaudePProvider.kt:904-916`). ChatService applies
that chunk to the conversation graph asynchronously in its collector.

But `persistPendingBarrier(conversation, owner, tools, ...)` takes a `Conversation` and persists it
**inside its transaction** (`SecondUserApprovalLifecycle.kt:211-291`), and the projection it writes
is what the approval UI resolves against. So the barrier must be persisted against a conversation
that *already contains* the pending assistant message — and the host has no way to know that the
emit it just performed has been applied.

The normal path does not have this problem because ChatService does both in one `applyRunUpdate`
block (`ChatService.kt:3996-4074`): it persists the card into the graph and the barrier in the same
authority transaction, and only then publishes. The in-flight path cannot reuse that, because its
generation is still running and its `execute` is suspended inside the provider's collection.

This is the concrete form of what §7 called "the hard half and the reason this was not rushed", and
§9.4.1/§9.4.2 were written to prepare for it. It needs a designed handshake — most plausibly a way
for `execute` to await confirmation that its published part has been applied to the authority
graph, rather than assuming it — and that handshake should be designed and reviewed before the code
is written, not discovered by a failing test.

**Nothing in this batch assumes otherwise.** With the surface closed, `execute` is unreachable and
answers `FAILED` if a wiring bug ever reached it, which claims no execution and no stop.

## 12.4 What remains

Phases C–F, with C3 and C1 being the tractable half and C2 requiring the handshake above.

1. **C1 — local read.** Through `DefaultToolRuntime` with the real subject and a re-assessment
   against the real arguments. Ready to write.
2. **C3 — MCP.** The same path; the dispatcher is already inside the `Tool` closure. Ready to write.
3. **C2 — local write approval.** Blocked on the §12.3 handshake.
4. **D — lifecycle and query**, over the real `ToolExecutionHandle` via `ClaudePToolRunControls`.
5. **E — snapshot activation**, only after B–D are tested, as the last independent commit.
6. **F — tests and the workflow gate**, including the instrumentation tests the plan calls for
   (ordinary assistant `IN_FLIGHT` persists a barrier; second user `RESUME_COMMAND` still works;
   ordinary assistant `RESUME_COMMAND` still refused; approve/deny use exact identity; no wrong
   resume command). Those need a managed device and a real `AppDatabase`, which is exactly what
   `migration-instrumentation.yml` already provides.

## 12.5 Boundary compliance

- No Server file read or written. No Room schema, version or migration change.
- No new runtime dependency; no workflow change in this batch.
- No push, CI dispatch, PR, tag, release or deploy. No VPS connection. No model call. No tool side
  effect. No phone. No M3 work.
- `snapshot` remains empty; `tool_snapshot` is still never sent; the production host offers nothing.
- `git diff --check` passes; the only working-tree entry is the pre-existing untracked
  `web-ui/bun.lock`; `c6f84fc4`, `5fceaf27` and `a232730f` all remain ancestors.

---

# 13. The M2-B batch: pending publication acknowledgement

This batch implements the handshake §12.3 said had to be designed and reviewed before C2 could be
written. It closes §12.3. It does **not** attempt C2 itself.

**In scope.** The publication receipt primitive; the provider ↔ ChatService transaction
acknowledgement; the continuation-mode metadata that lets ChatService tell an in-flight publication
from an ordinary pending approval; the `isSecondUser` gate replaced by that mode; deterministic
concurrency tests.

**Out of scope, and unchanged.** Production `ToolRuntime` / `McpManager` / execution gate wiring;
real tool execution; the cancel lifecycle; snapshot activation; `ToolExecutionHandle`. The
production host still offers no catalog, so `execute` remains unreachable in production.

## 13.1 The audit, done before any code was written

The instructions required the real call order to be established first, and one of the five
questions turned out to decide the whole design.

```
Server tool.invoke
  → pumpFrames                       ClaudePProvider.kt:766
  → ClaudePToolFrameHandler.onInvoke ClaudePProvider.kt:801
  → host.execute(invocation, publishingStatusTo(emit))   ClaudePProvider.kt:807
        → status.publish(…)  →  emit(chunk)              ClaudePProvider.kt:916
```

**1. What the status sink is.** A `fun interface` with a `suspend publish(...)` returning
`Accepted | Refused` — not a Flow, not a Channel. Its implementation
(`ClaudePProvider.kt:907-920`) emits a `MessageChunk` carrying the tool part and catches failures.

**2. Does the provider wait on the same coroutine as ChatService? No — and this is the finding that
makes the batch possible.** `ProviderTurnRunner.kt:286` creates
`Channel<MessageChunk>(capacity = Channel.UNLIMITED)`; the provider flow is collected in a child
`async` (`:287-360`), while `onChunk` — which is what reaches `emit(GenerationChunk.Messages)` and
then ChatService's collector — runs on the **caller** coroutine (`:386-388`). `host.execute` runs on
the provider child. So a `publish` inside `execute` is a non-blocking `Channel.send` that returns
**before ChatService has looked at the chunk**, and the two sides are genuinely different
coroutines.

**3. Where the pending part is applied.** `ChatService` applies `UIMessagePart.Tool(Pending)` inside
`applyRunUpdate { … }` under `chunk.persistenceBarrier == PENDING_APPROVAL`; the barrier itself is
written by `authority.checkpointWaiting(...)` → `ProductionRuntimeRunAuthority.checkpointWaiting`
(`ProductionRuntimeCommandAuthority.kt:185`), whose `approvalMutation` calls
`persistPendingBarrierInCurrentAuthorityTransaction`.

**4. How the transaction outcome is observed.** `checkpointWaiting` returns only after commit
(`waitingCommitted.set(true)`), and `ChatService` sets `waitingAuthorityCommitted = true`
immediately afterwards (`:4152`). A rollback is an exception. There is no polling and no callback.

**5. The ring wait.** There is none, for the reason in (2): the host suspends on a receipt while
ChatService's collector — a different coroutine — processes the chunk and commits. Had the
provider run its callback inline through a rendezvous channel, the cycle the instructions warned
about would have been real.

### 13.1.1 The second finding: the bridge path raised no barrier at all

`GenerationPersistenceBarrier.PENDING_APPROVAL` had exactly one producer,
`GenerationHandler.kt:1697`, in the ordinary tool loop. A bridged call never reaches it — its
generation does not end while the peer waits — so `ChatService`'s barrier branch was **unreachable
from the bridge**, and a receipt would have had nothing to complete against. Fixing this was a
prerequisite, not a nice-to-have, and it is why the batch touches `GenerationHandler`.

## 13.2 The deadlock question, answered

The instructions said to stop and ask if the topology could not avoid a cycle without changing the
Server contract or adding a dependency. It could, so nothing was stopped.

- No `delay`, no sleep, no DAO polling, no `GlobalScope`, no UI-thread blocking, no unbounded
  growth. The only deadline is the caller's own, and it is the contract's existing
  `BridgeLimits.MAX_DEADLINE_MS` rather than a new number.
- The one Channel involved is pre-existing and belongs to `ProviderTurnRunner`; this batch adds no
  channel, no job and no scheduler.
- ChatService can process a pending chunk while the host is suspended because the host's suspension
  is on the *provider* coroutine, not on the collector's.
- No terminal path leaks: `release` runs in a `finally` on every exit from the host's publication
  step, including cancellation.

## 13.3 The receipt

`ClaudePToolPublicationReceipts` (`data/claudep/ClaudePToolPublicationReceipts.kt`).

- **Exact identity.** `ClaudePToolPublicationId(generationId, toolCallId, invocationIdentity)`.
  `generationId` is the app's run identity — the same value `GenerationRunControl.runId`,
  `ClaudePToolGenerationContext.runId` and the host's plan all carry. It is deliberately *not* the
  Server's own `generationId`: the conversation authority never learns that value, and a key that
  required it could not be rebuilt on the side that answers. Generation-exactness comes from the
  run id plus the fact that an entry only exists between `begin` and the generation's close.
- **The approval identity is not in the key.** `approvalId` and `executionId` are produced by the
  single authoritative derivation site (`SecondUserApprovalLifecycle`) inside the transaction and
  travel back in `Committed`. The host never derives them — as instructed, and it matters: a second
  derivation is a second answer to "which approval is this?", and the one that reaches
  `InFlightApprovalWaiters` has to be the one the tap will match.
- **Exactly once.** The live entry is removed under the same lock that settles it, so a duplicate
  finds nothing and changes nothing. A separate bounded `settled` set stops a settled id being
  re-armed, which is how a stale duplicate would otherwise release a *new* request.
- **Bounded.** `maxEntries` (64) caps both maps, oldest-first. An evicted live wait is *completed*
  as `EVICTED`, not dropped, so a publisher fails closed rather than hanging.
- **In-process only.** No serialization, no wire, no prompt, no fingerprint, no Room, no log —
  `toString()` renders SHA-256 pseudonyms so a debugger or a test failure cannot leak the
  identities.

### 13.3.1 Why the arguments are not part of the invocation identity

They were the obvious thing to hash and are the one thing that cannot be: `RuntimeSecretRedactor`
rewrites `UIMessagePart.Tool.input` *before* the chunk is emitted
(`RuntimeSecretRedactor.kt:54-59`), while the publisher hashes what it sent. For any
approval-gated call whose arguments contain a known secret the two digests would differ, so the
receipt would never complete for exactly the calls where asking the user matters most. The identity
is therefore the **runtime tool name**, which survives every transform on that path. The call is
already named exactly by the generation and tool-call fields.

## 13.4 The continuation mode, and the gate it replaces

`ChatService` decided whether to write a barrier with `isSecondUser`, which is wrong for the
in-flight path and right for the resume path. The instruction to fix it without deleting the gate
and without keying on the provider is implemented as an explicit mode.

- `ClaudePToolStatusUpdate.pendingContinuation` — a token the **app** declares when it publishes,
  carried verbatim through `MessageChunk.pendingApprovalContinuation` (both `@Transient`) and mapped
  by `ApprovalContinuationMode.fromWireOrNull`.
- `GenerationChunk.Messages.continuationMode` (`@Transient`) carries it into `ChatService`.
- `GenerationHandler` maps the token by **exact match**. An unrecognised or absent token raises no
  barrier at all — the card stays on screen and nothing is armed, which is the fail-closed shape.
- `GenerationHandler.kt:1697` now states `RESUME_COMMAND` explicitly rather than relying on a
  default, so "missing" really is unknown.
- In `ChatService`: `RESUME_COMMAND` keeps the original `isSecondUser` rule **verbatim**, including
  that `pendingTools` is only computed when something will own it (the mapping asserts a schema is
  present, and asserting that for a card being left alone would turn a working legacy path into a
  crash). `IN_FLIGHT` is admitted for every subject type and uses `runControl.runId` with **no**
  command-id fallback. `null` commits nothing.

Nothing infers the mode from `provider == ClaudeP`.

## 13.5 Where the receipt is completed, and nowhere else

`ChatService.kt:4112-4215`. The receipt is completed strictly after `checkpointWaiting` returns and
`waitingAuthorityCommitted = true` is set. Three other outcomes are answered, and all three are
refusals:

| outcome | what the publisher is told |
|---|---|
| transaction committed, barrier names the card | `Committed(approvalId, executionId)` |
| rollback / exception / cancellation | `Refused("approval_authority_rollback")` |
| committed, but no barrier names the card | `Refused("approval_barrier_missing")` |
| no authority transaction at all | `Refused("approval_authority_transaction_absent")` |

An `emit` that returned, or a `Channel.send` that succeeded, is never treated as a commit.

## 13.6 The DI boundary

One Koin `single` (`DataSourceModule.kt:1253-1261`), shared by the host and `ChatService`, plus one
constructor parameter on each. This is the narrow exception the batch was granted: a request created
on one side and completed on the other cannot work without one shared instance, and two instances
present as a call that sits out its whole deadline instead of failing loudly.

It grants no capability. Not wired: `ToolRuntime`, `McpManager`, the execution gate, the snapshot.
`offerCatalog` remains `false` (`DataSourceModule.kt:1567`), so no `tool_snapshot` is sent, the
Server registers no bridge tool, and `execute` stays unreachable in production.

## 13.7 Verification, and the limits of it

Coverage is stated against the fifteen cases the batch was required to cover. Deterministic means
`CompletableDeferred` handshakes and registry state; there is no `sleep` in any of these, and every
wait that crosses a coroutine is wrapped in `withTimeout` so a reintroduced cycle **fails** rather
than hangs.

Run with the worktree's own Android SDK (`D:\Android\Sdk`), `:app:testDebugUnitTest --tests
"me.rerere.rikkahub.data.claudep.*"` — **91 tests, 0 failures, 0 errors, 0 skipped** across the six
claudep suites, of which the two this batch writes are `ClaudePToolPublicationReceiptsTest` (20) and
`ClaudePToolBridgeHostExecuteTest` (17, five of them new).

| required case | where it is covered |
|---|---|
| 1. apply-before-host-await | `a receipt committed before the publisher waits is returned immediately` — deadline of 50 ms, so a registry that only completed *present* waiters returns `TIMEOUT` |
| 2. host-await-before-apply | `a receipt committed while the publisher is suspended releases it` — the authority commits from another thread against a registered, suspended waiter |
| 3. approval immediately after commit, before await | same two tests, whose point is that neither order is special |
| 4. transaction rollback | `a refused publication never becomes a commit`; host: `a card whose barrier rolled back is not run` |
| 5. duplicate receipt completion | `a duplicate completion changes nothing` |
| 6. wrong publication id | `an id that was never begun settles nothing` |
| 7. wrong generation / toolCall / tool identity | `a completion for another identity leaves the right waiter untouched`, `the tool name is part of the identity` |
| 8. timeout | `a wait that outlives its deadline is abandoned and releases nothing` |
| 9. cancel while awaiting | `a cancel ends the wait and a later commit does not reopen it` |
| 10. generation terminal / registry close | `a generation close abandons only its own publications`, `a registry close ends every wait` |
| 11. receipt success, runtime still not called | `an approval-gated call publishes an in-flight card and still runs nothing` |
| 12. card and barrier atomicity | see the limit below — contract-tested, **not** database-proven |
| 13. receipt never enters wire / serialization / log | `the continuation never enters a serialized provider chunk` (real bytes), `the generation chunk declares the continuation transient` (declaration, see below), `a publication id does not print what it holds` |
| 9. cancel while awaiting | `a cancel ends the wait and a later commit does not reopen it`; host: `an unanswered publication is waited on and a cancel ends it` |
| 14. no second model generation | `the publisher declares the continuation, and it is the in-flight one` — the `IN_FLIGHT` mode is what `isInFlightContinuation()` reads to suppress the resume command (`ChatService.kt:2830`, `:2900`). End-to-end proof needs a device. |
| 15. no coroutine / deferred / registry residue | `no path leaves an entry behind once released`; `committing does not require the lock a suspended wait holds`; `an evicted publication fails closed rather than hanging`; `remembered terminal ids are bounded too` |

### 13.7.1 What is *not* proven here, stated plainly

**Case 12 is not proven.** These are JVM unit tests on `:ai` and `:app`. No in-memory Room harness
exists for `ChatService`, and `RuntimeRunAuthority.checkpointWaiting` needs an `AppDatabase` and
five collaborators. What is proven is that the *registry* makes the four outcomes distinguishable
and that the host fails closed on all of the non-committed ones. That "the `Pending` card and the
`IN_FLIGHT` barrier commit together or not at all" is a property of the existing authority
transaction and of where the completion is placed — verified by reading, not by a passing test.
Nothing in this section should be read as Room evidence.

**Case 14 is only proven at the token.** The `IN_FLIGHT` barrier is written, and
`isInFlightContinuation()` is the existing, separately tested mechanism that suppresses the resume
command. That no second model generation is started has not been exercised end to end.

**The ChatService branch itself is not unit-tested.** The `when (continuation)` block, the atomic
placement of the completion after `checkpointWaiting`, and the `isSecondUser` → mode replacement
are all inside a 5 000-line Android service with no test harness. §13.8 names what a managed device
has to confirm.

**One case-13 assertion is on a declaration, not on bytes.** `GenerationChunk.Messages` turns out
not to be `@Serializable` at all — the annotation is on the interface and the subclass has no
serializer, so the hierarchy cannot actually be encoded. The first run of this suite proved it by
throwing `Serializer for subclass 'Messages' is not found in the polymorphic scope of
'GenerationChunk'`. Rather than add a subclass serializer so a test could encode a type that
nothing encodes, the test asserts the `@Transient` declaration. The `MessageChunk` half — the
carrier that really does cross the provider boundary — is still checked by serializing one. The
vestigial `@Serializable` on `GenerationChunk` is noted here rather than fixed, because fixing it
is not this batch's business.

### 13.7.2 Compilation, and the two pre-existing baseline failures

Both modules were compiled with the worktree's own Android SDK (`D:\Android\Sdk` via
`local.properties`): `:ai:compileDebugKotlin` and `:app:compileDebugKotlin`.

Two failures are **pre-existing** and excluded from this batch's regression conclusion. Neither is
fixed here. Had either changed shape, or any new failure appeared, this batch would have stopped —
and it did stop to check when the second one surfaced.

**(a) `P2CapabilityCatalogTest`** — `:app`, already known and recorded before this batch. Its
subject is the capability catalog, and this batch touches no capability file:

```
tests="21" skipped="0" failures="1" errors="0"
java.lang.AssertionError: Unclassified system-assistant tools: [transient_conversation_search]
```

**(b) `ClaudePConformanceCorpusTest."corpus revision is bound to the specification it was
derived from"`** — `:ai`, not previously reported, and it surfaced only because this batch ran the
`:ai` provider suites. It compares a recorded SHA-256 against `claudep/02-wire-protocol-v1.md`.

This one was checked rather than assumed, because a wire-specification digest moving is exactly
what a batch that touched the provider must rule out. It is a **line-ending artifact of this
Windows working copy**, proven three ways:

```
recorded (corpus)  8ff5e6a5a6e494b7fe7546a918d55567c918623853f5fa7367ddb23094ba3e85
file as-is         6d53aa6393f63dbbfab28c28987ef14a47605aba1fb61b14fbb1ae5a8122ad66   (CRLF)
file, CR stripped  8ff5e6a5a6e494b7fe7546a918d55567c918623853f5fa7367ddb23094ba3e85   (LF)
```

The LF-normalized digest equals the recorded one **byte for byte**, and `git status`/`git diff`
report the file unmodified. So the specification is what the corpus was derived from, and the check
is failing on `core.autocrlf` alone. This batch cannot have caused it — it writes no markdown under
`claudep/`, and no code change can alter a file's bytes — and fixing it would mean writing to a spec
file or a working-copy EOL setting, neither of which is this batch's business.

`:ai` provider suites: **592 tests, 0 errors, 1 failure — that one.**

### 13.7.3 One pre-existing test changed meaning, and that is the batch working

`ClaudePToolBridgeHostExecuteTest.a call that needs approval is refused rather than run` passed
`ClaudePToolStatusSink.NONE` for an approval-gated call and expected an immediate refusal. On the
first run after this batch it **hung**, and the thread dump named it: the test worker was parked in
`runBlocking` for the call's own 30-minute `MAX_DEADLINE_MS`.

The cause is the change itself. `NONE` accepts everything, so the old test's expectation was "a
sink said `Accepted`, therefore publish is settled, therefore return now" — which is exactly the
inference the acknowledgement batch removes. After this batch a host that is told `Accepted` and
then never answered waits out the call's deadline before refusing. That is correct and fail-closed,
but the old assertion could no longer hold, so the test was not "fixed to pass": it was rewritten
around the refusal path it was actually trying to describe.

- The refusal case now uses a sink that `Refused` — nothing was shown, nothing can be tapped, no
  commit is coming — which is the fast, decided path, and the original four assertions are kept
  verbatim.
- A new test, `an unanswered publication is waited on and a cancel ends it`, covers the accepted-but-
  unanswered shape the old test accidentally described: the wait is entered (asserted from the
  registry's own state, via a `CompletableDeferred` handshake rather than a poll), it is
  cancellable, nothing runs, and the publication is released.
- `ClaudePToolStatusSink.NONE`'s own documentation now says that `Accepted` is not evidence of a
  commit and that handing this sink an approval-gated call costs the full deadline. The footgun is
  labelled where someone would reach for it.

No other test was affected: every other `NONE` call site in the suite uses a tool that needs no
decision, and the four new host tests all answer their receipts.

## 13.8 What a managed device still has to confirm

These need a real `AppDatabase` and the instrumentation workflow. They are the items §12.4 F called
for, narrowed to what this batch now makes reachable.

1. **Atomicity.** Force a failure after the `Pending` part is applied but before the barrier is
   written; assert the transaction rolls back, that no `PendingToolApprovalRecord` exists, and that
   the publisher observed `Refused` — not a commit and not a timeout.
2. **An ordinary assistant's in-flight barrier.** An ordinary (non-second-user) assistant with a
   Claude P provider and an approval-gated tool: assert the card appears, the barrier is written
   with `continuationMode = IN_FLIGHT`, and the receipt carries the authority's own `approvalId` /
   `executionId`.
3. **No second generation.** Approve that card and assert **no resume command is created** and no
   second `generation.start` is dispatched.
4. **The legacy path is untouched.** A second-user assistant in `RESUME_COMMAND` still resumes
   through a command, and an ordinary assistant in `RESUME_COMMAND` is still refused a barrier.
5. **A wrong-identity completion is inert.** Deliver a receipt for an adjacent id and assert the
   real waiter is neither released nor refused.
6. **Cold restart.** Kill the process with a card pending; assert the existing `IN_FLIGHT` recovery
   rules invalidate it and nothing resumes automatically.

## 13.9 Boundary compliance

- No production `ToolRuntime`, `McpManager` or execution gate wired; `ToolExecutionHandle` not
  touched; no snapshot activation; `offerCatalog` still `false`.
- No real tool execution. A successful receipt still answers the call `unexecuted` (`FAILED`),
  which is what actually happened — nothing ran.
- No `Server` file read or written; no Room version, schema or migration change; no new runtime
  dependency; the only DI additions are the one `single` and the two parameters named in §13.6.
- No push, CI dispatch, PR, tag, release or deploy. No VPS. No model call. No phone. No M3 work.
- `git diff --check` passes at each commit; `web-ui/bun.lock` remains untracked and untouched.

## 13.10 Commits

| commit | what |
|---|---|
| `d7eb1c75` | `feat(claudep): add the one-shot publication receipt registry` — the primitive alone, referenced by nothing |
| `d4473ab7` | `feat(claudep): acknowledge a pending card only after its barrier commits` — the plumbing, the mode, the gate, the DI |
| `9cbb776e` | `test(claudep): cover the publication receipt and the in-flight handshake` — 25 new tests, plus the two findings running them produced |
| *(this batch)* | `docs(claudep): record the acknowledgement batch` |

## 13.11 Requested verdict

**Stop here, at Codex review — this batch is not declared complete.** Three things specifically
warrant an adversarial read:

1. **The identity omits the arguments** (§13.3.1). The reasoning is that redaction makes hashing
   them wrong for the calls that matter most. If that trade is judged wrong, the fix is a different
   identity, not a different key.
2. **`generationId` is the run id, not the Server's generation id** (§13.3). This is forced by the
   authority never learning the Server's id; the generation-exactness argument rests on `begin` /
   `closeGeneration` lifecycle rather than on the id itself.
3. **The DI exception** (§13.6) is one binding wider than the batch's stated prohibition. It was
   granted explicitly, on the grounds that no shared instance means no acknowledgement; it wires no
   execution capability. If it is judged to exceed the grant, the alternative considered was
   hanging the registry off the already-injected `ClaudePToolRunControls`, which was rejected as
   giving that class a second responsibility.

---

# 14. The C-batch semantic hardening: identity, digest, corpus, classification

Scope of this batch was §1–§4 of the hardening instruction. **§5 (production DI and real
execution), §6 (instrumentation) and §7 (snapshot activation) were not started** — see §14.5.
`offerCatalog` remains `false` (`DataSourceModule.kt:1567`).

## 14.1 The Server generation and the run are now separate types

Commit `f272e567`. The receipt called the run id `generationId`, which is the one name it must not
have — the Server assigns a generation id at `generation.start`, Android has a run id from
`GenerationRunControl`, and neither is a stand-in for the other.

- `ClaudePToolPublicationKey(runId, toolCallId, invocationIdentity)` is the half the conversation
  authority can rebuild, and it is what `complete`/`refuse` are addressed by. It **cannot** carry
  the Server's id: that side never learns it.
- `ClaudePToolPublicationInvocation(serverGenerationId, runId, toolCallId, toolName, argsDigest)` is
  the publisher's immutable record.
- `openGeneration` pairs the two once and refuses a contradiction **in both directions**; `begin`
  refuses a publication whose halves do not match that pairing; `closeGeneration` retires the
  pairing with the execution binding. Nothing derives the pairing from a conversation id, an
  assistant id or "the run that is executing now" — those find a run, none of them proves one.

Tests: `a publication is refused before its generation is bound`, `a contradicted pairing is refused
in both directions`, `a publication for a mismatched pairing is refused`, `closing a generation
retires the pairing`, plus host-level `opening a generation pairs the Server generation with its
run` and `closing a generation retires the pairing`.

## 14.2 The argument digest stays on the publisher's side

The ledger already refused a repeat carrying different arguments (`InvocationDecision.CONFLICT` via
`BridgeBinding.invocationDigest`), so this batch records rather than reinvents it:

- `argsDigest` is taken from the **Server's original invocation** (`invocation.argsDigest`), never
  recomputed from the conversation part — `RuntimeSecretRedactor` rewrites
  `UIMessagePart.Tool.input` before the authority sees it, so a digest taken from there would
  disagree with the ledger for exactly the calls that carry a secret.
- It is deliberately **not** in the key, which is why `redaction of the arguments does not change
  the key` passes: the completion still arrives, and the publisher's record still holds the
  original digest.
- `sameCallAs` compares every field including the digest, and the host re-asserts it against
  `request.invocation` after `Committed`. Nothing executes in this batch, so the guard is asserted
  while it is still checkable rather than load-bearing.
- An invocation whose own digest disagrees with the binding it carries is refused before anything is
  published — `an invocation whose digest disagrees with its binding is refused`.
- No raw arguments cross to `ChatService`; no credential scanner was added; `toString` redacts every
  field through `shortRef`, including the digests.

## 14.3 The corpus was a stale checkout, not a missing attribute

**The instruction's premise was wrong, and nothing was committed for this item.** The rule
`claudep/02-wire-protocol-v1.md text eol=lf` already exists at `.gitattributes:21`, and
`git check-attr` already reports `eol: lf` for it — adding another entry would have been a
duplicate no-op. The defect was a stale **working-tree** checkout: CRLF on disk, LF in the blob.

The two spec markdown files were re-materialized from the index. They are now CR=0, and
`claudep/02-wire-protocol-v1.md` hashes to `8ff5e6a5…`, matching the recorded
`protocol_spec_sha256` **byte for byte**. `ClaudePConformanceCorpusTest` passes 14/14. No corpus
content changed, no manifest was recomputed, and `git status` reports no change to those paths —
which is why there is no commit here. On a fresh checkout this file is already correct; only this
worktree was stale.

## 14.4 `transient_conversation_search` is withheld, not opened

Commit `37c5eea2`. The tool failed `P2CapabilityCatalogTest` because a rename did not finish: the
Second-User reader's list and read tools reuse their ordinary names and were covered by accident,
while this one was renamed to `transient_conversation_search` to break a collision with the ordinary
`conversation_search`, and no classification was updated.

It is added to `phase1UnavailableToolNames`, **not** to `backgroundToolNames` — and that is
behaviour-preserving rather than a fix that happened to work. `ToolExposurePlan.blockReason` refuses
`Phase1Unavailable` and `Unclassified` in exactly the same two places, so the VoiceInteraction
overlay stays closed to it and the ordinary `LocalChat` path (which returns before any
classification is consulted) is untouched in both directions. **Nothing about who can reach this
tool changed**; the withholding became a decision instead of an omission.

It is genuinely read-only, and that is deliberately not why it would be allowed: it reads
conversation content, so exposing it needs its own privacy decision. `ClaudePToolCatalogExposureTest`
pins the rule — the system-assistant surface never offers it (both overlay branches), the catalog
the Claude P host would freeze has no entry for it, the local path is unchanged, and no branch keys
on the provider's name.

## 14.5 What was **not** done, and why

§5 requires implementing a real `BridgeExecutionHost` (`abandonApproval`, `requestStop`,
bounded `awaitConclusions`), arming `InFlightApprovalWaiters` on a committed receipt, and wiring
`DefaultToolRuntime`, the gate and the subject resolver into the production host — new code on the
path that actually executes tools. §6 and §7 depend on it.

It was not started. The batch stopped at the last self-consistent commit with the snapshot closed,
as the instruction permits, rather than leaving a partially wired execution path — which is the one
kind of defect that could run a tool. §12.4 remains the plan of record for C1, C3, D, E and F.

## 14.6 Verification and boundary

- `:ai:compileDebugKotlin` and `:app:compileDebugKotlin` pass. `:app:testDebugUnitTest` over the six
  claudep suites plus `P2CapabilityCatalogTest`: **102 + 22 tests, 0 failures, 0 errors, 0 skipped**
  — including `ClaudePToolCatalogExposureTest` (4) and `ClaudePToolPublicationReceiptsTest` (28).
  `:ai:testDebugUnitTest` for `ClaudePConformanceCorpusTest`: 14/14.
- The §13 baseline failures are both resolved: `P2CapabilityCatalogTest` by §14.4, the conformance
  digest by §14.3.
- No DI file, gradle file, workflow or Room file was touched (`DataSourceModule` and `AppModule` are
  absent from the batch's diff). No push, no CI dispatch, no deploy, no VPS, no model call, no
  phone, no M3. `git diff --check` clean; `web-ui/bun.lock` still the only untracked entry.
- **Not `M2-B complete`.** §5–§7 remain.
