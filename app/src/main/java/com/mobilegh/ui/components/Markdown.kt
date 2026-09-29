package com.mobilegh.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import com.mobilegh.ui.theme.Gh
import com.mobilegh.ui.theme.GhColors

private val EMOJI = mapOf(
    "rocket" to "🚀", "bug" to "🐛", "sparkles" to "✨", "tada" to "🎉", "fire" to "🔥", "memo" to "📝", "art" to "🎨",
    "zap" to "⚡", "white_check_mark" to "✅", "lock" to "🔒", "arrow_up" to "⬆️", "arrow_down" to "⬇️", "wrench" to "🔧",
    "recycle" to "♻️", "construction" to "🚧", "heavy_plus_sign" to "➕", "heavy_minus_sign" to "➖", "pencil2" to "✏️",
    "lipstick" to "💄", "package" to "📦", "truck" to "🚚", "boom" to "💥", "bookmark" to "🔖", "rotating_light" to "🚨",
    "green_heart" to "💚", "arrow_right" to "➡️", "warning" to "⚠️", "+1" to "👍", "-1" to "👎", "thumbsup" to "👍",
    "thumbsdown" to "👎", "heart" to "❤️", "eyes" to "👀", "star" to "⭐", "bulb" to "💡", "pushpin" to "📌", "hammer" to "🔨",
    "globe_with_meridians" to "🌐", "children_crossing" to "🚸", "wastebasket" to "🗑️", "card_file_box" to "🗃️",
    "loud_sound" to "🔊", "mute" to "🔇", "speech_balloon" to "💬", "alien" to "👽", "ambulance" to "🚑", "pencil" to "📝",
    "books" to "📚", "book" to "📖", "gear" to "⚙️", "test_tube" to "🧪", "seedling" to "🌱", "triangular_flag_on_post" to "🚩",
    "goal_net" to "🥅", "monocle_face" to "🧐", "bento" to "🍱", "wheelchair" to "♿", "bricks" to "🧱", "technologist" to "🧑‍💻",
    "money_with_wings" to "💸", "thread" to "🧵", "safety_vest" to "🦺", "airplane" to "✈️", "x" to "❌", "heavy_check_mark" to "✔️",
    "100" to "💯", "smile" to "😄", "laughing" to "😆", "joy" to "😂", "pray" to "🙏", "clap" to "👏", "wave" to "👋",
    "point_right" to "👉", "new" to "🆕", "sparkle" to "❇️", "chart_with_upwards_trend" to "📈", "mag" to "🔍", "link" to "🔗",
    "calendar" to "📆", "bell" to "🔔", "robot" to "🤖", "shield" to "🛡️", "key" to "🔑", "iphone" to "📱", "computer" to "💻",
    "moneybag" to "💰", "hourglass" to "⌛", "stop_sign" to "🛑", "no_entry" to "⛔", "information_source" to "ℹ️",
    "question" to "❓", "exclamation" to "❗", "hammer_and_wrench" to "🛠️", "arrow_up_small" to "🔼", "label" to "🏷️",
    "whale" to "🐳", "penguin" to "🐧", "apple" to "🍎", "checkered_flag" to "🏁", "construction_worker" to "👷",
    "busts_in_silhouette" to "👥", "bar_chart" to "📊", "clipboard" to "📋", "rewind" to "⏪", "twisted_rightwards_arrows" to "🔀",
    "mag_right" to "🔎", "see_no_evil" to "🙈", "camera_flash" to "📸", "dizzy" to "💫", "beers" to "🍻", "coffin" to "⚰️",
    "passport_control" to "🛂", "adhesive_bandage" to "🩹", "necktie" to "👔", "stethoscope" to "🩺", "arrow_down_small" to "🔽",
    "white_circle" to "⚪", "red_circle" to "🔴", "large_blue_circle" to "🔵", "green_circle" to "🟢", "yellow_circle" to "🟡",
    "heavy_exclamation_mark" to "❗", "rainbow" to "🌈", "cat" to "🐱", "dog" to "🐶", "octocat" to "🐙", "shipit" to "🐿️",
)

private val SHORTCODE = Regex(""":([a-z0-9_+\-]+):""")

/** 行内 Markdown：`代码`、**粗体**、*斜体*、~~删除线~~、[链接](url)、:emoji: */
fun mdInline(text: String, g: GhColors): AnnotatedString {
    val b = AnnotatedString.Builder()
    parse(text.replace("\r", ""), b, g)
    return b.toAnnotatedString()
}

private fun codeStyle(g: GhColors) = SpanStyle(
    fontFamily = FontFamily.Monospace,
    background = if (g.dark) Color(0x33656C76) else Color(0x1F818B98),
    fontSize = 0.88.em,
)

private fun parse(s: String, b: AnnotatedString.Builder, g: GhColors) {
    var i = 0
    val n = s.length
    val plain = StringBuilder()
    fun flush() { if (plain.isNotEmpty()) { b.append(plain.toString()); plain.clear() } }
    fun isWord(c: Char?) = c != null && (c.isLetterOrDigit())
    while (i < n) {
        val c = s[i]
        when {
            c == '\\' && i + 1 < n && s[i + 1] in "`*_~[]:#" -> { plain.append(s[i + 1]); i += 2 }
            c == '`' -> {
                var ticks = 0
                while (i + ticks < n && s[i + ticks] == '`') ticks++
                val fence = "`".repeat(ticks)
                val end = s.indexOf(fence, i + ticks)
                if (end < 0) { plain.append(fence); i += ticks } else {
                    flush()
                    b.pushStyle(codeStyle(g))
                    b.append(" " + s.substring(i + ticks, end).trim() + " ")
                    b.pop()
                    i = end + ticks
                }
            }
            (s.startsWith("**", i) || s.startsWith("__", i)) -> {
                val mark = s.substring(i, i + 2)
                val end = s.indexOf(mark, i + 2)
                if (end <= i + 2 || (mark == "__" && isWord(s.getOrNull(i - 1)))) { plain.append(mark); i += 2 } else {
                    flush()
                    b.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    parse(s.substring(i + 2, end), b, g)
                    b.pop()
                    i = end + 2
                }
            }
            s.startsWith("~~", i) -> {
                val end = s.indexOf("~~", i + 2)
                if (end <= i + 2) { plain.append("~~"); i += 2 } else {
                    flush()
                    b.pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                    parse(s.substring(i + 2, end), b, g)
                    b.pop()
                    i = end + 2
                }
            }
            (c == '*' || c == '_') && !isWord(s.getOrNull(i - 1)) && s.getOrNull(i + 1)?.isWhitespace() == false -> {
                var end = s.indexOf(c, i + 1)
                // 结束符后面不能紧跟字母数字（避免 snake_case 误判）
                while (end > 0 && c == '_' && isWord(s.getOrNull(end + 1))) end = s.indexOf(c, end + 1)
                if (end <= i + 1 || s[end - 1].isWhitespace()) { plain.append(c); i++ } else {
                    flush()
                    b.pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    parse(s.substring(i + 1, end), b, g)
                    b.pop()
                    i = end + 1
                }
            }
            c == '[' -> {
                val close = s.indexOf("](", i + 1)
                val end = if (close > 0) s.indexOf(')', close + 2) else -1
                if (close < 0 || end < 0 || s.substring(i + 1, close).contains('\n')) { plain.append(c); i++ } else {
                    flush()
                    b.pushStyle(SpanStyle(color = g.accent))
                    parse(s.substring(i + 1, close), b, g)
                    b.pop()
                    i = end + 1
                }
            }
            c == ':' -> {
                val m = SHORTCODE.matchAt(s, i)
                val e = m?.let { EMOJI[it.groupValues[1]] }
                if (m != null && e != null) { plain.append(e); i = m.range.last + 1 } else { plain.append(c); i++ }
            }
            else -> { plain.append(c); i++ }
        }
    }
    flush()
}

/** 支持行内 Markdown 的文本 */
@Composable
fun MdText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Gh.c.fg,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    lineHeight: TextUnit = TextUnit.Unspecified,
) {
    val g = Gh.c
    val a = remember(text, g.dark) { mdInline(text, g) }
    Text(a, modifier, color = color, fontSize = fontSize, fontWeight = fontWeight, maxLines = maxLines, overflow = TextOverflow.Ellipsis, lineHeight = lineHeight)
}
