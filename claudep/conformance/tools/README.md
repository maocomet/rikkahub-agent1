# Conformance vector tooling

These tools exist to answer one question honestly: **where did these vectors come
from?** A corpus that was typed out by hand and then "verified" against the
implementation that was written from the same memory proves nothing. So the byte
vectors are derived twice, by two implementations, and only written once the two agree.

## Files

| File | Role |
|---|---|
| `gen-vectors.mjs` | Derives the transcript and fingerprint vectors in JavaScript. Cites the Kotlin source and line range each algorithm mirrors. |
| `VerifyVectors.java` | Derives the **same** vectors on the JVM. |
| `build-corpus.mjs` | Runs `gen-vectors.mjs`, adds the routing vectors, and writes `../` in full plus `MANIFEST.sha256`. |

## Why the Java file is not redundant

`VerifyVectors.java` is not a port for its own sake — it runs on the **same runtime the
Android implementation runs on**, which means it has the same semantics for exactly the
things these vectors are sensitive to:

- `String.length()` counts **UTF-16 code units**, so an astral character (an emoji)
  counts as 2 while its UTF-8 encoding is 4 bytes;
- `String.getBytes(StandardCharsets.UTF_8)`;
- `MessageDigest.getInstance("SHA-256")`.

JavaScript happens to share the UTF-16 `String.length` semantics, so the two agree — but
that agreement is a *finding*, not an assumption, and the emoji vectors are what force it
into the open. A third implementation in a language with code-point semantics (Python 3,
Rust, Go) would diverge on `handshake-astral-emoji` unless it deliberately emulated
UTF-16 counting. That is the trap these vectors are for.

## Reproducing

```bash
cd claudep/conformance/tools
node build-corpus.mjs                    # writes ../ and ../MANIFEST.sha256
javac -encoding UTF-8 VerifyVectors.java # then:
java -Dfile.encoding=UTF-8 VerifyVectors  # prints TSV on stdout
```

To re-run the cross-check, convert the generator's JSON to the same TSV shape and diff
the two. The Java program prints three `--`-separated sections in the order: handshake
transcripts, pairing transcripts, fingerprints.

**The `.class` files are build output and must not be committed.**

## What is and is not executed here

Be precise about this, because the corpus mixes two evidence classes:

| Corpus part | Evidence class |
|---|---|
| `transcripts/*.json` | **Dual-implementation verified.** JS and JVM agreed byte-for-byte before writing. |
| `fingerprints/vectors.json` | **Dual-implementation verified.** Same. |
| `frames/envelope.json` | **Read-derived.** Derived by reading `ClaudePProtocol.parseInbound`; not executed here. |
| `expect/routing.json` | **Read-derived.** Same. |

No Android SDK is available in the development environment, so the Kotlin routing
behaviour could not be executed during corpus construction. The Android-side test
`ClaudePConformanceCorpusTest` is what actually holds the implementation to the
read-derived vectors, and it runs wherever the Android test suite runs. Until it has run
and passed, the routing vectors are **an expectation, not a verified fact** — say so
rather than describing the whole corpus as verified.

## Regenerating after a protocol change

Do not regenerate to make a failure go away. If the manifest check fails, either the
corpus was vendored incorrectly or the protocol changed — and a protocol change is a
reviewed decision recorded in `claudep/02-wire-protocol-v1.md`, not something a
regeneration step is allowed to paper over. See `../README.md`.
