# Claude P conformance corpus

**This directory is the authoritative source.** It is vendored, byte-for-byte, into the
server repository (`maocomet/rikkahub-claude-p-server`), which verifies
`MANIFEST.sha256` and **must fail its build** when the two copies differ.

The two repositories never share code. They share *executable evidence*: the same bytes,
asserted by both test suites. That is what keeps a Kotlin client and a TypeScript server
from drifting apart in ways a specification document cannot catch — a document cannot
tell you that your length prefix counts the wrong thing.

## Why a corpus rather than a schema

The genuinely dangerous divergences here are not renamed fields. They are:

- a length prefix that counts **UTF-16 code units** in one implementation and **UTF-8
  bytes** in the other;
- `null` and `""` collapsing into the same value where a presence flag was required;
- field **order** not being covered by a hash or a signature;
- an unknown event being treated as a terminal, ending a generation early.

None of those are visible in a field list. All of them are visible in a byte string.

## Layout

```text
conformance/
├─ frames/envelope.json        Raw wire frames, stored exactly as they arrive
├─ expect/routing.json         Expected routing outcome per frame, keyed by id
├─ transcripts/handshake.json  client.hello transcript bytes
├─ transcripts/pairing.json    Pairing transcript bytes
├─ fingerprints/vectors.json   Request-fingerprint digests
├─ SPEC_REVISION.json          Which spec revision these vectors describe
├─ MANIFEST.sha256             SHA-256 of every file above
└─ tools/                      How the vectors were derived (see tools/README.md)
```

## File formats

### `frames/envelope.json`

Each entry is `{ id, note, raw, expect }`. `raw` is the exact text of one frame. Do not
reformat, re-serialize or re-indent it — several entries exist specifically to pin
properties that a re-serialization would silently destroy, and their `note` says so.

### `expect/routing.json`

`expect.kind` is one of:

| Kind | Meaning |
|---|---|
| `event` | Routed to a typed event; `eventType` names it. |
| `ignored_unknown` | A well-formed envelope whose type is not routed. Must produce **no text and no terminal**. |
| `rejected` | A protocol violation; `reason` is a `ClaudePParseRejection` name. |

The `ignored_unknown` / `rejected` distinction is not stylistic. An unknown *optional
event* is forward compatibility and must be dropped quietly; an unknown *major version* is
a correctness hazard and must stop the stream. Conflating them either breaks forward
compatibility or breaks fail-closed.

### `transcripts/*.json` and `fingerprints/vectors.json`

Each carries the algorithm it was derived from, the Kotlin `file:line` it mirrors, and
a `verification` field naming the evidence class. Read that field before citing the
vectors as proof — see `tools/README.md` for why two of the four parts are
dual-implementation verified and two are read-derived.

## The two length-prefix framings are different on purpose

This is the single easiest thing to get wrong, so it is stated twice:

| Used by | Framing |
|---|---|
| Handshake and pairing transcripts | `"${value.length}:${value}"` — a **decimal ASCII count of UTF-16 code units**, then a colon, then the characters. The whole string is then UTF-8 encoded. |
| Request fingerprint | **4-byte big-endian UTF-8 byte length**, preceded by a **1-byte presence flag** for the value. |

They are not interchangeable. An emoji is `2` under the first framing and `4` bytes under
the second. `handshake-astral-emoji` and `fingerprint-unicode` exist to catch exactly
this.

## Changing the corpus

A corpus change is a **protocol change**, and the protocol is frozen by review:

1. The change must be decided in `claudep/02-wire-protocol-v1.md` first, or in an ADR.
2. Regenerate with `tools/build-corpus.mjs` and update `SPEC_REVISION.json`.
3. The Android test and the server test must both be updated in the same change.
4. The server repository re-vendors and updates its `MANIFEST.sha256`.

**Never** resolve a failing manifest check by regenerating the manifest. A mismatch means
either the vendoring was wrong or the protocol moved — both need a human, not a rebuild.
