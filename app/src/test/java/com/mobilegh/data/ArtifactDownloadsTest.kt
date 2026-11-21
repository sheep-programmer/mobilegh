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

    @Test fun detectsCommonPlatformSuffixesAndBuildNames() {
        val cases = mapOf(
            ReleasePlatform.Android to listOf("app.APKS", "app.xapk", "tool-android34-arm64.zip"),
            ReleasePlatform.Windows to listOf("app.msixbundle", "tool-windows11-x64.zip", "tool-x86_64-w64-mingw32.7z", "tool-win-arm64.zip"),
            ReleasePlatform.Mac to listOf("app.PKG", "tool-macOS14-arm64.zip", "tool-apple-darwin.tar.xz", "Studio-Ubuntu-Theme.dmg"),
            ReleasePlatform.Linux to listOf("app.flatpak", "app.snap", "tool-linux-musl.tgz", "tool_ubuntu22.04_amd64.tar.zst"),
        )
        for ((platform, names) in cases) {
            for (name in names) {
                assertEquals(name, listOf(platform), ReleasePlatform.entries.filter { it != ReleasePlatform.All && it.matchesAsset(name) })
            }
        }
    }

    @Test fun specificTargetsWinOverGenericMarkersAndMetadataStaysInAll() {
        assertTrue(ReleasePlatform.Android.matchesAsset("tool-aarch64-linux-android.tar.gz"))
        assertFalse(ReleasePlatform.Linux.matchesAsset("tool-aarch64-linux-android.tar.gz"))
        assertTrue(ReleasePlatform.Windows.matchesAsset("tool-win64-android-tools.exe"))
        assertFalse(ReleasePlatform.Android.matchesAsset("tool-win64-android-tools.exe"))
        for (name in listOf("tool-aarch64.zip", "game-universal.zip", "source-code.tar.gz", "tool-win64.zip.sha256", "tool-linux.deb.sig", "build-android.yml", "tool-darwin.dmg.blockmap")) {
            assertTrue(ReleasePlatform.All.matchesAsset(name))
            assertFalse(name, ReleasePlatform.entries.any { it != ReleasePlatform.All && it.matchesAsset(name) })
        }
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
