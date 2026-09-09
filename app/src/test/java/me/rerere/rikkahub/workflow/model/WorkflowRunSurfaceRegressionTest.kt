package me.rerere.rikkahub.workflow.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.tools.appendTopToolExample
import me.rerere.rikkahub.toolcatalog.ToolCatalogSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the second `tool_schema_stale` divergence point:
 *
 * A workflow authored in a confirmed Second-User session fingerprints its actions against the
 * assistant's EXPANDED (allowlist) surface, but the engine used to re-derive the run surface from
 * `assistant.localTools` only — so any action tool outside localTools (e.g. `post_notification`
 * when the assistant's localTools is just `[TimeInfo]`) read as `runtime_has_tool=false` and was
 * wrongly reported `tool_schema_stale` on every fresh create→run.
 *
 * The fix has two halves, both pure/JVM and covered here:
 *  1. [WorkflowRunSurfaceResolver] — run uses the same effective surface the author saw
 *     (Second-User allowlist) while the authoring assistant is still the ACTIVE Second User,
 *     and never expands ordinary/LOCAL or revoked workflows.
 *  2. [WorkflowSchemaGate] — "tool absent from the current effective surface" is an
 *     AVAILABILITY problem (`workflow_tool_unavailable`), distinct from "tool present but its
 *     schema fingerprint changed" (`tool_schema_stale`). Learned workflows keep fail-closed.
 */
class WorkflowRunSurfaceRegressionTest {

    // ---- fixtures -------------------------------------------------------------------------

    private val realPostNotificationDescription =
        "Post an Android notification on behalf of the user. Use sparingly — notifications are intrusive."

    /** Replicates the production NotificationTool factory (see NotificationTool.kt). */
    private fun realPostNotificationTool(descriptionOverride: String? = null): Tool = Tool(
        name = "post_notification",
        description = descriptionOverride ?: realPostNotificationDescription,
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("title", buildJsonObject {
                        put("type", "string")
                        put("description", "Notification title")
                    })
                    put("body", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional notification body text")
                    })
                    put("id", buildJsonObject {
                        put("type", "integer")
                        put("description", "Optional notification id; defaults to a fresh auto-generated id")
                    })
                },
                required = listOf("title"),
            )
        },
        execute = { emptyList() },
    )

    private fun plainTool(name: String): Tool = Tool(
        name = name,
        description = "A generic tool $name.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject { },
                required = emptyList(),
            )
        },
        execute = { emptyList() },
    )

    /** The seam used by production authoring + engine: example suffix, fingerprint-equivalent to the full render. */
    private fun rendered(tool: Tool): Tool = appendTopToolExample(tool)

    private fun catalog(vararg tools: Tool): ToolCatalogSnapshot =
        ToolCatalogSnapshot.fromDefinitions(tools.toList().map(::rendered))

    private fun fingerprint(tool: Tool): String =
        ToolCatalogSnapshot.fromDefinitions(listOf(rendered(tool)))
            .entry(tool.name)!!.schemaFingerprint

    private fun action(toolName: String, fp: String?): WorkflowAction =
        WorkflowAction(tool = toolName, args = buildJsonObject { }, toolSchemaFingerprint = fp)

    // ---- 0) evidence: the rendered post_notification surface fingerprints to the stored value ----

    @Test
    fun `rendered post_notification reproduces the observed stored fingerprint`() {
        // Observed on-device for two independently-created workflows (authoring surface stable).
        val observed = "71c0d8ed6dc5d14e1f01e82e76ced4615ab1dc458da9d6613d7886cb7691b7f9"
        assertEquals(observed, fingerprint(realPostNotificationTool()))
    }

    // ---- 1) root-cause regression: second-user workflow must not be schema-stale ----

    @Test
    fun `second-user workflow whose action tool is outside localTools runs against the allowlist surface`() {
        val localToolName = "get_time_info"
        val postTool = rendered(realPostNotificationTool())
        val storedFp = fingerprint(realPostNotificationTool())
        val postAction = action("post_notification", storedFp)

        // Surface decision: authoring assistant is still the ACTIVE Second User.
        val surface = WorkflowRunSurfaceResolver.choose(
            origin = WorkflowOrigin.USER,
            authoringAuthority = WorkflowAuthoringAuthority.SECOND_USER_CONFIRMED,
            referencedToolNames = setOf("post_notification"),
            localSurfaceToolNames = setOf(localToolName),
            assistantIsCurrentActiveSecondUser = true,
        )
        assertEquals(WorkflowRunSurface.SECOND_USER_ALLOWLIST, surface)

        // The allowlist surface exposes the tool and the fingerprint matches → no schema problem.
        assertNull(
            WorkflowSchemaGate.firstProblem(
                listOf(postAction), catalog(postTool), isLearned = false,
            ),
        )
    }

    @Test
    fun `before the fix the same workflow was stale from localTools-only surface; now it is availability not stale`() {
        val localTool = plainTool("get_time_info")
        val localCatalog = catalog(localTool)
        val storedFp = fingerprint(realPostNotificationTool())
        val postAction = action("post_notification", storedFp)

        // The OLD engine scan (assistant.localTools only) tripped schema-stale here because the
        // tool was absent (null entry != stored). The fix reclassifies an absent tool as an
        // availability problem — never `tool_schema_stale` — so it is distinguishable and heals
        // when the tool is re-allowed.
        val problem = WorkflowSchemaGate.firstProblem(listOf(postAction), localCatalog, isLearned = false)
        assertTrue(problem is WorkflowSchemaProblem.ToolUnavailable)
        assertEquals("post_notification", (problem as WorkflowSchemaProblem.ToolUnavailable).action.tool)
    }

    // ---- 2) a REAL schema change on a still-present tool stays stale ----

    @Test
    fun `present tool whose schema changed is still tool_schema_stale`() {
        val storedFp = fingerprint(realPostNotificationTool())
        val mutated = rendered(
            realPostNotificationTool("$realPostNotificationDescription Updated signature wording."),
        )
        assertNotEquals("description change must move the fingerprint", storedFp, fingerprint(realPostNotificationTool(descriptionOverride = "$realPostNotificationDescription Updated signature wording.")))
        val problem = WorkflowSchemaGate.firstProblem(
            listOf(action("post_notification", storedFp)), catalog(mutated), isLearned = false,
        )
        assertTrue(problem is WorkflowSchemaProblem.Stale)
    }

    // ---- 3) current policy no longer allows the tool → availability, not stale ----

    @Test
    fun `allowlist drop makes a second-user workflow unavailable, not stale`() {
        // Assistant is still ACTIVE second user, but the CURRENT allowlist no longer yields the
        // tool, so the effective (allowlist) surface has no post_notification.
        val localTool = plainTool("get_time_info")
        val emptyAllowlistCatalog = catalog(localTool) // simulates allowlist without notification
        val storedFp = fingerprint(realPostNotificationTool())
        val problem = WorkflowSchemaGate.firstProblem(
            listOf(action("post_notification", storedFp)), emptyAllowlistCatalog, isLearned = false,
        )
        assertTrue(problem is WorkflowSchemaProblem.ToolUnavailable)
    }

    // ---- 4) revocation / reassignment removes standing -> falls back to localTools ----

    @Test
    fun `second-user workflow after authority removal uses the assistant local surface`() {
        val surface = WorkflowRunSurfaceResolver.choose(
            origin = WorkflowOrigin.USER,
            authoringAuthority = WorkflowAuthoringAuthority.SECOND_USER_CONFIRMED,
            referencedToolNames = setOf("post_notification"),
            localSurfaceToolNames = setOf("get_time_info"),
            assistantIsCurrentActiveSecondUser = false, // revoked / reassigned elsewhere
        )
        assertEquals(WorkflowRunSurface.ASSISTANT_LOCAL, surface)
        // Against localTools the referenced tool is absent → availability, never a stale claim.
        val problem = WorkflowSchemaGate.firstProblem(
            listOf(action("post_notification", fingerprint(realPostNotificationTool()))),
            catalog(plainTool("get_time_info")),
            isLearned = false,
        )
        assertTrue(problem is WorkflowSchemaProblem.ToolUnavailable)
    }

    // ---- 5) legacy (no marker) heals only when the ordinary surface can't cover a tool AND the
    //         authoring assistant is still the ACTIVE Second User ----

    @Test
    fun `legacy row referencing a tool outside localTools heals via the allowlist surface`() {
        assertEquals(
            WorkflowRunSurface.SECOND_USER_ALLOWLIST,
            WorkflowRunSurfaceResolver.choose(
                origin = WorkflowOrigin.USER,
                authoringAuthority = null, // pre-marker row
                referencedToolNames = setOf("post_notification"),
                localSurfaceToolNames = setOf("get_time_info"),
                assistantIsCurrentActiveSecondUser = true,
            ),
        )
    }

    @Test
    fun `legacy row whose tools all sit inside localTools stays ordinary (allowlist never imposed)`() {
        assertEquals(
            WorkflowRunSurface.ASSISTANT_LOCAL,
            WorkflowRunSurfaceResolver.choose(
                origin = WorkflowOrigin.USER,
                authoringAuthority = null,
                referencedToolNames = setOf("get_time_info"),
                localSurfaceToolNames = setOf("get_time_info"),
                assistantIsCurrentActiveSecondUser = true,
            ),
        )
    }

    @Test
    fun `legacy row does not heal when the assistant is no longer the active second user`() {
        assertEquals(
            WorkflowRunSurface.ASSISTANT_LOCAL,
            WorkflowRunSurfaceResolver.choose(
                origin = WorkflowOrigin.USER,
                authoringAuthority = null,
                referencedToolNames = setOf("post_notification"),
                localSurfaceToolNames = setOf("get_time_info"),
                assistantIsCurrentActiveSecondUser = false,
            ),
        )
    }

    // ---- 6) LOCAL is pinned; LEARNED never expands ----

    @Test
    fun `LOCAL marker pins to the assistant local surface even if the assistant becomes second user`() {
        assertEquals(
            WorkflowRunSurface.ASSISTANT_LOCAL,
            WorkflowRunSurfaceResolver.choose(
                origin = WorkflowOrigin.USER,
                authoringAuthority = WorkflowAuthoringAuthority.LOCAL,
                referencedToolNames = setOf("post_notification"),
                localSurfaceToolNames = setOf("get_time_info"),
                assistantIsCurrentActiveSecondUser = true,
            ),
        )
    }

    @Test
    fun `learned workflow keeps its ordinary local surface regardless of second-user standing`() {
        assertEquals(
            WorkflowRunSurface.ASSISTANT_LOCAL,
            WorkflowRunSurfaceResolver.choose(
                origin = WorkflowOrigin.LEARNED,
                authoringAuthority = WorkflowAuthoringAuthority.SECOND_USER_CONFIRMED,
                referencedToolNames = setOf("post_notification"),
                localSurfaceToolNames = setOf("get_time_info"),
                assistantIsCurrentActiveSecondUser = true,
            ),
        )
    }

    // ---- 7) gate boundary semantics preserved ----

    @Test
    fun `legacy USER row with no fingerprint is allowed to run when the tool is present`() {
        assertNull(
            WorkflowSchemaGate.firstProblem(
                listOf(action("post_notification", fp = null)),
                catalog(realPostNotificationTool()),
                isLearned = false,
            ),
        )
    }

    @Test
    fun `non-canonical stored fingerprint is defensive staleness, never silently trusted`() {
        val problem = WorkflowSchemaGate.firstProblem(
            listOf(action("post_notification", fp = "not-a-64-hex")),
            catalog(realPostNotificationTool()),
            isLearned = false,
        )
        assertTrue(problem is WorkflowSchemaProblem.Stale)
    }

    @Test
    fun `learned workflow keeps fail-closed stale on a missing tool`() {
        val problem = WorkflowSchemaGate.firstProblem(
            listOf(action("post_notification", fingerprint(realPostNotificationTool()))),
            catalog(plainTool("get_time_info")),
            isLearned = true,
        )
        assertTrue("learned must map any anomaly to staleness, not availability", problem is WorkflowSchemaProblem.Stale)
    }

    // ---- 8) marker serialization round-trip + legacy absence + spoof rejection ----

    private fun def(authority: WorkflowAuthoringAuthority?): WorkflowDefinition = WorkflowDefinition(
        id = "wf-1",
        name = "test",
        trigger = TriggerSpec.Manual,
        actions = listOf(action("post_notification", fingerprint(realPostNotificationTool()))),
        authoringAssistantId = null,
        authoringAuthority = authority,
    )

    @Test
    fun `marker survives encode - parseStored round-trip and legacy absence stays null`() {
        val secondUser = WorkflowJson.encode(def(WorkflowAuthoringAuthority.SECOND_USER_CONFIRMED))
        assertEquals(
            WorkflowAuthoringAuthority.SECOND_USER_CONFIRMED,
            WorkflowJson.parseStored(secondUser)!!.authoringAuthority,
        )
        val local = WorkflowJson.encode(def(WorkflowAuthoringAuthority.LOCAL))
        assertEquals(
            WorkflowAuthoringAuthority.LOCAL,
            WorkflowJson.parseStored(local)!!.authoringAuthority,
        )
        val legacy = WorkflowJson.encode(def(null))
        assertNull("legacy (no marker) must stay null so inference can heal it", WorkflowJson.parseStored(legacy)!!.authoringAuthority)
        assertNull(
            "absent key must not be encoded for a legacy row",
            Json.parseToJsonElement(legacy).jsonObject["authoring_authority"],
        )
    }

    @Test
    fun `unknown marker value is rejected on the stored path`() {
        val element = Json.parseToJsonElement(WorkflowJson.encode(def(WorkflowAuthoringAuthority.LOCAL))).jsonObject
        val forged = JsonPrimitive("BOGUS").let { bad ->
            kotlinx.serialization.json.buildJsonObject {
                element.forEach { (k, v) -> if (k == "authoring_authority") put(k, bad) else put(k, v) }
            }
        }.toString()
        assertNull(WorkflowJson.parseStored(forged))
    }

    // ---- 9) ingress: the AUTHORING parser never surfaces a caller marker ----

    @Test
    fun `authoring parser ignores a caller marker (validated shape, never a permission fact)`() {
        val raw = """{"name":"x","trigger":{"type":"manual"},"actions":[{"tool":"get_time_info","args":{},"timeout_seconds":60}],"authoring_authority":"SECOND_USER_CONFIRMED"}"""
        val parsed = WorkflowJson.parse(raw, listOf(plainTool("get_time_info")))
        assertTrue("parse must accept a well-formed definition", parsed is WorkflowJson.ParseResult.Ok)
        assertNull(
            "a caller-emitted marker must not surface from the authoring parser",
            (parsed as WorkflowJson.ParseResult.Ok).definition.authoringAuthority,
        )
    }

    @Test
    fun `authoring parser rejects a malformed marker value`() {
        val raw = """{"name":"x","trigger":{"type":"manual"},"actions":[{"tool":"get_time_info","args":{},"timeout_seconds":60}],"authoring_authority":"OWNER_FORGED"}"""
        val parsed = WorkflowJson.parse(raw, listOf(plainTool("get_time_info")))
        assertTrue(parsed is WorkflowJson.ParseResult.Err)
    }
}
