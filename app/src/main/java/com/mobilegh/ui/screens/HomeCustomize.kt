package com.mobilegh.ui.screens

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import com.mobilegh.R
import com.mobilegh.data.*
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Screen
import com.mobilegh.ui.components.*
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.launch

/** 快捷入口块定义 */
data class ShortcutItem(
    val id: String,
    val label: String,
    @param:DrawableRes val icon: Int,
    val color: Color,
    val isSearch: Boolean = false,
    val search: SavedSearch? = null,
)

/** 自定义首页：「我的工作」入口的显示与顺序、保存的搜索、收藏仓库。以首页相同的「块」样式展示与拖拽。 */
@Composable
fun HomeCustomizeScreen() {
    val g = Gh.c
    val nav = LocalNav.current
    val layout = HomePrefs.layout()
    var pendingSearch by remember { mutableStateOf<SavedSearch?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmReset by remember { mutableStateOf(false) }

    // 复用首页的图标、名称与颜色
    val builtins = homeShortcuts().associateBy { it.id }

    fun toItem(slotId: String): ShortcutItem? {
        val search = layout.search(slotId)
        if (search != null) {
            return ShortcutItem(
                id = slotId,
                label = search.name,
                icon = savedSearchIcon(search.icon),
                color = if (search.query.pulls) g.accent else g.success,
                isSearch = true,
                search = search,
            )
        }
        val builtin = builtins[slotId] ?: return null
        return ShortcutItem(
            id = slotId,
            label = builtin.label,
            icon = builtin.icon,
            color = builtin.color,
            isSearch = false,
        )
    }

    val slots = layout.slots()
    val activeSlots = slots.filter { it.visible }
    val inactiveSlots = slots.filterNot { it.visible }

    val activeItems = activeSlots.mapNotNull { toItem(it.id) }
    val inactiveItems = inactiveSlots.mapNotNull { toItem(it.id) }

    // 拖拽状态与槽位测量
    var draggedItem by remember { mutableStateOf<ShortcutItem?>(null) }
    var draggedFromActive by remember { mutableStateOf(true) }
    var dragItemStartInRoot by remember { mutableStateOf(Offset.Zero) }
    var dragDelta by remember { mutableStateOf(Offset.Zero) }
    val slotBounds = remember { mutableStateMapOf<String, Rect>() }
    var containerPosInRoot by remember { mutableStateOf(Offset.Zero) }
    var activeMenuTarget by remember { mutableStateOf<ShortcutItem?>(null) }

    // 区域坐标范围
    var activeZoneBounds by remember { mutableStateOf(Rect.Zero) }
    var inactiveZoneBounds by remember { mutableStateOf(Rect.Zero) }

    fun moveActiveItem(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex || fromIndex !in activeItems.indices || toIndex !in activeItems.indices) return
        val activeIds = activeItems.map { it.id }.toMutableList()
        val moved = activeIds.removeAt(fromIndex)
        activeIds.add(toIndex, moved)
        val newSlots = activeIds.map { ShortcutSlot(it, visible = true) } +
                inactiveSlots.map { ShortcutSlot(it.id, visible = false) }
        HomePrefs.setShortcuts(newSlots)
    }

    fun addInactiveItemToActive(itemId: String, targetIndex: Int = activeItems.size) {
        val activeIds = activeItems.map { it.id }.toMutableList()
        val insertAt = targetIndex.coerceIn(0, activeIds.size)
        activeIds.add(insertAt, itemId)
        val newSlots = activeIds.map { ShortcutSlot(it, visible = true) } +
                inactiveSlots.filter { it.id != itemId }.map { ShortcutSlot(it.id, visible = false) }
        HomePrefs.setShortcuts(newSlots)
    }

    val currentPointer = dragItemStartInRoot + dragDelta
    val hoveredTargetId = if (draggedItem != null && (activeZoneBounds.contains(currentPointer) || (currentPointer.y >= activeZoneBounds.top - 80f && currentPointer.y <= activeZoneBounds.bottom + 80f))) {
        activeItems.minByOrNull { item ->
            val bounds = slotBounds[item.id] ?: Rect.Zero
            (bounds.center - currentPointer).getDistance()
        }?.id
    } else null

    Page(
        tr("自定义首页"),
        subtitle = tr("仅保存在本机"),
        snackbar = snackbar,
        actions = {
            MoreMenu(
                listOf(
                    MenuAction(tr("新建快捷键")) { nav.push(Screen.NewShortcut) },
                    MenuAction(tr("恢复默认入口")) { confirmReset = true },
                ),
            )
        },
    ) { pad ->
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { containerPosInRoot = it.positionInRoot() }
        ) {
            Column(
                Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .verticalScrollWithBar(enabled = draggedItem == null)
                    .padding(vertical = 12.dp),
            ) {
                // 1. 工作区（首页展示的入口）
                SectionTitle(tr("我的工作（工作区）"))
                Hint(tr("首页当前展示的块。拖动调整位置，点击 － 或拖出工作区即可移出。"))
                Spacer(Modifier.height(10.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .onGloballyPositioned { activeZoneBounds = it.boundsInRoot() },
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                    ) {
                        if (activeItems.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    tr("工作区暂无入口，从下方「未添加功能」点击 ＋ 或拖入即可显示"),
                                    fontSize = 13.sp,
                                    color = g.fgMuted,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        } else {
                            activeItems.chunked(4).forEach { row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    horizontalArrangement = Arrangement.Start,
                                ) {
                                    row.forEach { item ->
                                        val isHover = hoveredTargetId == item.id && draggedItem?.id != item.id
                                        Box(Modifier.weight(1f)) {
                                            ShortcutBlock(
                                                item = item,
                                                isActive = true,
                                                isDragging = draggedItem?.id == item.id,
                                                isHoverTarget = isHover,
                                                onClick = { activeMenuTarget = item },
                                                onAction = { HomePrefs.setShortcutVisible(item.id, false) },
                                                onDelete = { pendingSearch = item.search },
                                                onDragStart = { startPos ->
                                                    draggedItem = item
                                                    draggedFromActive = true
                                                    dragItemStartInRoot = startPos
                                                    dragDelta = Offset.Zero
                                                },
                                                onDrag = { delta -> dragDelta += delta },
                                                onDragEnd = {
                                                    val endPos = dragItemStartInRoot + dragDelta
                                                    if (inactiveZoneBounds.contains(endPos)) {
                                                        // 拖出工作区或拖入未添加区：移出
                                                        HomePrefs.setShortcutVisible(item.id, false)
                                                    } else if (activeZoneBounds.contains(endPos) || (endPos.y >= activeZoneBounds.top - 100f && endPos.y <= activeZoneBounds.bottom + 100f)) {
                                                        // 工作区内部拖动排序：找最近槽位
                                                        val currentIndex = activeItems.indexOfFirst { it.id == item.id }
                                                        val targetIndex = activeItems.indices.minByOrNull { i ->
                                                            val bounds = slotBounds[activeItems[i].id] ?: Rect.Zero
                                                            (bounds.center - endPos).getDistance()
                                                        } ?: currentIndex
                                                        if (currentIndex >= 0 && targetIndex >= 0 && targetIndex != currentIndex) {
                                                            moveActiveItem(currentIndex, targetIndex)
                                                        }
                                                    }
                                                    draggedItem = null
                                                    dragDelta = Offset.Zero
                                                },
                                                onPositioned = { rect -> slotBounds[item.id] = rect },
                                            )
                                        }
                                    }
                                    repeat(4 - row.size) {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                // 2. 未添加功能区域
                SectionTitle(tr("未添加功能"))
                Hint(tr("点击 ＋ 或拖入上方工作区，即可在首页「我的工作」显示。"))
                Spacer(Modifier.height(10.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .onGloballyPositioned { inactiveZoneBounds = it.boundsInRoot() },
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                    ) {
                        if (inactiveItems.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 20.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    tr("所有功能都已在工作区中"),
                                    fontSize = 13.sp,
                                    color = g.fgMuted,
                                )
                            }
                        } else {
                            inactiveItems.chunked(4).forEach { row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                    horizontalArrangement = Arrangement.Start,
                                ) {
                                    row.forEach { item ->
                                        Box(Modifier.weight(1f)) {
                                            ShortcutBlock(
                                                item = item,
                                                isActive = false,
                                                isDragging = draggedItem?.id == item.id,
                                                onClick = { addInactiveItemToActive(item.id) },
                                                onAction = { addInactiveItemToActive(item.id) },
                                                onDelete = { pendingSearch = item.search },
                                                onDragStart = { startPos ->
                                                    draggedItem = item
                                                    draggedFromActive = false
                                                    dragItemStartInRoot = startPos
                                                    dragDelta = Offset.Zero
                                                },
                                                onDrag = { delta -> dragDelta += delta },
                                                onDragEnd = {
                                                    val endPos = dragItemStartInRoot + dragDelta
                                                    if (activeZoneBounds.contains(endPos) || endPos.y <= activeZoneBounds.bottom + 80f) {
                                                        // 拖入上方工作区：添加
                                                        val targetIndex = activeItems.indices.minByOrNull { i ->
                                                            val bounds = slotBounds[activeItems[i].id] ?: Rect.Zero
                                                            (bounds.center - endPos).getDistance()
                                                        } ?: activeItems.size
                                                        addInactiveItemToActive(item.id, targetIndex)
                                                    }
                                                    draggedItem = null
                                                    dragDelta = Offset.Zero
                                                },
                                                onPositioned = { rect -> slotBounds[item.id] = rect },
                                            )
                                        }
                                    }
                                    repeat(4 - row.size) {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 3. 新建快捷键入口卡片（独立全屏页）
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clickable { nav.push(Screen.NewShortcut) },
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(g.accent.copy(alpha = if (g.dark) 0.18f else 0.11f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Oc(R.drawable.oc_plus, g.accent, 22.dp)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                tr("新建快捷键"),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = g.fg,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                tr("定制 Issues、PR 或特定条件的快捷入口"),
                                fontSize = 12.sp,
                                color = g.fgMuted,
                            )
                        }
                        Oc(R.drawable.oc_chevron_right, g.fgMuted, 16.dp)
                    }
                }

                Spacer(Modifier.height(24.dp))

                // 4. 收藏仓库
                SectionTitle(
                    tr("收藏仓库"),
                    trailing = {
                        SectionActionChip(tr("同步置顶"), tooltip = tr("同步 GitHub 置顶仓库")) {
                            scope.launch {
                                val login = Session.login ?: return@launch
                                val pins = runCatching { GitHub.pinned(login) }.getOrDefault(emptyList())
                                if (pins.isNotEmpty()) {
                                    HomePrefs.syncWithPinned(pins, merge = true)
                                    snackbar.showSnackbar(trf("已同步 {0} 个 GitHub 置顶仓库", pins.size))
                                } else {
                                    snackbar.showSnackbar(tr("GitHub 暂无置顶仓库"))
                                }
                            }
                        }
                    },
                )
                if (layout.favorites.isEmpty()) {
                    Hint(tr("可与 GitHub 官方置顶仓库自动同步，或在仓库页右上角菜单选择「加入收藏」。"))
                } else {
                    Hint(tr("在仓库列表中长按或使用下移/移出进行管理。"))
                    Spacer(Modifier.height(8.dp))
                    MenuGroup {
                        layout.favorites.forEachIndexed { i, f ->
                            if (i > 0) HDivider()
                            EditRow(
                                icon = R.drawable.oc_repo,
                                iconTint = g.fgMuted,
                                title = "${f.owner} / ${f.name}",
                                desc = null,
                                canUp = i > 0,
                                canDown = i < layout.favorites.lastIndex,
                                onMove = { d -> HomePrefs.moveFavorite(i, d) },
                            ) {
                                OcButton(
                                    R.drawable.oc_trash,
                                    { removeFavoriteWithUndo(scope, snackbar, i, f) },
                                    g.fgMuted,
                                    description = trf("移出收藏 {0}", "${f.owner} / ${f.name}"),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(36.dp))
            }

            // 悬浮拖拽时的跟随块
            draggedItem?.let { item ->
                val localPos = (dragItemStartInRoot + dragDelta) - containerPosInRoot
                val density = LocalDensity.current
                val halfWidthPx = with(density) { 36.dp.toPx() }
                val halfHeightPx = with(density) { 36.dp.toPx() }

                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = (localPos.x - halfWidthPx).roundToInt(),
                                y = (localPos.y - halfHeightPx).roundToInt(),
                            )
                        }
                        .zIndex(999f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(g.canvas)
                        .border(1.5.dp, item.color, RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(item.color.copy(alpha = 0.20f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Oc(item.icon, item.color, 24.dp)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            item.label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = g.fg,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }

    if (confirmReset) ConfirmResetShortcutsDialog { confirmReset = false }
    pendingSearch?.let { search -> ConfirmDeleteSearchDialog(search) { pendingSearch = null } }
    activeMenuTarget?.let { item ->
        val idx = activeItems.indexOfFirst { it.id == item.id }
        if (idx >= 0) {
            ShortcutOrderDialog(
                item = item,
                currentIndex = idx,
                total = activeItems.size,
                onMove = { from, to -> moveActiveItem(from, to) },
                onRemove = { HomePrefs.setShortcutVisible(item.id, false) },
                onDismiss = { activeMenuTarget = null },
            )
        }
    }
}

/** 类似首页「我的工作」的块（带拖动手势与角标操作） */
@Composable
private fun ShortcutBlock(
    item: ShortcutItem,
    isActive: Boolean,
    isDragging: Boolean,
    isHoverTarget: Boolean = false,
    onClick: () -> Unit = {},
    onAction: () -> Unit,
    onDelete: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onPositioned: (Rect) -> Unit = {},
) {
    val g = Gh.c
    var blockPosInRoot by remember { mutableStateOf(Offset.Zero) }

    val alpha by animateFloatAsState(if (isDragging) 0.20f else 1f, label = "block_alpha")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha }
            .onGloballyPositioned {
                val bounds = it.boundsInRoot()
                blockPosInRoot = bounds.center
                onPositioned(bounds)
            }
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .pointerInput(item.id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { onDragStart(blockPosInRoot) },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount)
                    },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd,
                )
            }
            .then(
                if (isHoverTarget) {
                    Modifier.border(2.dp, g.accent, RoundedCornerShape(10.dp))
                } else Modifier
            )
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.size(54.dp)) {
            // 中心图标方块（与首页完全一致）
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        item.color.copy(
                            alpha = if (isActive) (if (g.dark) 0.18f else 0.11f) else (if (g.dark) 0.09f else 0.05f),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Oc(
                    item.icon,
                    if (isActive) item.color else g.fgMuted,
                    22.dp,
                )
            }

            // 右上角操作角标：工作区显示红色的 －（或删除），未添加显示绿色的 ＋
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(
                        if (isActive) g.danger.copy(alpha = if (g.dark) 0.25f else 0.14f)
                        else g.btnPrimary.copy(alpha = if (g.dark) 0.25f else 0.16f),
                    )
                    .border(
                        1.dp,
                        if (isActive) g.danger.copy(alpha = 0.45f) else g.btnPrimary.copy(alpha = 0.45f),
                        CircleShape,
                    )
                    .clickable(role = Role.Button, onClick = onAction)
                    .semantics {
                        contentDescription = (if (isActive) tr("移出工作区") else tr("加入工作区")) + " " + item.label
                    },
                contentAlignment = Alignment.Center,
            ) {
                Oc(
                    if (isActive) R.drawable.oc_x else R.drawable.oc_plus,
                    if (isActive) g.danger else g.btnPrimary,
                    10.dp,
                )
            }

            // 如果是自定义保存的搜索，在左上角提供删除图标
            if (item.isSearch && !isActive) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(g.canvasSubtle)
                        .border(1.dp, g.border, CircleShape)
                        .clickable(role = Role.Button, onClick = onDelete)
                        .semantics { contentDescription = trf("删除搜索 {0}", item.label) },
                    contentAlignment = Alignment.Center,
                ) {
                    Oc(R.drawable.oc_trash, g.fgMuted, 10.dp)
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            item.label,
            fontSize = 12.sp,
            fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
            color = if (isActive) g.fg else g.fgMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** 调整快捷入口顺序的快捷菜单弹窗 */
@Composable
private fun ShortcutOrderDialog(
    item: ShortcutItem,
    currentIndex: Int,
    total: Int,
    onMove: (from: Int, to: Int) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val g = Gh.c
    GhDialog(
        title = item.label,
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                tr("调整入口顺序"),
                fontSize = 13.sp,
                color = g.fgMuted,
            )
            Spacer(Modifier.height(4.dp))
            if (currentIndex > 0) {
                GhButton(
                    tr("向左移动"),
                    icon = R.drawable.oc_arrow_left,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    onMove(currentIndex, currentIndex - 1)
                    onDismiss()
                }
                GhButton(
                    tr("移到最前面"),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    onMove(currentIndex, 0)
                    onDismiss()
                }
            }
            if (currentIndex < total - 1) {
                GhButton(
                    tr("向右移动"),
                    icon = R.drawable.oc_arrow_right,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    onMove(currentIndex, currentIndex + 1)
                    onDismiss()
                }
                GhButton(
                    tr("移到最后面"),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    onMove(currentIndex, total - 1)
                    onDismiss()
                }
            }
            GhButton(
                tr("移出工作区"),
                modifier = Modifier.fillMaxWidth(),
            ) {
                onRemove()
                onDismiss()
            }
        }
    }
}

/**
 * 新建快捷键全屏界面：
 * 具有即时效果预览、名称、图标选择器、检索类型（Issues / PR）、仓库范围与状态条件。
 */
@Composable
fun NewShortcutScreen() {
    val g = Gh.c
    val nav = LocalNav.current
    val ctx = rememberCtx()

    var name by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf("search") }
    var pulls by remember { mutableStateOf(false) }
    var repo by remember { mutableStateOf("") }
    var state by remember { mutableStateOf(IssueStateFilter.Open) }
    var involves by remember { mutableStateOf(Involvement.Created) }
    var text by remember { mutableStateOf("") }

    val repoText = repo.trim().removePrefix("https://github.com/").trimEnd('/')
    val repoOk = repoText.isEmpty() || IssueQuery(repo = repoText).normalized().repo != null
    val kinds = Involvement.entries.filter { pulls || !it.pullsOnly }
    val query = IssueQuery(
        pulls = pulls,
        repo = repoText.ifEmpty { null },
        state = state,
        text = text,
        involves = if (repoText.isEmpty()) involves.takeIf { it in kinds } ?: Involvement.Created else null,
    ).normalized()

    val nameOver = savedSearchNameOverflow(name)
    val valid = name.isNotBlank() && nameOver == 0 && repoOk
    val previewColor = if (pulls) g.accent else g.success

    Page(
        tr("新建快捷键"),
        back = true,
        actions = {
            GhButton(
                tr("保存"),
                primary = true,
                enabled = valid,
            ) {
                runCatching { HomePrefs.addSearch(name, icon, query) ?: error(tr("请先登录")) }
                    .onSuccess {
                        ctx.toast(tr("已创建快捷键并添加到首页「我的工作」"))
                        nav.pop()
                    }
                    .onFailure { ctx.toast(it.message ?: tr("保存失败")) }
            }
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScrollWithBar()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 1. 实时首页效果预览卡片
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        tr("首页预览"),
                        fontSize = 12.sp,
                        color = g.fgMuted,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(14.dp))
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(previewColor.copy(alpha = if (g.dark) 0.20f else 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Oc(savedSearchIcon(icon), previewColor, 26.dp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        name.ifBlank { tr("快捷键名称") },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (name.isBlank()) g.fgMuted else g.fg,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(g.canvasSubtle)
                            .border(1.dp, g.border, RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Oc(if (pulls) R.drawable.oc_git_pull_request else R.drawable.oc_issue_opened, previewColor, 12.dp)
                        Text(
                            query.summary(),
                            fontSize = 11.sp,
                            color = g.fgMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // 2. 基本信息卡片
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(tr("基本信息"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = g.fg)

                    GhField(
                        name,
                        { name = it.take(HomeLayout.MAX_NAME + 8) },
                        tr("名称"),
                        placeholder = tr("例如：待处理 Bug、我的 PR"),
                    )

                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (nameOver > 0) {
                            Text(
                                trf("名称不能超过 {0} 个字符", HomeLayout.MAX_NAME),
                                Modifier.weight(1f),
                                fontSize = 12.sp,
                                color = g.danger,
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        Text(
                            "${name.trim().length}/${HomeLayout.MAX_NAME}",
                            fontSize = 12.sp,
                            color = if (nameOver > 0) g.danger else g.fgMuted,
                        )
                    }

                    SavedSearchIconPicker(icon) { icon = it }
                }
            }

            // 3. 类型与范围卡片
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(tr("类型与范围"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = g.fg)

                    Segmented(listOf("Issues", "Pull Requests"), if (pulls) 1 else 0) {
                        pulls = it == 1
                        if (icon == "issue" || icon == "pr") icon = if (pulls) "pr" else "issue"
                    }

                    GhField(
                        repo,
                        { repo = it },
                        tr("指定仓库（可选）"),
                        placeholder = "owner/name",
                    )
                    if (!repoOk) {
                        Text(tr("格式应为 owner/name"), fontSize = 12.sp, color = g.danger)
                    }
                }
            }

            // 4. 筛选规则卡片
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(tr("筛选条件"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = g.fg)

                    if (repoText.isEmpty()) {
                        Column {
                            Text(tr("与我相关"), fontSize = 13.sp, color = g.fgMuted)
                            Spacer(Modifier.height(6.dp))
                            Segmented(
                                kinds.map { it.label },
                                kinds.indexOf(involves).coerceAtLeast(0),
                            ) { involves = kinds[it] }
                        }
                    }

                    Column {
                        Text(tr("状态"), fontSize = 13.sp, color = g.fgMuted)
                        Spacer(Modifier.height(6.dp))
                        Chips(
                            IssueStateFilter.entries.map { it.label },
                            IssueStateFilter.entries.indexOf(state),
                        ) { state = IssueStateFilter.entries[it] }
                    }

                    GhField(
                        text,
                        { text = it },
                        tr("关键词与筛选（可选）"),
                        placeholder = tr("支持 label:bug 等高级搜索语法"),
                    )
                }
            }

            // 5. 底部保存大按钮
            GhButton(
                tr("保存并添加到工作区"),
                Modifier.fillMaxWidth(),
                primary = true,
                enabled = valid,
                icon = R.drawable.oc_check,
            ) {
                runCatching { HomePrefs.addSearch(name, icon, query) ?: error(tr("请先登录")) }
                    .onSuccess {
                        ctx.toast(tr("已创建快捷键并添加到首页「我的工作」"))
                        nav.pop()
                    }
                    .onFailure { ctx.toast(it.message ?: tr("保存失败")) }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 恢复默认入口前的确认：只重置入口的顺序和显示开关，不删除保存的搜索和收藏仓库 */
@Composable
private fun ConfirmResetShortcutsDialog(onDismiss: () -> Unit) {
    val g = Gh.c
    val ctx = rememberCtx()
    GhDialog(tr("恢复默认入口？"), onDismiss, confirm = tr("恢复"), onConfirm = {
        HomePrefs.resetShortcuts()
        ctx.toast(tr("已恢复默认入口"))
        onDismiss()
    }) {
        Text(tr("首页入口的顺序和显示开关会回到默认设置。"), color = g.fg)
    }
}

/** 纯逻辑：名称（去掉首尾空白后）超出上限的字数，0 表示合法。提示文字与保存按钮共用这一个判断 */
fun savedSearchNameOverflow(name: String, max: Int = HomeLayout.MAX_NAME): Int =
    (name.trim().length - max).coerceAtLeast(0)

@Composable
private fun Hint(text: String) {
    Text(
        text,
        Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = Gh.c.fgMuted,
    )
}

/** 收藏仓库行 */
@Composable
private fun EditRow(
    @DrawableRes icon: Int,
    iconTint: Color,
    title: String,
    desc: String?,
    canUp: Boolean,
    canDown: Boolean,
    onMove: (Int) -> Unit,
    trailing: @Composable () -> Unit,
) {
    val g = Gh.c
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MenuIcon(icon, iconTint)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = g.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (desc != null) {
                Text(desc, fontSize = 12.sp, lineHeight = 16.sp, color = g.fgMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        OcButton(R.drawable.oc_arrow_up, { onMove(-1) }, g.fgMuted, canUp, description = trf("上移{0}", title))
        OcButton(R.drawable.oc_arrow_down, { onMove(1) }, g.fgMuted, canDown, description = trf("下移{0}", title))
        trailing()
    }
}
