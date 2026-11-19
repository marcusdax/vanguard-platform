package com.odasi.fieldwright.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** The four destinations of the dock (§3.4). Labels are the dock's own product copy. */
enum class DockTab(val label: String, val icon: ImageVector) {
    GROUND("Ground", Icons.Outlined.Explore),
    JOBS("Jobs", Icons.Outlined.Work),
    SPACES("Spaces", Icons.Outlined.Forum),
    VAULT("Vault", Icons.Outlined.Lock),
    PAYOUTS("Payouts", Icons.Outlined.AccountBalanceWallet),
}

/**
 * The page frame: top bar, scrolling content, dock, and the snackbar slot.
 *
 * AppRoot owns the chrome (it passes the top bar, the dock and the snackbar host); the
 * screen inside receives [PaddingValues] and fills the remaining space itself.
 */
@Composable
fun FwScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    dock: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Fw.colors.desk)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        topBar()
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            content(PaddingValues(0.dp))
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            ) {
                snackbarHost()
            }
        }
        dock()
    }
}

/**
 * Operator identity, sample-book honesty chip, sync state and the door to the operating
 * brief. The sync dot is colour plus text — never colour alone.
 */
@Composable
fun FwTopBar(
    operatorLabel: String,
    sampleChip: Boolean,
    syncLabel: String?,
    onBrief: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Fw.dimens.topBarHeight)
            .padding(horizontal = Fw.space.gutter, vertical = Fw.space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FwLogoMark(size = Fw.dimens.iconLarge)
        Spacer(Modifier.width(Fw.space.sm))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = operatorLabel,
                    style = Fw.type.bodyStrong,
                    color = Fw.colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (sampleChip) {
                    Spacer(Modifier.width(Fw.space.xs))
                    FwPill(text = FwA11y.sampleBook, tone = PillTone.AMBER)
                }
            }
            if (syncLabel != null) {
                Spacer(Modifier.height(Fw.space.xxs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(Fw.dimens.dotSmall)
                            .clip(CircleShape)
                            .background(Fw.colors.olive),
                    )
                    Spacer(Modifier.width(Fw.space.xs))
                    Text(
                        text = syncLabel,
                        style = Fw.type.meta,
                        color = Fw.colors.inkSoft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        IconButton(
            onClick = onBrief,
            modifier = Modifier.size(Fw.dimens.touchTarget),
        ) {
            Icon(
                imageVector = Icons.Outlined.Article,
                contentDescription = FwA11y.openBrief,
                tint = Fw.colors.ink,
                modifier = Modifier.size(Fw.dimens.icon),
            )
        }
    }
}

/**
 * The dock: four destinations, ≥ 56 dp tall, active state is olive text with a 24 dp oxide
 * underline at the top edge. Only the dock and sheets cast shadow.
 */
@Composable
fun FwDock(selected: DockTab, onSelect: (DockTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = Fw.dimens.dockElevation,
                shape = RectangleShape,
                clip = false,
                ambientColor = Fw.colors.sheetShadow,
                spotColor = Fw.colors.sheetShadow,
            )
            .background(Fw.colors.sheet)
            .drawBehind {
                drawLine(
                    color = Fw.colors.line,
                    start = androidx.compose.ui.geometry.Offset.Zero,
                    end = androidx.compose.ui.geometry.Offset(size.width, 0f),
                    strokeWidth = Fw.dimens.hairline.toPx(),
                )
            },
    ) {
        DockTab.entries.forEach { tab ->
            val active = tab == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Fw.dimens.dockItemHeight)
                    .selectable(
                        selected = active,
                        role = Role.Tab,
                        onClick = { onSelect(tab) },
                    ),
            ) {
                if (active) {
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .width(Fw.dimens.dockUnderlineWidth)
                            .height(Fw.dimens.dockUnderlineHeight)
                            .background(Fw.colors.oxide),
                    )
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(vertical = Fw.space.xs),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = null,
                        tint = if (active) Fw.colors.olive else Fw.colors.inkSoft,
                        modifier = Modifier.size(Fw.dimens.icon),
                    )
                    Spacer(Modifier.height(Fw.space.xxs))
                    Text(
                        text = tab.label,
                        style = Fw.type.micro,
                        color = if (active) Fw.colors.olive else Fw.colors.inkSoft,
                    )
                }
            }
        }
    }
}

@Preview(name = "Chrome", showBackground = true, widthDp = 360, heightDp = 520)
@Composable
private fun FwChromePreview() {
    FieldwrightTheme {
        FwScaffold(
            topBar = {
                FwTopBar(
                    operatorLabel = "Tam Nguyen · Lead inspector",
                    sampleChip = true,
                    syncLabel = "Synced 2 min ago · 3 queued",
                    onBrief = {},
                )
            },
            dock = {
                FwDock(selected = DockTab.GROUND, onSelect = {})
            },
        ) { padding ->
            Column(Modifier.padding(padding)) { }
        }
    }
}
