# M3-B — Android session continuation wiring

- Status: **items 1–5 wired and activated. `mode: "auto"` is reachable in production. Awaiting
  Codex review.**
- Branch: `codex/claudep-cp1b-local`
- Base: `60e9e70a` → `3e04b869` (immediate) → `54fd4716` (dispatch refusal) → the deferred-bind
  commit → the replay commit → the activation commit this report ships with
- Server: read-only, unchanged. No wire, schema, dependency or workflow change.
- Working tree: the pre-existing untracked `web-ui/bun.lock`, and nothing else.

## 1. Items 1–5

| Item | What lands |
|---|---|
| 1. immediate admission | The barrier is written into the conversation the command's admission transaction already persists, so the command row and `START_IN_FLIGHT` commit together. The settlement is attached immediately before `generateText`, and nothing above that line reaches the network. A barrier that cannot be placed where the resolver reads it refuses the admission. |
| 2. immediate terminal | Both result-bearing terminals settle the conversation the authority transaction persists — success → `BOUND`, model/protocol failure → `FAILED_CLOSED` — and the result-less path settles through the reviewed `finishGenerationWithoutResult` seam (`INTERRUPTED` for cancel, disconnect and no-trusted-terminal). No call site composes a record. |
| 3. stale reconciliation | At the next admission, a `START_IN_FLIGHT` this process owns no run for is superseded in its own slot, in its own transaction, and the operation stops with `generation.start = 0`. A live run keeps the refusal. A reconciliation that cannot write pauses the queue. No timer, task or heartbeat is added. |
| 4. deferred bind | No start at admission. A successful turn writes `BIND_PENDING` in the transaction that commits its variant, then sends exactly one `session.bind` — after that transaction returned — and settles the full vocabulary: `bound`/`already_bound` → `BOUND`; the three refusals and a malformed reply → `FAILED_CLOSED`; a dropped connection → `INTERRUPTED` keeping the same generation id. |
| 5. replay | A user action, before admission. One attempt per `(generationId, connectionEpoch)`; a new connection re-opens it. Only `NoReplayNeeded` and `Bound` let the user's command proceed. `generation.start` is unreachable from the path. |

### 1.1 The connection epoch

`ClaudePConnectionEpoch` is a narrow `ai`-layer value: an opaque, monotonically increasing **local**
token minted by the transport at the one place a handshake completes, reported by
`ClaudePGatewayClient.connectionEpoch()` and reached by the app through
`ClaudePProvider.connectionEpoch()`.

It is deliberately **not** the Server's connection id — that is the Server's identity for the
socket, it appears on the wire, and handing it to the app would spread a remote identifier through a
layer with no business holding one. The epoch moves on every completed handshake, is stable for as
long as that socket is in use, is `null` when nothing is carried, and is never persisted, framed or
logged (its `toString` redacts even the local counter). Implemented by `WssClaudePGatewayClient`,
`FakeClaudePGatewayClient` and `ResolvingClaudePGatewayClient`; `UnpairedClaudePGatewayClient` takes
the interface default of `null`, which is exactly "no connection".

The resolving client is the one that must be asynchronous: it reports the fallback's zeros for its
counters (honest — nothing was dispatched), but this accessor is asked *before* a replay precisely
to learn whether a live connection exists, so returning the fallback's `null` unconditionally would
answer "no connection" for a device that has one.

## 2. The activation audit

Every path that can dispatch a Claude P generation was enumerated; each had to hold a barrier or be
refused by name.

| Entry | Outcome |
|---|---|
| `executeRuntimeCommandBody` → `handleMessageComplete` → `generationHandler.generateText`, from `SendMessageCommand`, `RegenerateCommand`, `ResumeAfterApprovalCommand` | Holds the barrier. The decision was produced at admission and the barrier written there; the settlement is attached before the call. The streaming, retry and non-streaming shapes all travel on the same `params`, so they all carry the binding request. |
| The same dispatch from `InterruptCommand` / `InterruptRegenerateCommand` | **Refused by name** — `claude_p_continuation_emergency_not_admitted` — `generation.start = 0`, no legacy-`new` fallback, no continuation state touched. |
| `GenerationHandler` final-answer recovery | **Refused by name** — `claude_p_final_answer_recovery_not_supported` — before any recovery dispatch, so no second `generation.start` and no second session. §3. |
| `ChatService.generateTitle`, `generateSuggestion`, `compressMessages`; `GenerationHandler.translateText`; `VisionDescriptionClient`, `OcrTransformer`, `ProviderDreamSynthesizer`, `ProviderMemoryExtractor`, `PetDialogueGenerator`, `PetDiarySummary`, `StickerVisionClient`, `ProviderConnectionTester` | Never names a branch. These pass no `claudePSessionBindingRequest` and cannot carry a branch identity, so they cannot acquire one's session and dispatch exactly as they did before this batch. |

## 3. The final-answer recovery refusal

Final-answer recovery makes a **second** model call when a turn produced no visible answer. For
Claude P that call cannot be the primary's generation: the primary runs under `mode: "auto"` with a
binding intent, which is what makes the Server resolve or resume *a session for the branch*, and the
branch's continuation record is settled from it. A recovery call is a retry, not a continuation, so
it would have to go out as `mode: "new"` — a second, unbound session whose answer the user reads
while the branch stays bound to the first. The next turn would then resume a session that never
produced that answer.

So the recovery is not attempted. `ClaudePFinalAnswerRecovery.refusal(provider, activationEnabled)`
is asked **before** the recovery loop, and on a refusal the turn ends there:

- the message is annotated `FinalAnswerRecoveryStatus.FAILED` with the named reason and
  `UIMessageState.INCOMPLETE_NO_VISIBLE_ANSWER` — a bounded, terminal state that says no final answer
  was generated and that this recovery is not supported;
- whatever the primary produced is **kept**: the path only replaces the annotation, never the
  visible parts, and the merge that a successful recovery would perform never runs;
- nothing the primary established is touched — no continuation record, no binding, no protocol
  change, and `generation.start` is never reached from here;
- the switch gates it, so while activation is off Claude P keeps the recovery it always had.

`ClaudePFinalAnswerRecovery` is a decision over `(provider, activationEnabled)`, the same shape
`FinalAnswerRecoveryPolicy` already uses in that file, so the "who is affected" matrix is a thing a
test asserts rather than a branch a reader has to trace.

## 4. Tests

New in this batch, all green:

| Class | Tests | Covers |
|---|---:|---|
| `ClaudePSessionAdmissionWiringTest` | 15 | barrier placement, `settlementFor` per terminal, stale detect + supersede, the close-after-terminal consequence |
| `ClaudePSessionContinuationAdmissionsTest` | 6 | first-decision-wins, forget, both stop shapes, and that activation is on |
| `ClaudePSessionContinuationDispatchTest` | 8 | the dispatch-shape audit, the emergency refusal and its stable code, the "dispatches nothing" set |
| `ClaudePSessionDeferredBindTest` | 8 | the pending write's message identity, and the full bind vocabulary |
| `ClaudePSessionReplayTest` | 13 | epoch opacity, once-per-connection, new-connection re-attempt, the full replay flow including "drops again keeps the identity" |
| `ClaudePFinalAnswerRecoveryTest` | 5 | the recovery matrix: Claude P refused by name when active, unchanged when not, every other provider untouched |

### 4.1 What is executed, and what is argued

Stated plainly rather than left for a reader to discover from the list above.

**Executed by test:** the connection epoch's shape and per-connection rule; the whole replay flow
including every bind answer and a second drop; the deferred bind vocabulary; barrier placement;
stale reconciliation; the dispatch-shape audit; the recovery-refusal matrix.

**Argued structurally, not executed:** the six checks the brief asks for around the recovery refusal
— `generation.start` counts, absence of a second request id, content preservation on a partial
answer, and the tool-loop-completes case. There is **no `GenerationHandler` test harness in this
repository** (`grep 'GenerationHandler(' app/src/test` matches nothing), and building one means
standing up settings, models, transformers, memory repositories and diagnostics — test
infrastructure this batch did not write. What is proven is the *decision*: the refusal is taken
before the recovery loop's first statement, so the loop body — and the dispatch inside it — is
unreachable when it fires, and the annotation path replaces only the annotation. A reader should
treat those four checks as reviewed code, not as verified behaviour.

## 5. Accepted limitations

All three are named, visible refusals. None falls back to `mode: "new"`, and none can produce a
hidden second session.

1. **A cancelled or failed turn closes the branch to the next ordinary message.** Verified by test.
   A send appends at `selectIndex == 0`, which does not change the branch identity, so the next
   message lands on the branch the terminal just closed, and `INTERRUPTED` / `FAILED_CLOSED` both
   answer `allowsNewGeneration = false`. Specified fail-closed semantics: the user creates a new
   conversation, or a new branch with the existing regenerate/fork. **MVP known limitation**; M4
   verifies the safe closure and does not require continuing a cancelled branch.
2. **The two emergency command shapes** (`InterruptCommand`, `InterruptRegenerateCommand`) are
   unsupported: they bypass admission by construction, so no barrier can be written for them,
   and they are refused by name. Out of M3-B acceptance scope; M4 does not include them.
3. **Claude P turns do not get final-answer recovery.** §3. Recorded as an MVP known limitation and
   explicitly **not** a success requirement for M4, which verifies only that no hidden second model
   call occurs.

## 6. Verification log

| Check | Command | Result |
|---|---|---|
| `:app` compile | `./gradlew :app:compileDebugKotlin` | pass |
| `:ai` compile / tests | `./gradlew :ai:testDebugUnitTest` | **805 tests, 73 classes, 0 failures, 0 skipped** |
| `:app` full suite | `./gradlew :app:testDebugUnitTest` | **4286 tests, 695 classes, 1 skipped, 1 failure** — the pre-existing one below |
| Whitespace | `git diff --check` | clean |
| Room schema / dependencies / workflows | `git status --porcelain` | untouched |

The single failure in the full suite is `HardlineSelfPreservationTest`, **pre-existing and
unrelated**: it asserts a release-variant fact (`me.rerere.rikkahub`) from a debug-variant build,
where `applicationIdSuffix = ".agenttest"` makes the assertion correctly fail. Established, not
assumed — `git diff --name-only` for these commits matches nothing in `HardlineCommandGuard`,
`SelfPreservationPolicy`, `HardlineSelfPreservationTest` or any `build.gradle*`. Left as found.

### Not done

- **No instrumentation test, device or emulator.**
- **No Server run, VPS or Worker.** `session.bind` has still never been sent to a real Server, and
  no `mode: "auto"` request has ever left this device for one.
- **No model call, OAuth or CLI child.**
- **No push, no CI run, no deploy, no usage UI, no M4.**
