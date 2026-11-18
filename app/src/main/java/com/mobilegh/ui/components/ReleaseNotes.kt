package com.mobilegh.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.mobilegh.ui.theme.Gh

@Composable
internal fun ReleaseNotes(markdown: String?, html: String?, baseUrl: String, modifier: Modifier = Modifier) {
    when {
        !html.isNullOrBlank() -> HtmlView(html, modifier, baseUrl = baseUrl)
        !markdown.isNullOrBlank() -> SelectionContainer {
            Text(markdown, modifier.fillMaxWidth(), color = Gh.c.fg, fontSize = 14.sp, lineHeight = 21.sp)
        }
    }
}
