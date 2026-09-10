package me.rerere.rikkahub.workflow.execution

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.execution.ToolExecutionPlanRequest
import me.rerere.rikkahub.data.ai.execution.ToolExecutionPlanResult
import me.rerere.rikkahub.data.ai.execution.ToolRunPreflight
import me.rerere.rikkahub.data.ai.execution.ToolRuntime
import me.rerere.rikkahub.data.ai.execution.ToolRuntimeInvocation
import me.rerere.rikkahub.data.ai.execution.ToolStartableResolver
import me.rerere.rikkahub.data.ai.execution.WorkflowActionDiagnostics
import me.rerere.rikkahub.data.ai.tools.HardlineCommandGuard
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
import me.rerere.rikkahub.data.capability.CapabilitySubject
import me.rerere.rikkahub.data.capability.SubjectType
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.workflow.condition.ConditionEvaluator
import me.rerere.rikkahub.workflow.condition.ContextProvider
import me.rerere.rikkahub.workflow.model.WorkflowAction
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import me.rerere.rikkahub.workflow.model.WorkflowCapabilitySnapshot
import me.rerere.rikkahub.workflow.model.WorkflowInputSchemaValidator
import me.rerere.rikkahub.workflow.model.WorkflowOrigin
import me.rerere.rikkahub.workflow.model.WorkflowRunStatus
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import me.rerere.rikkahub.workflow.trigger.TriggerFireCallback
import java.time.LocalDate
import java.time.ZoneId

data class LearnedWorkflowAuthoritySnapshot(
    val sourceCandidateId: String,
    val sourceArtifactHash: String,
    val grantDigest: String,
    val authoringAssistantId: String,
    /** The definition parsed from the exact AppDatabase row selected for this fire. */
    val installedDefinition: WorkflowDefinition,
)

/** P4 promotion binds this port; absence is intentionally fail-closed. */
fun interface LearnedWorkflowAuthorityValidator {
    suspend fun isActive(snapshot: LearnedWorkflowAuthoritySnapshot): Boolean
}

/**
 * Phase 12 — workflow execution engine. The single entry point for any workflow fire.
 *
 * Lifecycle of a fire (matches `headless = true` semantics from cron jobs):
 *  1. Lookup workflow + verify enabled.
 *  2. Cooldown check — `lastRunAtMs + cooldownSeconds` against now.
 *  3. Daily-cap check — counted fires (SUCCESS+FAILED) for today's local date.
 *  4. Build [WorkflowContext] — lazy on location for sunset/sunrise conditions.
 *  5. Evaluate conditions; AND-combined.
 *  6. Resolve assistant + tool list. Workflows are app-global, but actions still need a
 *     tool surface to execute against — we use the first assistant with the Workflows
 *     toggle on (the toggle gates *authoring*; runtime fallback is reasonable).
 *  7. Execute action sequence via [DirectModeActionRunner] — every action HARDLINE-checked.
 *  8. Persist run row, projected last-run state, daily counter, trim history.
 *
 * Concurrency: per-workflow mutex so two near-simultaneous fires (e.g. WiFi flicker) can't
 * race on the daily counter. Cross-workflow execution stays parallel.
 *
 * Approval semantics: HARDLINE applies in workflow context. Tool factories that set
 * `needsApproval = { true }` would normally pop a prompt — workflows are headless and the
 * pre-authorisation is the workflow_create approval the user already granted. So the
 * action runner just calls the tool's [Tool.execute] directly. This matches scheduled-jobs
 * direct-mode behavior.
 *
 * The `Workflows` per-assistant toggle gates the seven `workflow_*` LLM tools, NOT the
 * trigger pipeline. A workflow that's been authored stays armed regardless of which
 * assistant the user is currently chatting with. Trigger dispatch is gated by the
 * workflow's own `enabled` flag.
 */
class WorkflowEngine(
    private val repository: WorkflowRepository,
    private val settingsStore: SettingsStore,
    private val contextProvider: ContextProvider,
    private val actionRunner: WorkflowActionRunner,
    private val emergencyController: WorkflowEmergencyController,
) {

    /**
     * [LocalTools] is resolved lazily via Koin to break the construction cycle:
     *   - [LocalTools] constructor takes a [WorkflowEngine] (so workflow_run can fire)
     *   - [WorkflowEngine] needs [LocalTools] only at fire time (to build the action's tool surface)
     * Eager constructor injection would loop the DI graph at startup — observed as a
     * StackOverflowError on first install of Phase 12. Lazy lookup is safe because the
     * graph is fully resolved by the time `fire()` is called.
     */
    private val localTools: LocalTools by lazy {
        org.koin.java.KoinJavaComponent.getKoin().get<LocalTools>()
    }

    /**
     * Phase 24 — unified AgentRun ledger writer. Resolved lazily via Koin (same pattern as
     * [localTools] above) to keep the engine's constructor DI surface minimal — the engine
     * is shared across cron / sub-agent surfaces and a tiny lookup on the rare-fire path is
     * cheaper than threading another constructor arg through the factory. No cycle risk:
     * AgentRunRepository depends only on its DAO.
     */
    private val agentRunRepo: me.rerere.rikkahub.data.agentrun.AgentRunRepository by lazy {
        org.koin.java.KoinJavaComponent.getKoin().get<me.rerere.rikkahub.data.agentrun.AgentRunRepository>()
    }

    private val learnedAuthorityValidator: LearnedWorkflowAuthorityValidator? by lazy {
        org.koin.java.KoinJavaComponent.getKoin().getOrNull<LearnedWorkflowAuthorityValidator>()
    }

    private val perWorkflowLocks = mutableMapOf<String, Mutex>()
    private val locksMutex = Mutex()

    private suspend fun lockFor(id: String): Mutex = locksMutex.withLock {
        perWorkflowLocks.getOrPut(id) { Mutex() }
    }

    /**
     * Drop the lock entry for a deleted workflow. Wired from
     * [me.rerere.rikkahub.workflow.repository.WorkflowRepository.deleteCascading] so the
     * lock map can't grow unbounded across heavy LLM-driven create/delete churn.
     */
    suspend fun forgetWorkflow(id: String) {
        locksMutex.withLock { perWorkflowLocks.remove(id) }
    }

    /**
     * Trigger callback target. The registry hands every fire here. [matchSpec] is the
     * variant that fired — used for diagnostics; the workflow's own [WorkflowDefinition.trigger]
     * is the source of truth for its semantics.
     */
    val triggerCallback = TriggerFireCallback { workflowId, _ -> fire(workflowId) }

    /**
     * Fire a workflow. Resolves cooldown / daily cap / conditions, then runs the action
     * sequence. Returns the resulting status — useful for `workflow_run` synchronous tool
     * call, ignored by the trigger callback path.
     */
    suspend fun fire(workflowId: String): FireOutcome = withContext(Dispatchers.IO) {
        emergencyController.runTracked(workflowId) {
            val lock = lockFor(workflowId)
            lock.withLock { fireLocked(workflowId) }
        } ?: FireOutcome(WorkflowRunStatus.FAILED, WorkflowFailureCode.EMERGENCY_STOP, "")
    }

    private suspend fun fireLocked(workflowId: String): FireOutcome {
        val firedAtMs = System.currentTimeMillis()
        val started = System.nanoTime()
        val loaded = repository.getById(workflowId) ?: run {
            val disabled = repository.disableInvalidLearnedById(
                workflowId,
                WorkflowFailureCode.LEARNED_DEFINITION_INVALID,
            )
            return FireOutcome(
                WorkflowRunStatus.FAILED,
                if (disabled) WorkflowFailureCode.LEARNED_DEFINITION_INVALID else WorkflowFailureCode.NOT_FOUND,
                "",
            )
        }
        val def = loaded.definition
        val entity = loaded.entity

        // Phase 24 — open the cross-pillar ledger row for this fire. Opened after the
        // workflow loads so a `workflow_not_found` non-fire isn't recorded, but before the
        // gate checks so a SKIPPED_* outcome is still visible in the ledger. domain_id is
        // the workflow id; the ledger row is per-fire (a fresh row each time fire() runs).
        val ledgerId = agentRunRepo.open(
            kind = me.rerere.rikkahub.data.agentrun.AgentRunKind.Workflow,
            domainId = workflowId,
            metadata = buildJsonObject {
                // Content-free diagnostics only: workflow names/descriptions/args never enter
                // the cross-pillar run ledger.
                put("origin", entity.origin)
                put("trigger_kind", def.trigger::class.simpleName ?: "unknown")
            },
        )

        if (!entity.enabled) {
            return persistAndReturn(workflowId, firedAtMs, started, WorkflowRunStatus.SKIPPED_DISABLED, null, "", ledgerId)
        }

        // Trigger runtime pre-flight — surface "this trigger needs setup" as an explicit
        // FAILED row in history so the user sees WHY the workflow doesn't fire instead of
        // just "Never run". The audit found these were silently dying:
        //  - geofence triggers without ACCESS_FINE_LOCATION + ACCESS_BACKGROUND_LOCATION
        //  - notification_received without notification listener bound
        //  - app_launched / app_closed without accessibility service running
        triggerRuntimeCheck(def.trigger)?.let { reason ->
            return persistAndReturn(workflowId, firedAtMs, started, WorkflowRunStatus.FAILED, reason, "", ledgerId)
        }

        // Cooldown gate. NOTE: must use `lastActualFireAtMs` (most-recent SUCCESS/FAILED
        // from history) — NOT `entity.lastRunAtMs`, which gets overwritten on every
        // attempt INCLUDING skips. Using the projected column would let SKIPPED_COOLDOWN
        // fires push the cooldown window forward indefinitely; the cooldown could never
        // be satisfied by waiting.
        val lastActualFireMs = if (def.cooldownSeconds > 0) repository.lastActualFireAtMs(workflowId) else null
        if (CooldownGate.isWithinCooldown(def.cooldownSeconds, lastActualFireMs, firedAtMs)) {
            return persistAndReturn(workflowId, firedAtMs, started, WorkflowRunStatus.SKIPPED_COOLDOWN, null, "", ledgerId)
        }

        // Daily-cap gate
        if (def.maxRunsPerDay != null) {
            val today = LocalDate.now(ZoneId.systemDefault()).toString()
            val countedToday = if (entity.runsTodayDate == today) entity.runsTodayCount else 0
            if (countedToday >= def.maxRunsPerDay) {
                return persistAndReturn(workflowId, firedAtMs, started, WorkflowRunStatus.SKIPPED_DAILY_CAP, null, "", ledgerId)
            }
        }

        // Conditions
        if (def.conditions.isNotEmpty()) {
            val ctx = contextProvider.snapshot(needsLocation = ConditionEvaluator.needsLocation(def.conditions))
            val cr = ConditionEvaluator.evaluateAll(def.conditions, ctx)
            if (cr is ConditionEvaluator.Result.FailedAt) {
                return persistAndReturn(
                    workflowId, firedAtMs, started, WorkflowRunStatus.SKIPPED_CONDITIONS,
                    WorkflowFailureCode.CONDITION_NOT_MET, "", ledgerId,
                )
            }
        }

        val isLearned = entity.origin == WorkflowOrigin.LEARNED.name
        var learnedAuthority: LearnedWorkflowAuthoritySnapshot? = null
        if (isLearned) {
            val authority = LearnedWorkflowAuthoritySnapshot(
                sourceCandidateId = def.sourceCandidateId.orEmpty(),
                sourceArtifactHash = def.sourceArtifactHash.orEmpty(),
                grantDigest = def.grantDigest.orEmpty(),
                authoringAssistantId = def.authoringAssistantId.orEmpty(),
                installedDefinition = def,
            )
            learnedAuthority = authority
            val authorityActive = authority.sourceCandidateId.isNotBlank() &&
                authority.sourceArtifactHash.isNotBlank() &&
                authority.grantDigest.isNotBlank() &&
                authority.authoringAssistantId.isNotBlank() &&
                runCatching { learnedAuthorityValidator?.isActive(authority) == true }
                    .getOrDefault(false)
            if (!authorityActive) {
                repository.disableLearnedAsStale(
                    loaded,
                    WorkflowFailureCode.LEARNED_AUTHORITY_INACTIVE,
                )
                return persistAndReturn(
                    workflowId,
                    firedAtMs,
                    started,
                    WorkflowRunStatus.FAILED,
                    WorkflowFailureCode.LEARNED_AUTHORITY_INACTIVE,
                    "",
                    ledgerId,
                )
            }
        }

        // Resolve assistant + tools. Prefer the persisted authoring assistant id (added by
        // the audit-pass fix to remove "first matching assistant" non-determinism). If the
        // workflow predates that fix (legacy null) OR the authoring assistant was deleted,
        // fall back to "any assistant with Workflows toggle on" but log loudly — the user's
        // intent might not match what we run.
        val settings = settingsStore.settingsFlow.first()
        val authoringAssistant = run {
            val storedId = def.authoringAssistantId
            val byId = if (storedId != null) {
                settings.assistants.firstOrNull { it.id.toString() == storedId }
            } else null
            if (byId != null) {
                byId
            } else if (isLearned) {
                null
            } else {
                if (storedId != null) {
                    logSafe("workflow_authoring_assistant_fallback")
                }
                settings.assistants.firstOrNull { asst ->
                    asst.localTools.any { it is me.rerere.rikkahub.data.ai.tools.LocalToolOption.Workflows }
                }
            }
        }
        if (authoringAssistant == null) {
            if (isLearned) {
                repository.disableLearnedAsStale(
                    loaded,
                    WorkflowFailureCode.LEARNED_ASSISTANT_MISSING,
                )
            }
            return persistAndReturn(workflowId, firedAtMs, started, WorkflowRunStatus.FAILED,
                if (isLearned) WorkflowFailureCode.LEARNED_ASSISTANT_MISSING else WorkflowFailureCode.NO_ASSISTANT,
                "", ledgerId)
        }
        // Headless context — sub-agent recursion guard fires from workflow-action
        // dispatch so a workflow's actions can't spawn a sub-agent that re-fires another
        // workflow_run that re-spawns ad infinitum.
        val frozenCapabilities = if (isLearned) {
            WorkflowCapabilitySnapshot.parsePersistedForLearnedExecution(def.capabilitySnapshot)
                ?: run {
                    repository.disableLearnedAsStale(
                        loaded,
                        WorkflowFailureCode.LEARNED_CAPABILITY_MISSING,
                    )
                    return persistAndReturn(
                        workflowId, firedAtMs, started, WorkflowRunStatus.FAILED,
                        WorkflowFailureCode.LEARNED_CAPABILITY_MISSING, "", ledgerId,
                    )
                }
        } else {
            WorkflowCapabilitySnapshot.parse(def.capabilitySnapshot.ifEmpty {
                // Legacy definitions have no persisted snapshot. Derive the narrowest snapshot
                // from their already-persisted actions rather than granting the author's whole
                // current tool surface; workflow_update will persist it explicitly.
                WorkflowCapabilitySnapshot.capture(def.actions)
            })
        }
        val executionContext = ToolExecutionContext(
            runId = kotlin.uuid.Uuid.random(),
            conversationId = kotlin.uuid.Uuid.random(),
            assistantId = authoringAssistant.id.toString(),
            callOrigin = ToolCallOrigin.TrustedWorkflow,
            capabilitySubject = CapabilitySubject(
                id = "workflow:${def.id}",
                type = SubjectType.WORKFLOW,
            ),
            frozenCapabilities = frozenCapabilities,
        )
        val workflowInvocation = me.rerere.rikkahub.data.ai.tools.ToolInvocationContext(
            callerAssistantId = authoringAssistant.id.toString(),
            callerConversationId = executionContext.conversationId.toString(),  // headless fire — synthetic conv
            callerWorkspaceId = authoringAssistant.workspaceId?.toString(),
            callerRunId = executionContext.runId.toString(),
            callOrigin = ToolCallOrigin.TrustedWorkflow,
            isHeadless = true,
        )
        // A USER workflow validates + executes against the CURRENT effective tool surface of its
        // authoring assistant — not unconditionally authoringAssistant.localTools. This closes the
        // authoring/run divergence where a workflow authored in a confirmed Second-User session
        // (which exposes the full allowlist surface) was later stamped against assistant.localTools
        // only, so every action tool outside localTools read as `tool_schema_stale`. Second-User
        // workflows re-validate against the assistant's current allowlist surface ONLY while the
        // assistant is still the ACTIVE Second User; legacy rows are healed by a bounded inference;
        // ordinary / LOCAL workflows stay pinned to localTools. See WorkflowRunSurfaceResolver.
        val localSurfaceTools = localTools.getTools(authoringAssistant.localTools, workflowInvocation)
        val localSurfaceToolNames = localSurfaceTools.mapTo(hashSetOf()) { it.name }
        val assistantIsActiveSecondUser = isActiveSecondUser(settings, authoringAssistant.id.toString())
        val runSurface = me.rerere.rikkahub.workflow.model.WorkflowRunSurfaceResolver.choose(
            origin = def.origin,
            authoringAuthority = def.authoringAuthority,
            referencedToolNames = def.actions.mapTo(hashSetOf()) { it.tool },
            localSurfaceToolNames = localSurfaceToolNames,
            assistantIsCurrentActiveSecondUser = assistantIsActiveSecondUser,
        )
        val tools = when (runSurface) {
            me.rerere.rikkahub.workflow.model.WorkflowRunSurface.SECOND_USER_ALLOWLIST -> {
                if (def.authoringAuthority == null) {
                    // De-identified: a legacy pre-marker row self-healed via bounded inference.
                    logSafe("workflow_legacy_second_user_surface_inferred workflow_id=$workflowId")
                }
                localTools.getTools(
                    me.rerere.rikkahub.data.ai.tools.SecondUserToolAllowlist
                        .resolveLocalOptions(settings.secondUserEnabledLocalToolTokens),
                    workflowInvocation,
                )
            }
            me.rerere.rikkahub.workflow.model.WorkflowRunSurface.ASSISTANT_LOCAL -> localSurfaceTools
        }

        // Resolver changes may narrow or widen current requirements, but they never mutate the
        // frozen grant. Any newly-required capability makes the durable workflow stale instead
        // of silently expanding its authority.
        val currentRequirements = WorkflowCapabilitySnapshot.capture(def.actions)
        val authorizedCapabilities = frozenCapabilities.mapTo(hashSetOf()) { it.value }
        val capabilitiesStillValid = if (isLearned) {
            authorizedCapabilities == currentRequirements
        } else {
            authorizedCapabilities.containsAll(currentRequirements)
        }
        if (!capabilitiesStillValid) {
            if (isLearned) repository.disableLearnedAsStale(
                loaded,
                WorkflowFailureCode.LEARNED_CAPABILITY_STALE,
            )
            return persistAndReturn(
                workflowId, firedAtMs, started, WorkflowRunStatus.FAILED,
                if (isLearned) WorkflowFailureCode.LEARNED_CAPABILITY_STALE else WorkflowFailureCode.CAPABILITY_STALE,
                "", ledgerId,
            )
        }

        val currentSchemas = me.rerere.rikkahub.toolcatalog.ToolCatalogSnapshot
            .fromDefinitions(tools)
        when (val problem = me.rerere.rikkahub.workflow.model.WorkflowSchemaGate
            .firstProblem(def.actions, currentSchemas, isLearned)
        ) {
            is me.rerere.rikkahub.workflow.model.WorkflowSchemaProblem.Stale -> {
                // De-identified diagnostic (723b3d0f) kept — now fires only on a TRUE schema
                // mismatch (tool present in the effective surface but its model-visible schema
                // changed), never on "tool absent from the current surface".
                logSafe(
                    workflowSchemaStaleDiagnostic(
                        workflowId = workflowId,
                        entityOrigin = entity.origin,
                        isLearned = isLearned,
                        actionTool = problem.action.tool,
                        storedFingerprint = problem.action.toolSchemaFingerprint,
                        actualEntry = currentSchemas.entry(problem.action.tool),
                        storedAssistantId = def.authoringAssistantId,
                        runtimeAssistantId = authoringAssistant.id.toString(),
                        capabilitySnapshot = def.capabilitySnapshot,
                    )
                )
                if (isLearned) repository.disableLearnedAsStale(
                    loaded,
                    WorkflowFailureCode.LEARNED_SCHEMA_STALE,
                )
                return persistAndReturn(
                    workflowId, firedAtMs, started, WorkflowRunStatus.FAILED,
                    if (isLearned) WorkflowFailureCode.LEARNED_SCHEMA_STALE else WorkflowFailureCode.SCHEMA_STALE,
                    "", ledgerId,
                )
            }
            is me.rerere.rikkahub.workflow.model.WorkflowSchemaProblem.ToolUnavailable -> {
                // USER action whose tool is not in the CURRENT effective surface — an
                // availability/policy problem (Second-User revoked, allowlist dropped the tool,
                // or assistant.localTools no longer has it), NOT a schema change. Distinct durable
                // code; it self-heals when the tool is re-allowed.
                logSafe(
                    workflowToolUnavailableDiagnostic(
                        workflowId = workflowId,
                        entityOrigin = entity.origin,
                        isLearned = isLearned,
                        actionTool = problem.action.tool,
                        storedAssistantId = def.authoringAssistantId,
                        runtimeAssistantId = authoringAssistant.id.toString(),
                        runSurface = runSurface,
                        capabilitySnapshot = def.capabilitySnapshot,
                    )
                )
                return persistAndReturn(
                    workflowId, firedAtMs, started, WorkflowRunStatus.FAILED,
                    WorkflowFailureCode.WORKFLOW_TOOL_UNAVAILABLE, "", ledgerId,
                )
            }
            null -> Unit // every action is present and schema-current — proceed to execution.
        }

        // Execute the action sequence. ActionRunner enforces per-action timeout + HARDLINE.
        val result = actionRunner.run(
            actions = def.actions,
            availableTools = tools,
            invocation = ToolRuntimeInvocation(
                executionContext = executionContext,
                // Workflow permissions are intentionally independent of the assistant's
                // legacy unrestricted marker. Future scoped workflow grants plug in here.
                unrestrictedOverride = false,
            ),
            beforeAction = {
                val authority = learnedAuthority
                if (authority == null) {
                    true
                } else {
                    runCatching { learnedAuthorityValidator?.isActive(authority) == true }
                        .getOrDefault(false)
                }
            },
        )
        val status = if (result.success) WorkflowRunStatus.SUCCESS else WorkflowRunStatus.FAILED
        return persistAndReturn(workflowId, firedAtMs, started, status, result.error, result.summary, ledgerId)
    }

    /**
     * Pre-flight check for trigger types that depend on runtime state (a permission, a
     * service binding, Play Services availability). Returns null if the trigger can fire,
     * or a stable error code otherwise — the engine then records the fire as FAILED with
     * that reason and the user sees a clear "missing setup" message in workflow_get history.
     */
    private fun triggerRuntimeCheck(trigger: me.rerere.rikkahub.workflow.model.TriggerSpec): String? {
        val ctx = (this as Any).let {
            // Static context lookup via Koin so we don't need to take it as a constructor arg
            // (engine is shared across cron / sub-agent surfaces; minimising its DI surface
            // is worth a tiny lookup cost on the rare-fire path).
            org.koin.java.KoinJavaComponent.getKoin().get<android.content.Context>()
        }
        return when (trigger) {
            is me.rerere.rikkahub.workflow.model.TriggerSpec.GeofenceEnter,
            is me.rerere.rikkahub.workflow.model.TriggerSpec.GeofenceExit -> {
                val fineGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                    ctx, android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                val bgGranted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        ctx, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                } else true
                when {
                    !fineGranted -> WorkflowFailureCode.GEOFENCE_FINE_LOCATION_MISSING
                    !bgGranted -> WorkflowFailureCode.GEOFENCE_BACKGROUND_LOCATION_MISSING
                    else -> null
                }
            }
            is me.rerere.rikkahub.workflow.model.TriggerSpec.NotificationReceived -> {
                if (!me.rerere.rikkahub.data.ai.tools.local.NotificationListenerHandle.isBound()) {
                    WorkflowFailureCode.NOTIFICATION_LISTENER_MISSING
                } else null
            }
            is me.rerere.rikkahub.workflow.model.TriggerSpec.AppLaunched,
            is me.rerere.rikkahub.workflow.model.TriggerSpec.AppClosed -> {
                if (!me.rerere.rikkahub.data.ai.tools.local.AccessibilityServiceHandle.isRunning()) {
                    WorkflowFailureCode.ACCESSIBILITY_MISSING
                } else null
            }
            is me.rerere.rikkahub.workflow.model.TriggerSpec.BluetoothDeviceConnected,
            is me.rerere.rikkahub.workflow.model.TriggerSpec.BluetoothDeviceDisconnected -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                        ctx, android.Manifest.permission.BLUETOOTH_CONNECT
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (!granted) WorkflowFailureCode.BLUETOOTH_PERMISSION_MISSING
                    else null
                } else null
            }
            else -> null
        }
    }

    private suspend fun persistAndReturn(
        workflowId: String,
        firedAtMs: Long,
        startedNanos: Long,
        status: WorkflowRunStatus,
        error: String?,
        summary: String,
        ledgerId: String,
    ): FireOutcome {
        val durationMs = (System.nanoTime() - startedNanos) / 1_000_000L
        runCatching {
            repository.recordFire(
                workflowId = workflowId,
                firedAtMs = firedAtMs,
                status = status,
                durationMs = durationMs,
                errorMessage = error,
            )
        }.onFailure { logSafe("workflow_run_persistence_failed") }
        // Phase 24 — mirror the terminal outcome into the cross-pillar ledger. Every
        // WorkflowRunStatus is terminal from the ledger's point of view: SUCCESS →
        // succeeded; FAILED → failed; every SKIPPED_* variant → cancelled (the fire was
        // accepted but a gate stopped it — not a failure, not a success).
        val ledgerStatus = when (status) {
            WorkflowRunStatus.SUCCESS -> me.rerere.rikkahub.data.agentrun.AgentRunStatus.succeeded
            WorkflowRunStatus.FAILED -> me.rerere.rikkahub.data.agentrun.AgentRunStatus.failed
            else -> me.rerere.rikkahub.data.agentrun.AgentRunStatus.cancelled
        }
        agentRunRepo.markTerminal(
            id = ledgerId,
            status = ledgerStatus,
            lastError = error ?: if (
                ledgerStatus == me.rerere.rikkahub.data.agentrun.AgentRunStatus.cancelled
            ) WorkflowFailureCode.GATE_SKIPPED else null,
        )
        return FireOutcome(status, error, summary)
    }

    companion object { private const val TAG = "WorkflowEngine" }

    private fun logSafe(code: String) {
        runCatching { Log.w(TAG, code) }
    }

    /**
     * Current ACTIVE Second-User standing for one assistant, read from the durable authority
     * config (the only trusted source — legacy per-assistant privileged fields are never used for
     * a run decision). Requires the same assistant id; a reassignment/revocation that moves the
     * authority elsewhere, or any non-ACTIVE state, removes standing and the run falls back to the
     * assistant's ordinary localTools surface.
     */
    private fun isActiveSecondUser(
        settings: me.rerere.rikkahub.data.datastore.Settings,
        assistantId: String,
    ): Boolean {
        val config = settings.secondUserAuthority.normalized()
        return config.state == me.rerere.rikkahub.assistant.SecondUserAuthorityState.ACTIVE &&
            config.assistantId?.toString() == assistantId
    }

    data class FireOutcome(
        val status: WorkflowRunStatus,
        val error: String?,
        val summary: String,
    )
}

/**
 * Cooldown decision in isolation so the (load-bearing) gate logic can be unit-tested
 * without spinning up Room + the engine. The rule: use the most-recent SUCCESS/FAILED
 * fire time, not the workflow row's projected lastRunAtMs (the projected column is
 * bumped on every attempt — including skips — so it can't be the cooldown anchor).
 */
internal object CooldownGate {
    fun isWithinCooldown(cooldownSeconds: Int, lastActualFireMs: Long?, nowMs: Long): Boolean {
        if (cooldownSeconds <= 0) return false
        if (lastActualFireMs == null) return false
        return nowMs < lastActualFireMs + cooldownSeconds * 1000L
    }
}

/**
 * Sequential action runner — wraps [me.rerere.rikkahub.service.DirectModeActionRunner]'s
 * core logic but on the workflow side, since direct-mode's own runner takes a slightly
 * different action shape. Same HARDLINE-then-execute semantics.
 *
 * Per-action timeout is the action's [WorkflowAction.timeoutSeconds] field; default 60s.
 */
class WorkflowActionRunner(
    private val toolRuntime: ToolRuntime,
    private val toolStartableResolver: ToolStartableResolver,
    private val preflight: ToolRunPreflight,
) {

    data class RunResult(val success: Boolean, val error: String?, val summary: String)

    suspend fun run(
        actions: List<WorkflowAction>,
        availableTools: List<Tool>,
        invocation: ToolRuntimeInvocation,
        /** Last-responsible-boundary grant/rollout fence; checked before every action. */
        beforeAction: suspend () -> Boolean = { true },
    ): RunResult {
        var completed = 0
        // ── TEMPORARY diagnostics (diag commit) — content-free boundary markers. ────────────
        // Resolved once per run; the markers never read tool arguments or outputs.
        val diagWorkflowId = WorkflowActionDiagnostics.workflowIdOf(invocation)
        val diagOrigin = invocation.executionContext.callOrigin
        // A workflow action is headless by construction, and TrustedWorkflow is the origin the
        // engine stamps for exactly this path.
        val diagHeadless = diagOrigin == ToolCallOrigin.TrustedWorkflow
        for ((idx, action) in actions.withIndex()) {
            val toolCallId = "workflow-${invocation.executionContext.runId}-$idx"
            WorkflowActionDiagnostics.beginAction(toolCallId)
            WorkflowActionDiagnostics.mark(
                "workflow_action_start", toolCallId, action.tool, diagWorkflowId, diagHeadless,
                diagOrigin.name,
            )
            // Diagnostic-only wrapper: guarantees the anchor is released on EVERY
            // exit path — success, timeout, tool failure, Throwable, the runner-side
            // early returns below (fence / hardline / unknown tool / schema), and a
            // rethrown CancellationException. No control flow is altered.
            try {
                val actionAuthorized = try {
                    beforeAction()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    false
                }
                if (!actionAuthorized) {
                    return RunResult(
                        false,
                        WorkflowFailureCode.ACTION_RUNTIME_FENCE_REJECTED,
                        WorkflowFailureCode.ACTIONS_INCOMPLETE,
                    )
                }
                val argsJson = action.args.toString()
                val hardlineReason = HardlineCommandGuard.checkTool(action.tool, argsJson)
                if (hardlineReason != null) {
                    logSafe(WorkflowFailureCode.ACTION_HARDLINE_BLOCKED)
                    return RunResult(success = false,
                        error = WorkflowFailureCode.ACTION_HARDLINE_BLOCKED,
                        summary = WorkflowFailureCode.ACTIONS_INCOMPLETE)
                }
                val tool = availableTools.find { it.name == action.tool }
                    ?: return RunResult(
                        false,
                        WorkflowFailureCode.ACTION_UNKNOWN_TOOL,
                        WorkflowFailureCode.ACTIONS_INCOMPLETE,
                    )
                val inputSchemaError = runCatching {
                    WorkflowInputSchemaValidator.validate(action.args, tool.parameters())
                }.getOrElse {
                    return RunResult(
                        false,
                        WorkflowFailureCode.ACTION_INVALID_SCHEMA,
                        WorkflowFailureCode.ACTIONS_INCOMPLETE,
                    )
                }
                if (inputSchemaError != null) {
                    return RunResult(
                        false,
                        WorkflowFailureCode.ACTION_INVALID_ARGS,
                        WorkflowFailureCode.ACTIONS_INCOMPLETE,
                    )
                }
                // Nullable only so the diagnostic `finally` below can observe the outcome before
                // releasing the anchor. Every non-local exit from the try (rethrow / return) means
                // the fallback after the block is unreachable in practice.
                var actionOutcome: ToolExecutionPlanResult? = null
                try {
                    WorkflowActionDiagnostics.mark(
                        "before_action_dispatch", toolCallId, action.tool, diagWorkflowId,
                        diagHeadless, diagOrigin.name,
                    )
                    actionOutcome = toolRuntime.execute(
                        ToolExecutionPlanRequest(
                            toolCallId = toolCallId,
                            toolName = tool.name,
                            toolSchemaFingerprint = me.rerere.rikkahub.toolcatalog.ToolCatalogSnapshot
                                .fromDefinitions(listOf(tool))
                                .entry(tool.name)
                                ?.schemaFingerprint,
                            args = action.args,
                            executionContext = invocation.executionContext,
                            startableTool = toolStartableResolver.resolve(
                                tool,
                                invocation.executionContext,
                            ),
                            legacyExecute = { input -> tool.execute(input.jsonObject) },
                            runControl = null,
                            wallClockBudgetMs = action.timeoutSeconds.toLong()
                                .coerceAtLeast(0L) * 1_000L,
                            preExecutionGate = {
                                preflight.authorize(
                                    toolName = tool.name,
                                    args = action.args,
                                    context = invocation.executionContext,
                                    unrestrictedOverride = invocation.unrestrictedOverride,
                                )
                            },
                        ),
                    )
                } catch (c: kotlinx.coroutines.CancellationException) {
                    // Don't swallow cancellation — re-throw so structured concurrency can
                    // unwind the fire (e.g. the engine scope is cancelled on shutdown). The
                    // generic catch below would otherwise turn it into a spurious FAILED row.
                    throw c
                } catch (_: Throwable) {
                    logSafe(WorkflowFailureCode.ACTION_RUNTIME_FAILURE)
                    return RunResult(false,
                        WorkflowFailureCode.ACTION_RUNTIME_FAILURE,
                        WorkflowFailureCode.ACTIONS_INCOMPLETE)
                } finally {
                    // Nested diagnostic finally. These markers still need a live anchor, so they
                    // are emitted here; the anchor itself is released by the OUTER finally, which
                    // runs last and covers the early returns that never reach this block.
                    WorkflowActionDiagnostics.mark(
                        "after_action_dispatch", toolCallId, action.tool, diagWorkflowId,
                        diagHeadless, diagOrigin.name,
                    )
                    if (actionOutcome is ToolExecutionPlanResult.TimedOut) {
                        WorkflowActionDiagnostics.mark(
                            "workflow_action_timeout", toolCallId, action.tool, diagWorkflowId,
                            diagHeadless, diagOrigin.name,
                        )
                    }
                }
                val finished = actionOutcome ?: return RunResult(
                    false,
                    WorkflowFailureCode.ACTION_RUNTIME_FAILURE,
                    WorkflowFailureCode.ACTIONS_INCOMPLETE,
                )
                if (finished is ToolExecutionPlanResult.TimedOut) {
                    return RunResult(false,
                        WorkflowFailureCode.ACTION_TIMEOUT,
                        WorkflowFailureCode.ACTIONS_INCOMPLETE)
                }
                if (finished is ToolExecutionPlanResult.Rejected) {
                    return RunResult(
                        false,
                        WorkflowFailureCode.ACTION_REJECTED,
                        WorkflowFailureCode.ACTIONS_INCOMPLETE,
                    )
                }
                completed++
            } finally {
                // Markers above need the anchor, so the release is last.
                WorkflowActionDiagnostics.endAction(toolCallId)
            }
        }
        return RunResult(
            true,
            null,
            if (completed == actions.size) {
                WorkflowFailureCode.ACTIONS_COMPLETED
            } else {
                WorkflowFailureCode.ACTIONS_INCOMPLETE
            },
        )
    }

    /**
     * Wrap [Log.w] in a guard so JVM unit tests (where android.util.Log is unmocked)
     * don't crash before the runner can return its actual result.
     */
    private fun logSafe(msg: String) {
        runCatching { Log.w(TAG, msg) }
    }

    companion object { private const val TAG = "WorkflowActionRunner" }
}

/** Content-free workflow run reasons. Never append runtime values to these allowlisted codes. */
object WorkflowFailureCode {
    const val EMERGENCY_STOP = "emergency_stop"
    const val NOT_FOUND = "workflow_not_found"
    const val LEARNED_DEFINITION_INVALID = "learned_definition_invalid"
    const val CONDITION_NOT_MET = "condition_not_met"
    const val LEARNED_AUTHORITY_INACTIVE = "learned_authority_inactive"
    const val LEARNED_ASSISTANT_MISSING = "learned_assistant_missing"
    const val NO_ASSISTANT = "workflow_assistant_missing"
    const val LEARNED_CAPABILITY_MISSING = "learned_capability_missing"
    const val LEARNED_CAPABILITY_STALE = "learned_capability_stale"
    const val CAPABILITY_STALE = "capability_stale"
    const val LEARNED_SCHEMA_STALE = "learned_schema_stale"
    const val SCHEMA_STALE = "tool_schema_stale"
    /**
     * USER action references a tool that is not present in the CURRENT effective run surface
     * (Second-User allowlist dropped it, the Second-User authority was revoked/reassigned, or the
     * assistant's localTools no longer include it). Availability/policy problem, NOT schema
     * staleness — it self-heals when the tool is re-allowed. Learned workflows never receive this
     * code (they keep fail-closed [LEARNED_SCHEMA_STALE]).
     */
    const val WORKFLOW_TOOL_UNAVAILABLE = "workflow_tool_unavailable"
    const val GEOFENCE_FINE_LOCATION_MISSING = "geofence_fine_location_missing"
    const val GEOFENCE_BACKGROUND_LOCATION_MISSING = "geofence_background_location_missing"
    const val NOTIFICATION_LISTENER_MISSING = "notification_listener_missing"
    const val ACCESSIBILITY_MISSING = "accessibility_service_missing"
    const val BLUETOOTH_PERMISSION_MISSING = "bluetooth_permission_missing"
    const val ACTION_HARDLINE_BLOCKED = "action_hardline_blocked"
    const val ACTION_UNKNOWN_TOOL = "action_unknown_tool"
    const val ACTION_INVALID_SCHEMA = "action_invalid_schema"
    const val ACTION_INVALID_ARGS = "action_invalid_args"
    const val ACTION_RUNTIME_FAILURE = "action_runtime_failure"
    const val ACTION_TIMEOUT = "action_timeout"
    const val ACTION_REJECTED = "action_rejected"
    const val ACTION_RUNTIME_FENCE_REJECTED = "action_runtime_fence_rejected"
    const val ACTIONS_COMPLETED = "actions_completed"
    const val ACTIONS_INCOMPLETE = "actions_incomplete"
    const val GATE_SKIPPED = "workflow_gate_skipped"

    private val durable = setOf(
        EMERGENCY_STOP, NOT_FOUND, LEARNED_DEFINITION_INVALID, CONDITION_NOT_MET,
        LEARNED_AUTHORITY_INACTIVE, LEARNED_ASSISTANT_MISSING, NO_ASSISTANT,
        LEARNED_CAPABILITY_MISSING, LEARNED_CAPABILITY_STALE, CAPABILITY_STALE,
        LEARNED_SCHEMA_STALE, SCHEMA_STALE, WORKFLOW_TOOL_UNAVAILABLE,
        GEOFENCE_FINE_LOCATION_MISSING,
        GEOFENCE_BACKGROUND_LOCATION_MISSING, NOTIFICATION_LISTENER_MISSING,
        ACCESSIBILITY_MISSING, BLUETOOTH_PERMISSION_MISSING, ACTION_HARDLINE_BLOCKED,
        ACTION_UNKNOWN_TOOL, ACTION_INVALID_SCHEMA, ACTION_INVALID_ARGS,
        ACTION_RUNTIME_FAILURE, ACTION_TIMEOUT, ACTION_REJECTED,
        ACTION_RUNTIME_FENCE_REJECTED, GATE_SKIPPED,
    )

    fun durableOrGeneric(value: String?): String? = when (value) {
        null -> null
        in durable -> value
        else -> ACTION_RUNTIME_FAILURE
    }
}

/**
 * De-identified schema-stale diagnostic. Only fingerprints/hashes and identity are emitted —
 * never tool arguments, prompts, tokens/secrets, user content, or full schema/description text.
 * Hashes use the same canonical JSON shape as ToolCatalog (encodeDefaults, no explicit nulls) so
 * the numbers are comparable to what ToolCatalogSnapshot hashed at authoring time.
 */
private fun workflowSchemaStaleDiagnostic(
    workflowId: String,
    entityOrigin: String,
    isLearned: Boolean,
    actionTool: String,
    storedFingerprint: String?,
    actualEntry: me.rerere.rikkahub.toolcatalog.ToolCatalogEntry?,
    storedAssistantId: String?,
    runtimeAssistantId: String,
    capabilitySnapshot: Set<String>,
): String {
    val actualFingerprint = actualEntry?.schemaFingerprint?.take(16)
    val descriptionSha = actualEntry?.let { schemaStaleSha256(it.definition.description).take(16) }
    val parametersSha = actualEntry?.let { entry ->
        runCatching {
            entry.definition.parameters()?.let { parameters ->
                schemaStaleJson.encodeToString(InputSchema.serializer(), parameters)
            }
        }.getOrNull()?.let { schemaStaleSha256(it).take(16) }
    }
    return buildString {
        append("workflow_schema_stale")
        append(" workflow_id=").append(workflowId)
        append(" origin=").append(entityOrigin)
        append(" is_learned=").append(isLearned)
        append(" tool=").append(actionTool)
        append(" expected=").append(storedFingerprint?.take(16) ?: "null")
        append(" actual=").append(actualFingerprint ?: "null")
        append(" runtime_has_tool=").append(actualEntry != null)
        append(" stored_assistant=").append(storedAssistantId ?: "null")
        append(" runtime_assistant=").append(runtimeAssistantId)
        append(" desc_sha=").append(descriptionSha ?: "null")
        append(" params_sha=").append(parametersSha ?: "null")
        append(" source=").append(actualEntry?.source?.name ?: "null")
        append(" capability=[").append(capabilitySnapshot.toSortedSet().joinToString(",")).append("]")
    }.toString()
}

/**
 * De-identified availability diagnostic for a USER action whose tool is absent from the current
 * effective run surface. Identity + surface only — never tool args, prompts, tokens, or schema text.
 */
private fun workflowToolUnavailableDiagnostic(
    workflowId: String,
    entityOrigin: String,
    isLearned: Boolean,
    actionTool: String,
    storedAssistantId: String?,
    runtimeAssistantId: String,
    runSurface: me.rerere.rikkahub.workflow.model.WorkflowRunSurface,
    capabilitySnapshot: Set<String>,
): String = buildString {
    append("workflow_tool_unavailable")
    append(" workflow_id=").append(workflowId)
    append(" origin=").append(entityOrigin)
    append(" is_learned=").append(isLearned)
    append(" tool=").append(actionTool)
    append(" stored_assistant=").append(storedAssistantId ?: "null")
    append(" runtime_assistant=").append(runtimeAssistantId)
    append(" run_surface=").append(runSurface.name)
    append(" capability=[").append(capabilitySnapshot.toSortedSet().joinToString(",")).append("]")
}.toString()

private fun schemaStaleSha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }

private val schemaStaleJson = Json { encodeDefaults = true; explicitNulls = false }
