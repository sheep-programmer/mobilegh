package com.mobilegh.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationPolishTest {
    private fun zh(source: String, translated: String) = polishTranslation(source, translated, "en", "zh")

    @Test fun pullRequestMistranslationsBecomeTheStandardTerm() {
        val source = "It lets you browse repositories, review pull requests, and manage issues."
        // 以下三种都是 ML Kit 实际给出过的错误译法
        assertEquals("查看拉取请求，并管理问题", zh(source, "查看拉请求，并管理问题"))
        assertEquals("查看拉取请求，并管理问题", zh(source, "查看拉拔请求，并管理问题"))
        assertEquals("查看拉取请求，并管理问题", zh(source, "查看提取请求，并管理问题"))
    }

    @Test fun correctTermIsLeftAloneAndTheFixIsIdempotent() {
        val source = "Review pull requests."
        assertEquals("查看拉取请求。", zh(source, "查看拉取请求。"))
        assertEquals("查看拉取请求。", zh(source, zh(source, "查看拉请求。")))
    }

    @Test fun termsAreOnlyFixedWhenTheSourceActuallyMentionsThem() {
        // 「提取请求」在别的语境里是正常中文，源文没有 pull request 就不能改
        assertEquals("请提取请求中的数据", zh("Extract the data from the request.", "请提取请求中的数据"))
        assertEquals("存储库的设置", zh("The storage settings.", "存储库的设置"))
    }

    @Test fun repositoryBecomesTheAppsOwnTerm() {
        assertEquals("浏览仓库", zh("Browse repositories", "浏览存储库"))
        assertEquals("浏览仓库", zh("Browse the repository", "浏览储存库"))
    }

    @Test fun identifiersKeepTheCaseTheyHadInTheSource() {
        assertEquals("MobileGH是GitHub的客户端。", zh("MobileGH is a client for GitHub.", "MobileGh是Github的客户端。"))
        // 只含普通大写开头的单词不算标识符，不动
        assertEquals("你好 World", zh("Hello World", "你好 World"))
        // 不把单词内部的片段改掉
        assertEquals("OpenMobileGhX", zh("MobileGH works.", "OpenMobileGhX"))
    }

    @Test fun placeholderMarkersAreRestoredToTheirOriginalCase() {
        assertEquals("运行[XQ0X]下载", zh("Run [XQ0X] to download", "运行[xq0x]下载"))
    }

    @Test fun glossaryOnlyAppliesToEnglishToChinese() {
        assertEquals("拉请求", polishTranslation("pull request", "拉请求", "en", "ja"))
        assertEquals("pull request", polishTranslation("拉取请求", "pull request", "zh", "en"))
    }
}
