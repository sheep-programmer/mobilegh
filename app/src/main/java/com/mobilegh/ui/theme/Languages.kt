package com.mobilegh.ui.theme

import androidx.compose.ui.graphics.Color

/** 常见语言颜色（来自 github-linguist） */
private val LANG = mapOf(
    "Kotlin" to 0xFFA97BFF, "Java" to 0xFFB07219, "JavaScript" to 0xFFF1E05A, "TypeScript" to 0xFF3178C6,
    "Python" to 0xFF3572A5, "Go" to 0xFF00ADD8, "Rust" to 0xFFDEA584, "C" to 0xFF555555, "C++" to 0xFFF34B7D,
    "C#" to 0xFF178600, "Swift" to 0xFFF05138, "Objective-C" to 0xFF438EFF, "Ruby" to 0xFF701516, "PHP" to 0xFF4F5D95,
    "HTML" to 0xFFE34C26, "CSS" to 0xFF663399, "SCSS" to 0xFFC6538C, "Vue" to 0xFF41B883, "Svelte" to 0xFFFF3E00,
    "Shell" to 0xFF89E051, "Dart" to 0xFF00B4AB, "Lua" to 0xFF000080, "Scala" to 0xFFC22D40, "Haskell" to 0xFF5E5086,
    "Elixir" to 0xFF6E4A7E, "Clojure" to 0xFFDB5855, "R" to 0xFF198CE7, "Perl" to 0xFF0298C3, "Jupyter Notebook" to 0xFFDA5B0B,
    "Dockerfile" to 0xFF384D54, "Makefile" to 0xFF427819, "CMake" to 0xFFDA3434, "Vim Script" to 0xFF199F4B,
    "PowerShell" to 0xFF012456, "Groovy" to 0xFF4298B8, "Zig" to 0xFFEC915C, "Nix" to 0xFF7E7EFF, "MDX" to 0xFFFCB32C,
    "Markdown" to 0xFF083FA1, "TeX" to 0xFF3D6117, "Assembly" to 0xFF6E4C13, "Solidity" to 0xFFAA6746, "Julia" to 0xFFA270BA,
    "Erlang" to 0xFFB83998, "OCaml" to 0xFFEF7A08, "F#" to 0xFFB845FC, "Batchfile" to 0xFFC1F12E, "Astro" to 0xFFFF5A03,
    "Nim" to 0xFFFFC200, "Crystal" to 0xFF000100, "V" to 0xFF4F87C4, "HCL" to 0xFF844FBA, "Starlark" to 0xFF76D275,
    "GDScript" to 0xFF355570, "Cuda" to 0xFF3A4E3A, "Fortran" to 0xFF4D41B1, "MATLAB" to 0xFFE16737, "Less" to 0xFF1D365D,
    "Smarty" to 0xFFF0C040, "Handlebars" to 0xFFF7931E, "Mustache" to 0xFF724B3B, "Objective-C++" to 0xFF6866FB,
)

fun langColor(name: String?, hex: String? = null): Color {
    hex?.let { parseHex(it)?.let { c -> return c } }
    return LANG[name]?.let { Color(it) } ?: Color(0xFF8B949E)
}

fun parseHex(hex: String): Color? {
    val h = hex.removePrefix("#")
    return when (h.length) {
        6 -> h.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
        3 -> h.map { "$it$it" }.joinToString("").toLongOrNull(16)?.let { Color(0xFF000000 or it) }
        else -> null
    }
}
