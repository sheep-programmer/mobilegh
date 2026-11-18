package com.mobilegh.data

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilegh.NavVm
import com.mobilegh.nav.Tab
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ArtifactInstallerDeviceTest {
    @Test fun selectedBuildArtifactIsExtractedAndRecognizedByAndroid() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val artifact = File.createTempFile("test-artifact", ".zip", context.cacheDir)
        try {
            ZipOutputStream(artifact.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("output/mobilegh.apk"))
                File(context.applicationInfo.sourceDir).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            val task = DownloadTask(url = "https://example.org/artifact", filename = "sample.zip", filePath = artifact.path,
                state = DownloadState.Complete, actionsArchive = true)
            val entry = Downloads.artifactApks(task).single()
            assertEquals("output/mobilegh.apk", entry.path)
            val prepared = Downloads.prepareArtifactApk(task, entry)
            val path = checkNotNull(prepared.filePath)
            try {
                assertTrue(File(path).isFile)
                assertEquals(context.packageName, context.packageManager.getPackageArchiveInfo(path, 0)?.packageName)
            } finally {
                File(path).delete()
            }
        } finally {
            artifact.delete()
        }
    }

    @Test fun coldLaunchUsesTheConfiguredTab() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val previous = Session.startupTab
            try {
                Session.chooseStartupTab(Tab.Star)
                assertEquals(Tab.Star, NavVm(SavedStateHandle()).navFor(Session.generation).tab)
            } finally {
                Session.chooseStartupTab(previous)
            }
        }
    }

    @Test fun downloadsRejectMismatchedChecksumsBeforePublishing() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    runCatching {
                        server.accept().use { client ->
                            client.soTimeout = 3_000
                            val input = client.getInputStream().bufferedReader()
                            val method = input.readLine().substringBefore(' ')
                            while (!input.readLine().isNullOrEmpty()) Unit
                            client.getOutputStream().apply {
                                write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: 3\r\nConnection: close\r\n\r\n".toByteArray())
                                if (method != "HEAD") write("abc".toByteArray())
                            }
                        }
                    }
                }
            }
            val url = "http://127.0.0.1:${server.localPort}/verified-fixture.bin"
            val good = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
            try {
                suspend fun download(checksum: String): DownloadTask {
                    instrumentation.runOnMainSync { Downloads.start(url, "mobilegh-checksum-test.bin", false, digest = "sha256:$checksum") }
                    return withTimeout(20_000) {
                        var completed: DownloadTask? = null
                        while (completed == null) {
                            instrumentation.runOnMainSync { completed = Downloads.tasks.firstOrNull { it.url == url && it.expectedSha256 == checksum && !it.active } }
                            if (completed == null) delay(100)
                        }
                        checkNotNull(completed)
                    }
                }
                val valid = download(good)
                assertEquals(DownloadState.Complete, valid.state)
                assertTrue(Downloads.fileExists(valid))
                val invalid = download("0".repeat(64))
                assertEquals(DownloadState.Failed, invalid.state)
                assertTrue(invalid.message.orEmpty().contains("SHA-256"))
                assertNull(invalid.filePath)
                assertNull(invalid.contentUri)
                assertFalse(Downloads.fileExists(invalid))
            } finally {
                withContext(Dispatchers.Main) {
                    Downloads.tasks.filter { it.url == url }.forEach { task ->
                        if (task.active) Downloads.cancel(task)
                        if (task.state == DownloadState.Complete) Downloads.deleteFile(context, task)
                        Downloads.forget(context, task)
                    }
                }
            }
        }
    }
}
