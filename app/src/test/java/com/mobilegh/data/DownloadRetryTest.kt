package com.mobilegh.data

import android.app.DownloadManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 只有"换节点还有可能成功"的失败才自动重试：
 * 空间不足/文件冲突这类换谁都一样，403/404 说明资源本身拿不到。
 */
class DownloadRetryTest {
    @Test fun connectionProblemsAreRetried() {
        assertTrue(retryableDownloadFailure(DownloadManager.ERROR_UNKNOWN))
        assertTrue(retryableDownloadFailure(DownloadManager.ERROR_HTTP_DATA_ERROR))
        assertTrue(retryableDownloadFailure(DownloadManager.ERROR_TOO_MANY_REDIRECTS))
        assertTrue(retryableDownloadFailure(DownloadManager.ERROR_CANNOT_RESUME))
        assertTrue(retryableDownloadFailure(500))
        assertTrue(retryableDownloadFailure(502))
        assertTrue(retryableDownloadFailure(408))
    }

    @Test fun storageProblemsAreNotRetried() {
        assertFalse(retryableDownloadFailure(DownloadManager.ERROR_INSUFFICIENT_SPACE))
        assertFalse(retryableDownloadFailure(DownloadManager.ERROR_FILE_ALREADY_EXISTS))
        assertFalse(retryableDownloadFailure(DownloadManager.ERROR_DEVICE_NOT_FOUND))
        assertFalse(retryableDownloadFailure(DownloadManager.ERROR_FILE_ERROR))
    }

    @Test fun permissionAndMissingResourcesAreNotRetried() {
        assertEquals(false, retryableDownloadFailure(401))
        assertEquals(false, retryableDownloadFailure(403))
        assertEquals(false, retryableDownloadFailure(404))
        assertEquals(false, retryableDownloadFailure(451))
        // 限流和超时是临时的，换节点有意义
        assertEquals(true, retryableDownloadFailure(429))
    }
}
