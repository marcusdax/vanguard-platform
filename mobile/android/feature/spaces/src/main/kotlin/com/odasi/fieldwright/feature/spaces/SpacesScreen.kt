package com.odasi.fieldwright.feature.spaces

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.odasi.fieldwright.data.FieldwrightRepositories
import com.odasi.fieldwright.design.Fw
import com.odasi.fieldwright.design.FwCard
import com.odasi.fieldwright.design.FwPill
import com.odasi.fieldwright.design.FwPrimaryButton
import com.odasi.fieldwright.design.FwSectionRule
import com.odasi.fieldwright.design.FwTextField
import com.odasi.fieldwright.design.PillTone

@Composable
fun SpacesRoute(
    repos: FieldwrightRepositories,
    onOpenBrief: () -> Unit,
) {
    val vm: SpacesViewModel = viewModel { SpacesViewModel(repos) }
    val state by vm.state.collectAsStateWithLifecycle()
    SpacesScreen(
        state = state,
        onSelectTab = vm::selectTab,
        onSelectSpaceForFeed = vm::selectSpaceForFeed,
        onToggleFollow = vm::toggleFollow,
        onCreateSpace = vm::createSpace,
        onSetFollowingOnly = vm::setFollowingOnly,
        onPublishPost = vm::publishPost,
        onToggleKeep = vm::toggleKeep,
        onAddReply = vm::addReply,
        onToggleLive = vm::toggleLiveStream,
        onSendLiveChat = vm::sendLiveChat,
        onOpenBrief = onOpenBrief,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpacesScreen(
    state: SpacesUiState,
    onSelectTab: (SpacesTab) -> Unit,
    onSelectSpaceForFeed: (String?) -> Unit,
    onToggleFollow: (String) -> Unit,
    onCreateSpace: (name: String, district: String, roleLabel: String, tagline: String) -> Unit,
    onSetFollowingOnly: (Boolean) -> Unit,
    onPublishPost: (FieldPostKind, String, String, String?, List<String>, String?) -> Unit,
    onToggleKeep: (String) -> Unit,
    onAddReply: (String, String) -> Unit,
    onToggleLive: (String?) -> Unit,
    onSendLiveChat: (String) -> Unit,
    onOpenBrief: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Spaces & Feed", style = Fw.type.displayL, color = Fw.colors.ink)
                Text(
                    "Vanguard territory desks, chronological field log & live broadcasts",
                    style = Fw.type.meta,
                    color = Fw.colors.inkSoft,
                )
            }
            TextButton(onClick = onOpenBrief) {
                Text("Brief", color = Fw.colors.olive)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.activeTab == SpacesTab.SPACES,
                onClick = { onSelectTab(SpacesTab.SPACES) },
                label = { Text("Spaces (${state.spaces.size})") },
            )
            FilterChip(
                selected = state.activeTab == SpacesTab.FEED,
                onClick = { onSelectTab(SpacesTab.FEED) },
                label = { Text("Feed (${state.posts.size})") },
            )
            FilterChip(
                selected = state.activeTab == SpacesTab.LIVE,
                onClick = { onSelectTab(SpacesTab.LIVE) },
                label = { Text(if (state.liveActive) "Live • ON AIR" else "Live") },
            )
        }

        when (state.activeTab) {
            SpacesTab.SPACES -> SpacesTabContent(
                spaces = state.spaces,
                onCreateSpace = onCreateSpace,
                onToggleFollow = onToggleFollow,
                onOpenFeed = onSelectSpaceForFeed,
            )
            SpacesTab.FEED -> FeedTabContent(
                state = state,
                onSelectSpaceFilter = onSelectSpaceForFeed,
                onSetFollowingOnly = onSetFollowingOnly,
                onPublishPost = onPublishPost,
                onToggleKeep = onToggleKeep,
                onAddReply = onAddReply,
            )
            SpacesTab.LIVE -> LiveTabContent(
                state = state,
                onToggleLive = onToggleLive,
                onSendLiveChat = onSendLiveChat,
            )
        }
    }
}

@Composable
private fun SpacesTabContent(
    spaces: List<FieldSpaceItem>,
    onCreateSpace: (String, String, String, String) -> Unit,
    onToggleFollow: (String) -> Unit,
    onOpenFeed: (String) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var district by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("Storm Crew") }
    var tagline by remember { mutableStateOf("") }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Territory Desks", style = Fw.type.title, color = Fw.colors.ink)
        OutlinedButton(onClick = { creating = !creating }) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(if (creating) "Close" else "Create Space")
        }
    }

    if (creating) {
        FwCard {
            FwSectionRule("New Field Space")
            Spacer(Modifier.height(8.dp))
            FwTextField(value = name, onValueChange = { name = it }, label = "Space Name")
            Spacer(Modifier.height(8.dp))
            FwTextField(value = district, onValueChange = { district = it }, label = "District / Swath")
            Spacer(Modifier.height(8.dp))
            FwTextField(value = role, onValueChange = { role = it }, label = "Crew Role")
            Spacer(Modifier.height(8.dp))
            FwTextField(value = tagline, onValueChange = { tagline = it }, label = "Tagline", singleLine = false)
            Spacer(Modifier.height(10.dp))
            FwPrimaryButton(
                text = "Publish Space",
                enabled = name.trim().length >= 2,
                onClick = {
                    onCreateSpace(name, district, role, tagline)
                    name = ""
                    district = ""
                    tagline = ""
                    creating = false
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    spaces.forEach { sp ->
        FwCard(onClick = { onOpenFeed(sp.id) }) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FwSectionRule("${sp.roleLabel} · ${sp.district}")
                if (sp.liveNow) FwPill("LIVE", PillTone.FLAG)
            }
            Spacer(Modifier.height(6.dp))
            Text(sp.name, style = Fw.type.title, color = Fw.colors.ink)
            Spacer(Modifier.height(4.dp))
            Text(sp.tagline, style = Fw.type.body, color = Fw.colors.inkSoft)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FwPill("Trust ${sp.trustScore} · ${sp.sealedPackets} packets", PillTone.OLIVE)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("${sp.followerCount} following", style = Fw.type.meta, color = Fw.colors.inkSoft)
                    OutlinedButton(onClick = { onToggleFollow(sp.id) }) {
                        Text(if (sp.following) "Following" else "Follow")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeedTabContent(
    state: SpacesUiState,
    onSelectSpaceFilter: (String?) -> Unit,
    onSetFollowingOnly: (Boolean) -> Unit,
    onPublishPost: (FieldPostKind, String, String, String?, List<String>, String?) -> Unit,
    onToggleKeep: (String) -> Unit,
    onAddReply: (String, String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(FieldPostKind.NOTE) }
    val pendingPhotos = remember { mutableStateListOf<String>() }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents(),
    ) { uris: List<Uri> ->
        uris.forEach { uri ->
            val label = uri.lastPathSegment?.substringAfterLast('/') ?: "field-photo.jpg"
            if (!pendingPhotos.contains(label)) pendingPhotos.add(label)
        }
    }

    FwCard {
        FwSectionRule("Log Field Update")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = kind == FieldPostKind.NOTE,
                onClick = { kind = FieldPostKind.NOTE },
                label = { Text("Field Note") },
            )
            FilterChip(
                selected = kind == FieldPostKind.LOT_HANDOFF,
                onClick = { kind = FieldPostKind.LOT_HANDOFF },
                label = { Text("Pass Job Lot") },
            )
            OutlinedButton(onClick = { imagePicker.launch("image/*") }) {
                Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Photos (${pendingPhotos.size})")
            }
        }
        Spacer(Modifier.height(8.dp))
        FwTextField(value = title, onValueChange = { title = it }, label = "Headline (optional)")
        Spacer(Modifier.height(8.dp))
        FwTextField(value = body, onValueChange = { body = it }, label = "Field Observation", singleLine = false)
        if (pendingPhotos.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                pendingPhotos.forEach { photo ->
                    FwPill(photo, PillTone.NEUTRAL)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        FwPrimaryButton(
            text = "Publish to Feed",
            enabled = body.trim().length >= 2,
            onClick = {
                onPublishPost(
                    kind,
                    title,
                    body,
                    state.selectedSpaceId,
                    pendingPhotos.toList(),
                    if (kind == FieldPostKind.LOT_HANDOFF) state.attachedJobChoices.firstOrNull() else null,
                )
                title = ""
                body = ""
                pendingPhotos.clear()
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = !state.followingOnly,
            onClick = { onSetFollowingOnly(false) },
            label = { Text("All Chronological") },
        )
        FilterChip(
            selected = state.followingOnly,
            onClick = { onSetFollowingOnly(true) },
            label = { Text("Following Only") },
        )
        if (state.selectedSpaceId != null) {
            TextButton(onClick = { onSelectSpaceFilter(null) }) {
                Text("Clear Space Filter")
            }
        }
    }

    val followedIds = state.spaces.filter { it.following }.map { it.id }.toSet()
    val visiblePosts = state.posts.filter { p ->
        val spaceOk = state.selectedSpaceId == null || p.spaceId == state.selectedSpaceId
        val followOk = !state.followingOnly || followedIds.contains(p.spaceId)
        spaceOk && followOk
    }

    visiblePosts.forEach { post ->
        var replyOpen by remember(post.id) { mutableStateOf(false) }
        var replyDraft by remember(post.id) { mutableStateOf("") }
        FwCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${post.author} · ${post.timeAgo}", style = Fw.type.meta, color = Fw.colors.inkSoft)
                FwPill(post.spaceName, PillTone.NEUTRAL)
            }
            if (post.title.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(post.title, style = Fw.type.title, color = Fw.colors.ink)
            }
            Spacer(Modifier.height(4.dp))
            Text(post.body, style = Fw.type.body, color = Fw.colors.inkSoft)
            if (post.attachedJobLabel != null) {
                Spacer(Modifier.height(6.dp))
                FwPill("Job: ${post.attachedJobLabel}", PillTone.OXIDE)
            }
            if (post.photoLabels.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    post.photoLabels.forEach { ph ->
                        FwPill("📷 $ph", PillTone.AMBER)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { onToggleKeep(post.id) }) {
                    Icon(Icons.Outlined.Bookmark, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("${if (post.keptByMe) "Kept" else "Keep"} (${post.keepCount})")
                }
                TextButton(onClick = { replyOpen = !replyOpen }) {
                    Text("Reply (${post.replies.size})")
                }
            }
            post.replies.forEach { r ->
                Text(
                    "${r.author}: ${r.body}",
                    style = Fw.type.meta,
                    color = Fw.colors.ink,
                    modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                )
            }
            if (replyOpen) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FwTextField(
                        value = replyDraft,
                        onValueChange = { replyDraft = it },
                        label = "Reply",
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = {
                            onAddReply(post.id, replyDraft)
                            replyDraft = ""
                            replyOpen = false
                        },
                    ) {
                        Text("Send")
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveTabContent(
    state: SpacesUiState,
    onToggleLive: (String?) -> Unit,
    onSendLiveChat: (String) -> Unit,
) {
    var titleDraft by remember { mutableStateOf(state.liveTitle) }
    var chatDraft by remember { mutableStateOf("") }

    FwCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FwSectionRule("Live Field Broadcast")
            FwPill(
                text = if (state.liveActive) "ON AIR" else "STANDBY",
                tone = if (state.liveActive) PillTone.FLAG else PillTone.NEUTRAL,
            )
        }
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(Fw.shape.card)
                .background(Fw.colors.ink)
                .border(Fw.dimens.hairline, Fw.colors.line, Fw.shape.card)
                .padding(14.dp),
        ) {
            Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = if (state.liveActive) Icons.Outlined.RadioButtonChecked else Icons.Outlined.Videocam,
                    contentDescription = null,
                    tint = Fw.colors.sheet,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (state.liveActive) "STREAMING: ${state.liveTitle}" else "Ready to broadcast roof walk & storm inspection frames",
                    style = Fw.type.bodyStrong,
                    color = Fw.colors.sheet,
                )
            }
            if (state.liveActive) {
                Row(
                    modifier = Modifier.align(Alignment.TopStart),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Fw.colors.flag))
                    Spacer(Modifier.width(6.dp))
                    Text("LIVE · ${state.liveViewerCount} watching", style = Fw.type.micro, color = Fw.colors.sheet)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        FwTextField(value = titleDraft, onValueChange = { titleDraft = it }, label = "Broadcast Title")
        Spacer(Modifier.height(10.dp))
        FwPrimaryButton(
            text = if (state.liveActive) "End Live Broadcast" else "Go Live Now",
            onClick = { onToggleLive(titleDraft) },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    FwCard {
        FwSectionRule("Live Room Chat")
        Spacer(Modifier.height(8.dp))
        state.liveChat.take(8).forEach { msg ->
            Text("${msg.author} (${msg.at}): ${msg.body}", style = Fw.type.body, color = Fw.colors.inkSoft)
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FwTextField(
                value = chatDraft,
                onValueChange = { chatDraft = it },
                label = "Send live note",
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = {
                    onSendLiveChat(chatDraft)
                    chatDraft = ""
                },
            ) {
                Text("Send")
            }
        }
    }
}
