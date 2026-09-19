package me.rerere.rikkahub.sticker

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext

const val STICKER_SEARCH_TOOL_NAME = "sticker_search"
const val STICKER_SEND_TOOL_NAME = "sticker_send"

/** The complete sticker tool surface. Nothing outside this set is a sticker tool. */
val STICKER_TOOL_NAMES: Set<String> = setOf(
    STICKER_SEARCH_TOOL_NAME,
    STICKER_SEND_TOOL_NAME,
)

/**
 * The sticker tools for one turn.
 *
 * Built through [StickerToolSurface], which decides whether they exist at all; this function only
 * knows how they behave once they do.
 *
 * [assistantEnabled] and the invocation context are threaded down to `sticker_send` rather than
 * assumed true because the surface was built. A tool that can be invoked is a tool whose guards
 * should not rest on the caller having honoured a gate it never had to prove it honoured — so the
 * execute path re-checks the same conditions the surface did, and fails closed on its own.
 */
fun createStickerTools(
    delivery: StickerDelivery,
    repository: StickerRepository,
    invocationContext: ToolInvocationContext,
    assistantEnabled: Boolean,
): List<Tool> = listOf(
    Tool(
        name = STICKER_SEARCH_TOOL_NAME,
        description = """
            Search the person's shared sticker library by meaning, and get back a short list of
            candidates you can send.

            This searches a library the person curates by hand. Every sticker in it has a
            description and tags, written when it was imported, that say what the picture shows and
            what it expresses — so search for the *feeling* or the situation, in the person's own
            language: "委屈 撒娇", "无语", "开心猫猫", "震惊". Several keywords score better than
            one.

            The library is **shared**: the same stickers are available to the person and to every
            assistant. You are searching it, not your own collection.

            Search first, then send. Never guess a sticker_id — the only ids you may send are the
            ones this tool returned. If nothing here fits what you want to express, that is a
            normal result: say what you meant in words instead, or send nothing.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "What you want to express, as keywords. Chinese is expected. " +
                                "Example: \"委屈 撒娇\".",
                        )
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "How many candidates to return. Default ${StickerSearch.DEFAULT_LIMIT}, " +
                                "maximum ${StickerSearch.MAX_LIMIT}.",
                        )
                    })
                },
                required = listOf("query"),
            )
        },
        execute = { input ->
            val args = input.jsonObject
            val query = args["query"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val limit = StickerSearch.clampLimit(args["limit"]?.jsonPrimitive?.intOrNull)

            val candidates = repository.search(query, limit)

            stickerOk(
                code = when {
                    query.isBlank() -> "EMPTY_QUERY"
                    candidates.isEmpty() -> "NO_MATCHES"
                    else -> "OK"
                },
            ) {
                put("candidates", buildJsonArray {
                    candidates.forEach { sticker -> add(stickerCandidate(sticker)) }
                })
            }
        },
    ),
    Tool(
        name = STICKER_SEND_TOOL_NAME,
        description = """
            Send ONE sticker from the shared library into this conversation.

            Pass a sticker_id that ${STICKER_SEARCH_TOOL_NAME} returned. There is no other way to
            name a sticker: this tool takes no file path, no URL and no image data, and an id that
            did not come from a search will be rejected.

            A sticker is one way to say something, not the thing to do every turn. Most replies
            should be words. Send one when a picture genuinely says it better — after a line of
            text, or on its own when the picture is the whole reply. If you are unsure, do not send
            one; a message with no sticker is a complete answer, and repeatedly sending stickers to
            show the feature works is worse than never using it.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("sticker_id", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "An id returned by $STICKER_SEARCH_TOOL_NAME. Never invent one.",
                        )
                    })
                },
                required = listOf("sticker_id"),
            )
        },
        execute = { input ->
            val stickerId = input.jsonObject["sticker_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            when (
                val outcome = delivery.send(
                    stickerId = stickerId,
                    assistantEnabled = assistantEnabled,
                    callOrigin = invocationContext.callOrigin,
                )
            ) {
                is StickerSendOutcome.Sent -> listOf(
                    // The image travels as a tool output and is lifted into the assistant message
                    // by StickerSendToMessagePartTransformer, which also drops it from here so it
                    // is rendered exactly once. If that transformer ever stops running, the image
                    // still shows — in the tool card — rather than vanishing, which is why it is
                    // carried here instead of being named by a path in the acknowledgement.
                    outcome.image,
                    stickerSentAck(outcome.sticker),
                )
                is StickerSendOutcome.Rejected -> stickerRejected(outcome.error)
            }
        },
    ),
)

/**
 * What the model sees in place of the image once the transformer has lifted it into the message.
 *
 * Carries no path: the conversation copy is the app's business, and a tool result that names a
 * file is a tool result that invites the model to try to name one itself.
 */
private fun stickerSentAck(sticker: Sticker): UIMessagePart.Text = UIMessagePart.Text(
    buildJsonObject {
        put("ok", true)
        put("code", "STICKER_SENT")
        put("sticker_id", sticker.id)
    }.toString(),
)

private fun stickerCandidate(sticker: Sticker) = buildJsonObject {
    put("sticker_id", sticker.id)
    put("description", sticker.description)
    put("tags", buildJsonArray { sticker.tags.forEach { add(it) } })
    put("mime_type", sticker.mimeType)
}

private fun stickerOk(
    code: String,
    // Named `body`, not `build`: inside the `buildJsonObject` receiver below, a bare `build()`
    // would resolve to `JsonObjectBuilder.build()` rather than to this parameter.
    body: JsonObjectBuilder.() -> Unit = {},
): List<UIMessagePart> = listOf(
    UIMessagePart.Text(
        buildJsonObject {
            put("ok", true)
            put("code", code)
            body()
        }.toString(),
    ),
)

private fun stickerRejected(error: StickerToolError): List<UIMessagePart> = listOf(
    UIMessagePart.Text(
        buildJsonObject {
            put("ok", false)
            put("code", error.name)
        }.toString(),
    ),
)
