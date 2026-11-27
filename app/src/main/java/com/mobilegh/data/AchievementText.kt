package com.mobilegh.data

import com.mobilegh.i18n.I18n
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf

/**
 * 成就的本地化显示。GitHub 的成就名称、说明和解锁历史只返回英文，即使请求带中文语言也一样，
 * 所以在中文模式下进行本地翻译：已知成就按 slug 查表，说明与历史句式按规则替换，其余保留原文。
 * 英文模式下直接保留 GitHub 返回的规范英文文本。
 */
object AchievementText {
    private val names: Map<String, () -> String> = mapOf(
        "pull-shark" to { tr("拉取鲨鱼") },
        "quickdraw" to { tr("快枪手") },
        "galaxy-brain" to { tr("银河大脑") },
        "starstruck" to { tr("星光熠熠") },
        "pair-extraordinaire" to { tr("结对高手") },
        "public-sponsor" to { tr("公开赞助者") },
        "arctic-code-vault-contributor" to { tr("北极代码库贡献者") },
        "heart-on-your-sleeve" to { tr("真情流露") },
        "open-sourcerer" to { tr("开源达人") },
        "developer-program-member" to { tr("开发者计划成员") },
        "yolo" to { tr("大胆合并 (YOLO)") },
        "mars-2020-contributor" to { tr("火星 2020 贡献者") },
    )

    /** 成就名称：已知的 slug 用对应中文，英文模式及未知徽章保留 GitHub 英文名。 */
    fun name(slug: String, fallback: String): String {
        if (I18n.isEnglish) return fallback
        return names[slug]?.invoke() ?: fallback
    }

    private data class DescriptionRule(val regex: Regex, val format: (MatchResult) -> String)

    private val descriptionRules = listOf(
        DescriptionRule(Regex("""^(@\S+) opened (?:a )?pull requests? that (?:has|have) been merged\s*\.?$""")) {
            trf("{0} 的拉取请求已被合并。", it.groupValues[1])
        },
        DescriptionRule(Regex("""^(@\S+) created (?:a )?repositor(?:y|ies) that (?:has|have) many stars\s*\.?$""")) {
            "${it.groupValues[1]} 创建了获得大量 Star 的仓库。"
        },
        DescriptionRule(Regex("""^(@\S+) closed (?:an|multiple) issues? or pull requests? within 5 minutes of opening\s*\.?$""")) {
            "${it.groupValues[1]} 在创建后 5 分钟内关闭了 Issue 或拉取请求。"
        },
        DescriptionRule(Regex("""^(@\S+) coauthored (?:a )?commits? on (?:a )?merged pull requests?\s*\.?$""")) {
            "${it.groupValues[1]} 在已合并的拉取请求中参与了共同提交。"
        },
        DescriptionRule(Regex("""^(@\S+) merged (?:a )?pull requests? without (?:a )?review\s*\.?$""")) {
            "${it.groupValues[1]} 在未经代码审查的情况下合并了拉取请求。"
        },
        DescriptionRule(Regex("""^(@\S+) answered discussions\s*\.?$""")) {
            "${it.groupValues[1]} 在讨论中提供了被采纳的回答。"
        },
        DescriptionRule(Regex("""^(@\S+) had (?:an )?accepted answers? on discussions?\s*\.?$""")) {
            "${it.groupValues[1]} 在讨论中的回答被采纳。"
        },
        DescriptionRule(Regex("""^(@\S+) has sponsored (\d+) organizations? or users?\s*\.?$""")) {
            "${it.groupValues[1]} 已赞助 ${it.groupValues[2]} 位用户或组织。"
        },
        DescriptionRule(Regex("""^(@\S+) sponsored (?:an )?open source (?:contributor|work).*$""")) {
            "${it.groupValues[1]} 赞助了开源贡献。"
        },
        DescriptionRule(Regex("""^(@\S+) contributed code to (?:several )?repositories in the 2020 GitHub Archive Program\s*\.?$""")) {
            "${it.groupValues[1]} 为 2020 GitHub 代码存档计划贡献了代码。"
        },
        DescriptionRule(Regex("""^(@\S+) contributed code to .*Mars 2020.*$""")) {
            "${it.groupValues[1]} 为火星 2020 无人直升机任务用到的仓库贡献了代码。"
        },
        DescriptionRule(Regex("""^(@\S+) reacted to something on GitHub with a heart emoji\s*\.?$""")) {
            "${it.groupValues[1]} 在 GitHub 上使用爱心表情做出了回应。"
        },
        DescriptionRule(Regex("""^(@\S+) had commits merged into (?:a )?public repositor(?:y|ies)\s*\.?$""")) {
            "${it.groupValues[1]} 向公开仓库合并了提交。"
        },
    )

    /** 成就说明：在中文模式下匹配并翻译常见 GitHub 成就说明，其余与英文模式保留原文。 */
    fun description(text: String): String {
        if (I18n.isEnglish) return text
        val trimmed = text.trim()
        descriptionRules.forEach { rule ->
            rule.regex.matchEntire(trimmed)?.let { return rule.format(it) }
        }
        return text
    }

    private val multiTierUnlocked = Regex("""\b((?:(?:Bronze|Silver|Gold|Platinum|Diamond)(?:\s*(?:,|and)\s*)?)+)\s+unlocked\b""")
    private val tierWord = Regex("""\b(Bronze|Silver|Gold|Platinum|Diamond)\b""")
    private val percentUnlocked = Regex("""\b(\d+)%\s+unlocked\b""")
    private val firstUnlocked = Regex("""First unlocked (.+)""")
    private val unlockedAt = Regex("""\bUnlocked (.+)""")
    private val nthPullMerged = Regex("""(\d+)(?:st|nd|rd|th) pull request merged""")
    private val nthAcceptedAnswer = Regex("""(\d+)(?:st|nd|rd|th) accepted answer""")
    private val nthSponsorship = Regex("""(\d+)(?:st|nd|rd|th) sponsorship""")
    private val starMilestone = Regex("""(?:⭐️|⭐)?\s*(\d+)\s+stars?\b""", RegexOption.IGNORE_CASE)
    private val coauthoredWith = Regex("""Coauthored with (@\S+)""")
    private val coauthoredUnknown = Regex("""Coauthored with an unknown user""")
    private val mergedWithoutReview = Regex("""Merged (?:a pull request )?without (?:a )?review""", RegexOption.IGNORE_CASE)
    private val closedQuickly = Regex("""Closed within ([^·]+)""")
    private val archivedRepos = Regex("""these repositories,\s*and more,\s*were archived""", RegexOption.IGNORE_CASE)
    private val inaccessibleRepo = Regex("""^inaccessible$""")

    /** 解锁历史的一行：替换等级、解锁比例、时间与事件描述，仓库名与具体日期原样保留。 */
    fun historyLine(line: String): String {
        if (I18n.isEnglish) return line
        var out = line
        out = out.replace(multiTierUnlocked) { m ->
            val tiers = tierWord.findAll(m.groupValues[1]).map { tierLabel(it.groupValues[1]) }.toList()
            if (tiers.size <= 1) trf("{0}已解锁", tiers.firstOrNull() ?: m.groupValues[1])
            else "${tiers.joinToString("、")}已解锁"
        }
        out = out.replace(percentUnlocked) { m -> "${m.groupValues[1]}% 已解锁" }
        out = out.replace(firstUnlocked) { m -> trf("首次解锁 {0}", m.groupValues[1]) }
        out = out.replace(unlockedAt) { m -> "解锁于 ${m.groupValues[1]}" }
        out = out.replace(nthPullMerged) { m -> trf("第 {0} 个已合并的拉取请求", m.groupValues[1]) }
        out = out.replace(nthAcceptedAnswer) { m -> "第 ${m.groupValues[1]} 个被采纳的回答" }
        out = out.replace(nthSponsorship) { m -> "第 ${m.groupValues[1]} 次赞助" }
        out = out.replace(starMilestone) { m -> "⭐️ ${m.groupValues[1]} Star" }
        out = out.replace(coauthoredUnknown, "与未知用户共同创作")
        out = out.replace(coauthoredWith) { m -> "与 ${m.groupValues[1]} 共同创作" }
        out = out.replace(mergedWithoutReview, "未经审查直接合并")
        out = out.replace(closedQuickly) { m -> "在 ${m.groupValues[1].trim()} 内关闭" }
        out = out.replace(archivedRepos, "以上仓库及更多项目已被永久存档")
        out = out.split(" · ").joinToString(" · ") { part ->
            val t = part.trim()
            if (inaccessibleRepo.matches(t)) "不可访问的仓库" else part
        }
        return out
    }

    private fun tierLabel(english: String): String = when (english) {
        "Bronze" -> tr("铜级")
        "Silver" -> tr("银级")
        "Gold" -> tr("金级")
        "Platinum" -> tr("白金级")
        "Diamond" -> tr("钻石级")
        else -> english
    }
}
