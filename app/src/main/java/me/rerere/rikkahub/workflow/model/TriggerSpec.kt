package me.rerere.rikkahub.workflow.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Phase 12 — typed trigger spec. The LLM authors workflows by emitting JSON whose `trigger`
 * object carries `type` + trigger-specific `params`. kotlinx.serialization's polymorphic
 * sealed-class serializer maps `type` to the right variant; unknown `type` strings fail
 * validation with a clear error.
 *
 * 19 variants per the locked spec. Variants that need no params are `data object`s; those
 * with params are `data class`es with their own fields. The JSON shape on the wire is
 * `{"type": "wifi_connected", "ssid": "HomeWiFi"}` (flat) — see WorkflowJson for how this is
 * parsed using a discriminator + wrapper, since we want to keep the user-facing schema as
 * `{"type":"...","params":{...}}` for LLM ergonomics.
 */
@Serializable
sealed class TriggerSpec {
    /** Time-of-day or cron schedule. Reuses the scheduled-jobs WorkManager backend. */
    @Serializable
    @SerialName("time_cron")
    data class TimeCron(
        /** 5-field cron OR @every Ns — same dialect ScheduledJobs accepts. Optional if timeOfDay set. */
        val cron: String? = null,
        /** "HH:mm" (24h, device local). Mutually exclusive with cron. */
        val timeOfDay: String? = null,
        /** ISO 1..7 (1=Mon). Empty = every day. Only used with timeOfDay. */
        val daysOfWeek: List<Int> = emptyList(),
        /** Optional IANA timezone id; null = device local. */
        val timezone: String? = null,
    ) : TriggerSpec()

    @Serializable
    @SerialName("wifi_connected")
    data class WifiConnected(val ssid: String? = null) : TriggerSpec()

    @Serializable
    @SerialName("wifi_disconnected")
    data class WifiDisconnected(val ssid: String? = null) : TriggerSpec()

    @Serializable
    @SerialName("bluetooth_device_connected")
    data class BluetoothDeviceConnected(val deviceAddress: String? = null) : TriggerSpec()

    @Serializable
    @SerialName("bluetooth_device_disconnected")
    data class BluetoothDeviceDisconnected(val deviceAddress: String? = null) : TriggerSpec()

    @Serializable @SerialName("headphones_plugged") data object HeadphonesPlugged : TriggerSpec()

    @Serializable @SerialName("headphones_unplugged") data object HeadphonesUnplugged : TriggerSpec()

    @Serializable @SerialName("power_connected") data object PowerConnected : TriggerSpec()

    @Serializable @SerialName("power_disconnected") data object PowerDisconnected : TriggerSpec()

    /** Fires on transition: previous level was ≥threshold, current level is <threshold. */
    @Serializable
    @SerialName("battery_below")
    data class BatteryBelow(val thresholdPercent: Int) : TriggerSpec()

    /** Fires on transition: previous level was <threshold, current level is ≥threshold. */
    @Serializable
    @SerialName("battery_above")
    data class BatteryAbove(val thresholdPercent: Int) : TriggerSpec()

    @Serializable
    @SerialName("geofence_enter")
    data class GeofenceEnter(
        val lat: Double,
        val lng: Double,
        val radiusM: Int,
        val label: String? = null,
    ) : TriggerSpec()

    @Serializable
    @SerialName("geofence_exit")
    data class GeofenceExit(
        val lat: Double,
        val lng: Double,
        val radiusM: Int,
        val label: String? = null,
    ) : TriggerSpec()

    @Serializable
    @SerialName("app_launched")
    data class AppLaunched(val packageName: String) : TriggerSpec()

    @Serializable
    @SerialName("app_closed")
    data class AppClosed(val packageName: String) : TriggerSpec()

    /**
     * `*_contains` fields match as case-insensitive plain substrings; `*_matches` fields
     * hold a Java regex ([java.util.regex.Pattern]) tested with `find()` against the
     * notification title/text. All non-null filters are AND-combined — if both
     * `title_contains` and `title_matches` are set, the title must satisfy both. An
     * uncompilable regex fails safe (treated as "no match", never crashes evaluation);
     * workflow_create rejects bad patterns up front so the LLM can repair them.
     */
    @Serializable
    @SerialName("notification_received")
    data class NotificationReceived(
        val packageName: String? = null,
        val titleContains: String? = null,
        val textContains: String? = null,
        val titleMatches: String? = null,
        val textMatches: String? = null,
    ) : TriggerSpec()

    /**
     * Fires when a Cat Garden notification is created for this workflow's authoring assistant.
     *
     * Only user-originated notifications reach a workflow. A notification produced by an
     * assistant's own run carries `origin_depth >= 1` and is dropped by the trigger family, which
     * is what bounds assistant-to-assistant recursion (A comments B, B is woken, B comments A,
     * A is woken, ...). Each notification is additionally claimed exactly once, so a redelivered
     * event cannot fire a workflow twice.
     *
     * The trigger carries no payload a model could author. The notification id exists only in
     * runtime-owned context; a woken run reads the content back through `space_list_notifications`
     * / `space_get_post`. There is deliberately no template interpolation and nothing is written
     * into an action's arguments.
     */
    @Serializable
    @SerialName("space_notification_created")
    data class SpaceNotificationCreated(
        /**
         * Optional filter: `LIKE` or `COMMENT`. Null means both.
         *
         * The wire field is `params.notice_type` — deliberately NOT `params.type`. The trigger
         * object already spends `type` on the polymorphic class discriminator, and kotlinx
         * filters the discriminator only out of the unknown-key check, never out of element
         * decoding. A property named `type` therefore collides with it: when the discriminator
         * survives into the flattened object the property silently receives the serial name
         * (`"space_notification_created"`), and when `params.type` is supplied it overwrites the
         * discriminator and the variant cannot be resolved at all. Neither shape is authorable.
         */
        @SerialName("notice_type") val noticeType: String? = null,
    ) : TriggerSpec()

    @Serializable @SerialName("boot_completed") data object BootCompleted : TriggerSpec()

    @Serializable @SerialName("screen_on") data object ScreenOn : TriggerSpec()

    @Serializable @SerialName("screen_off") data object ScreenOff : TriggerSpec()

    /** Only via workflow_run() or the Settings "Run now" button. */
    @Serializable @SerialName("manual") data object Manual : TriggerSpec()
}
