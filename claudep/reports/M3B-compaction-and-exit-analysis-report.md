# M3-B — compaction semantics, and what the exit wiring actually needs

- Status: **compaction semantics delivered and green. Items 2–5 not started. `auto` stays off.**
- Base: `924df934` → HEAD `877e3ebc`
- Branch: `codex/claudep-cp1b-local`
- Working tree: the pre-existing untracked `web-ui/bun.lock`, and nothing else

## 1. The semantics change, as specified

The table gains **exactly two rows** and the terminal compacts into the barrier's own slot.

| | Before | Now |
|---|---|---|
| first turn, no message | supersede at `revision + 1` → lone `FAILED_CLOSED(2)` | supersede **at the barrier's revision** → lone `FAILED_CLOSED(1)` |
| later turn on a bound branch, no message | `Refused(SETTLEMENT_NOT_READABLE)` — lingering | `BOUND(n) -> FAILED_CLOSED(n+1)`, contiguous |

Keeping the barrier's revision rather than adding one is what makes the second row contiguous:
the barrier's revision was already `n + 1`, so the compaction leaves no step skipped. `REVISION_GAP`
is untouched, and a test asserts a genuine gap is still refused.

Added: `BOUND -> FAILED_CLOSED`, `BOUND -> INTERRUPTED`.
**Not** added: `BOUND -> BOUND`, `BOUND -> BIND_PENDING`. The line between those is the point — a
bound branch is never returned to `BOUND` by an outcome that is not a completed turn. Treating
"we could not find out" as "it is still bound" is the permissive reading this layer exists to
refuse, and a test asserts all four of those rows directly.

`SUCCEEDED` with no new terminal message **still refuses**, and the compaction is not a route
around it: it is a way to record a terminal whose turn produced nothing, not a way to invent one.

### Tests

`:ai` `ClaudePSessionContinuationTest` — 4 added:

- `a bound branch closes when a later turn ends with nothing to write to` — both successors
- `a bound branch never returns to bound or to a pending bind` — all four illegitimate rows
- plus the existing gap tests, which still pass unchanged

`app` `ClaudePSessionSettlementTest` — 22 tests (was 18):

| Brief's required proof | Test |
|---|---|
| first turn, no answer, fails/interrupts, settles | `a failed run with no new message supersedes its own barrier`, `a cancelled run …` |
| bound branch, next turn, no answer, settles | `a bound branch closes when a later turn ends with no message`, `… is interrupted …` |
| revisions contiguous, resolver lands on the expected closed state | both of the above assert the revision (`3`) and the state |
| non-exact barrier: zero and multiple matches both refuse | `a barrier that is not on the path refuses`, `a barrier recorded twice on the path refuses` |
| a general gap is still refused | `a supersede that would leave a revision gap is refused` |
| an unknown outcome never returns to `BOUND` | `no terminal ever settles back to bound` |
| success with no terminal message refuses | `a successful run with no new message refuses rather than faking a bound` |

## 2. What the exit wiring actually requires — a finding, not a deferral

I started item 2 and stopped before writing anything, because the cancel exit is not a call that can
be added at a site. This is worth the reviewer's attention independent of my budget.

`ConversationRuntime.kt:2092` settles a cancellation through:

```kotlin
runAuthority.finishFallback(
    terminalState = finalOutcome.toDurableState(),
    errorCode = finalOutcome.toDurableErrorCode(),
)
```

`finishFallback` takes **no conversation and no graph mutation** by design — its own doc says it is
*"intentionally result-less: generation paths must use `finish` with an exact assistant pair"*. So a
cancellation terminalises the command row and **writes no graph at all**, and there is nowhere for a
continuation record to go.

Settling a cancel therefore needs one of:

- **extend `finishFallback`** (or add a sibling) to accept a conversation and a
  `ConversationGraphAuthorityMutation`, so the terminal is written in the same authority
  transaction as the command row. This changes `RuntimeRunAuthority`, an audited interface shared
  with the non-Claude-P paths.
- settle in `handleMessageComplete`'s `onCompletion`, which *does* have the conversation — but that
  is a plain `updateConversation`, outside any authority transaction, and the brief requires the
  authority transaction.

The failure path is affected too: `generationResult.onFailure` rethrows `CancellationException`
before its settle, so a cancel never reaches the `onFailure` branch either.

**This is why nothing was written.** Wiring the barrier and the two `handleMessageComplete` exits
while the cancel path has nowhere to settle would leave every cancelled turn in
`START_IN_FLIGHT` — the gate reads that as *a model may be running* and blocks the conversation.
That is the same all-or-nothing boundary the previous report described, and the compaction work
above does not move it: it makes the *record* writable, not the *transaction* available.

So item 2 is blocked on a decision about `RuntimeRunAuthority`'s shape, and it is a decision rather
than an implementation because it touches every generation path, not only Claude P.

## 3. Verification log

| Check | Command | Result |
|---|---|---|
| `:ai` compile | `./gradlew :ai:compileDebugKotlin --offline` | pass |
| `:app` compile | `./gradlew :app:compileDebugKotlin --offline` | pass |
| `:ai` tests | `./gradlew :ai:testDebugUnitTest --offline` | 73 classes, 0 failures, 0 skipped |
| `:app` full suite | `./gradlew :app:testDebugUnitTest --offline` | **4225 tests, 1 skipped, 1 failure** |
| Whitespace | `git diff --check` | clean |
| Room schema / dependencies | `git status --porcelain` | no entity, DAO, migration, `*.gradle*` change |

The single failure is `HardlineSelfPreservationTest` — the same pre-existing one. 4225 − 89 = 4136.

### Corrections made during this batch

- A test fixture built a `BIND_PENDING` record with no generation id to assert an illegal
  transition; the shape check refused it first, for a different reason. Given a generation id, so
  it tests the row it claims to.
- The settlement suite's "a supersede the table refuses" test used the now-legal
  `BOUND -> FAILED_CLOSED` as its example; retargeted to `FAILED_CLOSED -> START_IN_FLIGHT`, which
  is genuinely illegal.

## 4. Not done

- **Items 2–5.** §2 gives the blocking decision for item 2; items 3 (stale reconciliation), 4
  (deferred bind) and 5 (replay) follow it.
- **No instrumentation test, no device, no emulator.**
- **No Server run, no VPS, no Worker.** `session.bind` has still never been sent to a real Server.
- **No model call, no OAuth, no CLI child.**
- **No push, no CI, no deploy, no usage UI, no M4.**

---

The compaction semantics are complete and green. `auto` remains off, no barrier is written, and the
completion line is not written — item 2 needs a `RuntimeRunAuthority` decision before anything can
be wired, and §2 states it.
