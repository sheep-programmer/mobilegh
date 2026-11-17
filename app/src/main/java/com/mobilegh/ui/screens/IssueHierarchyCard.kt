package com.mobilegh.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.GitHub
import com.mobilegh.data.IssueHierarchy
import com.mobilegh.data.IssueRef
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Screen
import com.mobilegh.nav.friendly
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.GhField
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.OcButton
import com.mobilegh.ui.components.ScrollbarLazyColumn
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.components.toast
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

fun hierarchyCandidates(currentNodeId: String, children: List<IssueRef>, issues: List<IssueRef>): List<IssueRef> {
    val excluded = children.mapTo(mutableSetOf(currentNodeId)) { it.nodeId }
    return issues.filter { it.nodeId !in excluded }
}

object IssueHierarchyActions {
    suspend fun replaceParent(
        currentNodeId: String,
        parentNodeId: String,
        operation: suspend (String, String, Boolean) -> Unit = { parent, current, replace -> IssueHierarchy.addSubIssue(parent, current, replace) },
    ): Result<Unit> = runOperation { operation(parentNodeId, currentNodeId, true) }

    suspend fun removeChild(
        currentNodeId: String,
        childNodeId: String,
        operation: suspend (String, String) -> Unit = { current, child -> IssueHierarchy.removeSubIssue(current, child) },
    ): Result<Unit> = runOperation { operation(currentNodeId, childNodeId) }

    suspend fun removeParent(
        parentNodeId: String,
        currentNodeId: String,
        operation: suspend (String, String) -> Unit = { parent, current -> IssueHierarchy.removeParent(parent, current) },
    ): Result<Unit> = runOperation { operation(parentNodeId, currentNodeId) }

    private suspend inline fun runOperation(block: () -> Unit): Result<Unit> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
}

@Composable
fun IssueHierarchyCard(
    owner: String,
    name: String,
    currentNumber: Int,
    currentNodeId: String,
    hierarchy: IssueHierarchy,
    canEdit: Boolean,
    busy: Boolean,
    onAdd: () -> Unit,
    onChangeParent: () -> Unit,
    onRemoveParent: () -> Unit,
    onRemoveChild: (IssueRef) -> Unit,
) {
    val g = Gh.c
    val nav = LocalNav.current
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()
            .border(1.dp, g.border, RoundedCornerShape(8.dp)),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Oc(R.drawable.oc_issue_opened, g.fgMuted, 16.dp)
            Spacer(Modifier.width(8.dp))
            Text(tr("层级"), fontSize = 13.sp, color = g.fgMuted, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            if (canEdit) {
                Text(tr("添加子 Issue"), Modifier.clickable(enabled = !busy, onClick = onAdd).padding(8.dp), fontSize = 13.sp, color = g.accent)
            }
        }
        hierarchy.parent?.let { parent ->
            Text(tr("父 Issue"), Modifier.padding(horizontal = 12.dp), fontSize = 12.sp, color = g.fgMuted)
            IssueRefRow(parent, busy) { nav.push(Screen.IssueDetail(parent.owner, parent.name, parent.number, false)) }
            if (canEdit) Row(Modifier.padding(start = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("更换父 Issue"), Modifier.clickable(enabled = !busy, onClick = onChangeParent).padding(vertical = 6.dp), fontSize = 13.sp, color = g.accent)
                Spacer(Modifier.width(16.dp))
                Text(tr("移除父 Issue"), Modifier.clickable(enabled = !busy, onClick = onRemoveParent).padding(vertical = 6.dp), fontSize = 13.sp, color = g.danger)
            }
        } ?: if (canEdit) {
            Text(tr("设置父 Issue"), Modifier.clickable(enabled = !busy, onClick = onChangeParent).padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 13.sp, color = g.accent)
        } else Unit
        hierarchy.summary?.let { summary ->
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(trf("已完成 {0} / {1} · {2}%", summary.completed, summary.total, summary.percentCompleted), fontSize = 12.sp, color = g.fgMuted)
                LinearProgressIndicator(
                    progress = { (summary.percentCompleted / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    color = g.success,
                    trackColor = g.border,
                )
            }
        }
        if (hierarchy.subIssues.isEmpty()) {
            Text(tr("暂无子 Issue"), Modifier.padding(horizontal = 12.dp, vertical = 8.dp), fontSize = 13.sp, color = g.fgMuted)
        } else hierarchy.subIssues.forEachIndexed { index, issue ->
            if (index > 0 || hierarchy.parent != null || hierarchy.summary != null) HDivider(Modifier.padding(start = 12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BoxedRef(issue, Modifier.weight(1f), busy) { nav.push(Screen.IssueDetail(issue.owner, issue.name, issue.number, false)) }
                if (canEdit) OcButton(R.drawable.oc_x_circle, { onRemoveChild(issue) }, tint = g.fgMuted, enabled = !busy, description = tr("移除子 Issue"))
            }
        }
        if (canEdit) Text(
            tr("新建子 Issue"),
            Modifier.clickable(enabled = !busy) { nav.push(Screen.NewIssue(owner, name, parentNodeId = currentNodeId, parentNumber = currentNumber)) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            fontSize = 13.sp, color = g.accent,
        )
    }
}

@Composable
fun ClosingIssuesCard(issues: List<IssueRef>) {
    val g = Gh.c
    val nav = LocalNav.current
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()
            .border(1.dp, g.border, RoundedCornerShape(8.dp)),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Oc(R.drawable.oc_issue_closed, g.done, 16.dp)
            Spacer(Modifier.width(8.dp))
            Text(tr("关闭关联"), fontSize = 13.sp, color = g.fgMuted, fontWeight = FontWeight.Medium)
        }
        issues.forEachIndexed { index, issue ->
            if (index > 0) HDivider(Modifier.padding(start = 12.dp))
            IssueRefRow(issue, busy = false) {
                nav.push(Screen.IssueDetail(issue.owner, issue.name, issue.number, false))
            }
        }
    }
}

@Composable
private fun IssueRefRow(issue: IssueRef, busy: Boolean, onClick: () -> Unit) = BoxedRef(issue, Modifier.fillMaxWidth(), busy, onClick)

@Composable
private fun BoxedRef(issue: IssueRef, modifier: Modifier, busy: Boolean, onClick: () -> Unit) {
    val g = Gh.c
    val open = issue.state == "open"
    Row(modifier.clickable(enabled = !busy, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Oc(if (open) R.drawable.oc_issue_opened else R.drawable.oc_issue_closed, if (open) g.success else g.done, 16.dp)
        Spacer(Modifier.width(8.dp))
        Column {
            Text("${issue.owner}/${issue.name}#${issue.number}", fontSize = 12.sp, color = g.fgMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(issue.title, fontSize = 14.sp, color = g.fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun IssueHierarchyPicker(
    owner: String,
    name: String,
    currentNodeId: String,
    existing: List<IssueRef>,
    title: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onPick: (IssueRef) -> Unit,
) {
    val g = Gh.c
    val ctx = rememberCtx()
    val scope = LocalEntry.current.scope
    var text by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<IssueRef>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var nextPage by remember { mutableStateOf(1) }
    var fetchedCount by remember { mutableStateOf(0) }
    var hasMore by remember { mutableStateOf(false) }
    var searchedQuery by remember { mutableStateOf("") }
    fun search(loadMore: Boolean = false) {
        if (searching || (loadMore && !hasMore)) return
        val page = if (loadMore) nextPage else 1
        val query = if (loadMore) searchedQuery else "repo:$owner/$name ${text.trim()} is:issue".trim()
        if (!loadMore) {
            results = null
            hasMore = false
            searchedQuery = query
        }
        searching = true
        scope.launch {
            try {
                val found = GitHub.searchIssues(query, page)
                val mapped = found.items.mapNotNull { issue ->
                    issue.nodeId?.takeIf { it.isNotBlank() }?.let { IssueRef(it, issue.number, issue.title, issue.state, owner, name) }
                }
                val previous = if (page == 1) emptyList() else results.orEmpty()
                val additions = hierarchyCandidates(currentNodeId, existing + previous, mapped).distinctBy { it.nodeId }
                results = previous + additions
                val previousFetched = if (page == 1) 0 else fetchedCount
                fetchedCount = previousFetched + found.items.size
                nextPage = page + 1
                hasMore = found.items.isNotEmpty() && fetchedCount < found.totalCount
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ctx.toast(e.friendly())
            } finally {
                searching = false
            }
        }
    }
    GhDialog(title, { if (!busy) onDismiss() }, confirm = if (searching) tr("搜索中…") else tr("搜索"), confirmEnabled = !searching && !busy, onConfirm = { search() }) {
        Column(Modifier.heightIn(max = 420.dp)) {
            GhField(text, { text = it }, tr("搜索仓库内的 Issue"), enabled = !busy)
            if (searching) CircularProgressIndicator(Modifier.padding(12.dp), color = g.fgMuted)
            else results?.let { found ->
                if (found.isEmpty()) Text(tr("没有可添加的 Issue"), Modifier.padding(vertical = 12.dp), color = g.fgMuted)
                else ScrollbarLazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(found, key = { it.nodeId }) { issue -> IssueRefRow(issue, busy) { onPick(issue) } }
                    if (hasMore) item(key = "hierarchy-load-more") {
                        Text(
                            tr("加载更多"),
                            Modifier.fillMaxWidth().clickable(enabled = !busy) { search(loadMore = true) }.padding(12.dp),
                            color = g.accent,
                        )
                    }
                }
            }
        }
    }
}
