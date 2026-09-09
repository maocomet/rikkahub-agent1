package me.rerere.rikkahub.owner

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.automation.AutomationControlFacade
import me.rerere.rikkahub.automation.ScheduledJobTriggerResult
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.execution.ToolRuntimeInvocation
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
import me.rerere.rikkahub.data.capability.CapabilitySubject
import me.rerere.rikkahub.data.capability.SubjectType
import me.rerere.rikkahub.data.ai.tools.local.ScheduleJobValidator
import me.rerere.rikkahub.data.db.entity.AlarmEntity
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.privilege.PrivilegedSessionContext
import me.rerere.rikkahub.workflow.execution.WorkflowActionRunner
import me.rerere.rikkahub.workflow.model.WorkflowAuthoringAuthority
import me.rerere.rikkahub.workflow.model.WorkflowCapabilitySnapshot
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import me.rerere.rikkahub.workflow.model.WorkflowJson
import me.rerere.rikkahub.workflow.model.WorkflowRunStatus
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.uuid.Uuid

/** Owner CRUD plus an interactive run path over the exact current-turn tool surface. */
class OwnerWorkflowOperationHandler(
    private val repository: WorkflowRepository,
    private val actionRunner: WorkflowActionRunner,
    private val automation: AutomationControlFacade,
    private val conversations: ConversationRepository,
    private val settings: SettingsStore,
) : OwnerOperationHandler {
    override fun supports(request: OwnerOperationRequest, action: OwnerAction): Boolean =
        request.family == OwnerToolFamily.WORKFLOW && action.type in FIELDS

    override suspend fun validate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerActionValidation {
        val allowed = FIELDS[action.type]
            ?: return invalid("OWNER_ACTION_UNSUPPORTED", "Unsupported Workflow action.")
        if ((action.arguments.keys - allowed).isNotEmpty()) {
            return invalid("OWNER_UNSUPPORTED_FIELD", "Workflow action contains an unsupported field.")
        }
        if (action.type in setOf("workflow_create", "workflow_update")) {
            val parsed = parseDefinition(request, action)
            if (parsed is ParsedDefinition.Error) return invalid(parsed.code, parsed.message)
            val definition = (parsed as ParsedDefinition.Value).definition
            val recursive = definition.actions.firstOrNull {
                it.tool.startsWith("owner_") || it.tool == "workflow_run"
            }
            if (recursive != null) {
                return invalid("WORKFLOW_RECURSION_BLOCKED", "Owner and workflow_run tools cannot be nested inside a Workflow.")
            }
            val existing = repository.getById(definition.id)
            if (action.type == "workflow_create" && existing != null) {
                return invalid("WORKFLOW_ALREADY_EXISTS", "Workflow ID already exists; use workflow_update.")
            }
            if (action.type == "workflow_update" && existing == null) {
                return invalid("WORKFLOW_NOT_FOUND", "Workflow to update does not exist.")
            }
        }
        if (action.type in setOf("workflow_delete", "workflow_set_enabled", "workflow_run")) {
            val id = action.arguments.string("workflow_id")?.trim().orEmpty()
            if (id.isBlank() || repository.getById(id) == null) {
                return invalid("WORKFLOW_NOT_FOUND", "Workflow does not exist.")
            }
        }
        if (action.type == "workflow_run" && request.availableTools.isEmpty()) {
            return invalid("OWNER_TOOL_SURFACE_UNAVAILABLE", "The current interactive tool surface is unavailable.")
        }
        if (action.type in setOf("schedule_create", "schedule_update")) {
            val existing = action.arguments.string("job_id")?.let { automation.getScheduledJob(it) }
            if (action.type == "schedule_update" && existing == null) {
                return invalid("SCHEDULE_NOT_FOUND", "Scheduled job does not exist.")
            }
            when (val parsed = parseScheduledJob(request, action, existing)) {
                is ParsedSchedule.Error -> return invalid(parsed.code, parsed.message)
                is ParsedSchedule.Value -> Unit
            }
        }
        if (action.type in setOf("schedule_set_enabled", "schedule_run_now", "schedule_delete")) {
            val id = action.arguments.string("job_id")?.trim().orEmpty()
            if (id.isBlank() || automation.getScheduledJob(id) == null) {
                return invalid("SCHEDULE_NOT_FOUND", "Scheduled job does not exist.")
            }
        }
        if (action.type == "schedule_set_enabled" && action.arguments.boolean("enabled") == null) {
            return invalid("SCHEDULE_ENABLED_REQUIRED", "enabled must be true or false.")
        }
        if (action.type in setOf("alarm_create", "alarm_update")) {
            val existing = action.arguments.string("alarm_id")?.let { automation.getAlarm(it) }
            if (action.type == "alarm_update" && existing == null) {
                return invalid("ALARM_NOT_FOUND", "Alarm does not exist.")
            }
            when (val parsed = parseAlarm(action, existing)) {
                is ParsedAlarm.Error -> return invalid(parsed.code, parsed.message)
                is ParsedAlarm.Value -> Unit
            }
        }
        if (action.type in setOf("alarm_set_enabled", "alarm_delete")) {
            val id = action.arguments.string("alarm_id")?.trim().orEmpty()
            if (id.isBlank() || automation.getAlarm(id) == null) {
                return invalid("ALARM_NOT_FOUND", "Alarm does not exist.")
            }
        }
        if (action.type == "alarm_set_enabled" && action.arguments.boolean("enabled") == null) {
            return invalid("ALARM_ENABLED_REQUIRED", "enabled must be true or false.")
        }
        return OwnerActionValidation(true, "WORKFLOW_ACTION_VALID", "Workflow action validated.")
    }

    override suspend fun apply(
        index: Int,
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerAppliedAction = runCatching {
        when (action.type) {
            "workflow_list" -> list(index)
            "workflow_create", "workflow_update" -> upsert(index, request, action)
            "workflow_delete" -> delete(index, action)
            "workflow_set_enabled" -> setEnabled(index, action)
            "workflow_run" -> runInteractive(index, request, action, context)
            "schedule_list" -> scheduleList(index, action)
            "schedule_create", "schedule_update" -> scheduleUpsert(index, request, action)
            "schedule_set_enabled" -> scheduleSetEnabled(index, action)
            "schedule_run_now" -> scheduleRunNow(index, action)
            "schedule_delete" -> scheduleDelete(index, action)
            "alarm_list" -> alarmList(index, action)
            "alarm_create", "alarm_update" -> alarmUpsert(index, action)
            "alarm_set_enabled" -> alarmSetEnabled(index, action)
            "alarm_delete" -> alarmDelete(index, action)
            else -> failure(index, action.type, "OWNER_ACTION_UNSUPPORTED", "Unsupported Workflow action.")
        }
    }.getOrElse {
        failure(index, action.type, "WORKFLOW_OPERATION_FAILED", "Workflow operation failed inside the host runtime.")
    }

    override suspend fun verify(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ): OwnerActionValidation {
        if (!applied.result.ok) return invalid(applied.result.code, applied.result.message)
        val workflowId = action.arguments.string("workflow_id")
            ?: applied.result.data?.get("workflow_id")?.jsonPrimitive?.contentOrNull
        return when (action.type) {
            "workflow_create", "workflow_update", "workflow_set_enabled" -> if (!workflowId.isNullOrBlank() && repository.getById(workflowId) != null) {
                OwnerActionValidation(true, "WORKFLOW_ACTION_VERIFIED", "Workflow state verified.")
            } else invalid("WORKFLOW_VERIFY_FAILED", "Workflow state could not be confirmed.")
            "workflow_delete" -> if (!workflowId.isNullOrBlank() && repository.getById(workflowId) == null) {
                OwnerActionValidation(true, "WORKFLOW_DELETE_VERIFIED", "Workflow deletion verified.")
            } else invalid("WORKFLOW_VERIFY_FAILED", "Workflow deletion could not be confirmed.")
            "schedule_create", "schedule_update", "schedule_set_enabled" -> {
                val id = action.arguments.string("job_id") ?: applied.result.data?.get("job_id")?.jsonPrimitive?.contentOrNull
                if (!id.isNullOrBlank() && automation.getScheduledJob(id) != null) {
                    OwnerActionValidation(true, "SCHEDULE_ACTION_VERIFIED", "Scheduled job state verified.")
                } else invalid("SCHEDULE_VERIFY_FAILED", "Scheduled job state could not be confirmed.")
            }
            "schedule_delete" -> {
                val id = action.arguments.string("job_id")
                if (!id.isNullOrBlank() && automation.getScheduledJob(id) == null) {
                    OwnerActionValidation(true, "SCHEDULE_DELETE_VERIFIED", "Scheduled job deletion verified.")
                } else invalid("SCHEDULE_VERIFY_FAILED", "Scheduled job deletion could not be confirmed.")
            }
            "alarm_create", "alarm_update", "alarm_set_enabled" -> {
                val id = action.arguments.string("alarm_id") ?: applied.result.data?.get("alarm_id")?.jsonPrimitive?.contentOrNull
                if (!id.isNullOrBlank() && automation.getAlarm(id) != null) {
                    OwnerActionValidation(true, "ALARM_ACTION_VERIFIED", "Alarm state verified.")
                } else invalid("ALARM_VERIFY_FAILED", "Alarm state could not be confirmed.")
            }
            "alarm_delete" -> {
                val id = action.arguments.string("alarm_id")
                if (!id.isNullOrBlank() && automation.getAlarm(id) == null) {
                    OwnerActionValidation(true, "ALARM_DELETE_VERIFIED", "Alarm deletion verified.")
                } else invalid("ALARM_VERIFY_FAILED", "Alarm deletion could not be confirmed.")
            }
            else -> OwnerActionValidation(true, "WORKFLOW_ACTION_VERIFIED", "Workflow action completed.")
        }
    }

    override suspend fun compensate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ): OwnerCompensationResult {
        return runCatching {
            when (val receipt = applied.compensationReceipt) {
                is WorkflowReceipt -> if (receipt.previous == null) {
                    repository.deleteCascading(receipt.workflowId)
                } else {
                    repository.upsert(receipt.previous)
                    repository.setEnabled(receipt.workflowId, receipt.previousEnabled)
                }
                is ScheduledJobReceipt -> if (receipt.previous == null) {
                    automation.deleteScheduledJob(receipt.jobId)
                } else {
                    automation.saveScheduledJob(receipt.previous)
                }
                is AlarmReceipt -> if (receipt.previous == null) {
                    automation.deleteAlarm(receipt.alarmId)
                } else {
                    automation.saveAlarm(receipt.previous)
                }
                else -> return OwnerCompensationResult(true, "WORKFLOW_NO_COMPENSATION_REQUIRED")
            }
            OwnerCompensationResult(true, "AUTOMATION_STATE_RESTORED")
        }.getOrElse { OwnerCompensationResult(false, "WORKFLOW_COMPENSATION_FAILED") }
    }

    private suspend fun list(index: Int): OwnerAppliedAction = success(
        index, "workflow_list", "WORKFLOW_LIST", "Workflow metadata returned.", buildJsonObject {
            put("workflows", buildJsonArray {
                repository.listAll().forEach { loaded ->
                    add(buildJsonObject {
                        put("workflow_id", loaded.entity.id)
                        put("name", loaded.entity.name.take(80))
                        put("enabled", loaded.entity.enabled)
                        put("trigger", loaded.definition.trigger::class.simpleName.orEmpty())
                        put("action_count", loaded.definition.actions.size)
                        put("frozen_capability_count", loaded.definition.capabilitySnapshot.size)
                        put("last_status", loaded.entity.lastRunStatus ?: "NEVER")
                    })
                }
            })
        },
    )

    private suspend fun upsert(index: Int, request: OwnerOperationRequest, action: OwnerAction): OwnerAppliedAction {
        val parsed = parseDefinition(request, action) as? ParsedDefinition.Value
            ?: return failure(index, action.type, "WORKFLOW_INVALID", "Workflow definition is invalid.")
        val old = repository.getById(parsed.definition.id)
        val definition = parsed.definition.copy(
            authoringAssistantId = old?.definition?.authoringAssistantId ?: request.assistantId,
            // Server-stamped, never caller JSON (the authoring parser already nulls it out). Owner
            // authoring is a remote/management surface, NOT a confirmed local Second-User session,
            // so the marker is pinned to LOCAL: we never declare SECOND_USER_CONFIRMED from "target
            // assistant is currently ACTIVE". Validation/fingerprint surface and marker are kept
            // consistent by refusing to claim the Second-User allowlist surface here; an Owner
            // workflow that references a tool outside the target assistant's ordinary localTools
            // therefore fails closed at run (workflow_tool_unavailable) instead of asserting an
            // authority the authoring surface did not actually confer. If Owner authoring is later
            // taught to validate/fingerprint against the assistant's real effective surface, the
            // marker must be decided together with that surface — never from availableTools alone.
            authoringAuthority = WorkflowAuthoringAuthority.LOCAL,
            capabilitySnapshot = WorkflowCapabilitySnapshot.capture(parsed.definition.actions),
            createdAtMs = old?.definition?.createdAtMs ?: parsed.definition.createdAtMs,
            updatedAtMs = System.currentTimeMillis(),
        )
        val receipt = WorkflowReceipt(definition.id, old?.definition, old?.entity?.enabled ?: false)
        repository.upsert(definition)
        repository.setEnabled(definition.id, definition.enabled)
        return success(index, action.type, if (old == null) "WORKFLOW_CREATED" else "WORKFLOW_UPDATED", if (old == null) "Workflow created with a frozen capability snapshot." else "Workflow updated with a new frozen capability snapshot.", buildJsonObject {
            put("workflow_id", definition.id)
            put("frozen_capability_count", definition.capabilitySnapshot.size)
        }, receipt)
    }

    private suspend fun delete(index: Int, action: OwnerAction): OwnerAppliedAction {
        val id = action.arguments.string("workflow_id")!!.trim()
        val old = repository.getById(id)!!
        val receipt = WorkflowReceipt(id, old.definition, old.entity.enabled)
        if (!repository.deleteCascading(id)) return failure(index, action.type, "WORKFLOW_DELETE_FAILED", "Workflow could not be deleted.")
        return success(index, action.type, "WORKFLOW_DELETED", "Workflow and run history deleted.", buildJsonObject {
            put("workflow_id", id)
        }, receipt)
    }

    private suspend fun setEnabled(index: Int, action: OwnerAction): OwnerAppliedAction {
        val id = action.arguments.string("workflow_id")!!.trim()
        val enabled = action.arguments.boolean("enabled")
            ?: return failure(index, action.type, "WORKFLOW_ENABLED_REQUIRED", "enabled must be true or false.")
        val old = repository.getById(id)!!
        val receipt = WorkflowReceipt(id, old.definition, old.entity.enabled)
        if (!repository.setEnabled(id, enabled)) {
            return failure(
                index,
                action.type,
                if (old.entity.origin == me.rerere.rikkahub.workflow.model.WorkflowOrigin.LEARNED.name && enabled) {
                    "LEARNED_WORKFLOW_ENABLE_REQUIRES_REVIEW"
                } else {
                    "WORKFLOW_ENABLE_CONFLICT"
                },
                if (old.entity.origin == me.rerere.rikkahub.workflow.model.WorkflowOrigin.LEARNED.name && enabled) {
                    "Learned workflows can only be enabled from the two-step Workflow review flow."
                } else {
                    "Workflow enabled state changed concurrently. Reload and retry."
                },
            )
        }
        return success(index, action.type, "WORKFLOW_ENABLED_UPDATED", "Workflow enabled state updated.", buildJsonObject {
            put("workflow_id", id)
            put("enabled", enabled)
        }, receipt)
    }

    private suspend fun runInteractive(
        index: Int,
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerAppliedAction {
        val id = action.arguments.string("workflow_id")!!.trim()
        val loaded = repository.getById(id)!!
        val executionContext = ToolExecutionContext(
            runId = Uuid.random(),
            conversationId = context.conversationId,
            assistantId = context.assistantId.toString(),
            callOrigin = context.origin,
            capabilitySubject = CapabilitySubject(
                id = request.authoritySubjectId,
                type = SubjectType.LOCAL_SECOND_USER,
                privilegedConversationId = context.conversationId.toString(),
            ),
            selectedPrivilegedConversation = true,
        )
        val allowedTools = request.availableTools.filterNot { tool ->
            tool.name.startsWith("owner_") || tool.name == "workflow_run"
        }
        val result = actionRunner.run(
            actions = loaded.definition.actions,
            availableTools = allowedTools,
            invocation = ToolRuntimeInvocation(
                executionContext = executionContext,
                unrestrictedOverride = context.unrestrictedOverride,
            ),
        )
        val status = if (result.success) WorkflowRunStatus.SUCCESS else WorkflowRunStatus.FAILED
        repository.recordFire(
            workflowId = id,
            firedAtMs = System.currentTimeMillis(),
            status = status,
            durationMs = 0,
            // Tool arguments, paths, commands and remote error bodies are not workflow audit
            // material. Keep only a stable outcome code in the durable run row.
            errorMessage = if (result.success) null else "OWNER_INTERACTIVE_WORKFLOW_FAILED",
        )
        return if (result.success) success(index, action.type, "WORKFLOW_RUN_SUCCEEDED", "Workflow ran with the current Owner authority and tool surface.", buildJsonObject {
            put("workflow_id", id)
            put("status", status.name)
        }) else failure(index, action.type, "WORKFLOW_RUN_FAILED", "Workflow action failed; inspect the underlying execution ledger for redacted facts.")
    }

    private suspend fun scheduleList(index: Int, action: OwnerAction): OwnerAppliedAction = success(
        index, action.type, "SCHEDULE_LISTED", "Scheduled job metadata returned.", buildJsonObject {
            put("jobs", buildJsonArray {
                automation.listScheduledJobs().take(100).forEach { job -> add(buildJsonObject {
                    put("job_id", job.id)
                    put("name", job.name.take(80))
                    put("assistant_id", job.assistantId)
                    put("mode", job.mode)
                    put("schedule_type", job.scheduleType)
                    put("enabled", job.enabled)
                    job.nextRunAtMs?.let { put("next_run_at_ms", it) }
                    put("runs_so_far", job.runsSoFar)
                }) }
            })
        },
    )

    private suspend fun scheduleUpsert(
        index: Int,
        request: OwnerOperationRequest,
        action: OwnerAction,
    ): OwnerAppliedAction {
        val previous = action.arguments.string("job_id")?.let { automation.getScheduledJob(it) }
        val parsed = parseScheduledJob(request, action, previous) as? ParsedSchedule.Value
            ?: return failure(index, action.type, "SCHEDULE_DEFINITION_INVALID", "Scheduled job definition is invalid.")
        val saved = automation.saveScheduledJob(parsed.job)
        return success(
            index,
            action.type,
            if (previous == null) "SCHEDULE_CREATED" else "SCHEDULE_UPDATED",
            if (previous == null) "Scheduled job created." else "Scheduled job updated.",
            buildJsonObject {
                put("job_id", saved.id)
                put("enabled", saved.enabled)
                saved.nextRunAtMs?.let { put("next_run_at_ms", it) }
            },
            ScheduledJobReceipt(saved.id, previous),
        )
    }

    private suspend fun scheduleSetEnabled(index: Int, action: OwnerAction): OwnerAppliedAction {
        val id = action.arguments.string("job_id")!!.trim()
        val previous = automation.getScheduledJob(id)!!
        val enabled = action.arguments.boolean("enabled")!!
        val saved = automation.setScheduledJobEnabled(id, enabled)
            ?: return failure(index, action.type, "SCHEDULE_NOT_FOUND", "Scheduled job does not exist.")
        return success(index, action.type, "SCHEDULE_ENABLED_UPDATED", "Scheduled job enabled state updated.", buildJsonObject {
            put("job_id", id); put("enabled", saved.enabled)
            saved.nextRunAtMs?.let { put("next_run_at_ms", it) }
        }, ScheduledJobReceipt(id, previous))
    }

    private suspend fun scheduleRunNow(index: Int, action: OwnerAction): OwnerAppliedAction {
        val id = action.arguments.string("job_id")!!.trim()
        return when (automation.triggerScheduledJob(id)) {
            ScheduledJobTriggerResult.ENQUEUED -> success(index, action.type, "SCHEDULE_RUN_ENQUEUED", "Scheduled job was queued through its normal worker.", buildJsonObject { put("job_id", id) })
            ScheduledJobTriggerResult.DISABLED -> failure(index, action.type, "SCHEDULE_DISABLED", "Enable the scheduled job before running it.")
            ScheduledJobTriggerResult.NOT_FOUND -> failure(index, action.type, "SCHEDULE_NOT_FOUND", "Scheduled job does not exist.")
        }
    }

    private suspend fun scheduleDelete(index: Int, action: OwnerAction): OwnerAppliedAction {
        val id = action.arguments.string("job_id")!!.trim()
        return if (automation.deleteScheduledJob(id)) {
            success(index, action.type, "SCHEDULE_DELETED", "Scheduled job and its run history were deleted.", buildJsonObject { put("job_id", id) })
        } else failure(index, action.type, "SCHEDULE_DELETE_FAILED", "Scheduled job could not be deleted.")
    }

    private suspend fun alarmList(index: Int, action: OwnerAction): OwnerAppliedAction = success(
        index, action.type, "ALARM_LISTED", "Alarm metadata returned.", buildJsonObject {
            put("exact_alarm_ready", automation.canScheduleExactAlarms())
            put("alarms", buildJsonArray {
                automation.listAlarms().take(100).forEach { alarm -> add(buildJsonObject {
                    put("alarm_id", alarm.id)
                    put("label", alarm.label.take(160))
                    put("schedule_type", alarm.scheduleType)
                    put("timezone", alarm.timezone)
                    put("enabled", alarm.enabled)
                    alarm.nextFireAtMs?.let { put("next_fire_at_ms", it) }
                }) }
            })
        },
    )

    private suspend fun alarmUpsert(index: Int, action: OwnerAction): OwnerAppliedAction {
        val previous = action.arguments.string("alarm_id")?.let { automation.getAlarm(it) }
        val parsed = parseAlarm(action, previous) as? ParsedAlarm.Value
            ?: return failure(index, action.type, "ALARM_DEFINITION_INVALID", "Alarm definition is invalid.")
        if (parsed.alarm.enabled && !automation.canScheduleExactAlarms()) {
            automation.openExactAlarmSettings()
            return failure(index, action.type, "NEEDS_USER_ACTION", "Android exact-alarm permission is required; the system settings page was opened.")
        }
        val saved = automation.saveAlarm(parsed.alarm)
        return success(
            index,
            action.type,
            if (previous == null) "ALARM_CREATED" else "ALARM_UPDATED",
            if (previous == null) "Alarm created." else "Alarm updated.",
            buildJsonObject {
                put("alarm_id", saved.id)
                put("enabled", saved.enabled)
                saved.nextFireAtMs?.let { put("next_fire_at_ms", it) }
            },
            AlarmReceipt(saved.id, previous),
        )
    }

    private suspend fun alarmSetEnabled(index: Int, action: OwnerAction): OwnerAppliedAction {
        val id = action.arguments.string("alarm_id")!!.trim()
        val previous = automation.getAlarm(id)!!
        val enabled = action.arguments.boolean("enabled")!!
        if (enabled && !automation.canScheduleExactAlarms()) {
            automation.openExactAlarmSettings()
            return failure(index, action.type, "NEEDS_USER_ACTION", "Android exact-alarm permission is required; the system settings page was opened.")
        }
        val saved = automation.setAlarmEnabled(id, enabled)
            ?: return failure(index, action.type, "ALARM_NOT_FOUND", "Alarm does not exist.")
        return success(index, action.type, "ALARM_ENABLED_UPDATED", "Alarm enabled state updated.", buildJsonObject {
            put("alarm_id", id); put("enabled", saved.enabled)
            saved.nextFireAtMs?.let { put("next_fire_at_ms", it) }
        }, AlarmReceipt(id, previous))
    }

    private suspend fun alarmDelete(index: Int, action: OwnerAction): OwnerAppliedAction {
        val id = action.arguments.string("alarm_id")!!.trim()
        return if (automation.deleteAlarm(id)) {
            success(index, action.type, "ALARM_DELETED", "Alarm deleted.", buildJsonObject { put("alarm_id", id) })
        } else failure(index, action.type, "ALARM_DELETE_FAILED", "Alarm could not be deleted.")
    }

    private sealed interface ParsedSchedule {
        data class Value(val job: ScheduledJobEntity) : ParsedSchedule
        data class Error(val code: String, val message: String) : ParsedSchedule
    }

    private suspend fun parseScheduledJob(
        request: OwnerOperationRequest,
        action: OwnerAction,
        previous: ScheduledJobEntity?,
    ): ParsedSchedule {
        val definition = action.arguments["definition"] as? JsonObject
            ?: return ParsedSchedule.Error("SCHEDULE_DEFINITION_REQUIRED", "definition must be an object.")
        val unknown = definition.keys - SCHEDULE_DEFINITION_FIELDS
        if (unknown.isNotEmpty()) {
            return ParsedSchedule.Error("SCHEDULE_DEFINITION_FIELD_INVALID", "Unsupported schedule fields: ${unknown.sorted().joinToString()}.")
        }
        val knownTools = request.availableToolNames.filterNot {
            it.startsWith("owner_") || it in AUTOMATION_RECURSIVE_TOOLS
        }
        ScheduleJobValidator.validate(definition, knownTools)?.let { error ->
            return ParsedSchedule.Error(error.code.uppercase(), error.detail)
        }
        val explicitAssistantId = definition.string("assistant_id")?.takeIf { it.isNotBlank() }
        if (explicitAssistantId != null) {
            val parsed = runCatching { Uuid.parse(explicitAssistantId) }.getOrNull()
                ?: return ParsedSchedule.Error("SCHEDULE_ASSISTANT_INVALID", "assistant_id must be a UUID.")
            if (settings.settingsFlow.value.assistants.none { it.id == parsed }) {
                return ParsedSchedule.Error("SCHEDULE_ASSISTANT_NOT_FOUND", "assistant_id does not identify an existing assistant.")
            }
        }
        val targetInput = definition.string("target_conversation_id")?.takeIf { it.isNotBlank() }
        val targetId = if (targetInput == "current") request.conversationId else targetInput
        var assistantId = explicitAssistantId ?: previous?.assistantId ?: request.assistantId
        if (targetId != null) {
            val targetUuid = runCatching { Uuid.parse(targetId) }.getOrNull()
                ?: return ParsedSchedule.Error("SCHEDULE_CONVERSATION_INVALID", "target_conversation_id must be a UUID or current.")
            val conversation = conversations.getConversationById(targetUuid)
                ?: return ParsedSchedule.Error("SCHEDULE_CONVERSATION_NOT_FOUND", "Target conversation does not exist.")
            if (explicitAssistantId != null && explicitAssistantId != conversation.assistantId.toString()) {
                return ParsedSchedule.Error("SCHEDULE_ASSISTANT_MISMATCH", "Target conversation belongs to a different assistant.")
            }
            assistantId = conversation.assistantId.toString()
        }
        val now = System.currentTimeMillis()
        val tags = (definition["tags"] as? JsonArray)?.mapNotNull { element ->
            (element as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        }?.joinToString(",")
        return ParsedSchedule.Value(ScheduledJobEntity(
            id = previous?.id ?: Uuid.random().toString(),
            name = definition.string("name")!!,
            prompt = definition.string("prompt"),
            assistantId = assistantId,
            scheduleType = definition.string("schedule_type")!!,
            atUnixMs = definition.long("at_unix_ms"),
            enabled = definition.boolean("enabled") ?: previous?.enabled ?: true,
            createdAtMs = previous?.createdAtMs ?: now,
            lastRunAtMs = previous?.lastRunAtMs,
            nextRunAtMs = previous?.nextRunAtMs,
            mode = definition.string("mode")!!,
            actionsJson = (definition["actions"] as? JsonArray)?.toString(),
            cronExpression = definition.string("cron_expression"),
            timezone = definition.string("timezone"),
            startAtUnixMs = definition.long("start_at_unix_ms"),
            endAtUnixMs = definition.long("end_at_unix_ms"),
            maxRuns = definition.int("max_runs"),
            runsSoFar = previous?.runsSoFar ?: 0,
            catchup = definition.string("catchup") ?: "fire_once",
            description = definition.string("description")?.take(500),
            tags = tags,
            targetConversationId = targetId,
        ))
    }

    private sealed interface ParsedAlarm {
        data class Value(val alarm: AlarmEntity) : ParsedAlarm
        data class Error(val code: String, val message: String) : ParsedAlarm
    }

    private fun parseAlarm(action: OwnerAction, previous: AlarmEntity?): ParsedAlarm {
        val definition = action.arguments["definition"] as? JsonObject
            ?: return ParsedAlarm.Error("ALARM_DEFINITION_REQUIRED", "definition must be an object.")
        val unknown = definition.keys - ALARM_DEFINITION_FIELDS
        if (unknown.isNotEmpty()) {
            return ParsedAlarm.Error("ALARM_DEFINITION_FIELD_INVALID", "Unsupported alarm fields: ${unknown.sorted().joinToString()}.")
        }
        val label = definition.string("label") ?: previous?.label
            ?: return ParsedAlarm.Error("ALARM_LABEL_REQUIRED", "label is required.")
        if (label.isBlank() || label.length > 160) return ParsedAlarm.Error("ALARM_LABEL_INVALID", "label must contain 1 to 160 characters.")
        val note = if (definition.containsKey("note")) definition.string("note") else previous?.note
        if (note != null && note.length > 2_000) return ParsedAlarm.Error("ALARM_NOTE_LIMIT", "note exceeds 2000 characters.")
        val scheduleType = definition.string("schedule_type") ?: previous?.scheduleType
            ?: return ParsedAlarm.Error("ALARM_SCHEDULE_TYPE_REQUIRED", "schedule_type is required.")
        val timezone = definition.string("timezone") ?: previous?.timezone ?: ZoneId.systemDefault().id
        if (runCatching { ZoneId.of(timezone) }.isFailure) return ParsedAlarm.Error("ALARM_TIMEZONE_INVALID", "timezone must be a valid IANA zone.")
        val enabled = definition.boolean("enabled") ?: previous?.enabled ?: true
        val time = definition.string("time") ?: previous?.time
        val hour = definition.int("hour") ?: previous?.hour
        val minute = definition.int("minute") ?: previous?.minute
        val days = (definition["days_of_week"] as? JsonArray)?.mapNotNull { element ->
            (element as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toIntOrNull()
        } ?: previous?.daysOfWeek?.split(",")?.mapNotNull { it.trim().toIntOrNull() }
        when (scheduleType) {
            "once" -> {
                val fireAt = time?.let { runCatching { ZonedDateTime.parse(it).withNano(0) }.getOrNull() }
                    ?: return ParsedAlarm.Error("ALARM_TIME_INVALID", "A zoned ISO-8601 time is required for a once alarm.")
                if (enabled && !fireAt.isAfter(ZonedDateTime.now(fireAt.zone))) {
                    return ParsedAlarm.Error("ALARM_TIME_PAST", "Enabled once alarm time must be in the future.")
                }
            }
            "weekly" -> {
                if (hour == null || hour !in 0..23 || minute == null || minute !in 0..59) return ParsedAlarm.Error("ALARM_TIME_INVALID", "Weekly alarm requires hour 0..23 and minute 0..59.")
                if (days.isNullOrEmpty() || days.any { it !in 1..7 }) return ParsedAlarm.Error("ALARM_DAYS_INVALID", "days_of_week must contain values 1..7.")
            }
            else -> return ParsedAlarm.Error("ALARM_SCHEDULE_TYPE_INVALID", "schedule_type must be once or weekly.")
        }
        val now = System.currentTimeMillis()
        return ParsedAlarm.Value(AlarmEntity(
            id = previous?.id ?: Uuid.random().toString(),
            label = label,
            note = note,
            scheduleType = scheduleType,
            time = if (scheduleType == "once") time else null,
            hour = if (scheduleType == "weekly") hour else null,
            minute = if (scheduleType == "weekly") minute else null,
            daysOfWeek = if (scheduleType == "weekly") days!!.distinct().sorted().joinToString(",") else null,
            timezone = timezone,
            enabled = enabled,
            vibrate = definition.boolean("vibrate") ?: previous?.vibrate ?: true,
            createdAtMs = previous?.createdAtMs ?: now,
            updatedAtMs = now,
            lastFiredAtMs = previous?.lastFiredAtMs,
            nextFireAtMs = previous?.nextFireAtMs,
        ))
    }

    private sealed interface ParsedDefinition {
        data class Value(val definition: WorkflowDefinition) : ParsedDefinition
        data class Error(val code: String, val message: String) : ParsedDefinition
    }

    private fun parseDefinition(request: OwnerOperationRequest, action: OwnerAction): ParsedDefinition {
        val element = action.arguments["definition"]
            ?: return ParsedDefinition.Error("WORKFLOW_DEFINITION_REQUIRED", "definition is required.")
        val allowedNames = request.availableToolNames.filterNotTo(mutableSetOf()) {
            it.startsWith("owner_") || it == "workflow_run"
        }
        val definitions = request.availableTools.filter { tool -> tool.name in allowedNames }
        return when (val parsed = WorkflowJson.parse(element.toString(), definitions)) {
            is WorkflowJson.ParseResult.Ok -> ParsedDefinition.Value(parsed.definition)
            is WorkflowJson.ParseResult.Err -> ParsedDefinition.Error(parsed.error, parsed.detail)
        }
    }

    private data class WorkflowReceipt(
        val workflowId: String,
        val previous: WorkflowDefinition?,
        val previousEnabled: Boolean,
    )
    private data class ScheduledJobReceipt(val jobId: String, val previous: ScheduledJobEntity?)
    private data class AlarmReceipt(val alarmId: String, val previous: AlarmEntity?)

    private fun success(index: Int, type: String, code: String, message: String, data: JsonObject? = null, receipt: Any? = null) =
        OwnerAppliedAction(OwnerActionResult(index, type, true, code, message, data), receipt)
    private fun failure(index: Int, type: String, code: String, message: String) =
        OwnerAppliedAction(OwnerActionResult(index, type, false, code, message.take(500)))
    private fun invalid(code: String, message: String) = OwnerActionValidation(false, code, message.take(500))
    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.boolean(key: String) = string(key)?.toBooleanStrictOrNull()
    private fun JsonObject.int(key: String) = string(key)?.toIntOrNull()
    private fun JsonObject.long(key: String) = string(key)?.toLongOrNull()

    private companion object {
        val FIELDS = mapOf(
            "workflow_list" to emptySet(),
            "workflow_create" to setOf("definition"),
            "workflow_update" to setOf("definition"),
            "workflow_delete" to setOf("workflow_id"),
            "workflow_set_enabled" to setOf("workflow_id", "enabled"),
            "workflow_run" to setOf("workflow_id"),
            "schedule_list" to emptySet(),
            "schedule_create" to setOf("definition"),
            "schedule_update" to setOf("job_id", "definition"),
            "schedule_set_enabled" to setOf("job_id", "enabled"),
            "schedule_run_now" to setOf("job_id"),
            "schedule_delete" to setOf("job_id"),
            "alarm_list" to emptySet(),
            "alarm_create" to setOf("definition"),
            "alarm_update" to setOf("alarm_id", "definition"),
            "alarm_set_enabled" to setOf("alarm_id", "enabled"),
            "alarm_delete" to setOf("alarm_id"),
        )
        val SCHEDULE_DEFINITION_FIELDS = setOf(
            "name", "description", "tags", "assistant_id", "mode", "prompt", "actions", "schedule_type",
            "at_unix_ms", "cron_expression", "timezone", "start_at_unix_ms", "end_at_unix_ms", "max_runs",
            "catchup", "target_conversation_id", "enabled",
        )
        val ALARM_DEFINITION_FIELDS = setOf(
            "label", "note", "schedule_type", "time", "hour", "minute", "days_of_week", "timezone", "vibrate", "enabled",
        )
        val AUTOMATION_RECURSIVE_TOOLS = setOf("workflow_run", "schedule_job", "trigger_job_now")
    }
}
