# M3-B — immediate and deferred continuation wiring

- Status: **items 1–4 wired and verified at the seam; item 5 (replay) not implemented. `auto` is
  unreachable. Awaiting Codex review.**
- Branch: `codex/claudep-cp1b-local`
- Base: `60e9e70a` → `3e04b869` (immediate continuation) → `54fd4716` (dispatch refusal) → the
  deferred-bind commit this report ships with
- Server: read-only, unchanged. No wire, schema, dependency or workflow change.
- Working tree: the pre-existing untracked `web-ui/bun.lock`, and nothing else.

**The completion line is deliberately not written.** Two things block activation, and one of them is
a product decision rather than a coding task. Both are in §3.

## 0. Read this first

`ClaudePSessionContinuationActivation.ENABLED` is **`false`**. While it holds:

- `dispatchesThroughClaudeP` answers `false` for every conversation, so no decision is produced,
  no barrier is written, no settlement is attached and no binding intent reaches the wire;
- every Claude P request keeps the `mode: "new"` shape it had before this batch, byte for byte;
- every non-Claude-P provider is untouched — the guard is behind both the switch and the provider
  check, and no other path reads any of the new state.

So the wiring below is real, compiled, unit-tested and **dormant**. That is the brief's own rule:
a barrier is only safe once every exit settles it, and one unhandled exit is not a degraded feature
but a conversation that can never be continued again.

## 1. What each item delivers

### Item 1 — immediate admission

`ProductionRuntimeCommandAuthority` is untouched. The barrier is written where the admission
transaction already persists the graph: `ChatService.runtimeAdmissionGraphProvider` now computes
the gate decision from the graph the transaction is about to commit, attaches the
`START_IN_FLIGHT` record to the anchor, and returns **that** conversation to be persisted. The
command row and the barrier therefore commit together or not at all.

The order the brief requires is structural rather than checked:

| step | where | why the order holds |
|---|---|---|
| barrier written | admission graph provider → `admission.admit` transaction | it *is* the graph the transaction persists |
| settlement attached | `handleMessageComplete`, immediately before `generateText` | nothing above that line reaches the network |
| dispatch | `generationHandler.generateText` | reached only after the line above returned |

A barrier that cannot be placed where the resolver reads it (`barricade` returns `null` — the anchor
is missing, or is not the **selected** variant of its node) refuses the admission outright, so no
request is ever sent for it.

`Decision.Refused` refuses the admission rather than the dispatch. Admitting a command the gate
refused would write a durable row whose only possible outcome is a later failure, and the user would
see the message appear before it was rejected.

### Item 2 — immediate terminal

Both result-bearing terminals now settle **the conversation the command's authority transaction
persists**, so the branch state and the command row cannot disagree about whether the turn ended:

- `handleMessageComplete` success → `Terminal.SUCCEEDED` → `BOUND` (or a refusal, which fails the
  save rather than recording a completion whose branch was never settled);
- `handleMessageComplete` failure → `Terminal.FAILED` → `FAILED_CLOSED`.

The result-less path was already routed through `finishGenerationWithoutResult` by the reviewed
seam; admission now supplies the settlement the run consumes
(`ClaudePSessionContinuationGate.settlementFor`), so cancellation, disconnect and "no trusted
terminal" settle as `INTERRUPTED` and a known model/protocol failure as `FAILED_CLOSED`.

No call site composes a continuation record: every write goes through the gate, and the authority's
terminal vocabulary is mapped in exactly one function (`terminalFor`).

### Item 3 — stale reconciliation

At the next admission for a conversation, `staleInFlight` asks the **planner** first — the refusal
it refines is `CONTINUATION_BLOCKED`, which is already the planner's answer for a branch whose model
may be running — and then confirms the state is `START_IN_FLIGHT`. The other blocked states are
deliberately not returned: `BIND_PENDING` waits for an answer that may still arrive, `INTERRUPTED`
waits for a user-triggered *bind* replay rather than a generation, and `FAILED_CLOSED` does not
recover.

- **A live run keeps the refusal.** `ConversationRuntime.isRunLive` is the only thing that knows a
  generation is still executing; the graph cannot tell a running generation from a process that
  died mid-turn. Superseding a running generation's barrier would erase the record that stops a
  second one from starting.
- **A stale start is superseded in its own slot**, in its own transaction, and the operation stops:
  `generation.start` is zero by construction, not by a later check.
- **A reconciliation that cannot write pauses the queue** (`ClaudePSessionAdmissionStop(pauseQueue =
  true)`), because the graph still claims a model may be running on a branch no run owns and
  dispatching onto that is what the barrier exists to prevent.

No background task, timer or heartbeat is added. The reconciliation happens only when the
conversation is next admitted.

### Item 4 — deferred bind

- No start is written at admission: a `Deferred` decision has no `admissionRecord`, and `barricade`
  returns the conversation unchanged.
- On a **successful** turn, `continuationCommit` calls `deferredPending` **inside** the transaction
  that commits the new variant, writing `BIND_PENDING`. The caller uses only the value it returns —
  conversation, `branchId`, `revision`, `record` and the `messageId` the record went to.
- `messageId` is new in this batch and is carried for a specific reason: the settlement that follows
  supersedes the pending record **in its own slot**, and that slot cannot be re-derived afterwards
  (`terminalTarget` answers "the last message with no record of this branch", which is a *different*
  message once the pending record is on it).
- **After** the transaction returns — never before — exactly one `session.bind` is sent, carrying
  the persisted `(generationId, branchId, assistantId)`.
- The answer settles through `settleDeferred` → `deferredSettlement`: `bound`/`already_bound` →
  `BOUND` at the pending record's revision (a supersede, not an advance); `conflict`,
  `candidate_unavailable`, `refused` and malformed → `FAILED_CLOSED`; `Unproven` → `INTERRUPTED`
  **keeping the same generation id**, which is the whole of what a later replay re-sends.
- A transport failure is mapped to `Unproven`, never to "the bind did not happen": the Server may
  have applied it before the socket died.

The generation id comes from the existing `ClaudePGenerationIdentityObserver`, wired to
`onClaudePGenerationAccepted` for the first time. A second, *different* id for one dispatch raises
rather than resolving — choosing either would bind a branch to a generation the app did not record
as the turn's.

## 2. Tests

New in this batch, all green:

| Class | Tests | Covers |
|---|---:|---|
| `ClaudePSessionAdmissionWiringTest` | 15 | barrier placement (selected-path only, missing anchor, no-record case), `settlementFor` per terminal, stale detection and supersede, and the close-after-terminal consequence (§3) |
| `ClaudePSessionContinuationAdmissionsTest` | 6 | first-decision-wins on repeated admission, forget, and the two `ClaudePSessionAdmissionStop` shapes |
| `ClaudePSessionContinuationDispatchTest` | 8 | the dispatch-shape audit, the emergency refusal and its stable code, and the "dispatches nothing" set |
| `ClaudePSessionDeferredBindTest` | 8 | the pending write's message identity, and the full bind vocabulary |

## 3. What blocks activation

### 3.1 A cancelled or failed turn closes the branch to the next ordinary message *(product decision)*

**Verified by execution**, in `a cancelled or failed turn closes the branch to the next ordinary
message` and its positive counterpart `a completed turn leaves the branch continuable`.

A `SendMessageCommand` appends its node at `selectIndex == 0`, which is not part of the selection
vector — so the next message a user types lands on **the same branch**, and that branch is now
`INTERRUPTED` or `FAILED_CLOSED`. Both answer `allowsNewGeneration = false`, so the next ordinary
send is refused.

The design says this on purpose: a cancelled or failed turn leaves the Server potentially holding
something this device cannot prove, and reading "we could not find out" as a free branch is exactly
what buys a second Claude session for a branch that may already have one. The documented way to
continue is a *new* branch — a regenerate or a fork.

But in RikkaHub "type the next message" **is** the ordinary continuation, and it does not create a
new branch. With `auto` enabled, one model failure or one user cancellation would make the
conversation unusable for ordinary sends. That is a product-level consequence, not a wiring defect,
and it is the state machine's decision rather than this layer's — so it is raised here rather than
changed unilaterally.

**This is the reason `auto` is not enabled.** It is not that a path is missing; it is that a
specified behaviour has a consequence the product owner has to confirm before it goes live.

### 3.2 Replay (item 5) has no seam to attach to

`ClaudePSessionContinuationGate.replayBeforeGeneration` exists and is tested at the orchestrator
level, but production could not call it:

- **No entry point.** The replay must run before admission (a branch in `INTERRUPTED` cannot be
  admitted), which means at a user action — `submitUserMessage` / `submitCommand`. Neither is
  currently wired to any continuation seam.
- **No connection identity.** §6.1's "at most once per active connection" needs a connection
  epoch. The negotiated id exists (`WssClaudePGatewayClient.negotiatedHello?.connectionId`) but is
  **private** and is only stamped onto outgoing frames; `ClaudePGatewayClient` exposes no accessor,
  and `ChatService` has no route to `ClaudePDevicePairingRepository`. Exposing one is an `ai`-module
  surface change, and inventing an app-side surrogate (a client instance identity) would be a new
  mechanism, not wiring.

Per the brief's own rule, `auto` stays off rather than shipping a replay with a guessed
once-per-connection rule.

### 3.3 The two emergency command shapes *(deferred known limitation, accepted)*

`InterruptCommand` and `InterruptRegenerateCommand` are `EmergencyCommand`s: they preempt a running
command, so they bypass `persistDurable` — and therefore the admission transaction that writes the
barrier — and then start a model generation anyway.

They are **refused by name** (`claude_p_continuation_emergency_not_admitted`) with
`generation.start = 0`, no fallback to legacy `mode: "new"`, and no continuation state or session
touched. The refusal is a value (`RunOutcome.Rejected(code)` at the command boundary), so the user
sees a named reason and nothing is dispatched; the guard at the provider call is a backstop in the
same vocabulary, not an assertion.

Reusing the ordinary admission seam for them is not possible without changing the continuation
design: an interrupt runs *while* the previous generation's `START_IN_FLIGHT` is on the branch, or
after that generation has been cancelled into `INTERRUPTED` — and both states refuse a new
generation by construction (§3.1). So the shape is out of M3-B acceptance scope and M4 does not
include these two operations, as agreed.

## 4. Verification log

| Check | Command | Result |
|---|---|---|
| `:app` compile | `./gradlew :app:compileDebugKotlin` | pass |
| `:app` full suite | `./gradlew :app:testDebugUnitTest` | **4268 tests, 1 skipped, 1 failure** — the pre-existing one below. 693 classes. |
| Whitespace | `git diff --check` | clean |
| Room schema / dependencies / workflows | `git status --porcelain` | untouched |

The single failure in the full suite is `HardlineSelfPreservationTest`, which is **pre-existing and
unrelated**: it asserts a release-variant fact (`me.rerere.rikkahub`) from a debug-variant build,
where `applicationIdSuffix = ".agenttest"` makes the assertion correctly fail. Established, not
assumed — `git diff --name-only` for these commits matches nothing in `HardlineCommandGuard`,
`SelfPreservationPolicy`, `HardlineSelfPreservationTest` or any `build.gradle*`. It is left as found.

### Not done

- **Item 5 (replay)** — §3.2.
- **Item 6 (the activation audit)** — partially: `ClaudePSessionContinuationDispatchTest` audits the
  dispatch *shapes* and proves each is admitted or refused by name, but the audit was not repeated
  over every call site after the replay work that did not happen.
- **Item 7 (enable `auto` and re-run)** — not reached; `ENABLED` is `false`.
- **No instrumentation test, device or emulator.**
- **No Server run, VPS or Worker.** `session.bind` has still never been sent to a real Server.
- **No model call, OAuth or CLI child.**
- **No push, no CI run, no deploy, no usage UI, no M4.**

---

Items 1–4 are wired, compiled and unit-verified; item 5 is not implemented and `auto` is
unreachable. The completion line is **not** written.
