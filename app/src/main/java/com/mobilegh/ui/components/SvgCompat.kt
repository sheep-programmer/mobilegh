package com.mobilegh.ui.components

/**
 * Normalizes SVG 2 / browser-exported SVG syntax that AndroidSVG 1.4 rejects with SVGParseException
 * or XmlPullParserException, so diagrams and badges render instead of failing completely.
 */
internal object SvgCompat {
    private val autoStartReverseOrient = Regex("""\borient\s*=\s*(["'])auto-start-reverse\1""", RegexOption.IGNORE_CASE)
    private val autoLengthAttr = Regex("""(?<=\s)(?:width|height|rx|ry)\s*=\s*(["'])\s*(?:auto|fit-content|max-content|min-content)\s*\1""", RegexOption.IGNORE_CASE)
    private val transformAttr = Regex("""(?<=\s)(transform|gradientTransform|patternTransform)\s*=\s*(["'])(.*?)\2""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val cssUnitInNumber = Regex("""(-?\d+(?:\.\d+)?)\s*(?:px|deg)\b""", RegexOption.IGNORE_CASE)
    private val translateXFn = Regex("""\btranslateX\(\s*([^)]+?)\s*\)""")
    private val translateYFn = Regex("""\btranslateY\(\s*([^)]+?)\s*\)""")
    private val scaleXFn = Regex("""\bscaleX\(\s*([^)]+?)\s*\)""")
    private val scaleYFn = Regex("""\bscaleY\(\s*([^)]+?)\s*\)""")
    private val htmlEntity = Regex("""&(nbsp|mdash|ndash|rsquo|lsquo|ldquo|rdquo|copy|reg|trade|hellip|times|middot|bull|deg|plusmn|rarr|larr|uarr|darr);""")

    private val htmlEntities = mapOf(
        "nbsp" to "&#160;",
        "mdash" to "&#8212;",
        "ndash" to "&#8211;",
        "rsquo" to "&#8217;",
        "lsquo" to "&#8216;",
        "ldquo" to "&#8220;",
        "rdquo" to "&#8221;",
        "copy" to "&#169;",
        "reg" to "&#174;",
        "trade" to "&#8482;",
        "hellip" to "&#8230;",
        "times" to "&#215;",
        "middot" to "&#183;",
        "bull" to "&#8226;",
        "deg" to "&#176;",
        "plusmn" to "&#177;",
        "rarr" to "&#8594;",
        "larr" to "&#8592;",
        "uarr" to "&#8593;",
        "darr" to "&#8595;",
    )

    fun normalize(bytes: ByteArray): ByteArray {
        if (bytes.size > 2 * 1024 * 1024) return bytes
        val text = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            return bytes
        }
        var result = text
        if (result.contains("auto-start-reverse", ignoreCase = true)) {
            result = autoStartReverseOrient.replace(result) { "orient=${it.groupValues[1]}auto${it.groupValues[1]}" }
        }
        if (result.contains("auto", ignoreCase = true) || result.contains("-content", ignoreCase = true)) {
            result = autoLengthAttr.replace(result, "")
        }
        if (result.contains("transform", ignoreCase = true)) {
            result = transformAttr.replace(result) { match ->
                val attr = match.groupValues[1]
                val quote = match.groupValues[2]
                val value = match.groupValues[3].trim()
                if (value.equals("none", ignoreCase = true)) {
                    ""
                } else {
                    val normalized = normalizeTransformValue(value)
                    if (normalized == match.groupValues[3]) match.value else "$attr=$quote$normalized$quote"
                }
            }
        }
        if ('&' in result) {
            result = htmlEntity.replace(result) { htmlEntities[it.groupValues[1]] ?: it.value }
        }
        return if (result == text) bytes else result.encodeToByteArray()
    }

    private fun normalizeTransformValue(value: String): String {
        var out = cssUnitInNumber.replace(value, "$1")
        if ("translateX" in out) out = translateXFn.replace(out) { "translate(${it.groupValues[1]}, 0)" }
        if ("translateY" in out) out = translateYFn.replace(out) { "translate(0, ${it.groupValues[1]})" }
        if ("scaleX" in out) out = scaleXFn.replace(out) { "scale(${it.groupValues[1]}, 1)" }
        if ("scaleY" in out) out = scaleYFn.replace(out) { "scale(1, ${it.groupValues[1]})" }
        return out
    }
}
