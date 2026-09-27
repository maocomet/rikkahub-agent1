# M3-A2a — Android stable/volatile prompt separation: implementation report

- Status: **implementation complete, awaiting Codex review.**
- Base: `f8c94ce8` (HEAD at the time of writing; unchanged by this batch)
- Branch: `codex/claudep-cp1b-local`
- Working tree: the pre-existing untracked `web-ui/bun.lock`, and this batch's own files
- Server: read-only, still at `d2f2ea6`. No `ConfigFingerprintV2` work was started.

## 0. Read this first

**This deliberately changes where some content sits in the model's input.** A system-position
lorebook entry now reaches the model one message layer down, in the last user turn, instead of
inside the system message. That is a change of **message role**, not a re-encoding, and nothing
here claims the model's input is byte-equivalent to what it was before. It is a Claude-P-only
layout rule, required because the system instruction is part of the identity a remote session is
continued under — see §2.

What is *not* changed: any other provider. Every new branch is gated on a provider capability, and
§6 states how that is proved rather than asserted.

## 1. The problem, stated as the server needs it

The server side of M3 continues a Claude session by binding it to a configuration fingerprint. That
fingerprint includes the system instruction, because the Worker writes `system_prompt` to a file and
hands it to the CLI as `--system-prompt-file`. An instruction that changes between two turns of one
branch therefore makes the session uncontinuable — not "less cacheable", uncontinuable.

On this worktree the instruction changed on almost every turn, through four independent paths, all
verified at the real call site rather than inferred from the generic provider pipeline:

| Source | What it wrote into SYSTEM |
|---|---|
| `PlaceholderTransformer` (no role filter) | `{{cur_date}}`, `{{cur_time}}`, `{{cur_datetime}}`, `{{battery_level}}`, `{{model_name}}`, `{{locale}}`, `{{timezone}}`, `{{device_info}}`, `{{system_version}}`, `{{user}}` |
| `PromptInjectionTransformer` | `BEFORE_SYSTEM_PROMPT` / `AFTER_SYSTEM_PROMPT` content, with lorebook entries triggered by the current conversation |
| `ProviderSystemPromptLayout` (`useAnchoredVolatileContext == false` for Claude P) | the whole `<provider_runtime_context>` envelope: memory, recent chats, user identity, per-call addendum |
| `WorkspaceReminderTransformer`, `TemplateTransformer` | a work-space prompt; a user-authored message template whose `time`/`date` come from the message's `createdAt` — and the system message is rebuilt every turn |

The shipped default assistant prompt contains `- Date: {{cur_date}}`, so with no further
configuration at all the instruction changed once a day.

## 2. The approved design, and why this shape

Reuse the v1-r4 wire. No new frame field, no v1-r5. The system message keeps a **fixed reference**
where a dynamic value would have gone, and this turn's resolved values travel in the runtime context
that is anchored to the last user turn:

```
SYSTEM                       Date: <runtime_value_ref name="cur_date"/>
last user turn               <provider_runtime_context>
                               <runtime_values>
                                 <runtime_value name="cur_date">2026-09-27</runtime_value>
                               </runtime_values>
                             </provider_runtime_context>
```

Three properties follow, and each was a decision rather than a default:

- **The reference is not the raw `{{cur_date}}` token.** The raw token is what a substitution pass
  recognises, so leaving it invites a later pass to resolve it back into the message that must stay
  stable; and the brief this implements requires the model to receive a value rather than a template
  it is being asked to interpret.
- **The vocabulary is closed.** A key this build has not classified is refused, never passed through.
  A key nobody has classified is a key nobody has proved anything about, and the failure it causes is
  invisible at the time.
- **Refusal is the fallback everywhere.** Missing value, conflicting value, unclassifiable key, or any
  system message that does not match what the app froze ⇒ the turn is refused before `generation.start`.

## 3. What was built

| File | Change |
|---|---|
| `ai/.../provider/Provider.kt` | `StableSystemPromptProvider` marker; `TextGenerationParams.stableSystemPromptExpectation` (`@Transient`) |
| `ai/.../providers/ClaudePProvider.kt` | implements the marker; `requireStableSystemPrompt` at the dispatch boundary; two new `ClaudePUnsupportedInput` members |
| `app/.../data/ai/prompt/PromptReferencePolicy.kt` | **new** — the classification table, the marker syntax, the shared rewrite, the concrete rewrite, XML escaping |
| `app/.../data/ai/prompt/StableSystemPromptSession.kt` | **new** — the per-request accumulator, `RuntimeContextSection`, placement/origin metadata, canonical-order rendering |
| `app/.../transformers/Transformer.kt` | `TransformerContext.stableSystemPromptSession` (defaulted) and the matching `transforms(...)` parameter |
| `app/.../transformers/PlaceholderTransformer.kt` | records resolved values and neutralises SYSTEM when a session is present; unchanged otherwise; registry's `char` now delegates to the policy |
| `app/.../transformers/PromptInjectionTransformer.kt` | relocates system-position injections when a session is present; `applyNonSystemPositions` extracted and shared |
| `app/.../ProviderSystemPromptLayout.kt` | `withAdditionalVolatileContext(extra)` — appends to the volatile channel and cannot touch `initialMessages` |
| `app/.../GenerationHandler.kt` | anchors volatile context for the capability; creates the session; **freezes the expectation from the layout before the transformer pass**; renders the session into the volatile channel; passes the transient expectation |

### The classification, as frozen

| Key | Class | Why |
|---|---|---|
| `char` | stable | `assistant.name` — assistant configuration |
| `cur_date`, `cur_time`, `cur_datetime` | dynamic | wall clock |
| `model_id`, `model_name` | dynamic | per-request model; M3 excludes the model alias from the continuation identity, so switching models mid-thread must not invalidate a session |
| `locale`, `timezone`, `system_version`, `device_info`, `battery_level` | dynamic | device state — stable for long stretches, not *provably* stable between two turns |
| `nickname`, `user` | dynamic | editable at any moment |
| anything else, in app-composed text | **refused** | cannot be shown to be stable |

## 4. The review constraints, one by one

1. **The expectation is frozen independently.** `freezeStableSystemPrompt` reads the **layout**, not
   the outgoing messages, and runs before the transformer pass. The provider compares frozen-expected
   against final-actual. Nothing re-derives it afterwards.
2. **Scope is app-composed text only.** The neutraliser runs on SYSTEM messages and on relocated
   sections. User-authored text keeps the substitution it always had, and a user who types
   `{{mystery}}` keeps it verbatim — proved by `a placeholder a user typed is left exactly as they
   typed it`.
3. **Positional handling.** SYSTEM ⇒ marker; runtime context and relocated sections ⇒ concrete
   values; ordinary user content ⇒ unchanged. A relocated injection containing a registered
   placeholder is resolved, so no raw token survives anywhere in app-generated input.
4. **Fixed vocabulary, order and encoding.** Closed table; `<runtime_values>` emitted in
   `CANONICAL_KEY_ORDER`, never map-iteration order; one value per name (a second, different value is
   a refusal); names, values, placement and origin all escaped; missing/conflicting/unclassifiable ⇒
   refused before dispatch.
5. **The session is a per-request value object.** No singleton, no thread-local, no cross-request
   cache. A second turn makes a new one; nothing survives a normal end, a cancel or an exception.
   `StableSystemPromptSessionTest` pins the isolation and the empty-after-completion property.
6. **Workspace and template, explicitly.** Neither is edited. The default path reaches neither: the
   default `messageTemplate` is `{{ message }}` (the identity) and the default assistant has no
   `workspaceId`. Both are pinned in `StableSystemPromptDefaultPathTest`, and the non-default template
   has a **dedicated** refusal test rather than an incidental mismatch. Content that is dynamic with
   no safe relocation this batch fails closed **only when the feature is enabled** — ordinary Claude P
   chat and the tool path are unaffected.
7. **Composition test**, see §5.
8. **Refusal tests**, see §5.
9. **Other providers protected by tests.** `ProviderSystemPromptLayoutTest` and
   `PromptInjectionTransformerTest` each pair a "session absent ⇒ byte-identical to before" case with
   a "session present ⇒ the new behaviour" case, and the existing OpenAI / Claude API / Gemini /
   AICore characterization suites are untouched and green (§6).
10. **The role change is stated, not glossed** — §0.

## 5. Tests

64 test methods added: 57 across the five files below, and 7 in the two existing suites.

| Suite | What it pins |
|---|---|
| `PromptReferencePolicyTest` (22) | the marker shape; the classification of every key the default assistant uses; the single-brace form; refusal of an unclassified key; refusal of a value that would introduce a token; the exception carries the key and never the sentence around it; canonical resolution; escaping |
| `StableSystemPromptSessionTest` (12) | the exact rendered bytes; canonical order independent of recording order; one value per name; blank sections dropped; hostile values and section bodies cannot forge a boundary; **two sessions cannot see each other**; a fresh session is empty |
| `StableSystemPromptCompositionTest` (12) | default assistant config → the real `SystemPromptBuilder`, `ProviderSystemPromptLayout`, `PromptInjectionTransformer`, `neutralizeStableSystemMessage`, `StableSystemPromptSession`, and out to a **real `ClaudePGenerationStartBody`** |
| `ClaudePProviderStableSystemPromptTest` (8) | the wire boundary |
| `StableSystemPromptDefaultPathTest` (3) | the default-path preconditions |
| extensions to `ProviderSystemPromptLayoutTest` (3) and `PromptInjectionTransformerTest` (4) | the new behaviour, paired with the unchanged one |

The composition test asserts, in one run: `startGenerationCallCount == 1`; the dispatched
`systemPrompt` is **exactly** the independently frozen bytes; two turns a day apart dispatch identical
bytes; the resolved date appears in the last user turn and in no other message; SYSTEM carries no
concrete value, no envelope and no raw token; a lorebook firing moves the user turn only, keeping
`placement` and `origin`; editing the assistant prompt **does** move the bytes; memory and recent
chats move the user turn only; a turn with nothing dynamic adds no runtime block at all.

The refusal tests assert `startGenerationCallCount == 0` and `remoteDispatchCount == 0` for: a
post-freeze SYSTEM mutation; a missing expectation with a SYSTEM present; and — by name, as its own
case — a clock-naming message template. A refusal issues no second request and no retry.

### What the tests do **not** execute, stated rather than implied

- **`PlaceholderTransformer`'s Android dependencies.** The SYSTEM rewrite lives in
  `neutralizeStableSystemMessage`, a top-level pure function, and the composition test drives **that**
  — the production code — with the platform resolvers substituted. What is not exercised end to end is
  the Koin `SettingsStore` lookup and the battery/`Context` resolvers.
- **`TemplateTransformer` itself.** `PebbleEngine` binds SLF4J, whose only provider on this classpath
  is the Android binding, so an engine cannot be constructed in a JVM unit test. The default template
  is pinned as a string; the template *drift* is proved at the wire by its shape.
- **`GenerationHandler` end to end.** It needs an Android `Context`. The freeze step it performs is
  reproduced in the composition test by applying the same function to the same layout, and the
  wire-side comparison it feeds is asserted against the real provider.
- **`WorkspaceReminderTransformer`.** What is pinned is the precondition its guard reads
  (`workspaceId == null`), not the guard's execution.

## 6. Verification

Run on this revision, with the local Android SDK from `local.properties`.

- `./gradlew :ai:testDebugUnitTest :app:testDebugUnitTest`
  - `:ai` — **755 tests, 0 failed, 0 skipped.**
  - `:app` — **4106 tests, 1 failed, 1 skipped.**
- `git diff --check` — clean.
- No new dependencies; no credential read at any point; no `push`, no CI, no deploy, no VPS, no model
  call. The server is untouched.

### The one failure, isolated rather than attributed

`me.rerere.rikkahub.data.ai.tools.HardlineSelfPreservationTest > default hardline policy protects
the installed application id`.

**Not caused by this batch, and provably so.** The test asserts that
`pm uninstall me.rerere.rikkahub` is blocked by the guard's *default* policy. That policy is
`SelfPreservationPolicy.forApplication(BuildConfig.APPLICATION_ID)`
(`HardlineCommandGuard.kt:42-44`), and this build's debug variant sets
`applicationIdSuffix = ".agenttest"` (`app/build.gradle.kts:152`), so the generated constant is the
suffixed package:

```
app/build/generated/source/buildConfig/debug/me/rerere/rikkahub/BuildConfig.java
  public static final String APPLICATION_ID = "me.rerere.rikkahub.agenttest";
```

The guard therefore protects `me.rerere.rikkahub.agenttest`, the command in the test names a
*different* package, and it is correctly not blocked. The assertion can only hold in a variant whose
application id carries no suffix, which is why the debug unit-test task is the one that fails — for
any revision, including one with no changes at all. None of this batch's nine changed files is in
that path, and `git diff --name-only` shows no build-script, `BuildConfig`, guard or policy file.

`testReleaseUnitTest` does not exist in this project, so the same test could not be re-run under the
unsuffixed variant to demonstrate the flip; the generated constant above is the evidence instead.

## R1 — the case-insensitivity the legacy transformer had

`PlaceholderTransformer` matched registered keys with `ignoreCase = true`, for **both** brace forms.
`PromptReferencePolicy` matched them exactly, against the lowercase registry. That is not "stricter",
and the difference is not symmetric:

- `{{CUR_DATE}}` was refused as unclassified — loud, and wrong.
- `{CUR_DATE}` matched nothing, was left in the text, and was left **identically** in the frozen
  expectation. The system message the app froze and the one it dispatched still agreed, so the
  dispatch check passed with a raw placeholder inside the system instruction. The failure mode was
  silence, which is the one thing this module exists to remove.

Both are fixed by canonicalising before every lookup:

- both brace forms match case-insensitively for registered keys;
- the marker, the recorded value and the refusal reason all name the **canonical lowercase** key, so
  `{{CUR_DATE}}` and `{Cur_Date}` both become `<runtime_value_ref name="cur_date"/>` and share one
  canonical value in the runtime block;
- an unknown double-brace token is still refused, compared through the same canonicalisation;
- an unknown single-brace token is still left as ordinary text, and user messages are still neither
  interpreted nor refused.

`PromptReferencePolicyTest` now asserts this over the **whole vocabulary** — every registered key ×
{lowercase, uppercase, mixed} × {`{{ }}`, `{ }`} — because a hardcoded example list would have to be
extended by whoever adds the next key, which is exactly when the hole would reopen. The composition
suite adds a mixed-case assistant prompt taken end to end to a real `ClaudePGenerationStartBody`, and
a scan that fails on any raw registered token in any case anywhere in the request.

One thing the fix had to restore: `referencedKeys` had been silently reordered from the registry's
order to the text's. The contract is a function of the key **set** — two templates using the same keys
must report them identically — so it is sorted back into registry order.

## 8. Deliberate consequences for review

1. **A work space, or a non-default message template, fails closed on Claude P.** Both can write to
   the system message, neither can be shown to be stable from the provider's position. Relocating the
   work-space prompt into the runtime context is the obvious follow-up and is **not** done here — it
   is outside this batch's list, and guessing would be worse than refusing.
2. **The system prompt still contains the constant `PROVIDER_RUNTIME_CONTEXT_POLICY`**, a fixed
   sentence that *names* the envelope in order to tell the model what the suffix on its turn means. It
   is identical on every turn; the envelope itself is not in the system prompt. The composition test
   asserts the envelope's rendered shape rather than the bare name, and says why.
3. **The reference carries no value of its own**, so a reader of the system prompt alone cannot see
   the date. That is the point — the value is in the turn — but it does mean the two halves must be
   read together.

## 9. Not in this batch

M3-A2b: the server-side `ConfigFingerprintV2`. The server is untouched at `d2f2ea6`.
