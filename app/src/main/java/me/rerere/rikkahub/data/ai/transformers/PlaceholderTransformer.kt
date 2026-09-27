package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.prompt.PromptReferencePolicy
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Assistant
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.Temporal
import java.util.Locale
import java.util.TimeZone

data class PlaceholderCtx(
    val context: Context,
    val settingsStore: SettingsStore,
    val model: Model,
    val assistant: Assistant,
)

interface PlaceholderProvider {
    val placeholders: Map<String, PlaceholderInfo>
}

data class PlaceholderInfo(
    val displayName: @Composable () -> Unit,
    val resolver: (PlaceholderCtx) -> String
)

class PlaceholderBuilder {
    private val placeholders = mutableMapOf<String, PlaceholderInfo>()

    fun placeholder(
        key: String,
        displayName: @Composable () -> Unit,
        resolver: (PlaceholderCtx) -> String
    ) {
        placeholders[key] = PlaceholderInfo(displayName, resolver)
    }

    fun build(): Map<String, PlaceholderInfo> = placeholders.toMap()
}

fun buildPlaceholders(block: PlaceholderBuilder.() -> Unit): Map<String, PlaceholderInfo> {
    return PlaceholderBuilder().apply(block).build()
}

object DefaultPlaceholderProvider : PlaceholderProvider {
    override val placeholders: Map<String, PlaceholderInfo> = buildPlaceholders {
        placeholder("cur_date", { Text(stringResource(R.string.placeholder_current_date)) }) {
            LocalDate.now().toDateString()
        }

        placeholder("cur_time", { Text(stringResource(R.string.placeholder_current_time)) }) {
            LocalTime.now().toTimeString()
        }

        placeholder("cur_datetime", { Text(stringResource(R.string.placeholder_current_datetime)) }) {
            LocalDateTime.now().toDateTimeString()
        }

        placeholder("model_id", { Text(stringResource(R.string.placeholder_model_id)) }) {
            it.model.modelId
        }

        placeholder("model_name", { Text(stringResource(R.string.placeholder_model_name)) }) {
            it.model.displayName
        }

        placeholder("locale", { Text(stringResource(R.string.placeholder_locale)) }) {
            Locale.getDefault().displayName
        }

        placeholder("timezone", { Text(stringResource(R.string.placeholder_timezone)) }) {
            TimeZone.getDefault().displayName
        }

        placeholder("system_version", { Text(stringResource(R.string.placeholder_system_version)) }) {
            "Android SDK v${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})"
        }

        placeholder("device_info", { Text(stringResource(R.string.placeholder_device_info)) }) {
            "${Build.BRAND} ${Build.MODEL}"
        }

        placeholder("battery_level", { Text(stringResource(R.string.placeholder_battery_level)) }) {
            it.context.batteryLevel().toString()
        }

        placeholder("nickname", { Text(stringResource(R.string.placeholder_nickname)) }) {
            it.settingsStore.settingsFlow.value.displaySetting.userNickname.ifBlank { "user" }
        }

        // Delegates to the policy so there is one definition of what `char` resolves to: the
        // neutraliser and this registry must agree byte for byte, and two copies of the expression
        // is how they stop agreeing.
        placeholder("char", { Text(stringResource(R.string.placeholder_char)) }) {
            PromptReferencePolicy.stableValueOf("char", it.assistant.name) ?: "assistant"
        }

        placeholder("user", { Text(stringResource(R.string.placeholder_user)) }) {
            it.settingsStore.settingsFlow.value.displaySetting.userNickname.ifBlank { "user" }
        }
    }

    private fun Temporal.toDateString() = DateTimeFormatter
        .ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .format(this)

    private fun Temporal.toTimeString() = DateTimeFormatter
        .ofLocalizedTime(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .format(this)

    private fun Temporal.toDateTimeString() = DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .format(this)

    private fun Context.batteryLevel(): Int {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }
}

object PlaceholderTransformer : InputMessageTransformer, KoinComponent {

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val placeholderCtx = PlaceholderCtx(
            context = ctx.context,
            settingsStore = get<SettingsStore>(),
            model = ctx.model,
            assistant = ctx.assistant,
        )
        val session = ctx.stableSystemPromptSession

        // No stable-system requirement: byte-for-byte the behaviour that existed before this
        // parameter did. Every provider other than Claude P takes this path, and this branch is the
        // reason the change cannot reach them.
        if (session == null) {
            return messages.map { it.substitutingPlaceholders(placeholderCtx) }
        }

        // A stable-system provider. The *system* message keeps a fixed reference where a dynamic
        // value would have gone, and the value is recorded for this turn's runtime context.
        // Everything else — the user's own turns, the model's replies — keeps the substitution it
        // always had: `{{cur_date}}` in a message the user wrote is that user's text, and answering
        // with a privilege check on it would be the app reinterpreting someone else's words.
        val referenced = LinkedHashSet<String>()
        val transformed = messages.map { message ->
            if (message.role != MessageRole.SYSTEM) {
                message.substitutingPlaceholders(placeholderCtx)
            } else {
                val neutralized = neutralizeStableSystemMessage(message) { key ->
                    resolvePlaceholder(key, placeholderCtx)
                }
                referenced.addAll(neutralized.referencedKeys)
                neutralized.message
            }
        }

        // Recorded here, at the moment of resolution, rather than re-read when the caller renders
        // the runtime context. A second reading could land on the other side of a date boundary and
        // describe a different turn than the reference in the system prompt.
        session.recordValues(
            PromptReferencePolicy.resolveDynamicValues(referenced) { key ->
                resolvePlaceholder(key, placeholderCtx)
            }
        )

        // Relocated system-position injections are app-composed text as well, and one of them may
        // legitimately contain a registered placeholder. It is resolved to the concrete value here,
        // where the platform inputs are, rather than left for a later pass that will never see it:
        // this content no longer lives in a message, so nothing downstream would rewrite it.
        session.rewriteSectionContent { content ->
            PromptReferencePolicy.resolveAppControlledTemplate(content) { key ->
                resolvePlaceholder(key, placeholderCtx)
            }
        }

        return transformed
    }

    /** Applies the registered substitutions to one message, exactly as this transformer always has. */
    private fun UIMessage.substitutingPlaceholders(placeholderCtx: PlaceholderCtx): UIMessage =
        substitutingRegisteredPlaceholders { key -> resolvePlaceholder(key, placeholderCtx) }
}

/**
 * The substitution every message has always had, as a function of a resolver.
 *
 * Deliberately **not** a privilege check. A key this build does not register is left exactly as the
 * user wrote it — that is the behaviour this transformer has always had for user-authored text, and
 * the stable-system path must not change it: `{{foo}}` typed into a message is that user's text, and
 * refusing the turn over it would be the app reading meaning into someone else's words.
 */
internal fun UIMessage.substitutingRegisteredPlaceholders(
    valueOf: (String) -> String?,
): UIMessage = copy(
    parts = parts.map { part ->
        if (part !is UIMessagePart.Text) {
            part
        } else {
            part.copy(text = substituteRegisteredPlaceholders(part.text, valueOf))
        }
    }
)

internal fun substituteRegisteredPlaceholders(text: String, valueOf: (String) -> String?): String {
    var result = text
    for (key in DefaultPlaceholderProvider.placeholders.keys) {
        val value = valueOf(key) ?: continue
        result = result
            .replace(oldValue = "{{$key}}", newValue = value, ignoreCase = true)
            .replace(oldValue = "{$key}", newValue = value, ignoreCase = true)
    }
    return result
}

/** One system message after neutralisation, and what it turned out to refer to. */
internal data class NeutralizedSystemMessage(
    val message: UIMessage,
    /** Dynamic keys in first-appearance order — what the runtime context owes this turn. */
    val referencedKeys: List<String>,
)

/**
 * The stable-system rewrite of one system message, as a function of its text and a resolver.
 *
 * A top-level function rather than a private method so that the composition tests drive **this**
 * code rather than a re-implementation of it. The platform inputs arrive as [resolve] — the
 * production caller supplies the live registry, a JVM test supplies the same shape with fixed
 * values — which is the only thing that differs between the two, and it is the seam that makes the
 * transformer's Android dependencies irrelevant to what is being proved.
 *
 * Only ever applied to app-composed text. Ordinary user content is not passed through here, so a
 * user who types `{{foo}}` keeps it as plain text.
 */
internal fun neutralizeStableSystemMessage(
    message: UIMessage,
    resolve: (String) -> String?,
): NeutralizedSystemMessage {
    val referenced = LinkedHashSet<String>()
    val parts = message.parts.map { part ->
        if (part !is UIMessagePart.Text) {
            part
        } else {
            val neutralized = PromptReferencePolicy.neutralizeAppControlledTemplate(
                text = part.text,
                stableValueOf = resolve,
            )
            referenced.addAll(neutralized.referencedKeys)
            part.copy(text = neutralized.text)
        }
    }
    return NeutralizedSystemMessage(
        message = message.copy(parts = parts),
        referencedKeys = referenced.toList(),
    )
}

/**
 * One registered placeholder's value, or `null` when this build has no resolver for the key.
 *
 * Shared with the relocating transformers so a value placed in the runtime context is resolved by
 * exactly the same resolver that would have substituted it in place — two resolvers for one name is
 * how a prompt and its runtime context start disagreeing.
 */
internal fun resolvePlaceholder(key: String, ctx: PlaceholderCtx): String? =
    DefaultPlaceholderProvider.placeholders[key]?.resolver?.invoke(ctx)
