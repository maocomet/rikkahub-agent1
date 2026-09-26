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
