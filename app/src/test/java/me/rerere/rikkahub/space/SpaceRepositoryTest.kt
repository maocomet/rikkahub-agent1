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

        val userPosts = postsBy(SpaceActor.USER)
        val assistantPosts = postsBy(assistantA)
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

        assertEquals(1, notifications(assistantA).size)
        assertEquals(0, notifications(assistantB).size)
        val notification = notifications(assistantA).single()
        assertEquals(SpaceNotificationType.LIKE.name, notification.type)
        assertEquals(assistantB.id, notification.actorId)
    }

    @Test
    fun `a comment notifies the author`() = runBlocking {
        val postId = post(assistantA)
        repo.createComment(assistantB, postId, "hi", 0)

        val notification = notifications(assistantA).single()
        assertEquals(SpaceNotificationType.COMMENT.name, notification.type)
        assertNotNull(notification.commentId)
    }

    @Test
    fun `acting on your own post never notifies you`() = runBlocking {
        val postId = post(assistantA)
        repo.setLike(assistantA, postId, liked = true, originDepth = 0)
        repo.createComment(assistantA, postId, "self reply", originDepth = 0)

        assertEquals(0, notifications(assistantA).size)
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
        assertEquals(1, notifications(assistantA).size)
    }

    @Test
    fun `a notification records the causal depth of the action that produced it`() = runBlocking {
        val postId = post(assistantA)
        // The person acting directly: the notification IS the record of that action, so it carries
        // the same depth the action did — zero. It is not a further hop beyond the like.
        repo.setLike(SpaceActor.USER, postId, true, SpaceCausalDepth.USER_INITIATED)
        // An assistant acting inside an automation run. This is the depth the trigger gate refuses,
        // and it is the depth the headless tool surface stamps.
        val automation = SpaceActor(SpaceActorKind.ASSISTANT, "cccccccc-0000-0000-0000-000000000003")
        repo.setLike(automation, postId, true, SpaceCausalDepth.AUTOMATION_DRIVEN)

        val byDepth = notifications(assistantA).associateBy { it.actorKind }
        assertEquals(
            SpaceCausalDepth.USER_INITIATED,
            byDepth.getValue(SpaceActorKind.USER.name).originDepth,
        )
        assertEquals(
            SpaceCausalDepth.AUTOMATION_DRIVEN,
            byDepth.getValue(SpaceActorKind.ASSISTANT.name).originDepth,
        )
    }

    @Test
    fun `a comment records the causal depth of the acting write`() = runBlocking {
        val postId = post(assistantA)
        repo.createComment(SpaceActor.USER, postId, "from the person", SpaceCausalDepth.USER_INITIATED)
        repo.createComment(assistantB, postId, "from an automation", SpaceCausalDepth.AUTOMATION_DRIVEN)

        val depths = comments(postId).associate { it.content to it.originDepth }
        assertEquals(SpaceCausalDepth.USER_INITIATED, depths.getValue("from the person"))
        assertEquals(SpaceCausalDepth.AUTOMATION_DRIVEN, depths.getValue("from an automation"))
    }

    @Test
    fun `exactly one depth value may wake a workflow, and it is zero`() {
        // The guard's threshold, asserted over every case the contract names, so a future change
        // has to come through here rather than silently slide.
        assertTrue(SpaceCausalDepth.mayWakeWorkflow(SpaceCausalDepth.USER_INITIATED))
        assertFalse(SpaceCausalDepth.mayWakeWorkflow(SpaceCausalDepth.AUTOMATION_DRIVEN))
        assertFalse(SpaceCausalDepth.mayWakeWorkflow(2))
        // A NEGATIVE depth is the case a `<=` comparison would have got wrong: it is out of
        // contract, not "shallower than the person", and it must not be wake-capable.
        assertFalse(SpaceCausalDepth.mayWakeWorkflow(-1))
        assertFalse(SpaceCausalDepth.mayWakeWorkflow(Int.MIN_VALUE))
    }

    @Test
    fun `only depth zero and above are in contract`() {
        assertTrue(SpaceCausalDepth.isValid(SpaceCausalDepth.USER_INITIATED))
        assertTrue(SpaceCausalDepth.isValid(SpaceCausalDepth.AUTOMATION_DRIVEN))
        assertTrue(SpaceCausalDepth.isValid(2))
        assertFalse(SpaceCausalDepth.isValid(-1))
        assertFalse(SpaceCausalDepth.isValid(Int.MIN_VALUE))
    }

    // ── An illegal depth is refused, never repaired ──────────────────────────────────────────

    @Test
    fun `a negative origin depth is refused on every write and stores nothing`() = runBlocking {
        val postId = post(assistantA)

        assertEquals(
            "INVALID_ORIGIN_DEPTH",
            (repo.createPost(SpaceActor.USER, "illegal", -1) as SpaceWriteOutcome.Rejected).code,
        )
        assertEquals(
            "INVALID_ORIGIN_DEPTH",
            (repo.createComment(assistantB, postId, "illegal", -1) as SpaceWriteOutcome.Rejected).code,
        )
        assertEquals(
            "INVALID_ORIGIN_DEPTH",
            (repo.setLike(assistantB, postId, liked = true, originDepth = -1)
                as SpaceWriteOutcome.Rejected).code,
        )

        assertEquals("only the setup post may exist", 1, dao.posts.size)
        assertEquals(0, dao.comments.size)
        assertEquals(0, dao.likes.size)
        assertEquals(0, dao.notifications.size)
    }

    @Test
    fun `an illegal depth is never clamped into a wake-capable notification`() = runBlocking {
        val postId = post(assistantA)

        // The defect this guards against: clamping -1 up to 0 would produce a notification that
        // looks exactly like the person's own action, and the trigger family would wake a workflow
        // for something no producer legitimately emitted.
        repo.createComment(assistantB, postId, "illegal", -1)
        repo.setLike(assistantB, postId, liked = true, originDepth = -1)

        assertTrue("no row may be written at all", dao.notifications.isEmpty())
        assertFalse(
            "nothing may exist that the trigger would treat as user-originated",
            dao.notifications.values.any {
                SpaceCausalDepth.mayWakeWorkflow(it.originDepth)
            },
        )
        assertEquals(0, repo.commentCount(postId))
    }

    @Test
    fun `the unlike branch refuses an illegal depth too`() = runBlocking {
        val postId = post(assistantA)
        repo.setLike(assistantB, postId, liked = true, SpaceCausalDepth.USER_INITIATED)

        // Unlike emits no notification, so it would be easy to skip validation here — but a
        // negative depth is an out-of-contract argument on this branch as much as on the other.
        assertEquals(
            "INVALID_ORIGIN_DEPTH",
            (repo.setLike(assistantB, postId, liked = false, originDepth = -1)
                as SpaceWriteOutcome.Rejected).code,
        )
        assertEquals("the existing like is untouched", 1, repo.likeCount(postId))
    }

    @Test
    fun `a refused illegal depth is reported, not thrown`() = runBlocking {
        // A caller must be able to see WHY, and the message must not invite clamping it.
        val rejected = repo.createPost(assistantA, "x", -5) as SpaceWriteOutcome.Rejected
        assertTrue(rejected.message.contains("-5"))
        assertTrue(rejected.message.contains("never clamped"))
    }

    @Test
    fun `a notification is consumed exactly once`() = runBlocking {
        val postId = post(assistantA)
        repo.setLike(assistantB, postId, true, 0)
        val notificationId = notifications(assistantA).single().notificationId

        assertTrue(repo.claimNotificationForConsumption(notificationId))
        assertFalse(repo.claimNotificationForConsumption(notificationId))
    }

    @Test
    fun `marking read only touches the named rows owned by that recipient`() = runBlocking {
        val postId = post(assistantA)
        repo.setLike(assistantB, postId, true, 0)
        repo.createComment(assistantB, postId, "hi", 0)
        val inbox = notifications(assistantA)
        assertEquals(2, inbox.size)

        val changed = repo.markNotificationsRead(assistantA, listOf(inbox.first().notificationId))
        assertEquals(1, changed)
        assertEquals(1, notifications(assistantA).count { it.readAtMs == null })

        // A different identity naming those ids changes nothing.
        assertEquals(0, repo.markNotificationsRead(assistantB, listOf(inbox.last().notificationId)))
        assertNull(repo.getNotification(inbox.last().notificationId)!!.readAtMs)
    }

    // ── Pagination ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `cursor pagination covers every row exactly once even within one millisecond`() = runBlocking {
        // All posts share a timestamp: a time-only cursor would loop or skip here.
        repeat(5) { repo.createPost(assistantA, "post $it", 0) }

        val pages = mutableListOf<SpacePage<SpacePostEntity>>()
        var cursor: SpaceCursor? = null
        // Stops at the last page rather than after a fixed number of reads: reading one page past
        // the end would repeat its rows and make the uniqueness assertion below meaningless.
        var guard = 0
        while (true) {
            val page = repo.listPostsPage(before = cursor, limit = 2)
            pages += page
            if (!page.hasMore) break
            cursor = page.items.last().let { SpaceCursor(it.createdAtMs, it.postId) }
            check(++guard <= 10) { "post pagination did not terminate" }
        }

        val seen = pages.flatMap { it.items }.map { it.postId }
        assertEquals(5, seen.size)
        assertEquals("a page boundary must not repeat or skip a post", 5, seen.toSet().size)
        assertEquals(listOf(true, true, false), pages.map { it.hasMore })
    }

    @Test
    fun `listing honours the caller limit and the hard page cap`() = runBlocking {
        repeat(3) { repo.createPost(assistantA, "post $it", 0) }
        assertEquals(2, repo.listPostsPage(before = null, limit = 2).items.size)
        assertEquals(3, repo.listPostsPage(before = null, limit = 10).items.size)

        // A caller asking for more than the cap still gets only the cap: these rows are read into
        // model context, so the ceiling has to hold regardless of what the model requested.
        val fresh = FakeSpaceDao()
        val bigRepo = SpaceRepository(fresh, nowMs = { clock }, newId = { "cap-${++idSeq}" })
        repeat(60) { bigRepo.createPost(assistantA, "p$it", 0) }
        assertEquals(
            SpaceRepository.MAX_PAGE_SIZE,
            bigRepo.listPostsPage(before = null, limit = 500).items.size,
        )
    }

    // ── Post deletion and the cascade ────────────────────────────────────────────────────────

    @Test
    fun `the author may delete their own post`() = runBlocking {
        val postId = post(assistantA)
        assertTrue(repo.deletePost(assistantA, postId) is SpaceWriteOutcome.Created)
        assertNull(repo.getPost(postId))
    }

    @Test
    fun `the local user may delete their own post`() = runBlocking {
        val postId = post(SpaceActor.USER)
        assertTrue(repo.deletePost(SpaceActor.USER, postId) is SpaceWriteOutcome.Created)
        assertNull(repo.getPost(postId))
    }

    @Test
    fun `deleting a post that does not exist reports POST_NOT_FOUND`() = runBlocking {
        val outcome = repo.deletePost(assistantA, "no-such-post")
        assertEquals("POST_NOT_FOUND", (outcome as SpaceWriteOutcome.Rejected).code)
    }

    @Test
    fun `another assistant cannot delete a post and is told why`() = runBlocking {
        val postId = post(assistantA)
        val outcome = repo.deletePost(assistantB, postId)
        assertEquals("NOT_POST_OWNER", (outcome as SpaceWriteOutcome.Rejected).code)
        assertNotNull(repo.getPost(postId))
    }

    @Test
    fun `the local user deletes an assistant's post`() = runBlocking {
        val assistantPost = post(assistantA)

        // The local user moderates the space, so this is allowed — and it is allowed as a moderator,
        // not by pretending the post is theirs.
        assertTrue(repo.deletePost(SpaceActor.USER, assistantPost) is SpaceWriteOutcome.Created)
        assertNull(repo.getPost(assistantPost))
    }

    @Test
    fun `an assistant cannot delete the local user's post`() = runBlocking {
        val userPost = post(SpaceActor.USER)

        // The moderator power runs one way only. Widening the ownership check to "anyone may
        // delete" would let an assistant erase the person's posts, which is exactly what this pins.
        assertEquals(
            "NOT_POST_OWNER",
            (repo.deletePost(assistantA, userPost) as SpaceWriteOutcome.Rejected).code,
        )
        assertNotNull(repo.getPost(userPost))
    }

    @Test
    fun `a user actor that is not the local user gets no moderator power`() = runBlocking {
        val assistantPost = post(assistantA)
        val impostor = SpaceActor(SpaceActorKind.USER, "not_the_local_user")

        // The moderator branch is pinned to the local user SENTINEL, compared as a whole (kind, id)
        // pair. A `USER` actor carrying any other id matches neither branch and fails closed.
        assertEquals(
            "NOT_POST_OWNER",
            (repo.deletePost(impostor, assistantPost) as SpaceWriteOutcome.Rejected).code,
        )
        assertNotNull(repo.getPost(assistantPost))
    }

    @Test
    fun `deleting a post takes its likes, comments and notifications with it`() = runBlocking {
        val postId = post(assistantA)
        repo.setLike(assistantB, postId, true, SpaceCausalDepth.USER_INITIATED)
        repo.setLike(SpaceActor.USER, postId, true, SpaceCausalDepth.USER_INITIATED)
        repo.createComment(assistantB, postId, "nice one", SpaceCausalDepth.USER_INITIATED)
        assertEquals(2, repo.likeCount(postId))
        assertEquals(1, repo.commentCount(postId))
        // Every action here is taken by somebody other than the author, so each one notifies the
        // author exactly once: two likes and one comment.
        assertEquals(3, notifications(assistantA).size)

        assertTrue(repo.deletePost(assistantA, postId) is SpaceWriteOutcome.Created)

        assertEquals(0, repo.likeCount(postId))
        assertEquals(0, repo.commentCount(postId))
        assertEquals(0, notifications(assistantA).size)
        assertTrue(dao.likes.isEmpty() && dao.comments.isEmpty() && dao.notifications.isEmpty())
    }

    @Test
    fun `deleting one post leaves another post's reactions alone`() = runBlocking {
        val doomed = post(assistantA)
        val survivor = post(assistantA)
        repo.setLike(assistantB, doomed, true, SpaceCausalDepth.USER_INITIATED)
        repo.setLike(assistantB, survivor, true, SpaceCausalDepth.USER_INITIATED)
        repo.createComment(assistantB, doomed, "on the doomed one", SpaceCausalDepth.USER_INITIATED)
        repo.createComment(assistantB, survivor, "on the survivor", SpaceCausalDepth.USER_INITIATED)

        repo.deletePost(assistantA, doomed)

        assertEquals(1, repo.likeCount(survivor))
        assertEquals(1, repo.commentCount(survivor))
        // The survivor keeps its own like and comment notifications; the doomed post's are gone.
        assertEquals(2, notifications(assistantA).size)
    }

    // ── Comment pagination ───────────────────────────────────────────────────────────────────

    @Test
    fun `comment pages cover every row exactly once even within one millisecond`() = runBlocking {
        val postId = post(assistantA)
        // A frozen clock: every comment shares a timestamp, so a time-only cursor would loop or
        // skip here.
        repeat(5) { repo.createComment(assistantB, postId, "c$it", SpaceCausalDepth.USER_INITIATED) }

        val pages = walkComments(postId, limit = 2)
        val seen = pages.flatMap { it.items }.map { it.commentId }
        assertEquals(5, seen.size)
        assertEquals("a page boundary must not repeat or skip a comment", 5, seen.toSet().size)
        assertEquals(listOf(true, true, false), pages.map { it.hasMore })
    }

    @Test
    fun `comment pages keep the thread's own oldest-first order`() = runBlocking {
        val postId = post(assistantA)
        clock = 1_000L
        repo.createComment(assistantB, postId, "first", SpaceCausalDepth.USER_INITIATED)
        clock = 2_000L
        repo.createComment(assistantB, postId, "second", SpaceCausalDepth.USER_INITIATED)
        clock = 3_000L
        repo.createComment(assistantB, postId, "third", SpaceCausalDepth.USER_INITIATED)

        val head = repo.listCommentsPage(postId, after = null, limit = 1)
        val tail = repo.listCommentsPage(postId, after = cursorOf(head.items.last()), limit = 10)

        assertEquals(listOf("first"), head.items.map { it.content })
        assertEquals(listOf("second", "third"), tail.items.map { it.content })
    }

    @Test
    fun `comment pages never leak across posts`() = runBlocking {
        val postId = post(assistantA)
        val otherPost = post(assistantA)
        repo.createComment(assistantB, postId, "mine", SpaceCausalDepth.USER_INITIATED)
        repo.createComment(assistantB, otherPost, "theirs", SpaceCausalDepth.USER_INITIATED)

        // A null cursor walks the thread from its start; the other post's comments must not appear,
        // and neither must an unrelated thread be reachable through this post's cursor.
        val page = repo.listCommentsPage(postId, after = null, limit = 10)
        assertEquals(listOf("mine"), page.items.map { it.content })
        assertEquals(
            listOf("theirs"),
            repo.listCommentsPage(otherPost, after = null, limit = 10).items.map { it.content },
        )
    }

    // ── `hasMore` is proven, not guessed ─────────────────────────────────────────────────────

    @Test
    fun `hasMore is exact at forty comments with a twenty-comment page`() = runBlocking {
        val postId = post(assistantA)
        repeat(40) { repo.createComment(assistantB, postId, "c$it", SpaceCausalDepth.USER_INITIATED) }

        val pages = walkComments(postId, limit = 20)

        assertEquals(2, pages.size)
        assertEquals(20, pages[0].items.size)
        assertEquals(20, pages[1].items.size)
        // The old rule (`size == limit && size < total`) answered `true` here, so the second page
        // offered a "load more" that returned nothing.
        assertEquals(listOf(true, false), pages.map { it.hasMore })
    }

    @Test
    fun `hasMore is exact at forty-one comments with a twenty-comment page`() = runBlocking {
        val postId = post(assistantA)
        repeat(41) { repo.createComment(assistantB, postId, "c$it", SpaceCausalDepth.USER_INITIATED) }

        val pages = walkComments(postId, limit = 20)

        assertEquals(3, pages.size)
        assertEquals(listOf(20, 20, 1), pages.map { it.items.size })
        assertEquals(listOf(true, true, false), pages.map { it.hasMore })
    }

    @Test
    fun `hasMore is exact when the thread ends just short of a page`() = runBlocking {
        val postId = post(assistantA)
        repeat(19) { repo.createComment(assistantB, postId, "c$it", SpaceCausalDepth.USER_INITIATED) }

        val page = repo.listCommentsPage(postId, after = null, limit = 20)

        assertEquals(19, page.items.size)
        assertFalse(page.hasMore)
    }

    @Test
    fun `an empty thread has nothing more`() = runBlocking {
        val postId = post(assistantA)
        val page = repo.listCommentsPage(postId, after = null, limit = 20)
        assertTrue(page.items.isEmpty())
        assertFalse(page.hasMore)
    }

    @Test
    fun `a page never returns the lookahead row it used to prove hasMore`() = runBlocking {
        val postId = post(assistantA)
        repeat(21) { repo.createComment(assistantB, postId, "c$it", SpaceCausalDepth.USER_INITIATED) }

        // The thread's OWN order, read in one shot. This fixture's clock is frozen, so every comment
        // shares a millisecond and the cursor's id half decides the order — the ids are not
        // sequential strings, so that order is not the order they were created in. Asserting
        // against "c20" here would be asserting an assumption the contract never made.
        val ordered = dao.listComments(postId, 100)
        assertEquals(21, ordered.size)

        val page = repo.listCommentsPage(postId, after = null, limit = 20)

        assertEquals("the probe row must be trimmed, not returned", 20, page.items.size)
        assertTrue(page.hasMore)
        assertEquals(
            "the page must be the first twenty rows of the thread's own order",
            ordered.take(20).map { it.commentId },
            page.items.map { it.commentId },
        )

        // And the cursor built from this page must be its last row, so the next page resumes at row
        // 21 — not at the probe, which would skip it.
        val next = repo.listCommentsPage(postId, after = cursorOf(page.items.last()), limit = 20)
        assertEquals(
            "the next page must resume at the twenty-first row",
            listOf(ordered[20].commentId),
            next.items.map { it.commentId },
        )
    }

    @Test
    fun `post pages prove hasMore the same way`() = runBlocking {
        repeat(20) { repo.createPost(assistantA, "post $it", 0) }
        val exact = repo.listPostsPage(before = null, limit = 20)
        assertEquals(20, exact.items.size)
        assertFalse("a timeline ending on the boundary has nothing more", exact.hasMore)

        repo.createPost(assistantA, "post 20", 0)
        val over = repo.listPostsPage(before = null, limit = 20)
        assertEquals(20, over.items.size)
        assertTrue(over.hasMore)
    }

    @Test
    fun `author pages prove hasMore the same way`() = runBlocking {
        repeat(20) { repo.createPost(SpaceActor.USER, "mine $it", 0) }
        // Posts by somebody else must not be counted towards this author's "more".
        repeat(5) { repo.createPost(assistantA, "theirs $it", 0) }

        val page = repo.listPostsByAuthorPage(SpaceActor.USER, before = null, limit = 20)

        assertEquals(20, page.items.size)
        assertFalse(page.hasMore)
    }

    @Test
    fun `notification pages prove hasMore the same way`() = runBlocking {
        val postId = post(assistantA)
        repeat(20) { repo.createComment(assistantB, postId, "c$it", SpaceCausalDepth.USER_INITIATED) }

        // One notification per comment, since the id is derived from the action.
        val exact = repo.listNotificationsPage(assistantA, before = null, limit = 20)
        assertEquals(20, exact.items.size)
        assertFalse("exactly twenty notifications is not 'more'", exact.hasMore)
    }

    /** Walks a comment thread to its end, asserting the walk terminates. */
    private suspend fun walkComments(postId: String, limit: Int): List<SpacePage<SpaceCommentEntity>> {
        val pages = mutableListOf<SpacePage<SpaceCommentEntity>>()
        var cursor: SpaceCursor? = null
        // Bounded so a cursor bug fails as a test failure rather than an infinite loop.
        repeat(100) {
            val page = repo.listCommentsPage(postId, after = cursor, limit = limit)
            pages += page
            if (!page.hasMore) return pages
            cursor = cursorOf(page.items.last())
        }
        error("comment pagination did not terminate after 100 pages")
    }

    // ── Pending (unconsumed) notifications ───────────────────────────────────────────────────

    @Test
    fun `pending notifications are the unconsumed ones, oldest first`() = runBlocking {
        val postId = post(assistantA)
        clock = 1_000L
        repo.setLike(assistantB, postId, true, SpaceCausalDepth.USER_INITIATED)
        clock = 2_000L
        repo.createComment(assistantB, postId, "hi", SpaceCausalDepth.USER_INITIATED)

        val pending = repo.listPendingNotifications(assistantA, sinceMs = 0L, limit = 10)
        assertEquals(2, pending.size)
        assertEquals(listOf(1_000L, 2_000L), pending.map { it.createdAtMs })

        repo.claimNotificationForConsumption(pending.first().notificationId)
        val afterClaim = repo.listPendingNotifications(assistantA, sinceMs = 0L, limit = 10)
        assertEquals(listOf(2_000L), afterClaim.map { it.createdAtMs })
    }

    @Test
    fun `pending notifications are scoped to one recipient and one window`() = runBlocking {
        val assistantPost = post(assistantA)
        val userPost = post(SpaceActor.USER)
        clock = 1_000L
        repo.setLike(assistantB, assistantPost, true, SpaceCausalDepth.USER_INITIATED)
        clock = 2_000L
        repo.setLike(assistantB, userPost, true, SpaceCausalDepth.USER_INITIATED)

        assertEquals(1, repo.listPendingNotifications(assistantA, sinceMs = 0L, limit = 10).size)
        assertEquals(1, repo.listPendingNotifications(SpaceActor.USER, sinceMs = 0L, limit = 10).size)
        // A window that starts after both rows sees neither, which is what bounds a replay.
        assertTrue(repo.listPendingNotifications(assistantA, sinceMs = 5_000L, limit = 10).isEmpty())
    }

    private fun cursorOf(comment: SpaceCommentEntity) =
        SpaceCursor(comment.createdAtMs, comment.commentId)

    // Read helpers for tests that are about something other than paging. They go through the SAME
    // page API production uses — there is deliberately no raw list reader on the repository, so a
    // test cannot accidentally exercise a path production does not have.
    private suspend fun notifications(actor: SpaceActor, limit: Int = 10) =
        repo.listNotificationsPage(actor, before = null, limit = limit).items

    private suspend fun postsBy(actor: SpaceActor, limit: Int = 10) =
        repo.listPostsByAuthorPage(actor, before = null, limit = limit).items

    private suspend fun comments(postId: String, limit: Int = 10) =
        repo.listCommentsPage(postId, after = null, limit = limit).items

    private suspend fun post(author: SpaceActor): String =
        (repo.createPost(author, "content", originDepth = 0) as SpaceWriteOutcome.Created).id
}
