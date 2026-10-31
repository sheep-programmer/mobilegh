package com.mobilegh.data

import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf

/**
 * 成就的中文显示。GitHub 的成就名称、说明和解锁历史只返回英文，即使请求带中文语言也一样，
 * 所以在本地翻译：已知成就按 slug 查表，常见的说明与历史句式按规则替换；查不到的保留 GitHub 的原文。
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
    )

    /** 成就名称：已知的 slug 用中文，其余保留 GitHub 给出的英文名。 */
    fun name(slug: String, fallback: String): String = names[slug]?.invoke() ?: fallback

    private val pullShark = Regex("""^(@\S+) opened pull requests that have been merged\.?$""")
    private val tierUnlocked = Regex("""\b(Bronze|Silver|Gold|Platinum|Diamond) unlocked\b""")
    private val firstUnlocked = Regex("""First unlocked (.+)""")
    private val nthPullMerged = Regex("""(\d+)(st|nd|rd|th) pull request merged""")

    /** 成就说明：只翻译已知的整句，其余保留原文。 */
    fun description(text: String): String {
        val m = pullShark.matchEntire(text.trim()) ?: return text
        return trf("{0} 的拉取请求已被合并。", m.groupValues[1])
    }

    /** 解锁历史的一行：替换等级、首次解锁时间和第 N 个合并请求，其余（仓库名、日期等）保留。 */
    fun historyLine(line: String): String {
        var out = line.replace(tierUnlocked) { m -> trf("{0}已解锁", tierLabel(m.groupValues[1])) }
        out = out.replace(firstUnlocked) { m -> trf("首次解锁 {0}", m.groupValues[1]) }
        out = out.replace(nthPullMerged) { m -> trf("第 {0} 个已合并的拉取请求", m.groupValues[1]) }
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
