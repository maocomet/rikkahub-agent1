package me.rerere.rikkahub.ui.pages.space

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Favourite
import me.rerere.hugeicons.stroke.Message01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.space.SpaceActorKind
import me.rerere.rikkahub.space.SpaceProfile
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.formatRelativeAgo
import me.rerere.rikkahub.workflow.ui.relativeStrings
import me.rerere.rikkahub.workflow.ui.rememberTickingNowMs
import org.koin.androidx.compose.koinViewModel

/**
 * Cat Garden: the local social timeline.
 *
 * This is the person's side of the space. Assistants post through their own tools; the screen
 * reads and writes as the local user, never as an assistant.
 */
@Composable
fun CatGardenPage(vm: CatGardenVM = koinViewModel()) {
    val feed by vm.feed.collectAsStateWithLifecycle()
    val mine by vm.mine.collectAsStateWithLifecycle()
    val feedHasMore by vm.feedHasMore.collectAsStateWithLifecycle()
    val mineHasMore by vm.mineHasMore.collectAsStateWithLifecycle()
    val notifications by vm.notifications.collectAsStateWithLifecycle()
    val unread by vm.unreadCount.collectAsStateWithLifecycle()
    val commentThread by vm.commentThread.collectAsStateWithLifecycle()
    val nickname by vm.userNickname.collectAsStateWithLifecycle()
    val userAvatar by vm.userAvatar.collectAsStateWithLifecycle()

    // Re-read every tab whenever the screen comes back to the foreground. The person can hand a
    // delete to an Assistant from the chat and return here, and Cat Garden reads snapshots rather
    // than observing Room, so it would otherwise still be showing the post that just went.
    //
    // Deliberately the lifecycle event rather than a LaunchedEffect or a call in the composable
    // body: a recomposition is not new data, and refreshing on one would turn scrolling into a
    // query storm. The ViewModel skips the first resume, so opening the screen is still one load.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.refreshOnResume()
    }

    val tabs = CatGardenTab.entries
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()
    val nowMs by rememberTickingNowMs()
    val relative = relativeStrings()

    var composing by remember { mutableStateOf(false) }
    var commentingOn by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<SpaceFeedItem?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.space_page_title)) },
                navigationIcon = { BackButton() },
                actions = {
                    if (tabUnread(tabs, pagerState.currentPage, unread)) {
                        TextButton(onClick = { vm.markAllVisibleRead() }) {
                            Text(stringResource(R.string.space_action_mark_all_read))
                        }
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        floatingActionButton = {
            if (tabs.getOrElse(pagerState.currentPage) { CatGardenTab.FEED } != CatGardenTab.NOTIFICATIONS) {
                FloatingActionButton(onClick = { composing = true }) {
                    Icon(
                        imageVector = HugeIcons.Add01,
                        contentDescription = stringResource(R.string.space_action_new_post),
                    )
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            SecondaryTabRow(selectedTabIndex = pagerState.currentPage) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        text = {
                            if (tab == CatGardenTab.NOTIFICATIONS && unread > 0) {
                                BadgedBox(badge = { Badge { Text(unread.toString()) } }) {
                                    Text(tabLabel(tab))
                                }
                            } else {
                                Text(tabLabel(tab))
                            }
                        },
                    )
                }
            }
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                when (tabs.getOrElse(page) { CatGardenTab.FEED }) {
                    CatGardenTab.FEED -> FeedList(
                        items = feed,
                        nickname = nickname,
                        userAvatar = userAvatar,
                        nowMs = nowMs,
                        relative = relative,
                        emptyLabel = stringResource(R.string.space_empty_feed),
                        hasMore = feedHasMore,
                        onLike = vm::toggleLike,
                        onComment = { commentingOn = it },
                        onOpenComments = vm::openComments,
                        onDelete = { pendingDelete = it },
                        onLoadMore = vm::loadMoreFeed,
                    )

                    CatGardenTab.MINE -> FeedList(
                        items = mine,
                        nickname = nickname,
                        userAvatar = userAvatar,
                        nowMs = nowMs,
                        relative = relative,
                        emptyLabel = stringResource(R.string.space_empty_mine),
                        hasMore = mineHasMore,
                        onLike = vm::toggleLike,
                        onComment = { commentingOn = it },
                        onOpenComments = vm::openComments,
                        onDelete = { pendingDelete = it },
                        onLoadMore = vm::loadMoreMine,
                    )

                    CatGardenTab.NOTIFICATIONS -> NotificationList(
                        items = notifications,
                        nickname = nickname,
                        userAvatar = userAvatar,
                        nowMs = nowMs,
                        relative = relative,
                        onRead = { vm.markNotificationsRead(listOf(it)) },
                    )
                }
            }
        }
    }

    if (composing) {
        TextEntryDialog(
            title = stringResource(R.string.space_action_new_post),
            hint = stringResource(R.string.space_compose_hint),
            confirmLabel = stringResource(R.string.space_action_publish),
            onDismiss = { composing = false },
            onConfirm = { text ->
                composing = false
                vm.createPost(text)
            },
        )
    }

    commentingOn?.let { postId ->
        TextEntryDialog(
            title = stringResource(R.string.space_action_comment),
            hint = stringResource(R.string.space_comment_hint),
            confirmLabel = stringResource(R.string.space_action_publish),
            onDismiss = { commentingOn = null },
            onConfirm = { text ->
                commentingOn = null
                vm.createComment(postId, text)
            },
        )
    }

    // Deleting is permanent and cascades, so it is confirmed rather than undoable. The dialog is
    // shown for any post the person reaches, since they moderate the space; the repository still
    // decides for itself, and refuses anything the runtime actor is not entitled to delete.
    pendingDelete?.let { item ->
        DeletePostDialog(
            preview = item.post.content,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                vm.deletePost(item.post.postId)
            },
        )
    }

    commentThread?.let { thread ->
        CommentThreadDialog(
            thread = thread,
            nickname = nickname,
            userAvatar = userAvatar,
            nowMs = nowMs,
            relative = relative,
            onDismiss = vm::closeComments,
            onLoadMore = vm::loadMoreComments,
            onSend = { text -> vm.createComment(thread.post.postId, text) },
        )
    }
}

@Composable
private fun DeletePostDialog(
    preview: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.space_action_delete_post)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.space_delete_post_confirm))
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.space_action_delete_post))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.space_action_cancel))
            }
        },
    )
}

/**
 * The complete comment view for one post.
 *
 * Full screen rather than a sheet, because it is a reading surface: the post's own content and
 * author stay pinned at the top, the whole thread scrolls under them, and the composer stays at
 * the bottom. Only the loaded pages live in memory — the thread is read a page at a time, so a post
 * with thousands of comments opens as fast as one with three.
 */
@Composable
private fun CommentThreadDialog(
    thread: SpaceCommentThread,
    nickname: String,
    userAvatar: Avatar,
    nowMs: Long,
    relative: me.rerere.rikkahub.utils.RelativeTimeStrings,
    onDismiss: () -> Unit,
    onLoadMore: () -> Unit,
    onSend: (String) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.space_comments_title)) },
                        // Not BackButton: this is a dialog, not a destination, so dismissing it
                        // must not pop the nav stack behind it.
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    imageVector = HugeIcons.ArrowLeft01,
                                    contentDescription = stringResource(R.string.back),
                                )
                            }
                        },
                        colors = CustomColors.topBarColors,
                    )
                },
                bottomBar = {
                    CommentComposer(onSend = onSend)
                },
            ) { padding ->
                LazyColumn(
                    modifier = Modifier.padding(padding).fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        // The post the thread belongs to, so the conversation is never read out of
                        // context.
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CustomColors.cardColorsOnSurfaceContainer,
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    ActorAvatar(thread.author, nickname, userAvatar, size = 36)
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = actorLabel(thread.author, nickname),
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = formatRelativeAgo(
                                                thread.post.createdAtMs,
                                                nowMs,
                                                relative,
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                    }
                                }
                                Text(
                                    text = thread.post.content,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }

                    item {
                        Text(
                            text = stringResource(
                                R.string.space_comments_count,
                                thread.totalCount,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }

                    if (thread.comments.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.space_empty_comments),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }

                    items(thread.comments, key = { it.comment.commentId }) { view ->
                        CommentRow(view, nickname, userAvatar)
                    }

                    if (thread.hasMore) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (thread.loadingMore) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                                } else {
                                    TextButton(onClick = onLoadMore) {
                                        Text(stringResource(R.string.space_action_load_more))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One comment, with its author's resolved identity. */
@Composable
private fun CommentRow(view: SpaceCommentView, nickname: String, userAvatar: Avatar) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ActorAvatar(view.author, nickname, userAvatar, size = 28)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = actorLabel(view.author, nickname),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(text = view.comment.content, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CommentComposer(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Surface(tonalElevation = 3.dp) {
        Column {
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(stringResource(R.string.space_comment_hint)) },
                    modifier = Modifier.weight(1f),
                    maxLines = 3,
                )
                TextButton(
                    onClick = {
                        val body = text
                        text = ""
                        onSend(body)
                    },
                    enabled = text.isNotBlank(),
                ) {
                    Text(stringResource(R.string.space_action_publish))
                }
            }
        }
    }
}

private fun tabUnread(tabs: List<CatGardenTab>, page: Int, unread: Int): Boolean =
    tabs.getOrElse(page) { CatGardenTab.FEED } == CatGardenTab.NOTIFICATIONS && unread > 0

@Composable
private fun tabLabel(tab: CatGardenTab): String = stringResource(
    when (tab) {
        CatGardenTab.FEED -> R.string.space_tab_feed
        CatGardenTab.MINE -> R.string.space_tab_mine
        CatGardenTab.NOTIFICATIONS -> R.string.space_tab_notifications
    },
)

@Composable
private fun FeedList(
    items: List<SpaceFeedItem>,
    nickname: String,
    userAvatar: Avatar,
    nowMs: Long,
    relative: me.rerere.rikkahub.utils.RelativeTimeStrings,
    emptyLabel: String,
    hasMore: Boolean,
    onLike: (String, Boolean) -> Unit,
    onComment: (String) -> Unit,
    onOpenComments: (String) -> Unit,
    onDelete: (SpaceFeedItem) -> Unit,
    onLoadMore: () -> Unit,
) {
    if (items.isEmpty()) {
        EmptyState(emptyLabel)
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items, key = { it.post.postId }) { item ->
            SpacePostCard(
                item = item,
                nickname = nickname,
                userAvatar = userAvatar,
                nowMs = nowMs,
                relative = relative,
                onLike = onLike,
                onComment = onComment,
                onOpenComments = onOpenComments,
                onDelete = onDelete,
            )
        }
        if (hasMore) {
            item {
                // Cursor pagination: the list never renders the whole history at once, and asking
                // for the next page is an explicit, bounded action. The button is only offered
                // while a next page actually exists, so it is never a control that does nothing.
                TextButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.space_action_load_more))
                }
            }
        }
    }
}

@Composable
private fun SpacePostCard(
    item: SpaceFeedItem,
    nickname: String,
    userAvatar: Avatar,
    nowMs: Long,
    relative: me.rerere.rikkahub.utils.RelativeTimeStrings,
    onLike: (String, Boolean) -> Unit,
    onComment: (String) -> Unit,
    onOpenComments: (String) -> Unit,
    onDelete: (SpaceFeedItem) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ActorAvatar(item.author, nickname, userAvatar, size = 36)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = actorLabel(item.author, nickname),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = formatRelativeAgo(item.post.createdAtMs, nowMs, relative),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                // Every post offers this, an assistant's included: the person reading this screen
                // is Cat Garden's moderator. It is only an entry point — the repository decides
                // again from the runtime actor, and refuses an assistant that asks for a post it
                // did not publish.
                if (localUserMayDelete(item.post)) {
                    IconButton(onClick = { onDelete(item) }) {
                        Icon(
                            imageVector = HugeIcons.Delete01,
                            contentDescription = stringResource(R.string.space_action_delete_post),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }

            Text(text = item.post.content, style = MaterialTheme.typography.bodyMedium)

            if (item.likerNames.isNotEmpty()) {
                val separator = stringResource(R.string.space_list_separator)
                Text(
                    text = stringResource(
                        R.string.space_likes_summary,
                        item.likerNames.joinToString(separator),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.clickable { onLike(item.post.postId, !item.likedByViewer) },
                ) {
                    Icon(
                        imageVector = HugeIcons.Favourite,
                        contentDescription = stringResource(R.string.space_action_like),
                        modifier = Modifier.size(18.dp),
                        tint = if (item.likedByViewer) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                    )
                    Text(
                        text = item.likeCount.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    // The count is the standard "open the conversation" affordance, so it opens
                    // the full thread rather than jumping straight into composing a new comment.
                    modifier = Modifier.clickable { onOpenComments(item.post.postId) },
                ) {
                    Icon(
                        imageVector = HugeIcons.Message01,
                        contentDescription = stringResource(R.string.space_comments_title),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        // The real total, never the preview length.
                        text = item.commentCount.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                TextButton(onClick = { onComment(item.post.postId) }) {
                    Text(stringResource(R.string.space_action_comment))
                }
            }

            item.comments.forEach { view ->
                Text(
                    text = "${actorLabel(view.author, nickname)}: ${view.comment.content}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // The preview is capped so a timeline never grows without bound, which means this line
            // is the reader's only signal that there is more. It therefore has to lead somewhere —
            // it opens the same full thread the count does.
            if (item.commentCount > item.comments.size) {
                Text(
                    text = stringResource(
                        R.string.space_more_comments,
                        item.commentCount - item.comments.size,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onOpenComments(item.post.postId) },
                )
            }
        }
    }
}

@Composable
private fun NotificationList(
    items: List<SpaceNotificationView>,
    nickname: String,
    userAvatar: Avatar,
    nowMs: Long,
    relative: me.rerere.rikkahub.utils.RelativeTimeStrings,
    onRead: (String) -> Unit,
) {
    if (items.isEmpty()) {
        EmptyState(stringResource(R.string.space_empty_notifications))
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.notification.notificationId }) { item ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onRead(item.notification.notificationId) },
                colors = CustomColors.cardColorsOnSurfaceContainer,
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ActorAvatar(item.actor, nickname, userAvatar, size = 32)
                    val actor = actorLabel(item.actor, nickname)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(
                                if (item.notification.type ==
                                    me.rerere.rikkahub.space.SpaceNotificationType.LIKE.name
                                ) {
                                    R.string.space_notification_like
                                } else {
                                    R.string.space_notification_comment
                                },
                                actor,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        item.postPreview?.let { preview ->
                            Text(
                                text = preview,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (item.notification.readAtMs == null) {
                        Text(
                            text = formatRelativeAgo(item.notification.createdAtMs, nowMs, relative),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(label: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun ActorAvatar(profile: SpaceProfile, nickname: String, userAvatar: Avatar, size: Int) {
    if (profile.actor.kind == SpaceActorKind.USER) {
        UIAvatar(name = nickname, value = userAvatar, modifier = Modifier.size(size.dp))
    } else {
        UIAvatar(
            name = actorLabel(profile, nickname),
            value = profile.avatar,
            modifier = Modifier.size(size.dp),
        )
    }
}

/**
 * A display name for an actor. The space layer deliberately resolves no localised fallback, so
 * the two cases that have no stored name are decided here: the local user is the current nickname,
 * and an assistant that no longer exists says so rather than showing an empty row.
 */
@Composable
private fun actorLabel(profile: SpaceProfile, nickname: String): String = when {
    profile.actor.kind == SpaceActorKind.USER ->
        nickname.ifBlank { stringResource(R.string.user_default_name) }

    profile.isDeleted -> stringResource(R.string.space_deleted_assistant)
    else -> profile.displayName
}

@Composable
private fun TextEntryDialog(
    title: String,
    hint: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(hint) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank(),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.space_action_cancel))
            }
        },
    )
}
