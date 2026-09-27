# M3-B — production closure: progress report

- Status: **items 1 and 2 delivered and green; items 3–4 not started. `auto` remains off, by the
  brief's own rule. Awaiting Codex review.**
- Base: `cbf5bd6f` → HEAD `069a4eda`
- Branch: `codex/claudep-cp1b-local`
- Working tree: the pre-existing untracked `web-ui/bun.lock`, and nothing else
- Server: read-only, unchanged. No protocol, schema, dependency or workflow change.

## 0. Read this first

| Brief item | State |
|---|---|
| 1. Fix regenerate identity | **done** — `f415a73d` |
| 2. Single terminal settlement seam | **done** — `069a4eda` |
| 3. Process-death reconciliation | **not started** |
| 4. Production wiring (admission barrier, `auto`, bind, replay) | **not started** |
| 5. Required test matrix | partially — see §4 |
| 6. Commits | 2 of 5; the remaining three have nothing to carry yet |

The brief permits exactly this stopping point: *"若不能在本轮完整覆盖 admission、所有
terminal/fallback、stale reconciliation、bind 和 replay，则不要打开 `auto`；停在最近一个可验证提交，
列出剩余项."* No barrier has been written, so no branch can be stranded, and production Claude P is
byte-for-byte what it was at `cbf5bd6f`.

**One commit-hygiene note, stated rather than hidden.** The settlement seam's *code* landed in
`f415a73d`, whose message describes only the identity fix and the refusal removal — I wrote the seam
into the same file before committing, and `f415a73d` therefore carries something its message does not
mention. `069a4eda` carries its tests and the CI entry. Nothing is pushed; a reviewer reading
`f415a73d` for the identity fix will find roughly 200 extra lines that belong to `069a4eda`.

## 1. Item 1 — the regenerate identity (`f415a73d`)

A user-target regenerate truncates: `executeRegenerateInline` keeps the nodes up to and including
the target and drops the rest. The assistant node the generation produces lands at
`selectIndex == 0`, so the committed branch is exactly the identity of the **truncated** graph — and
that is now the graph the identity is read from.

Reading it from the graph as *admitted* agreed only while every dropped node sat at
`selectIndex == 0`. When any later node had a selected non-default variant the two differed, and
the difference travelled as `remote_branch_id` with nothing to announce it: the Server accepts the
digest, resolves a session for a branch that is not the one about to exist, and the app records a
binding against the same wrong identity.

`ClaudePSessionBranchPlanner.graphAfterCommand` is the executable form of the sentence `classify`'s
doc already contained, placed beside `classify` because that is where "what does this command do to
the graph" is written down. `plan` computes the candidate graph **once** and uses it for both the
branch identity and the resolver, so the identity used for the continuation metadata, the
`ClaudePSessionBindingRequest` and the wire request is one value computed one time.

**Why this is the fix and not a workaround.** The obvious alternative — truncate the admission
graph so its identity happens to match — would move the truncation into the admission transaction,
changing when a source-authority tombstone is written for every user-target regenerate. The
identity is a *derived* value; deriving it from the right graph is the smaller change and does not
touch durable writes.

Tests (`f415a73d`):

- `a user regenerate binds the identity of the graph it is about to commit` — the decision's branch
  id equals the truncated graph's, **and is asserted not to equal the admitted graph's**, so the
  test cannot pass against a build that never truncated;
- `a user regenerate whose suffix selects nothing keeps the same identity` — the case where the old
  computation happened to be right;
- `the decision carries the same id in the request and the metadata record` — the three identities
  agree;
- planner-level: `a user regenerate truncates the graph at its target`,
  `an assistant regenerate leaves the graph alone` (truncating there would delete the node the new
  variant appends to), `a regenerate at the graph tip is a no-op`, `a send leaves the graph alone`.

**`Reason.BARRIER_NOT_ATOMIC` was deleted, not re-documented.** Its premise is gone: the
content-integrity projection means a continuation-only write no longer moves a message's source
revision, proved by `ConversationContinuationSourceRevisionTest`. A refusal whose reason no longer
exists refuses real work, which is why the brief asked for its removal rather than a rewrite.

## 2. Item 2 — the settlement seam (`069a4eda`)

`Obligation.of(decision)` turns the admission decision into the one value a run carries, and
`settleImmediate` is **the only place a terminal becomes a durable state**:

| Terminal | State | Consequence |
|---|---|---|
| `SUCCEEDED` | `BOUND` | the turn completed, so the binding is proven and the branch is resumable |
| `FAILED` | `FAILED_CLOSED` | absorbing; a second generation cannot quietly start |
| `UNPROVEN` | `INTERRUPTED` | carries no generation id, so `replay` offers nothing |

The three are asserted as a *set*, because the failure this guards against is two of them collapsing
into one — which is how a cancellation comes to look like a failure, or worse, like a success.

Two refusals are the substance:

- **`NothingToWrite`** when the graph grew no message a terminal could legally attach to. Writing it
  onto the barrier would fold as a revision regression; the branch stays `START_IN_FLIGHT`, which is
  *closed*. A turn that cannot be settled must not look resumable.
- **`deferredPending`** reads the branch identity from the graph **as it will be committed** and
  returns the conversation and the identity together, so a caller cannot commit the variant with one
  digest and bind it with another.

Tests assert against a **re-resolution** of the settled graph, not against the record written: a
record that is attached but not resolvable is the same as no record at all, and only the resolver
knows the difference.

Compatibility: `Conversation`'s `currentMessages` remains the authority for the selected path and
nothing about the record layout changed, so messages written by any earlier build still decode — the
projection is presentational and the field is unchanged.

## 3. Items 3 and 4 — not started

**Item 3, process-death reconciliation.** The shape is settled: at the next authority/admission pass
for a conversation, a branch whose persisted state is `START_IN_FLIGHT` with **no live run for this
process** settles to `INTERRUPTED`, in the same transaction, and the request fails closed rather
than silently starting a generation. The information needed already exists —
`GenerationRunControl` / `RuntimeRunAuthoritySlot` distinguish a live run from a stale record — but
identifying the live-run set correctly, for every origin, is the work.

**Item 4, production wiring.** Five settle sites, unchanged from the previous report, and none is
wired:

| Site | File | Terminal |
|---|---|---|
| successful generation | `ChatService.handleMessageComplete` `onSuccess` | `SUCCEEDED` |
| model failure | `ChatService.handleMessageComplete` `onFailure` | `FAILED` |
| cancellation / stop | `ConversationRuntime.kt:2092` → `finishFallback` | `UNPROVEN` |
| disconnect / process death | none exists | `UNPROVEN` |
| tool-loop WAITING | `checkpointWaiting` | **must not settle** |

Then: the admission barrier, the binding request through `handleMessageComplete`, the deferred
commit + bind, and the user-triggered replay.

**The barrier stays off until all of that is in.** Writing `START_IN_FLIGHT` while any exit can fail
to settle it strands the branch in a state the gate reads as "a model may be running" — a
conversation unusable after one turn. That is the state the brief forbids, and it is worse than no
barrier.

## 4. The required test matrix (§5)

| Scenario | State |
|---|---|
| immediate `START_IN_FLIGHT` → `BOUND` | covered (`ClaudePSessionSettlementTest`) |
| deferred: no branch dispatch → `BIND_PENDING` → bind → `BOUND` | pending-write covered; bind + settle not |
| `already_bound` / `conflict` / `candidate_unavailable` / `refused` | covered at the mapping (`ClaudePSessionContinuationGateTest`) |
| model failure → `FAILED_CLOSED` | covered at the seam; not at the call site |
| explicit cancel → `INTERRUPTED` | covered at the seam; not at the call site |
| transport disconnect → `INTERRUPTED` | covered at the seam; no call site exists |
| stale `START_IN_FLIGHT` after restart → `INTERRUPTED`, generation count 0 | **not written** (item 3) |
| a live run is not mistaken for stale | **not written** (item 3) |
| regenerate truncating a non-default suffix: metadata = wire = persisted | covered |
| approval continuation is one generation | covered (`a replayed approval continues the in-flight generation…`) |
| replay is user-triggered and at most once per connection | covered at the orchestrator (`replayBeforeGeneration`); no call site |
| non-Claude-P provider unchanged | **not written** |
| legacy `mode: "new"` unchanged | **not written** |

The last two matter and are cheap: they are the "did we break anyone else" pair, and the conformance
corpus in `:ai` covers the wire half already.

## 5. Verification log

| Check | Command | Result |
|---|---|---|
| `:ai` compile | `./gradlew :ai:compileDebugKotlin --offline` | pass |
| `:app` compile | `./gradlew :app:compileDebugKotlin --offline` | pass |
| `:ai` tests | `./gradlew :ai:testDebugUnitTest --offline` | 73 classes, 0 failures, 0 skipped |
| `:app` full suite | `./gradlew :app:testDebugUnitTest --offline` | **4214 tests, 1 skipped, 1 failure** |
| Whitespace | `git diff --check` | clean |
| Workflow YAML | `yaml.safe_load` | loads; `jobs: [build-debug-apk]` |
| Room schema / dependencies | `git diff --name-only cbf5bd6f..HEAD` filtered | no match |

The single failure is `HardlineSelfPreservationTest` — the same pre-existing one recorded at
`4136`. 4214 − 78 = 4136, where 78 is the classes this branch has added across all its commits
(42 gate + 6 observer + 7 projection + 7 source-revision + 11 settlement + 5 new planner/gate).

This is real compilation and real test execution.

### Corrections made during this batch

- Two settlement fixtures disagreed with themselves: one passed an obligation revision the barrier
  did not carry (folding as a `REVISION_GAP`), and one settled a send that had produced no answer
  at all (correctly `NothingToWrite`). Both were fixture bugs; the gate was right both times.
- `ClaudePSessionContinuationGateTest`'s message helper left `createdAt` at the current time, so its
  one digest-sensitive assertion was passing by luck and failed the first time it ran on a slower
  machine. Pinned, with the reason written at the helper. The same trap was fixed in two other
  suites in the previous batch.
- `assertNotNull` returns `Unit` in JUnit 4, so it cannot be used as an expression. Replaced with a
  local `present(...)` helper that keeps the failure message.

## 6. Not done

- **Items 3 and 4.** §3.
- **No instrumentation test, no device, no emulator.** Nothing in this batch needed one.
- **No Server run, no VPS, no Worker.** `session.bind` has still never been sent to a real Server.
- **No model call, no OAuth, no CLI child.**
- **No push, no CI, no deploy, no usage UI, no M4.**

---

Items 1 and 2 of the batch are complete and green. `auto` is **not** enabled and production Claude P
is unchanged — the barrier, the bind and the replay have no call site yet, and per the brief the
completion line is not written.
