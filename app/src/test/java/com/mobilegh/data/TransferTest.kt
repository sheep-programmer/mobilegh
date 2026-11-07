package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentPlanTest {
    @Test fun aFileNeedsAtLeastTwoMinimumPiecesToBeSplit() {
        assertFalse(SegmentPlan.worthSplitting(SegmentPlan.MIN_PIECE))
        assertTrue(SegmentPlan.worthSplitting(2 * SegmentPlan.MIN_PIECE))
    }

    @Test fun piecesCoverTheWholeFileWithoutGapsOrOverlap() {
        val total = 40L * 1024 * 1024 + 5
        val pieces = SegmentPlan.pieces(total, 3L * 1024 * 1024)
        assertEquals(0L, pieces.first().first)
        assertEquals(total - 1, pieces.last().last)
        pieces.zipWithNext().forEach { (a, b) -> assertEquals(a.last + 1, b.first) }
        assertEquals(total, pieces.sumOf { it.last - it.first + 1 })
    }

    @Test fun pieceSizeGivesEachConnectionSeveralPiecesAndStaysWithinLimits() {
        // 大文件：夹在最大分块
        assertEquals(SegmentPlan.MAX_PIECE, SegmentPlan.pieceSize(1024L * 1024 * 1024, 8))
        // 小文件：夹在最小分块
        assertEquals(SegmentPlan.MIN_PIECE, SegmentPlan.pieceSize(8L * 1024 * 1024, 8))
        // 中间值：32 MiB 给 4 条连接，每条约两块 → 4 MiB
        assertEquals(4L * 1024 * 1024, SegmentPlan.pieceSize(32L * 1024 * 1024, 4))
    }

    @Test fun threadChoicesAreWithinLimitsAndIncludeSingleConnection() {
        assertTrue(1 in SegmentPlan.THREAD_CHOICES)
        assertTrue(SegmentPlan.DEFAULT_SEGMENTS in SegmentPlan.THREAD_CHOICES)
        assertTrue(SegmentPlan.THREAD_CHOICES.all { it in 1..SegmentPlan.MAX_SEGMENTS })
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
