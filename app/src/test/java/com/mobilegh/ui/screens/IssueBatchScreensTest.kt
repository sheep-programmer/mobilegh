package com.mobilegh.ui.screens

import com.mobilegh.data.DraftEntry
import com.mobilegh.data.DraftValue
import com.mobilegh.data.Repo
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IssueBatchScreensTest {
    private val repo = Repo(name = "app", description = "desc", homepage = "", defaultBranch = "main")

    @Test fun settingsPatchOnlyContainsChangedFields() {
        // 只改描述：不能带上默认分支（空仓库会因此 422）
        val p = repoSettingsPatch(repo, "app", "new desc", "", "main")
        assertEquals(setOf("description"), p.keys)
        assertEquals("new desc", p["description"]!!.jsonPrimitive.content)
    }

    @Test fun settingsPatchRenameAndBranch() {
        val p = repoSettingsPatch(repo, " app2 ", "desc", "", "dev")
        assertEquals(setOf("name", "default_branch"), p.keys)
        assertEquals("app2", p["name"]!!.jsonPrimitive.content)
        assertTrue(repoSettingsPatch(repo, "app", "desc", "", "main").isEmpty())
    }

    @Test fun fileSearchQueryUsesFilenamePathAndExtension() {
        assertEquals("filename:README repo:o/r", fileSearchQuery("o", "r", "README"))
        assertEquals("extension:md repo:o/r", fileSearchQuery("o", "r", ".md"))
        assertEquals("path:.github/workflows repo:o/r", fileSearchQuery("o", "r", ".github/workflows/"))
        assertEquals("path:src/main filename:App repo:o/r", fileSearchQuery("o", "r", "src/main App"))
        assertNull(fileSearchQuery("o", "r", "   "))
    }

    @Test fun draftLabelsDescribeLocation() {
        assertEquals("新建 Issue · o/r", draftLabel("new-issue:o/r"))
        assertEquals("评论 · o/r#12", draftLabel("comment:o/r:12"))
        assertEquals("编辑文件 · o/r/src/A.kt", draftLabel("edit-file:o/r:src/A.kt:abc"))
        assertEquals("unknown:thing", draftLabel("unknown:thing"))
    }

    @Test fun draftPreviewPrefersTitleThenFirstLine() {
        assertEquals("标题", draftPreview(DraftEntry("k", DraftValue(title = "标题", body = "正文"), null)))
        assertEquals("第二行", draftPreview(DraftEntry("k", DraftValue(body = "\n  \n第二行\n第三行"), null)))
    }
}
