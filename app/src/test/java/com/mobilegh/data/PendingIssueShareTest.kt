package com.mobilegh.data

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PendingIssueShareTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun peekKeepsTheShareAndAttachmentsUntilFinish() {
        val store = PendingIssueShare.Store(temp.root)
        val attachment = File(temp.root, "a.png").apply { writeText("image") }
        val id = store.save(IssueShare("body", listOf(SharedAttachment(attachment.absolutePath, "image/png", "a.png"))))
        // 组合、重进页面或进程回收后重新读取，都还能拿到同一份元数据
        assertEquals("body", store.peek(id)?.text)
        assertEquals("body", store.peek(id)?.text)
        assertTrue("附件必须保留到上传完成", attachment.exists())
        assertEquals("body", store.finish(id)?.text)
        assertNull(store.peek(id))
        assertNull(store.finish(id))
        assertFalse("上传完成后缓存附件随元数据一起清理", attachment.exists())
    }

    @Test fun aNewShareReplacesTheOldMetadataAndCachedFiles() {
        val store = PendingIssueShare.Store(temp.root)
        val oldFile = File(temp.root, "old.png").apply { writeText("old") }
        val first = store.save(IssueShare("old", listOf(SharedAttachment(oldFile.absolutePath, "image/png", "old.png"))))
        val id = store.save(IssueShare("new", emptyList()))
        assertNull(store.peek(first))
        assertFalse(oldFile.exists())
        assertEquals("new", store.peek(id)?.text)
    }

    @Test fun failedReplacementKeepsPreviousShareAndFiles() {
        val ids = arrayOf("00000000-0000-0000-0000-000000000000", "00000000-0000-0000-0000-000000000001")
        var next = 0
        val store = PendingIssueShare.Store(temp.root, newId = { ids[next++] })
        val oldFile = File(temp.root, "old.png").apply { writeText("old image") }
        val oldId = store.save(IssueShare("old draft", listOf(SharedAttachment(oldFile.absolutePath, "image/png", "old.png"))))
        val oldMetadata = File(temp.root, "$oldId.json").readText()
        assertTrue(File(temp.root, "00000000-0000-0000-0000-000000000001.json").mkdir())
        org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            store.save(IssueShare("replacement", emptyList()))
        }
        assertEquals(oldMetadata, File(temp.root, "$oldId.json").readText())
        assertEquals("old draft", store.peek(oldId)?.text)
        assertEquals("old image", oldFile.readText())
    }

    @Test fun pendingShareIsNotBoundToLoginAndSurvivesStoreRecreation() {
        val id = PendingIssueShare.Store(temp.root).save(IssueShare("kept while logged out", emptyList()))
        assertEquals("kept while logged out", PendingIssueShare.Store(temp.root).peek(id)?.text)
    }

    @Test fun storesSharingTheProductionRootSerializeSaveAndPeek() {
        val oldId = PendingIssueShare.Store(temp.root).save(IssueShare("old", emptyList()))
        val saveStarted = CountDownLatch(1)
        val allowSave = CountDownLatch(1)
        val savingStore = PendingIssueShare.Store(temp.root, newId = {
            saveStarted.countDown()
            check(allowSave.await(5, TimeUnit.SECONDS))
            "00000000-0000-0000-0000-000000000002"
        })
        val readingStore = PendingIssueShare.Store(temp.root)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val saving = pool.submit<String> { savingStore.save(IssueShare("new", emptyList())) }
            assertTrue(saveStarted.await(5, TimeUnit.SECONDS))
            val reading = pool.submit<String?> { readingStore.peek(oldId)?.text }
            try {
                reading.get(200, TimeUnit.MILLISECONDS)
                fail("a second Store must wait for the in-flight save on the same root")
            } catch (_: TimeoutException) {
                // Expected: the root-level transaction is still in progress.
            }
            allowSave.countDown()
            assertEquals("00000000-0000-0000-0000-000000000002", saving.get(5, TimeUnit.SECONDS))
            assertNull(reading.get(5, TimeUnit.SECONDS))
        } finally {
            allowSave.countDown()
            pool.shutdownNow()
        }
    }
}
