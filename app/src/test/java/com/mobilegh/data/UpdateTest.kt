package com.mobilegh.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateTest {
    @Test fun higherNumericSegmentIsNewer() {
        assertTrue(isNewerVersion("1.2.10", "1.2.9"))
        assertTrue(isNewerVersion("2", "1.9.9"))
        assertTrue(isNewerVersion("1.3", "1.2.9"))
    }

    @Test fun equalOrOlderIsNotNewer() {
        assertFalse(isNewerVersion("1.2.4", "1.2.4"))
        assertFalse(isNewerVersion("1.2.3", "1.2.4"))
        assertFalse(isNewerVersion("1.2", "1.2.0"))
    }

    @Test fun buildSuffixAfterPlusCountsAsAPatchLevel() {
        // 版本名 1.2.4+1 与 1.2.4 的比较按数字段进行
        assertTrue(isNewerVersion("1.2.4+1", "1.2.4"))
        assertFalse(isNewerVersion("1.2.4", "1.2.4+1"))
    }

    @Test fun installedUpdatePackageIsRemovedOnlyAfterTheNewVersionRuns() {
        // 下载的是 1.2.4，当前已经是 1.2.4：说明装好了，可以删安装包
        assertTrue(shouldRemoveInstalledPackage("1.2.4", "1.2.4"))
        // 当前版本更低：还没装，保留安装包
        assertFalse(shouldRemoveInstalledPackage("1.3.0", "1.2.4"))
        // 已经比记录更新（例如再次更新过）：同样可以删
        assertTrue(shouldRemoveInstalledPackage("1.2.3", "1.2.4"))
        // 没有记录或版本无法识别：不动安装包
        assertFalse(shouldRemoveInstalledPackage(null, "1.2.4"))
        assertFalse(shouldRemoveInstalledPackage("", "1.2.4"))
        assertFalse(shouldRemoveInstalledPackage("latest", "1.2.4"))
    }

    @Test fun unparseableRemoteIsNeverNewer() {
        assertFalse(isNewerVersion("", "1.0.0"))
        assertFalse(isNewerVersion("latest", "1.0.0"))
    }
}
