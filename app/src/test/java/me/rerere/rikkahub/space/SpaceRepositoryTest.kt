package me.rerere.rikkahub.space

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cat Garden data-layer invariants.
 *
 * These run on the JVM so CI enforces them: the migration tests that guard the schema are
 * instrumentation tests and never execute in the build workflow.
 */
class SpaceRepositoryTest {

    private val dao = FakeSpaceDao()
    private var clock = 1_000L
    private var idSeq = 0
    private val repo = SpaceRepository(
        dao = dao,
        nowMs = { clock },
        newId = { "id-${++idSeq}" },
    )

    private val assistantA = SpaceActor(SpaceActorKind.ASSISTANT, "aaaaaaaa-0000-0000-0000-000000000001")
    private val assistantB = SpaceActor(SpaceActorKind.ASSISTANT, "bbbbbbbb-0000-0000-0000-000000000002")

    // ── Identity ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a post author is the acting identity passed by the runtime`() = runBlocking {
        val outcome = repo.createPost(assistantA, content = "hello garden", originDepth = 0)
        val id = (outcome as SpaceWriteOutcome.Created).id

        val post = requireNotNull(repo.getPost(id)) { "post was not stored" }
        assertEquals(SpaceActorKind.ASSISTANT.name, post.authorKind)
        assertEquals(assistantA.id, post.authorId)
        // The actor is a parameter of the write, not something read out of the content, so no
        // text an author writes can change who the post is attributed to.
        assertEquals("hello garden", post.content)
    }

    @Test
    fun `the local user and an assistant are distinct authors`() = runBlocking {
        repo.createPost(SpaceActor.USER, "from user", 0)
        repo.createPost(assistantA, "from assistant", 0)

        val userPosts = repo.listPostsByAuthor(SpaceActor.USER, 10)
        val assistantPosts = repo.listPostsByAuthor(assistantA, 10)
        assertEquals(1, userPosts.size)
        assertEquals(1, assistantPosts.size)
        assertEquals(SpaceActorKind.USER.name, userPosts.single().authorKind)
        assertEquals("local_user", userPosts.single().authorId)
        assertEquals(assistantA.id, assistantPosts.single().authorId)
    }

    @Test
    fun `blank and oversized content are rejected rather than stored`() = runBlocking {
        assertTrue(repo.createPost(assistantA, "   ", 0) is SpaceWriteOutcome.Rejected)
        assertTrue(
            repo.createPost(assistantA, "x".repeat(SpaceRepository.MAX_POST_CHARS + 1), 0)
                is SpaceWriteOutcome.Rejected
        )
        assertEquals(0, dao.posts.size)
    }

    // ── Likes ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `liking twice is idempotent and reports the second as already applied`() = runBlocking {
        val postId = post(assistantA)

        assertTrue(repo.setLike(assistantB, postId, liked = true, originDepth = 0) is SpaceWriteOutcome.Created)
        assertTrue(
            repo.setLike(assistantB, postId, liked = true, originDepth = 0)
                is SpaceWriteOutcome.AlreadyApplied
        )

        assertEquals(1, repo.likeCount(postId))
        assertTrue(repo.hasLike(assistantB, postId))
    }

    @Test
    fun `unlike removes the like and is safe to repeat`() = runBlocking {
        val postId = post(assistantA)
        repo.setLike(assistantB, postId, liked = true, originDepth = 0)

        repo.setLike(assistantB, postId, liked = false, originDepth = 0)
        assertFalse(repo.hasLike(assistantB, postId))
        assertEquals(0, repo.likeCount(postId))

        repo.setLike(assistantB, postId, liked = false, originDepth = 0)
        assertEquals(0, repo.likeCount(postId))
    }

    @Test
    fun `two actors liking one post produce two likes`() = runBlocking {
        val postId = post(assistantA)
        repo.setLike(assistantB, postId, true, 0)
        repo.setLike(SpaceActor.USER, postId, true, 0)
        assertEquals(2, repo.likeCount(postId))
    }

    @Test
    fun `liking a post that does not exist fails closed`() = runBlocking {
        val outcome = repo.setLike(assistantB, "no-such-post", liked = true, originDepth = 0)
        assertEquals("POST_NOT_FOUND", (outcome as SpaceWriteOutcome.Rejected).code)
        assertEquals(0, dao.likes.size)
    }

    // ── Comments ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a comment is attributed to its author and attached to the post`() = runBlocking {
        val postId = post(assistantA)
        val outcome = repo.createComment(assistantB, postId, "nice one", originDepth = 0)
        val commentId = (outcome as SpaceWriteOutcome.Created).id

        val comment = requireNotNull(dao.getComment(commentId)) { "comment was not stored" }
        assertEquals(postId, comment.postId)
        assertEquals(assistantB.id, comment.authorId)
        assertEquals(1, repo.commentCount(postId))
    }

    @Test
    fun `commenting on a post that does not exist is rejected`() = runBlocking {
        val outcome = repo.createComment(assistantB, "ghost", "hi", 0)
        assertEquals("POST_NOT_FOUND", (outcome as SpaceWriteOutcome.Rejected).code)
        assertEquals(0, dao.comments.size)
    }

    // ── Notifications ────────────────────────────────────────────────────────────────────────

    @Test
    fun `a like notifies the author and not the liker`() = runBlocking {
        val postId = post(assistantA)
        repo.setLike(assistantB, postId, liked = true, originDepth = 0)

        assertEquals(1, repo.listNotifications(assistantA, 10).size)
        assertEquals(0, repo.listNotifications(assistantB, 10).size)
        val notification = repo.listNotifications(assistantA, 10).single()
        assertEquals(SpaceNotificationType.LIKE.name, notification.type)
        assertEquals(assistantB.id, notification.actorId)
    }

    @Test
    fun `a comment notifies the author`() = runBlocking {
        val postId = post(assistantA)
        repo.createComment(assistantB, postId, "hi", 0)

        val notification = repo.listNotifications(assistantA, 10).single()
        assertEquals(SpaceNotificationType.COMMENT.name, notification.type)
        assertNotNull(notification.commentId)
    }

    @Test
    fun `acting on your own post never notifies you`() = runBlocking {
        val postId = post(assistantA)
        repo.setLike(assistantA, postId, liked = true, originDepth = 0)
        repo.createComment(assistantA, postId, "self reply", originDepth = 0)

        assertEquals(0, repo.listNotifications(assistantA, 10).size)
        assertEquals(0, dao.notifications.size)
    }

    @Test
    fun `re-liking after unliking does not create a second notification`() = runBlocking {
        val postId = post(assistantA)
        repo.setLike(assistantB, postId, true, 0)
        repo.setLike(assistantB, postId, false, 0)
        repo.setLike(assistantB, postId, true, 0)

        // The notification id is derived from the action, so the row collapses instead of
        // producing a fresh trigger target on every re-like.
        assertEquals(1, repo.listNotifications(assistantA, 10).size)
    }

    @Test
    fun `assistant-originated notifications carry a greater causal depth than user ones`() = runBlocking {
        val postId = post(assistantA)
        // The user acting directly: the notification records depth 1, one step from the origin.
        repo.setLike(SpaceActor.USER, postId, true, originDepth = 0)
        // An assistant acting inside an automation run that is itself one step from the user:
        // the notification must record depth 2, which the trigger gate refuses to act on.
        val automation = SpaceActor(SpaceActorKind.ASSISTANT, "cccccccc-0000-0000-0000-000000000003")
        repo.setLike(automation, postId, true, originDepth = 1)

        val byDepth = repo.listNotifications(assistantA, 10).associateBy { it.actorKind }
        assertEquals(1, byDepth.getValue(SpaceActorKind.USER.name).originDepth)
        assertEquals(2, byDepth.getValue(SpaceActorKind.ASSISTANT.name).originDepth)
    }

    @Test
    fun `a notification is consumed exactly once`() = runBlocking {
        val postId = post(assistantA)
        repo.setLike(assistantB, postId, true, 0)
        val notificationId = repo.listNotifications(assistantA, 10).single().notificationId

        assertTrue(repo.claimNotificationForConsumption(notificationId))
        assertFalse(repo.claimNotificationForConsumption(notificationId))
    }

    @Test
    fun `marking read only touches the named rows owned by that recipient`() = runBlocking {
        val postId = post(assistantA)
        repo.setLike(assistantB, postId, true, 0)
        repo.createComment(assistantB, postId, "hi", 0)
        val notifications = repo.listNotifications(assistantA, 10)
        assertEquals(2, notifications.size)

        val changed = repo.markNotificationsRead(assistantA, listOf(notifications.first().notificationId))
        assertEquals(1, changed)
        assertEquals(1, repo.listNotifications(assistantA, 10).count { it.readAtMs == null })

        // A different identity naming those ids changes nothing.
        assertEquals(0, repo.markNotificationsRead(assistantB, listOf(notifications.last().notificationId)))
        assertNull(repo.getNotification(notifications.last().notificationId)!!.readAtMs)
    }

    // ── Pagination ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `cursor pagination covers every row exactly once even within one millisecond`() = runBlocking {
        // All posts share a timestamp: a time-only cursor would loop or skip here.
        repeat(5) { repo.createPost(assistantA, "post $it", 0) }

        val firstPage = repo.listPosts(limit = 2)
        assertEquals(2, firstPage.size)

        val secondPage = repo.listPostsBefore(
            SpaceCursor(firstPage.last().createdAtMs, firstPage.last().postId),
            limit = 2,
        )
        val thirdPage = repo.listPostsBefore(
            SpaceCursor(secondPage.last().createdAtMs, secondPage.last().postId),
            limit = 2,
        )

        val seen = (firstPage + secondPage + thirdPage).map { it.postId }
        assertEquals(5, seen.size)
        assertEquals(5, seen.toSet().size)
    }

    @Test
    fun `listing honours the caller limit and the hard page cap`() = runBlocking {
        repeat(3) { repo.createPost(assistantA, "post $it", 0) }
        assertEquals(2, repo.listPosts(limit = 2).size)
        assertEquals(3, repo.listPosts(limit = 10).size)

        // A caller asking for more than the cap still gets only the cap: these rows are read into
        // model context, so the ceiling has to hold regardless of what the model requested.
        val fresh = FakeSpaceDao()
        val bigRepo = SpaceRepository(fresh, nowMs = { clock }, newId = { "cap-${++idSeq}" })
        repeat(60) { bigRepo.createPost(assistantA, "p$it", 0) }
        assertEquals(SpaceRepository.MAX_PAGE_SIZE, bigRepo.listPosts(limit = 500).size)
    }

    private suspend fun post(author: SpaceActor): String =
        (repo.createPost(author, "content", originDepth = 0) as SpaceWriteOutcome.Created).id
}
