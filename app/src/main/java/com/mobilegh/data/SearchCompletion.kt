package com.mobilegh.data

internal data class SearchCompletion(val start: Int, val end: Int, val qualifier: String, val values: List<String>) {
    fun apply(text: String, value: String): Pair<String, Int> {
        val formatted = if (value.any(Char::isWhitespace)) "\"$value\"" else value
        val prefix = text.substring(0, start) + formatted
        val suffix = text.substring(end)
        val separator = if (suffix.firstOrNull()?.isWhitespace() == true) "" else " "
        return (prefix + separator + suffix) to (prefix.length + 1)
    }
}

private val queryToken = Regex("""(?:"[^"]*(?:"|$)|[^\s"])+""")

internal fun searchCompletion(text: String, cursor: Int, type: Int, history: HistoryData, login: String?): SearchCompletion? {
    val token = queryToken.findAll(text).firstOrNull { cursor > it.range.first && cursor <= it.range.last + 1 } ?: return null
    val colon = token.value.indexOf(':')
    if (colon < 1) return null
    val start = token.range.first + colon + 1
    if (cursor < start) return null
    val qualifier = token.value.take(colon).removePrefix("-").lowercase()
    val threads = type == SearchType.ISSUE || type == SearchType.PR
    val values = when (qualifier) {
        "is", "state" -> if (threads) {
            if (type == SearchType.PR && qualifier == "is") listOf("open", "closed", "merged", "unmerged", "draft") else listOf("open", "closed")
        } else emptyList()
        "repo" -> if (threads || type == SearchType.CODE) history.repositories.map(RecentRepository::fullName) else emptyList()
        "label" -> if (threads) {
            val used = history.searches.asSequence().filter { it.type == type }.flatMap { queryToken.findAll(it.query) }
                .mapNotNull { match -> match.value.removePrefix("-").takeIf { it.startsWith("label:", true) }?.substringAfter(':')?.trim('"') }
                .filter { it.isNotBlank() && '"' !in it && ',' !in it }.toList()
            used + listOf("bug", "enhancement", "documentation", "good first issue", "help wanted", "question")
        } else emptyList()
        "review" -> if (type == SearchType.PR) listOf("none", "required", "approved", "changes_requested") else emptyList()
        "author", "assignee", "mentions", "review-requested" -> if (threads && (qualifier != "review-requested" || type == SearchType.PR)) listOfNotNull(login) else emptyList()
        "org", "user" -> if (type != SearchType.USER) history.repositories.map(RecentRepository::owner) else emptyList()
        else -> emptyList()
    }
    val prefix = text.substring(start, cursor).trim('"')
    val existing = text.substring(start, token.range.last + 1).trim('"')
    val matches = values.distinctBy(String::lowercase).filter { it.startsWith(prefix, true) && !it.equals(existing, true) }.take(5)
    return if (matches.isEmpty()) null else SearchCompletion(start, token.range.last + 1, qualifier, matches)
}
