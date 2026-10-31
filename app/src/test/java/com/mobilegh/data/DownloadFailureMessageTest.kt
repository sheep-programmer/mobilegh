package com.mobilegh.data

import android.app.DownloadManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 公开下载失败时给用户看的说明：HTTP 类与私有下载保持一致，5xx 是服务器暂时不可用，
 * 原始错误码只写日志，界面文案里不出现数字。
 */
class DownloadFailureMessageTest {
    @Test fun storageProblemsHaveTheirOwnMessages() {
        assertEquals("存储空间不足，请清理后重试", downloadFailureMessage(DownloadManager.ERROR_INSUFFICIENT_SPACE))
        assertEquals("同名文件已存在，请重试", downloadFailureMessage(DownloadManager.ERROR_FILE_ALREADY_EXISTS))
        assertEquals("存储设备不可用", downloadFailureMessage(DownloadManager.ERROR_DEVICE_NOT_FOUND))
        assertEquals("无法写入文件，请检查下载目录", downloadFailureMessage(DownloadManager.ERROR_FILE_ERROR))
    }

    @Test fun httpPermissionAndMissingCodesMatchPrivateDownloads() {
        assertEquals("需要登录 GitHub 后才能下载", downloadFailureMessage(401))
        assertEquals("没有下载权限，或资源已经删除", downloadFailureMessage(403))
        assertEquals("没有下载权限，或资源已经删除", downloadFailureMessage(404))
        assertEquals("该资源因法律原因无法下载", downloadFailureMessage(451))
    }

    @Test fun serverErrorsSayTheServiceIsTemporarilyUnavailable() {
        for (code in listOf(500, 502, 503, 599)) {
            assertEquals("服务器暂时不可用，请稍后重试", downloadFailureMessage(code))
        }
    }

    @Test fun rateLimitAndDataInterruptionHaveSpecificMessages() {
        assertEquals("请求过于频繁，请稍后重试", downloadFailureMessage(429))
        assertEquals("数据传输中断，请检查网络后重试", downloadFailureMessage(DownloadManager.ERROR_HTTP_DATA_ERROR))
        assertEquals("下载中断且无法续传，请重新下载", downloadFailureMessage(DownloadManager.ERROR_CANNOT_RESUME))
    }

    @Test fun unknownReasonsFallBackToTheGenericMessage() {
        assertEquals("下载失败，请检查网络后重试", downloadFailureMessage(DownloadManager.ERROR_UNKNOWN))
        assertEquals("下载失败，请检查网络后重试", downloadFailureMessage(418))
    }

    @Test fun rawCodesNeverAppearInUserText() {
        for (code in listOf(400, 418, 429, 500, 503, 999, DownloadManager.ERROR_UNKNOWN, DownloadManager.ERROR_HTTP_DATA_ERROR)) {
            assertFalse("code $code leaked into the message", downloadFailureMessage(code).any { it.isDigit() })
        }
    }

    @Test fun stuckHintOnlyOffersNodeChangeWhenThereIsANode() {
        assertEquals("节点无响应，系统仍在重试；可取消或更换节点", stuckDownloadHint(isPrivate = false, slow = false))
        val privateHint = stuckDownloadHint(isPrivate = true, slow = false)
        assertTrue(privateHint.contains("取消"))
        assertFalse(privateHint.contains("更换节点"))
    }

    @Test fun slowHintSaysSpeedNotNode() {
        assertTrue(stuckDownloadHint(isPrivate = false, slow = true).contains("下载速度过慢"))
    }
}
