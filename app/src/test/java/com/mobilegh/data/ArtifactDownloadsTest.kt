package com.mobilegh.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArtifactDownloadsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun platformFilteringKeepsOnlyMatchingInstallers() {
        assertTrue(ReleasePlatform.Android.matchesAsset("App-ARM64.APK"))
        assertTrue(ReleasePlatform.Windows.matchesAsset("app-win64.zip"))
        assertTrue(ReleasePlatform.Mac.matchesAsset("app-darwin-arm64.tar.gz"))
        assertTrue(ReleasePlatform.Linux.matchesAsset("app-x86.AppImage"))
        assertFalse(ReleasePlatform.Windows.matchesAsset("app-darwin-arm64.tar.gz"))
        assertFalse(ReleasePlatform.Android.matchesAsset("source-code.zip"))
        assertTrue(ReleasePlatform.All.matchesAsset("source-code.zip"))
    }

    @Test fun checksumMatchesBytesAndRejectsMalformedDigests() = runBlocking {
        val file = tmp.newFile().apply { writeText("abc") }
        val expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertEquals(expected, trustedSha256("SHA256:${expected.uppercase()}"))
        assertNull(trustedSha256("sha256:incomplete"))
        assertTrue(verifyDownload(file, expected))
        file.appendText("d")
        assertFalse(verifyDownload(file, expected))
    }

    @Test fun zipListsNestedApksAndExtractsOnlyTheSelectedEntry() = runBlocking {
        val zip = tmp.newFile()
        ZipOutputStream(zip.outputStream()).use { output ->
            for (name in listOf("build/beta.APK", "build/stable.apk", "README.txt", "../outside.apk")) {
                output.putNextEntry(ZipEntry(name))
                output.write(name.toByteArray())
                output.closeEntry()
            }
        }
        val entries = ArtifactApks.list(zip)
        assertEquals(listOf("build/beta.APK", "build/stable.apk"), entries.map { it.path })
        val result = tmp.newFile("selected.apk")
        ArtifactApks.extract(zip, entries.last(), result)
        assertEquals("build/stable.apk", result.readText())
        assertFalse(tmp.root.resolve("outside.apk").exists())
    }
}
