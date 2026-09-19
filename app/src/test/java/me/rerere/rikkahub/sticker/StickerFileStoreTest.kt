package me.rerere.rikkahub.sticker

import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The sticker file lifecycle, against a real directory.
 *
 * A temp directory rather than a fake filesystem on purpose: every failure this class guards —
 * orphaned bytes, a half-finished import, a delete that removed the row but not the file — is a
 * real filesystem behaviour, and a fake would be free to have the semantics we wish rename had.
 */
class StickerFileStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val filesDir: File get() = temporaryFolder.root
    private val store get() = StickerFileStore(filesDir)

    @Test
    fun `staging copies the bytes into the app-private staging directory`() {
        val bytes = StickerFixtures.png(tag = 1)

        val staged = store.stage(ByteArrayInputStream(bytes))

        assertTrue("staged file should exist", staged.file.isFile)
        assertEquals(bytes.size.toLong(), staged.sizeBytes)
        assertEquals(
            "staged file should live under the app-private sticker directory",
            store.stagingRoot.canonicalPath,
            staged.file.canonicalFile.parentFile?.canonicalPath,
        )
        assertEquals(
            "staging must not be mistaken for a committed image",
            true,
            staged.file.name.endsWith(StickerFileStore.STAGING_SUFFIX),
        )
    }

    @Test
    fun `the checksum is the SHA-256 of the copied bytes`() {
        val bytes = StickerFixtures.png(tag = 7)

        val staged = store.stage(ByteArrayInputStream(bytes))

        // Independently computed by the JDK rather than by this class, so the two must agree.
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, staged.checksum)
    }

    @Test
    fun `identical bytes produce identical checksums and different bytes do not`() {
        val first = store.stage(ByteArrayInputStream(StickerFixtures.png(tag = 3)))
        val same = store.stage(ByteArrayInputStream(StickerFixtures.png(tag = 3)))
        val other = store.stage(ByteArrayInputStream(StickerFixtures.png(tag = 4)))

        assertEquals(first.checksum, same.checksum)
        assertFalse(first.checksum == other.checksum)
    }

    @Test
    fun `commit places the image under its final name and reports a filesDir-relative path`() {
        val staged = store.stage(ByteArrayInputStream(StickerFixtures.png(tag = 1)))

        val relativePath = store.commit(staged, stickerId = "abc", extension = "png")

        assertEquals("stickers/abc.png", relativePath)
        assertNotNull(store.resolve(relativePath!!))
        assertTrue("the staged copy should be gone", !staged.file.exists())
    }

    @Test
    fun `a commit that cannot move the file removes the staged copy and reports nothing`() {
        val staged = store.stage(ByteArrayInputStream(StickerFixtures.png(tag = 1)))
        staged.file.delete()

        val relativePath = store.commit(staged, stickerId = "abc", extension = "png")

        assertNull("a failed placement must not report a path", relativePath)
        assertFalse(staged.file.exists())
        assertEquals(
            "no image should be left behind",
            emptyList<File>(),
            store.root.listFiles()?.filter { it.isFile }.orEmpty(),
        )
    }

    @Test
    fun `discarding a staged file leaves the library empty`() {
        val staged = store.stage(ByteArrayInputStream(StickerFixtures.png(tag = 1)))

        assertTrue(store.discardStaged(staged))

        assertFalse(staged.file.exists())
        assertEquals(0, store.sweepOrphans(emptySet()))
    }

    @Test
    fun `delete removes one committed image`() {
        val relativePath = store.stage(ByteArrayInputStream(StickerFixtures.png(tag = 1)))
            .let { store.commit(it, stickerId = "gone", extension = "png")!! }

        assertTrue(store.delete(relativePath))

        assertNull(store.resolve(relativePath))
        assertFalse(store.delete(relativePath))
    }

    @Test
    fun `resolve refuses anything that is not a direct child of the sticker directory`() {
        val committed = store.stage(ByteArrayInputStream(StickerFixtures.png(tag = 1)))
            .let { store.commit(it, stickerId = "safe", extension = "png")!! }
        assertNotNull(store.resolve(committed))

        listOf(
            "stickers/../evil.png",
            "stickers/sub/dir.png",
            "stickers/",
            "stickers/..",
            "stickers/.",
            "upload/abc.png",
            "abc.png",
            "",
        ).forEach { hostile ->
            assertNull("resolve must refuse `$hostile`", store.resolve(hostile))
        }
    }

    @Test
    fun `delete refuses a traversal path instead of deleting outside the library`() {
        val outsider = temporaryFolder.newFile("outside.png")
        outsider.writeBytes(StickerFixtures.png(tag = 9))

        assertFalse(store.delete("stickers/../outside.png"))

        assertTrue("the file outside the library must survive", outsider.exists())
    }

    @Test
    fun `the orphan sweep removes images no row names and keeps the ones that are named`() {
        val kept = store.stage(ByteArrayInputStream(StickerFixtures.png(tag = 1)))
            .let { store.commit(it, stickerId = "kept", extension = "png")!! }
        val orphan = store.stage(ByteArrayInputStream(StickerFixtures.png(tag = 2)))
            .let { store.commit(it, stickerId = "orphan", extension = "png")!! }

        val removed = store.sweepOrphans(setOf(kept))

        assertEquals(1, removed)
        assertNotNull("a named image must survive the sweep", store.resolve(kept))
        assertNull("an un-row'd image must be reclaimed", store.resolve(orphan))
    }

    @Test
    fun `the orphan sweep leaves the staging directory alone`() {
        val staged = store.stage(ByteArrayInputStream(StickerFixtures.png(tag = 1)))

        store.sweepOrphans(emptySet())

        assertTrue(
            "a staged import is not an orphan — its row simply does not exist yet",
            staged.file.exists(),
        )
    }

    @Test
    fun `the staging sweep only reclaims imports older than the grace period`() {
        val abandoned = store.stage(ByteArrayInputStream(StickerFixtures.png(tag = 1)))
        val inFlight = store.stage(ByteArrayInputStream(StickerFixtures.png(tag = 2)))
        val now = System.currentTimeMillis()
        abandoned.file.setLastModified(now - StickerFileStore.STAGING_GRACE_MS - 1_000)

        val removed = store.sweepStaging(now, StickerFileStore.STAGING_GRACE_MS)

        assertEquals(1, removed)
        assertFalse(abandoned.file.exists())
        assertTrue("an import happening right now must not be swept", inFlight.file.exists())
    }

    @Test
    fun `a swept staging directory does not touch committed images`() {
        val committed = store.stage(ByteArrayInputStream(StickerFixtures.png(tag = 1)))
            .let { store.commit(it, stickerId = "kept", extension = "png")!! }
        val staged = store.stage(ByteArrayInputStream(StickerFixtures.png(tag = 2)))
        staged.file.setLastModified(0L)

        store.sweepStaging(System.currentTimeMillis(), StickerFileStore.STAGING_GRACE_MS)

        assertNotNull(store.resolve(committed))
    }
}
