package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.pet.ActivePetDialogue
import me.rerere.rikkahub.pet.PetAction
import me.rerere.rikkahub.pet.PetArchiveResult
import me.rerere.rikkahub.pet.PetBubbleSanitizer
import me.rerere.rikkahub.pet.PetDialogueGenerator
import me.rerere.rikkahub.pet.PetDialogueInputKind
import me.rerere.rikkahub.pet.PetDialogueRepository
import me.rerere.rikkahub.pet.PetDialogueTurnDraft
import me.rerere.rikkahub.pet.PetDialogueTurnEntityView
import me.rerere.rikkahub.pet.PetGenerationResult
import me.rerere.rikkahub.pet.PetHandoffDraft
import me.rerere.rikkahub.pet.PetHandoffCoordinator
import me.rerere.rikkahub.pet.PetHandoffMode
import me.rerere.rikkahub.pet.PetHandoffStatus
import me.rerere.rikkahub.pet.PetHandoffSubmitResult
import me.rerere.rikkahub.pet.PetPersonaSource
import me.rerere.rikkahub.pet.resolvePetOverlaySelection
import me.rerere.rikkahub.pet.petGenerationErrorMessage
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.chat.PetInteractionSlotResult
import org.koin.compose.koinInject

@Composable
fun PetDialogueCard(
    assistant: Assistant,
    conversationId: Uuid,
    mainBusy: Boolean,
    modifier: Modifier = Modifier,
) {
    val settingsStore: SettingsStore = koinInject()
    val context = LocalContext.current.applicationContext
    val settings by settingsStore.settingsFlow.collectAsState(initial = Settings.dummy())
    val selection = settings.resolvePetOverlaySelection()?.selection
    if (selection?.ownerAssistantId != assistant.id || selection.privilegedConversationId != conversationId) return
    val repository: PetDialogueRepository = koinInject()
    val generator: PetDialogueGenerator = koinInject()
    val personaSource: PetPersonaSource = koinInject()
    val handoffCoordinator: PetHandoffCoordinator = koinInject()
    val chatService: ChatService = koinInject()
    val scope = rememberCoroutineScope()
    val assistantId = assistant.id.toString()
    val conversationKey = conversationId.toString()
    val active by remember(assistantId, conversationKey) {
        repository.observeActive(assistantId, conversationKey)
    }.collectAsState(initial = null)
    val pending by remember(assistantId) {
        repository.observePendingHandoffs(assistantId)
    }.collectAsState(initial = emptyList())
    var expanded by remember { mutableStateOf(false) }
    var showDiary by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var localNotice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(assistantId, conversationKey) {
        repository.ensureActive(assistantId, conversationKey)
    }
    val handOffDirectly: () -> Unit = {
        val submitted = input.trim()
        if (submitted.isNotBlank() && !sending) {
            input = ""
            sending = true
            localError = null
            localNotice = null
            scope.launch {
                try {
                    val safeRequest = PetBubbleSanitizer.sanitizeDraft(submitted).take(2_000)
                    val configuredMode = runCatching { PetHandoffMode.valueOf(assistant.petHandoffMode) }
                        .getOrDefault(PetHandoffMode.CONFIRM)
                    val draftMode = configuredMode.takeUnless { it == PetHandoffMode.SUGGEST_ONLY }
                        ?: PetHandoffMode.CONFIRM
                    val updated = repository.append(
                        assistantId,
                        conversationKey,
                        PetDialogueTurnDraft(
                            inputKind = PetDialogueInputKind.TEXT,
                            userText = submitted,
                            assistantText = "我现在把这件事交给第二用户处理。",
                            action = PetAction.RUNNING,
                            handoff = PetHandoffDraft(
                                mode = draftMode,
                                title = PetBubbleSanitizer.sanitize(submitted).take(80),
                                request = safeRequest,
                            ),
                        ),
                    )
                    val requestId = updated.turns.lastOrNull()?.handoffRequestId
                    val result = requestId?.let { handoffCoordinator.submit(it, automatic = false) }
                    if (result is PetHandoffSubmitResult.Submitted) {
                        me.rerere.rikkahub.pet.overlay.DesktopPetService.showHandoffVisual(context)
                        localNotice = "已交给第二用户，会按普通任务排队并继续使用原有审批规则。"
                    } else {
                        input = submitted
                        localError = "转交暂未成功，请重试。"
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    input = submitted
                    localError = "转交暂未成功，请重试。"
                } finally {
                    sending = false
                }
            }
        }
    }

    Card(
        onClick = { expanded = true },
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
                Text(
                    text = assistant.name.trim().take(1).ifBlank { "宠" },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(assistant.name.ifBlank { "桌宠" }, style = MaterialTheme.typography.titleSmall)
                val latest = active?.turns?.takeLast(2).orEmpty()
                Text(
                    text = latest.lastOrNull()?.assistantText?.ifBlank { null }
                        ?: if (mainBusy) "主任务进行中，触摸只做本地反馈" else "点这里聊两句",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${active?.turns?.size ?: 0}/20 轮", style = MaterialTheme.typography.labelSmall)
                if (pending.any { it.status == PetHandoffStatus.DRAFT.name }) {
                    Text("待转交", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }

    if (expanded) {
        ModalBottomSheet(onDismissRequest = { expanded = false }) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("${assistant.name.ifBlank { "桌宠" }} · 当前短会话", style = MaterialTheme.typography.titleMedium)
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                    items(active?.turns.orEmpty(), key = { it.turnId }) { turn ->
                        Column(modifier = Modifier.padding(vertical = 5.dp)) {
                            if (turn.inputKind == PetDialogueInputKind.HANDOFF_RESULT.name) {
                                turn.assistantText?.let {
                                    Text("第二用户：$it", style = MaterialTheme.typography.bodyMedium)
                                }
                            } else {
                                Text("你：${turn.userText ?: "[${turn.inputKind}]"}", style = MaterialTheme.typography.bodySmall)
                                turn.assistantText?.let {
                                    Text("${assistant.name}：$it", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
                localNotice?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                localError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                OutlinedTextField(
                    value = input,
                    onValueChange = { value -> if (value.codePointCount(0, value.length) <= 500) input = value },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("最多500字") },
                    enabled = !sending,
                    maxLines = 4,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = input.isNotBlank() && !mainBusy && !sending,
                        onClick = {
                            val submitted = input.trim()
                            input = ""
                            sending = true
                            localError = null
                            localNotice = null
                            scope.launch {
                                try {
                                    var autoHandoffId: String? = null
                                    val slot = chatService.runPetInteraction(conversationId) {
                                        val persona = personaSource.observe(assistant.id).first()
                                        val history = active?.turns.orEmpty().map { turn ->
                                            PetDialogueTurnEntityView(
                                                userInput = turn.userText ?: turn.interactionJson.orEmpty(),
                                                assistantText = turn.assistantText,
                                            )
                                        }
                                        val mode = runCatching { PetHandoffMode.valueOf(assistant.petHandoffMode) }
                                            .getOrDefault(PetHandoffMode.CONFIRM)
                                        when (val result = generator.generate(persona, history, submitted, mode)) {
                                            is PetGenerationResult.Success -> {
                                                val updated = repository.append(
                                                    assistantId,
                                                    conversationKey,
                                                    PetDialogueTurnDraft(
                                                        inputKind = PetDialogueInputKind.TEXT,
                                                        userText = submitted,
                                                        assistantText = result.text.ifBlank { null },
                                                        action = result.action,
                                                        handoff = result.handoff,
                                                    ),
                                                )
                                                me.rerere.rikkahub.pet.overlay.DesktopPetService.showDialogueVisual(
                                                    context,
                                                    result.visualHint,
                                                )
                                                if (result.handoff != null) {
                                                    if (mode == PetHandoffMode.AUTO) {
                                                        autoHandoffId = updated.turns.lastOrNull()?.handoffRequestId
                                                    } else {
                                                        localNotice = "桌宠已整理成转交草稿，你可以检查后交给第二用户。"
                                                    }
                                                }
                                            }
                                            PetGenerationResult.LocalAnimationOnly -> repository.append(
                                                assistantId,
                                                conversationKey,
                                                PetDialogueTurnDraft(PetDialogueInputKind.TEXT, userText = submitted),
                                            )
                                            is PetGenerationResult.Failure -> {
                                                input = submitted
                                                localError = petGenerationErrorMessage(result.code)
                                            }
                                        }
                                    }
                                    if (slot is PetInteractionSlotResult.Busy) {
                                        input = submitted
                                        localError = "主任务已开始，这条桌宠消息没有发送"
                                    } else {
                                        autoHandoffId?.let { requestId ->
                                            when (handoffCoordinator.submit(requestId, automatic = true)) {
                                                is PetHandoffSubmitResult.Submitted -> {
                                                    me.rerere.rikkahub.pet.overlay.DesktopPetService.showHandoffVisual(context)
                                                    localNotice = "桌宠已自动交给第二用户处理。"
                                                }
                                                else -> localError = "自动转交暂未成功，请在转交卡片中重试。"
                                            }
                                        }
                                    }
                                } finally {
                                    sending = false
                                }
                            }
                        },
                    ) { Text(if (sending) "回应中" else "发送") }
                    Button(
                        enabled = input.isNotBlank() && !sending,
                        onClick = handOffDirectly,
                    ) { Text("交给第二用户") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        scope.launch {
                            if (repository.archiveNow(assistantId, conversationKey) is PetArchiveResult.Empty) {
                                localError = "当前没有可保存的对白"
                            }
                        }
                    }) { Text("保存今天的对白") }
                    TextButton(onClick = { showDiary = true }) { Text("查看日记") }
                }
                pending.filter { it.status == PetHandoffStatus.DRAFT.name }.forEach { request ->
                    key(request.requestId) {
                        var handoffTitle by remember(request.stateVersion) { mutableStateOf(request.title) }
                        var handoffText by remember(request.stateVersion) { mutableStateOf(request.request) }
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("待转交（可编辑）", style = MaterialTheme.typography.titleSmall)
                                OutlinedTextField(
                                    value = handoffTitle,
                                    onValueChange = { handoffTitle = it.take(160) },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("任务标题") },
                                )
                                OutlinedTextField(
                                    value = handoffText,
                                    onValueChange = { handoffText = it.take(2_000) },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("任务草稿") },
                                    maxLines = 5,
                                )
                                Row {
                                    TextButton(onClick = {
                                        scope.launch {
                                            localError = null
                                            localNotice = null
                                            val saved = handoffCoordinator.editDraft(
                                                request.requestId,
                                                request.stateVersion,
                                                handoffTitle,
                                                handoffText,
                                            )
                                            if (saved) {
                                                localNotice = "草稿已保存"
                                            } else {
                                                localError = "草稿保存失败：状态已变化或任务不存在"
                                            }
                                        }
                                    }) { Text("保存草稿") }
                                    TextButton(onClick = {
                                        scope.launch {
                                            localError = null
                                            localNotice = null
                                            when (val result = handoffCoordinator.submit(request.requestId, false)) {
                                                is PetHandoffSubmitResult.Submitted ->
                                                    localNotice = "已转交，等待第二用户处理"
                                                is PetHandoffSubmitResult.Rejected ->
                                                    localError = "转交被拒绝：${result.code}"
                                                PetHandoffSubmitResult.Missing ->
                                                    localError = "转交失败：任务不存在或已被处理"
                                                PetHandoffSubmitResult.Conflict ->
                                                    localError = "转交失败：状态已变化，请刷新后重试"
                                                PetHandoffSubmitResult.Expired ->
                                                    localError = "转交失败：任务已过期"
                                                PetHandoffSubmitResult.RateLimited ->
                                                    localError = "转交失败：操作过于频繁，请稍后再试"
                                            }
                                        }
                                    }) { Text("转交") }
                                    TextButton(onClick = {
                                        scope.launch {
                                            localError = null
                                            localNotice = null
                                            val dismissed = handoffCoordinator.dismiss(
                                                request.requestId,
                                                request.stateVersion,
                                            )
                                            if (dismissed) {
                                                localNotice = "已拒绝该转交"
                                            } else {
                                                localError = "拒绝失败：状态已变化或任务不存在"
                                            }
                                        }
                                    }) { Text("拒绝") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDiary) PetDiaryDialog(assistantId = assistantId, onDismiss = { showDiary = false })
}
