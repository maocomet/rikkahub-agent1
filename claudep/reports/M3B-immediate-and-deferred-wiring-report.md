# M3-B — session continuation wiring, and where activation stands

- Status: **items 1–5 wired and verified at the seam. `auto` is NOT enabled. Awaiting Codex
  review.**
- Branch: `codex/claudep-cp1b-local`
- Base: `60e9e70a` → `3e04b869` (immediate) → `54fd4716` (dispatch refusal) → the deferred-bind
  commit → the replay commit this report ships with
- Server: read-only, unchanged. No wire, schema, dependency or workflow change.
- Working tree: the pre-existing untracked `web-ui/bun.lock`, and nothing else.

**The completion line is deliberately not written.** The activation audit (§4) found one dispatch
entry that neither holds a barrier nor is refused by name, and it is in a path this batch did not
write. §4.3 states it precisely. Everything else the brief asked for is in.

## 0. Read this first

`ClaudePSessionContinuationActivation.ENABLED` is **`false`**. While it holds:

- `dispatchesThroughClaudeP` answers `false`, so no decision is produced, no barrier is written, no
  settlement is attached, no replay is attempted and no binding intent reaches the wire;
- every Claude P request keeps the `mode: "new"` shape it had before this batch, byte for byte;
- every non-Claude-P provider is untouched — the guard sits behind both the switch and the provider
  check, and no other path reads any of the new state.

## 1. Items 1–4

Unchanged from the previous revision of this report; the summaries below are the short form, and
`ClaudePSessionContinuationGate`, `ClaudePSessionContinuationDispatch`,
`ClaudePSessionContinuationAdmissions` and `ClaudePSessionBindReplays` carry the full reasoning.

| Item | What lands |
|---|---|
| 1. immediate admission | The barrier is written into the conversation the command's admission transaction already persists, so the command row and `START_IN_FLIGHT` commit together. The settlement is attached immediately before `generateText`, and nothing above that line reaches the network. A barrier that cannot be placed where the resolver reads it refuses the admission. |
| 2. immediate terminal | Both result-bearing terminals settle the conversation the authority transaction persists — success → `BOUND`, model/protocol failure → `FAILED_CLOSED` — and the result-less path settles through the reviewed `finishGenerationWithoutResult` seam (`INTERRUPTED` for cancel/disconnect/no-trusted-terminal). No call site composes a record. |
| 3. stale reconciliation | At the next admission, a `START_IN_FLIGHT` this process owns no run for is superseded in its own slot, in its own transaction, and the operation stops with `generation.start = 0`. A live run keeps the refusal. A reconciliation that cannot write pauses the queue. No timer, task or heartbeat is added. |
| 4. deferred bind | No start at admission. A successful turn writes `BIND_PENDING` in the transaction that commits its variant, then sends exactly one `session.bind` — after that transaction returned, never before — and settles the full answer vocabulary: `bound`/`already_bound` → `BOUND`, the three refusals and a malformed reply → `FAILED_CLOSED`, a dropped connection → `INTERRUPTED` keeping the same generation id. |

## 2. Item 5 — replay

### 2.1 The connection epoch

`ClaudePConnectionEpoch` is a narrow `ai`-layer value: an opaque, monotonically increasing **local**
token, minted by the transport at the one place a handshake completes and reported by
`ClaudePGatewayClient.connectionEpoch()`.

It is deliberately **not** the Server's connection id. That value is the Server's identity for the
socket — it appears on the wire and it names the device's connection to a remote party — and handing
it to the app would spread a remote identifier through a layer with no business holding one. The
epoch instead:

- moves on every completed handshake and is stable for as long as that socket is the one in use;
- is `null` before a handshake, after a socket ends, and in every client with no transport at all;
- is never persisted, never encoded into a frame, never logged (its `toString` redacts even the
  local counter), and carries no origin, device id, credential or connection id.

Implemented by `WssClaudePGatewayClient` (mints on handshake, clears on socket end and shutdown),
`FakeClaudePGatewayClient` (mints per successful `hello`), and `ResolvingClaudePGatewayClient`
(delegates to the **resolved** client — see below); `UnpairedClaudePGatewayClient` takes the
interface default of `null`, which is exactly "no connection". `ClaudePProvider.connectionEpoch()`
is the app's only way to ask.

The resolving client is the one that has to be asynchronous. Its counters report the fallback's
zeros while unpaired, and that is honest for them because nothing was dispatched — but this accessor
is asked *before* a replay precisely to learn whether there is a live connection, so reporting the
fallback's `null` unconditionally would answer "no connection" for a device that has one.

### 2.2 The replay itself

`ClaudePSessionBindReplays` consumes **one attempt per `(generationId, connectionEpoch)`**:

- the first attempt on a connection is permitted; the second on that connection is refused;
- a new connection re-opens the attempt for a bind still `INTERRUPTED` — which is the whole case
  replay exists for, since the first attempt's answer really was lost with the old socket;
- the rule is per generation id, so two different bind obligations each get their own attempt;
- an absent epoch refuses, and does **not** consume the attempt: replaying on a connection nobody
  can name risks a repeat, while refusing costs one recovery that the next connection makes again.

It runs at the user action, in `submitCommandTracked`, **before** admission — a branch awaiting a
bind resolves to `INTERRUPTED`, which refuses a new generation by construction, so a replay that
ran any later could never settle the branch the user's own command needs. Only a command that would
dispatch a model triggers it, so a queue edit or a stop cannot send a bind nothing asked for.

`replayBeforeGeneration` keeps the order it was written with: persist `BIND_PENDING`, then send
exactly one bind re-using the persisted `(generationId, branchId, assistantId)`, then record what
the answer proved. Both writes go through `settleReplay`, which supersedes the interrupted record
**in its own slot** — a new `Replay.messageId` carries that slot, for the same reason
`DeferredPending.messageId` does: the slot stops being findable by shape the moment the first write
lands on it. A failed write stops the replay, including before the send.

Only `NoReplayNeeded` and `Bound` let the user's command proceed. Every other outcome rejects it
with a named code (`claude_p_replay_not_permitted`, `claude_p_replay_closed`,
`claude_p_replay_unproven`) — the brief's "a failed replay must fail visibly and the generation it
was for must not start", as a value rather than an exception. `generation.start` is unreachable
from the replay path at all: it reaches `bindSession` and nothing else.

## 3. Tests

New in this batch, all green:

| Class | Tests | Covers |
|---|---:|---|
| `ClaudePSessionAdmissionWiringTest` | 15 | barrier placement, `settlementFor` per terminal, stale detect + supersede, and the close-after-terminal consequence |
| `ClaudePSessionContinuationAdmissionsTest` | 6 | first-decision-wins on repeated admission, forget, both stop shapes |
| `ClaudePSessionContinuationDispatchTest` | 8 | the dispatch-shape audit, the emergency refusal and its stable code, the "dispatches nothing" set |
| `ClaudePSessionDeferredBindTest` | 8 | the pending write's message identity, and the full bind vocabulary |
| `ClaudePSessionReplayTest` | 13 | the epoch's opacity, once-per-connection, new-connection re-attempt, and the full replay flow including "drops again keeps the identity" |

## 4. The activation audit

Every path that can dispatch a Claude P generation was enumerated, and each had to hold a barrier or
be refused by name.

### 4.1 Holds a barrier, or is refused by name

| Entry | Outcome |
|---|---|
| `executeRuntimeCommandBody` → `handleMessageComplete` → `generationHandler.generateText`, reached from `SendMessageCommand`, `RegenerateCommand`, `ResumeAfterApprovalCommand` | The decision was produced at admission and the barrier written there; the settlement is attached before the call. `streamText`, the retry shape and the non-streaming shape all travel on the same `params`, so they carry the binding request too. |
| The same dispatch reached from `InterruptCommand` / `InterruptRegenerateCommand` | **Refused by name** — `claude_p_continuation_emergency_not_admitted` — with `generation.start = 0`, no legacy-`new` fallback, and no continuation state touched. |

### 4.2 Never names a branch, so owes no barrier

`ChatService.generateTitle`, `generateSuggestion`, `compressMessages`; `GenerationHandler.translateText`;
`VisionDescriptionClient`, `OcrTransformer`, `ProviderDreamSynthesizer`, `ProviderMemoryExtractor`,
`PetDialogueGenerator`, `PetDiarySummary`, `StickerVisionClient`, `ProviderConnectionTester`.

These are utility model calls. They pass no `claudePSessionBindingRequest`, they never name a
branch, and they therefore cannot acquire one's session — they dispatch exactly as they did before
this batch. Recorded here because "it is only a title call" is not a proof, and the proof is that
none of them can carry a branch identity.

### 4.3 **Neither — and this is why `auto` is not enabled**

`GenerationHandler`'s **final-answer recovery** dispatch (`GenerationHandler.kt:1418-1453`) passes
`claudePSessionBindingRequest = null` **deliberately**, with a documented rationale: it is a
*second* model generation for the same turn, and a recovery request sent as `auto`/`immediate`
"would claim to be the turn the barrier was written for".

That decision predates this batch and is not wrong on its own terms. Activating M3-B is what turns
it into a new risk, and the audit cannot prove the property the brief asks for:

- the primary dispatch now resolves or resumes the branch's session and the branch settles
  `BOUND` against it;
- if that turn produces no final answer, the recovery dispatch goes out as `mode: "new"` — a second,
  unbound session — and its answer is what the user sees;
- the branch is therefore bound to a session that did **not** produce the visible answer, and the
  next turn sends `immediate` to resume it.

Before M3-B both dispatches were `mode: "new"` and no binding existed to be wrong. So this is a
genuine consequence of activation rather than a pre-existing defect, and it is exactly the shape the
brief forbids: a second session a user cannot tell was created.

**It is left unchanged because it is a design decision, not a wiring defect.** The two candidate
resolutions point in different directions — refuse the recovery for Claude P (fail the turn
visibly, and lose recovery), or give the recovery a distinct binding intent (a protocol change, and
out of scope for this batch) — and picking one is the design owner's call. Nothing in this batch
depends on the answer: flipping `ENABLED` is a one-line change once it is made.

### 4.4 Accepted limitations, recorded

- **A cancelled or failed turn closes the branch to the next ordinary message.** Verified by test.
  A send appends at `selectIndex == 0`, which does not change the branch identity, so the next
  message lands on the branch the terminal just closed — and `INTERRUPTED` / `FAILED_CLOSED` both
  answer `allowsNewGeneration = false`. This is the specified fail-closed semantics: the user
  creates a new conversation, or a new branch with the existing regenerate/fork. **MVP known
  limitation**; M4 verifies the safe closure but does not require continuation of a cancelled
  branch.
- **The two emergency command shapes** (`InterruptCommand`, `InterruptRegenerateCommand`) are
  unsupported: they bypass admission by construction and are refused by name. Reusing the ordinary
  seam is not possible without changing the continuation design, because such a command runs while
  the previous generation's start is on the branch, or after it was cancelled into `INTERRUPTED` —
  and both refuse a new generation. Out of M3-B acceptance scope; M4 does not include them.

## 5. Verification log

| Check | Command | Result |
|---|---|---|
| `:app` compile | `./gradlew :app:compileDebugKotlin` | pass |
| `:ai` compile / tests | `./gradlew :ai:testDebugUnitTest` | **805 tests, 73 classes, 0 failures, 0 skipped** |
| `:app` full suite | `./gradlew :app:testDebugUnitTest` | **4281 tests, 694 classes, 1 skipped, 1 failure** — the pre-existing one below |
| Whitespace | `git diff --check` | clean |
| Room schema / dependencies / workflows | `git status --porcelain` | untouched |

The single failure in the full suite is `HardlineSelfPreservationTest`, which is **pre-existing and
unrelated**: it asserts a release-variant fact (`me.rerere.rikkahub`) from a debug-variant build,
where `applicationIdSuffix = ".agenttest"` makes the assertion correctly fail. Established, not
assumed — `git diff --name-only` for these commits matches nothing in `HardlineCommandGuard`,
`SelfPreservationPolicy`, `HardlineSelfPreservationTest` or any `build.gradle*`. Left as found.

### Not done

- **Activation.** §4.3.
- **No instrumentation test, device or emulator.**
- **No Server run, VPS or Worker.** `session.bind` has still never been sent to a real Server.
- **No model call, OAuth or CLI child.**
- **No push, no CI run, no deploy, no usage UI, no M4.**

---

Items 1–5 are wired, compiled and unit-verified at the seam. `auto` is unreachable, one audit entry
is unresolved, and the completion line is **not** written.
