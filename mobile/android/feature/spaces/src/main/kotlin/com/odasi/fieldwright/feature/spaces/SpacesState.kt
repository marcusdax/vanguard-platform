package com.odasi.fieldwright.feature.spaces

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.odasi.fieldwright.data.FieldwrightRepositories
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SpacesTab { SPACES, FEED, LIVE }

enum class FieldPostKind { NOTE, LOT_HANDOFF, LIVE_CLIP }

data class FieldSpaceItem(
    val id: String,
    val slug: String,
    val name: String,
    val district: String,
    val roleLabel: String,
    val tagline: String,
    val sealedPackets: Int,
    val trustScore: Int,
    val followerCount: Int,
    val following: Boolean,
    val attachedJobTitle: String? = null,
    val liveNow: Boolean = false,
)

data class FieldReplyItem(
    val id: String,
    val author: String,
    val body: String,
    val timeAgo: String,
)

data class FieldPostItem(
    val id: String,
    val spaceId: String,
    val spaceName: String,
    val author: String,
    val role: String,
    val kind: FieldPostKind,
    val title: String,
    val body: String,
    val photoLabels: List<String> = emptyList(),
    val attachedJobLabel: String? = null,
    val keepCount: Int = 0,
    val keptByMe: Boolean = false,
    val replies: List<FieldReplyItem> = emptyList(),
    val timeAgo: String = "Just now",
)

data class LiveChatMessage(
    val id: String,
    val author: String,
    val body: String,
    val at: String,
)

data class SpacesUiState(
    val activeTab: SpacesTab = SpacesTab.SPACES,
    val spaces: List<FieldSpaceItem> = defaultSpaces(),
    val posts: List<FieldPostItem> = defaultPosts(),
    val selectedSpaceId: String? = null,
    val followingOnly: Boolean = false,
    val liveActive: Boolean = false,
    val liveTitle: String = "Plano Ridge Hail Inspection · Live",
    val liveViewerCount: Int = 1,
    val liveSpaceId: String = "sp-ntx-hail",
    val liveChat: List<LiveChatMessage> = defaultChat(),
    val attachedJobChoices: List<String> = emptyList(),
)

class SpacesViewModel(
    private val repos: FieldwrightRepositories? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(SpacesUiState())
    val state: StateFlow<SpacesUiState> = _state.asStateFlow()

    init {
        repos?.jobs?.observeQueue()?.let { flow ->
            viewModelScope.launch {
                flow.collect { jobs ->
                    val titles = jobs.map { it.title }
                    _state.update { it.copy(attachedJobChoices = titles) }
                }
            }
        }
    }

    fun selectTab(tab: SpacesTab) {
        _state.update { it.copy(activeTab = tab) }
    }

    fun selectSpaceForFeed(spaceId: String?) {
        _state.update { it.copy(selectedSpaceId = spaceId, activeTab = SpacesTab.FEED) }
    }

    fun setFollowingOnly(enabled: Boolean) {
        _state.update { it.copy(followingOnly = enabled) }
    }

    fun createSpace(name: String, district: String, roleLabel: String, tagline: String) {
        val cleanName = name.trim()
        if (cleanName.length < 2) return
        val slug = cleanName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val item = FieldSpaceItem(
            id = "sp-${System.currentTimeMillis()}",
            slug = slug.ifEmpty { "space-${System.currentTimeMillis()}" },
            name = cleanName,
            district = district.trim().ifEmpty { "Field Sector" },
            roleLabel = roleLabel.trim().ifEmpty { "Storm Crew" },
            tagline = tagline.trim().ifEmpty { "Field dispatches, site captures, and live roof briefings." },
            sealedPackets = 0,
            trustScore = 52,
            followerCount = 1,
            following = true,
        )
        _state.update { it.copy(spaces = listOf(item) + it.spaces) }
    }

    fun toggleFollow(spaceId: String) {
        _state.update { cur ->
            cur.copy(
                spaces = cur.spaces.map { sp ->
                    if (sp.id != spaceId) sp
                    else {
                        val nextFollow = !sp.following
                        val nextCount = (sp.followerCount + if (nextFollow) 1 else -1).coerceAtLeast(0)
                        sp.copy(following = nextFollow, followerCount = nextCount)
                    }
                },
            )
        }
    }

    fun publishPost(
        kind: FieldPostKind,
        title: String,
        body: String,
        spaceId: String?,
        photoLabels: List<String>,
        attachedJobLabel: String?,
    ) {
        val cleanBody = body.trim()
        if (cleanBody.length < 2) return
        _state.update { cur ->
            val targetSpace = cur.spaces.firstOrNull { it.id == (spaceId ?: cur.selectedSpaceId) } ?: cur.spaces.first()
            val post = FieldPostItem(
                id = "fp-${System.currentTimeMillis()}",
                spaceId = targetSpace.id,
                spaceName = targetSpace.name,
                author = "Field Operator",
                role = "Storm Crew",
                kind = kind,
                title = title.trim(),
                body = cleanBody,
                photoLabels = photoLabels,
                attachedJobLabel = attachedJobLabel?.takeIf { it.isNotBlank() },
                timeAgo = "Just now",
            )
            cur.copy(posts = listOf(post) + cur.posts)
        }
    }

    fun toggleKeep(postId: String) {
        _state.update { cur ->
            cur.copy(
                posts = cur.posts.map { p ->
                    if (p.id != postId) p
                    else {
                        val nextKept = !p.keptByMe
                        val nextCount = (p.keepCount + if (nextKept) 1 else -1).coerceAtLeast(0)
                        p.copy(keptByMe = nextKept, keepCount = nextCount)
                    }
                },
            )
        }
    }

    fun addReply(postId: String, body: String) {
        val clean = body.trim()
        if (clean.isEmpty()) return
        _state.update { cur ->
            cur.copy(
                posts = cur.posts.map { p ->
                    if (p.id != postId) p
                    else {
                        val reply = FieldReplyItem(
                            id = "fr-${System.currentTimeMillis()}",
                            author = "Field Operator",
                            body = clean,
                            timeAgo = "Just now",
                        )
                        p.copy(replies = p.replies + reply)
                    }
                },
            )
        }
    }

    fun toggleLiveStream(title: String? = null) {
        _state.update { cur ->
            val nextLive = !cur.liveActive
            cur.copy(
                liveActive = nextLive,
                liveTitle = title?.trim()?.takeIf { it.isNotEmpty() } ?: cur.liveTitle,
                spaces = cur.spaces.map { sp ->
                    if (sp.id == cur.liveSpaceId) sp.copy(liveNow = nextLive) else sp
                },
            )
        }
    }

    fun sendLiveChat(body: String) {
        val clean = body.trim()
        if (clean.isEmpty()) return
        val msg = LiveChatMessage(
            id = "lc-${System.currentTimeMillis()}",
            author = "Field Operator",
            body = clean,
            at = "Now",
        )
        _state.update { it.copy(liveChat = listOf(msg) + it.liveChat.take(30)) }
    }
}

private fun defaultSpaces(): List<FieldSpaceItem> = listOf(
    FieldSpaceItem(
        id = "sp-ntx-hail",
        slug = "north-texas-hail-desk",
        name = "North Texas Hail Desk",
        district = "Plano · Frisco · McKinney",
        roleLabel = "Storm Crew",
        tagline = "Live roof walks, shingle test squares, and verified adjuster packets across Collin County.",
        sealedPackets = 3,
        trustScore = 88,
        followerCount = 12,
        following = true,
        attachedJobTitle = "412 Oak Ridge Pkwy",
    ),
    FieldSpaceItem(
        id = "sp-rapid-tarp",
        slug = "rapid-tarp-response",
        name = "Rapid Tarp & Drone Dispatch",
        district = "DFW Metro Swath",
        roleLabel = "Drone Unit",
        tagline = "Part 107 aerial sweeps, ridge cap bruising, and real-time emergency tarping updates.",
        sealedPackets = 1,
        trustScore = 69,
        followerCount = 7,
        following = false,
        attachedJobTitle = "829 Cedar Bluff Ln",
    ),
)

private fun defaultPosts(): List<FieldPostItem> = listOf(
    FieldPostItem(
        id = "fp-1",
        spaceId = "sp-ntx-hail",
        spaceName = "North Texas Hail Desk",
        author = "Elena Morales",
        role = "Lead Inspector",
        kind = FieldPostKind.LOT_HANDOFF,
        title = "West slope test square · 11 hail hits/100 sq ft",
        body = "Uploaded chalk-circled shingle impact photos + pitch gauge reading (7/12). Sealed Packet queued for adjuster review.",
        photoLabels = listOf("west_slope_test_sq.jpg", "ridge_vent_crease.jpg"),
        attachedJobLabel = "412 Oak Ridge Pkwy",
        keepCount = 4,
        keptByMe = false,
        timeAgo = "18m ago",
    ),
    FieldPostItem(
        id = "fp-2",
        spaceId = "sp-rapid-tarp",
        spaceName = "Rapid Tarp & Drone Dispatch",
        author = "Marcus Vance",
        role = "Part 107 Pilot",
        kind = FieldPostKind.NOTE,
        title = "Clear flight ceiling across Frisco Sector 3",
        body = "Wind subsided below 14 kt. Thermal + RGB passes starting for the Cedar Bluff corridor.",
        keepCount = 2,
        keptByMe = false,
        timeAgo = "42m ago",
    ),
)

private fun defaultChat(): List<LiveChatMessage> = listOf(
    LiveChatMessage("lc-1", "Dispatch Desk", "Live telemetry channel open for Sector 7B roof walks.", "2m ago"),
)
