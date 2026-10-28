package com.mobilegh.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 入口形式的守卫测试。
 *
 * 选文件必须走系统原生文件选择器（直接多选文件），不能变成「先授权一个目录、再在应用里翻」：
 * 后者要求用户为一个本来只要选几个文件的操作交出整个目录树的读取权限，是我早前实现里的错误方向，
 * 已经回退过一次。这类"看起来更省事"的替换很容易又被写回来，所以用源码文本钉住。
 *
 * 只扫 src/main，不扫测试目录：测试里出现 FileBrowser 字样不算把入口改掉。
 */
class UploadEntryStyleTest {

    private val repoRoot by lazy {
        var d = File(System.getProperty("user.dir")).absoluteFile
        while (!File(d, "app/src/main/java/com/mobilegh/ui/screens").isDirectory && d.parentFile != null) d = d.parentFile
        d
    }

    private val screens = File(repoRoot, "app/src/main/java/com/mobilegh/ui/screens")

    private fun source(name: String): String = File(screens, name).readText()

    /** 上传页必须挂原生选择器契约，而不是自建浏览界面 */
    @Test fun uploadScreenUsesNativePickers() {
        val s = source("FileUploadScreen.kt")
        assertTrue("缺少多选文件契约 OpenMultipleDocuments", s.contains("ActivityResultContracts.OpenMultipleDocuments"))
        assertTrue("缺少选文件夹契约 OpenDocumentTree", s.contains("ActivityResultContracts.OpenDocumentTree"))
        assertFalse("上传页不应再承载自建文件浏览器入口", s.contains("FileBrowserScreen"))
    }

    /** 不允许把「授权目录后应用内浏览」的实现重新引回主源码 */
    @Test fun noInAppBrowserScreenInMainSources() {
        val offenders = screens.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "kt" }
            .filter { "FileBrowserScreen" in it.readText() }
            .map { it.name }
        assertTrue("这些文件里出现了自建文件浏览器：$offenders", offenders.isEmpty())
    }

    /** 文件夹仍然要能带上层级：由 UploadSource 递归展开，不靠用户改选多次 */
    @Test fun folderExpansionStillLivesInDataSource() {
        val s = File(repoRoot, "app/src/main/java/com/mobilegh/data/UploadSource.kt").readText()
        assertTrue("缺少递归展开", s.contains("fun readFolder"))
        assertTrue("缺少多选文件读取", s.contains("fun readFiles"))
    }
}
