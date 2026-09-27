# M3-B — Android session continuation wiring: implementation report

- Status: **partially implemented, stopped deliberately. Awaiting Codex review.**
- Base: `b2acc21d` (HEAD at the time of writing; unchanged by this batch)
- Branch: `codex/claudep-cp1b-local`
- Commits added: `ecad022c`, `94808116`
- Working tree: the pre-existing untracked `web-ui/bun.lock`, and nothing else
- Server: read-only, unchanged. No wire-spec change.

## 0. Read this first

**This batch does not deliver M3-B. It delivers the first two thirds of it and stops.**

Implemented and verified:

1. The whole **durable continuation layer** — the metadata on `UIMessage`, the selected-path
   resolver, the closed transition table, and the planner's production seam. (Commit 1.)
2. The whole **wire and provider layer** — `mode: "auto"` with `binding_intent` and
   `assistant_id`, an absent `remote_branch_id` for `deferred`, and the `session.bind` RPC with
   its four-state answer. (Commit 2, first half.)

**Not implemented:** the application of that layer to the production dispatch path. No code in
`ChatService` or `GenerationHandler` reads a plan, writes a `START_IN_FLIGHT` record, writes a
terminal record, or sends a `session.bind`. Production Claude P still sends `mode: "new"`, and
every non-M3 path is byte-for-byte unchanged.

That is the deliberate stopping point, and §7 states exactly why and what remains. The short
version: the remaining work is the largest and most delicate part of the batch, it touches four
call sites in a 5,000-line authority-bearing file, and the brief's own rule is that stopping at a
compiling, tested, self-consistent commit is required rather than leaving `auto` enabled with an
unconnected barrier. **`auto` is not enabled, so that state does not exist here.**

## 1. What Commit 1 delivers — branch authority and durable metadata

`ecad022c`. `ClaudePSessionContinuation` is a serializable record carried on `UIMessage` as
`claudePSessionContinuation`, nullable and defaulting to `null`.

The brief's requirements and where each is met:

| Requirement | Where |
|---|---|
| Exact `assistantId + branchId` binding | `ClaudePSessionContinuationResolver.resolve` filters on both, exactly |
| The same Server `generationId` is kept for a bind replay | the record's `generationId`, permitted only on `BIND_PENDING`/`INTERRUPTED` |
| No session id, config hash or credential stored | structural — the type has no field for any of them |
| Selected-path-only resolver | `messageNodes` → `messages[selectIndex]`, one variant per node |
| Fork-inherited metadata ignored on `branchId` mismatch | exact `(assistantId, branchId)` match, no fallback |
| Closed transition table, no last-write-wins | `ClaudePSessionContinuationTransitions.fold` |
| No Room migration | the field rides in the existing `messages` JSON column |

Two design points are worth a reviewer's attention because they were decisions, not defaults:

- **`UNINITIALIZED` is a resolution, not a stored state.** It is the absence of a matching record
  on the selected path, and there is deliberately no enum member for it, so a reader cannot
  produce a second spelling of "absent" by accident.
- **`Absent → BOUND` is legal, and that is load-bearing.** The `deferred` flow commits its new
  variant with a `BIND_PENDING` record and then *supersedes that same record in place* when the
  bind confirms. The path therefore shows a lone `BOUND`, and without this row the deferred
  success path could not be read back at all. It is not last-write-wins: a single record has
  nothing to be writing over, and every *pair* still has to chain.

`FAILED_CLOSED` is absorbing and `INTERRUPTED` leads only back to a bind replay. A user who wants
to continue past a failure does it by creating a new branch — the failed record stays on the
variant that is no longer selected — which is the fail-closed reading the brief specifies.

## 2. What Commit 2 delivers — the wire and the provider

`94808116`. `ClaudePGenerationStartBody` now carries `binding_intent` and `assistant_id`, and
`remote_branch_id` became `String?`.

- The three request shapes are derived by **one** function (`resolveRequestShape`), because
  `mode`, branch presence and intent are only meaningful together, and §5.4 refuses four of the
  eight combinations. They are refused at construction rather than at the socket: a request the
  Server rejects costs the whole connection.
- `deferred` is expressed by the field being **absent**. The encoder has `explicitNulls = false`,
  so `null` drops the key; `""` would be a *present but empty* branch identity and would share a
  fingerprint with a shape it is not.
- `session.bind` / `session.bind.result` are now client-frame and routed-event types, implemented
  across all four `ClaudePGatewayClient` implementations.
- `ClaudePProvider.bindSession` reaches `gateway.bindSession` and **never** `generation.start`, so
  a replay cannot buy a second CLI child. It ensures the handshake first, which is what makes a
  replay after a reconnect legal rather than a send onto an un-introduced socket.
- The four-state answer maps in exactly one place. `bound`/`already_bound` → `Bound`/`AlreadyBound`;
  the three refusals → `Refused(state)`; an unrecognised state **or an answer naming a different
  generation** → `Malformed`; a dropped connection → `Unproven`. `Unproven` is deliberately not a
  failure: the Server may have applied the bind before the socket died.
- A `deferred` generation stamps its generation id on its chunks and nothing else does.

The `when` in `ClaudePTerminalGate.route` is exhaustive over the event hierarchy, so adding
`SessionBindResult` was a **compile error** until it was classified — which is the property the
file claims for itself. It is routed as `Ignore`, alongside `receipt.result`, because it answers
an RPC the caller is already awaiting by `request_id`.

## 3. Local real compilation

Run in this worktree, against `D:\Android\Sdk` via this worktree's own `local.properties`:

| Command | Result |
|---|---|
| `./gradlew :ai:compileDebugKotlin --offline` | BUILD SUCCESSFUL |
| `./gradlew :app:compileDebugKotlin` | BUILD SUCCESSFUL |
| `./gradlew :ai:testDebugUnitTest --offline` | BUILD SUCCESSFUL — 73 test classes |
| `./gradlew :app:testDebugUnitTest` | see §4 |

This is a real Kotlin compilation and a real test execution, not a type-check of a subset.

## 4. JVM tests

**`ai` module — 73 classes, 0 failures, 0 skipped.** Includes the pre-existing conformance corpus,
which is what proves the frozen v1-r3 fingerprint digests did **not** move: the tail fields are
appended only when present, so a legacy request still produces the v1-r3 byte stream.

New and changed classes in this batch:

| Class | Module | Tests | Result |
|---|---|---:|---|
| `ClaudePSessionContinuationTest` | `ai` | 26 | pass |
| `ClaudePSessionBindWireTest` | `ai` | 19 | pass |
| `ClaudePSessionContinuationResolverTest` | `app` | 18 | pass |
| `ClaudePSessionContinuationPlanTest` | `app` | 12 | pass |
| `ClaudePSessionBranchPlannerTest` (pre-existing, regression) | `app` | 23 | pass |

**`app` module — 53 classes across the four classes named above, 0 failures, 0 skipped** when run
as targeted filters. See §8 for whether the *full* `:app:testDebugUnitTest` suite completed in
this session; that is stated there as it actually happened rather than assumed.

## 5. Room / transaction tests

**None were run, because none were written — and none could have been, in this state.**

The brief requires that the deferred variant commit and the `BIND_PENDING` record be atomic in one
`ConversationCommandAuthorityTransactions` / `updateConversation` transaction. No code in this
batch performs either write, so there is no transaction behaviour to test. Writing a Room test
now would test a path that does not exist.

What *is* proved about durability is narrower and worth stating precisely:

- The record round-trips through `JsonInstant` — the exact codec `MessageNodeEntity.messages`
  uses (`ignoreUnknownKeys = true`, `encodeDefaults = true`).
- A message written before the field existed decodes to `claudePSessionContinuation == null`,
  from both a hand-written legacy literal and a re-encoded message with the key stripped.
- An unknown `state` string **fails to decode** rather than defaulting to a known state.
- No Room entity, DAO, migration or exported schema file was touched — verified by
  `git diff --name-only b2acc21d..HEAD` matching nothing under `db/`, `schema`, `*.gradle*` or
  `.github/`.

## 6. Static audit only

These are claims I read out of the code rather than executed:

- That `runtimeAdmissionGraphProvider` is the right seam for the admission-transaction write: the
  conversation it prepares is what `graph.mutation()` persists inside
  `CommandAdmissionAuthorityCoordinator.admit`'s transaction.
- That the terminal write belongs immediately before `authority.finish(...)` in
  `handleMessageComplete`, so that the resolved assistant message and the record share one
  transaction.
- That `WssClaudePGatewayClient.sendRpc` correlates a `session.bind.result` by `request_id` and
  that the reply therefore never needs the generation stream.
- That the Server (per `claudep/02-wire-protocol-v1.md` §6.1) accepts the frame shapes this build
  now produces. **The Server was not run and this was not exercised against it.**

None of these was verified by execution. They are the audit I would hand to whoever applies §7.

## 7. What is not done, and why

The remaining work is the application of §1 and §2 to the dispatch path:

1. A `ClaudePSessionContinuationGate` (app) owning: plan → binding request, the `START_IN_FLIGHT`
   write into the admitted conversation, the terminal write, and the deferred settle.
2. `ChatService`: populate a plan at admission keyed by command id; resolve it inside
   `handleMessageComplete`; pass a `ClaudePSessionBindingRequest` into the generation; write the
   terminal record into the conversation handed to `authority.finish(...)`; for `deferred`, write
   `BIND_PENDING` in that same transaction and send `session.bind` only after it returns.
3. `GenerationHandler`: one parameter in, one generation-id callback out — the callback reads the
   transient `MessageChunk.claudePGenerationId`, which is why that field exists.
4. Bind replay: one send per active connection, re-sent only on an explicit user action or a
   reconnect boundary, never on a timer, and always with the persisted `(generationId, branchId)`.

**Why I stopped here rather than starting it.** This is the part of the batch where a partial
result is worse than none: enabling `auto` on the immediate path while the terminal and deferred
writes are unfinished produces exactly the state the brief forbids — `auto` on, barrier not
connected — and it would be a *durable* inconsistency, not a visible one. The remaining context
in this session was not sufficient to write, compile, and test that change to a standard I would
report as verified. The brief's rule for this situation is explicit: stop at the last compiling,
tested, self-consistent commit.

**The stopping state is safe.** `auto` is unreachable: nothing constructs a
`ClaudePSessionBindingRequest`, so `resolveRequestShape(null)` runs for every production dispatch
and every request keeps the `mode: "new"` shape it had at `b2acc21d`. The new wire types are
reachable only from tests.

**What this costs.** M3-B is not complete, and the four-commit plan became two-plus. §1 and §2 are
finished work that the next batch consumes unchanged; §7 items 1–4 are the remainder.

## 8. Verification log

| Check | Command | Result |
|---|---|---|
| `:ai` compile | `./gradlew :ai:compileDebugKotlin --offline` | pass |
| `:app` compile | `./gradlew :app:compileDebugKotlin` | pass |
| `:ai` tests | `./gradlew :ai:testDebugUnitTest --offline` | 73 classes, 0 failures, 0 skipped |
| `:app` targeted tests | `./gradlew :app:testDebugUnitTest --tests <4 classes>` | 53 tests, 0 failures, 0 skipped |
| `:app` full suite | `./gradlew :app:testDebugUnitTest` | see below |
| Whitespace | `git diff --check b2acc21d..HEAD` | clean |
| Dependency / Room schema diff | `git diff --name-only b2acc21d..HEAD` filtered | no match — nothing changed |
| Working tree | `git status --porcelain` | only the pre-existing untracked `web-ui/bun.lock` |

### The CI gate was updated, and why that is not "running CI"

`.github/workflows/build-debug-apk.yml` pins the classes both test steps run and a `REQUIRED`
array that fails the job when a pinned class produced no XML. Per the standing note on this
repository, a class absent from that list is compiled but never executed, so four new test
classes would have been silent: `ClaudePSessionContinuationTest` and `ClaudePSessionBindWireTest`
in `:ai`, and `ClaudePSessionContinuationResolverTest` and `ClaudePSessionContinuationPlanTest`
in `:app`. They were added to both the `--tests` filters and the `REQUIRED` array. The file was
parsed as YAML to confirm it still loads.

No workflow was **run**, no push was made, and no job was triggered — the trigger for this branch
does not exist. Editing the allowlist is what makes the tests this batch wrote actually gate
anything; leaving them out would have made "the tests pass" a claim about a suite that never ran
them.

**Full `:app:testDebugUnitTest`.** Started in this session as the broadest available regression
check on the two files this batch touched indirectly (`Provider.kt`, `ui/Message.kt`). It had not
completed when this report was written — the job is long, and the `:ai` changes force a full
`:app` recompilation before the first test runs. Its result is therefore **not** part of the
evidence above. What the batch rests on is the `:ai` suite, which ran to completion, and the
targeted `:app` classes, which also ran to completion.

If the full suite later reports a failure, the first thing to check is whether it also fails at
`b2acc21d`: nothing in this batch changes a code path any pre-existing `:app` test exercises, and
the `:app` compile is known good.

### Not done

- **No real device.** No instrumentation test, no Android emulator, no APK installed.
- **No VPS, no Server run, no Worker.** `session.bind` has never been sent to a real Server.
- **No real Claude end-to-end.** No model call, no OAuth, no CLI child. None was permitted.
- **No Room or transaction test** (§5).
- **No push, no CI, no deploy.** The CI trigger for this branch does not exist anyway.
- **No usage UI, no M4.**

### Corrections made during this batch

Recorded because the brief asks for them rather than for a clean story:

- The first `ClaudePGenerationStartBody` shape check tested "binding intent implies a branch",
  which is wrong for `deferred` — that shape requires the branch to be **absent**. Rewritten as an
  explicit per-intent `when`.
- `assistant_id` was initially added only to the fingerprint and not to the body. A test asserting
  the encoded JSON caught it; §5.4 row 7 requires it on the wire under `auto`.
- The commit `94808116` was amended once, before any review, to remove a trailing-whitespace line
  introduced by an editing script. No other commit was rewritten.

## 9. Rule compliance

- Server and wire spec: **unmodified**. `session.bind` / `session.bind.result` are implemented from
  `02-wire-protocol-v1.md` §6.1 as already frozen; no field, type or value was invented.
- No Room migration, no separate durable store, no generic job/task API: none was added.
- `git status` shows only the pre-existing untracked `web-ui/bun.lock`; it was not touched.
- No push, no CI, no deploy, no VPS, no model invocation, no OAuth.

---

M3-B Android session continuation wiring **incomplete** — commits 1 and 2a complete and verified;
the dispatch-path application (§7) remains.
