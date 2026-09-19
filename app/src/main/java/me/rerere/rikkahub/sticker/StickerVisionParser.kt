package me.rerere.rikkahub.sticker

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant

/**
 * Turns a vision model's reply into [StickerVisionMetadata], or null when there is nothing usable.
 *
 * Null is the only failure mode, and it never propagates as an exception or as a failed import:
 * an unparseable reply means this sticker has no generated metadata, which is a state the library
 * already has to support (a sticker imported with no model configured looks the same). So the
 * parser's job is to extract as much as it honestly can and to answer "nothing" rather than to
 * guess or to take the import down with it.
 *
 * What it deliberately will NOT do is fall back to storing the raw reply as the description. A
 * model that answers "抱歉，我无法处理这张图片" would then be recorded as a successful recognition of
 * a sticker whose description is an apology — which reads as a working feature and is worse than
 * an honest empty one, because nothing prompts the person to fix it.
 *
 * Tolerated deviations, all of which turn up in practice:
 *  - the object wrapped in a ```json fence, or embedded in surrounding prose;
 *  - `tags` as a single delimited string instead of an array;
 *  - extra keys, which `JsonInstant` is configured to ignore.
 */
object StickerVisionParser {

    fun parse(raw: String): StickerVisionMetadata? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        val candidate = extractJsonObject(text) ?: return null
        val element = runCatching { JsonInstant.parseToJsonElement(candidate) }.getOrNull()
        val json = element as? JsonObject ?: return null

        val description = json.stringOrNull("description")?.trim().orEmpty()
        // A description is the part that makes a sticker retrievable; tags without one describe
        // nothing. Requiring it also rejects an object that parsed but carries an unrelated shape,
        // which is what a misrouted or truncated reply looks like.
        if (description.isEmpty()) return null

        return StickerVisionMetadata(
            description = description,
            tags = StickerTags.normalize(json.tagsOrEmpty()),
        )
    }

    /**
     * Narrows the reply to its first `{` … last `}`.
     *
     * Using the outermost braces rather than the first balanced pair is what makes the fence and
     * the surrounding-prose cases the same code path: anything the model wrapped around the object
     * falls outside the slice either way.
     */
    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        val end = text.lastIndexOf('}')
        if (end <= start) return null
        return text.substring(start, end + 1)
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /**
     * Tags as an array, or as one delimited string.
     *
     * The single-string shape is accepted because it is a common way for a model to satisfy "a
     * list of tags" while ignoring the JSON detail, and the alternative — discarding every tag over
     * the punctuation of a value that is otherwise exactly what was asked for — costs the person a
     * manual re-entry for no gain. Chinese and ASCII separators are both split on, since the prompt
     * is Chinese and the model may answer in either.
     */
    private fun JsonObject.tagsOrEmpty(): List<String> {
        val value = this["tags"] ?: return emptyList()
        return when (value) {
            is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            is JsonPrimitive -> value.takeIf { it.isString }
                ?.content
                ?.let(StickerTags::splitDelimited)
                .orEmpty()
            else -> emptyList()
        }
    }
}
