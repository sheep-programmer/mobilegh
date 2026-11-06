package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentPlanTest {
    @Test fun smallOrUnsupportedFilesUseASingleSegment() {
        assertEquals(listOf(0L..-1L), SegmentPlan.plan(10, rangesSupported = false))
        assertEquals(listOf(0L until 1), SegmentPlan.plan(1, true))
        assertEquals(listOf(0L until SegmentPlan.MIN_SEGMENT), SegmentPlan.plan(SegmentPlan.MIN_SEGMENT, true))
    }

    @Test fun bigFilesSplitIntoEvenRangesCoveringEverything() {
        val total = 40L * 1024 * 1024
        val parts = SegmentPlan.plan(total, true)
        assertEquals(4, parts.size)
        // 各段连续且拼起来正好覆盖全文件
        assertEquals(0L, parts.first().first)
        assertEquals(total - 1, parts.last().last)
        parts.zipWithNext().forEach { (a, b) -> assertEquals(a.last + 1, b.first) }
        // 每段至少 8 MiB
        parts.forEach { assertTrue(it.last - it.first + 1 >= SegmentPlan.MIN_SEGMENT) }
    }

    @Test fun remaindersGoToTheFirstSegmentsAndCapAtMaxSegments() {
        // 10 MiB 只够分成 1 段（每段至少 8 MiB）；16 MiB 分成 2 段
        assertEquals(1, SegmentPlan.plan(10L * 1024 * 1024 + 7, true).size)
        val parts = SegmentPlan.plan(16L * 1024 * 1024 + 3, true)
        assertEquals(2, parts.size)
        assertEquals(16L * 1024 * 1024 + 3, parts.sumOf { it.last - it.first + 1 })
        // 请求的段数超过文件能分出的数量时按 8 MiB 一段截住
        assertEquals(2, SegmentPlan.plan(16L * 1024 * 1024, true, segments = 4).size)
    }
}

class CommentAttachmentsTest {
    @Test fun imagesAndVideosAreAllowedByExtension() {
        CommentAttachments.IMAGE_EXT.forEach { assertTrue(it, CommentAttachments.isAllowed("photo.$it")) }
        CommentAttachments.VIDEO_EXT.forEach { assertTrue(it, CommentAttachments.isAllowed("clip.$it")) }
        assertTrue(CommentAttachments.isAllowed("IMG_0001.JPG"))
    }

    @Test fun otherTypesAreRejectedBeforeUploading() {
        listOf("notes.txt", "code.zip", "doc.pdf", "sheet.xlsx", "no-ext").forEach {
            assertFalse(it, CommentAttachments.isAllowed(it))
        }
    }

    @Test fun mimeTypesMatchTheExtension() {
        assertEquals("image/png", CommentAttachments.mimeOf("a.png"))
        assertEquals("video/mp4", CommentAttachments.mimeOf("a.mp4"))
        assertEquals("application/octet-stream", CommentAttachments.mimeOf("a.xyz"))
    }
}
