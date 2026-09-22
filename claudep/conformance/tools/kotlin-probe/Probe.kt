// Runs the PRODUCTION Claude P decoder against the conformance corpus.
//
// This file is deliberately not a reimplementation. It compiles the real
// `ClaudePProtocol.kt` and `ClaudePDto.kt` — copied unchanged from
// `ai/src/main/java/me/rerere/ai/provider/claudep/` — and calls the real
// `ClaudePProtocol.parseInbound` on every frame, comparing the result with the corpus
// expectation.
//
// It exists because a CI failure reported `AssertionError at :350` and nothing else: Gradle
// prints an exception type and a line number, the assertion message never reached the log,
// and the JUnit XML was not uploaded. The divergent frame could be located but not
// identified. Reading the source said all seven event vectors should route to an event; CI
// said otherwise. Reading is not evidence, so this measures instead.
//
// It found two wrong expectations — see README.md. Use it whenever the routing vectors
// change: it is the only thing in this repository that checks the corpus against the
// implementation it claims to describe.
//
// Build and run: see README.md in this directory.

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.claudep.ClaudePInbound
import me.rerere.ai.provider.claudep.ClaudePProtocol

private val json = Json { ignoreUnknownKeys = true }

private fun readJson(dir: File, name: String): JsonObject =
    json.parseToJsonElement(File(dir, name).readText()).jsonObject

private fun describe(inbound: ClaudePInbound): String = when (inbound) {
    is ClaudePInbound.Event -> "event:" + inbound.event.envelope.type.trim()
    is ClaudePInbound.Rejected -> "rejected:" + inbound.reason.name
    ClaudePInbound.IgnoredUnknownEvent -> "ignored_unknown"
}

fun main(args: Array<String>) {
    val corpus = File(args[0])

    val frames = readJson(corpus, "frames/envelope.json")["frames"]!!.jsonArray
    val expectations = readJson(corpus, "expect/routing.json")["expectations"]!!.jsonArray
        .map { it.jsonObject }
        .associateBy { it["id"]!!.jsonPrimitive.content }

    var mismatches = 0

    for (element in frames) {
        val frame = element.jsonObject
        val id = frame["id"]!!.jsonPrimitive.content
        val raw = frame["raw"]!!.jsonPrimitive.content
        val expect = expectations[id]!!["expect"]!!.jsonObject
        val kind = expect["kind"]!!.jsonPrimitive.content

        val expected = when (kind) {
            "event" -> "event:" + expect["eventType"]!!.jsonPrimitive.content
            "rejected" -> "rejected:" + expect["reason"]!!.jsonPrimitive.content
            else -> kind
        }

        val actual = describe(ClaudePProtocol.parseInbound(raw))

        val ok = actual == expected
        if (!ok) mismatches += 1

        println(
            (if (ok) "ok    " else "MISMATCH") + "  " +
                id.padEnd(42) + " expected=" + expected.padEnd(36) + " actual=" + actual,
        )
        if (!ok) println("          frame: $raw")
    }

    println()
    println("frames=${frames.size} mismatches=$mismatches")
    if (mismatches != 0) {
        println()
        println("The corpus and the implementation disagree. Decide which is wrong before")
        println("changing either: a wrong expectation is a corpus bug, but a wrong")
        println("implementation is a protocol bug, and only one of those is safe to fix here.")
        kotlin.system.exitProcess(1)
    }
}
