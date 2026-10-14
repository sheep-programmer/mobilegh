package com.mobilegh.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.TextFieldValue
import com.mobilegh.data.User
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.Navigator
import com.mobilegh.nav.guarded
import com.mobilegh.nav.write
import com.mobilegh.ui.components.MarkdownEditor
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.SectionTitle
import com.mobilegh.ui.components.TextLink
import com.mobilegh.ui.components.copy
import com.mobilegh.ui.components.toast
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.mobilegh.ui.components.InlineError
import com.mobilegh.ui.components.Chips
import com.mobilegh.ui.components.Dropdown
import com.mobilegh.R
import com.mobilegh.ui.components.EmptyState
import com.mobilegh.ui.components.emptyItem
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.GhField
import com.mobilegh.ui.components.HDivider
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
    val entry = LocalEntry.current
    val g = Gh.c
    val repository = rememberLoader("discussions:repository") { Discussions.repository(owner, name) }
    var categoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var answeredFilter by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var composing by rememberSaveable { mutableStateOf(false) }

    Page("Discussions", subtitle = "$owner/$name") { padding ->
        LoadBox(repository, Modifier.padding(padding)) { repo ->
            if (!repo.enabled) {
                EmptyState("此仓库未启用 Discussions", R.drawable.oc_comment_discussion)
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
                val listState = rememberLazyListState()
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
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
                            DiscussionStats(discussion, "#${discussion.number} · ${discussion.category.name}")
                            Text("${discussion.author?.login ?: "ghost"} · ${relTime(discussion.updatedAt)}${if (discussion.answered) " · 已解答" else ""}${if (discussion.closed) " · 已关闭" else ""}${if (discussion.locked) " · 已锁定" else ""}", color = g.fgMuted, fontSize = 12.sp)
                        }
                        HDivider()
                    }
                    if (shown.isEmpty() && discussions.loaded && discussions.error == null && !discussions.loading) {
                        if (query.isNotBlank()) emptyItem(listState, "已加载讨论中没有匹配项", R.drawable.oc_search, "清除搜索") { query = "" }
                        else if (allowed && categories.items.isNotEmpty()) emptyItem(listState, "此筛选下没有已加载的讨论", R.drawable.oc_comment_discussion, "发起新讨论") { composing = true }
                        else emptyItem(listState, "此筛选下没有已加载的讨论", R.drawable.oc_comment_discussion)
                    }
                    item { CursorFooter(discussions, "加载更多讨论", "讨论", Modifier.padding(12.dp)) }
                    item { Spacer(Modifier.height(24.dp)) }
                }
                if (composing) {
                    DiscussionComposeDialog(
                        draftKey = "new-discussion:${owner.lowercase()}/${name.lowercase()}",
                        title = "新讨论", categories = categories.items, initialCategory = categoryId,
                        enabled = allowed, onDismiss = { composing = false },
                        contextRepo = "$owner/$name",
                        onSubmit = { category, title, body ->
                            val created = entry.guarded {
                                Discussions.create(repo, category!!, title, body, allowed && Session.token != null && !Session.authExpired)
                            }
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

/** 返回列表后能看到最新状态：刷新导航栈里同一仓库的讨论列表 */
private fun Navigator.refreshDiscussionList(owner: String, name: String) {
    stack.forEach { e ->
        if (e != top && e.screen == Screen.Discussions(owner, name)) {
            (e.bag["discussions:list"] as? DiscussionCursorPager<*>)?.refresh()
        }
    }
}

private fun DiscussionAuthor.asUser() = User(login = login, avatarUrl = avatarUrl.orEmpty())

@Composable
fun DiscussionDetailScreen(
    owner: String,
    name: String,
    number: Int,
    canWrite: Boolean = Session.token != null && !Session.authExpired,
) {
    val g = Gh.c
    val ctx = rememberCtx()
    val nav = LocalNav.current
    val entry = LocalEntry.current
    val thread = rememberLoader("discussion:thread") { Discussions.detail(owner, name, number) }
    val comments = retain("discussion:comments") { e ->
        DiscussionCursorPager<DiscussionComment>(e.scope, DiscussionsClient.MAX_COMMENTS) { it.id }
    }
    val replies = retain("discussion:replies") { e ->
        DiscussionCursorPager<DiscussionComment>(e.scope, DiscussionsClient.MAX_REPLIES) { it.id }
    }
    /** 已展开全部回复的顶层评论；其余评论只显示随正文返回的第一页回复 */
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var composing by rememberSaveable { mutableStateOf(false) }
    var replyTo by remember { mutableStateOf<DiscussionComment?>(null) }
    /** 点赞的本地状态（subjectId → 点赞数, 是否已点赞），重新加载后清空 */
    val upvotes = remember { mutableStateMapOf<String, Pair<Int, Boolean>>() }
    val upvoting = remember { mutableStateMapOf<String, Boolean>() }
    var editingDiscussion by remember { mutableStateOf(false) }
    var editingComment by remember { mutableStateOf<DiscussionComment?>(null) }
    var deletingComment by remember { mutableStateOf<DiscussionComment?>(null) }
    var deletingDiscussion by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val t = thread.data
    LaunchedEffect(t) {
        t?.let {
            comments.restart({ cursor -> Discussions.comments(owner, name, number, cursor) }, it.comments)
            replies.stop()
            expanded = null
            upvotes.clear()
        }
    }
    val url = t?.discussion?.url ?: "https://github.com/$owner/$name/discussions/$number"

    fun reload() = thread.refresh()

    fun toggleUpvote(id: String, count: Int, on: Boolean) {
        if (upvoting[id] == true) return
        val target = !on
        upvotes[id] = (count + if (target) 1 else -1).coerceAtLeast(0) to target
        upvoting[id] = true
        entry.write<Pair<Int, Boolean>>({ upvotes[id] = count to on; upvoting.remove(id); ctx.toast(it) }, { confirmed ->
            upvotes[id] = confirmed
            upvoting.remove(id)
        }) { Discussions.upvote(id, target) }
    }

    fun markAnswer(comment: DiscussionComment, on: Boolean) {
        if (busy) return
        busy = true
        entry.write<Boolean>({ busy = false; ctx.toast(it) }, {
            busy = false
            ctx.toast(if (on) "已采纳为答案" else "已取消采纳")
            reload()
            nav.refreshDiscussionList(owner, name)
        }) { Discussions.markAnswer(comment.id, on) }
    }

    Page("讨论 #$number", subtitle = "$owner/$name", actions = {
        MoreMenu(buildList {
            val d = t?.discussion
            if (d != null && d.viewerCanUpdate) add(MenuAction("编辑讨论") { editingDiscussion = true })
            add(MenuAction("刷新") { reload() })
            add(MenuAction("复制链接") { ctx.copy(url) })
            add(MenuAction("在浏览器打开") { ctx.openBrowser(url) })
            if (d != null && d.viewerCanDelete) add(MenuAction("删除讨论", danger = true) { deletingDiscussion = true })
        })
    }) { padding ->
        LoadBox(thread, Modifier.padding(padding)) { data ->
            val discussion = data.discussion
            val allowed = discussion.canReply(data.repository, canWrite && Session.token != null && !Session.authExpired)
            val answerable = discussion.category.isAnswerable
            fun compose(comment: DiscussionComment? = null) {
                replyTo = comment
                composing = true
            }

            @Composable
            fun Upvote(id: String, count: Int, on: Boolean, can: Boolean) {
                val (shownCount, shownOn) = upvotes[id] ?: (count to on)
                UpvotePill(shownCount, shownOn, enabled = can && upvoting[id] != true) { toggleUpvote(id, shownCount, shownOn) }
            }

            fun menuFor(comment: DiscussionComment, topLevel: Boolean): List<MenuAction> = buildList {
                if (topLevel && allowed) add(MenuAction("回复") { compose(comment) })
                if (topLevel && answerable && !comment.isAnswer && comment.viewerCanMarkAsAnswer) {
                    add(MenuAction("采纳为答案") { markAnswer(comment, true) })
                }
                if (comment.isAnswer && comment.viewerCanUnmarkAsAnswer) add(MenuAction("取消采纳答案") { markAnswer(comment, false) })
                if (comment.viewerCanUpdate && comment.body != null) add(MenuAction("编辑") { editingComment = comment })
                add(MenuAction("复制链接") { ctx.copy(comment.url) })
                if (comment.viewerCanDelete) add(MenuAction("删除", danger = true) { deletingComment = comment })
            }

            @Composable
            fun Comment(comment: DiscussionComment, topLevel: Boolean, modifier: Modifier = Modifier) {
                val answer = comment.isAnswer
                TimelineCard(
                    comment.author?.asUser(), comment.createdAt,
                    when {
                        answer -> "已采纳答案"
                        comment.author != null && comment.author.login == discussion.author?.login -> "作者"
                        else -> null
                    },
                    if (comment.isMinimized) null else comment.bodyHtml, comment.url,
                    menu = menuFor(comment, topLevel),
                    modifier = modifier,
                    borderColor = if (answer) g.success else g.border,
                    badgeColor = if (answer) g.success else g.fgMuted,
                    emptyText = if (comment.isMinimized) "此评论已被折叠" else "没有提供内容。",
                ) {
                    Upvote(comment.id, comment.upvoteCount, comment.viewerHasUpvoted, comment.viewerCanUpvote)
                }
            }

            LazyColumn(Modifier.fillMaxSize()) {
                item(key = "header") {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(discussion.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = g.fg)
                        DiscussionStats(discussion, discussion.category.name)
                    }
                }
                item(key = "body") {
                    // HTML comes only from GitHub's bodyHTML, never from raw Markdown or errors.
                    TimelineCard(
                        discussion.author?.asUser(), discussion.createdAt, null, discussion.bodyHtml, discussion.url,
                        menu = buildList {
                            if (allowed) add(MenuAction("回复讨论") { compose() })
                            if (discussion.viewerCanUpdate) add(MenuAction("编辑") { editingDiscussion = true })
                            add(MenuAction("复制链接") { ctx.copy(url) })
                            if (discussion.viewerCanDelete) add(MenuAction("删除讨论", danger = true) { deletingDiscussion = true })
                        },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Upvote(discussion.id, discussion.upvoteCount, discussion.viewerHasUpvoted, discussion.viewerCanUpvote)
                            if (allowed) ActionPill(R.drawable.oc_comment, "回复") { compose() }
                        }
                    }
                    if (!allowed) Text(when {
                        data.repository.archived -> "仓库已归档，仅可阅读"
                        discussion.locked -> "讨论已锁定"
                        discussion.closed -> "讨论已关闭"
                        else -> "当前账号仅可阅读讨论"
                    }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = g.fgMuted, fontSize = 12.sp)
                }
                val answer = data.answer
                if (answer != null) {
                    item(key = "answer-title") {
                        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Oc(R.drawable.oc_check_circle_fill, g.success, 16.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("${answer.author?.login ?: "ghost"} 的回答已被采纳", color = g.success, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    item(key = "answer:${answer.id}") { Comment(answer, topLevel = false) }
                }
                item(key = "comments-title") {
                    SectionTitle("评论 ${comments.totalCount.takeIf { it > 0 } ?: discussion.commentCount}")
                }
                comments.items.forEach { comment ->
                    item(key = comment.id) { Comment(comment, topLevel = true) }
                    val full = expanded == comment.id
                    val shown = if (full) replies.items else comment.replies?.items.orEmpty()
                    items(shown, key = { "reply:${comment.id}:${it.id}" }) { reply ->
                        Comment(reply, topLevel = false, modifier = Modifier.padding(start = 24.dp))
                    }
                    val more = if (full) replies.hasMore else comment.replies?.hasNextPage ?: (comment.replyCount > 0)
                    if (full || more) item(key = "replies-more:${comment.id}") {
                        Row(Modifier.fillMaxWidth().padding(start = 40.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            when {
                                full && replies.loading -> Loading(Modifier.fillMaxWidth().height(40.dp))
                                full && replies.error != null -> InlineError(replies.error!!) { replies.retry() }
                                full && replies.limited -> Text("已达到回复加载上限，更多回复可在 GitHub 查看。", color = g.fgMuted, fontSize = 12.sp)
                                more -> TextLink("查看更多回复（共 ${comment.replyCount} 条）") {
                                    if (full) replies.loadMore()
                                    else {
                                        expanded = comment.id
                                        replies.restart({ cursor -> Discussions.replies(comment.id, cursor) }, comment.replies)
                                        if (comment.replies != null) replies.loadMore()
                                    }
                                }
                                else -> TextLink("收起回复", Gh.c.fgMuted) {
                                    replies.stop()
                                    expanded = null
                                }
                            }
                        }
                    }
                }
                if (comments.loaded && comments.items.isEmpty() && comments.error == null) {
                    item(key = "empty") {
                        Text(
                            if (allowed) "还没有评论，点按上方「回复」参与讨论" else "还没有评论",
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = g.fgMuted, fontSize = 13.sp,
                        )
                    }
                }
                item(key = "footer") { CursorFooter(comments, "加载更多评论", "评论", Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
                item { Spacer(Modifier.height(24.dp)) }
            }
            if (composing) {
                DiscussionComposeDialog(
                    draftKey = "discussion:${owner.lowercase()}/${name.lowercase()}:$number:${replyTo?.id ?: "root"}",
                    title = replyTo?.author?.login?.let { "回复 @$it" } ?: "回复讨论",
                    enabled = allowed, contextRepo = "$owner/$name", onDismiss = { composing = false },
                    onSubmit = { _, _, body ->
                        entry.guarded {
                            Discussions.reply(data.repository, discussion, body, allowed && Session.token != null && !Session.authExpired, replyTo?.id)
                        }
                        composing = false
                        reload()
                        nav.refreshDiscussionList(owner, name)
                    },
                )
            }
        }
    }

    val d = t?.discussion
    if (editingDiscussion && d != null) {
        EditDialog("编辑讨论", d.title, d.body.orEmpty(), "$owner/$name", onDismiss = { editingDiscussion = false }, busy = busy) { title, body ->
            busy = true
            entry.write<Unit>({ busy = false; ctx.toast(it) }, {
                busy = false
                editingDiscussion = false
                ctx.toast("已保存")
                reload()
                nav.refreshDiscussionList(owner, name)
            }) { Discussions.update(d.id, title, body) }
        }
    }
    editingComment?.let { c ->
        EditDialog("编辑评论", null, c.body.orEmpty(), "$owner/$name", onDismiss = { editingComment = null }, busy = busy) { _, body ->
            busy = true
            entry.write<Unit>({ busy = false; ctx.toast(it) }, {
                busy = false
                editingComment = null
                ctx.toast("已保存")
                reload()
            }) { Discussions.updateComment(c.id, body) }
        }
    }
    deletingComment?.let { c ->
        GhDialog(
            "删除评论", { if (!busy) deletingComment = null }, confirm = if (busy) "删除中…" else "删除", danger = true, confirmEnabled = !busy,
            onConfirm = {
                busy = true
                entry.write<Unit>({ busy = false; deletingComment = null; ctx.toast(it) }, {
                    busy = false
                    deletingComment = null
                    ctx.toast("已删除")
                    reload()
                    nav.refreshDiscussionList(owner, name)
                }) { Discussions.deleteComment(c.id) }
            },
        ) { Text(if (c.replyCount > 0) "评论及其 ${c.replyCount} 条回复将被删除，无法恢复。" else "删除后无法恢复。", color = g.fg) }
    }
    if (deletingDiscussion && d != null) {
        GhDialog(
            "删除讨论", { if (!busy) deletingDiscussion = false }, confirm = if (busy) "删除中…" else "删除", danger = true, confirmEnabled = !busy,
            onConfirm = {
                busy = true
                entry.write<Unit>({ busy = false; deletingDiscussion = false; ctx.toast(it) }, {
                    busy = false
                    deletingDiscussion = false
                    ctx.toast("讨论已删除")
                    nav.refreshDiscussionList(owner, name)
                    nav.pop()
                }) { Discussions.delete(d.id) }
            },
        ) { Text("「${d.title}」及其全部评论将被永久删除，无法恢复。", color = g.fg) }
    }
}

/** 紧凑的胶囊开关：向上箭头 + 点赞数；已点赞时为强调色 */
@Composable
private fun UpvotePill(count: Int, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val g = Gh.c
    val shape = RoundedCornerShape(50)
    val tint = if (on) g.accent else g.fgMuted
    Row(
        Modifier.clip(shape)
            .background(if (on) g.accentSubtle else g.canvas, shape)
            .border(1.dp, if (on) g.accent.copy(alpha = 0.6f) else g.border, shape)
            .clickable(enabled, role = androidx.compose.ui.semantics.Role.Switch, onClickLabel = if (on) "取消点赞" else "点赞", onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Oc(R.drawable.oc_arrow_up, tint, 14.dp)
        Spacer(Modifier.width(4.dp))
        Text(count.toString(), fontSize = 12.sp, color = tint, fontWeight = FontWeight.Medium)
    }
}

/** 与点赞胶囊同尺寸的次要操作 */
@Composable
private fun ActionPill(icon: Int, text: String, onClick: () -> Unit) {
    val g = Gh.c
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.clip(shape).background(g.canvas, shape).border(1.dp, g.border, shape)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Oc(icon, g.fgMuted, 14.dp)
        Spacer(Modifier.width(4.dp))
        Text(text, fontSize = 12.sp, color = g.fgMuted, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun <T> CursorFooter(pager: DiscussionCursorPager<T>, more: String, noun: String, modifier: Modifier = Modifier) {
    val g = Gh.c
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            pager.loading -> Loading(Modifier.fillMaxWidth().height(48.dp))
            pager.error != null -> InlineError(pager.error!!) { pager.retry() }
            pager.limited -> Text("已达到本页${noun}加载上限；刷新可从头加载，更多内容可在 GitHub 查看。", color = g.fgMuted, fontSize = 12.sp)
            pager.hasMore -> GhButton(more) { pager.loadMore() }
        }
    }
}

@Composable
private fun DiscussionComposeDialog(
    draftKey: String,
    title: String,
    enabled: Boolean,
    onDismiss: () -> Unit,
    contextRepo: String? = null,
    categories: List<DiscussionCategory>? = null,
    initialCategory: String? = null,
    onSubmit: suspend (categoryId: String?, title: String, body: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val draft = rememberDraft(draftKey)
    var category by rememberSaveable(draftKey) { mutableStateOf(draft.initial.category?.takeIf { id -> categories?.any { it.id == id } == true } ?: initialCategory ?: categories?.firstOrNull()?.id) }
    var subject by rememberSaveable(draftKey) { mutableStateOf(draft.initial.title) }
    var body by rememberSaveable(draftKey, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(draft.initial.body)) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val valid = enabled && !sending && body.text.isNotBlank() &&
        (categories == null || (subject.isNotBlank() && categories.any { it.id == category }))
    GhDialog(
        title, onDismiss = { if (!sending) onDismiss() }, confirm = if (sending) "发表中…" else "发表",
        confirmEnabled = valid, onConfirm = {
            if (valid) {
                sending = true
                error = null
                val selected = category
                val submittedTitle = subject
                val submittedBody = body.text
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
                categories.find { it.id == category }?.let { c ->
                    if (c.isAnswerable) Text("问答分类：可以把有帮助的回复采纳为答案", color = Gh.c.fgMuted, fontSize = 12.sp)
                }
                GhField(subject, { if (!sending) subject = it }, "标题")
            }
            MarkdownEditor(
                body, { if (!sending) body = it },
                placeholder = if (categories != null) "正文（支持 Markdown）" else "回复内容（支持 Markdown）",
                contextRepo = contextRepo, minLines = 6,
            )
            DraftBinding(draft, DraftValue(subject, body.text, category))
            if (!enabled) Text("当前无法发表，请检查账号权限或刷新讨论。", color = Gh.c.fgMuted, fontSize = 12.sp)
            error?.let { Text(it, color = Gh.c.danger, fontSize = 13.sp) }
        }
    }
}

/** 分类 + 评论数 + 点赞数 + 解答状态，图标与列表其他元信息一致 */
@Composable
private fun DiscussionStats(discussion: Discussion, lead: String) {
    val g = Gh.c
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(lead, color = g.fgMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        com.mobilegh.ui.components.IconText(R.drawable.oc_comment, discussion.commentCount.toString())
        com.mobilegh.ui.components.IconText(R.drawable.oc_arrow_up, discussion.upvoteCount.toString())
        if (discussion.answered) com.mobilegh.ui.components.IconText(R.drawable.oc_check_circle, "已解答", g.success)
    }
}
