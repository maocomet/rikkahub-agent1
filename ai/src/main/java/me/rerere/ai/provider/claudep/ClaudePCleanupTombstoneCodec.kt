package me.rerere.ai.provider.claudep

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The wire record, its strict codec, and the verdict a read produces.
 *
 * ### Why this lives in `ai` rather than beside the file I/O
 *
 * The rule that matters most here is *"a record that cannot be interpreted is not the same as no
 * record at all."* Treating a malformed or unknown-version tombstone as "absent" would silently
 * discard the only pointer to a private key that may still exist — the device would look clean while
 * key material remained. That is a security rule, and a security rule that can only be verified by
 * reading it is a security rule that will eventually regress.
 *
 * So the encoding, the validation and the verdict are pure and live here, where the JVM tests run.
 * The Android store supplies bytes and calls [decode]; it makes no judgement of its own.
 *
 * No `Context`, DataStore, Keystore, Compose or file API appears in this file.
 */
object ClaudePCleanupTombstoneCodec {
    /** A tombstone is a version and an alias. Anything larger is not one. */
    const val MAX_RECORD_BYTES: Int = 512

    const val MAX_ALIAS_LENGTH: Int = 128

    /**
     * Alias shape.
     *
     * Deliberately narrow, because this value is handed to `KeyStore.deleteEntry`. An arbitrary
     * string must never become a deletion target.
     */
    private val ALIAS_PATTERN = Regex("^[A-Za-z0-9_.-]{1,$MAX_ALIAS_LENGTH}$")

    /**
     * Strict on purpose.
     *
     * `ignoreUnknownKeys = false` is the setting that matters: a record carrying extra fields is
     * rejected rather than partially accepted. A tombstone has exactly two fields, so anything else
     * in the document was not written by this code — and accepting it would mean a future field
     * could be added to the file format without anyone noticing the old build silently ignored it.
     */
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = false
    }

    /** Encodes a tombstone, or returns `null` when it is not encodable as one. */
    fun encode(tombstone: ClaudePCleanupTombstone): String? {
        if (!isPlausibleAlias(tombstone.deviceKeyAlias)) return null
        if (tombstone.version != ClaudePCleanupTombstone.CURRENT_VERSION) return null
        return json.encodeToString(
            StoredTombstone(version = tombstone.version, deviceKeyAlias = tombstone.deviceKeyAlias),
        )
    }

    /**
     * Reads a record.
     *
     * Every rejection is [ClaudePTombstoneRead.Unusable] — never [ClaudePTombstoneRead.Absent]. The
     * store decides that a *missing file* is absent; once bytes exist, they either parse into a
     * usable tombstone or they are an unreadable one, and the caller must treat those differently.
     */
    fun decode(raw: String): ClaudePTombstoneRead {
        if (raw.isEmpty()) return unusable(ClaudePTombstoneRejection.EMPTY)
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_RECORD_BYTES) {
            return unusable(ClaudePTombstoneRejection.TOO_LARGE)
        }

        val record = try {
            json.decodeFromString<StoredTombstone>(raw)
        } catch (_: Exception) {
            // Covers malformed JSON, a missing field, an extra field, a wrong type and trailing
            // data — kotlinx refuses trailing content after a complete document.
            return unusable(ClaudePTombstoneRejection.MALFORMED)
        }

        if (record.version != ClaudePCleanupTombstone.CURRENT_VERSION) {
            // Guessing at a future format could delete the wrong key, so an unknown version is
            // unusable rather than upgraded or ignored.
            return unusable(ClaudePTombstoneRejection.UNKNOWN_VERSION)
        }

        if (!isPlausibleAlias(record.deviceKeyAlias)) {
            return unusable(ClaudePTombstoneRejection.IMPLAUSIBLE_ALIAS)
        }

        return ClaudePTombstoneRead.Valid(
            ClaudePCleanupTombstone(
                version = record.version,
                deviceKeyAlias = record.deviceKeyAlias,
            ),
        )
    }

    /** True when [alias] is a shape this code is willing to hand to `KeyStore.deleteEntry`. */
    fun isPlausibleAlias(alias: String): Boolean = ALIAS_PATTERN.matches(alias)

    private fun unusable(reason: ClaudePTombstoneRejection) = ClaudePTombstoneRead.Unusable(reason)
}

/** What a tombstone read produced. */
sealed interface ClaudePTombstoneRead {
    /** No record exists. Nothing is pending. */
    data object Absent : ClaudePTombstoneRead

    /** A usable record. */
    data class Valid(val tombstone: ClaudePCleanupTombstone) : ClaudePTombstoneRead

    /**
     * A record exists but cannot be used.
     *
     * Callers must keep the device non-dispatchable and must **not** clear the record: it is the only
     * evidence that something may be left behind.
     */
    data class Unusable(val reason: ClaudePTombstoneRejection) : ClaudePTombstoneRead
}

/** Why a tombstone could not be used. Stable enum — never file content. */
enum class ClaudePTombstoneRejection {
    EMPTY,
    TOO_LARGE,
    MALFORMED,
    UNKNOWN_VERSION,
    IMPLAUSIBLE_ALIAS,
}

/**
 * On-disk shape, with no default on either field.
 *
 * A default would make an omitted `version` decode as the current one and an omitted alias decode as
 * `""` — the same fail-open shape CP1-A's review closed on the protocol envelope.
 */
@Serializable
internal data class StoredTombstone(
    @SerialName("version") val version: Int,
    @SerialName("device_key_alias") val deviceKeyAlias: String,
)
