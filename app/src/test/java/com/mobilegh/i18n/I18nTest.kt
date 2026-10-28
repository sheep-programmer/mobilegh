package com.mobilegh.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class I18nTest {
    @Test fun chineseModeReturnsTheSourceTextUnchanged() {
        assertEquals("设置", tr("设置"))
        assertEquals("共 3 个文件", trf("共 {0} 个文件", 3))
    }

    @Test fun languageChoiceResolvesToEnglishOnlyWhenNeeded() {
        assertFalse(I18n.resolve(AppLanguage.Chinese, "en"))
        assertTrue(I18n.resolve(AppLanguage.English, "zh"))
        assertFalse(I18n.resolve(AppLanguage.System, "zh"))
        assertFalse(I18n.resolve(AppLanguage.System, "ZH"))
        assertTrue(I18n.resolve(AppLanguage.System, "ja"))
        assertEquals(AppLanguage.Chinese, AppLanguage.of(null))
        assertEquals(AppLanguage.English, AppLanguage.of("en"))
        assertEquals(AppLanguage.System, AppLanguage.of("system"))
    }

    @Test fun dictionaryParsesEscapesCommentsAndBlankLines() {
        val text = "## comment\n\n#{0} 号\t#{0}\n设置\tSettings\n两行\\n文字\tTwo\\nlines\n反斜杠\\\\\tBackslash\\\\\n无译文\n"
        val map = I18n.parseDictionary(text)
        assertEquals("Settings", map["设置"])
        assertEquals("#{0}", map["#{0} 号"])
        assertEquals("Two\nlines", map["两行\n文字"])
        assertEquals("Backslash\\", map["反斜杠\\"])
        assertFalse(map.containsKey("无译文"))
    }

    @Test fun escapeAndUnescapeRoundTrip() {
        val raw = "a\\b\nc\td\re"
        assertEquals(raw, I18n.unescape(I18n.escape(raw)))
    }

    @Test fun placeholdersAreFilledInAnyOrderAndUnknownOnesStay() {
        assertEquals("b then a", I18n.fill("{1} then {0}", arrayOf("a", "b")))
        assertEquals("{2} {x} {}", I18n.fill("{2} {x} {}", arrayOf("a")))
        assertEquals("keep {0}", I18n.fill("keep {0}", emptyArray()))
        assertEquals("n=null", I18n.fill("n={0}", arrayOf(null)))
        assertEquals("a a", I18n.fill("{0} {0}", arrayOf("a")))
    }
}

/** 检查随应用发布的英文词典本身：占位符齐全、没有空译文、键不重复 */
class EnglishDictionaryTest {
    private val file: java.io.File by lazy {
        var d = java.io.File(System.getProperty("user.dir")).absoluteFile
        while (!java.io.File(d, "app/src/main/assets/i18n/en.tsv").isFile && d.parentFile != null) d = d.parentFile
        java.io.File(d, "app/src/main/assets/i18n/en.tsv")
    }

    private val entries: List<Pair<String, String>> by lazy {
        file.readLines(Charsets.UTF_8).filter { it.isNotEmpty() && !it.startsWith("##") && '\t' in it }.map {
            I18n.unescape(it.substringBefore('\t')) to I18n.unescape(it.substringAfter('\t'))
        }
    }

    @Test fun dictionaryIsNotEmptyAndHasNoDuplicateKeys() {
        assertTrue("词典条目太少：${entries.size}", entries.size > 2000)
        val keys = entries.map { it.first }
        assertEquals("重复的键：" + keys.groupBy { it }.filter { it.value.size > 1 }.keys, keys.size, keys.toSet().size)
    }

    @Test fun everyTranslationKeepsThePlaceholdersAndFormatSpecifiers() {
        val placeholder = Regex("""\{\d+\}""")
        val format = Regex("""%[0-9.]*[a-zA-Z]""")
        entries.forEach { (zh, en) ->
            assertTrue("译文为空：$zh", en.isNotBlank())
            assertEquals("占位符不一致：$zh -> $en", placeholder.findAll(zh).map { it.value }.sorted().toList(), placeholder.findAll(en).map { it.value }.sorted().toList())
            assertEquals("格式符不一致：$zh -> $en", format.findAll(zh).map { it.value }.toList(), format.findAll(en).map { it.value }.toList())
        }
    }

    @Test fun contextEntriesHaveABaseTranslationOrStandAlone() {
        val keys = entries.map { it.first }.toSet()
        entries.filter { '|' in it.first && it.first.substringBefore('|').isNotEmpty() }.forEach { (key, _) ->
            // 「中文|语境」的中文部分必须是真实存在的普通条目，否则语境条目没有退路也没有对应源码
            assertTrue("语境条目缺少基础条目：$key", key.substringBefore('|') in keys)
        }
    }
}
