# M3-B — the generation settlement seam

- Status: **seam delivered and green. Attach, stale reconciliation, deferred bind and replay not
  started. `auto` stays off.**
- Base: `c935854d` → HEAD `de84ff52`
- Branch: `codex/claudep-cp1b-local`
- Working tree: the pre-existing untracked `web-ui/bun.lock`, and nothing else

## 1. What the seam is

Option (b), as specified: a sibling for generations, with `finishFallback` untouched.

```kotlin
suspend fun finishGenerationWithoutResult(
    terminalState: DurableCommandState,
    errorCode: String?,
    settlement: GenerationTerminalGraphSettlement?,
)
```

| | `finishFallback` | `finishGenerationWithoutResult` |
|---|---|---|
| conversation | none | read **inside** the authority transaction |
| graph mutation | none | the settled conversation, persisted by that same transaction |
| `resultAssistantMessageId` | `null` | `null` |
| used by | control commands, dispatch failures | generations that wrote a barrier |
| when no settlement | — | delegates to `finishFallback`, byte for byte |

`GenerationTerminalGraphSettlement` is a `fun interface` over
`(Conversation, DurableCommandState) -> Conversation?`. It carries **no Claude P type, no session,
no provider and no state vocabulary** — the authority layer only knows *here is a function that
turns the conversation this transaction is about to persist into the one it must persist instead,
or refuses*.

Three decisions inside it are worth a reviewer's attention:

- **A refusal throws.** The settlement returning `null` rolls the transaction back and leaves both
  the command and the graph untouched, so a run that cannot record what it owes is never durable as
  finished. That is the brief's "不得终结命令为成功" made structural rather than a rule.
- **The call site has no branch.** `ConversationRuntime` always calls the sibling and passes
  `run.control.consumeTerminalGraphSettlement()`. Which of the two paths happens is the *run's own
  attachment* to decide, so a generation path added later cannot forget to route itself.
- **The conversation is read inside the transaction.** `finish`'s body moved into
  `finishWithMutation`, which takes the mutation rather than a conversation — so the caller decides
  *when* the read happens, and every existing path keeps reading exactly where it did.

The mapping from the authority's terminal vocabulary onto the continuation's is one function on the
gate (`terminalFor`), not a decision inside the attached closure — a per-attachment copy is how the
same cancellation comes to mean `FAILED_CLOSED` on one path and `INTERRUPTED` on another. It is
also why the slot is told `DurableCommandState` rather than a provider outcome.

## 2. Tests

`ClaudePSessionTerminalSettlementSeamTest` — 6 tests:

| Brief's requirement | Test |
|---|---|
| control commands keep `finishFallback`, graph unchanged | `a run with no settlement consumes nothing` (the routing that produces it) |
| settlement consumed once | `a settlement is consumed exactly once` |
| attach-once | `a settlement can only be attached once` |
| cancel → `INTERRUPTED`, never `BOUND` | `a cancellation settles a branch to interrupted, never to bound` |
| model failure → `FAILED_CLOSED` | `the authority's terminal states map onto the continuation's` |
| a non-end state settles nothing | `a state that is not an end maps to no terminal` |

The **non-Claude-P behaviour-unchanged** and **control-command graph-unchanged** requirements are
covered structurally rather than by a test I can write at this layer: with no settlement the sibling
delegates to `finishFallback` on its first line, so the two are the same call. The full `:app` suite
(4231 tests, same single pre-existing failure) is the regression evidence that no existing path
moved.

## 3. What is left

In the order the brief sets:

1. **Attach** — Claude P admission attaches a settlement right after the barrier transaction
   commits. *Small now*: the slot, the routing and the sibling all exist and are tested, so this is
   one closure built from `ClaudePSessionContinuationGate.settle` plus the attach call.
2. **The admission barrier itself** — restore the write, now that the cancel path has somewhere to
   settle.
3. **`handleMessageComplete`** success/failure settles — the result-bearing `finish` path, which
   does *not* go through the sibling and needs its own call.
4. **Stale reconciliation** — a persisted `START_IN_FLIGHT` with no live run settles to
   `INTERRUPTED` at the next admission, in the transaction, with `generation = 0`.
5. **Deferred bind** — `BIND_PENDING` in the variant-commit transaction, one `session.bind` after,
   settled through the same functions.
6. **Replay** — user-triggered, once per connection.

Nothing is in the tree in a partial state: no barrier is written, nothing attaches a settlement, and
every path takes exactly the route it took before this commit.

## 4. Verification

| Check | Result |
|---|---|
| `:ai` compile / tests | pass / 73 classes, 0 failures |
| `:app` compile | pass |
| `:app` full suite | **4231 tests, 1 skipped, 1 failure** — same pre-existing `HardlineSelfPreservationTest` |
| `git diff --check` | clean |
| Room schema / dependencies | untouched |

### Corrections

- `assertNotNull` used as an expression again (JUnit 4 returns `Unit`); replaced with `requireNotNull`
  and a boolean assertion. Second time in this branch — it is now in the report twice rather than
  fixed once, which is itself the finding: the test helpers in this module would benefit from the
  `present(...)` helper the settlement suite already has.

## 5. Not done

- Items 1–6 of §3.
- No instrumentation test, device or emulator. No Server run, VPS or Worker — `session.bind` has
  still never been sent to a real Server. No model call, OAuth or CLI child.
- No push, no CI run, no deploy, no usage UI, no M4.

---

The seam is complete and green. `auto` remains off, no barrier is written, and the completion line
is not written.
