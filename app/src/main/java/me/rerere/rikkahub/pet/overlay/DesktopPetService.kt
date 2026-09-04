package me.rerere.rikkahub.pet.overlay

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.assistant.SecondUserPresentationSource
import me.rerere.rikkahub.assistant.SecondUserTarget
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.pet.PetPresentationMapper
import me.rerere.rikkahub.pet.PetStatusBadge
import me.rerere.rikkahub.pet.PetAction
import me.rerere.rikkahub.pet.PetOverlaySelection
import me.rerere.rikkahub.pet.resolvePetOverlaySelection
import me.rerere.rikkahub.pet.PetDialogueGenerator
import me.rerere.rikkahub.pet.PetDialogueInputKind
import me.rerere.rikkahub.pet.PetDialogueRepository
import me.rerere.rikkahub.pet.PetDialogueTurnDraft
import me.rerere.rikkahub.pet.PetDialogueTurnEntityView
import me.rerere.rikkahub.pet.PetInteractionPayload
import me.rerere.rikkahub.pet.PetGenerationResult
import me.rerere.rikkahub.pet.PetHandoffDraft
import me.rerere.rikkahub.pet.PetHandoffCoordinator
import me.rerere.rikkahub.pet.PetHandoffMode
import me.rerere.rikkahub.pet.PetHandoffStatus
import me.rerere.rikkahub.pet.PetHandoffSubmitResult
import me.rerere.rikkahub.pet.PetOverlayGestureAction
import me.rerere.rikkahub.pet.PetPersonaSource
import me.rerere.rikkahub.pet.PetBubbleSanitizer
import me.rerere.rikkahub.pet.petOverlayGestureAction
import me.rerere.rikkahub.pet.assets.CODEX_FRAME_HEIGHT
import me.rerere.rikkahub.pet.assets.CODEX_FRAME_WIDTH
import me.rerere.rikkahub.pet.render.PetRenderer
import me.rerere.rikkahub.pet.render.PetRendererFactory
import me.rerere.rikkahub.pet.render.PetSpriteAtlas
import me.rerere.rikkahub.pet.action.CorePetActions
import me.rerere.rikkahub.pet.action.PetActionId
import me.rerere.rikkahub.pet.action.PetActionProfile
import me.rerere.rikkahub.pet.action.toSemanticAction
import me.rerere.rikkahub.pet.action.PetVisualHint
import me.rerere.rikkahub.pet.behavior.PetActionSource
import me.rerere.rikkahub.pet.behavior.PetActionTraceStore
import me.rerere.rikkahub.pet.behavior.PetRuntimeDiagnostics
import me.rerere.rikkahub.pet.behavior.PetBehaviorIntent
import me.rerere.rikkahub.pet.behavior.PetBehaviorOrchestrator
import me.rerere.rikkahub.pet.behavior.PetBehaviorPriority
import me.rerere.rikkahub.pet.behavior.PetIdlePoolController
import me.rerere.rikkahub.pet.behavior.PetSpeechBehaviorBridge
import me.rerere.rikkahub.pet.profile.PetProfileRepository
import me.rerere.rikkahub.tts.PersistentTtsLibrary
import me.rerere.rikkahub.tts.TtsPlaybackOwner
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.chat.PetInteractionSlotResult
import org.koin.android.ext.android.inject

class DesktopPetService : Service() {
    private val settingsStore: SettingsStore by inject()
    private val presentationSource: SecondUserPresentationSource by inject()
    private val dialogueRepository: PetDialogueRepository by inject()
    private val dialogueGenerator: PetDialogueGenerator by inject()
    private val personaSource: PetPersonaSource by inject()
    private val handoffCoordinator: PetHandoffCoordinator by inject()
    private val chatService: ChatService by inject()
    private val persistentTtsLibrary: PersistentTtsLibrary by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var windowManager: WindowManager
    private var spriteView: PetSpriteView? = null
    private var placeholderView: View? = null
    private var bubbleView: TextView? = null
    private var dialogueOverlay: PetDialogueOverlayView? = null
    private var spriteParams: WindowManager.LayoutParams? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var atlas: PetSpriteAtlas? = null
    private var activeProfile: PetActionProfile? = null
    private val profileRepository by lazy {
        PetProfileRepository(
            petsRoot = filesDir.resolve("pets"),
            overridesRoot = filesDir.resolve("pet_profile_overrides"),
        )
    }
    private var renderer: PetRenderer? = null
    private val actionTraces: PetActionTraceStore by inject()
    private val runtimeDiagnostics: PetRuntimeDiagnostics by inject()
    private val behavior = PetBehaviorOrchestrator(
        scope = scope,
        traceSink = actionTraces::append,
    )
    private val speechBridge by lazy {
        PetSpeechBehaviorBridge(scope, persistentTtsLibrary, behavior)
    }
    private val idlePool by lazy { PetIdlePoolController(scope, behavior) }
    private var loadedRenderConfig: PetRenderConfig? = null
    private var activePresentationSource: PetActionSource? = null
    private var lastPresentationStatus: me.rerere.rikkahub.assistant.SecondUserPresentationStatus? = null
    private var currentStatusBadge: PetStatusBadge? = null
    private var currentStatusBubble: String? = null
    private var sidecarAllowed = false
    private var configuredAssistant: me.rerere.rikkahub.data.model.Assistant? = null
    private var configuredSelection: PetOverlaySelection? = null
    private var interactionJob: Job? = null
    private var dialogueObservationJob: Job? = null
    private var handoffBubbleJob: Job? = null
    private var transientHandoffBubble: String? = null
    private var touchBubbleJob: Job? = null
    private var transientTouchBubble: String? = null
    private var lastTouchModelRequestAtMs = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refreshVisibility()
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(applicationInfo.icon)
                .setContentTitle("桌宠正在运行")
                .setContentText("与第二用户绑定的本地桌宠")
                .setOngoing(true)
                .setSilent(true)
                .build(),
        )
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch {
            behavior.state.collect { state ->
                renderer?.render(state)
                runtimeDiagnostics.updateBehavior(state)
            }
        }
        speechBridge
        idlePool
        scope.launch { observeConfiguredPet() }
        scope.launch { TrustedApprovalSurfaceVisibility.visible.collect { refreshVisibility() } }
        scope.launch {
            handoffCoordinator.completions.collect { completion ->
                if (System.currentTimeMillis() - completion.completedAtMs > HANDOFF_RESULT_REPLAY_WINDOW_MS) {
                    return@collect
                }
                transientHandoffBubble = completion.text
                dialogueOverlay?.setStatus(
                    if (completion.failed) "第二用户任务未完成" else "第二用户已回复",
                    error = completion.failed,
                )
                behavior.submit(
                    PetBehaviorIntent.OneShot(
                        action = if (completion.failed) CorePetActions.FAILURE else CorePetActions.REVIEW,
                        source = PetActionSource.HANDOFF,
                        priority = PetBehaviorPriority.HANDOFF_RESULT,
                        minDurationMs = 700L,
                        maxDurationMs = 3_000L,
                    ),
                )
                refreshBubble()
                handoffBubbleJob?.cancel()
                handoffBubbleJob = launch {
                    delay(HANDOFF_RESULT_BUBBLE_MS)
                    transientHandoffBubble = null
                    refreshBubble()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RELOAD) {
            configuredSelection?.let { loadConfiguredPackage(it, force = true) }
        }
        if (intent?.action == ACTION_DIALOGUE_VISUAL) {
            val hint = intent.getStringExtra(EXTRA_VISUAL_HINT)
                ?.let { value -> runCatching { PetVisualHint.valueOf(value) }.getOrNull() }
                ?: PetVisualHint.NEUTRAL
            behavior.submit(
                PetBehaviorIntent.OneShot(
                    action = hint.toSemanticAction(),
                    source = PetActionSource.DIALOGUE,
                    priority = PetBehaviorPriority.TOUCH,
                    minDurationMs = TOUCH_MIN_DURATION_MS,
                    maxDurationMs = TOUCH_RESPONSE_ACTION_MAX_DURATION_MS,
                ),
            )
        }
        if (intent?.action == ACTION_HANDOFF_VISUAL) {
            behavior.submit(
                PetBehaviorIntent.OneShot(
                    action = CorePetActions.REVIEW,
                    source = PetActionSource.HANDOFF,
                    priority = PetBehaviorPriority.HANDOFF_RESULT,
                    minDurationMs = 700L,
                    maxDurationMs = 2_000L,
                ),
            )
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        removeWindows(cancelTransientWork = true)
        speechBridge.close()
        idlePool.close()
        behavior.close()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun observeConfiguredPet() {
        settingsStore.settingsFlow.collectLatest { settings ->
            if (settings.init) return@collectLatest
            val resolvedSelection = settings.resolvePetOverlaySelection()
            if (resolvedSelection == null) {
                removeWindows(cancelTransientWork = false)
                // The settings screen persists asynchronously. On a first enable,
                // collectLatest will cancel this grace delay as soon as the enabled
                // assistant reaches SettingsStore.
                if (configuredAssistant == null) {
                    Log.i(TAG, "Waiting for enabled pet configuration")
                    delay(STARTUP_CONFIGURATION_GRACE_MS)
                }
                configuredAssistant = null
                configuredSelection = null
                stopSelf()
                return@collectLatest
            }
            if (resolvedSelection.migratedFromLegacy) {
                scope.launch { settingsStore.migrateLegacyPetOverlaySelection() }
            }
            val assistant = resolvedSelection.assistant
            val selection = resolvedSelection.selection
            Log.i(TAG, "Loaded enabled pet configuration")
            val targetChanged = configuredAssistant?.id != assistant.id ||
                configuredSelection?.privilegedConversationId != selection.privilegedConversationId
            val loaded = loadConfiguredPackage(selection)
            val activeAssistant = if (loaded || configuredAssistant == null) assistant else checkNotNull(configuredAssistant)
            val activeSelection = if (loaded || configuredSelection == null) selection else checkNotNull(configuredSelection)
            if (targetChanged && !loaded && configuredAssistant != null && configuredSelection != null) {
                // The requested replacement was invalid or could not be preloaded. Keep the old
                // target, P0 state and bubble alive rather than pretending the switch succeeded.
                Log.w(TAG, "Keeping existing pet after renderer preflight failed")
            }
            if (targetChanged && loaded) {
                closePetOverlay()
                activePresentationSource?.let { behavior.submit(PetBehaviorIntent.ClearSource(it)) }
                activePresentationSource = null
                lastPresentationStatus = null
                currentStatusBadge = null
                currentStatusBubble = null
                spriteView?.setStatusBadge(null)
                refreshBubble()
            }
            configuredAssistant = activeAssistant
            configuredSelection = activeSelection
            speechBridge.setOwnerKey(
                TtsPlaybackOwner.secondUser(
                    activeAssistant.id.toString(),
                    activeSelection.privilegedConversationId.toString(),
                ),
            )
            val target = SecondUserTarget(activeAssistant.id, activeSelection.privilegedConversationId)
            presentationSource.observe(target).collect { state ->
                val mapping = PetPresentationMapper.mapping(state.status)
                activePresentationSource
                    ?.takeIf { it != mapping.source }
                    ?.let { behavior.submit(PetBehaviorIntent.ClearSource(it)) }
                behavior.submit(mapping.asIntent())
                activePresentationSource = mapping.source
                currentStatusBadge = mapping.badge
                spriteView?.setStatusBadge(mapping.badge)
                if (state.status == me.rerere.rikkahub.assistant.SecondUserPresentationStatus.SUCCEEDED_RECENTLY &&
                    lastPresentationStatus != null &&
                    lastPresentationStatus != me.rerere.rikkahub.assistant.SecondUserPresentationStatus.SUCCEEDED_RECENTLY &&
                    state.trusted
                ) {
                    behavior.submit(
                        PetBehaviorIntent.Sequence(
                            steps = listOf(
                                me.rerere.rikkahub.pet.behavior.PetActionSequenceStep(
                                    action = CorePetActions.JUMP,
                                    minDurationMs = 700L,
                                    maxDurationMs = 1_200L,
                                ),
                                me.rerere.rikkahub.pet.behavior.PetActionSequenceStep(
                                    action = CorePetActions.WAVE,
                                    minDurationMs = 700L,
                                    maxDurationMs = 1_500L,
                                ),
                            ),
                            source = PetActionSource.HANDOFF,
                            priority = PetBehaviorPriority.HANDOFF_RESULT,
                        ),
                    )
                }
                lastPresentationStatus = state.status
                sidecarAllowed = state.status in setOf(
                    me.rerere.rikkahub.assistant.SecondUserPresentationStatus.IDLE,
                    me.rerere.rikkahub.assistant.SecondUserPresentationStatus.BACKGROUND_SERVICE_RUNNING,
                )
                currentStatusBubble = PetPresentationMapper.bubble(state.status)
                refreshIdlePool()
                refreshBubble()
                dialogueOverlay?.setStatus(
                    currentStatusBubble ?: if (sidecarAllowed) "可以和桌宠聊天" else "第二用户正在处理任务",
                )
                refreshVisibility()
            }
        }
    }

    private fun loadConfiguredPackage(selection: PetOverlaySelection, force: Boolean = false): Boolean {
        val config = PetRenderConfig(
            packageId = selection.packageId,
            profileId = selection.profileId,
            scale = selection.scale.coerceIn(MIN_PET_SCALE, MAX_PET_SCALE),
            animationFps = selection.animationFps.coerceIn(MIN_ANIMATION_FPS, MAX_ANIMATION_FPS),
        )
        if (!force && config == loadedRenderConfig && (spriteView != null || placeholderView != null)) return true
        if (!Settings.canDrawOverlays(this)) {
            Log.w(
                TAG,
                "Desktop pet overlay permission (SYSTEM_ALERT_WINDOW) is not granted; " +
                    "stopping instead of pretending the pet is running",
            )
            stopSelf()
            return false
        }
        val loaded = config.packageId?.let { packageId ->
            runCatching { profileRepository.load(packageId, config.profileId) }.getOrNull()
        }
        if (loaded == null) {
            if (config.packageId != null) runtimeDiagnostics.markResourceInvalid()
            // First launch can show the harmless application placeholder. A failed switch keeps
            // the current role visible instead of replacing it with a black or empty overlay.
            if (spriteView == null && placeholderView == null) {
                showPlaceholder()
                loadedRenderConfig = config
                return true
            }
            return false
        }
        val replaced = replaceSpriteAtomically(loaded.atlas, loaded.profile, config, selection)
        if (replaced) {
            loadedRenderConfig = config
            refreshVisibility()
        }
        return replaced
    }

    private fun replaceSpriteAtomically(
        newAtlas: PetSpriteAtlas,
        profile: PetActionProfile,
        config: PetRenderConfig,
        selection: PetOverlaySelection,
    ): Boolean {
        val requestedWidth = dp((CODEX_FRAME_WIDTH * config.scale).roundToInt()).coerceAtLeast(1)
        val requestedHeight = dp((CODEX_FRAME_HEIGHT * config.scale).roundToInt()).coerceAtLeast(1)
        val fit = minOf(
            1f,
            resources.displayMetrics.widthPixels * 0.95f / requestedWidth,
            resources.displayMetrics.heightPixels * 0.80f / requestedHeight,
        )
        val sizeWidth = (requestedWidth * fit).roundToInt().coerceAtLeast(1)
        val sizeHeight = (requestedHeight * fit).roundToInt().coerceAtLeast(1)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val params = baseParams(sizeWidth, sizeHeight).apply {
            gravity = Gravity.TOP or Gravity.START
            val availableX = (resources.displayMetrics.widthPixels - sizeWidth).coerceAtLeast(0)
            val availableY = (resources.displayMetrics.heightPixels - sizeHeight).coerceAtLeast(0)
            x = selection.normalizedX?.let { (it * availableX).roundToInt() }
                ?: prefs.getInt(KEY_X, availableX)
            y = selection.normalizedY?.let { (it * availableY).roundToInt() }
                ?: prefs.getInt(KEY_Y, (resources.displayMetrics.heightPixels - sizeHeight - dp(96)).coerceAtLeast(0))
        }
        val view = PetSpriteView(
            context = this,
            atlas = newAtlas,
            onInteraction = { gesture, region ->
                when (petOverlayGestureAction(gesture)) {
                    PetOverlayGestureAction.DIALOGUE -> showPetOverlay(quickMenu = false)
                    PetOverlayGestureAction.QUICK_MENU -> showPetOverlay(quickMenu = true)
                    PetOverlayGestureAction.MODEL_RESPONSE -> submitTouchInteraction(gesture, region)
                    PetOverlayGestureAction.LOCAL_FEEDBACK -> {
                        spriteView?.showLocalFeedback()
                        playLocalInteraction(region)
                    }
                }
            },
            onDrag = { event ->
                params.x = (params.x + event.deltaXpx).coerceIn(0, (resources.displayMetrics.widthPixels - sizeWidth).coerceAtLeast(0))
                params.y = (params.y + event.deltaYpx).coerceIn(0, (resources.displayMetrics.heightPixels - sizeHeight).coerceAtLeast(0))
                spriteView?.let { runCatching { windowManager.updateViewLayout(it, params) } }
                updateBubblePosition(params)
                if (event.finished) {
                    behavior.submit(PetBehaviorIntent.ClearSource(PetActionSource.DRAG))
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply()
                    val maxX = (resources.displayMetrics.widthPixels - sizeWidth).coerceAtLeast(1)
                    val maxY = (resources.displayMetrics.heightPixels - sizeHeight).coerceAtLeast(1)
                    val updatedSelection = selection.copy(
                        normalizedX = params.x.toFloat() / maxX,
                        normalizedY = params.y.toFloat() / maxY,
                    ).normalized()
                    configuredSelection = updatedSelection
                    scope.launch {
                        settingsStore.update { current ->
                            val currentSelection = current.petOverlaySelection
                            if (currentSelection?.ownerAssistantId == updatedSelection.ownerAssistantId &&
                                currentSelection.privilegedConversationId == updatedSelection.privilegedConversationId
                            ) {
                                current.copy(petOverlaySelection = updatedSelection)
                            } else {
                                current
                            }
                        }
                    }
                } else {
                    val horizontal = kotlin.math.abs(event.horizontalSpeedDpPerSecond)
                    val vertical = kotlin.math.abs(event.verticalSpeedDpPerSecond)
                    if (horizontal >= DRAG_SPEED_THRESHOLD_DP_PER_SECOND && horizontal >= vertical * DRAG_HORIZONTAL_DOMINANCE) {
                        behavior.submit(
                            PetBehaviorIntent.Operational(
                                action = if (event.horizontalSpeedDpPerSecond >= 0) {
                                    CorePetActions.MOVE_RIGHT
                                } else {
                                    CorePetActions.MOVE_LEFT
                                },
                                source = PetActionSource.DRAG,
                                priority = PetBehaviorPriority.DRAG,
                            ),
                        )
                    } else {
                        behavior.submit(PetBehaviorIntent.ClearSource(PetActionSource.DRAG))
                    }
                }
            },
            headBoundary = selection.headBoundary,
            bodyBoundary = selection.bodyBoundary,
            defaultAnimationFps = config.animationFps,
        )
        // Add and initialise the new view before touching the old one. A malformed package can
        // therefore never make a selected, working character disappear mid-task.
        runCatching { windowManager.addView(view, params) }.getOrElse {
            newAtlas.close()
            return false
        }
        val oldView = spriteView
        val oldPlaceholder = placeholderView
        val oldAtlas = atlas
        renderer?.close()
        renderer = when (profile.rendererType) {
            "composite_sprite" -> PetRendererFactory.createCompositeSprite(view, profile.capabilities)
            else -> PetRendererFactory.createCodexSprite(view, profile.capabilities)
        }
        spriteView = view
        placeholderView = null
        atlas = newAtlas
        spriteParams = params
        bubbleView?.let { updateBubblePosition(params) }
        oldView?.pauseAnimation()
        oldView?.let { runCatching { windowManager.removeViewImmediate(it) } }
        oldPlaceholder?.let { runCatching { windowManager.removeViewImmediate(it) } }
        oldAtlas?.close()
        activeProfile = profile
        view.setStatusBadge(currentStatusBadge)
        behavior.updateProfile(profile)
        runtimeDiagnostics.updateProfile(profile)
        idlePool.updateProfile(profile)
        renderer?.render(behavior.state.value)
        view.resumeAnimation()
        return true
    }

    private fun showPlaceholder() {
        val image = ImageView(this).apply {
            setImageDrawable(applicationInfo.loadIcon(packageManager))
            alpha = 0.55f
            isClickable = false
        }
        val params = baseParams(dp(64), dp(64)).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            gravity = Gravity.BOTTOM or Gravity.END
            x = dp(16)
            y = dp(96)
        }
        windowManager.addView(image, params)
        placeholderView = image
    }

    private fun renderBubble(text: String?) {
        if (text.isNullOrBlank()) {
            bubbleView?.let { runCatching { windowManager.removeViewImmediate(it) } }
            bubbleView = null
            bubbleParams = null
            return
        }
        bubbleView?.let { it.text = text; return }
        val bubble = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 12f
            maxWidth = dp(320)
            maxLines = 8
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = GradientDrawable().apply {
                setColor(0xDD202020.toInt())
                cornerRadius = dp(16).toFloat()
            }
            setOnClickListener { showPetOverlay(quickMenu = false) }
        }
        val params = baseParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        spriteParams?.let { sprite ->
            params.gravity = Gravity.TOP or Gravity.START
            params.x = sprite.x
            params.y = (sprite.y - dp(48)).coerceAtLeast(0)
        }
        windowManager.addView(bubble, params)
        bubbleView = bubble
        bubbleParams = params
    }

    private fun showPetOverlay(quickMenu: Boolean) {
        val assistant = configuredAssistant ?: return
        val conversationId = assistant.privilegedConversationId ?: return
        val view = dialogueOverlay ?: PetDialogueOverlayView(
            context = this,
            onSend = ::submitOverlayText,
            onHandoff = ::handoffOverlayText,
            onConfirmHandoff = ::confirmHandoffDraft,
            onDismissHandoff = ::dismissHandoffDraft,
            onQuickAction = { action ->
                showPetOverlay(quickMenu = false)
                when (action) {
                    PetQuickAction.FORTUNE -> submitOverlayText("用桌宠的口吻说一句今天的运势，轻松娱乐即可。")
                    PetQuickAction.JOKE -> submitOverlayText("讲一个简短、友善的笑话。")
                    PetQuickAction.WEATHER -> handoffOverlayText("请查询我当前位置今天的天气，并给出简短建议。")
                }
            },
            onClose = ::closePetOverlay,
        ).also { created ->
            val width = minOf(resources.displayMetrics.widthPixels - dp(24), dp(520))
            val params = baseParams(width.coerceAtLeast(dp(280)), WindowManager.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                x = 0
                y = dp(22)
                flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }
            windowManager.addView(created, params)
            dialogueOverlay = created
            dialogueObservationJob?.cancel()
            dialogueObservationJob = scope.launch {
                combine(
                    dialogueRepository.observeActive(assistant.id.toString(), conversationId.toString()),
                    dialogueRepository.observePendingHandoffs(assistant.id.toString()),
                ) { active, pending -> active to pending }
                    .collectLatest { (active, pending) ->
                        created.renderTurns(
                            active?.turns.orEmpty().map { turn ->
                                PetOverlayTurnUi(
                                    userText = when (turn.inputKind) {
                                        PetDialogueInputKind.HANDOFF_RESULT.name -> null
                                        PetDialogueInputKind.TOUCH.name -> "触摸互动"
                                        else -> turn.userText
                                    },
                                    assistantText = turn.assistantText,
                                )
                            },
                        )
                        val handoff = pending.firstOrNull {
                            it.privilegedConversationId == conversationId.toString()
                        }
                        created.renderHandoff(
                            handoff?.let {
                                PetOverlayHandoffUi(
                                    requestId = it.requestId,
                                    stateVersion = it.stateVersion,
                                    title = it.title,
                                    request = it.request,
                                    submitted = it.status != PetHandoffStatus.DRAFT.name,
                                )
                            },
                        )
                    }
            }
        }
        if (quickMenu) {
            view.showQuickMenu(assistant.name)
            view.setStatus("选择后会在桌宠小窗中继续")
        } else {
            view.showDialogue(assistant.name)
            view.setStatus(
                currentStatusBubble ?: if (sidecarAllowed) "可以和桌宠聊天" else "第二用户正在处理任务",
            )
            view.postDelayed({
                val input = view.focusInput()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }, 160L)
        }
        refreshVisibility()
    }

    private fun playLocalInteraction(region: me.rerere.rikkahub.pet.PetBodyRegion) {
        if (!sidecarAllowed) return
        behavior.submit(
            PetBehaviorIntent.OneShot(
                action = localAction(region),
                source = PetActionSource.TOUCH,
                priority = PetBehaviorPriority.TOUCH,
                minDurationMs = TOUCH_MIN_DURATION_MS,
                maxDurationMs = TOUCH_LOCAL_MAX_DURATION_MS,
            ),
        )
    }

    /**
     * A tap is a tiny sidecar turn, never a main-chat command. It uses the same low-priority,
     * zero-tool pet slot as typed pet dialogue and deliberately forces SUGGEST_ONLY so a casual
     * touch cannot create a handoff or inherit a tool approval.
     */
    private fun submitTouchInteraction(
        gesture: String,
        region: me.rerere.rikkahub.pet.PetBodyRegion,
    ) {
        val assistant = configuredAssistant ?: return
        val conversationId = assistant.privilegedConversationId ?: return
        spriteView?.showLocalFeedback()
        playLocalInteraction(region)
        val now = System.currentTimeMillis()
        if (!sidecarAllowed || interactionJob?.isActive == true ||
            now - lastTouchModelRequestAtMs < TOUCH_MODEL_COOLDOWN_MS
        ) {
            return
        }
        lastTouchModelRequestAtMs = now

        val payload = PetInteractionPayload(type = gesture, region = region)
        val interactionJson = json.encodeToString(PetInteractionPayload.serializer(), payload)
        transientTouchBubble = "…"
        behavior.submit(
            PetBehaviorIntent.Operational(
                action = CorePetActions.WAIT,
                source = PetActionSource.DIALOGUE,
                priority = PetBehaviorPriority.MODEL,
            ),
        )
        refreshBubble()
        interactionJob = scope.launch {
            try {
                val slot = chatService.runPetInteraction(conversationId) {
                    val current = dialogueRepository.observeActive(
                        assistant.id.toString(),
                        conversationId.toString(),
                    ).first()
                    val history = current?.turns.orEmpty().map { turn ->
                        PetDialogueTurnEntityView(
                            userInput = turn.userText ?: turn.interactionJson.orEmpty(),
                            assistantText = turn.assistantText,
                        )
                    }
                    val persona = personaSource.observe(assistant.id).first()
                    when (
                        val generated = dialogueGenerator.generate(
                            persona = persona,
                            history = history,
                            input = touchPrompt(region),
                            handoffMode = PetHandoffMode.SUGGEST_ONLY,
                        )
                    ) {
                        is PetGenerationResult.Success -> {
                            dialogueRepository.append(
                                assistant.id.toString(),
                                conversationId.toString(),
                                PetDialogueTurnDraft(
                                    inputKind = PetDialogueInputKind.TOUCH,
                                    interactionJson = interactionJson,
                                    assistantText = generated.text,
                                    action = generated.action,
                                ),
                            )
                            showTouchResponse(generated.text, generated.visualHint.toSemanticAction())
                        }

                        PetGenerationResult.LocalAnimationOnly -> {
                            dialogueRepository.append(
                                assistant.id.toString(),
                                conversationId.toString(),
                                PetDialogueTurnDraft(
                                    inputKind = PetDialogueInputKind.TOUCH,
                                    interactionJson = interactionJson,
                                ),
                            )
                            showTouchResponse(touchFallback(region), localAction(region))
                        }

                        is PetGenerationResult.Failure -> {
                            showTouchResponse(touchFallback(region), localAction(region))
                        }
                    }
                }
                if (slot is PetInteractionSlotResult.Busy) {
                    showTouchResponse("我先陪着你，等一下再聊呀。", localAction(region))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                showTouchResponse(touchFallback(region), localAction(region))
            }
        }
    }

    private fun showTouchResponse(text: String, action: PetActionId) {
        behavior.submit(PetBehaviorIntent.ClearSource(PetActionSource.DIALOGUE))
        transientTouchBubble = PetBubbleSanitizer.sanitize(text)
        if (sidecarAllowed) {
            behavior.submit(
                PetBehaviorIntent.OneShot(
                    action = action,
                    source = PetActionSource.TOUCH,
                    priority = PetBehaviorPriority.TOUCH,
                    minDurationMs = TOUCH_MIN_DURATION_MS,
                    maxDurationMs = TOUCH_RESPONSE_ACTION_MAX_DURATION_MS,
                ),
            )
        }
        refreshBubble()
        touchBubbleJob?.cancel()
        touchBubbleJob = scope.launch {
            delay(TOUCH_RESPONSE_BUBBLE_MS)
            transientTouchBubble = null
            refreshBubble()
        }
    }

    private fun touchPrompt(region: me.rerere.rikkahub.pet.PetBodyRegion): String = when (region) {
        me.rerere.rikkahub.pet.PetBodyRegion.HEAD ->
            "用户轻轻摸了摸你的头。只用一句温柔、简短的话回应这次互动；不要转交任务。"
        me.rerere.rikkahub.pet.PetBodyRegion.BODY ->
            "用户轻轻碰了碰你。只用一句自然、简短的话回应这次互动；不要转交任务。"
        me.rerere.rikkahub.pet.PetBodyRegion.FEET ->
            "用户轻轻碰了碰你的脚边。只用一句俏皮、简短的话回应这次互动；不要转交任务。"
        me.rerere.rikkahub.pet.PetBodyRegion.UNKNOWN ->
            "用户刚刚触摸了你。只用一句简短的话回应这次互动；不要转交任务。"
    }

    private fun touchFallback(region: me.rerere.rikkahub.pet.PetBodyRegion): String = when (region) {
        me.rerere.rikkahub.pet.PetBodyRegion.HEAD -> "摸摸头，我在呢。"
        me.rerere.rikkahub.pet.PetBodyRegion.BODY -> "嘿嘿，我收到啦。"
        me.rerere.rikkahub.pet.PetBodyRegion.FEET -> "轻一点，我会痒呀。"
        me.rerere.rikkahub.pet.PetBodyRegion.UNKNOWN -> "我在呢。"
    }

    private fun localAction(region: me.rerere.rikkahub.pet.PetBodyRegion): PetActionId {
        val key = when (region) {
            me.rerere.rikkahub.pet.PetBodyRegion.HEAD -> "head"
            me.rerere.rikkahub.pet.PetBodyRegion.BODY -> "body"
            me.rerere.rikkahub.pet.PetBodyRegion.FEET -> "feet"
            me.rerere.rikkahub.pet.PetBodyRegion.UNKNOWN -> return CorePetActions.IDLE
        }
        return activeProfile?.touchMappings?.get(key) ?: when (region) {
            me.rerere.rikkahub.pet.PetBodyRegion.HEAD -> CorePetActions.TOUCH_HEAD
            me.rerere.rikkahub.pet.PetBodyRegion.BODY -> CorePetActions.TOUCH_BODY
            me.rerere.rikkahub.pet.PetBodyRegion.FEET -> CorePetActions.TOUCH_FEET
            me.rerere.rikkahub.pet.PetBodyRegion.UNKNOWN -> CorePetActions.IDLE
        }
    }

    private fun closePetOverlay() {
        dialogueObservationJob?.cancel()
        dialogueObservationJob = null
        dialogueOverlay?.let { view ->
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(view.windowToken, 0)
            runCatching { windowManager.removeViewImmediate(view) }
        }
        dialogueOverlay = null
    }

    private fun submitOverlayText(text: String) {
        val assistant = configuredAssistant ?: return
        val conversationId = assistant.privilegedConversationId ?: return
        if (!sidecarAllowed) {
            dialogueOverlay?.restoreInput(text)
            dialogueOverlay?.setStatus("第二用户正在处理任务；可以改用“交给第二用户”排队。", error = true)
            return
        }
        if (interactionJob?.isActive == true) {
            dialogueOverlay?.restoreInput(text)
            dialogueOverlay?.setStatus("上一条消息还在处理中。", error = true)
            return
        }
        dialogueOverlay?.setSending(true)
        dialogueOverlay?.setStatus("桌宠正在回应……")
        behavior.submit(
            PetBehaviorIntent.Operational(
                action = CorePetActions.REVIEW,
                source = PetActionSource.DIALOGUE,
                priority = PetBehaviorPriority.MODEL,
            ),
        )
        interactionJob = scope.launch {
            try {
                var autoHandoffId: String? = null
                val slot = chatService.runPetInteraction(conversationId) {
                    val current = dialogueRepository.observeActive(
                        assistant.id.toString(),
                        conversationId.toString(),
                    ).first()
                    val history = current?.turns.orEmpty().map { turn ->
                        PetDialogueTurnEntityView(
                            userInput = turn.userText ?: turn.interactionJson.orEmpty(),
                            assistantText = turn.assistantText,
                        )
                    }
                    val mode = runCatching { PetHandoffMode.valueOf(assistant.petHandoffMode) }
                        .getOrDefault(PetHandoffMode.CONFIRM)
                    val persona = personaSource.observe(assistant.id).first()
                    when (val generated = dialogueGenerator.generate(persona, history, text, mode)) {
                        is PetGenerationResult.Success -> {
                            val updated = dialogueRepository.append(
                                assistant.id.toString(),
                                conversationId.toString(),
                                PetDialogueTurnDraft(
                                    inputKind = PetDialogueInputKind.TEXT,
                                    userText = text,
                                    assistantText = generated.text,
                                    action = generated.action,
                                    handoff = generated.handoff,
                                ),
                            )
                            behavior.submit(PetBehaviorIntent.ClearSource(PetActionSource.DIALOGUE))
                            behavior.submit(
                                PetBehaviorIntent.OneShot(
                                    action = generated.visualHint.toSemanticAction(),
                                    source = PetActionSource.DIALOGUE,
                                    priority = PetBehaviorPriority.TOUCH,
                                    minDurationMs = TOUCH_MIN_DURATION_MS,
                                    maxDurationMs = TOUCH_RESPONSE_ACTION_MAX_DURATION_MS,
                                ),
                            )
                            if (mode == PetHandoffMode.AUTO) {
                                autoHandoffId = updated.turns.lastOrNull()?.handoffRequestId
                            } else if (generated.handoff != null) {
                                dialogueOverlay?.setStatus("已整理转交草稿；需要时可直接点“交给第二用户”。")
                            } else {
                                dialogueOverlay?.setStatus("桌宠已回复")
                            }
                        }
                        PetGenerationResult.LocalAnimationOnly -> {
                            dialogueRepository.append(
                                assistant.id.toString(),
                                conversationId.toString(),
                                PetDialogueTurnDraft(PetDialogueInputKind.TEXT, userText = text),
                            )
                            behavior.submit(PetBehaviorIntent.ClearSource(PetActionSource.DIALOGUE))
                        }
                        is PetGenerationResult.Failure -> {
                            behavior.submit(PetBehaviorIntent.ClearSource(PetActionSource.DIALOGUE))
                            dialogueOverlay?.restoreInput(text)
                            dialogueOverlay?.setStatus(
                                me.rerere.rikkahub.pet.petGenerationErrorMessage(generated.code),
                                error = true,
                            )
                        }
                    }
                }
                if (slot is PetInteractionSlotResult.Busy) {
                    behavior.submit(PetBehaviorIntent.ClearSource(PetActionSource.DIALOGUE))
                    dialogueOverlay?.restoreInput(text)
                    dialogueOverlay?.setStatus("第二用户主任务已开始，这条消息没有发送。", error = true)
                } else {
                    autoHandoffId?.let { requestId ->
                        val result = handoffCoordinator.submit(requestId, automatic = true)
                        dialogueOverlay?.setStatus(
                            if (result is PetHandoffSubmitResult.Submitted) {
                                "已自动交给第二用户处理"
                            } else {
                                "自动转交暂未成功"
                            },
                            error = result !is PetHandoffSubmitResult.Submitted,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                behavior.submit(PetBehaviorIntent.ClearSource(PetActionSource.DIALOGUE))
                dialogueOverlay?.restoreInput(text)
                dialogueOverlay?.setStatus("桌宠回复失败，请重试。", error = true)
            } finally {
                dialogueOverlay?.setSending(false)
            }
        }
    }

    private fun handoffOverlayText(text: String) {
        val assistant = configuredAssistant ?: return
        val conversationId = assistant.privilegedConversationId ?: return
        if (interactionJob?.isActive == true) {
            dialogueOverlay?.restoreInput(text)
            dialogueOverlay?.setStatus("上一条消息还在处理中。", error = true)
            return
        }
        dialogueOverlay?.setSending(true)
        dialogueOverlay?.setStatus("正在交给第二用户……")
        interactionJob = scope.launch {
            try {
                val safeRequest = PetBubbleSanitizer.sanitizeDraft(text).take(2_000)
                val updated = dialogueRepository.append(
                    assistant.id.toString(),
                    conversationId.toString(),
                    PetDialogueTurnDraft(
                        inputKind = PetDialogueInputKind.TEXT,
                        userText = text,
                        assistantText = "我把这件事交给第二用户处理。",
                        action = PetAction.RUNNING,
                        handoff = PetHandoffDraft(
                            mode = PetHandoffMode.CONFIRM,
                            title = PetBubbleSanitizer.sanitize(text).take(80),
                            request = safeRequest,
                        ),
                    ),
                )
                val requestId = updated.turns.lastOrNull()?.handoffRequestId
                val result = requestId?.let { handoffCoordinator.submit(it, automatic = false) }
                dialogueOverlay?.setStatus(
                    if (result is PetHandoffSubmitResult.Submitted) {
                        "已交给第二用户，会按普通任务排队"
                    } else {
                        "转交暂未成功，请重试"
                    },
                    error = result !is PetHandoffSubmitResult.Submitted,
                )
                if (result !is PetHandoffSubmitResult.Submitted) dialogueOverlay?.restoreInput(text)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                dialogueOverlay?.restoreInput(text)
                dialogueOverlay?.setStatus("转交暂未成功，请重试。", error = true)
            } finally {
                dialogueOverlay?.setSending(false)
            }
        }
    }

    private fun confirmHandoffDraft(requestId: String) {
        dialogueOverlay?.setStatus("正在交给第二用户……")
        scope.launch {
            val result = handoffCoordinator.submit(requestId, automatic = false)
            dialogueOverlay?.setStatus(
                if (result is PetHandoffSubmitResult.Submitted) {
                    "已交给第二用户，完成后会回到桌宠"
                } else {
                    "转交暂未成功，请重试"
                },
                error = result !is PetHandoffSubmitResult.Submitted,
            )
        }
    }

    private fun dismissHandoffDraft(requestId: String, stateVersion: Long) {
        scope.launch {
            val dismissed = handoffCoordinator.dismiss(requestId, stateVersion)
            dialogueOverlay?.setStatus(
                if (dismissed) "已拒绝这份转交草稿" else "草稿状态已变化，请重试",
                error = !dismissed,
            )
        }
    }

    private fun refreshBubble() {
        renderBubble(transientHandoffBubble ?: transientTouchBubble ?: currentStatusBubble)
    }

    private fun updateBubblePosition(sprite: WindowManager.LayoutParams) {
        val bubble = bubbleView ?: return
        val params = bubbleParams ?: return
        params.x = sprite.x
        params.y = (sprite.y - dp(48)).coerceAtLeast(0)
        runCatching { windowManager.updateViewLayout(bubble, params) }
    }

    private fun refreshVisibility() {
        val power = getSystemService(POWER_SERVICE) as PowerManager
        val keyguard = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        val visible = power.isInteractive && !keyguard.isDeviceLocked && !keyguard.isKeyguardLocked &&
            !TrustedApprovalSurfaceVisibility.visible.value
        val visibility = if (visible) View.VISIBLE else View.GONE
        spriteView?.visibility = visibility
        placeholderView?.visibility = visibility
        bubbleView?.visibility = visibility
        dialogueOverlay?.visibility = visibility
        if (visible) spriteView?.resumeAnimation() else spriteView?.pauseAnimation()
        refreshIdlePool(screenVisible = visible)
    }

    private fun refreshIdlePool(screenVisible: Boolean? = null) {
        val power = getSystemService(POWER_SERVICE) as PowerManager
        val battery = getSystemService(BATTERY_SERVICE) as BatteryManager
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        idlePool.setConditions(
            enabledByUser = configuredSelection?.idlePoolEnabled == true,
            screenVisible = screenVisible ?: (spriteView?.visibility == View.VISIBLE),
            trueIdle = lastPresentationStatus == me.rerere.rikkahub.assistant.SecondUserPresentationStatus.IDLE,
            powerSave = power.isPowerSaveMode,
            lowBattery = level in 0..15,
        )
    }

    private fun removeWindows(cancelTransientWork: Boolean) {
        spriteView?.pauseAnimation()
        closePetOverlay()
        listOfNotNull<View>(spriteView, placeholderView, bubbleView).forEach { view ->
            runCatching { windowManager.removeViewImmediate(view) }
        }
        spriteView = null
        placeholderView = null
        bubbleView = null
        spriteParams = null
        bubbleParams = null
        renderer?.close()
        renderer = null
        atlas?.close()
        atlas = null
        activeProfile = null
        runtimeDiagnostics.clearRenderer()
        if (cancelTransientWork) {
            interactionJob?.cancel()
            interactionJob = null
            handoffBubbleJob?.cancel()
            handoffBubbleJob = null
            transientHandoffBubble = null
            touchBubbleJob?.cancel()
            touchBubbleJob = null
            transientTouchBubble = null
        }
    }

    private fun baseParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width,
        height,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        },
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    )

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "桌宠", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_STOP = "me.rerere.rikkahub.pet.STOP"
        const val ACTION_RELOAD = "me.rerere.rikkahub.pet.RELOAD"
        const val ACTION_DIALOGUE_VISUAL = "me.rerere.rikkahub.pet.DIALOGUE_VISUAL"
        const val ACTION_HANDOFF_VISUAL = "me.rerere.rikkahub.pet.HANDOFF_VISUAL"
        private const val EXTRA_VISUAL_HINT = "visual_hint"
        private const val TAG = "DesktopPetService"
        private const val CHANNEL_ID = "desktop_pet"
        private const val NOTIFICATION_ID = 7301
        private const val PREFS = "desktop_pet_position"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val STARTUP_CONFIGURATION_GRACE_MS = 3_000L
        private const val MIN_PET_SCALE = 0.05f
        private const val MAX_PET_SCALE = 2.0f
        private const val MIN_ANIMATION_FPS = 4
        private const val MAX_ANIMATION_FPS = 12
        private const val HANDOFF_RESULT_BUBBLE_MS = 15_000L
        private const val HANDOFF_RESULT_REPLAY_WINDOW_MS = 60_000L
        private const val TOUCH_RESPONSE_BUBBLE_MS = 10_000L
        private const val TOUCH_MIN_DURATION_MS = 900L
        private const val TOUCH_LOCAL_MAX_DURATION_MS = 1_400L
        private const val TOUCH_RESPONSE_ACTION_MAX_DURATION_MS = 5_000L
        private const val TOUCH_MODEL_COOLDOWN_MS = 3_000L
        private const val DRAG_SPEED_THRESHOLD_DP_PER_SECOND = 280f
        private const val DRAG_HORIZONTAL_DOMINANCE = 1.35f

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DesktopPetService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, DesktopPetService::class.java).setAction(ACTION_STOP))
        }

        fun reload(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DesktopPetService::class.java).setAction(ACTION_RELOAD))
        }

        fun showDialogueVisual(context: Context, hint: PetVisualHint) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DesktopPetService::class.java)
                    .setAction(ACTION_DIALOGUE_VISUAL)
                    .putExtra(EXTRA_VISUAL_HINT, hint.name),
            )
        }

        fun showHandoffVisual(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DesktopPetService::class.java).setAction(ACTION_HANDOFF_VISUAL),
            )
        }
    }
}

private data class PetRenderConfig(
    val packageId: String?,
    val profileId: String?,
    val scale: Float,
    val animationFps: Int,
)
