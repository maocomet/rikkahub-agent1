# M3-B — the content-integrity prerequisite: implementation report

- Status: **prerequisite complete and green; the dispatch wiring is not, and the barrier was
  reverted rather than left half-applied. Awaiting Codex review.**
- Branch: `codex/claudep-cp1b-local`
- Commits added: `fc6cbe0c` (gate, §1 of the previous batch), `1d822e96` (this one)
- Working tree: the pre-existing untracked `web-ui/bun.lock`, and nothing else
- Server: read-only, unchanged. No wire-spec change. No Room migration.

## 0. Read this first

**The prerequisite is done.** Option 1 of §3.4 is implemented with the exact semantics §1 and §2
asked for, and every test in §3 holds — including one that §3 did not ask for and that the fold was
failing.

**The M3-B wiring is not, and this commit does not pretend otherwise.** I wrote the admission
barrier, found that closing the loop requires more settle sites than the brief's inventory, and
**reverted it** rather than commit a state where every Claude P branch strands in
`START_IN_FLIGHT`. §4 explains what is left with the file and line of each site.

`auto` remains unreachable. Nothing outside tests constructs a `ClaudePSessionBindingRequest`.

## 1. The content-integrity projection

`ConversationSourceSnapshotFactory` (`app/.../data/authority/source/`). `payloadIntegritySha256` no
longer encodes the stored message; it encodes `contentIntegrityProjection(message)`, a named
`internal` function that clears **exactly one field**:

```kotlin
internal fun contentIntegrityProjection(message: UIMessage): UIMessage =
    if (message.claudePSessionContinuation == null) message
    else message.copy(claudePSessionContinuation = null)
```

What §1 forbids, and why none of it is here: there is no table of excluded names, no rule of the
form "anything that looks like control metadata", and nothing provider-dependent. A second field
would have to be named in this one function, which is an edit a reviewer sees. Every other field —
`id`, `role`, `parts` and the tool inputs and results inside them, `annotations`, `createdAt`,
`finishedAt`, `modelId`, `usage`, `translation`, `state` — still participates unchanged.

## 2. Two decisions inside the projection, and why

### 2.1 It substitutes `null` instead of dropping the key

This is the load-bearing one, and it is what makes §3's requirements 1 and 6 satisfiable **at the
same time**.

`JsonInstant` sets `encodeDefaults = true` and leaves `explicitNulls` at its `true` default, so
every message already in the database is stored with `"claudePSessionContinuation":null` in its
bytes. Substituting `null` therefore produces a **byte-identical** input for every message whose
continuation is null, so every digest already written into an authority row stays valid — that is
requirement 6, no drift for non-Claude-P messages.

Removing the key would have satisfied requirement 1 (`null` and `START_IN_FLIGHT` hash the same)
while breaking requirement 6: it would change the digest of every message in every conversation, and
that wave of `UPDATED` transitions is a migration in all but name — the thing §0 of the previous
report said the fix must not be.

Substituting satisfies both: a message carrying `START_IN_FLIGHT` and the same message carrying
`BOUND` project to one value, **and so does a message carrying nothing at all**.

### 2.2 Nothing else was removed

A projection that returned a constant would pass requirement 1 perfectly and be catastrophic. The
test class is therefore written as two halves that must hold together, and the second half is the
one to read skeptically: eighteen distinct field changes are asserted to *still* move the digest,
including all four `ToolApprovalState` shapes and the tool input and result.

## 3. The prerequisite tests

`.app/src/test/.../data/authority/source/ConversationContentIntegrityProjectionTest.kt` — **7 tests**:

| §3 item | Test |
|---|---|
| 1. Every continuation value, revision and generation id: same digest | `no continuation value changes the content digest`, `a continuation-only update is not a content change` |
| 2. Every other real change: different digest | `every other message field still changes the digest`, `a tool input, result or approval change still changes the digest` |
| 6. No drift for non-Claude-P messages | `a message with no continuation encodes exactly as it did before the projection` |

`ConversationContinuationSourceRevisionTest.kt` — **7 tests**, driving the **real**
`ConversationSourceAuthorityWriter` through the in-memory store:

| §3 item | Test |
|---|---|
| 3. Continuation-only write: no revision increment | `a continuation only write advances no source revision` |
| 3. The record is still stored | `the continuation is still persisted even though it is not content` |
| 3. Anchor still valid | `an anchor revision captured before a continuation write still matches afterwards` |
| 3. Execution binding still valid | `an assistant revision captured before a continuation write still matches afterwards` |
| 4. Real content still advances / still conflicts | `a real content change still advances the source revision`, `content beside a continuation still advances the source revision` |
| 4. No bypass of reconciliation | `structural graph changes are unaffected by the projection` |

`ClaudePSessionContinuationGateTest.kt` — **+2 tests** for item 5, and they are written to prove the
*independence* rather than the behaviour:

- `a malformed continuation chain fails closed even though the content digest is identical` —
  stale, skipped and disagreeing chains all resolve `Conflicted` and the gate refuses them with
  `CONTINUATION_CONFLICTED`;
- `the conflicting chains above really do share one content digest` — the same three messages,
  asserted through `payloadIntegritySha256`, one digest. Without this the first test would be
  asserting nothing.

### 3.1 One check §3 asked for that the fold was not making

`跳号` — a skipped revision — was **not** refused. `fold` compared each record against the previous
one for `REVISION_REGRESSION` and `REVISION_DISAGREEMENT`, but a record at revision 3 following one
at revision 1 passed as merely "later".

`Conflict.REVISION_GAP` now refuses it, because every writer this design defines advances the
revision by exactly one per record — a start then a terminal, a pending then a bound, an interrupted
then a pending then a bound — so a step with nothing on it means a record was lost: a half-applied
rollback, or a restore from a partial backup.

**The first record stays exempt**, and that is deliberate rather than an oversight. The transition
table's own documentation says a rollback can legitimately leave a lone record above revision one
("the same slot as a start, when the generation failed ... and the graph was rolled back to a point
where that slot is the only record left"), so `null -> any` must remain legal. Contiguity is
therefore measured from the first folded record, and three tests pin exactly that distinction.

This is the one place this batch changed a completed layer, and it is a *strengthening*, not a
relaxation of §2's list. The full `:ai` suite (73 classes) is green with it.

## 4. Why the dispatch wiring is not here

The prerequisite was the point of this batch; the wiring was to follow it. I started it, and stopped
with the barrier reverted, for one reason worth stating precisely.

**I implemented the admission barrier, and then removed it.** It is fifteen lines in
`runtimeAdmissionGraphProvider` and it compiles and tests clean. But writing `START_IN_FLIGHT` at
admission is only safe if a terminal **always** settles it, and the settle has more sites than the
brief's §7 inventory implies:

| Settle site | Where | State |
|---|---|---|
| Successful generation | `ChatService.handleMessageComplete`, `onSuccess` → `authority.finish` | analysed |
| Model failure | same function, `onFailure` → `authority.finish` | analysed |
| Cancellation / user stop | `ConversationRuntime.kt:2092` → `runAuthority.finishFallback` | **not analysed** — found while wiring; it is a different, result-less settle in a different file |
| Disconnect / process death | **no site** | the brief requires `INTERRUPTED`; today nothing writes it |
| Tool-loop WAITING | `checkpointWaiting` — not a terminal | must *not* settle |

With the barrier written and the settle unwired, every branch would sit in `START_IN_FLIGHT` after
its first turn, the gate would resolve `allowsNewGeneration = false`, and **the next turn on that
conversation would be refused**. That is not a missing feature; it is a regression that makes Claude
P unusable after one turn — and it is precisely the state the brief forbids ("不得在 barrier 完成前
单独开启"), arriving through the back door.

A half-applied barrier is worse than no barrier, so the barrier is not there. The commit is a
compiling, tested, self-consistent state in which production Claude P behaves exactly as it does at
`9d2ab42f`.

### 4.1 What is left, in the order it has to be done

1. **Settle every terminal**, including the `ConversationRuntime.finishFallback` site and a
   disconnect/process-death path that does not exist yet. This is the prerequisite for step 2, not
   a follow-up to it.
2. **The admission barrier** — restore the reverted edit. It is written and was verified to compile;
   it needs step 1 in place first.
3. **The dispatch request** — read the stashed decision in `handleMessageComplete` and pass
   `claudePSessionBindingRequest` into `generationHandler.generateText`. The seam is already there
   (§5).
4. **Deferred** — `BIND_PENDING` in the variant-commit transaction, then `session.bind`, then the
   settle. Needs provider access, which nothing in `ChatService` currently holds.
5. **Replay** — `ClaudePSessionContinuationGate.replayBeforeGeneration` is implemented and tested;
   it needs a caller on the user-action path.

### 4.2 A finding that outlives this batch

The previous report refused `RegenerateCommand` on a **user** message with `BARRIER_NOT_ATOMIC`,
because writing the barrier onto an already-committed anchor moved its source revision and an
approval lineage had recorded it.

**With the projection in place, that reason is gone.** The anchor's revision no longer moves, so the
write is safe and the refusal is no longer justified as an atomicity problem.

A **different** problem remains at exactly that path, and it is the more dangerous kind: the planner
computes the branch identity with `branchIdOf(conversation.messageNodes)` over the graph as it
stands at admission, while `executeRegenerateInline` **truncates** the nodes after the target before
dispatch. Those two graphs have the same selection vector only when every truncated node sat at
`selectIndex == 0`. When the user regenerates an earlier user message in a conversation where any
later node has a selected non-default variant, the id sent on the wire is **not** the id of the
branch that gets committed — a silent wrong identity, which is the failure this whole design exists
to prevent.

The fix is not in the gate: the planner should derive the identity from the graph the command
*leaves behind*. I did not make that change, because it is a change to a layer under review and the
two candidate shapes (planner owns "what the command does to the graph", or the admission graph
provider passes the post-command graph) are a reviewer's call. It is recorded here so it is not
rediscovered as a surprise when step 2 above is wired.

## 5. What is delivered

| Item | State |
|---|---|
| `ClaudePSessionContinuationGate` (§7 item 1, previous commit) | complete, 42 tests |
| Content-integrity projection (§1, §2) | complete, 14 tests |
| §3 prerequisite tests 1–6 | complete and green |
| §3 item 5 (`跳号`) | **newly refused** by `Conflict.REVISION_GAP`; 3 tests in `:ai`, 2 in `:app` |
| GenerationHandler seam (§5 of this batch) | complete: request threaded, one-shot id callback, `ClaudePGenerationIdentityObserver` with 6 tests |
| Admission barrier, terminal settle, deferred, replay | **not wired**; §4 |

The GenerationHandler seam is additive and currently inert — a parameter with a default and a
callback nothing supplies yet. Unlike the previous batch's dead seam, though, it is the *only*
remaining piece for step 3, and its fail-closed rule is now a tested object rather than a closure
inside a four-thousand-line function.

The seam deliberately passes `claudePSessionBindingRequest = null` and
`onClaudePGenerationAccepted = null` on the final-answer **recovery** dispatch. Recovery is a second
model generation; carrying the original binding would make it claim to be the turn the barrier was
written for.

## 6. Verification log

| Check | Command | Result |
|---|---|---|
| `:ai` compile | `./gradlew :ai:compileDebugKotlin --offline` | pass |
| `:app` compile | `./gradlew :app:compileDebugKotlin --offline` | pass |
| `:ai` tests | `./gradlew :ai:testDebugUnitTest --offline` | 73 classes, 0 failures, 0 skipped |
| `:app` new classes | `--tests` the four new/changed classes | 62 tests, 0 failures, 0 skipped (42 + 6 + 7 + 7) |
| `:app` full suite | `./gradlew :app:testDebugUnitTest --offline` | **4198 tests, 1 skipped, 1 failure** |
| Whitespace | `git diff --check` | clean |
| Workflow YAML | `yaml.safe_load` | loads; `jobs: [build-debug-apk]` |
| ChatService | `git diff --stat` | **no diff** — the reverted barrier left nothing behind |
| Room schema / dependencies | `git status --porcelain` | no entity, DAO, migration, `*.gradle*` or `app/schemas` change |

The single failure is `HardlineSelfPreservationTest.default hardline policy protects the installed
application id` — the same pre-existing one recorded at `4136` in the previous report. The
arithmetic confirms it is unrelated: **4198 − 62 = 4136**, where 62 is exactly the classes this
branch added across both commits (42 gate + 6 observer + 7 projection + 7 source-revision), counted
from the run's own XML rather than assumed. It is in no CI allowlist and this batch touches neither
the guard, the policy, the test nor any gradle file.

The CI allowlist gained the four new classes in both the `--tests` filter and the `REQUIRED` array.
No workflow was run and no push was made.

### Corrections made during this batch

- Two test helpers left `UIMessage.createdAt` at its default, which is the *current time*. The
  projection test then passed or failed depending on whether two consecutive calls landed in the
  same millisecond, and the source-revision tests failed outright for a reason that had nothing to
  do with the projection. Both now pin a fixed timestamp; the comment on each says why.
- A "disagreeing" fixture used two *identical* records at one revision, which legitimately fold to
  themselves. It was changed to two different states at that revision, which is what the rule
  actually refuses.
- The `ConversationSourceAuthorityStore` fake was `private` to its file; it is now `internal`, so
  the new suite drives the *real* writer through it instead of growing a second fake that would
  drift.

## 7. Not done

- **No production wiring.** §4.
- **No instrumentation test, no device, no emulator.** Nothing in this batch needed one: every
  claim above is a JVM test over real production code, and the two Room-dependent behaviours are
  covered through the store interface the writer is written against.
- **No Server run, no VPS, no Worker.** `session.bind` has still never been sent to a real Server.
- **No model call, no OAuth, no CLI child.**
- **No push, no CI, no deploy, no usage UI, no M4.**

---

Prerequisite complete and green; the M3-B dispatch wiring is **not** complete — see §4 for the five
settle sites and the order they have to be done in. `auto` remains unreachable.
