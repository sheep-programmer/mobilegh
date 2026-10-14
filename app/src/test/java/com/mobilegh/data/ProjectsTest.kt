package com.mobilegh.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProjectsTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private val todo = ProjectOption("o1", "Todo", ProjectColor.Gray)
    private val doing = ProjectOption("o2", "In Progress", ProjectColor.Yellow)
    private val done = ProjectOption("o3", "Done", ProjectColor.Purple)
    private val status = ProjectField("f-status", "Status", "SINGLE_SELECT", listOf(todo, doing, done))
    private val priority = ProjectField("f-pri", "Priority", "SINGLE_SELECT", listOf(ProjectOption("p1", "P1", ProjectColor.Red)))
    private val titleField = ProjectField("f-title", "Title", "TITLE")
    private val iteration = ProjectField("f-iter", "Sprint", "ITERATION")

    private fun statusValue(option: ProjectOption) =
        ProjectFieldValue(status.id, status.name, status.dataType, ProjectValueKind.SingleSelect, option.name, option.id)

    private fun item(id: String, vararg values: ProjectFieldValue, archived: Boolean = false) =
        ProjectItem(id, ProjectItemType.Issue, archived, "Item $id", 1, null, "o/r", "OPEN", values = values.toList())

    private suspend fun failure(block: suspend () -> Unit): Throwable {
        try { block() } catch (e: Throwable) { return e }
        fail("Expected failure")
        error("unreachable")
    }

    // ---------------- 颜色 ----------------

    @Test fun optionColorsMapCaseInsensitivelyAndUnknownFallsBackToGray() {
        assertEquals(ProjectColor.Blue, ProjectColor.of("BLUE"))
        assertEquals(ProjectColor.Green, ProjectColor.of("green"))
        assertEquals(ProjectColor.Yellow, ProjectColor.of("YELLOW"))
        assertEquals(ProjectColor.Orange, ProjectColor.of("ORANGE"))
        assertEquals(ProjectColor.Red, ProjectColor.of("RED"))
        assertEquals(ProjectColor.Pink, ProjectColor.of("PINK"))
        assertEquals(ProjectColor.Purple, ProjectColor.of("PURPLE"))
        assertEquals(ProjectColor.Gray, ProjectColor.of("GRAY"))
        assertEquals(ProjectColor.Gray, ProjectColor.of("TEAL"))
        assertEquals(ProjectColor.Gray, ProjectColor.of(null))
    }

    @Test fun layoutsMapAndUnknownFallsBackToTable() {
        assertEquals(ProjectLayout.Board, ProjectLayout.of("BOARD_LAYOUT"))
        assertEquals(ProjectLayout.Roadmap, ProjectLayout.of("ROADMAP_LAYOUT"))
        assertEquals(ProjectLayout.Table, ProjectLayout.of("TABLE_LAYOUT"))
        assertEquals(ProjectLayout.Table, ProjectLayout.of(null))
    }

    // ---------------- 分列 ----------------

    @Test fun groupFieldPrefersViewVerticalGroupingThenGroupByThenStatus() {
        val fields = listOf(titleField, priority, status, iteration)
        val board = ProjectView("v", 1, "Board", ProjectLayout.Board, groupByFieldIds = listOf(status.id), verticalGroupByFieldIds = listOf(priority.id))
        assertEquals(priority, boardGroupField(fields, board))
        assertEquals(status, boardGroupField(fields, board.copy(verticalGroupByFieldIds = emptyList())))
        // 非单选字段不能分列，退回 Status
        assertEquals(status, boardGroupField(fields, board.copy(verticalGroupByFieldIds = listOf(iteration.id), groupByFieldIds = emptyList())))
        assertEquals(status, boardGroupField(fields, null))
        assertEquals(priority, boardGroupField(listOf(titleField, priority), null))
        assertNull(boardGroupField(listOf(titleField, iteration), null))
    }

    @Test fun groupingFollowsOptionOrderAndPutsUnsetOrDeletedOptionsFirst() {
        val stale = ProjectFieldValue(status.id, status.name, status.dataType, ProjectValueKind.SingleSelect, "Removed", "gone")
        val items = listOf(
            item("a", statusValue(done)), item("b"), item("c", statusValue(todo)),
            item("d", stale), item("e", statusValue(done)), item("f", statusValue(todo), archived = true),
        )
        val columns = groupProjectItems(items, status)
        assertEquals(listOf(null, "o1", "o2", "o3"), columns.map { it.option?.id })
        assertEquals(listOf("b", "d"), columns[0].items.map { it.id })
        assertEquals(ProjectColumn.NO_VALUE_KEY, columns[0].key)
        assertEquals(listOf("c"), columns[1].items.map { it.id })
        assertTrue(columns[2].items.isEmpty())
        assertEquals(listOf("a", "e"), columns[3].items.map { it.id })
    }

    @Test fun noValueColumnIsOmittedWhenEmptyAndMissingFieldYieldsSingleColumn() {
        val items = listOf(item("a", statusValue(todo)), item("b", statusValue(done)))
        assertEquals(listOf("o1", "o2", "o3"), groupProjectItems(items, status).map { it.key })
        val single = groupProjectItems(items + item("z", archived = true), null)
        assertEquals(1, single.size)
        assertNull(single[0].option)
        assertEquals(listOf("a", "b"), single[0].items.map { it.id })
    }

    @Test fun cardValuesSkipTitleExcludedAndBlankAndRespectLimit() {
        val values = listOf(
            ProjectFieldValue(titleField.id, "Title", "TITLE", ProjectValueKind.Text, "Item"),
            statusValue(doing),
            ProjectFieldValue("f-note", "Note", "TEXT", ProjectValueKind.Text, "  "),
            ProjectFieldValue("f-est", "Estimate", "NUMBER", ProjectValueKind.Number, "3"),
            ProjectFieldValue("f-due", "Due", "DATE", ProjectValueKind.Date, "2026-10-20"),
            ProjectFieldValue("f-lab", "Labels", "LABELS", ProjectValueKind.Labels, "bug"),
        )
        val it = item("x", *values.toTypedArray())
        assertEquals(listOf("f-est", "f-due", "f-lab"), cardFieldValues(it, setOf(status.id)).map { v -> v.fieldId })
        assertEquals(listOf(status.id, "f-est"), cardFieldValues(it, limit = 2).map { v -> v.fieldId })
    }

    // ---------------- 本地编辑 ----------------

    @Test fun singleSelectEditReplacesOrClearsOnlyThatField() {
        val other = ProjectFieldValue("f-est", "Estimate", "NUMBER", ProjectValueKind.Number, "3")
        val original = item("a", statusValue(todo), other)
        val moved = original.withSingleSelect(status, done)
        assertEquals("o3", moved.value(status.id)?.optionId)
        assertEquals("Done", moved.value(status.id)?.text)
        assertEquals(other, moved.value("f-est"))
        assertEquals(2, moved.values.size)
        val cleared = moved.withSingleSelect(status, null)
        assertNull(cleared.value(status.id))
        assertEquals(listOf(other), cleared.values)
    }

    @Test fun editsOverlayLoadedItemsAndAddedDraftsAreDeduplicated() {
        val loaded = listOf(item("a"), item("b"), item("c"))
        val draft = item("n")
        val shown = applyProjectEdits(loaded, mapOf("b" to null, "c" to item("c").withSingleSelect(status, done)), listOf(draft, item("a")))
        assertEquals(listOf("n", "a", "c"), shown.map { it.id })
        assertEquals("o3", shown[2].value(status.id)?.optionId)
        // 新草稿随后出现在加载结果中：只保留一份
        assertEquals(listOf("n", "a"), applyProjectEdits(listOf(draft, item("a")), emptyMap(), listOf(draft)).map { it.id })
        // 新草稿随即被移除
        assertEquals(listOf("a"), applyProjectEdits(listOf(item("a")), mapOf("n" to null), listOf(draft)).map { it.id })
    }

    // ---------------- 错误分类 ----------------

    @Test fun scopeAndForbiddenErrorsAreClassified() {
        assertEquals(ProjectErrorKind.Scope, ProjectErrors.classify("INSUFFICIENT_SCOPES", "anything"))
        assertEquals(ProjectErrorKind.Scope, ProjectErrors.classify(null,
            "Your token has not been granted the required scopes to execute this query. The 'projectsV2' field requires one of the following scopes: ['read:project']"))
        assertEquals(ProjectErrorKind.Forbidden, ProjectErrors.classify("FORBIDDEN", "Resource not accessible by integration"))
        assertEquals(ProjectErrorKind.NotFound, ProjectErrors.classify("NOT_FOUND", "Could not resolve"))
        assertEquals(ProjectErrorKind.Other, ProjectErrors.classify(null, "Something went wrong"))

        assertEquals(ProjectErrorKind.Scope, ProjectErrors.ofMessage(ProjectErrors.READ_SCOPE_MESSAGE))
        assertEquals(ProjectErrorKind.Scope, ProjectErrors.ofMessage(ProjectErrors.WRITE_SCOPE_MESSAGE))
        assertEquals(ProjectErrorKind.Forbidden, ProjectErrors.ofMessage(ProjectErrors.FORBIDDEN_PREFIX + "OAuth App access restrictions"))
        assertEquals(ProjectErrorKind.NotFound, ProjectErrors.ofMessage(ProjectErrors.NOT_FOUND_MESSAGE))
        assertEquals(ProjectErrorKind.Other, ProjectErrors.ofMessage("网络连接失败"))
        assertEquals(ProjectErrorKind.Other, ProjectErrors.ofMessage(null))
    }

    @Test fun insufficientScopeWinsEvenWithPartialDataAndDistinguishesReadFromWrite() = runBlocking {
        val root = """{"data":{"user":{"projectsV2":null}},"errors":[{"type":"INSUFFICIENT_SCOPES","message":"needs read:project"}]}"""
        val read = failure { ProjectsClient { _, _ -> json(root) }.list(ProjectOwnerKind.User, "octocat") }
        assertEquals(ProjectErrors.READ_SCOPE_MESSAGE, read.message)
        assertEquals(403, (read as ApiException).code)
        val write = failure { ProjectsClient { _, _ -> json(root) }.delete("p", "i") }
        assertEquals(ProjectErrors.WRITE_SCOPE_MESSAGE, write.message)
    }

    @Test fun forbiddenAndMissingNodesBecomeFriendlyErrors() = runBlocking {
        val forbidden = failure {
            ProjectsClient { _, _ -> json("""{"data":{"organization":null},"errors":[{"type":"FORBIDDEN","message":"OAuth App access restrictions"}]}""") }
                .list(ProjectOwnerKind.Org, "acme")
        }
        assertEquals(ProjectErrors.FORBIDDEN_PREFIX + "OAuth App access restrictions", forbidden.message)
        val missing = failure { ProjectsClient { _, _ -> json("""{"data":{"node":null}}""") }.detail("x") }
        assertEquals(ProjectErrors.NOT_FOUND_MESSAGE, missing.message)
        val noData = failure { ProjectsClient { _, _ -> json("""{"errors":[{"message":"Parse error"}]}""") }.detail("x") }
        assertEquals("Parse error", noData.message)
    }

    // ---------------- 游标分页 ----------------

    @Test fun cursorsAdaptGraphQlPagingToPageNumbers() = runBlocking {
        val cursors = GraphQlCursors()
        val seen = ArrayList<String?>()
        val pages = mapOf<String?, DiscussionPage<Int>>(
            null to DiscussionPage(listOf(1, 2), "c1", true, 5),
            "c1" to DiscussionPage(listOf(3, 4), "c2", true, 5),
            "c2" to DiscussionPage(listOf(5), "c3", false, 5),
        )
        suspend fun load(page: Int) = cursors.page(page) { after -> seen.add(after); pages.getValue(after) }
        assertEquals(listOf(1, 2), load(1))
        assertEquals(listOf(3, 4), load(2))
        assertEquals(listOf(5), load(3))
        assertEquals(emptyList<Int>(), load(4))
        assertEquals(listOf(null, "c1", "c2"), seen)
        // 刷新从头开始
        assertEquals(listOf(1, 2), load(1))
        assertEquals(listOf(null, "c1", "c2", null), seen)
        // 未知页（未先请求前一页）不发请求
        assertEquals(emptyList<Int>(), GraphQlCursors().page(3) { fail("should not fetch"); pages.getValue(null) })
    }

    // ---------------- 解析与请求 ----------------

    private val summary = """{"id":"P1","number":3,"title":"Roadmap","shortDescription":"","closed":false,"public":true,
        "updatedAt":"2026-10-01T00:00:00Z","url":"https://github.com/users/octocat/projects/3","items":{"totalCount":12}}"""

    @Test fun listUsesVariablesAndParsesSummaries() = runBlocking {
        var query = ""
        var vars: Map<String, Any?> = emptyMap()
        val client = ProjectsClient { q, v ->
            query = q; vars = v
            json("""{"data":{"repository":{"projectsV2":{"totalCount":1,"pageInfo":{"endCursor":"n","hasNextPage":true},"nodes":[$summary,null]}}}}""")
        }
        val page = client.list(ProjectOwnerKind.Repo, "own\"er", "repo", after = "prev")
        assertEquals("own\"er", vars["owner"])
        assertEquals("repo", vars["name"])
        assertEquals("prev", vars["after"])
        assertFalse(query.contains("own\"er"))
        assertTrue(query.contains("orderBy: {field: UPDATED_AT, direction: DESC}"))
        val p = page.items.single()
        assertEquals(ProjectSummary("P1", 3, "Roadmap", null, false, true, "2026-10-01T00:00:00Z", "https://github.com/users/octocat/projects/3", 12), p)
        assertEquals("n", page.endCursor)
        assertTrue(page.hasNextPage)
    }

    @Test fun invalidCursorIsRejected() = runBlocking {
        val e = failure {
            ProjectsClient { _, _ -> json("""{"data":{"user":{"projectsV2":{"totalCount":1,"pageInfo":{"endCursor":"same","hasNextPage":true},"nodes":[]}}}}""") }
                .list(ProjectOwnerKind.User, "u", after = "same")
        }
        assertEquals(502, (e as ApiException).code)
    }

    @Test fun detailParsesFieldsViewsAndAllItemKinds() = runBlocking {
        val v = """"field":{"id":"F_S","name":"Status","dataType":"SINGLE_SELECT"}"""
        val root = """{"data":{"node":{
            "id":"P1","number":3,"title":"Roadmap","shortDescription":null,"closed":false,"url":"u","viewerCanUpdate":true,
            "fields":{"nodes":[
                {"id":"F_T","name":"Title","dataType":"TITLE"},
                {"id":"F_S","name":"Status","dataType":"SINGLE_SELECT","options":[{"id":"o1","name":"Todo","color":"GRAY"},{"id":"o2","name":"Done","color":"PURPLE"}]},
                {"id":"F_I","name":"Sprint","dataType":"ITERATION","configuration":{"iterations":[{"id":"i2","title":"Sprint 2","startDate":"2026-10-14"}],"completedIterations":[{"id":"i1","title":"Sprint 1","startDate":"2026-09-30"}]}},
                {}
            ]},
            "views":{"nodes":[{"id":"V1","number":1,"name":"Board","layout":"BOARD_LAYOUT","filter":"",
                "groupByFields":{"nodes":[]},"verticalGroupByFields":{"nodes":[{"id":"F_S"}]}}]},
            "items":{"totalCount":4,"pageInfo":{"endCursor":"c","hasNextPage":false},"nodes":[
                {"id":"I1","type":"ISSUE","isArchived":false,"content":{"__typename":"Issue","number":7,"title":"Bug","url":"https://github.com/o/r/issues/7",
                    "issueState":"CLOSED","stateReason":"NOT_PLANNED","repository":{"nameWithOwner":"o/r"}},
                 "fieldValues":{"nodes":[
                    {"__typename":"ProjectV2ItemFieldTextValue","text":"Bug","field":{"id":"F_T","name":"Title","dataType":"TITLE"}},
                    {"__typename":"ProjectV2ItemFieldSingleSelectValue","name":"Done","optionId":"o2",$v},
                    {"__typename":"ProjectV2ItemFieldNumberValue","number":3.0,"field":{"id":"F_N","name":"Estimate","dataType":"NUMBER"}},
                    {"__typename":"ProjectV2ItemFieldNumberValue","number":2.5,"field":{"id":"F_N2","name":"Hours","dataType":"NUMBER"}},
                    {"__typename":"ProjectV2ItemFieldLabelValue","labels":{"nodes":[{"name":"bug"},{"name":"ui"}]},"field":{"id":"F_L","name":"Labels","dataType":"LABELS"}},
                    {"__typename":"ProjectV2ItemFieldUserValue","users":{"nodes":[]},"field":{"id":"F_A","name":"Assignees","dataType":"ASSIGNEES"}},
                    {"__typename":"ProjectV2ItemFieldRepositoryValue","field":{"id":"F_R","name":"Repository","dataType":"REPOSITORY"}},
                    {}
                 ]}},
                {"id":"I2","type":"PULL_REQUEST","isArchived":false,"content":{"__typename":"PullRequest","number":8,"title":"Fix","url":"https://github.com/o/r/pull/8",
                    "prState":"OPEN","isDraft":true,"merged":false,"repository":{"nameWithOwner":"o/r"}},"fieldValues":{"nodes":[]}},
                {"id":"I3","type":"DRAFT_ISSUE","isArchived":false,"content":{"__typename":"DraftIssue","title":"Idea","body":"Notes"},"fieldValues":{"nodes":[]}},
                {"id":"I4","type":"REDACTED","isArchived":true,"content":null,"fieldValues":{"nodes":[]}}
            ]}
        }}}"""
        var vars: Map<String, Any?> = emptyMap()
        var query = ""
        val d = ProjectsClient { q, x -> query = q; vars = x; json(root) }.detail("P1")
        assertEquals("P1", vars["id"])
        assertTrue(query.contains("issueState: state") && query.contains("prState: state"))
        assertTrue(d.viewerCanUpdate)
        assertEquals(listOf("F_T", "F_S", "F_I"), d.fields.map { it.id })
        assertEquals(ProjectColor.Purple, d.fields[1].options[1].color)
        assertEquals(listOf("i2", "i1"), d.fields[2].iterations.map { it.id })
        val view = d.views.single()
        assertEquals(ProjectLayout.Board, view.layout)
        assertNull(view.filter)
        assertEquals(d.fields[1], boardGroupField(d.fields, view))

        val (issue, pr, draft, redacted) = d.items.items
        assertEquals(ProjectItemType.Issue, issue.type)
        assertEquals("CLOSED", issue.state)
        assertEquals("NOT_PLANNED", issue.stateReason)
        assertEquals("o/r#7", issue.reference)
        assertEquals("o" to "r", issue.repoParts)
        assertEquals("o2", issue.value("F_S")?.optionId)
        assertEquals("3", issue.value("F_N")?.text)
        assertEquals("2.5", issue.value("F_N2")?.text)
        assertEquals("bug, ui", issue.value("F_L")?.text)
        assertNull(issue.value("F_A"))
        assertNull(issue.value("F_R"))
        assertEquals(listOf("F_N", "F_N2", "F_L"), cardFieldValues(issue, setOf("F_S")).map { it.fieldId })

        assertEquals(ProjectItemType.PullRequest, pr.type)
        assertEquals("OPEN", pr.state)
        assertTrue(pr.isDraft)
        assertFalse(pr.merged)

        assertEquals(ProjectItemType.DraftIssue, draft.type)
        assertEquals("Notes", draft.body)
        assertNull(draft.reference)

        assertEquals(ProjectItemType.Redacted, redacted.type)
        assertTrue(redacted.archived)
        assertEquals(listOf(listOf("I1", "I2", "I3")), groupProjectItems(d.items.items, null).map { c -> c.items.map { it.id } })
    }

    @Test fun mutationsSendInputObjectsAndUseClearForEmptyValue() = runBlocking {
        val calls = ArrayList<Pair<String, JsonObject>>()
        val client = ProjectsClient { q, v ->
            val input = v["input"] as JsonObject
            calls.add(q to input)
            when {
                q.contains("updateProjectV2ItemFieldValue") -> json("""{"data":{"updateProjectV2ItemFieldValue":{"projectV2Item":{"id":"I1"}}}}""")
                q.contains("clearProjectV2ItemFieldValue") -> json("""{"data":{"clearProjectV2ItemFieldValue":{"projectV2Item":{"id":"I1"}}}}""")
                q.contains("deleteProjectV2Item") -> json("""{"data":{"deleteProjectV2Item":{"deletedItemId":"I1"}}}""")
                else -> json("""{"data":{"addProjectV2DraftIssue":{"projectItem":{"id":"I9","type":"DRAFT_ISSUE","isArchived":false,
                    "content":{"__typename":"DraftIssue","title":"New","body":""},"fieldValues":{"nodes":[]}}}}}""")
            }
        }
        client.setSingleSelect("P", "I1", "F", "o2")
        client.setSingleSelect("P", "I1", "F", null)
        client.delete("P", "I1")
        val draft = client.addDraft("P", "  New  ", "")

        val (setQ, setInput) = calls[0]
        assertTrue(setQ.contains("updateProjectV2ItemFieldValue"))
        assertEquals("o2", setInput["value"]!!.jsonObject["singleSelectOptionId"]!!.jsonPrimitive.content)
        assertEquals(listOf("projectId", "itemId", "fieldId", "value"), setInput.keys.toList())
        val (clearQ, clearInput) = calls[1]
        assertTrue(clearQ.contains("clearProjectV2ItemFieldValue"))
        assertFalse(clearInput.containsKey("value"))
        assertEquals("I1", calls[2].second["itemId"]!!.jsonPrimitive.content)
        assertEquals("New", calls[3].second["title"]!!.jsonPrimitive.content)
        assertFalse(calls[3].second.containsKey("body"))
        assertEquals("I9", draft.id)
        assertEquals(ProjectItemType.DraftIssue, draft.type)

        val e = failure { ProjectsClient { _, _ -> json("""{"data":{"deleteProjectV2Item":null}}""") }.delete("P", "I1") }
        assertTrue(e is ApiException)
        val blank = failure { client.addDraft("P", "  ", "") }
        assertTrue(blank is IllegalArgumentException)
    }

    @Test fun numbersFormatWithoutTrailingZero() {
        assertEquals("3", ProjectsClient.fmtNumber(3.0))
        assertEquals("-2", ProjectsClient.fmtNumber(-2.0))
        assertEquals("2.5", ProjectsClient.fmtNumber(2.5))
        assertNull(ProjectsClient.fmtNumber(null))
    }
}
