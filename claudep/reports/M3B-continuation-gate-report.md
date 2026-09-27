# M3-B — the continuation gate: implementation report

- Status: **gate delivered and tested; production wiring blocked, with the blocker evidenced. Awaiting Codex review.**
- Branch: `codex/claudep-cp1b-local`
- Base: `9d2ab42f`
- Working tree: the pre-existing untracked `web-ui/bun.lock`, and nothing else
- Server: read-only, unchanged. No wire-spec change. No Room migration.

## 0. Read this first

This batch delivers **§7 item 1** — `ClaudePSessionContinuationGate` — complete, compiled and
tested. It does **not** deliver §7 items 2–4, and it does not enable `auto`.

It stops because items 2–4 share one blocker that is not an implementation detail: **a
continuation record cannot be written into the message graph without moving a durable identity that
an existing, audited subsystem compares.** §5 below states it with the code that proves it. Every
destination for a terminal record perturbs one of two durable identities, and the choice is a design
decision the brief's stop conditions assign to the reviewer, not to me.

`auto` therefore remains unreachable: nothing constructs a `ClaudePSessionBindingRequest` outside
tests, so every production dispatch still takes `resolveRequestShape(null)` and keeps the `mode:
"new"` shape it had at `b2acc21d`.

## 1. What is delivered — `ClaudePSessionContinuationGate`

`app/src/main/java/me/rerere/rikkahub/data/claudep/ClaudePSessionContinuationGate.kt`, new.

The single app-side seam that turns a command into a binding decision, so the admission
transaction, the dispatch path and the terminal settle cannot each grow their own state machine.

It **persists nothing and starts no coroutine.** Every member is a pure function over the
authoritative conversation returning either a decision or the `UIMessage` the caller must write
inside the transaction it already owns. That is what makes the decision table testable without
Room, and it is also what keeps atomicity where it belongs — the caller's transaction.

It re-derives nothing the layer already owns. The mode comes from `ClaudePSessionBranchPlanner`,
the branch identity from `ClaudePSessionBranchPlanner.branchIdOf`, the state from
`ClaudePSessionContinuationResolver`, the legality of every step from
`ClaudePSessionContinuationTransitions`, and the meaning of a bind answer from
`ClaudePSessionBindOutcome`. No stored state, no branch identity and no transition is computed a
second time anywhere in the file.

It does declare one enum, `Reason`, and that is a deliberate exception rather than a second
vocabulary: it is the *image* of `ClaudePSessionContinuationPlan.Reason` — a total `when` over it,
with no member that is not a planner reason except `BARRIER_NOT_ATOMIC`. That one member is a fact
about the **admission transaction**, and folding it into the planner's enum would put a
transaction-shaped reason in a type that never sees a transaction. The gate's test suite pins the
mapping, so the two cannot drift apart silently.

| Requirement (§7.1) | Where |
|---|---|
| `ClaudePSessionBranchPlanner` | `admission`, `resumeAfterApproval`, `replay`, `resolutionOf` |
| `ClaudePSessionContinuationResolver` | `resolutionOf`, `replay` |
| `ClaudePSessionContinuationTransitions` | transitively, through the resolver's fold |
| `ClaudePSessionBindOutcome` | `replaySettlement`, `deferredSettlement` |
| No state-machine copy | the file contains no `when` over a stored state that decides a transition |

### 1.1 One correction inside the layer, and why it is here

`ResumeAfterApprovalCommand` is classified **before** the planner rather than by it.

The planner asks the resolver whether the branch permits a new generation, and a branch in
`START_IN_FLIGHT` does not — which is precisely the state an approved tool resumes, because the tool
loop it continues has not reached a terminal yet. Routing it through `plan()` would return
`Refused(CONTINUATION_BLOCKED)` and **refuse every approved tool call**.

The gate therefore reads the graph itself for this one command, and only accepts the case where the
branch really is `START_IN_FLIGHT`; every other state refuses. The request it produces is the same
`immediate` shape the opening dispatch sent, so the Worker resumes the session it established, and
**no new barrier is written** — `START_IN_FLIGHT -> START_IN_FLIGHT` is not a transition the table
has, and inventing one would let a branch accumulate starts. That is §5's "the tool loop and an
in-flight approval are one generation" expressed as a value rather than as a rule each call site
remembers.

This is the one place the gate does not simply delegate, and it is a defect in the completed layer
that wiring surfaced: the planner's own doc says an approval resume "resumes the existing assistant
message", which is a generation already in flight, while its gate refuses exactly that state.

### 1.2 The barrier writer, and the one refusal it introduces

`AnchorOrigin` is an **input**, not something the gate infers, because only the admission path knows
whether the message that will carry the barrier is created by the transaction about to write it.

When it is not — `ALREADY_COMMITTED` — the gate answers `Refused(BARRIER_NOT_ATOMIC)` rather than
dispatching a generation whose barrier is not atomic with its admission. §5 explains which command
that is and why.

### 1.3 `terminalTarget`, and a rule the tests found

A branch's records fold **in node order**, so a terminal is legal only if it sits after the barrier
that opened the generation. Writing it back onto the barrier's own message reads as a revision
regression; writing it onto any earlier message reads as the branch restarting. Both are refused by
the transition table, and both would leave a completed turn with a branch that cannot be continued.

The rule is therefore structural: **the last message on the selected path that does not already
carry this branch's record.** Turn one picks the assistant message; turn two picks the *new*
assistant message, and the fold reads start, bound, start, bound; a `deferred` generation picks the
variant it just committed. A test caught my first attempt placing the second turn's terminal back on
the barrier, which folded as `BOUND -> BOUND` and was refused — the rule is in the production code
because the test failed, not because it was designed in.

`null` means the graph grew no message for this branch. That is the rollback case, and a caller must
read it as "no record" rather than "nothing to do": a branch left in `START_IN_FLIGHT` is closed,
not open.

### 1.4 The replay

`replayBeforeGeneration` performs §4's user-triggered replay in the required order:

1. decide whether a replay is owed — `INTERRUPTED` **carrying a generation id**;
2. persist `BIND_PENDING` **before** the RPC, and stop if it did not commit;
3. send **exactly one** bind, re-using the persisted `(generationId, branchId, assistantId)`;
4. record what the answer proved, and stop if that write did not commit.

There is no timer, no poll, no retry and no background loop: one call per invocation, and the
invocation is a user action. An interrupted *start* carries no generation id, so `replay` returns
`null` for it and the branch stays closed — reading the state alone would turn a dropped start into
a branch that looks recoverable, which is the permissive mistake this layer exists to prevent.

## 2. Tests

`app/src/test/java/me/rerere/rikkahub/data/claudep/ClaudePSessionContinuationGateTest.kt`, **40
tests, 0 failures, 0 skipped**, covering every behavioural item in §8's *Planner/admission*,
*Immediate*, *Deferred* and *Replay* lists that is decidable at the gate:

| §8 group | Covered by |
|---|---|
| SendMessage immediate | `a send on a fresh branch dispatches immediately behind a start barrier` |
| regenerate user target immediate | `a user regenerate refuses when the barrier cannot be atomic with its admission` |
| regenerate assistant target deferred | `an assistant regenerate dispatches deferred with no branch id on the request` |
| replayed approval immediate | `a replayed approval continues the in-flight generation behind the existing barrier` |
| in-flight approval produces no new plan | `an in-flight approval decision produces no plan` |
| START_IN_FLIGHT / BIND_PENDING / FAILED_CLOSED block dispatch | `in-flight, pending and closed branches all block a dispatch` |
| absent → START_IN_FLIGHT → BOUND | `an absent branch runs start then bound and reads back as resumable` |
| BOUND → START_IN_FLIGHT → BOUND | `a second turn on a bound branch appends its barrier and reads back bound` |
| rollback leaves no BOUND | `a terminal against a message the graph does not hold does not leave a bound` |
| failure / cancel / disconnect mapping | `terminal outcomes map onto three distinct durable states` |
| no decay to absent after recreation | `a settled branch survives a storage round trip and does not decay to absent` |
| deferred pending record carries the id once | `a pending record carries the generation id exactly once` |
| variant + BIND_PENDING, supersede in place | `a settled deferred bind supersedes the pending record in place and reads back` |
| bound / already_bound / conflict / candidate_unavailable / refused / malformed / unproven | `every bind outcome maps onto its own durable state` |
| result identity mismatch does not settle | same test, `Malformed -> FAILED_CLOSED` |
| INTERRUPTED round trip and replay identity | `an interrupted bind offers a replay that reuses its own identity`, `a settled branch survives a storage round trip` |
| bind before generation, bind count = 1 | `a replay writes pending, sends exactly one identical bind, and settles to bound` |
| generation count = 0 when the bind fails | `a replay whose pending record does not commit sends nothing`, `a replay whose settlement does not commit stays unproven` |
| immediate-type interrupted fails closed | `an interrupted start offers no replay and stays closed` |
| redaction | `a continuation record does not print its identities` |

The round-trip tests are real encode/decode through `JsonInstant` — the exact codec
`MessageNodeEntity.messages` uses — not copies of the object.

**What is deliberately not claimed.** No test exercises the production dispatch path, because none
of it is wired (§5). The §8 rows that require a real generation — "admission rollback → no
`startGeneration`", "tool loop produces no second generation/bind", "metadata does not enter the
prompt, wire body or fingerprint" — are covered at the value level where they can be (a `Refused`
carries neither a request nor a record, so there is nothing a caller could dispatch with) and
**not** claimed as end-to-end.

## 3. The §5 blocker

This is the reason items 2–4 are not implemented, and it is the section to review.

### 3.1 The coupling

`ConversationSourceSnapshotFactory.payloadIntegritySha256` hashes the **whole** `UIMessage`:

```kotlin
fun payloadIntegritySha256(message: UIMessage): String =
    MessageDigest.getInstance("SHA-256")
        .digest(JsonInstant.encodeToString(message).encodeToByteArray())
```

`claudePSessionContinuation` is a field of `UIMessage`, so **writing a continuation record changes
that message's payload digest**, and
`ConversationSourceAuthorityWriter.reconcileInCurrentTransaction` turns a changed digest into
`sourceRevision = nextRevision(existing.sourceRevision)`. A record is therefore never a free write:
it moves its message's source revision.

### 3.2 The two identities that move with it

**(a) The branch anchor's revision, which an approval lineage records.**

`CommandAdmissionAuthorityCoordinator.admit` refuses when the anchor has moved:

```kotlin
val anchor = sourceCommit.requireActiveMessage(command.branchAnchorMessageId, expectedRole = "USER")
if (anchor.sourceRevision != command.branchAnchorMessageRevision) {
    throw AuthorityTransactionConflictException("COMMAND_BRANCH_ANCHOR_REVISION_CONFLICT")
}
```

A child command (`ToolApprovalCommand`, `ResumeAfterApprovalCommand`) is admitted with
`lineage.branchAnchorMessageRevision` — the value its parent's admission recorded. So the anchor may
only be written where nothing has recorded its revision yet: the message the admission transaction
itself creates.

This is why **`RegenerateCommand` on a user message is refused**: its anchor is a user message that
has been committed for a while, so the barrier would have to move a revision an approval lineage may
already hold. The planner says `immediate` for that path; the gate refuses it. That gap is pinned by
a test, so it cannot be closed silently later.

**(b) An execution-owning assistant message's revision.**

`ExecutionMessageAuthorityBinder.bindInCurrentAuthorityTransaction`:

```kotlin
if (current.owningAssistantMessageId == authority.assistantMessageId &&
    current.owningAssistantMessageRevision == authority.assistantMessageRevision) {
    ExecutionMessageBindResult.Duplicate(current)
} else {
    ExecutionMessageBindResult.Conflict
}
```

`WaitingApprovalAuthorityCoordinator.checkpoint` binds each tool execution to the assistant
revision resolved **at the WAITING checkpoint**. `FinalConversationAuthorityCoordinator.finish`
re-checks the same bindings with the revision resolved **at the final commit**. If a continuation
record is written onto that assistant message between the two, the revisions differ and the final
commit fails with `execution_message_binding_conflict`.

### 3.3 Why that stops the batch

The two hazards are complementary, and together they exclude every destination a terminal record
could have on a turn that used a tool:

- write the terminal on the **assistant message** → (b) fails for any tool turn;
- write it on the **barrier/anchor message** → (a) is fine for later children, which are all
  admitted before the final terminal, but it breaks
  `RewardFeedbackAuthorityRepository`'s `anchorSource.sourceRevision !=
  target.branchAnchorMessageRevision` check, which is evaluated *after* the turn.

Admission cannot know whether the turn will use a tool, so a barrier written at admission commits
the app to a terminal it may be unable to write — and a branch left in `START_IN_FLIGHT` is
**closed**, so the conversation could not be continued at all. Enabling `auto` in that state is
exactly the durable inconsistency the brief forbids ("不得在 barrier 完成前单独开启"), and it would
be worse than the current state, not better.

### 3.4 The three ways out, none of them this batch's to choose

1. **Exclude the field from the payload digest.** `payloadIntegritySha256` would skip
   `claudePSessionContinuation`. Cleanest semantically — the record *is* bookkeeping, not payload —
   but every stored `payloadIntegritySha256` was computed with the current scheme, so recomputing
   would show every message as changed once, producing a wave of revision bumps. That is a
   migration-sized change to an authority subsystem.
2. **Move the record out of `UIMessage`** — onto `MessageNode`, which is in the same JSON column but
   is not part of any per-message digest, or into a sibling table. Both are Room schema changes.
3. **Give the authority layer an explicit bookkeeping-only write** that persists a message without
   advancing its source revision, so a caller can say "this change is not payload".

The brief's stop conditions name the first two directly: "需要 Room migration、独立 store" and
"无法保证 transaction 原子性". Picking among them changes durable identity semantics in an audited
area, which is a reviewer's call rather than an implementer's.

## 4. Verification log

Run in this worktree against `D:\Android\Sdk` via this worktree's own `local.properties`.

| Check | Command | Result |
|---|---|---|
| `:ai` compile | `./gradlew :ai:compileDebugKotlin --offline` | pass |
| `:app` compile | `./gradlew :app:compileDebugKotlin --offline` | pass |
| `:ai` tests | `./gradlew :ai:testDebugUnitTest --offline` | 73 classes, 800 tests, 0 failures, 0 skipped |
| `:app` targeted tests | `--tests` the four M3-B classes plus the new gate class | 93 tests, 0 failures, 0 skipped |
| `:app` full suite | `./gradlew :app:testDebugUnitTest --offline` | **4176 tests, 1 skipped, 1 failure** — `HardlineSelfPreservationTest` only, see below |
| Whitespace | `git diff --check` | clean |
| Workflow YAML | parsed with `yaml.safe_load` | loads; `jobs: [build-debug-apk]` |
| Room schema / dependencies | `git status --porcelain` | no entity, DAO, migration, `*.gradle*` or `app/schemas` change |

This is real Kotlin compilation and real test execution, not a type-check of a subset.

### The one failure, and why it is not this batch's

`HardlineSelfPreservationTest.default hardline policy protects the installed application id` — the
**same pre-existing failure** the previous M3-B report recorded at `4136 tests`. The arithmetic
confirms it is unrelated: 4136 + 40 new gate tests = 4176, and the failing set is unchanged. It
asserts a release-variant fact from a debug-variant build (the guard derives the protected package
from `BuildConfig.APPLICATION_ID`, which the debug build type suffixes with `.agenttest`), it is in
no CI allowlist, and this batch touches none of the guard, the policy, the test or any gradle file.
Left as found: it is security-adjacent and belongs in its own reviewed batch.

### The CI gate was updated, and why that is not "running CI"

`.github/workflows/build-debug-apk.yml` pins the classes both test steps run, and a `REQUIRED` array
fails the job when a pinned class produced no XML. Per the standing note on this repository, a class
absent from that list is compiled but never executed, so
`ClaudePSessionContinuationGateTest` would have been silent. It was added to both the `--tests`
filter and the `REQUIRED` array.

No workflow was **run**, no push was made, and no job was triggered — the trigger for this branch
does not exist.

### Not done

- **No production wiring.** §7 items 2–4. `auto` is not enabled and remains unreachable.
- **No Room or transaction test.** The gate writes nothing, so there is still no production
  write path to test; §5 is why. This is unchanged from the previous report and is *not* an
  oversight.
- **No instrumentation test, no device, no emulator.** Nothing new needed one.
- **No Server run, no VPS, no Worker.** `session.bind` has still never been sent to a real Server.
- **No model call, no OAuth, no CLI child.**
- **No push, no CI, no deploy, no usage UI, no M4.**

## 5. Corrections made during this batch

Recorded because the brief asks for them rather than for a clean story:

- The gate's first draft routed `ResumeAfterApprovalCommand` through
  `ClaudePSessionBranchPlanner.plan`, which refuses a `START_IN_FLIGHT` branch — i.e. it would have
  refused every approved tool call. Reclassified ahead of the planner, with the in-flight check
  kept. §1.1.
- The first `terminalTarget` rule was positional (the assistant message produced by the turn) and
  the second turn's test failed with a `Conflicted` resolution: the terminal landed back on the
  barrier and folded as `BOUND -> BOUND`, which the table refuses. Replaced with the structural
  rule in §1.3, and the failing case kept as a test.
- Three tests were written against the wrong graph — they appended the barrier into a conversation
  that already held the anchor, which is the re-admission case rather than the admission case, and
  `appendWithBarrier` correctly returned `null`. The tests were wrong, not the gate.
- One unused import and two stray declarations were removed from the gate before its first commit.

## 6. Rule compliance

- Server and wire spec: **unmodified**. No field, type or value was invented.
- No Room migration, no separate durable store, no generic task/job queue: none was added. §3.4
  explains why the *fix* for the blocker may need one, and leaves that decision open.
- No push, no CI run, no deploy, no VPS, no model invocation, no OAuth.
- `git status` shows only the pre-existing untracked `web-ui/bun.lock`, untouched.
- No second model generation and no fallback `new`: nothing new constructs a binding request
  outside tests.

---

M3-B gate complete and tested; §7 items 2–4 blocked on the durable-identity coupling in §3 — **the
production dispatch path is unchanged and `auto` remains unreachable.**
