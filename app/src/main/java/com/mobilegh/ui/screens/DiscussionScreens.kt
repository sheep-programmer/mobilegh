package com.mobilegh.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.data.Discussion
import com.mobilegh.data.DiscussionAuthor
import com.mobilegh.data.DiscussionCategory
import com.mobilegh.data.DiscussionComment
import com.mobilegh.data.DiscussionPage
import com.mobilegh.data.DiscussionWindow
import com.mobilegh.data.Discussions
import com.mobilegh.data.DiscussionsClient
import com.mobilegh.data.Session
import com.mobilegh.data.DraftValue
import com.mobilegh.ui.components.rememberDraft
import com.mobilegh.ui.components.DraftBinding
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Screen
import com.mobilegh.nav.friendly
import com.mobilegh.nav.rememberLoader
import com.mobilegh.nav.retain
import com.mobilegh.ui.components.Avatar
import com.mobilegh.ui.components.Chips
import com.mobilegh.ui.components.Dropdown
import com.mobilegh.ui.components.EmptyState
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.GhField
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.HtmlView
import com.mobilegh.ui.components.LoadBox
import com.mobilegh.ui.components.Loading
import com.mobilegh.ui.components.MenuAction
import com.mobilegh.ui.components.MoreMenu
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.openBrowser
import com.mobilegh.ui.components.relTime
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One retained pager per connection; filter changes replace its window and cancel old requests. */
internal class DiscussionCursorPager<T>(
    private val scope: CoroutineScope,
    private val limit: Int,
    private val id: (T) -> String,
) {
    var items by mutableStateOf<List<T>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var loaded by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var hasMore by mutableStateOf(true)
        private set
    var limited by mutableStateOf(false)
        private set
    var totalCount by mutableIntStateOf(0)
        private set
    private var window = DiscussionWindow(limit, id)
    private var fetch: (suspend (String?) -> DiscussionPage<T>)? = null
    private var job: Job? = null
    private var generation = 0
    private var replaceOnRetry = false

    fun restart(source: suspend (String?) -> DiscussionPage<T>, seed: DiscussionPage<T>? = null) {
        stop()
        fetch = source
        window = DiscussionWindow(limit, id)
        items = emptyList()
        loaded = false
        error = null
        limited = false
        hasMore = true
        totalCount = 0
        if (seed == null) loadMore() else publish(seed, replace = true)
    }

    fun stop() {
        generation++
        job?.cancel()
        job = null
        loading = false
    }

    fun loadMore() {
        if (!loading && hasMore) request(replace = false)
    }

    fun refresh() {
        stop()
        request(replace = true)
    }

    fun retry() {
        if (!loading) request(replaceOnRetry)
    }

    private fun request(replace: Boolean) {
        val source = fetch ?: return
        val cursor = if (replace) null else window.endCursor
        val version = ++generation
        replaceOnRetry = replace
        loading = true
        error = null
        job = scope.launch {
            try {
                val page = withContext(Dispatchers.Default) { source(cursor) }
                if (version == generation) publish(page, replace)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (version == generation) error = e.friendly()
            } finally {
                // An older cancelled request must never clear a newer request's loading state.
                if (version == generation) {
                    loading = false
                    loaded = true
                }
            }
        }
    }

    private fun publish(page: DiscussionPage<T>, replace: Boolean) {
        window.accept(page, replace)
        items = window.items
        hasMore = window.hasNextPage
        limited = window.limited
        totalCount = window.totalCount
        loaded = true
    }
}

@Composable
fun DiscussionsScreen(
    owner: String,
    name: String,
    canWrite: Boolean = Session.token != null && !Session.authExpired,
) {
    val nav = LocalNav.current
    val g = Gh.c
    val repository = rememberLoader("discussions:repository") { Discussions.repository(owner, name) }
    var categoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var answeredFilter by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var composing by rememberSaveable { mutableStateOf(false) }

    Page("Discussions", subtitle = "$owner/$name") { padding ->
        LoadBox(repository, Modifier.padding(padding)) { repo ->
            if (!repo.enabled) {
                EmptyState("此仓库未启用 Discussions", modifier = Modifier.fillMaxSize())
            } else {
                val categories = retain("discussions:categories") { entry ->
                    DiscussionCursorPager<DiscussionCategory>(entry.scope, DiscussionsClient.MAX_CATEGORIES) { it.id }
                        .also { it.restart({ cursor -> Discussions.categories(owner, name, cursor) }) }
                }
                val discussions = retain("discussions:list") { entry ->
                    DiscussionCursorPager<Discussion>(entry.scope, DiscussionsClient.MAX_DISCUSSIONS) { it.id }
                }
                LaunchedEffect(categoryId, answeredFilter) {
                    val answered = when (answeredFilter) { 1 -> false; 2 -> true; else -> null }
                    discussions.restart({ cursor -> Discussions.list(owner, name, cursor, categoryId, answered) })
                }
                val allowed = repo.canWrite(canWrite && Session.token != null && !Session.authExpired)
                val shown = discussions.items.filter { it.matches(query) }
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Dropdown(
                                    categories.items.find { it.id == categoryId }?.name ?: "全部分类",
                                    listOf("全部分类") + categories.items.map { it.name },
                                    { index -> categoryId = categories.items.getOrNull(index - 1)?.id },
                                    Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(8.dp))
                                GhButton("新讨论", enabled = allowed && categories.items.isNotEmpty()) { composing = true }
                            }
                            CursorFooter(categories, "加载更多分类", "分类")
                            GhField(query, { query = it }, "搜索已加载讨论")
                            Text("搜索标题、作者、分类和编号 · 已加载 ${discussions.items.size}/${discussions.totalCount}", color = g.fgMuted, fontSize = 12.sp)
                            if (!allowed) Text(if (repo.archived) "仓库已归档，仅可阅读" else "当前账号仅可阅读讨论", color = g.fgMuted, fontSize = 12.sp)
                        }
                        Chips(listOf("全部", "未解答", "已解答"), answeredFilter) { answeredFilter = it }
                        GhButton("刷新", Modifier.padding(horizontal = 12.dp), enabled = !discussions.loading) {
                            repository.refresh()
                            categories.refresh()
                            discussions.refresh()
                        }
                        HDivider(Modifier.padding(top = 8.dp))
                    }
                    items(shown, key = { it.id }) { discussion ->
                        Column(Modifier.fillMaxWidth().clickable {
                            nav.push(Screen.DiscussionDetail(owner, name, discussion.number))
                        }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(discussion.title, color = g.fg, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text("#${discussion.number} · ${discussion.category.name} · ${discussion.commentCount} 条评论 · ↑ ${discussion.upvoteCount}", color = g.fgMuted, fontSize = 12.sp)
                            Text("${discussion.author?.login ?: "ghost"} · ${relTime(discussion.updatedAt)}${if (discussion.answered) " · 已解答" else ""}${if (discussion.closed) " · 已关闭" else ""}${if (discussion.locked) " · 已锁定" else ""}", color = g.fgMuted, fontSize = 12.sp)
                        }
                        HDivider()
                    }
                    if (shown.isEmpty() && discussions.loaded && discussions.error == null && !discussions.loading) {
                        item { EmptyState(if (query.isBlank()) "此筛选下没有已加载的讨论" else "已加载讨论中没有匹配项") }
                    }
                    item { CursorFooter(discussions, "加载更多讨论", "讨论", Modifier.padding(12.dp)) }
                    item { Spacer(Modifier.height(24.dp)) }
                }
                if (composing) {
                    DiscussionComposeDialog(
                        draftKey = "new-discussion:${owner.lowercase()}/${name.lowercase()}",
                        title = "新讨论", categories = categories.items, initialCategory = categoryId,
                        enabled = allowed, onDismiss = { composing = false },
                        onSubmit = { category, title, body ->
                            val created = Discussions.create(repo, category!!, title, body, allowed && Session.token != null && !Session.authExpired)
                            composing = false
                            discussions.refresh()
                            nav.push(Screen.DiscussionDetail(owner, name, created.number))
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun DiscussionDetailScreen(
    owner: String,
    name: String,
    number: Int,
    canWrite: Boolean = Session.token != null && !Session.authExpired,
) {
    val g = Gh.c
    val ctx = rememberCtx()
    val thread = rememberLoader("discussion:thread") { Discussions.detail(owner, name, number) }
    val comments = retain("discussion:comments") { entry ->
        DiscussionCursorPager<DiscussionComment>(entry.scope, DiscussionsClient.MAX_COMMENTS) { it.id }
    }
    val replies = retain("discussion:replies") { entry ->
        DiscussionCursorPager<DiscussionComment>(entry.scope, DiscussionsClient.MAX_REPLIES) { it.id }
    }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var composing by rememberSaveable { mutableStateOf(false) }
    var replyTo by remember { mutableStateOf<DiscussionComment?>(null) }
    val t = thread.data
    LaunchedEffect(t) {
        t?.let {
            comments.restart({ cursor -> Discussions.comments(owner, name, number, cursor) }, it.comments)
            expanded = null
        }
    }
    LaunchedEffect(expanded) {
        val id = expanded
        if (id == null) replies.stop() else replies.restart({ cursor -> Discussions.replies(id, cursor) })
    }

    Page("讨论 #$number", subtitle = "$owner/$name", actions = {
        MoreMenu(listOf(
            MenuAction("刷新") { thread.refresh() },
            MenuAction("在浏览器打开") { ctx.openBrowser("https://github.com/$owner/$name/discussions/$number") },
        ))
    }) { padding ->
        LoadBox(thread, Modifier.padding(padding)) { data ->
            val discussion = data.discussion
            val allowed = discussion.canReply(data.repository, canWrite && Session.token != null && !Session.authExpired)
            fun compose(comment: DiscussionComment? = null) {
                replyTo = comment
                composing = true
            }
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(discussion.title, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = g.fg)
                        Text("${discussion.category.name} · ${discussion.commentCount} 条评论 · ↑ ${discussion.upvoteCount}${if (discussion.answered) " · 已解答" else ""}", color = g.fgMuted, fontSize = 13.sp)
                        DiscussionByline(discussion.author, discussion.createdAt)
                        // HTML comes only from GitHub's bodyHTML, never from raw Markdown or errors.
                        HtmlView(discussion.bodyHtml.orEmpty(), baseUrl = discussion.url)
                        GhButton("回复讨论", enabled = allowed) { compose() }
                        if (!allowed) Text(when {
                            data.repository.archived -> "仓库已归档，仅可阅读"
                            discussion.locked -> "讨论已锁定"
                            discussion.closed -> "讨论已关闭"
                            else -> "当前账号仅可阅读讨论"
                        }, color = g.fgMuted, fontSize = 12.sp)
                    }
                }
                val answer = data.answer
                if (answer != null && comments.items.none { it.id == answer.id }) {
                    item(key = "answer:${answer.id}") {
                        DiscussionCommentCard(answer, nested = false, onReply = null, onExpand = null, expanded = false)
                    }
                }
                comments.items.forEach { comment ->
                    item(key = comment.id) {
                        DiscussionCommentCard(
                            comment, nested = false,
                            onReply = if (allowed) ({ compose(comment) }) else null,
                            onExpand = if (comment.replyCount > 0) ({ expanded = if (expanded == comment.id) null else comment.id }) else null,
                            expanded = expanded == comment.id,
                        )
                    }
                    if (expanded == comment.id) {
                        items(replies.items, key = { "reply:${it.id}" }) { reply ->
                            DiscussionCommentCard(reply, nested = true, onReply = null, onExpand = null, expanded = false)
                        }
                        item(key = "replies:${comment.id}") {
                            CursorFooter(replies, "加载更多回复", "回复", Modifier.padding(start = 32.dp, end = 12.dp, bottom = 8.dp))
                        }
                    }
                }
                if (comments.loaded && comments.items.isEmpty() && comments.error == null) {
                    item { EmptyState("暂无评论") }
                }
                item { CursorFooter(comments, "加载更多评论", "评论", Modifier.padding(12.dp)) }
                item { Spacer(Modifier.height(24.dp)) }
            }
            if (composing) {
                DiscussionComposeDialog(
                    draftKey = "discussion:${owner.lowercase()}/${name.lowercase()}:$number:${replyTo?.id ?: "root"}",
                    title = replyTo?.author?.login?.let { "回复 @$it" } ?: "回复讨论",
                    enabled = allowed, onDismiss = { composing = false },
                    onSubmit = { _, _, body ->
                        Discussions.reply(data.repository, discussion, body, allowed && Session.token != null && !Session.authExpired, replyTo?.id)
                        composing = false
                        thread.refresh()
                    },
                )
            }
        }
    }
}

@Composable
private fun <T> CursorFooter(pager: DiscussionCursorPager<T>, more: String, noun: String, modifier: Modifier = Modifier) {
    val g = Gh.c
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            pager.loading -> Loading(Modifier.fillMaxWidth().height(48.dp))
            pager.error != null -> {
                Text(pager.error!!, color = g.danger, fontSize = 13.sp)
                GhButton("重试") { pager.retry() }
            }
            pager.limited -> Text("已达到本页${noun}加载上限；刷新可从头加载，更多内容可在 GitHub 查看。", color = g.fgMuted, fontSize = 12.sp)
            pager.hasMore -> GhButton(more) { pager.loadMore() }
        }
    }
}

@Composable
private fun DiscussionByline(author: DiscussionAuthor?, time: String?) {
    val nav = LocalNav.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(author?.avatarUrl, 24.dp, Modifier.clickable(enabled = author != null) { author?.let { nav.push(Screen.Profile(it.login)) } })
        Spacer(Modifier.width(8.dp))
        Text("${author?.login ?: "ghost"} · ${relTime(time)}", color = Gh.c.fgMuted, fontSize = 12.sp)
    }
}

@Composable
private fun DiscussionCommentCard(
    comment: DiscussionComment,
    nested: Boolean,
    onReply: (() -> Unit)?,
    onExpand: (() -> Unit)?,
    expanded: Boolean,
) {
    val g = Gh.c
    Column(
        Modifier.padding(start = if (nested) 32.dp else 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
            .fillMaxWidth().border(1.dp, g.border, RoundedCornerShape(8.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DiscussionByline(comment.author, comment.createdAt)
        if (comment.isAnswer) Text("已采纳的答案", color = g.success, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        if (comment.isMinimized) {
            Text("此评论已被折叠", color = g.fgMuted, fontSize = 13.sp)
        } else {
            HtmlView(comment.bodyHtml, baseUrl = comment.url)
        }
        if (onReply != null) GhButton("回复", onClick = onReply)
        if (onExpand != null) GhButton(if (expanded) "收起回复" else "查看 ${comment.replyCount} 条回复", onClick = onExpand)
    }
}

@Composable
private fun DiscussionComposeDialog(
    draftKey: String,
    title: String,
    enabled: Boolean,
    onDismiss: () -> Unit,
    categories: List<DiscussionCategory>? = null,
    initialCategory: String? = null,
    onSubmit: suspend (categoryId: String?, title: String, body: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val draft = rememberDraft(draftKey)
    var category by rememberSaveable(draftKey) { mutableStateOf(draft.initial.category?.takeIf { id -> categories?.any { it.id == id } == true } ?: initialCategory ?: categories?.firstOrNull()?.id) }
    var subject by rememberSaveable(draftKey) { mutableStateOf(draft.initial.title) }
    var body by rememberSaveable(draftKey) { mutableStateOf(draft.initial.body) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val valid = enabled && !sending && body.isNotBlank() &&
        (categories == null || (subject.isNotBlank() && categories.any { it.id == category }))
    GhDialog(
        title, onDismiss = { if (!sending) onDismiss() }, confirm = if (sending) "发表中…" else "发表",
        confirmEnabled = valid, onConfirm = {
            if (valid) {
                sending = true
                error = null
                val selected = category
                val submittedTitle = subject
                val submittedBody = body
                scope.launch {
                    try {
                        val submitted = DraftValue(submittedTitle, submittedBody, selected)
                        draft.update(submitted)
                        onSubmit(selected, submittedTitle, submittedBody)
                        draft.submitted(submitted)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.friendly()
                    } finally {
                        sending = false
                    }
                }
            }
        },
    ) {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (categories != null) {
                Dropdown(categories.find { it.id == category }?.name ?: "选择分类", categories.map { it.name }, {
                    if (!sending) category = categories[it].id
                })
                GhField(subject, { if (!sending) subject = it }, "标题")
            }
            GhField(body, { if (!sending) body = it }, "正文（支持 Markdown）", singleLine = false, minLines = 6)
            DraftBinding(draft, DraftValue(subject, body, category))
            if (!enabled) Text("当前无法发表，请检查账号权限或刷新讨论。", color = Gh.c.fgMuted, fontSize = 12.sp)
            error?.let { Text(it, color = Gh.c.danger, fontSize = 13.sp) }
        }
    }
}
