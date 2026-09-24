# M2 bridge conformance corpus

Byte-level evidence for the contract in `src/bridge/`. **This repository is the authoritative
source**, and these vectors will be vendored *into* `maocomet/rikkahub-agent1` when M2-B
implements the Kotlin half.

## Why this is not in `conformance/`

`conformance/` holds the Claude P wire protocol, and it runs the other way round: it is
authored in the Android repository and vendored here, and its own README is explicit that a
corpus change is a protocol change decided in the specification document, with both test
suites updated together. Adding a bridge vector family to it is not something this repository
can do on its own, and regenerating its manifest to make room would destroy the one property
that manifest exists to provide.

The bridge is the opposite direction. The Server defines this contract; M2-B implements the
other half. So the vectors are authored here, and they live under `test/` because that tree is
already walked by both of this repository's secret scans — a new corpus cannot become a place
where a credential is pasted unexamined.

## Layout

```text
test/bridge/vectors/
├─ canonical.json   Canonical JSON: exact output bytes, and what is refused
├─ catalog.json     Frozen catalog: digest, name mapping, and what is refused
├─ bindings.json    Generation and invocation bindings, argument digests
├─ lifecycle.json   Idempotency decisions, exact-call queries, Android answers
└─ MANIFEST.sha256  SHA-256 of every file above
```

## What the corpus is, and what it is not

The vector **inputs** and their notes are written by hand in
`scripts/build-bridge-corpus.mjs`; the **outputs** are computed by running this repository's
implementation over them. The lifecycle vectors are the exception in the other direction: each
carries a hand-written `expect`, and the generator refuses to build a corpus where the
implementation disagrees with it. Those expectations are the specification of the idempotency
rules, with the vectors as instances.

Stated plainly, because it is easy to over-read a corpus: a *generated* vector cannot prove
the implementation correct, only that it has not moved since the vector was written. The rules
themselves are asserted directly, with hand-written expectations, in `test/bridge/*.test.ts` —
and for the byte-level canonical rules that is where the proof lives, because a vector that
records what the implementation produced would record a bug just as faithfully as a correct
answer.

What the corpus is for is the thing the other checks cannot do: giving a second implementation
the exact bytes to reproduce. A specification sentence saying "sort by code unit" cannot tell
an implementer that `1e21` is `1e+21` and `1e-7` is `1e-7` but `1e20` is
`100000000000000000000`, or that an unpaired surrogate is escaped while an astral pair is not.

## The byte contract for a second implementation

These vectors exist so a Kotlin implementation can be checked against the same bytes without
the two repositories sharing any code. A consumer needs four things from this directory, and
all four are here:

1. **A format version.** Every vector file carries `format_version` at the top level. It
   versions the *envelope* — the field names and the shape of a vector — and is deliberately
   not the bridge ABI, which the vectors record inside themselves (`catalog.json` digests over
   it; every binding carries `bridge_abi`). A consumer that reads a `format_version` it does
   not know must refuse the corpus rather than guess at the fields. The test asserts the
   current value, so an envelope change cannot land unnoticed.
2. **A digest over the whole directory.** `MANIFEST.sha256` lists the SHA-256 of every file
   below `vectors/`, computed over raw bytes, in the same `<hex>  <path>` form `sha256sum -c`
   reads. `test/bridge/conformance.test.ts` checks every entry *and* that no file exists which
   the manifest does not list, so a vector added without regenerating the manifest fails rather
   than being silently skipped.
3. **A language-neutral data format.** Every file is UTF-8 JSON with a newline. Nothing is
   encoded with a Node-only serializer: the vectors are ordinary JSON values, and the strings
   inside them are JSON strings. Where a vector needs a spelling that JSON itself cannot carry
   — `-0`, a duplicate member, a lone surrogate — it travels as an `inputText` string holding
   the exact document to parse, rather than as a value that would have lost the distinction
   before the test could see it.
4. **Explicit canonical bytes and their digest.** A canonical vector carries `canonical`, the
   exact expected output as a string, and `sha256`, the digest of its UTF-8 bytes. Neither is
   implied: a consumer compares bytes, not a parsed value, because every rule this corpus
   exists to pin is a rule about bytes.

`test/bridge/vectors/**` is marked `-text` in `.gitattributes`, for the same reason
`/conformance/**` is: the manifest hashes the bytes on disk, and a Windows checkout with
`core.autocrlf=true` would otherwise rewrite them to CRLF and fail the check on one platform
only.

Kotlin side, in short: vendor the directory unchanged, verify `MANIFEST.sha256` over the bytes
you hold, check `format_version` against the one you implement, then run every accepted vector
through your implementation and compare the produced bytes and digests. A mismatch is a real
divergence — which of the two is wrong is a judgement only a person can make, and is exactly
why the manifest is never regenerated to make a build green.

## Credential-shaped values are deliberately absent

No vector plants a credential-shaped value. The marker list lives in `src/log/redact.ts` and
nowhere else — that file's own comment says so, and the CI secret scan reads it from there
rather than keeping a second copy. A vector carrying a marker as an example would be exactly
the duplication that rule forbids, and would put a credential-shaped string into a scanned
tree.

The ruling is asserted in `test/bridge/opaque-payload.test.ts`, which imports `SECRET_MARKERS`
at run time and proves each one is **accepted** — a JWT inspector receives JWTs, a secret
manager receives secrets, a catalogue may document a key format. The Server does not read an
opaque payload as a credential policy. The corpus stays free of markers because of the *scan*,
not because of a rule about payloads, and the test cannot drift from the list.

## Where `assistantId` comes from

`GenerationBinding.assistantId` in `bindings.json` is supplied by `generation.start.body.
assistant_id` — the public field, carried verbatim. **M2-B must implement it as follows**, and
none of this is derivable from the vectors alone:

- It is a required field of the public body when the request carries a non-empty
  `tool_snapshot`. A generation with no catalogue may omit it, and one that omits it is a text
  run with no binding for the value to participate in.
- When required, it must be a valid opaque identity: non-empty, at most 128 UTF-8 bytes, and
  free of control characters. The Server refuses the request otherwise, before anything is
  written or dispatched.
- It participates in the **request fingerprint**, which is a framing M2-B also computes.
  `src/idempotency/fingerprint.ts` appends the block `assistant_id` after
  `attachment_manifest`, and **only when the request has one** — so a request without an
  assistant produces exactly the byte stream the existing vectors pin, and every digest in
  `conformance/fingerprints/vectors.json` still holds. Append at the same position, with the
  same presence-and-length framing every other field uses.
- The Server does **not** fall back to the conversation id. An earlier revision did, which made
  the field look bound while binding a value already bound beside it. A change to the assistant
  under a repeated `request_id` is therefore an `idempotency_conflict`, and no second
  generation is dispatched.

`conformance/` is the authoritative copy of the public protocol and lives upstream: this
repository vendors it and must not edit it. The rule above is written here because
`test/bridge/vectors/` is the corpus *this* repository authors.

## Changing the corpus

A vector change is a **contract change**, and the contract is what M2-B implements:

1. Change `scripts/build-bridge-corpus.mjs` — the inputs and notes, not the outputs.
2. Regenerate deliberately: `node scripts/build-bridge-corpus.mjs --write`.
3. Read the diff. A changed digest in `catalog.json` or `bindings.json` is a changed protocol,
   not a build artifact, and it breaks every frozen catalog already in flight.
4. If the change is semantic, it belongs in the M2 report and in the contract's own doc
   comments — the vectors record the bytes, they do not explain the rule.

`node scripts/build-bridge-corpus.mjs` on a clean tree is the verification mode, and it is what
CI runs. It fails rather than repairs: a mismatch means the implementation and the corpus
disagree, and only a person can say which of the two is wrong.

Never resolve a failing check with `--write`. That converts a real divergence into a green
build, which is precisely the failure the `conformance/` manifest's own README describes for
the wire protocol.
