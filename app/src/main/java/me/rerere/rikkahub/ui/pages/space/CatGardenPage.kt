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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
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
    val notifications by vm.notifications.collectAsStateWithLifecycle()
    val unread by vm.unreadCount.collectAsStateWithLifecycle()
    val nickname by vm.userNickname.collectAsStateWithLifecycle()
    val userAvatar by vm.userAvatar.collectAsStateWithLifecycle()

    val tabs = CatGardenTab.entries
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()
    val nowMs by rememberTickingNowMs()
    val relative = relativeStrings()

    var composing by remember { mutableStateOf(false) }
    var commentingOn by remember { mutableStateOf<String?>(null) }

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
                        onLike = vm::toggleLike,
                        onComment = { commentingOn = it },
                        onLoadMore = vm::loadMoreFeed,
                    )

                    CatGardenTab.MINE -> FeedList(
                        items = mine,
                        nickname = nickname,
                        userAvatar = userAvatar,
                        nowMs = nowMs,
                        relative = relative,
                        emptyLabel = stringResource(R.string.space_empty_mine),
                        onLike = vm::toggleLike,
                        onComment = { commentingOn = it },
                        onLoadMore = {},
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
    onLike: (String, Boolean) -> Unit,
    onComment: (String) -> Unit,
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
            )
        }
        item {
            // Cursor pagination: the feed never renders the whole history at once, and asking for
            // the next page is an explicit, bounded action.
            TextButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.space_action_load_more))
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
                    modifier = Modifier.clickable { onComment(item.post.postId) },
                ) {
                    Icon(
                        imageVector = HugeIcons.Message01,
                        contentDescription = stringResource(R.string.space_action_comment),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        text = item.commentCount.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
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
            if (item.commentCount > item.comments.size) {
                Text(
                    text = stringResource(
                        R.string.space_more_comments,
                        item.commentCount - item.comments.size,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
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
