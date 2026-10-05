package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebParsingTest {
    private fun badge(slug: String, tier: String = "default", label: String = "", name: String = "Pull Shark") = """
        <a href='/mona?tab=achievements&amp;achievement=$slug' class='position-relative'>
          <img width='64' alt='Achievement: $name' class='achievement-badge-sidebar'
            src='https://github.githubassets.com/assets/$slug-$tier-abc123.png'>
          $label
        </a>
    """.trimIndent()

    @Test fun currentSidebarMarkupDeduplicatesAndPreservesTheHighestTierMultiplier() {
        val bronze = badge("pull-shark", "bronze", "<span class='achievement-tier-label'>x2</span>")
        val gold = badge("pull-shark", "gold", "<div><span class='Label achievement-tier-label'><span>×4</span></span></div>")
        val parsed = Web.parseAchievements(bronze + gold + bronze)
        assertEquals(1, parsed.size)
        assertEquals("pull-shark", parsed.single().slug)
        assertEquals("gold", parsed.single().tier)
        assertEquals(4, parsed.single().count)
    }

    @Test fun tierAndMultiplierAreIndependentAndDefaultIsNotAnEventCount() {
        val parsed = Web.parseAchievements(badge("quickdraw") + badge("starstruck", "silver", "<span class='achievement-tier-label'>x3</span>"))
        assertEquals(listOf("default", "silver"), parsed.map { it.tier })
        assertEquals(listOf(1, 3), parsed.map { it.count })
    }

    @Test fun attributesCanChangeOrderQuoteStyleCaseAndContainGreaterThanSigns() {
        val html = """<A HREF="/mona?achievement=pair-extraordinaire&amp;tab=achievements">
            <IMG ALT="Achievement: Pair > Shark &#x26; Friends" DATA-HOVERCARD-TYPE=achievement
              SRC="//github.githubassets.com/assets/pair-extraordinaire-silver-xyz.png">
            <span class="achievement-tier-label">x 3</span></A>"""
        val parsed = Web.parseAchievements(html).single()
        assertEquals("Pair > Shark & Friends", parsed.name)
        assertEquals("silver", parsed.tier)
        assertEquals(3, parsed.count)
        assertTrue(parsed.imageUrl.startsWith("https://github.githubassets.com/"))
    }

    @Test fun scriptCommentAndUnsafeOrNonBadgeImagesAreIgnored() {
        val valid = badge("pull-shark")
        val html = "<script>$valid</script><!-- $valid -->" +
            valid.replace("/mona?", "https://other.example/mona?") +
            valid.replace("https://github.githubassets.com", "https://other.example") +
            valid.replace("https://github.githubassets.com", "http://github.githubassets.com") +
            valid.replace("achievement-badge-sidebar", "user-content-image") +
            valid.replace("achievement=pull-shark", "achievement=../bad")
        assertTrue(Web.parseAchievements(html).isEmpty())
    }

    @Test fun invalidMultiplierFallsBackAndBadgeCollectionIsBounded() {
        assertEquals(1, Web.parseAchievements(badge("pull-shark", label = "<span class='achievement-tier-label'>not a number</span>")).single().count)
        val html = (1..80).joinToString("") { badge("badge-$it") }
        assertEquals(32, Web.parseAchievements(html).size)
    }

    @Test fun realListCardsReadHeadingsDescriptionsAndCountsWithoutLayoutDependence() {
        val card = """<a class="Box-row" href="/stars/Mona/lists/favorites">
          <div class="d-flex"><h3><span>⭐</span> Favorites &amp; tools</h3>
            <div class="color-fg-muted">1,234 repositories</div></div>
          <div><p class="color-fg-muted">Utilities &#38; libraries</p></div>
        </a>"""
        val parsed = Web.parseLists(card + card, "mona").single()
        assertEquals("⭐ Favorites & tools", parsed.name)
        assertEquals(1234, parsed.count)
        assertEquals("Utilities & libraries", parsed.description)
        assertEquals("https://github.com/stars/mona/lists/favorites", parsed.url)
    }

    @Test fun listLinksWithoutHeadingsUnicodeSlugsAndSingularCountsWork() {
        val parsed = Web.parseLists("""<a href='/stars/mona/lists/%E5%AD%A6%E4%B9%A0'>学习 <span>1 repository</span></a>""", "mona").single()
        assertEquals("学习", parsed.name)
        assertEquals("学习", parsed.slug)
        assertEquals(1, parsed.count)
        assertTrue(parsed.url.endsWith("/%E5%AD%A6%E4%B9%A0"))
    }

    @Test fun digitsInTheListTitleAreNotMistakenForItsRepositoryCount() {
        val list = Web.parseLists("""<a href='/stars/mona/lists/2026'><h3>Reading 2026</h3><div>2 repositories</div></a>""", "mona").single()
        assertEquals("Reading 2026", list.name)
        assertEquals(2, list.count)
    }

    @Test fun crossAccountExternalAndTraversalListLinksAreRejected() {
        val html = """
          <a href='/stars/other/lists/tools'><h3>Wrong account</h3></a>
          <a href='https://evil.example/stars/mona/lists/tools'>External</a>
          <a href='/stars/mona/lists/tools/extra'>Extra path</a>
          <a href='/stars/mona/lists/a%2Fb'>Encoded slash</a>
          <a href='/stars/mona/lists/..'>Traversal</a>
        """
        assertTrue(Web.parseLists(html, "mona").isEmpty())
        assertTrue(Web.parseLists(html, "mona/other").isEmpty())
    }

    @Test fun entityDecodingIsSinglePassAndRejectsInvalidUnicode() {
        assertEquals("& < > \" ' 😀 A", Web.unescape("&amp; &lt; &gt; &quot; &#39; &#x1F600; &#65;"))
        assertEquals("&#39;", Web.unescape("&amp;#39;"))
        assertEquals("&#xD800; &#99999999; &unknown;", Web.unescape("&#xD800; &#99999999; &unknown;"))
        assertEquals("&amplitude", Web.unescape("&amplitude"))
    }

    @Test fun hiddenAchievementsOrChangedMarkupReturnsAnEmptyCollection() {
        assertTrue(Web.parseAchievements("<main>Private profile</main>").isEmpty())
        assertTrue(Web.parseLists("<main>No lists</main>", "mona").isEmpty())
    }
}
