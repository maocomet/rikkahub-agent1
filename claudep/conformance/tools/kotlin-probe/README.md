# Kotlin probe — checking the corpus against the implementation

`Probe.kt` compiles the **production** `ClaudePProtocol.kt` and `ClaudePDto.kt` and replays
every frame in `../../frames/` through the real `ClaudePProtocol.parseInbound`, comparing
each result with `../../expect/routing.json`.

It is not a reimplementation. A second decoder would reproduce the same misreading that led
to a wrong expectation in the first place — which is exactly what happened, twice over: the
routing vectors were read out of the Kotlin by hand, and a validator in the server
repository that was written from the same reading made the identical mistake.

## When to run it

Whenever the routing vectors change, and before trusting a corpus PR that adds one. It is
the only thing in this repository that checks the corpus against the implementation it
claims to describe. Everything else checks the corpus against itself.

## Requirements

A Kotlin compiler and `kotlinx-serialization`. Neither is installed by default, and neither
is part of the build — this is a maintainer tool, not a gate. It needs:

- `kotlinc` matching the project's Kotlin version (`gradle/libs.versions.toml`):
  <https://github.com/JetBrains/kotlin/releases> → `kotlin-compiler-<version>.zip`
- `kotlinx-serialization-json-jvm` and `kotlinx-serialization-core-jvm` at the version in
  `gradle/libs.versions.toml`, from Maven Central
- The serialization compiler plugin, which ships inside the `kotlinc` distribution as
  `lib/kotlin-serialization-compiler-plugin.jar`. Without it `@Serializable` classes have no
  generated serializer and nothing decodes.

## Build and run

```bash
K=<kotlinc>/lib          # compiler jars
L=<dir with the kotlinx-serialization jars>
P=<this directory>

java -cp "$K/kotlin-compiler.jar" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -Xplugin="$K/kotlin-serialization-compiler-plugin.jar" \
  -classpath "$L/kotlinx-serialization-json-jvm-<v>.jar;$L/kotlinx-serialization-core-jvm-<v>.jar;$K/kotlin-stdlib.jar" \
  -include-runtime -d probe.jar \
  "$P/../../../../ai/src/main/java/me/rerere/ai/provider/claudep/ClaudePProtocol.kt" \
  "$P/../../../../ai/src/main/java/me/rerere/ai/provider/claudep/ClaudePDto.kt" \
  "$P/Probe.kt"

java -cp "probe.jar;$L/kotlinx-serialization-json-jvm-<v>.jar;$L/kotlinx-serialization-core-jvm-<v>.jar" \
  ProbeKt "<path to claudep/conformance>"
```

**Use the production sources directly, never a copy.** Compiling a copied `ClaudePDto.kt`
that has drifted from the repository proves nothing about the repository. The one time this
was run, the sources were verified content-identical to the committed blobs before use.

Exit status is non-zero when any frame disagrees.

## What it found

The first run reported `mismatches=2`, both on `server.hello`:

| Frame | Corpus said | Production decoder did |
|---|---|---|
| `server-hello-empty-body` | `event:server.hello` | `rejected:MALFORMED_EVENT_BODY` |
| `server-hello-absent-body` | `event:server.hello` | `rejected:MALFORMED_EVENT_BODY` |

**The corpus was wrong.** `ClaudePDto.kt:31` declares

```kotlin
@SerialName("protocol_version") val protocolVersion: String,
```

with **no default**, while every other field of `ClaudePServerHelloBody` has one. A validator
that treated all declared fields as optional therefore produced two expectations that the
implementation never satisfied. Both vectors now expect a rejection, and both were renamed
from `valid-*` to describe what they actually are.

The implementation is right to refuse them: `claudep/02` §3 defines `server.hello` as the
frame that freezes the chosen protocol version, so one that carries no version cannot do the
job it exists for.

## The limits of this tool

- **It is not run by CI.** It needs a Kotlin compiler, which the Android build already has —
  but wiring it in would mean compiling the production sources a second time to run a probe,
  and `ClaudePConformanceCorpusTest` already exercises the same parser under the real test
  runner. This is the tool for when that test fails and the corpus is the suspect.
- **It checks routing only.** Transcript bytes and fingerprint digests are verified by the
  dual-implementation method described in `../README.md`; this probe does not touch them.
