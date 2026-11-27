package com.mobilegh.ui.screens

import com.mobilegh.data.DraftController
import com.mobilegh.data.DraftStore
import com.mobilegh.data.DraftValue
import com.mobilegh.data.MemoryRecords
import com.mobilegh.data.SharedAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NewIssueDraftTest {
    @Test fun blankFormHasNoContentToSave() {
        assertTrue(newIssueDraft("", "", NewIssueMeta()).empty)
    }

    @Test fun metadataAloneIsContentAndSurvivesRestore() {
        val meta = NewIssueMeta(
            labels = listOf(MetaOpt("bug", "bug", color = "ff0000")),
            assignees = listOf(MetaOpt("alice", "alice", avatar = "https://example.test/a.png")),
            milestone = MetaOpt("3", "v1"),
            issueType = MetaOpt("IT_bug", "Bug", color = "RED"),
        )
        val draft = newIssueDraft("", "", meta)
        assertFalse(draft.empty)

        val restored = draft.toNewIssueMeta()
        assertEquals(listOf("bug"), restored.labels.map { it.key })
        assertEquals("ff0000", restored.labels.single().color)
        assertEquals(listOf("alice"), restored.assignees.map { it.key })
        assertEquals("https://example.test/a.png", restored.assignees.single().avatar)
        assertEquals("3", restored.milestone?.key)
        assertEquals("v1", restored.milestone?.label)
        assertEquals("IT_bug", restored.issueType?.key)
        assertEquals("Bug", restored.issueType?.label)
        assertEquals("RED", restored.issueType?.color)
    }

    @Test fun titleAndBodyStillRoundTrip() {
        val draft = newIssueDraft("Crash on start", "steps", NewIssueMeta())
        assertEquals("Crash on start", draft.title)
        assertEquals("steps", draft.body)
        assertTrue(draft.toNewIssueMeta().labels.isEmpty())
    }

    @Test fun restoredMetadataIsExcludedFromSubmissionWithoutExplicitWritePermission() {
        val restored = newIssueDraft(
            "Crash", "steps",
            NewIssueMeta(
                labels = listOf(MetaOpt("bug", "bug")),
                assignees = listOf(MetaOpt("alice", "alice")),
                milestone = MetaOpt("3", "v1"),
                issueType = MetaOpt("IT_bug", "Bug"),
            ),
        ).toNewIssueMeta()

        assertEquals(NewIssueMeta(), newIssueSubmissionMeta(restored, canSetMeta = false))
        assertEquals(NewIssueMeta(), newIssueSubmissionMeta(restored, canSetMeta = null))
        assertEquals(restored, newIssueSubmissionMeta(restored, canSetMeta = true))
    }

    @Test fun existingDraftAndSharedPrefillAreBothPreservedWithoutDuplication() {
        assertEquals("saved draft\n\nshared text", initialSharedBody("saved draft", "shared text"))
        assertEquals("shared text", initialSharedBody("", "shared text"))
        assertEquals("saved draft\n\nshared text", initialSharedBody("saved draft\n\nshared text", "shared text"))
    }

    @Test fun choosingATemplateKeepsSharedMarkdownButNeverInjectsItIntoYamlForms() {
        assertEquals("template\n\nshared", sharedTemplateBody("template", "shared", yamlForm = false))
        assertEquals("", sharedTemplateBody("template", "shared", yamlForm = true))
    }

    @Test fun createMoreStartsFromTemplateWithoutConsumingSharedTextAgain() {
        assertEquals("template", sharedTemplateBody("template", "shared", yamlForm = false, includeShared = false))
        assertEquals("template\n\nshared", sharedTemplateBody("template", "shared", yamlForm = false))
    }

    @Test fun createdIssueDraftIsClearedBeforeOptionalFollowUpCanBeCancelled() {
        val storage = MemoryRecords()
        val store = DraftStore(storage)
        val value = DraftValue(title = "Created on GitHub")
        store.save("alice", "new-issue:mona/repo", value)
        val controller = DraftController(store, "alice", "new-issue:mona/repo")

        assertThrows(CancellationException::class.java) {
            runBlocking {
                createIssueWithDraftAck(
                    draft = controller,
                    submitted = value,
                    create = { 42 },
                    afterAcknowledged = { throw CancellationException("screen closed after REST creation") },
                )
            }
        }
        assertTrue(store.load("alice", "new-issue:mona/repo").empty)
    }

    @Test fun submissionWaitsForEditorAndSharedAttachmentUploads() {
        assertTrue(canSubmitNewIssue("Crash", false, emptyList(), uploadsInFlight = 0, sharedUploadsPending = false))
        assertFalse(canSubmitNewIssue("Crash", false, emptyList(), uploadsInFlight = 1, sharedUploadsPending = false))
        assertFalse(canSubmitNewIssue("Crash", false, emptyList(), uploadsInFlight = 0, sharedUploadsPending = true))
    }

    @Test fun yamlFormsKeepSharedContentPendingAndBlockSubmission() {
        assertTrue(sharedContentBlockedByYamlForm(true, "shared text", sharedAttachmentsPending = false))
        assertTrue(sharedContentBlockedByYamlForm(true, "", sharedAttachmentsPending = true))
        assertFalse(sharedContentBlockedByYamlForm(false, "shared text", sharedAttachmentsPending = true))
        assertFalse(sharedContentBlockedByYamlForm(true, "", sharedAttachmentsPending = false))
        assertFalse(
            canSubmitNewIssue(
                "Crash", false, emptyList(), uploadsInFlight = 0,
                sharedUploadsPending = false, sharedContentPending = true,
            )
        )
    }

    @Test fun unresolvedTemplateCannotConsumePendingSharedContent() {
        assertFalse(sharedContentCanBeConsumed(yamlForm = false, templateResolved = false))
        assertFalse(sharedContentCanBeConsumed(yamlForm = true, templateResolved = true))
        assertTrue(sharedContentCanBeConsumed(yamlForm = false, templateResolved = true))
    }

    @Test fun sharedImageUploadsResumeAfterTheAttachmentThatFailed() = runBlocking {
        val attachments = listOf(
            SharedAttachment("/tmp/a.png", "image/png", "a.png"),
            SharedAttachment("/tmp/b.png", "image/png", "b.png"),
            SharedAttachment("/tmp/c.png", "image/png", "c.png"),
        )
        val inserted = mutableListOf<String>()
        var failOnSecond = true
        val upload: suspend (SharedAttachment) -> String = { a ->
            if (failOnSecond && a.name == "b.png") error("network down")
            "https://cdn.test/${a.name}"
        }
        val progress = mutableListOf<Int>()

        val first = runCatching {
            uploadShared(attachments, 0, upload, { _, url -> inserted += url }, onProgress = { progress += it })
        }
        assertTrue(first.isFailure)
        assertEquals("失败时下标停在没有上传成功的那张", listOf(1), progress)
        assertEquals(listOf("https://cdn.test/a.png"), inserted)

        failOnSecond = false
        val done = uploadShared(attachments, 1, upload, { _, url -> inserted += url }, onProgress = { progress += it })
        assertEquals(3, done)
        assertEquals(listOf(1, 2, 3), progress)
        assertEquals(
            listOf("https://cdn.test/a.png", "https://cdn.test/b.png", "https://cdn.test/c.png"),
            inserted,
        )
    }

    @Test fun finishedSharedUploadsAreNotUploadedAgain() = runBlocking {
        var calls = 0
        val done = uploadShared(
            listOf(SharedAttachment("/tmp/a.png", "image/png", "a.png")),
            startIndex = 1,
            upload = { calls++; "https://cdn.test/a.png" },
            insert = { _, _ -> fail("已完成的上传不应再次插入正文") },
        )
        assertEquals(1, done)
        assertEquals(0, calls)
    }

    @Test fun untouchedTemplateDoesNotProduceADraft() {
        val template = com.mobilegh.data.IssueTemplate(
            file = "feature.yml",
            name = "功能建议",
            title = "",
            body = "默认正文",
            labels = listOf("enhancement"),
        )
        val initialMeta = NewIssueMeta(labels = listOf(MetaOpt("enhancement", "enhancement", color = "a2eeef")))
        assertTrue(newIssueDraftOrEmpty("", "默认正文", initialMeta, template, emptyMap()).empty)
        assertFalse(newIssueDraftOrEmpty("新标题", "默认正文", initialMeta, template, emptyMap()).empty)
        assertTrue(newIssueDraftOrEmpty("", "", NewIssueMeta(), template = null, emptyMap()).empty)
    }
}
