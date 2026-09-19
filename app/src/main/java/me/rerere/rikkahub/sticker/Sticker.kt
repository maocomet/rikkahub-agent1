package me.rerere.rikkahub.sticker

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import me.rerere.rikkahub.utils.JsonInstant

/**
 * Whether the shared library's vision model has looked at a sticker yet.
 *
 * This is a stored column rather than something derived from `description.isEmpty()`, because the
 * three states it separates are indistinguishable from the text alone and the UI has to render
 * them differently:
 *
 *  - [NONE] — recognition was never attempted (no model configured, or the sticker was imported
 *    before one was). The library shows "未识别" and offers 重新识别.
 *  - [OK] — a recognition run produced metadata. A sticker whose description the person later
 *    cleared by hand is STILL [OK]: the run happened, they just disagreed with it, and re-running
 *    it is not the obvious next step.
 *  - [FAILED] — a run was attempted and failed. Shows "识图失败" plus the reason, and 重新识别 is
 *    the obvious next step.
 *
 * Collapsing these into `description.isEmpty()` would make a hand-cleared description look like a
 * failed recognition and a failed recognition look like one the person never asked for.
 */
enum class StickerVisionState {
    NONE,
    OK,
    FAILED,
}

/**
 * Why a recognition run did not produce metadata.
 *
 * Stored as a stable short code, never a provider exception's message: those carry endpoint URLs,
 * request ids and sometimes response bodies, none of which belong in a row that survives restarts
 * and could be read back long after the fact.
 *
 * [UNKNOWN] exists so an unrecognised code read back from storage degrades to "something went
 * wrong" instead of throwing. A row written by a future version must not crash an older one.
 */
enum class StickerVisionFailure {
    /** No `stickerVisionModelId` is configured. */
    MODEL_NOT_CONFIGURED,

    /** An id is configured but no provider currently offers that model. */
    MODEL_NOT_FOUND,

    /** The configured model does not declare `Modality.IMAGE` as an input. */
    IMAGE_INPUT_UNSUPPORTED,

    /** The model's provider is disabled or missing from settings. */
    PROVIDER_UNAVAILABLE,

    /** The provider call itself failed — network, auth, timeout, HTTP error. */
    REQUEST_FAILED,

    /** The call succeeded but returned no text. */
    EMPTY_RESPONSE,

    /** The call returned text that does not carry a usable description/tags pair. */
    MALFORMED_RESPONSE,

    /** A code persisted by a build that knows failures this one does not. */
    UNKNOWN,
    ;

    companion object {
        fun fromCode(code: String?): StickerVisionFailure? {
            if (code.isNullOrBlank()) return null
            return entries.firstOrNull { it.name == code } ?: UNKNOWN
        }
    }
}

/**
 * A sticker in the shared library.
 *
 * The image itself never lives here — [relativePath] points at a file under the app's private
 * `filesDir`, and this row is the metadata the library (and, in a later phase, the model) reads
 * instead of looking at the picture again.
 *
 * [tags] is stored as JSON in one column rather than a join table. Tags are small, bounded (see
 * [StickerTags.MAX_TAGS]), always read as a set with their sticker, and never queried across
 * stickers in this phase, so a second table would buy nothing but a join.
 */
data class Sticker(
    val id: String,
    /** Path relative to `filesDir`, e.g. `stickers/<uuid>.png`. */
    val relativePath: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val description: String,
    val tags: List<String>,
    val enabled: Boolean,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    /** SHA-256 of the file's bytes, hex. The duplicate-import key. */
    val checksum: String,
    val visionState: StickerVisionState,
    val visionFailure: StickerVisionFailure?,
)

/**
 * Tag normalisation and the JSON encoding of the `tags_json` column.
 *
 * Both halves are tolerant on purpose. Encoding happens on model output and on whatever the person
 * types; decoding happens on every library render, where a malformed value must degrade to "no
 * tags" rather than take the page down — the sticker itself is still perfectly usable without them.
 */
object StickerTags {
    /** Upper bound asked of the model and enforced on every write, whoever produced the tags. */
    const val MAX_TAGS: Int = 8

    /** Fewer than this is not an error — a model that returns two good tags beat one that pads. */
    const val MIN_PROMPTED_TAGS: Int = 3

    private val serializer = ListSerializer(String.serializer())

    /**
     * What separates tags in a single-string form.
     *
     * One definition, used by both the editor field and [StickerVisionParser], so a model that
     * answers `"委屈,生气"` and a person who types `委屈、生气` are understood the same way. Chinese
     * and ASCII punctuation are both accepted because the prompt is Chinese and either can come
     * back.
     */
    val DELIMITERS: CharArray = charArrayOf(',', '，', '、', ';', '；')

    /** Splits a delimited tag string and normalises the parts. */
    fun splitDelimited(text: String): List<String> = normalize(text.split(*DELIMITERS))

    /**
     * Trims, drops blanks, de-duplicates and caps.
     *
     * Order is preserved rather than sorted: the model's own ordering usually puts the most
     * characteristic tag first, which is the one worth showing in a cramped grid cell.
     */
    fun normalize(tags: List<String>): List<String> = tags
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(MAX_TAGS)
        .toList()

    fun encode(tags: List<String>): String =
        JsonInstant.encodeToString(serializer, normalize(tags))

    /** Never throws: an unreadable column is treated as "this sticker has no tags". */
    fun decode(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching { JsonInstant.decodeFromString(serializer, json) }
            .getOrDefault(emptyList())
            .let(::normalize)
    }
}
