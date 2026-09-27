# M3-B — settlement terminals: report

- Status: **item 1 delivered and green; items 2–5 not started. `auto` stays off. Awaiting Codex
  review.**
- Base: `ed33b8f8` → HEAD `a30e4954`
- Branch: `codex/claudep-cp1b-local`
- Working tree: the pre-existing untracked `web-ui/bun.lock`, and nothing else

## 0. Read this first

Item 1 of the batch is done. Items 2–5 are not, and they are not partially done either — nothing
writes a barrier, nothing calls `settle`, and production Claude P is byte-for-byte what it was at
`ed33b8f8`.

The brief permits this: *"若不能覆盖全部路径，保持 `auto` 关闭，并停在最近的绿色提交."* The reason
items 2–5 were not started is worth stating rather than leaving as an absence, and §3 does.

## 1. The `NothingToWrite` gap, fixed

`NothingToWrite` meant two different things:

- "this run owed no continuation" — correct and harmless;
- "this run owed one and the graph offered nowhere to put it" — a **live barrier that never
  settles**. The branch stays in `START_IN_FLIGHT`, the gate reads that as *a model may be
  running*, and the conversation is blocked permanently.

Those are now separate, and the second one cannot happen:

| Terminal | Where the record goes | If the graph grew no message |
|---|---|---|
| `SUCCEEDED` | the newest message the branch gained | `Refused(SUCCESS_WITHOUT_TERMINAL_MESSAGE)` |
| `FAILED` | the newest message, else the barrier superseded | `Refused(BARRIER_NOT_UNIQUELY_LOCATED)` |
| `UNPROVEN` | same | same |

**Success has no fallback, deliberately.** A turn that produced no answer did not succeed, and
writing `BOUND` for it would credit the branch with a binding no model turn ever proved — which the
next turn would then send `immediate` on the strength of.

The barrier is located by an **exact** record match: assistant, branch, revision, `START_IN_FLIGHT`
*and* the absence of a generation id. Zero matches and more than one are both refusals. A looser
match would settle onto a different turn's record, which carries the same assistant and branch by
construction and differs only in the revision that says *which* turn it was.

`NothingToWrite` is now produced by exactly one place — `settle`, when the decision carries no
obligation at all. A `deferred` obligation reaching the terminal seam is `Refused` rather than
ignored, because answering "nothing to write" there would silently drop the bind the run owes.

### 1.1 The transition table is the arbiter, not a second rule

Whether a supersede is legal is decided by **resolving the candidate graph through the same
resolver every other reader uses**, and discarding the write when the result is not the expected
settled state. Restating the revision rules here would be a second copy of them, and the copy would
be the one that drifts.

That also refuses the one reachable shape the brief's supersede cannot cover, and it is a real
finding rather than a hypothetical:

> **A later turn's barrier sits after an earlier turn's `BOUND`, and the table permits `BOUND` to be
> followed only by a start.** So on a branch that has already completed a turn, a run that fails
> *before producing any message* has no legal place to put a terminal: superseding the barrier
> folds as `BOUND -> FAILED_CLOSED`, which the table refuses; and there is no later message to use
> instead.

Consequences, stated plainly:

- The supersede covers the whole first-turn case, which is what the brief's test describes
  (`派发后、尚未生成 assistant 消息即取消/断线`) and what the tests assert.
- For the already-bound case the settlement is `Refused(SETTLEMENT_NOT_READABLE)` and the caller
  must fail visibly. The branch stays blocked, which is fail-closed and visible — but it *is*
  lingering, and the brief asks that a persisted `START_IN_FLIGHT` never linger.
- The two candidate fixes are a table change (`BOUND -> FAILED_CLOSED`/`INTERRUPTED` as legal
  successors) or a designer's decision that a failed turn on a bound branch should return the
  branch to `BOUND` rather than close it. Both are semantic decisions about the continuation layer,
  which is under review; neither is mine to make inside this batch. The refusal is pinned by a test
  so it surfaces as a decision rather than as a surprise during item 2.

## 2. Tests

`ClaudePSessionSettlementTest` — **18 tests, 0 failures** (up from 11):

| Brief's required scenario | Test |
|---|---|
| success with a new assistant message → `BOUND` | `a successful immediate turn settles to bound and stays resumable` |
| success with no terminal message → refuse, never fake `BOUND` | `a successful run with no new message refuses rather than faking a bound` |
| failure with no assistant message → barrier superseded to `FAILED_CLOSED` | `a failed run with no new message supersedes its own barrier` |
| cancel/disconnect with no assistant message → barrier superseded to `INTERRUPTED` | `a cancelled run with no new message supersedes its barrier to interrupted` |
| zero matches refuse | `a barrier that is not on the path refuses` |
| more than one match refuses | `a barrier recorded twice on the path refuses` |
| a supersede the table refuses is refused | `a supersede the transition table refuses is refused, not written` |
| `NothingToWrite` means only "no obligation" | `settle reports nothing to write only when the decision carries no obligation` |
| a deferred obligation is not ignored at this seam | `a deferred obligation is refused at the terminal seam, not ignored` |
| the three terminals do not collapse | `no two terminals collapse onto one durable state` |

The `CANCELLED`/`INTERRUPTED` case is asserted through a **re-resolution** of the settled graph,
not against the record written: a record that is attached but not resolvable is the same as no
record at all.

## 3. Why items 2–5 were not started

Not for lack of a design — the design is settled and the pieces are in place. The reason is that
**wiring is all-or-nothing here**, and the batch's own rule says so: *"在上述路径全部接通且测试通过
前，不得开启 production `auto`."*

The five exits, unchanged from the previous report, and what each needs:

| Exit | Site | State |
|---|---|---|
| successful generation | `ChatService.handleMessageComplete` `onSuccess` | analysed, writable |
| model/protocol failure | same function, `onFailure` | analysed, writable |
| explicit cancel | `ConversationRuntime.kt:2092` → `finishFallback` | **not analysed** — a different, result-less settle in a different file |
| transport disconnect | none exists | needs a site that does not exist |
| tool-loop WAITING | `checkpointWaiting` | must **not** settle |

Plus item 3 (stale reconciliation), which is the safety net for exactly the exits above that cannot
be proven — a process that dies settles nothing, and only the next admission can. Writing the
barrier while any of those is unwired leaves a branch the gate reads as *a model may be running*
when it is not: a conversation unusable after one turn, which is worse than no barrier.

So the barrier stays unwritten, `auto` stays unreachable, and the next batch's work is well-defined
rather than half-done.

## 4. Verification log

| Check | Command | Result |
|---|---|---|
| `:ai` compile | `./gradlew :ai:compileDebugKotlin --offline` | pass |
| `:app` compile | `./gradlew :app:compileDebugKotlin --offline` | pass |
| `:ai` tests | `./gradlew :ai:testDebugUnitTest --offline` | 73 classes, 0 failures, 0 skipped |
| `:app` full suite | `./gradlew :app:testDebugUnitTest --offline` | **4221 tests, 1 skipped, 1 failure** |
| Whitespace | `git diff --check` | clean |
| Room schema / dependencies | `git status --porcelain` | no entity, DAO, migration, `*.gradle*` change |

The single failure is `HardlineSelfPreservationTest` — the same pre-existing one. 4221 − 85 = 4136,
the baseline recorded three batches ago.

### Corrections made during this batch

- The settlement suite's "a turn that gained no message settles to nothing and stays blocked" test
  encoded the behaviour being fixed and was replaced, not adjusted — it asserted the linger as
  correct.
- One test helper used inside a shell heredoc corrupted the script's quoting; the replacement was
  applied through the editor instead. Not a code defect, recorded because the batch asks for
  corrections rather than a clean story.

## 5. Not done

- **Items 2–5.** §3.
- **No instrumentation test, no device, no emulator.**
- **No Server run, no VPS, no Worker.** `session.bind` has still never been sent to a real Server.
- **No model call, no OAuth, no CLI child.**
- **No push, no CI, no deploy, no usage UI, no M4.**

---

Item 1 complete and green. `auto` remains off, no barrier is written, and the completion line is not
written — items 2–5 are outstanding, and §1.1 hands the reviewer a semantic decision that has to be
made before item 2's failure path can cover an already-bound branch.
