package com.mobilegh.ui.components

/**
 * 端侧小模型的译后修正。只做两类确定、可逆、不会越界的事：
 *  - 英译中时，把模型在 GitHub 语境里稳定出错的术语改成 App 自己的叫法（源文里确实出现了对应英文词才改）。
 *  - 源文里写法特殊的标识符（MobileGH、GitHub、iOS……）被模型改了大小写时，还原成源文写法。
 */
internal fun polishTranslation(source: String, translated: String, sourceLanguage: String, targetLanguage: String): String {
    var result = restoreIdentifierCase(source, translated)
    if (sourceLanguage == "en" && targetLanguage == "zh") {
        for (rule in glossary) {
            if (rule.trigger.containsMatchIn(source)) result = rule.wrong.replace(result, rule.right)
        }
    }
    return result
}

private class GlossaryRule(val trigger: Regex, val wrong: Regex, val right: String)

private val glossary = listOf(
    // pull request：ML Kit 常译成「拉请求」「拉拔请求」「提取请求」，GitHub 中文界面叫「拉取请求」
    GlossaryRule(Regex("(?i)pull[ -]?requests?"), Regex("拉拔请求|拉请求|提取请求|拉动请求"), "拉取请求"),
    // repository：模型译成「存储库」，App 里统一叫「仓库」
    GlossaryRule(Regex("(?i)repositor(?:y|ies)"), Regex("存储库|储存库"), "仓库"),
)

/** 至少 3 个字母，且带内部大写或全大写：MobileGH、GitHub、iOS、README。只有首字母大写的普通单词不算。 */
private val identifier = Regex("""(?<![A-Za-z0-9])(?:[A-Za-z][a-z0-9]*[A-Z][A-Za-z0-9]*|[A-Z][A-Z0-9]{2,})(?![A-Za-z0-9])""")

private fun restoreIdentifierCase(source: String, translated: String): String {
    var result = translated
    for (token in identifier.findAll(source).map { it.value }.distinct()) {
        val found = Regex("(?<![A-Za-z0-9])" + Regex.escape(token) + "(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)
        result = found.replace(result, Regex.escapeReplacement(token))
    }
    return result
}
