package me.rerere.rikkahub.data.claudep

import me.rerere.rikkahub.data.ai.GenerationRunControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

/**
 * The exact-id registry a Claude P tool call finds its run's control through.
 *
 * The failure this guards against is not "the lookup throws". It is that a lookup answers with
 * *a* control — a neighbouring run's, a previous run's, the most recent one — and everything
 * downstream then succeeds while being about the wrong run: the handle is registered against a
 * run that is not executing, and a close proves a stop for a call that is still going.
 *
 * So the tests are mostly negative: ids that are merely near a registered one must miss, an id
 * that was removed must stay missing, and a duplicate registration must not displace the control
 * that is actually running.
 */
class ClaudePToolRunControlsTest {

    private fun control() = GenerationRunControl(Uuid.random())

    @Test
    fun `a control is found by the exact id it was registered under`() {
        val registry = ClaudePToolRunControls()
        val control = control()

        assertTrue(registry.register("run-1", control))

        assertSame(control, registry.find("run-1"))
        assertEquals(1, registry.size)
    }

    @Test
    fun `an id that is merely near a registered one is a miss`() {
        val registry = ClaudePToolRunControls()
        registry.register("run-1", control())

        assertNull("a sibling run", registry.find("run-2"))
        assertNull("a prefix", registry.find("run-"))
        assertNull("a longer id", registry.find("run-11"))
        assertNull("the empty id", registry.find(""))
        assertNull("no id at all", registry.find(null))
    }

    @Test
    fun `another run's control is never returned in this run's place`() {
        val registry = ClaudePToolRunControls()
        val first = control()
        val second = control()
        registry.register("run-1", first)
        registry.register("run-2", second)

        assertSame(first, registry.find("run-1"))
        assertSame(second, registry.find("run-2"))
        assertFalse("the two are distinct controls", first === second)
    }

    @Test
    fun `a duplicate registration is refused and does not displace the live control`() {
        val registry = ClaudePToolRunControls()
        val live = control()
        val impostor = control()
        assertTrue(registry.register("run-1", live))

        assertFalse(registry.register("run-1", impostor))

        assertSame("the control whose job is actually running is the one that stays", live, registry.find("run-1"))
        assertEquals(1, registry.size)
    }

    @Test
    fun `an unregistered run is no longer discoverable`() {
        val registry = ClaudePToolRunControls()
        val control = control()
        registry.register("run-1", control)

        assertTrue(registry.unregister("run-1"))

        assertNull(registry.find("run-1"))
        assertEquals(0, registry.size)
    }

    @Test
    fun `an identity-checked removal does not remove a newer run under the same id`() {
        val registry = ClaudePToolRunControls()
        val old = control()
        registry.register("run-1", old)
        registry.unregister("run-1")

        val newer = control()
        registry.register("run-1", newer)

        assertFalse("a late completion for the old run must not remove the new one", registry.unregister("run-1", old))
        assertSame(newer, registry.find("run-1"))
    }

    @Test
    fun `an identity-checked removal removes the control it names`() {
        val registry = ClaudePToolRunControls()
        val control = control()
        registry.register("run-1", control)

        assertTrue(registry.unregister("run-1", control))
        assertNull(registry.find("run-1"))
    }

    @Test
    fun `removing an unknown run reports that nothing was removed`() {
        val registry = ClaudePToolRunControls()

        assertFalse(registry.unregister("never-registered"))
        assertFalse(registry.unregister("never-registered", control()))
    }

    @Test
    fun `clearing makes every run undiscoverable`() {
        val registry = ClaudePToolRunControls()
        registry.register("run-1", control())
        registry.register("run-2", control())

        registry.clear()

        assertEquals(0, registry.size)
        assertNull(registry.find("run-1"))
        assertNull(registry.find("run-2"))
    }

    @Test
    fun `the registered ids are the ones actually registered`() {
        val registry = ClaudePToolRunControls()
        registry.register("run-1", control())
        registry.register("run-2", control())

        assertEquals(setOf("run-1", "run-2"), registry.registeredRunIds())
    }

    @Test
    fun `concurrent registration and removal leave the registry consistent`() {
        val registry = ClaudePToolRunControls()
        val runs = 64
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)

        // Every worker registers its own run and then removes it, all released at once. What is
        // asserted is the invariant, not a particular interleaving: whatever the order, a run that
        // registered successfully is findable by its own id and by no other, and once removed it
        // is gone.
        val failures = java.util.Collections.synchronizedList(mutableListOf<String>())
        val workers = (0 until runs).map { index ->
            val runId = "run-$index"
            val control = control()
            pool.submit {
                start.await()
                if (!registry.register(runId, control)) {
                    failures += "$runId was refused its first registration"
                    return@submit
                }
                if (registry.find(runId) !== control) failures += "$runId did not find its own control"
                val neighbour = "run-${(index + 1) % runs}"
                val found = registry.find(neighbour)
                if (found === control) failures += "$runId found its own control under $neighbour"
                if (!registry.unregister(runId, control)) failures += "$runId could not remove its control"
                if (registry.find(runId) != null) failures += "$runId was still discoverable after removal"
            }
        }
        start.countDown()
        workers.forEach { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(emptyList<String>(), failures.toList())
        assertEquals("every worker removed what it registered", 0, registry.size)
    }
}
