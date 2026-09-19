package me.rerere.rikkahub.space

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Removing a deleted assistant's Cat Garden footprint.
 *
 * Posts deliberately outlive their author in the schema — `author_id` is not a foreign key — so
 * that deleting one assistant does not destroy other identities' comments and likes on its posts.
 * The cost of that choice is that nothing removes the rows automatically, and this is the explicit
 * counterpart that runs when the assistant itself is deleted.
 */
class SpaceAssistantFootprintTest {

    private val dao = FakeSpaceDao()
    private var clock = 1_000L
    private var idSeq = 0
    private val repo = SpaceRepository(
        dao = dao,
        nowMs = { clock++ },
        newId = { "id-${++idSeq}" },
    )

    private val assistantA = SpaceActor(SpaceActorKind.ASSISTANT, "aaaaaaaa-0000-0000-0000-000000000001")
    private val assistantB = SpaceActor(SpaceActorKind.ASSISTANT, "bbbbbbbb-0000-0000-0000-000000000002")

    // ── Posts ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `its posts go, and the cascade takes their likes, comments and notifications with them`() =
        runBlocking {
            val aPost = post(assistantA, "from A")
            repo.setLike(assistantB, aPost, liked = true, originDepth = 0)
            repo.setLike(SpaceActor.USER, aPost, liked = true, originDepth = 0)
            repo.createComment(assistantB, aPost, "nice", originDepth = 0)
            repo.createComment(SpaceActor.USER, aPost, "agreed", originDepth = 0)
            assertEquals(2, repo.likeCount(aPost))
            assertEquals(2, repo.commentCount(aPost))

            val removal = repo.deleteAssistantFootprint(assistantA.id)

            assertNull(repo.getPost(aPost))
            assertEquals(0, repo.likeCount(aPost))
            assertEquals(0, repo.commentCount(aPost))
            assertTrue(notificationsOf(assistantB).isEmpty())
            assertTrue(notificationsOf(SpaceActor.USER).isEmpty())

            // The sweep deleted one post directly and nothing else directly: the children went with
            // it through the cascade, and are deliberately NOT counted in the child totals.
            assertEquals(SpaceFootprintRemoval(posts = 1), removal)
        }

    @Test
    fun `its comments and likes on another identity's post go with it`() = runBlocking {
        val bPost = post(assistantB, "from B")
        repo.setLike(assistantA, bPost, liked = true, originDepth = 0)
        repo.createComment(assistantA, bPost, "from A", originDepth = 0)

        val removal = repo.deleteAssistantFootprint(assistantA.id)

        // B's post survives — it is not A's to take — so the cascade cannot reach A's traces on it.
        assertNotNull(repo.getPost(bPost))
        assertEquals(0, repo.likeCount(bPost))
        assertEquals(0, repo.commentCount(bPost))
        assertEquals(1, removal.likes)
        assertEquals(1, removal.comments)
        assertEquals(0, removal.posts)
    }

    @Test
    fun `the notifications it produced on a surviving post go with it`() = runBlocking {
        val bPost = post(assistantB, "from B")
        repo.setLike(assistantA, bPost, liked = true, originDepth = 0)
        repo.createComment(assistantA, bPost, "from A", originDepth = 0)
        assertEquals(2, notificationsOf(assistantB).size)

        val removal = repo.deleteAssistantFootprint(assistantA.id)

        // Left behind, these would announce an author who no longer exists AND point comment_id at
        // a comment this same sweep deleted.
        assertEquals(2, removal.notifications)
        assertTrue(notificationsOf(assistantB).isEmpty())
        assertNotNull(repo.getPost(bPost))
    }

    @Test
    fun `an inbox row addressed to the assistant goes even on a post that survives`() = runBlocking {
        val bPost = post(assistantB, "from B")
        // Written straight to the table. Nothing in the public write path addresses a notification
        // to anyone but the post's author, so this row cannot be produced through the API — which is
        // precisely the invariant the recipient arm must not silently depend on.
        dao.notifications["space:stranded"] = SpaceNotificationEntity(
            notificationId = "space:stranded",
            recipientKind = SpaceActorKind.ASSISTANT.name,
            recipientId = assistantA.id,
            actorKind = SpaceActorKind.USER.name,
            actorId = SpaceActor.USER.id,
            type = SpaceNotificationType.LIKE.name,
            postId = bPost,
            commentId = null,
            createdAtMs = clock++,
            originDepth = 0,
        )

        repo.deleteAssistantFootprint(assistantA.id)

        assertTrue(dao.notifications.isEmpty())
        assertNotNull(repo.getPost(bPost))
    }

    // ── Nothing else is touched ──────────────────────────────────────────────────────────────

    @Test
    fun `another assistant's data and the person's own survive the sweep`() = runBlocking {
        val aPost = post(assistantA, "from A")
        val bPost = post(assistantB, "from B")
        val userPost = post(SpaceActor.USER, "from the person")
        repo.createComment(assistantA, bPost, "A on B", originDepth = 0)
        repo.createComment(assistantB, bPost, "B on B", originDepth = 0)
        repo.setLike(SpaceActor.USER, aPost, liked = true, originDepth = 0)

        repo.deleteAssistantFootprint(assistantA.id)

        assertNotNull(repo.getPost(bPost))
        assertNotNull(repo.getPost(userPost))
        assertEquals(listOf("B on B"), commentsOf(bPost).map { it.content })

        // The person's like on A's post was on a post that no longer exists, so it went with it —
        // and that is the ONLY thing of the person's this sweep may take.
        assertEquals(0, repo.likeCount(aPost))
        assertEquals(1, mine().size)
        assertTrue(notificationsOf(assistantB).none { it.actorId == assistantA.id })
    }

    @Test
    fun `a blank or user id removes nothing`() = runBlocking {
        val aPost = post(assistantA, "from A")
        val userPost = post(SpaceActor.USER, "from the person")
        repo.setLike(assistantB, aPost, liked = true, originDepth = 0)
        repo.createComment(assistantB, userPost, "on the person's post", originDepth = 0)

        // The kind is pinned to ASSISTANT, so neither of these can match a stored row. Asserted
        // rather than assumed: a sweep that fell through to "no filter" would erase everything.
        assertEquals(SpaceFootprintRemoval.NONE, repo.deleteAssistantFootprint(""))
        assertEquals(SpaceFootprintRemoval.NONE, repo.deleteAssistantFootprint("   "))
        assertEquals(SpaceFootprintRemoval.NONE, repo.deleteAssistantFootprint(SpaceActor.USER.id))

        assertEquals(2, dao.posts.size)
        assertEquals(1, dao.likes.size)
        assertEquals(1, dao.comments.size)
        // One addressed to A for the like, one to the person for the comment.
        assertEquals(2, dao.notifications.size)
    }

    @Test
    fun `sweeping the same assistant twice is safe and the second is a no-op`() = runBlocking {
        val aPost = post(assistantA, "from A")
        repo.createComment(assistantA, post(assistantB, "from B"), "A on B", originDepth = 0)

        assertTrue(repo.deleteAssistantFootprint(assistantA.id).posts == 1)
        assertEquals(SpaceFootprintRemoval.NONE, repo.deleteAssistantFootprint(assistantA.id))
        assertNull(repo.getPost(aPost))
    }

    // ── The transaction boundary ─────────────────────────────────────────────────────────────

    @Test
    fun `the whole sweep runs inside one transaction`() = runBlocking {
        val aPost = post(assistantA, "from A")
        val bPost = post(assistantB, "from B")
        repo.setLike(assistantA, bPost, liked = true, originDepth = 0)
        repo.createComment(assistantA, bPost, "A on B", originDepth = 0)

        var transactionsOpened = 0
        var tablesAtBlockExit: List<Int>? = null
        val transactional = SpaceRepository(
            dao = dao,
            nowMs = { clock++ },
            newId = { "tx-${++idSeq}" },
            inTransaction = { block ->
                transactionsOpened++
                block()
                // Read at the instant the block returns: every one of the four deletes must already
                // be applied by then. A sweep that ran any delete outside the block — or opened one
                // transaction per table — would show a non-empty table here.
                tablesAtBlockExit = listOf(
                    dao.posts.size,
                    dao.comments.size,
                    dao.likes.size,
                    dao.notifications.size,
                )
            },
        )

        transactional.deleteAssistantFootprint(assistantA.id)

        assertEquals("one boundary, not one per table", 1, transactionsOpened)
        assertEquals(listOf(1, 0, 0, 0), tablesAtBlockExit)
        assertNull(dao.posts[aPost])
        assertNotNull(dao.posts[bPost])
    }

    @Test
    fun `a sweep that matches nothing still opens exactly one transaction`() = runBlocking {
        var transactionsOpened = 0
        val transactional = SpaceRepository(
            dao = dao,
            nowMs = { clock++ },
            newId = { "tx-${++idSeq}" },
            inTransaction = { block -> transactionsOpened++; block() },
        )

        assertEquals(SpaceFootprintRemoval.NONE, transactional.deleteAssistantFootprint("no-such-assistant"))
        assertEquals(1, transactionsOpened)

        // A blank id is refused before the boundary is even opened: there is no sweep to make atomic.
        assertEquals(SpaceFootprintRemoval.NONE, transactional.deleteAssistantFootprint(""))
        assertEquals(1, transactionsOpened)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────────────────

    private suspend fun post(who: SpaceActor, content: String): String =
        (repo.createPost(who, content, SpaceCausalDepth.USER_INITIATED) as SpaceWriteOutcome.Created).id

    private suspend fun notificationsOf(actor: SpaceActor) =
        repo.listNotificationsPage(actor, before = null, limit = SpaceRepository.MAX_PAGE_SIZE).items

    private suspend fun commentsOf(postId: String) =
        repo.listCommentsPage(postId, after = null, limit = SpaceRepository.MAX_PAGE_SIZE).items

    private suspend fun mine() =
        repo.listPostsByAuthorPage(SpaceActor.USER, before = null, limit = SpaceRepository.MAX_PAGE_SIZE).items
}
