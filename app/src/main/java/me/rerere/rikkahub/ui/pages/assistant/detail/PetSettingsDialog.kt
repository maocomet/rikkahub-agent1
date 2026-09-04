package me.rerere.rikkahub.ui.pages.assistant.detail

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.roundToInt
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.pet.PetHandoffMode
import me.rerere.rikkahub.pet.assets.AndroidPetImageProbe
import me.rerere.rikkahub.pet.assets.CodexPetPackageImporter
import me.rerere.rikkahub.pet.assets.CodexPetManifest
import me.rerere.rikkahub.pet.assets.PetPackageException
import me.rerere.rikkahub.pet.overlay.DesktopPetService
import me.rerere.rikkahub.ui.components.ui.Select

@Composable
fun PetSettingsDialog(
    assistant: Assistant,
    onDismiss: () -> Unit,
    onUpdate: (Assistant, afterUpdate: () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember(assistant) { mutableStateOf(assistant) }
    var status by remember { mutableStateOf<String?>(null) }
    var replacementUri by remember { mutableStateOf<Uri?>(null) }
    var showProfileEditor by remember { mutableStateOf(false) }
    var forceRendererReload by remember { mutableStateOf(false) }
    var installedPets by remember { mutableStateOf<List<CodexPetManifest>>(emptyList()) }

    suspend fun refreshInstalledPets() {
        installedPets = withContext(Dispatchers.IO) {
            listInstalledPetManifests(context.filesDir.resolve("pets"))
        }
    }

    LaunchedEffect(Unit) {
        refreshInstalledPets()
    }

    fun importPackage(uri: Uri, replace: Boolean) {
        scope.launch {
            status = "正在验证资源包…"
            val result = runCatching {
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input)
                    CodexPetPackageImporter(
                        petsRoot = context.filesDir.resolve("pets"),
                        imageProbe = AndroidPetImageProbe,
                    ).import(input, replaceExisting = replace)
                }
            }
            result.onSuccess { installed ->
                draft = draft.copy(petPackageId = installed.manifest.id)
                forceRendererReload = forceRendererReload || replace
                refreshInstalledPets()
                status = "已导入 ${installed.manifest.displayName}"
                replacementUri = null
            }.onFailure { error ->
                if (error is PetPackageException && error.code == "pet_id_exists" && !replace) {
                    replacementUri = uri
                    status = "同 ID 桌宠已存在，是否替换？"
                } else {
                    status = (error as? PetPackageException)?.code ?: "导入失败"
                }
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importPackage(it, false) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("第二用户桌宠") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (draft.privilegedConversationId == null) {
                    Text("必须先为这个助手配置固定的第二用户会话。", color = MaterialTheme.colorScheme.error)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text("启用桌宠")
                        Text("锁屏和熄屏时自动隐藏", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = draft.petEnabled,
                        enabled = draft.privilegedConversationId != null,
                        onCheckedChange = { draft = draft.copy(petEnabled = it) },
                    )
                }
                Text("桌宠角色", style = MaterialTheme.typography.labelLarge)
                if (installedPets.isNotEmpty()) {
                    val selectedManifest = installedPets.firstOrNull { manifest ->
                        manifest.id == draft.petPackageId
                    } ?: CodexPetManifest(
                        id = draft.petPackageId.orEmpty(),
                        displayName = draft.petPackageId ?: "请选择桌宠",
                    )
                    Select(
                        options = installedPets,
                        selectedOption = selectedManifest,
                        onOptionSelected = { manifest ->
                            if (draft.petPackageId != manifest.id) {
                                draft = draft.copy(petPackageId = manifest.id)
                                forceRendererReload = true
                                status = "已选择 ${manifest.displayName}，保存后立即切换"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        optionToString = { manifest -> manifest.displayName },
                    )
                    Text(
                        "点击当前角色可查看其余 ${installedPets.size} 个已安装桌宠",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text("尚未安装桌宠资源", style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = {
                        picker.launch(arrayOf("application/zip", "application/octet-stream"))
                    },
                ) {
                    Text("导入新的 .codex-pet.zip")
                }
                if (draft.petPackageId != null) {
                    TextButton(onClick = { showProfileEditor = true }) {
                        Text("编辑安全视觉动作")
                    }
                }
                Text("桌宠大小 ${(draft.petScale.coerceIn(0.05f, 2.0f) * 100).roundToInt()}%")
                Slider(
                    value = draft.petScale.coerceIn(0.05f, 2.0f),
                    onValueChange = { draft = draft.copy(petScale = it) },
                    valueRange = 0.05f..2.0f,
                    steps = 38,
                )
                Text("超出屏幕可用范围时会等比例缩小", style = MaterialTheme.typography.bodySmall)
                Text("动画速度 ${draft.petAnimationFps.coerceIn(4, 12)} 帧/秒")
                Slider(
                    value = draft.petAnimationFps.coerceIn(4, 12).toFloat(),
                    onValueChange = { draft = draft.copy(petAnimationFps = it.roundToInt()) },
                    valueRange = 4f..12f,
                    steps = 7,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text("空闲动作池")
                        Text("默认关闭；仅在亮屏、真正空闲且非省电/低电量时随机播放", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = draft.petIdlePoolEnabled,
                        onCheckedChange = { draft = draft.copy(petIdlePoolEnabled = it) },
                    )
                }
                OutlinedTextField(
                    value = draft.petSupplement.orEmpty(),
                    onValueChange = { draft = draft.copy(petSupplement = it.take(2_000)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("桌宠补充人物设定") },
                    maxLines = 4,
                )
                Text("触摸区域校准：头部 ${(draft.petHeadBoundary * 100).toInt()}%")
                Slider(
                    value = draft.petHeadBoundary,
                    onValueChange = { value ->
                        draft = draft.copy(
                            petHeadBoundary = value,
                            petBodyBoundary = draft.petBodyBoundary.coerceAtLeast(value + 0.1f),
                        )
                    },
                    valueRange = 0.15f..0.55f,
                )
                Text("身体结束 ${(draft.petBodyBoundary * 100).toInt()}%，以下为脚部")
                Slider(
                    value = draft.petBodyBoundary,
                    onValueChange = { draft = draft.copy(petBodyBoundary = it) },
                    valueRange = (draft.petHeadBoundary + 0.1f)..0.95f,
                )
                Text("转交模式", style = MaterialTheme.typography.labelLarge)
                PetHandoffMode.entries.forEach { mode ->
                    val label = when (mode) {
                        PetHandoffMode.CONFIRM -> "转交前确认（默认）"
                        PetHandoffMode.AUTO -> "自动低优先级转交（每30分钟最多一次，所有工具重新审批）"
                        PetHandoffMode.SUGGEST_ONLY -> "仅提示、不转交"
                    }
                    TextButton(onClick = { draft = draft.copy(petHandoffMode = mode.name) }) {
                        Text((if (draft.petHandoffMode == mode.name) "● " else "○ ") + label)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("开机后尝试恢复（默认关闭）")
                    Switch(
                        checked = draft.petBootRestoreEnabled,
                        onCheckedChange = { draft = draft.copy(petBootRestoreEnabled = it) },
                    )
                }
                if (!Settings.canDrawOverlays(context)) {
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
                        )
                    }) { Text("授予悬浮窗权限") }
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                replacementUri?.let { uri ->
                    Row {
                        TextButton(onClick = { importPackage(uri, true) }) { Text("确认替换") }
                        TextButton(onClick = { replacementUri = null; status = null }) { Text("取消") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val saved = draft.copy(
                    petScale = draft.petScale.coerceIn(0.05f, 2.0f),
                    petAnimationFps = draft.petAnimationFps.coerceIn(4, 12),
                )
                val appContext = context.applicationContext
                if (saved.petEnabled && !Settings.canDrawOverlays(context)) {
                    status =
                        "开启桌宠需要“悬浮窗”权限：请先点击上方的“授予悬浮窗权限”，返回后再保存。"
                } else {
                    onUpdate(saved) {
                        if (saved.petEnabled) {
                            if (forceRendererReload) {
                                DesktopPetService.reload(appContext)
                            } else {
                                DesktopPetService.start(appContext)
                            }
                        } else {
                            DesktopPetService.stop(appContext)
                        }
                    }
                    onDismiss()
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
    draft.petPackageId?.takeIf { showProfileEditor }?.let { packageId ->
        PetVisualProfileEditorDialog(
            packageId = packageId,
            onDismiss = { showProfileEditor = false },
        )
    }
}

internal fun listInstalledPetManifests(petsRoot: File): List<CodexPetManifest> {
    val json = Json { ignoreUnknownKeys = true }
    return petsRoot.listFiles().orEmpty()
        .asSequence()
        .filter { directory -> directory.isDirectory && !directory.name.startsWith(".") }
        .mapNotNull { directory ->
            runCatching {
                json.decodeFromString<CodexPetManifest>(
                    File(directory, "pet.json").readText(Charsets.UTF_8),
                )
            }.getOrNull()
        }
        .sortedBy { manifest -> manifest.displayName.lowercase() }
        .toList()
}
