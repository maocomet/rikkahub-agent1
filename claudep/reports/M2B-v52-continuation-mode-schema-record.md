# M2-B — v52 approval continuation mode: schema record

- Status: **schema bootstrap in progress. Not a release state.**
- Branch: `codex/claudep-cp1b-local`
- Server contract of record: `rikkahub-claude-p-server` `4fbd76444ca2892fe08ac9945f64f8decc06a2fa` (read only)
- Scope of this record: the Room schema change that persists `RESUME_COMMAND` / `IN_FLIGHT`, how the
  identity hash is obtained, and the two decisions that were taken about it.

## 0. Why this exists

M2-B's approval half needs to distinguish two continuations that reach the same `APPROVED` status
and differ in everything after it. `RESUME_COMMAND` is the existing behaviour: the generation has
already ended, so approval creates a deterministic resume command. `IN_FLIGHT` is the Claude P
bridge: the generation is still blocked inside the Worker, so approval releases that waiter and
must never create a second generation. Nothing in the ledger expressed that distinction, and it
cannot be inferred — an inferred mode is a guess about who is waiting.

## 1. The schema change

`pending_tool_approvals` gains one column, appended last and carrying a database default:

```sql
ALTER TABLE `pending_tool_approvals`
  ADD COLUMN `continuation_mode` TEXT NOT NULL DEFAULT 'RESUME_COMMAND'
```

`'RESUME_COMMAND'` is not a placeholder standing in for "unknown". Every row written before v52
belongs to the behaviour that existed before v52, so the default is what those rows *mean*; a
backfill to anything else would silently reclassify live approvals.

Room version 51 → 52. The touched surface is: the entity, `AppDatabase`, `Migration_51_52`, the DI
migration registry, `ImportedDatabaseReconciler` (staged cold-restore chain, raw `ensureColumns`
mirror, vocabulary validation), `AppDatabaseEveryAdjacentMigrationTest`, a new
`Migration_51_52_Test`, and the `migration-instrumentation.yml` allowlist plus an exact-test-count
gate. Both existing barrier writers now name `RESUME_COMMAND` explicitly rather than inheriting the
default, so the value is a statement instead of an omission.

## 2. Ruling: no SQL `CHECK`

The requirement asked for a "closed `CHECK` vocabulary". That is not expressible here.

- Room 2.8.4 has no entity-level `CHECK` support, and `grep -c "CHECK ("` over all 51 exported
  schemas returns 0 for every one of them.
- A hand-written `CHECK`, added in the migration and in the reconciler's raw DDL, would make the
  migrated table differ from the table Room creates on a fresh install — and the schema KSP exports
  for v52 comes from the entity, so it would not carry the `CHECK` either. Which install a user has
  would then decide what the column means.
- The only SQL-level alternative Room can express is a foreign key to a lookup table, which needs a
  full table rebuild (SQLite cannot `ALTER TABLE ADD CONSTRAINT`) plus seeding of the lookup rows on
  three separate paths — fresh install, migration, and cold restore. Missing one seeding path makes
  the entire approval ledger unwritable.

**Decision: the vocabulary is closed in Kotlin.** Concretely:

- `ApprovalContinuationMode` is the only thing that produces a value the app writes.
- `fromWireOrNull` / `fromWire` match exactly and case-sensitively and have **no fallback**. An
  unrecognised value is `null` or a failure, never `RESUME_COMMAND`.
- The fallback is the dangerous answer, which is why it is the one that is absent: it looks like a
  correct read and then creates the resume command that `IN_FLIGHT` exists to suppress.
- `ImportedDatabaseReconciler.requireV52ContinuationModeSchema` refuses a restored database holding
  any value outside the two. It does not repair and does not guess one.

**SQLite provides no constraint here, and nothing in this change may claim one.** There is no
database-level guarantee; there is a Kotlin-level one plus a fail-closed read and import boundary.

## 3. How the identity hash is obtained, and the first attempt that failed

`ImportedDatabaseReconciler.EXPECTED_IDENTITY_HASH` must equal the `identityHash` KSP writes into
`AppDatabase/52.json`. It "cannot be derived by hand and must never be guessed" (this file's own
words, pre-existing), and `AppDatabaseSchemaIdentityContractTest` compares it against the export the
compiler produced during the same build, so a guess fails the suite immediately.

### Attempt 1 (failed) — committed a placeholder `52.json`

The assumption was that committing a sentinel-valued `52.json` would let KSP regenerate it with the
real identity, which would then be read out of the run's artifact.

Run `35989321067`, `workflow_dispatch`, on `9d8ae290`.

**It did not regenerate the file.** `:app:kspDebugKotlin` succeeded (log line 1134) and
`:app:compileDebugKotlin` succeeded, yet the uploaded `app/schemas/**` artifact was **byte-identical
to the committed file**, sentinel included:

```
sha256 (artifact  AppDatabase/52.json) = 31584d6d6873cc42675761ce690649e42892fbb30c4b7bcd6961318776f567ae
sha256 (committed AppDatabase/52.json) = 31584d6d6873cc42675761ce690649e42892fbb30c4b7bcd6961318776f567ae
```

KSP exports a schema version by *creating* its file; it does not overwrite one that already exists.
So a committed placeholder does not get replaced — it blocks the value it stands in for, and the
block is silent because the build stays green. v51 only worked because `51.json` was **absent** when
its bootstrap ran.

The same run also failed earlier, on an unrelated defect in this session's own test code:
`Migration_51_52_Test.kt` used `val shape = assertNotNull(...)`, which is JUnit **5**'s
value-returning shape. The project pins JUnit 4.13.2, where `assertNotNull` returns `Unit`. The
local JUnit stub used for on-machine verification had declared a generic value-returning overload —
a shape nothing needed — so the misuse compiled locally and could only ever fail on CI. The stub has
been tightened to the real JUnit 4 signature; a stub that accepts code the real dependency rejects
converts a compile error into a false pass.

### Attempt 2 (this commit) — the file is absent

`app/schemas/me.rerere.rikkahub.data.db.AppDatabase/52.json` is removed from the tree. KSP therefore
creates it, and the artifact carries a genuine export. The cost is the declared one:
`MigrationTestHelper` reads the target version's export from androidTest assets, so every v52
migration test, and the v50 backup/restore and grant-restore suites that now target v52, fail with
"Cannot find the schema file in the assets folder" until the real export is committed.

The sentinel stays in `EXPECTED_IDENTITY_HASH`, because the alternative — a plausible-looking
32-hex value — is exactly what must not be invented.

### Conditions the run must meet before the value is copied in

`:app:kspDebugKotlin`, `:app:compileDebugKotlin` and `:app:compileDebugAndroidTestKotlin` all
succeed; no other Kotlin compile errors; the artifact contains a complete KSP-generated
`AppDatabase/52.json`; that file contains no sentinel; `version == 52`; `identityHash` is 32
lower-case hex; `setupQueries` stamps the same hash; the expected
`continuation_mode TEXT NOT NULL DEFAULT 'RESUME_COMMAND'` is present; and there is no other drift
from v51. Anything else stops the work rather than being repaired.

## 4. Boundaries

No Server file was read or modified beyond the contract SHA; no VPS, model call or device was
touched; no dependency was added; no M3 work. CI was dispatched twice in total, both
`workflow_dispatch` on the exact branch ref, both authorized explicitly before the fact.
