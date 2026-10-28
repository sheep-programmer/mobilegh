package com.mobilegh.i18n

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.InputStream
import java.util.Locale

/** 界面语言的选择：固定中文、固定英文，或跟随系统（系统是中文就用中文，其余用英文） */
enum class AppLanguage(val code: String) {
    Chinese("zh"), English("en"), System("system");

    companion object {
        fun of(code: String?): AppLanguage = entries.firstOrNull { it.code == code } ?: Chinese
    }
}

/**
 * 界面文案的中英切换。
 *
 * 源码里的文案本身就是中文，同时是词典的键：中文模式原样返回，英文模式去 `assets/i18n/en.tsv`
 * 查对应译文，查不到就退回中文（宁可显示中文也不显示空白或键名）。
 * 带变量的文案用 `{0}`、`{1}` 占位（[trf]），译文必须保留同样的占位符。
 *
 * 当前语言是 Compose 状态：界面读取文案时自动订阅，切换后立即重组刷新。
 */
object I18n {
    private var english by mutableStateOf(false)
    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null

    @Volatile private var dictionary: Map<String, String>? = null

    var choice by mutableStateOf(AppLanguage.Chinese)
        private set

    val isEnglish: Boolean get() = english

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext!!.getSharedPreferences("i18n", Context.MODE_PRIVATE)
        choice = AppLanguage.of(prefs!!.getString("lang", null))
        english = resolve(choice, Locale.getDefault().language)
    }

    fun select(language: AppLanguage) {
        prefs?.edit()?.putString("lang", language.code)?.apply()
        choice = language
        english = resolve(language, Locale.getDefault().language)
    }

    /** 当前选择最终是否显示英文 */
    internal fun resolve(choice: AppLanguage, systemLanguage: String): Boolean = when (choice) {
        AppLanguage.Chinese -> false
        AppLanguage.English -> true
        AppLanguage.System -> !systemLanguage.equals("zh", ignoreCase = true)
    }

    fun tr(zh: String): String {
        if (!english) return zh
        return (dictionary ?: load())[zh] ?: zh
    }

    fun trf(template: String, vararg args: Any?): String = fill(tr(template), args)

    /**
     * 同一个中文词在不同位置对应不同英文（「标签」既是 Issue 的 label 也是 git 的 tag）时带上语境：
     * 词典里先查「中文|语境」，没有再退回普通条目。中文模式下语境不显示。
     */
    fun trc(zh: String, context: String): String {
        if (!english) return zh
        val dict = dictionary ?: load()
        return dict["$zh|$context"] ?: dict[zh] ?: zh
    }

    private fun load(): Map<String, String> = synchronized(this) {
        dictionary ?: run {
            val loaded = runCatching {
                appContext!!.assets.open("i18n/en.tsv").use { parseDictionary(it) }
            }.getOrDefault(emptyMap())
            dictionary = loaded
            loaded
        }
    }

    /** 词典格式：每行 `中文<TAB>English`，换行、制表符、反斜杠分别写成 `\n`、`\t`、`\\`；以 `##` 开头的行是注释 */
    internal fun parseDictionary(input: InputStream): Map<String, String> =
        parseDictionary(input.bufferedReader(Charsets.UTF_8).readText())

    internal fun parseDictionary(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        text.lineSequence().forEach { line ->
            if (line.isEmpty() || line.startsWith("##")) return@forEach
            val tab = line.indexOf('\t')
            if (tab <= 0) return@forEach
            out[unescape(line.substring(0, tab))] = unescape(line.substring(tab + 1))
        }
        return out
    }

    internal fun escape(text: String): String =
        text.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r")

    internal fun unescape(text: String): String {
        if ('\\' !in text) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (text[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '\\' -> sb.append('\\')
                    else -> sb.append(c).append(text[i + 1])
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** 把 `{0}`、`{1}` 换成对应参数；超出范围的占位符原样保留 */
    internal fun fill(template: String, args: Array<out Any?>): String {
        if (args.isEmpty() || '{' !in template) return template
        val sb = StringBuilder(template.length + 16)
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c == '{') {
                val close = template.indexOf('}', i + 1)
                val index = if (close > i + 1) template.substring(i + 1, close).toIntOrNull() else null
                if (index != null && index in args.indices) {
                    sb.append(args[index].toString())
                    i = close + 1
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}

/** 取当前语言的文案；参数是中文原文 */
fun tr(zh: String): String = I18n.tr(zh)

/** 取带语境的文案：同一个中文词在不同位置译法不同时使用，词典键是「中文|语境」 */
fun trc(zh: String, context: String): String = I18n.trc(zh, context)

/** 取带变量的文案：模板里用 `{0}`、`{1}` 占位 */
fun trf(template: String, vararg args: Any?): String = I18n.trf(template, *args)
