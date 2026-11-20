package com.mobilegh.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mobilegh.data.GitHub
import com.mobilegh.data.Net
import com.mobilegh.data.Session
import com.mobilegh.data.User
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.Navigator
import com.mobilegh.ui.components.HtmlParser
import com.mobilegh.ui.components.HtmlView
import com.mobilegh.ui.components.Images
import com.mobilegh.ui.theme.MobileGhTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in, read-only regression for the exact repository and badge reported by the user. */
@RunWith(AndroidJUnit4::class)
class ReadmeImageLiveTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun reportedLogoAndAtomGitBadgeLoadThroughTheAppPipeline() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("imagesLive") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val credential = File(context.cacheDir, "images-live-credential")
        val token = if (credential.isFile) try { credential.readText().trim() } finally { credential.delete() } else null
        val previousAccount = Session.login
        val previousProxy = Net.apiProxy
        val fixtureAccount = "mobilegh-image-live-fixture"
        val nav = Navigator()
        try {
            compose.runOnUiThread {
                Net.apiProxy = ""
                if (!token.isNullOrBlank()) Session.signIn(token, User(login = fixtureAccount))
            }
            val owner = "hexisi-touko"
            val name = "yangtze-ai-gateway-web"
            val repository = runBlocking { GitHub.repo(owner, name, force = true) }
            val base = "https://github.com/$owner/$name/blob/${repository.defaultBranch}/README.md"
            val html = requireNotNull(runBlocking { GitHub.readme(owner, name, repository.defaultBranch, force = true) })
            val urls = HtmlParser.extractImageUrls(HtmlParser.parse(html, base).blocks, limit = 1000)
            val logo = urls.first { it.endsWith("/web/public/logo.png") }
            assertTrue("Repository root must be retained", logo.startsWith("https://github.com/$owner/$name/blob/"))
            assertNotNull("Reported logo must fetch and decode", runBlocking { Images.load(logo, 512, force = true) })
            val badge = "https://atomgit.com/QuantumNous/new-api/star/badge.svg"
            assertNotNull("Live AtomGit badge must fetch and decode", runBlocking { Images.load(badge, 512, force = true) })
            val camo = urls.first { it.startsWith("https://camo.githubusercontent.com/") && it.endsWith(badge.toByteArray().joinToString("") { b -> "%02x".format(b) }) }
            assertNotNull("GitHub Camo badge must fetch and decode", runBlocking { Images.load(camo, 512, force = true) })
            compose.setContent {
                CompositionLocalProvider(LocalNav provides nav) {
                    MobileGhTheme {
                        Surface {
                            HtmlView("<h2>README image regression</h2><p><img src=\"$logo\" alt=\"new-api\" width=\"128\"></p><p><img src=\"$camo\" alt=\"AtomGit G-Star\" width=\"132\" height=\"20\"></p>", baseUrl = base)
                        }
                    }
                }
            }
            compose.waitUntil(15_000) {
                compose.onAllNodesWithContentDescription("new-api").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithContentDescription("AtomGit G-Star").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                File(context.cacheDir, "readme-images-live.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        } finally {
            credential.delete()
            compose.runOnUiThread {
                nav.dispose()
                Net.apiProxy = previousProxy
                if (Session.login == fixtureAccount) {
                    Session.signOut()
                    previousAccount?.let(Session::switchTo)
                }
            }
        }
    }
}
