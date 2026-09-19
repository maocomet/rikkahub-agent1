package me.rerere.rikkahub.ui.pages.sticker

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.dokar.sonner.ToastType
import me.rerere.hugeicons.HugeIcons
import me.rerere.rikkahub.R
import me.rerere.rikkahub.sticker.Sticker
import me.rerere.rikkahub.sticker.StickerImportRejection
import me.rerere.rikkahub.sticker.StickerVisionFailure
import me.rerere.rikkahub.sticker.StickerVisionState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel

/**
 * The shared sticker library.
 *
 * "Shared" is the point of the screen and the reason there is no assistant picker on it: one
 * library serves the person and every assistant, so nothing here scopes a sticker to an owner.
 *
 * The layout is a plain adaptive grid on purpose. A sticker library is browsed by looking at the
 * pictures, and every attempt to be clever about that (masonry, varying spans) makes the one thing
 * the screen is for — scanning for a face — harder.
 */
@Composable
fun StickerLibraryPage(vm: StickerLibraryVM = koinViewModel()) {
    val stickers by vm.stickers.collectAsStateWithLifecycle()
    val importState by vm.import.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val toaster = LocalToaster.current

    var detail by remember { mutableStateOf<Sticker?>(null) }
    var pendingDelete by remember { mutableStateOf<Sticker?>(null) }

    val pickImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            vm.startImport { context.contentResolver.openInputStream(uri) }
        }
    }

    LaunchedEffect(Unit) {
        vm.messages.collect { message ->
            toaster.show(
                context.getString(message.textRes()),
                type = message.toastType(),
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sticker_page_title)) },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    pickImageLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
            ) {
                Icon(
                    imageVector = HugeIcons.Add01,
                    contentDescription = stringResource(R.string.sticker_action_import),
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (stickers.isEmpty()) {
                Text(
                    text = stringResource(R.string.sticker_page_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 132.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(stickers, key = { it.sticker.id }) { item ->
                        StickerCard(
                            item = item,
                            onClick = { detail = item.sticker },
                        )
                    }
                }
            }
        }
    }

    (importState as? StickerImportUiState.Editing)?.let { state ->
        StickerImportSheet(
            draft = state.draft,
            onDescriptionChange = vm::onDescriptionChange,
            onTagsChange = vm::onTagsChange,
            onEnabledChange = vm::onEnabledChange,
            onRecognizeAgain = vm::recognizeDraft,
            onSave = vm::save,
            onCancel = vm::cancelImport,
        )
    }

    if (importState is StickerImportUiState.Preparing) {
        // Deliberately a small modal rather than a sheet: the picking step is short, and swapping
        // one sheet for another mid-flow reads as a glitch.
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(stringResource(R.string.sticker_import_preparing)) },
            text = { CircularProgressIndicator() },
        )
    }

    when (val state = importState) {
        is StickerImportUiState.Duplicate -> AlertDialog(
            onDismissRequest = vm::dismissImportNotice,
            title = { Text(stringResource(R.string.sticker_duplicate_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.sticker_duplicate_message))
                    if (state.existing.description.isNotBlank()) {
                        Text(
                            text = state.existing.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = vm::dismissImportNotice) {
                    Text(stringResource(R.string.sticker_action_ok))
                }
            },
        )
        is StickerImportUiState.Rejected -> AlertDialog(
            onDismissRequest = vm::dismissImportNotice,
            title = { Text(stringResource(R.string.sticker_rejected_title)) },
            text = { Text(stringResource(state.reason.textRes())) },
            confirmButton = {
                TextButton(onClick = vm::dismissImportNotice) {
                    Text(stringResource(R.string.sticker_action_ok))
                }
            },
        )
        else -> Unit
    }

    detail?.let { sticker ->
        StickerDetailSheet(
            sticker = sticker,
            onDismiss = { detail = null },
            onSave = { description, tagsText ->
                vm.updateMetadata(sticker.id, description, tagsText)
                detail = null
            },
            onEnabledChange = { vm.setEnabled(sticker.id, it) },
            onRetryRecognition = { vm.retryRecognition(sticker.id) },
            onDelete = {
                detail = null
                pendingDelete = sticker
            },
        )
    }

    pendingDelete?.let { sticker ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.sticker_delete_title)) },
            text = { Text(stringResource(R.string.sticker_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.delete(sticker.id)
                        pendingDelete = null
                    },
                ) {
                    Text(stringResource(R.string.sticker_action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.sticker_action_cancel))
                }
            },
        )
    }
}

@Composable
private fun StickerCard(item: StickerItem, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (item.imageUri == null) {
                    Text(
                        text = stringResource(R.string.sticker_missing_file),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(8.dp),
                    )
                } else {
                    AsyncImage(
                        model = item.imageUri,
                        contentDescription = item.sticker.description.ifBlank { null },
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = item.sticker.description.ifBlank {
                        stringResource(item.sticker.stateLabelRes())
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.sticker.tags.isNotEmpty()) {
                    Text(
                        text = item.sticker.tags.joinToString("、"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (!item.sticker.enabled) {
                    Text(
                        text = stringResource(R.string.sticker_disabled_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StickerImportSheet(
    draft: StickerDraftUi,
    onDescriptionChange: (String) -> Unit,
    onTagsChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onRecognizeAgain: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onCancel) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.sticker_import_title),
                style = MaterialTheme.typography.titleMedium,
            )

            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(1.6f),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = draft.imageUri,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                if (draft.recognizing) {
                    // Over the preview rather than replacing it: the picture is the one thing the
                    // person is sure about, and hiding it while a model thinks is a worse wait.
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Text(
                                text = stringResource(R.string.sticker_import_recognizing),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            RecognitionNotice(draft)

            OutlinedTextField(
                value = draft.description,
                onValueChange = onDescriptionChange,
                label = { Text(stringResource(R.string.sticker_field_description)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = draft.tagsText,
                onValueChange = onTagsChange,
                label = { Text(stringResource(R.string.sticker_field_tags)) },
                supportingText = { Text(stringResource(R.string.sticker_field_tags_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Switch(checked = draft.enabled, onCheckedChange = onEnabledChange)
                Text(
                    text = stringResource(R.string.sticker_field_enabled),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRecognizeAgain, enabled = !draft.recognizing) {
                    Text(stringResource(R.string.sticker_action_recognize_again))
                }
                TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.sticker_action_cancel))
                }
                TextButton(onClick = onSave, enabled = !draft.saving) {
                    Text(stringResource(R.string.sticker_action_save))
                }
            }
        }
    }
}

@Composable
private fun StickerDetailSheet(
    sticker: Sticker,
    onDismiss: () -> Unit,
    onSave: (description: String, tagsText: String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onRetryRecognition: () -> Unit,
    onDelete: () -> Unit,
) {
    var description by remember(sticker.id) { mutableStateOf(sticker.description) }
    var tagsText by remember(sticker.id) {
        mutableStateOf(sticker.tags.joinToString("、"))
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(stringResource(R.string.sticker_field_description)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = tagsText,
                onValueChange = { tagsText = it },
                label = { Text(stringResource(R.string.sticker_field_tags)) },
                supportingText = { Text(stringResource(R.string.sticker_field_tags_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Switch(checked = sticker.enabled, onCheckedChange = onEnabledChange)
                Text(
                    text = stringResource(R.string.sticker_field_enabled),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            RecognitionNotice(
                state = sticker.visionState,
                failure = sticker.visionFailure,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = HugeIcons.Delete02,
                        contentDescription = stringResource(R.string.sticker_action_delete),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedButton(onClick = onRetryRecognition) {
                    Text(stringResource(R.string.sticker_action_recognize_again))
                }
                TextButton(onClick = { onSave(description, tagsText) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.sticker_action_save))
                }
            }
        }
    }
}

/**
 * Says what the recognition did, when it did not simply succeed.
 *
 * Nothing is shown for [StickerVisionState.OK]: a sticker with a description needs no badge
 * explaining where the description came from, and a label on every card would be noise on the
 * common case.
 */
@Composable
private fun RecognitionNotice(draft: StickerDraftUi) {
    RecognitionNotice(state = draft.visionState, failure = draft.visionFailure)
}

@Composable
private fun RecognitionNotice(state: StickerVisionState, failure: StickerVisionFailure?) {
    if (state == StickerVisionState.OK) return
    Text(
        text = if (failure == StickerVisionFailure.MODEL_NOT_CONFIGURED) {
            stringResource(R.string.sticker_state_not_configured)
        } else {
            stringResource(R.string.sticker_state_failed)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

private fun Sticker.stateLabelRes(): Int = when (visionState) {
    StickerVisionState.OK -> R.string.sticker_state_ok
    StickerVisionState.FAILED -> R.string.sticker_state_failed
    StickerVisionState.NONE -> R.string.sticker_state_not_recognized
}

private fun StickerMessage.textRes(): Int = when (this) {
    StickerMessage.SAVED -> R.string.sticker_message_saved
    StickerMessage.SAVE_FAILED -> R.string.sticker_message_save_failed
    StickerMessage.RECOGNITION_FAILED -> R.string.sticker_message_recognition_failed
    StickerMessage.RECOGNITION_FAILED_KEPT_METADATA ->
        R.string.sticker_message_recognition_failed_kept
    StickerMessage.DELETED -> R.string.sticker_message_deleted
}

private fun StickerMessage.toastType(): ToastType = when (this) {
    StickerMessage.SAVED, StickerMessage.DELETED -> ToastType.Success
    StickerMessage.SAVE_FAILED -> ToastType.Error
    StickerMessage.RECOGNITION_FAILED,
    StickerMessage.RECOGNITION_FAILED_KEPT_METADATA,
    -> ToastType.Warning
}

private fun StickerImportRejection.textRes(): Int = when (this) {
    StickerImportRejection.UNREADABLE -> R.string.sticker_rejected_unreadable
    StickerImportRejection.UNSUPPORTED_FORMAT -> R.string.sticker_rejected_unsupported
    StickerImportRejection.CORRUPT_IMAGE -> R.string.sticker_rejected_corrupt
}
