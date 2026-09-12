package me.rerere.rikkahub.workflow.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `space_notification_created` *wire format*, exercised through the real parser.
 *
 * Every other trigger test constructs [TriggerSpec] values directly in Kotlin, which is exactly
 * how a discriminator/business-field name collision shipped green in CI while being impossible to
 * author on device: `trigger.type` was doing double duty as the polymorphic class discriminator
 * and as the LIKE/COMMENT filter name, so the discriminator's own value was decoded into the
 * filter field (and `params.type` overwrote the discriminator and made the variant unresolvable).
 * Nothing that stays inside Kotlin types can catch that, so every assertion below goes through
 * [WorkflowJson].
 *
 * KEEP USING THE REAL [TriggerSpec.SpaceNotificationCreated] HERE.
 * Do not add a second `@Serializable` class annotated `@SerialName("space_notification_created")`
 * as a test double. kotlinx's `DescriptorSchemaCache` is keyed by `SerialDescriptor`, and
 * descriptor equality ignores property names (it compares each element's *type* serialName and
 * kind), so two such classes whose elements are both `String?` compare equal and share a single
 * cached JSON name map. Whichever one decodes first silently dictates the other's names, which
 * would make these assertions pass — or fail — for the wrong reason.
 */
class SpaceNotificationTriggerJsonTest {

    private val knownTools = setOf("post_notification")

    private fun parse(trigger: String): WorkflowJson.ParseResult = WorkflowJson.parse(
        """{"name":"Cat Garden","trigger":$trigger,"actions":[{"tool":"post_notification","args":{}}]}""",
        knownTools,
    )

    private fun trigger(trigger: String): TriggerSpec =
        (parse(trigger) as WorkflowJson.ParseResult.Ok).definition.trigger

    // -- Accepted shapes -----------------------------------------------------------------

    @Test
    fun `accepts a COMMENT filter`() {
        val t = trigger(
            """{"type":"space_notification_created","params":{"notice_type":"COMMENT"}}""",
        )
        // Equality on the data class asserts BOTH that the variant resolved AND that the filter
        // value landed in the business field.
        assertEquals(TriggerSpec.SpaceNotificationCreated(noticeType = "COMMENT"), t)
    }

    @Test
    fun `accepts a LIKE filter`() {
        val t = trigger(
            """{"type":"space_notification_created","params":{"notice_type":"LIKE"}}""",
        )
        assertEquals(TriggerSpec.SpaceNotificationCreated(noticeType = "LIKE"), t)
    }

    /**
     * The original defect in one assertion: an omitted filter must stay `null` ("match both"),
     * never pick up the discriminator's own serial name from the flattened trigger object.
     */
    @Test
    fun `an omitted filter is null and never the discriminator value`() {
        val t = trigger("""{"type":"space_notification_created"}""")
        assertEquals(TriggerSpec.SpaceNotificationCreated(noticeType = null), t)
    }

    /** LLMs commonly emit the flat form; it must accept the filter under the same name. */
    @Test
    fun `accepts the flat form with notice_type`() {
        val t = trigger("""{"type":"space_notification_created","notice_type":"LIKE"}""")
        assertEquals(TriggerSpec.SpaceNotificationCreated(noticeType = "LIKE"), t)
    }

    // -- Rejected shapes -----------------------------------------------------------------

    @Test
    fun `rejects an unknown notice type`() {
        val r = parse(
            """{"type":"space_notification_created","params":{"notice_type":"SHARE"}}""",
        ) as WorkflowJson.ParseResult.Err
        assertEquals("invalid_trigger", r.error)
        assertTrue(
            "detail should name the offending field, was: ${r.detail}",
            r.detail.contains("notice_type"),
        )
        assertTrue(
            "detail should list the accepted values, was: ${r.detail}",
            r.detail.contains("LIKE") && r.detail.contains("COMMENT"),
        )
    }

    /**
     * Regression guard for the collision itself. `params.type` used to *overwrite* the class
     * discriminator, leaving `{"type":"COMMENT"}` — an unresolvable variant. It must keep failing;
     * what it must never do is silently become the LIKE/COMMENT filter.
     */
    @Test
    fun `the old params type spelling is not read as a filter`() {
        val r = parse(
            """{"type":"space_notification_created","params":{"type":"COMMENT"}}""",
        )
        assertTrue(
            "params.type must not be accepted as a business filter",
            r is WorkflowJson.ParseResult.Err,
        )
        assertEquals("unknown_trigger_type", (r as WorkflowJson.ParseResult.Err).error)
    }

    // -- Round-trip ----------------------------------------------------------------------

    @Test
    fun `encode and decode round trip is stable`() {
        for (value in listOf("LIKE", "COMMENT")) {
            val raw =
                """{"type":"space_notification_created","params":{"notice_type":"$value"}}"""
            val parsed = parse(raw) as WorkflowJson.ParseResult.Ok
            val encoded = WorkflowJson.encode(parsed.definition)

            // The wire shape itself: the discriminator keeps its name, the filter does not.
            assertTrue("encoded should carry the discriminator: $encoded", encoded.contains("\"space_notification_created\""))
            assertTrue("encoded should carry notice_type: $encoded", encoded.contains("\"notice_type\""))

            val reparsed = requireNotNull(WorkflowJson.parseStored(encoded)) {
                "stored reparse failed for $encoded"
            }
            assertEquals(
                TriggerSpec.SpaceNotificationCreated(noticeType = value),
                reparsed.trigger,
            )

            // Encoding must be a fixed point, or a save/load cycle would drift.
            assertEquals(encoded, WorkflowJson.encode(reparsed))
        }
    }

    @Test
    fun `an omitted filter survives a round trip as null`() {
        val parsed = parse("""{"type":"space_notification_created"}""") as WorkflowJson.ParseResult.Ok
        val reparsed = requireNotNull(
            WorkflowJson.parseStored(WorkflowJson.encode(parsed.definition)),
        )
        assertEquals(TriggerSpec.SpaceNotificationCreated(noticeType = null), reparsed.trigger)
    }
}
