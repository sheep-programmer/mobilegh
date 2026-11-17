package com.mobilegh.nav

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilegh.MainActivity
import com.mobilegh.data.PendingIssueShare
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareImportTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "issue-share")
    private val imported = mutableListOf<String>()

    @After fun cleanUpImportedShares() {
        imported.forEach { PendingIssueShare.finish(context, it) }
    }

    @Test fun realContentUriPersistsTextAndImageUntilFinish() {
        val source = uri("pixel.png", bytes = 257)
        val id = import(send(source, text = "  steps to reproduce  ", type = "text/plain"))

        val first = PendingIssueShare.peek(context, id)
        assertEquals("steps to reproduce", first?.text)
        val attachment = first?.attachments?.single()
        assertEquals("pixel.png", attachment?.name)
        assertEquals("image/png", attachment?.mime)
        assertTrue(File(requireNotNull(attachment).path).isFile)
        assertEquals(root.canonicalFile, File(attachment.path).canonicalFile.parentFile)
        assertArrayEquals(bytes(257), File(attachment.path).readBytes())
        assertTrue(File(root, "$id.json").isFile)

        assertEquals(first, PendingIssueShare.peek(context, id))
        assertTrue(File(attachment.path).isFile)

        assertEquals(first, PendingIssueShare.finish(context, id))
        assertNull(PendingIssueShare.peek(context, id))
        assertFalse(File(attachment.path).exists())
        assertFalse(File(root, "$id.json").exists())
    }

    @Test fun clipDataOnlyTextIsPersisted() {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").apply {
            clipData = ClipData.newPlainText("shared text", "  steps from ClipData  ")
        }

        val id = import(intent)

        val share = PendingIssueShare.peek(context, id)
        assertEquals("steps from ClipData", share?.text)
        assertTrue(share?.attachments?.isEmpty() == true)
    }

    @Test fun multipleRealClipDataAttachmentsArePersisted() {
        val sources = listOf(
            uri("one.png", bytes = 31),
            uri("two.jpg", mime = "image/jpeg", bytes = 47),
            uri("three.gif", mime = "image/gif", bytes = 63),
        )
        val id = import(send(*sources.toTypedArray()))

        val share = PendingIssueShare.peek(context, id)
        assertEquals("", share?.text)
        assertEquals(listOf("one.png", "two.jpg", "three.gif"), share?.attachments?.map { it.name })
        assertEquals(listOf("image/png", "image/jpeg", "image/gif"), share?.attachments?.map { it.mime })
        assertEquals(listOf(31L, 47L, 63L), share?.attachments?.map { File(it.path).length() })
    }

    @Test fun multipleTypedStreamAttachmentsWithoutClipDataArePersisted() {
        val sources = listOf(uri("one.png", bytes = 31), uri("two.jpg", mime = "image/jpeg", bytes = 47))
        val intent = send(*sources.toTypedArray()).apply { clipData = null }

        val id = import(intent)

        val share = PendingIssueShare.peek(context, id)
        assertEquals(listOf("one.png", "two.jpg"), share?.attachments?.map { it.name })
    }

    @Test fun streamsRepeatedInClipDataAreImportedOnlyOnce() {
        val sources = listOf(uri("one.png", bytes = 31), uri("two.jpg", mime = "image/jpeg", bytes = 47))
        val intent = send(*sources.toTypedArray()).apply {
            putExtra(Intent.EXTRA_STREAM, sources.first())
        }

        val id = import(intent)

        assertEquals(listOf("one.png", "two.jpg"), PendingIssueShare.peek(context, id)?.attachments?.map { it.name })
    }

    @Test fun sendMultipleIsVisibleToTheSystemShareChooser() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/png")
        val activities = context.packageManager.queryIntentActivities(intent, 0)
        assertTrue(activities.any { it.activityInfo.packageName == context.packageName })
    }

    @Test fun wildcardImageIntentIsVisibleAndUsesResolvedAttachmentTypes() {
        val intent = Intent(Intent.ACTION_SEND).setType("image/*")
        val activities = context.packageManager.queryIntentActivities(intent, 0)
        assertTrue(activities.any { it.activityInfo.packageName == context.packageName })
        val wildcardOnly = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_SEND).setType("image/webp"), 0)
        assertTrue(wildcardOnly.any { it.activityInfo.packageName == context.packageName })

        val id = import(send(uri("wildcard.png"), type = "image/*"))
        assertEquals("image/png", PendingIssueShare.peek(context, id)?.attachments?.single()?.mime)
        assertRejected(send(uri("vector.svg", mime = "image/svg+xml"), type = "image/*"), IllegalArgumentException::class.java)
    }

    @Test fun launchingMainActivityWithTypedStreamsImportsTheShare() {
        val before = root.listFiles { file -> file.extension == "json" }?.map { it.name }?.toSet().orEmpty()
        val intent = send(uri("activity-one.png", bytes = 31), uri("activity-two.jpg", mime = "image/jpeg", bytes = 47))
            .setClass(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .apply { clipData = null }
        val activity = InstrumentationRegistry.getInstrumentation().startActivitySync(intent)
        try {
            val deadline = System.nanoTime() + 5_000_000_000L
            var id: String? = null
            while (System.nanoTime() < deadline && id == null) {
                id = root.listFiles { file -> file.extension == "json" && file.nameWithoutExtension !in before }
                    ?.singleOrNull()?.nameWithoutExtension
                if (id == null) Thread.sleep(50)
            }
            assertNotNull("MainActivity must consume ACTION_SEND_MULTIPLE", id)
            imported += requireNotNull(id)
            assertEquals(
                listOf("activity-one.png", "activity-two.jpg"),
                PendingIssueShare.peek(context, id)?.attachments?.map { it.name },
            )
        } finally {
            activity.finish()
        }
    }

    @Test fun unsupportedAttachmentTypeIsRejectedBeforeCopy() {
        val source = uri("document.pdf", mime = "application/pdf")
        assertRejected(send(source, type = "text/plain"), IllegalArgumentException::class.java)
    }

    @Test fun declaredPerFileAggregateAndCountLimitsAreRejectedBeforeCopy() {
        assertRejected(
            send(uri("large.png", bytes = 8, reportedSize = 10 * 1024 * 1024L + 1)),
            IllegalArgumentException::class.java,
        )
        val tooLargeTogether = List(6) { index ->
            uri("aggregate-$index.png", bytes = 8, reportedSize = 9 * 1024 * 1024L)
        }
        assertRejected(send(*tooLargeTogether.toTypedArray()), IllegalArgumentException::class.java)
        val tooMany = List(11) { index -> uri("count-$index.png") }
        assertRejected(send(*tooMany.toTypedArray()), IllegalArgumentException::class.java)
    }

    @Test fun unknownSizeStreamOverTenMiBIsRejectedAndRolledBack() {
        val before = snapshot()
        val first = uri("complete.png", bytes = 128, reportedSize = null)
        val oversized = uri("oversized.png", bytes = 10 * 1024 * 1024 + 1, reportedSize = null)

        assertRejected(send(first, oversized), IllegalArgumentException::class.java)
        assertEquals(before, snapshot())
    }

    @Test fun unknownSizeStreamsOverFiftyMiBAreRejectedAndRolledBack() {
        val before = snapshot()
        val sources = List(6) { index ->
            uri("stream-aggregate-$index.png", bytes = 9 * 1024 * 1024, reportedSize = null)
        }

        assertRejected(send(*sources.toTypedArray()), IllegalArgumentException::class.java)
        assertEquals(before, snapshot())
    }

    @Test fun streamFailureRollsBackEveryNewAttachment() {
        val before = snapshot()
        val complete = uri("complete.png", bytes = 4096)
        val broken = uri("broken.png", bytes = 4096, failAfter = 100)

        assertRejected(send(complete, broken), Exception::class.java)
        assertEquals(before, snapshot())
    }

    @Test fun failedReplacementPreservesTheOldValidShare() {
        val oldId = import(send(uri("old.png", bytes = 101), text = "old draft", type = "text/plain"))
        val old = PendingIssueShare.peek(context, oldId)
        val oldFiles = old?.attachments?.map { File(it.path) }.orEmpty()
        val before = snapshot()

        assertRejected(
            send(
                uri("new-complete.png", bytes = 128),
                uri("new-broken.png", bytes = 128, failAfter = 20),
                text = "replacement",
                type = "text/plain",
            ),
            Exception::class.java,
        )

        assertEquals(old, PendingIssueShare.peek(context, oldId))
        assertTrue(oldFiles.all { it.isFile })
        assertEquals(before, snapshot())
    }

    private fun import(intent: Intent): String {
        val id = ShareIntents.import(context, intent)
        assertNotNull(id)
        imported += requireNotNull(id)
        return id
    }

    private fun assertRejected(intent: Intent, type: Class<out Throwable>) {
        val before = snapshot()
        assertThrows(type) { ShareIntents.import(context, intent) }
        assertEquals(before, snapshot())
    }

    private fun snapshot(): Map<String, Long> =
        root.listFiles()?.associate { it.name to it.length() } ?: emptyMap()

    private fun send(vararg sources: Uri, text: String? = null, type: String = "image/png"): Intent {
        val intent = Intent(if (sources.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND)
            .setType(type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        text?.let { intent.putExtra(Intent.EXTRA_TEXT, it) }
        if (sources.size == 1) intent.putExtra(Intent.EXTRA_STREAM, sources.single())
        if (sources.size > 1) intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(sources.toList()))
        if (sources.isNotEmpty()) {
            intent.clipData = ClipData.newUri(context.contentResolver, "shared images", sources.first()).apply {
                sources.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
        }
        return intent
    }

    private fun uri(
        name: String,
        mime: String = "image/png",
        bytes: Int = 32,
        reportedSize: Long? = bytes.toLong(),
        failAfter: Int? = null,
    ): Uri = Uri.Builder()
        .scheme("content")
        .authority(AUTHORITY)
        .appendPath(UUID.randomUUID().toString())
        .appendQueryParameter("name", name)
        .appendQueryParameter("mime", mime)
        .appendQueryParameter("bytes", bytes.toString())
        .apply {
            reportedSize?.let { appendQueryParameter("reported", it.toString()) }
            failAfter?.let { appendQueryParameter("failAfter", it.toString()) }
        }
        .build()

    private companion object {
        const val AUTHORITY = "com.mobilegh.test.share-import"
        fun bytes(count: Int) = ByteArray(count) { index -> (index * 31 % 251).toByte() }
    }
}
