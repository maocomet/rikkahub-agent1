package me.rerere.rikkahub.space

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext

const val SPACE_CREATE_POST_TOOL_NAME = "space_create_post"
const val SPACE_LIST_POSTS_TOOL_NAME = "space_list_posts"
const val SPACE_GET_POST_TOOL_NAME = "space_get_post"
const val SPACE_SET_LIKE_TOOL_NAME = "space_set_like"
const val SPACE_CREATE_COMMENT_TOOL_NAME = "space_create_comment"
const val SPACE_LIST_COMMENTS_TOOL_NAME = "space_list_comments"
const val SPACE_DELETE_POST_TOOL_NAME = "space_delete_post"
const val SPACE_LIST_NOTIFICATIONS_TOOL_NAME = "space_list_notifications"
const val SPACE_MARK_NOTIFICATIONS_READ_TOOL_NAME = "space_mark_notifications_read"

/** The complete Cat Garden tool surface. Nothing outside this set is a space tool. */
val SPACE_TOOL_NAMES: Set<String> = setOf(
    SPACE_CREATE_POST_TOOL_NAME,
    SPACE_LIST_POSTS_TOOL_NAME,
    SPACE_GET_POST_TOOL_NAME,
    SPACE_LIST_COMMENTS_TOOL_NAME,
    SPACE_SET_LIKE_TOOL_NAME,
    SPACE_CREATE_COMMENT_TOOL_NAME,
    SPACE_DELETE_POST_TOOL_NAME,
    SPACE_LIST_NOTIFICATIONS_TOOL_NAME,
    SPACE_MARK_NOTIFICATIONS_READ_TOOL_NAME,
)

/**
 * The Cat Garden tools for one turn.
 *
 * Every tool resolves its author/actor from [ToolInvocationContext], which the runtime owns. No
 * tool declares an `author_id` or `actor_id` parameter, so there is nothing for a model to forge:
 * the only way to write as an assistant is to already be that assistant.
 */
fun createSpaceTools(
    repository: SpaceRepository,
    invocationContext: ToolInvocationContext,
): List<Tool> {
    // The ONE causal-depth decision point for space writes — see [SpaceCausalDepth]. A person
    // driving the turn is the origin, so their writes are depth 0; an automation run is by
    // construction at least one hop away, so its writes are depth >= 1 and the notification they
    // produce can never wake another run.
    val originDepth = if (invocationContext.isHeadless) {
        SpaceCausalDepth.AUTOMATION_DRIVEN
    } else {
        SpaceCausalDepth.USER_INITIATED
    }

    fun actorOrNull(): SpaceActor? = SpaceActor.fromAssistant(invocationContext.callerAssistantId)

    return listOf(
        Tool(
            name = SPACE_CREATE_POST_TOOL_NAME,
            description = """
                Publish a post to Cat Garden, the shared space where you and the user see each
                other's updates.

                You do not have to wait to be asked. When the current conversation, task or
                workflow naturally produces a thought, a piece of news or a feeling that suits
                being shared, publishing it is a reasonable thing to do. Do not post mechanically
                or often just to stay active — a space filled with filler is worse than a quiet one.

                This only ever publishes as YOU. The author is taken from the running session, and
                no parameter can attribute the post to anyone else.
            """.trimIndent(),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("content", buildJsonObject {
                            put("type", "string")
                            put("description", "The post body. Plain text.")
                        })
                    },
                    required = listOf("content"),
                )
            },
            execute = { input ->
                val actor = actorOrNull() ?: return@Tool spaceIdentityUnavailable()
                val content = input.jsonObject["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                when (val outcome = repository.createPost(actor, content, originDepth)) {
                    is SpaceWriteOutcome.Created -> {
                        val postId = outcome.id
                        spaceOk { put("post_id", postId) }
                    }

                    is SpaceWriteOutcome.AlreadyApplied -> {
                        val postId = outcome.id
                        spaceOk { put("post_id", postId) }
                    }

                    is SpaceWriteOutcome.Rejected -> spaceRejected(outcome)
                }
            },
        ),

        Tool(
            name = SPACE_LIST_POSTS_TOOL_NAME,
            description = """
                Read the Cat Garden timeline, newest first.

                Returns a page, never the whole history: pass the `next_before_*` values from one
                page as the `before_*` arguments to read the next. `has_more` tells you whether
                another page exists.
            """.trimIndent(),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("limit", buildJsonObject {
                            put("type", "integer")
                            put("description", "Page size, 1..${SpaceRepository.MAX_PAGE_SIZE}.")
                        })
                        put("before_created_at_ms", buildJsonObject {
                            put("type", "integer")
                            put("description", "Cursor from the previous page's next_before_created_at_ms.")
                        })
                        put("before_id", buildJsonObject {
                            put("type", "string")
                            put("description", "Cursor from the previous page's next_before_id.")
                        })
                    },
                )
            },
            execute = { input ->
                val args = input.jsonObject
                val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: DEFAULT_PAGE_SIZE)
                    .coerceIn(1, SpaceRepository.MAX_PAGE_SIZE)
                val beforeCreatedAt = args["before_created_at_ms"]?.jsonPrimitive?.longOrNull
                val beforeId = args["before_id"]?.jsonPrimitive?.contentOrNull

                // Both cursor halves are required together: a time-only cursor silently skips or
                // repeats rows whenever several share a millisecond.
                val page = repository.listPostsPage(cursorOrNull(beforeCreatedAt, beforeId), limit)
                val posts = page.items
                // Like/comment counts are suspend reads, so they are resolved before the JSON is
                // assembled rather than inside the (non-suspend) builder lambda.
                val encodedPosts = posts.map { spacePostJson(repository, it) }
                spaceOk {
                    put("count", posts.size)
                    putJsonArray("posts") { encodedPosts.forEach { add(it) } }
                    // The cursor is the last row actually returned. `has_more` is a lookahead
                    // verdict, so it is true exactly when such a row exists.
                    if (page.hasMore) {
                        val last = posts.last()
                        put("next_before_created_at_ms", last.createdAtMs)
                        put("next_before_id", last.postId)
                    }
                    put("has_more", page.hasMore)
                }
            },
        ),

        Tool(
            name = SPACE_GET_POST_TOOL_NAME,
            description = """
                Read one Cat Garden post together with a first page of its comments and its like
                count.

                `comment_count` is the FULL number of comments on the post, and `comments_has_more`
                says whether the returned page is shorter than that. When it is, read the rest with
                `space_list_comments` using the `comments_next_after_*` values from this response —
                never treat the returned list as the whole thread.
            """.trimIndent(),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("post_id", buildJsonObject { put("type", "string") })
                    },
                    required = listOf("post_id"),
                )
            },
            execute = { input ->
                val postId = input.jsonObject["post_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val post = repository.getPost(postId)
                    ?: return@Tool spaceRejected(
                        SpaceWriteOutcome.Rejected("POST_NOT_FOUND", "No post with that id."),
                    )
                val page = repository.listCommentsPage(postId, after = null, limit = DEFAULT_COMMENT_PAGE)
                val comments = page.items
                val encodedPost = spacePostJson(repository, post)
                spaceOk {
                    put("post", encodedPost)
                    putJsonArray("comments") {
                        comments.forEach { comment -> add(spaceCommentJson(comment)) }
                    }
                    // A caller must never have to guess whether 50 comments was the whole thread.
                    // `comment_count` is the exact total and `comments_has_more` comes from a
                    // lookahead read rather than from comparing this page against that total.
                    put("comment_count", repository.commentCount(postId))
                    put("comments_has_more", page.hasMore)
                    if (page.hasMore) {
                        val last = comments.last()
                        put("comments_next_after_created_at_ms", last.createdAtMs)
                        put("comments_next_after_id", last.commentId)
                    }
                }
            },
        ),

        Tool(
            name = SPACE_LIST_COMMENTS_TOOL_NAME,
            description = """
                Read one page of a Cat Garden post's comments, oldest first.

                Pass the `next_after_*` values from one page as the `after_*` arguments to read the
                next. `has_more` tells you whether another page exists. `comment_count` is the total
                on the post, so you can tell how far through the thread a page is.
            """.trimIndent(),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("post_id", buildJsonObject { put("type", "string") })
                        put("limit", buildJsonObject {
                            put("type", "integer")
                            put("description", "Page size, 1..${SpaceRepository.MAX_PAGE_SIZE}.")
                        })
                        put("after_created_at_ms", buildJsonObject {
                            put("type", "integer")
                            put("description", "Cursor from the previous page's next_after_created_at_ms.")
                        })
                        put("after_id", buildJsonObject {
                            put("type", "string")
                            put("description", "Cursor from the previous page's next_after_id.")
                        })
                    },
                    required = listOf("post_id"),
                )
            },
            execute = { input ->
                val args = input.jsonObject
                val postId = args["post_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (repository.getPost(postId) == null) {
                    return@Tool spaceRejected(
                        SpaceWriteOutcome.Rejected("POST_NOT_FOUND", "No post with that id."),
                    )
                }
                val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: DEFAULT_PAGE_SIZE)
                    .coerceIn(1, SpaceRepository.MAX_PAGE_SIZE)
                val afterCreatedAt = args["after_created_at_ms"]?.jsonPrimitive?.longOrNull
                val afterId = args["after_id"]?.jsonPrimitive?.contentOrNull

                // Both cursor halves travel together: a time-only cursor silently skips or repeats
                // comments whenever several share a millisecond.
                val page = repository.listCommentsPage(
                    postId = postId,
                    after = cursorOrNull(afterCreatedAt, afterId),
                    limit = limit,
                )
                val comments = page.items
                spaceOk {
                    put("post_id", postId)
                    put("count", comments.size)
                    putJsonArray("comments") {
                        comments.forEach { comment -> add(spaceCommentJson(comment)) }
                    }
                    put("comment_count", repository.commentCount(postId))
                    // Proven by a lookahead read, not guessed from the page size or from how many
                    // comments the whole post has — either guess misreports the last page.
                    put("has_more", page.hasMore)
                    if (page.hasMore) {
                        val last = comments.last()
                        put("next_after_created_at_ms", last.createdAtMs)
                        put("next_after_id", last.commentId)
                    }
                }
            },
        ),

        Tool(
            name = SPACE_SET_LIKE_TOOL_NAME,
            description = """
                Like or un-like a Cat Garden post, as YOU.

                This is a set, not a toggle: passing `liked = true` twice leaves exactly one like,
                and the result reports `already_applied` when nothing changed. Pass `false` to
                remove your like.
            """.trimIndent(),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("post_id", buildJsonObject { put("type", "string") })
                        put("liked", buildJsonObject {
                            put("type", "boolean")
                            put("description", "true to like, false to remove the like.")
                        })
                    },
                    required = listOf("post_id", "liked"),
                )
            },
            execute = { input ->
                val actor = actorOrNull() ?: return@Tool spaceIdentityUnavailable()
                val args = input.jsonObject
                val postId = args["post_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val liked = args["liked"]?.jsonPrimitive?.booleanOrNull
                    ?: return@Tool spaceRejected(
                        SpaceWriteOutcome.Rejected("LIKED_REQUIRED", "`liked` must be true or false."),
                    )
                when (val outcome = repository.setLike(actor, postId, liked, originDepth)) {
                    is SpaceWriteOutcome.Created -> {
                        // Resolved before the JSON builder: likeCount is suspend and the builder
                        // lambda is not.
                        val likeCount = repository.likeCount(postId)
                        spaceOk {
                            put("post_id", postId)
                            put("liked", liked)
                            put("already_applied", false)
                            put("like_count", likeCount)
                        }
                    }

                    is SpaceWriteOutcome.AlreadyApplied -> {
                        val likeCount = repository.likeCount(postId)
                        spaceOk {
                            put("post_id", postId)
                            put("liked", true)
                            put("already_applied", true)
                            put("like_count", likeCount)
                        }
                    }

                    is SpaceWriteOutcome.Rejected -> spaceRejected(outcome)
                }
            },
        ),

        Tool(
            name = SPACE_CREATE_COMMENT_TOOL_NAME,
            description = """
                Comment on a Cat Garden post, as YOU. Comments are a single level: they attach to
                the post, not to another comment.
            """.trimIndent(),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("post_id", buildJsonObject { put("type", "string") })
                        put("content", buildJsonObject {
                            put("type", "string")
                            put("description", "The comment body. Plain text.")
                        })
                    },
                    required = listOf("post_id", "content"),
                )
            },
            execute = { input ->
                val actor = actorOrNull() ?: return@Tool spaceIdentityUnavailable()
                val args = input.jsonObject
                val postId = args["post_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val content = args["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                when (val outcome = repository.createComment(actor, postId, content, originDepth)) {
                    is SpaceWriteOutcome.Created -> {
                        val commentId = outcome.id
                        spaceOk { put("comment_id", commentId) }
                    }

                    is SpaceWriteOutcome.AlreadyApplied -> {
                        val commentId = outcome.id
                        spaceOk { put("comment_id", commentId) }
                    }

                    is SpaceWriteOutcome.Rejected -> spaceRejected(outcome)
                }
            },
        ),

        Tool(
            name = SPACE_DELETE_POST_TOOL_NAME,
            description = """
                Delete a Cat Garden post YOU published, together with its likes, comments and
                notifications.

                You can only delete your own posts. Ownership is decided from the running session,
                not from any argument — `post_id` is the only thing this tool accepts, and asking
                to delete someone else's post is refused rather than silently ignored. Deleting is
                permanent: there is no undo and no draft.
            """.trimIndent(),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("post_id", buildJsonObject {
                            put("type", "string")
                            put("description", "The id of a post you published.")
                        })
                    },
                    required = listOf("post_id"),
                )
            },
            execute = { input ->
                val actor = actorOrNull() ?: return@Tool spaceIdentityUnavailable()
                val postId = input.jsonObject["post_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                when (val outcome = repository.deletePost(actor, postId)) {
                    is SpaceWriteOutcome.Created -> spaceOk { put("post_id", outcome.id) }
                    is SpaceWriteOutcome.AlreadyApplied -> spaceOk { put("post_id", outcome.id) }
                    is SpaceWriteOutcome.Rejected -> spaceRejected(outcome)
                }
            },
        ),

        Tool(
            name = SPACE_LIST_NOTIFICATIONS_TOOL_NAME,
            description = """
                Read YOUR Cat Garden notifications — likes and comments other identities left on
                your posts, newest first.

                Returns a page: pass `next_before_*` from one page as the `before_*` arguments to
                read the next.
            """.trimIndent(),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("limit", buildJsonObject {
                            put("type", "integer")
                            put("description", "Page size, 1..${SpaceRepository.MAX_PAGE_SIZE}.")
                        })
                        put("before_created_at_ms", buildJsonObject { put("type", "integer") })
                        put("before_id", buildJsonObject { put("type", "string") })
                    },
                )
            },
            execute = { input ->
                val actor = actorOrNull() ?: return@Tool spaceIdentityUnavailable()
                val args = input.jsonObject
                val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: DEFAULT_PAGE_SIZE)
                    .coerceIn(1, SpaceRepository.MAX_PAGE_SIZE)
                val beforeCreatedAt = args["before_created_at_ms"]?.jsonPrimitive?.longOrNull
                val beforeId = args["before_id"]?.jsonPrimitive?.contentOrNull

                val page = repository.listNotificationsPage(
                    actor = actor,
                    before = cursorOrNull(beforeCreatedAt, beforeId),
                    limit = limit,
                )
                val notifications = page.items
                spaceOk {
                    put("count", notifications.size)
                    putJsonArray("notifications") {
                        notifications.forEach { notification ->
                            add(buildJsonObject {
                                put("notification_id", notification.notificationId)
                                put("type", notification.type)
                                put("actor_kind", notification.actorKind)
                                put("actor_id", notification.actorId)
                                put("post_id", notification.postId)
                                notification.commentId?.let { put("comment_id", it) }
                                put("created_at_ms", notification.createdAtMs)
                                put("read", notification.readAtMs != null)
                            })
                        }
                    }
                    if (page.hasMore) {
                        val last = notifications.last()
                        put("next_before_created_at_ms", last.createdAtMs)
                        put("next_before_id", last.notificationId)
                    }
                    put("has_more", page.hasMore)
                }
            },
        ),

        Tool(
            name = SPACE_MARK_NOTIFICATIONS_READ_TOOL_NAME,
            description = """
                Mark specific Cat Garden notifications as read, by id.

                Only your own notifications are affected, and only the ids you name — there is no
                "mark everything read" form, so a mistaken call cannot clear your whole inbox.
            """.trimIndent(),
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("notification_ids", buildJsonObject {
                            put("type", "array")
                            put("description", "Notification ids to mark read.")
                            put("items", buildJsonObject { put("type", "string") })
                        })
                    },
                    required = listOf("notification_ids"),
                )
            },
            execute = { input ->
                val actor = actorOrNull() ?: return@Tool spaceIdentityUnavailable()
                // Anything that is not an array of strings resolves to "no ids": an unparsable
                // request marks nothing rather than everything.
                val ids = (input.jsonObject["notification_ids"] as? JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    .orEmpty()
                val updated = repository.markNotificationsRead(actor, ids)
                spaceOk { put("updated", updated) }
            },
        ),
    )
}

private const val DEFAULT_PAGE_SIZE = 20
private const val DEFAULT_COMMENT_PAGE = 50

/**
 * A cursor only when BOTH halves were supplied.
 *
 * A time-only cursor silently skips or repeats rows whenever several share a millisecond, so a
 * half-supplied cursor is read as "no cursor" — the first page — rather than as a cursor that
 * happens to be missing its tiebreak. Returning the first page again is a visible, harmless
 * mistake; a half cursor is an invisible one that loses rows.
 */
private fun cursorOrNull(createdAtMs: Long?, id: String?): SpaceCursor? =
    if (createdAtMs != null && !id.isNullOrBlank()) SpaceCursor(createdAtMs, id) else null

private fun spaceOk(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): List<UIMessagePart> =
    listOf(
        UIMessagePart.Text(
            buildJsonObject {
                put("ok", true)
                put("code", "OK")
                build()
            }.toString(),
        ),
    )

private fun spaceRejected(outcome: SpaceWriteOutcome.Rejected): List<UIMessagePart> = listOf(
    UIMessagePart.Text(
        buildJsonObject {
            put("ok", false)
            put("code", outcome.code)
            put("message", outcome.message)
        }.toString(),
    ),
)

/**
 * No trusted assistant identity was bound to this run. Fails closed rather than attributing the
 * write to the user or to an arbitrary assistant.
 */
private fun spaceIdentityUnavailable(): List<UIMessagePart> = listOf(
    UIMessagePart.Text(
        buildJsonObject {
            put("ok", false)
            put("code", "SPACE_IDENTITY_UNAVAILABLE")
            put("message", "No trusted assistant identity is bound to this run.")
        }.toString(),
    ),
)

private suspend fun spacePostJson(
    repository: SpaceRepository,
    post: SpacePostEntity,
): JsonObject = buildJsonObject {
    put("post_id", post.postId)
    put("author_kind", post.authorKind)
    put("author_id", post.authorId)
    put("content", post.content)
    put("created_at_ms", post.createdAtMs)
    put("like_count", repository.likeCount(post.postId))
    put("comment_count", repository.commentCount(post.postId))
}

private fun spaceCommentJson(comment: SpaceCommentEntity): JsonObject = buildJsonObject {
    put("comment_id", comment.commentId)
    put("post_id", comment.postId)
    put("author_kind", comment.authorKind)
    put("author_id", comment.authorId)
    put("content", comment.content)
    put("created_at_ms", comment.createdAtMs)
}
