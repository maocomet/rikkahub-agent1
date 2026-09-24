# M2-B — Android ToolRuntime adapter: implementation report

- Status: **INCOMPLETE. NOT ready for review as M2-B.**
- Base: `e833dbd6` (verified ancestor of HEAD)
- Branch / HEAD: `codex/claudep-cp1b-local` @ `f02417b1`
- Server contract of record: `rikkahub-claude-p-server` `4fbd76444ca2892fe08ac9945f64f8decc06a2fa`
- Working tree at time of writing: clean

## 0. Read this first

The three real Android execution paths the gate requires — Local tool, write-through-approval,
MCP tool — are **still not implemented**. What has changed since the last report is that the
two design rulings are applied and verified, and the `tool.*` public frames now exist and route.

The single most important thing in this report is **§3**: the provider catalog (B) **cannot**
be shipped before the runtime wiring (C). That is a finding, not an excuse, and it reorders the
remaining work.

## 1. What is implemented and verified

### 1.1 Vendored conformance corpus (unchanged from the previous report)

`claudep/conformance-bridge/vectors/` — byte-for-byte from the Server's `test/bridge/vectors/`,
`sha256sum -c MANIFEST.sha256` passing, `.gitattributes` `eol=lf` guard.

### 1.2 The Kotlin half of the byte contract

`ai/src/main/java/me/rerere/ai/provider/claudep/bridge/` — `BridgeContract`, `BridgeCanonical`,
`BridgeCatalog`, `BridgeBinding`, `BridgeArguments`, `BridgeLedger`, `BridgeToolCatalog`,
`BridgeToolAdapter`, `BridgeGenerationRegistry`.

### 1.3 Codex ruling 1 — generation lifecycle (commit `097ec9ca`)

`BridgeGenerationRegistry` keys adapters by the exact generation id and looks them up by nothing
else, so the forbidden cross-generation search has no expression. `open` is idempotent: a
reconnect returns the adapter that is already there, ledger intact — a fresh one would hold an
empty ledger and a re-delivered invoke would execute a second time. The capacity bound refuses a
new generation rather than evicting a live one, because an eviction discards a ledger and can
therefore cause a second execution. `close` concludes outstanding calls *before* dropping the
adapter.

### 1.4 Codex ruling 2 — `readOnly` cannot be forged (commit `097ec9ca`)

`BridgeToolCandidate` has a private constructor and no boolean parameter. Two factories name the
claim: `tool` (the default, effectful) and `provenReadOnly`, which refuses an MCP source because
Android cannot inspect a remote implementation to hold that proof. The ruling asks MCP tools to
*default* to false; refusing outright is stricter on purpose.

A reflective test asserts the shape, and it earned its place immediately: Kotlin emits a public
**synthetic** constructor carrying a `DefaultConstructorMarker` for the companion, so the
assertion had to be written against what the compiler actually emits rather than what
`private constructor` reads like.

### 1.5 `tool.*` frames and routing (commit `f02417b1`)

`tool.invoke`, `tool.cancel`, `tool.query.result` now have bodies, `ClaudePServerEvent` variants,
and membership in `KNOWN_SERVER_EVENT_TYPES`. They were previously left on the unknown-optional
path, which exists for forward compatibility — a tool invocation is not that, it is a request the
app is expected to answer.

The generation comes from the envelope and nowhere else. A tool frame whose envelope has no
generation is a named violation (`MISSING_TOOL_BINDING`) rather than a silent drop.

`ClaudePToolCallState` is the wire spelling and `ToolCallState` is the contract value; they are
deliberately two types. `ClaudePToolFrames` holds the rules for frames Android **sends** — a body
is legal only on `completed`, only the five reportable states are sendable, and an unreportable
outcome produces no frame at all.

## 2. How it was verified, and the limits

Same local harness as before: the Kotlin compiler bundled in the local Gradle distribution, the
Gradle-bundled stdlib and serialization libraries, no Android SDK, no CI.

**65 test methods pass, 0 failures**, across four suites. All files, including the decoding suite
that cannot run locally, compile.

Four caveats, stated because they bound what that proves:

1. **Version delta.** The project declares Kotlin `2.4.0` / `kotlinx-serialization-json` `1.11.0`;
   this was compiled against `2.3.0` / `1.9.0`.
2. **The JUnit run uses a local stub** of `org.junit` (`@Test`, the `Assert` methods). The test
   bodies and assertions genuinely execute; the runner is not the real one.
3. **The serialization compiler plugin is not available here**, so any test that decodes an
   `@Serializable` DTO cannot be *executed* locally — only compiled. `ClaudePToolFrameTest`
   (decoding and routing, 11 methods) is in that category: **written and compiling, not
   executed.** `ClaudePToolWireRulesTest` was split out precisely so the outbound rules — the
   security-relevant half — do run anywhere, including where the plugin is absent.
4. **No Android code was compiled at all.**

## 3. The ordering finding: B cannot precede C

I implemented the provider catalog (B) — building the frozen catalog from `params.tools`, sending
`tool_snapshot`, binding it into the fingerprint as canonical JSON — and then **reverted it**,
because wiring it now is a regression.

The reason is concrete. Sending a `tool_snapshot` makes the Server create a bridge and register
those tools with Claude Code. Claude will then call them. With no executor on Android, each
invocation reaches the device and is never answered, and the Server's `handleToolInvoke` waits on
`admission.wait` until the call's deadline — **30 minutes** (`MAX_DEADLINE_MS`) — before it times
out. Today, with no snapshot, Claude Code has no bridge tools and answers in text, which works.

So B is not "safe to land early". B is safe to land **only together with C**, or behind a seam
that is absent until C provides it. I chose to revert rather than ship either a regression or an
inert flag, and to report it instead.

**Revised ordering for the remaining work:** C's executor seam first, then B's wiring in the same
change.

## 4. Requirement coverage

### 4.1 Implemented and verified

| # | Requirement | Where |
|---|---|---|
| 1 | Catalog stable ordering and stable digest | `BridgeToolCatalog`, corpus catalog family |
| 2 | Local and MCP name mapping | `local and mcp tools map to their bridged names and keep their source` |
| 3 | Refuse on any device/assistant/generation/toolCall mismatch | adapter + registry suites, bindings vectors |
| 4 | Unfrozen tool refused | `a tool that was never frozen is refused and runs nothing` |
| 5 | Same invoke replays exactly once | `a repeated invoke executes once` |
| 6 | Same toolCallId, different arguments → conflict | `the same call id with different arguments conflicts and still executes once` |
| 7, 8, 9 | Query exact, inert, repeatable | `query` suite + lifecycle corpus |
| 13 | Cancel idempotent; honest when a stop cannot be guaranteed | `cancel is idempotent and propagates at most once`, `a call that completed after its cancel is reported completed, not cancelled` |
| 14, 15 | No re-dispatch after rebuild / reconnect | registry suite |
| 21 | Late/repeated terminal never changes a settled state | `a repeated terminal is not re-announced and a different one is dropped` |
| — | `readOnly` unforgeable; MCP never claimed read-only | ruling-2 suite |
| — | Generation lifecycle: keyed, idempotent open, no eviction, conclude-then-release | registry suite |
| — | Tool frames decode, route, and fail closed | `ClaudePToolFrameTest` (compiled; not executed locally) |
| — | Outbound frames: reportable states only, body only on completed, size bound | `ClaudePToolWireRulesTest` (executed) |

### 4.2 **Not** implemented

| # | Requirement | Status |
|---|---|---|
| 10 | pending approval → approve → execute → completed | **Not implemented.** |
| 11 | pending approval → reject | **Not implemented.** |
| 12 | pending approval → cancel, later approve executes zero times | **Not implemented.** |
| 16 | Local read-only tool end to end | **Not implemented.** |
| 17 | Local write tool through approval, end to end | **Not implemented.** |
| 18 | Connected MCP tool end to end via the real `McpManager` | **Not implemented.** |
| 19 | MCP credentials never enter a frame, log or snapshot | **Not testable yet** — no frame carries a tool call. |
| 22 | Old text-only chat path unchanged | **Unchanged** — the provider was reverted to its committed state. Not wired, so not regressed; equally, not proven. |
| 23 | cancel and stream regressions still pass | Not run (no CI). |

Also absent: the provider sending `tool_snapshot` (§3); the inbound tool frames reaching anything
that could answer them; the app-side executor; DI wiring for the registry and executor; the
wiring tests; instrumentation tests and their explicit class-name gate in the workflow.

## 5. Boundary compliance

- No Server file modified (read only, via `git show` at the contract SHA).
- No VPS connection, no model call, no tool side effect, no real MCP OAuth/token read.
- No new runtime dependency; nothing added to any build file or the version catalog.
- No push, CI dispatch, PR, tag, release or deploy. No M3 work.
- `git diff --check` passes; working tree clean; `e833dbd6` still an ancestor.

## 6. Commits

```
f02417b1 feat(claudep): add the tool.* frames and their routing rules
097ec9ca feat(claudep): make generation lifecycle structural and readOnly unforgeable
28f502ed docs(claudep): record M2-B implementation evidence and its gap
e73a4d54 test(claudep): cover tool bridge identity and lifecycle
57f2b1c2 feat(claudep): freeze the Android tool catalog and adapt bridge calls
5b162d39 feat(claudep): implement the Kotlin half of the M2 bridge contract
e833dbd6 (base)
```

## 7. What remains

1. **C first**: an executor seam on the provider (an injected host), and the app-side
   implementation that builds candidates from the real assistant tool set and runs admitted
   invocations through `DefaultToolRuntime` (`assess()` with the real arguments) with
   `ToolExecutionGate` as `preExecutionGate`, the existing `SecondUserApprovalLifecycle` and
   approval UI for anything needing human approval, and `McpManager` for MCP tools.
2. **Then B** in the same change, so the catalog is never sent without something to answer it.
3. The wiring tests for requirements 10–20 and 22–23, including the production-DI resolution
   tests and an instrumentation test that a Claude P pending tool appears in the existing
   approval UI — with explicit class names added to the workflow's execution gate, not assumed.
4. CI proof that the project's own toolchain compiles all of the above, and that
   `ClaudePToolFrameTest` executes somewhere the serialization plugin is present.

## 8. Requested verdict

Still **not** `M2-B complete`. The foundation is verified, the two rulings are applied, and the
frames exist. The gap is the executor, and §3 says it must come before the catalog rather than
after it.
