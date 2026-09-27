package me.rerere.rikkahub.data.ai.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reference syntax, the closed vocabulary, and the refusals.
 *
 * These are the golden/characterization tests for the stable-system prompt contract: the marker
 * shape, the classification of every registered key, the escaping, and the rule that an
 * unclassified key in app-composed text is a refusal rather than a pass-through. Changing the
 * marker, the vocabulary, the canonical order or the classification has to break something here,
 * which is the point — the wire value a Claude P session is continued under is derived from them.
 */
class PromptReferencePolicyTest {

    /** A stable key's value, as the layout freezes it. */
    private val stableValueOf: (String) -> String? = { key ->
        PromptReferencePolicy.stableValueOf(key, "Rikka")
    }

    private fun marker(name: String) = "<runtime_value_ref name=\"$name\"/>"

    // ---------------------------------------------------------------------------------------
    // The marker
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a dynamic key becomes a fixed reference and is reported as referenced`() {
        val result = PromptReferencePolicy.neutralizeAppControlledTemplate(
            "## Info\n- Date: {{cur_date}}",
            stableValueOf,
        )

        assertEquals("## Info\n- Date: ${marker("cur_date")}", result.text)
        assertEquals(listOf("cur_date"), result.referencedKeys)
    }

    @Test
    fun `the marker shape is fixed, so a session identity cannot drift on syntax alone`() {
        assertEquals("<runtime_value_ref name=\"cur_time\"/>", PromptReferencePolicy.markerFor("cur_time"))
        assertEquals("<runtime_value_ref name=\"battery_level\"/>", PromptReferencePolicy.markerFor("battery_level"))
    }

    @Test
    fun `repeating one reference reports it once, because one name owes one value`() {
        val result = PromptReferencePolicy.neutralizeAppControlledTemplate(
            "{{cur_date}} ... {{cur_date}} ... {{cur_date}}",
            stableValueOf,
        )

        assertEquals(listOf("cur_date"), result.referencedKeys)
        assertEquals(
            "${marker("cur_date")} ... ${marker("cur_date")} ... ${marker("cur_date")}",
            result.text,
        )
    }

    @Test
    fun `the single-brace form the engine also understands is rewritten too`() {
        // The registry substitutes both `{{key}}` and `{key}`. If only the double-brace form were
        // neutralised, `{cur_date}` would survive into the system prompt and be substituted there —
        // a dynamic value in the message this whole change exists to keep stable.
        val result = PromptReferencePolicy.neutralizeAppControlledTemplate("{cur_date}", stableValueOf)

        assertEquals(marker("cur_date"), result.text)
        assertEquals(listOf("cur_date"), result.referencedKeys)
    }

    @Test
    fun `neutralising an already neutralised template changes nothing`() {
        val once = PromptReferencePolicy.neutralizeAppControlledTemplate(
            "- Date: {{cur_date}} - Battery: {{battery_level}}",
            stableValueOf,
        ).text
        val twice = PromptReferencePolicy.neutralizeAppControlledTemplate(once, stableValueOf)

        assertEquals(once, twice.text)
        assertTrue(twice.referencedKeys.isEmpty())
    }

    // ---------------------------------------------------------------------------------------
    // The classification
    // ---------------------------------------------------------------------------------------

    @Test
    fun `assistant configuration is substituted in place and is not a reference`() {
        val result = PromptReferencePolicy.neutralizeAppControlledTemplate(
            "You are a helpful assistant, called {{char}}.",
            stableValueOf,
        )

        assertEquals("You are a helpful assistant, called Rikka.", result.text)
        assertTrue("a stable value owes no runtime entry", result.referencedKeys.isEmpty())
    }

    @Test
    fun `every key the shipped default assistant uses is classified`() {
        // The default assistant prompt, verbatim. If a key here were unclassified the request would
        // be refused, so this pins the two together.
        val defaultAssistantPrompt = """
            You are a helpful assistant, called {{char}}, based on model {{model_name}}.

            ## Info
            - Date: {{cur_date}}
            - Locale: {{locale}}
            - Timezone: {{timezone}}
            - Device Info: {{device_info}}
            - System Version: {{system_version}}
            - User Nickname: {{user}}
        """.trimIndent()

        val result = PromptReferencePolicy.neutralizeAppControlledTemplate(
            defaultAssistantPrompt,
            stableValueOf,
        )

        assertTrue(result.text.contains("called Rikka"))
        assertEquals(
            listOf("cur_date", "model_name", "locale", "timezone", "system_version", "device_info", "user"),
            result.referencedKeys,
        )
    }

    @Test
    fun `device and model state are dynamic, so a change in them cannot break continuation`() {
        // Each of these is stable for long stretches and none of them is *provably* stable between
        // two turns. The rule is about proof, not about how often a value happens to change.
        for (key in listOf(
            "cur_date", "cur_time", "cur_datetime",
            "model_id", "model_name",
            "locale", "timezone", "system_version", "device_info", "battery_level",
            "nickname", "user",
        )) {
            assertTrue("$key must be dynamic", PromptReferencePolicy.isDynamic(key))
        }
    }

    @Test
    fun `an unclassified key in app-composed text is refused, never passed through`() {
        val failure = assertThrows(PromptReferenceException::class.java) {
            PromptReferencePolicy.neutralizeAppControlledTemplate("hello {{mystery}}", stableValueOf)
        }

        assertEquals(PromptReferenceRejection.UNCLASSIFIED, failure.reason)
        assertEquals("mystery", failure.key)
    }

    @Test
    fun `a placeholder-shaped token inside a substituted value is refused`() {
        // An assistant named `{{cur_date}}` would otherwise leave a token in the output that no
        // later pass would resolve, because the pass that would have handled it already ran.
        val failure = assertThrows(PromptReferenceException::class.java) {
            PromptReferencePolicy.neutralizeAppControlledTemplate("called {{char}}") { key ->
                PromptReferencePolicy.stableValueOf(key, "{{cur_date}}")
            }
        }

        assertEquals(PromptReferenceRejection.UNRESOLVED, failure.reason)
        assertEquals("cur_date", failure.key)
    }

    @Test
    fun `a registered key with no value is refused rather than left as a hole`() {
        val failure = assertThrows(PromptReferenceException::class.java) {
            PromptReferencePolicy.neutralizeAppControlledTemplate("called {{char}}") { null }
        }

        assertEquals(PromptReferenceRejection.UNRESOLVED, failure.reason)
    }

    @Test
    fun `the exception carries the key and never the surrounding text`() {
        val failure = assertThrows(PromptReferenceException::class.java) {
            PromptReferencePolicy.neutralizeAppControlledTemplate(
                "a secret-looking sentence {{mystery}}",
                stableValueOf,
            )
        }

        // The key is a token from a closed vocabulary; the sentence is not in the message.
        assertEquals("prompt_reference_unclassified:mystery", failure.message)
    }

    // ---------------------------------------------------------------------------------------
    // The concrete form, used by relocated content
    // ---------------------------------------------------------------------------------------

    @Test
    fun `the concrete rewrite resolves dynamic keys to their values`() {
        val resolved = PromptReferencePolicy.resolveAppControlledTemplate(
            "Date {{cur_date}}, battery {battery_level}, called {{char}}",
        ) { key ->
            when (key) {
                "cur_date" -> "Sep 27, 2026"
                "battery_level" -> "87"
                "char" -> "Rikka"
                else -> null
            }
        }

        assertEquals("Date Sep 27, 2026, battery 87, called Rikka", resolved)
    }

    @Test
    fun `the concrete rewrite refuses a key it cannot resolve`() {
        val failure = assertThrows(PromptReferenceException::class.java) {
            PromptReferencePolicy.resolveAppControlledTemplate("{{cur_date}}") { null }
        }
        assertEquals(PromptReferenceRejection.UNRESOLVED, failure.reason)
    }

    @Test
    fun `resolveDynamicValues skips stable keys and refuses what it cannot classify`() {
        assertEquals(
            mapOf("cur_date" to "today"),
            PromptReferencePolicy.resolveDynamicValues(listOf("cur_date", "char")) { key ->
                if (key == "cur_date") "today" else "Rikka"
            },
        )

        val failure = assertThrows(PromptReferenceException::class.java) {
            PromptReferencePolicy.resolveDynamicValues(listOf("mystery")) { "x" }
        }
        assertEquals(PromptReferenceRejection.UNCLASSIFIED, failure.reason)
    }

    // ---------------------------------------------------------------------------------------
    // Escaping
    // ---------------------------------------------------------------------------------------

    @Test
    fun `values and names are escaped so they cannot break the block they sit in`() {
        assertEquals("&amp;&lt;&gt;&quot;&apos;", PromptReferencePolicy.escapeXmlAttribute("&<>\"'"))
    }

    // ---------------------------------------------------------------------------------------
    // Case: the transformer this replaced matched with `ignoreCase = true`
    // ---------------------------------------------------------------------------------------

    /** `cur_date` → `Cur_Date`, the spelling the review named. */
    private fun mixedCase(key: String): String =
        key.split('_').joinToString("_") { part -> part.replaceFirstChar { it.uppercaseChar() } }

    private val spellings = { key: String -> listOf(key, key.uppercase(), mixedCase(key)) }

    @Test
    fun `every registered key matches case-insensitively in both brace forms`() {
        // The heart of the R1 finding. A case-sensitive lookup does not merely refuse more: for the
        // single-brace form it refuses *nothing*, leaves the token in the text, leaves it
        // identically in the frozen expectation, and the wire check passes with a raw placeholder
        // inside the system instruction. So this is asserted over the whole vocabulary rather than
        // over one example.
        for (key in PromptReferencePolicy.REGISTERED_KEYS) {
            for (spelling in spellings(key)) {
                for (form in listOf("{{%s}}", "{%s}")) {
                    val written = form.format(spelling)
                    val result = PromptReferencePolicy.neutralizeAppControlledTemplate(
                        "prefix $written suffix",
                        stableValueOf,
                    )
                    val where = "$written"

                    if (PromptReferencePolicy.isDynamic(key)) {
                        assertEquals(where, "prefix ${marker(key)} suffix", result.text)
                        assertEquals(where, listOf(key), result.referencedKeys)
                    } else {
                        // `char` is the stable one: substituted in place, however it was spelled.
                        assertEquals(where, "prefix Rikka suffix", result.text)
                        assertTrue(where, result.referencedKeys.isEmpty())
                    }
                }
            }
        }
    }

    @Test
    fun `every spelling of one name collapses to one canonical reference and one value`() {
        val result = PromptReferencePolicy.neutralizeAppControlledTemplate(
            "{{CUR_DATE}} and {Cur_Date} and {{cur_date}}",
            stableValueOf,
        )

        assertEquals(
            "${marker("cur_date")} and ${marker("cur_date")} and ${marker("cur_date")}",
            result.text,
        )
        assertEquals(listOf("cur_date"), result.referencedKeys)
        assertEquals(
            mapOf("cur_date" to "Sep 27, 2026"),
            PromptReferencePolicy.resolveDynamicValues(result.referencedKeys) { "Sep 27, 2026" },
        )
    }

    @Test
    fun `the concrete rewrite is case-insensitive and canonical as well`() {
        val resolved = PromptReferencePolicy.resolveAppControlledTemplate(
            "{{CUR_DATE}}/{Cur_Date}",
        ) { "Sep 27, 2026" }

        assertEquals("Sep 27, 2026/Sep 27, 2026", resolved)
    }

    @Test
    fun `an unknown double-brace token is refused whatever case it is written in`() {
        for (spelling in listOf("mystery", "MYSTERY", "Mystery")) {
            val failure = assertThrows(PromptReferenceException::class.java) {
                PromptReferencePolicy.neutralizeAppControlledTemplate("{{$spelling}}", stableValueOf)
            }
            assertEquals(PromptReferenceRejection.UNCLASSIFIED, failure.reason)
            // The canonical name, not the spelling: the key is a name from a closed vocabulary.
            assertEquals("mystery", failure.key)
        }
    }

    @Test
    fun `an unknown single-brace token is left as ordinary text`() {
        for (text in listOf("{MYSTERY}", "{Cur_Date_ish}", "{ \"a\": 1 }", "{not a placeholder}")) {
            val result = PromptReferencePolicy.neutralizeAppControlledTemplate(text, stableValueOf)

            assertEquals(text, result.text)
            assertTrue(result.referencedKeys.isEmpty())
        }
    }

    @Test
    fun `canonicalKey is the registry's lowercase name`() {
        assertEquals("cur_date", PromptReferencePolicy.canonicalKey("CUR_DATE"))
        assertEquals("cur_date", PromptReferencePolicy.canonicalKey("Cur_Date"))
        assertEquals("cur_date", PromptReferencePolicy.canonicalKey("cur_date"))
    }
}
