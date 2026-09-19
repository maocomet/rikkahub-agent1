package me.rerere.rikkahub.data.repository

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Production composition checks for the assistant removal path.
 *
 * [AssistantRemovalServiceTest] proves the ordering *given* the wiring. This proves the wiring,
 * which is the half a unit test cannot reach: the service is assembled by Koin against Android-bound
 * collaborators, so dropping the Cat Garden dependency there would restore the ghost-author bug in
 * production while every JVM test stayed green.
 *
 * The same source-level idiom the repository already uses for this class of guarantee — see
 * TransientConversationFinalizationProductionContractTest.
 */
class AssistantRemovalServiceWiringTest {

    @Test
    fun `production DI gives the removal service the space repository`() {
        val module = read("app/src/main/java/me/rerere/rikkahub/di/RepositoryModule.kt")

        // A bounded window rather than "up to the next close paren": the constructor's arguments
        // are themselves `get()` calls, so the first `)` after the opening paren belongs to one of
        // those and the window would stop short of the argument being asserted on.
        val start = module.indexOf("AssistantRemovalService(")
        assertTrue("RepositoryModule no longer constructs AssistantRemovalService", start >= 0)
        val construction = module.substring(start, minOf(module.length, start + 900))
        assertTrue(
            "assistant removal must be wired to the space repository, or a deleted assistant's " +
                "posts survive as a ghost author",
            construction.contains("spaceRepository = get()"),
        )
    }

    @Test
    fun `the service hands the space repository to the removal sequence`() {
        val service = read(
            "app/src/main/java/me/rerere/rikkahub/data/repository/AssistantRemovalService.kt",
        )

        // The service is an adapter; the ordering lives in the sequence. If this binding were
        // dropped the sequence would still compile, and its tests would still pass, while nothing
        // ever reached Cat Garden.
        assertTrue(
            "the removal sequence must be given the space footprint sweep",
            service.contains("deleteSpaceFootprintOf = { spaceRepository.deleteAssistantFootprint(it) }"),
        )
    }

    @Test
    fun `the production space repository is built with a real transaction`() {
        val module = read("app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt")

        // The JVM tests inject a no-op boundary, so nothing else pins the production one. Without
        // it the four-table sweep is four separate autocommits, which is the half-applied state
        // deleteAssistantFootprint exists to avoid.
        assertTrue(
            "SpaceRepository must be built with AppDatabase.withTransaction",
            module.contains("inTransaction = { block -> database.withTransaction { block() } }"),
        )
    }

    private fun read(relative: String): String =
        Files.readString(locateProjectRoot().resolve(relative), StandardCharsets.UTF_8)

    private fun locateProjectRoot(): Path {
        var cursor = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        repeat(6) {
            if (Files.isDirectory(cursor.resolve("app/src/main/java"))) return cursor
            cursor = cursor.parent ?: return@repeat
        }
        error("Unable to locate project root")
    }
}
