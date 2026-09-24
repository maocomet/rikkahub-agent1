package me.rerere.ai.provider.claudep.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.security.MessageDigest

/**
 * Why a value could not be canonicalized. A closed set, mirroring
 * `src/bridge/canonical.ts#CanonicalRejection` exactly.
 *
 * The names are the contract: `claudep/conformance-bridge/vectors/canonical.json` records
 * them as the `reason` of a rejected vector, so a rename here is a corpus failure rather
 * than a refactor.
 */
enum class CanonicalRejection {
    UNSUPPORTED_TYPE,
    NON_FINITE_NUMBER,
    NEGATIVE_ZERO,
    ARRAY_NOT_DENSE,
    DEPTH_EXCEEDED,
    SIZE_EXCEEDED,
    CYCLE,
    DUPLICATE_KEY,
    NOT_JSON_TEXT;

    /** The wire spelling, which is the lowercase name the vectors carry. */
    val wire: String get() = name.lowercase()
}

/** Thrown instead of ever producing a canonical form that describes a different value. */
class CanonicalRejected(val reason: CanonicalRejection) :
    Exception("canonical rejected: ${reason.wire}")

/**
 * Canonical JSON, and the one digest implementation the M2 bridge contract uses.
 *
 * This is the Kotlin half of `rikkahub-claude-p-server`'s `src/bridge/canonical.ts`. The two
 * are separate implementations of one byte contract and are pinned against each other by the
 * vendored corpus in `claudep/conformance-bridge/vectors/`, which was authored by the Server
 * and is never regenerated here.
 *
 * ## Why a digest has to be byte-exact across two languages
 *
 * The Server freezes the tool catalog Android sends and computes the request fingerprint over
 * it. Android computes the same bytes for the same reason the Server does: the fingerprint
 * binds `tool_snapshot`, so an Android that canonicalized differently would produce a
 * different fingerprint for the same request and its retry would be refused as an
 * `idempotency_conflict`. A near-miss is not a near-miss here; it is a second identity.
 *
 * ## The rules, all of which the corpus pins with exact bytes
 *
 * - Object members are sorted by **UTF-16 code unit** of the key, which is what Kotlin's
 *   `String.compareTo` already does. Not locale-aware, not insertion order. `code-unit-not-locale`
 *   and `code-unit-not-code-point` exist because ICU collation and code-point order both give
 *   a *different* answer for the same input.
 * - No whitespace, no trailing newline, UTF-8 encoded.
 * - Strings escape exactly as `JSON.stringify` does: `"` and `\`, the five short escapes, any
 *   other code unit below `U+0020` as `\u00xx`, and an **unpaired surrogate** as `\uxxxx` — all
 *   with lowercase hex. Everything else, including all non-ASCII and a well-formed astral
 *   pair, is emitted raw. Note `/` is *not* escaped; `JSON.stringify` never produces `\/`.
 * - Numbers are finite and use ECMAScript's shortest round-tripping form. This is **not**
 *   `Double.toString`: Kotlin emits `1.0E18` where ECMAScript emits `1000000000000000000`, and
 *   the two disagree again at both threshold exponents. See [formatNumber].
 * - `NaN`, the infinities and `-0` are refused rather than rewritten. `-0` matters because
 *   `JSON.stringify` renders it `0`, so `-0` and `0` would share a digest while being distinct
 *   values — a digest that two different inputs share is worse than no digest.
 *
 * ## Duplicate members cannot be detected after parsing
 *
 * `Json.parseToJsonElement` keeps the last duplicate and discards the rest, so by the time a
 * tree exists the information is gone. [hasDuplicateMemberName] therefore walks the **text**,
 * and is only reachable through [canonicalizeText]. A duplicate check placed after a bare
 * parse would be a control that had already been bypassed.
 */
object BridgeCanonical {

    /**
     * Bounds that keep canonicalization total. Both are far above any real tool schema.
     *
     * Depth counts **containers**, not values: the root is at depth 0 and a container at depth
     * 32 is the deepest accepted, so 33 is refused. The bridge's own schema bound is smaller
     * (see `BridgeLimits.MAX_SCHEMA_DEPTH`), so a schema that reached this limit has already
     * been refused for a more specific reason.
     */
    const val MAX_DEPTH = 32
    const val MAX_BYTES = 256 * 1024

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parses JSON text, refusing a document that names the same member twice, and returns its
     * canonical form.
     *
     * @throws CanonicalRejected with a reason from the closed set.
     */
    fun canonicalizeText(text: String): String {
        if (hasDuplicateMemberName(text)) throw CanonicalRejected(CanonicalRejection.DUPLICATE_KEY)
        val element = try {
            json.parseToJsonElement(text)
        } catch (error: Exception) {
            throw CanonicalRejected(CanonicalRejection.NOT_JSON_TEXT)
        }
        return canonicalize(element)
    }

    /**
     * Canonicalizes an already-parsed JSON value.
     *
     * @throws CanonicalRejected with a reason from the closed set.
     */
    fun canonicalize(element: JsonElement): String {
        val out = StringBuilder()
        write(element, 0, out)
        if (out.toString().toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            throw CanonicalRejected(CanonicalRejection.SIZE_EXCEEDED)
        }
        return out.toString()
    }

    /** The digest of a canonicalized value, as lowercase hex. */
    fun digestOf(element: JsonElement): String = sha256Hex(canonicalize(element))

    /** SHA-256 of a UTF-8 string, as lowercase hex. */
    fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    // -----------------------------------------------------------------------------------------

    private fun write(value: JsonElement, depth: Int, out: StringBuilder) {
        if (depth > MAX_DEPTH) throw CanonicalRejected(CanonicalRejection.DEPTH_EXCEEDED)

        when (value) {
            is JsonNull -> out.append("null")
            is JsonPrimitive -> writePrimitive(value, out)
            is JsonArray -> {
                out.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    write(item, depth + 1, out)
                }
                out.append(']')
            }
            is JsonObject -> {
                // `sorted()` is `String.compareTo`, which is UTF-16 code-unit order — the one
                // order the Server can compute from the data alone without knowing ours.
                val keys = value.keys.sorted()
                out.append('{')
                keys.forEachIndexed { index, key ->
                    if (index > 0) out.append(',')
                    writeString(key, out)
                    out.append(':')
                    write(value.getValue(key), depth + 1, out)
                }
                out.append('}')
            }
        }
    }

    private fun writePrimitive(value: JsonPrimitive, out: StringBuilder) {
        if (value.isString) {
            writeString(value.content, out)
            return
        }
        val content = value.content
        when (content) {
            "true", "false", "null" -> out.append(content)
            else -> out.append(formatNumber(content.toDouble()))
        }
    }

    /**
     * Formats a double in ECMAScript's `Number::toString` form.
     *
     * The shortest decimal that round-trips is found by rounding the double's *exact* value to
     * increasing precision and stopping at the first precision that parses back to the same
     * double. That is the same definition Ryū implements and the same one the Server gets from
     * `JSON.stringify`, so the two agree on the digits without sharing an algorithm.
     *
     * The digits alone are not the format. ECMAScript switches to exponent notation outside
     * `1e-6 .. 1e21`, and *where* it switches is the thing a host language gets wrong:
     * `1E20` is written out in full as `100000000000000000000` while `1E21` becomes `1e+21`,
     * and `1e-7` becomes `1e-7` while `1e-6` stays `0.000001`.
     */
    internal fun formatNumber(value: Double): String {
        if (!value.isFinite()) throw CanonicalRejected(CanonicalRejection.NON_FINITE_NUMBER)
        if (value == 0.0 && 1.0 / value == Double.NEGATIVE_INFINITY) {
            throw CanonicalRejected(CanonicalRejection.NEGATIVE_ZERO)
        }
        if (value == 0.0) return "0"

        val negative = value < 0
        val (digits, n) = shortestDigits(Math.abs(value))
        val body = formatDigits(digits, n)
        return if (negative) "-$body" else body
    }

    /**
     * The shortest round-tripping digit string, and the exponent `n` such that the value is
     * `digits × 10^(n − digits.length)`.
     *
     * Ties are broken **away from zero**, which is ECMAScript's "if there are two such values
     * of `s`, choose the larger". A genuine tie needs the double's exact value to sit halfway
     * between two candidate decimals; when that happens both candidates are equidistant and
     * the spec picks the larger, so half-up on the magnitude is the rule and half-even is not.
     */
    private fun shortestDigits(magnitude: Double): Pair<String, Int> {
        for (precision in 1..17) {
            val rounded = BigDecimal(magnitude).round(MathContext(precision, RoundingMode.HALF_UP))
            if (rounded.toDouble() != magnitude) continue
            val stripped = rounded.stripTrailingZeros()
            val digits = stripped.unscaledValue().abs().toString()
            return digits to (digits.length - stripped.scale())
        }
        // Unreachable: 17 significant digits always round-trip an IEEE-754 double.
        throw CanonicalRejected(CanonicalRejection.NON_FINITE_NUMBER)
    }

    private fun formatDigits(s: String, n: Int): String {
        val k = s.length
        return when {
            // Integral, and written out in full below the exponent threshold.
            k <= n && n <= 21 -> s + "0".repeat(n - k)
            n in 1..21 -> s.substring(0, n) + "." + s.substring(n)
            n in -5..0 -> "0." + "0".repeat(-n) + s
            else -> {
                val exponent = n - 1
                val mantissa = if (k == 1) s else s.substring(0, 1) + "." + s.substring(1)
                val sign = if (exponent >= 0) "+" else "-"
                "$mantissa" + "e" + sign + Math.abs(exponent)
            }
        }
    }

    private fun writeString(value: String, out: StringBuilder) {
        out.append('"')
        var index = 0
        while (index < value.length) {
            val c = value[index]
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\b' -> out.append("\\b")
                c == '' -> out.append("\\f")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append("\\u").appendHex4(c.code)
                // A well-formed astral pair is one character and is emitted raw; a lone
                // surrogate has no UTF-8 encoding at all and must be escaped, or the bytes
                // this returns would not be the bytes that were hashed.
                c.isHighSurrogate() -> {
                    if (index + 1 < value.length && value[index + 1].isLowSurrogate()) {
                        out.append(c).append(value[index + 1])
                        index += 1
                    } else {
                        out.append("\\u").appendHex4(c.code)
                    }
                }
                c.isLowSurrogate() -> out.append("\\u").appendHex4(c.code)
                else -> out.append(c)
            }
            index += 1
        }
        out.append('"')
    }

    private fun StringBuilder.appendHex4(code: Int): StringBuilder {
        for (shift in intArrayOf(12, 8, 4, 0)) {
            append("0123456789abcdef"[(code shr shift) and 0xf])
        }
        return this
    }

    // -----------------------------------------------------------------------------------------
    // Duplicate-member detection, over text
    // -----------------------------------------------------------------------------------------

    /**
     * True when any object in this JSON **text** names the same member twice.
     *
     * A scanner rather than a validator, ported from the Server for the same reason it exists
     * there: it only has to answer one question, and it must not be fooled by a brace inside a
     * string. Member names are compared **after unescaping**, so a document that writes one
     * name twice — once plainly and once as `a` — is naming the same member twice, which
     * a comparison of the raw quoted slices would miss while the parse silently kept the
     * second. `duplicate-member-escaped` in the corpus is exactly that case.
     */
    fun hasDuplicateMemberName(text: String): Boolean {
        val stack = ArrayDeque<MutableSet<String>>()
        var index = 0

        while (index < text.length) {
            when (text[index]) {
                '"' -> {
                    val end = scanString(text, index)
                    if (end == -1) return false

                    // A string is a member name when the next non-space character is a colon.
                    // In text that is not JSON this may be wrong, and that is harmless: the
                    // parse refuses the document anyway.
                    var next = end
                    while (next < text.length && isJsonSpace(text[next])) next += 1
                    if (next < text.length && text[next] == ':' && stack.isNotEmpty()) {
                        val name = decodeStringLiteral(text.substring(index, end))
                        // An escape that does not decode means the document is not JSON. Stop
                        // looking and let the parse report it, rather than reporting a
                        // duplicate that may not exist.
                        if (name == null) return false
                        val frame = stack.last()
                        if (!frame.add(name)) return true
                    }
                    index = end
                    continue
                }
                '{' -> {
                    stack.addLast(mutableSetOf())
                    index += 1
                    continue
                }
                '}' -> {
                    if (stack.isNotEmpty()) stack.removeLast()
                    index += 1
                    continue
                }
            }
            // Arrays do not introduce a member scope; their contents belong to the object above.
            index += 1
        }
        return false
    }

    /** The index just past the closing quote, or -1 when the string is unterminated. */
    private fun scanString(text: String, start: Int): Int {
        var index = start + 1
        while (index < text.length) {
            when (text[index]) {
                '\\' -> index += 2
                '"' -> return index + 1
                else -> index += 1
            }
        }
        return -1
    }

    /**
     * Decodes one already-delimited JSON string literal, or `null` when it is not one.
     *
     * A real JSON parse is used rather than a hand-written unescaper: it is the same grammar
     * the eventual parse will apply, so the two cannot disagree about what `"a"` means.
     */
    private fun decodeStringLiteral(literal: String): String? = try {
        (json.parseToJsonElement(literal) as? JsonPrimitive)?.takeIf { it.isString }?.content
    } catch (error: Exception) {
        null
    }

    private fun isJsonSpace(character: Char): Boolean =
        character == ' ' || character == '\t' || character == '\n' || character == '\r'
}
