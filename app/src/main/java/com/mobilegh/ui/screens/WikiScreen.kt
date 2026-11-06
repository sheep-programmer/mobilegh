package com.mobilegh.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mobilegh.R
import com.mobilegh.data.GitHub
import com.mobilegh.data.Wiki
import com.mobilegh.nav.rememberLoader
import com.mobilegh.ui.components.HtmlView
import com.mobilegh.ui.components.LoadBox
import com.mobilegh.ui.components.OcButton
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.openBrowser
import com.mobilegh.ui.components.rememberCtx

/**
 * 仓库 Wiki：取原始 Markdown，用 GitHub 的 /markdown 渲染后在应用内显示。
 * 相对链接以 wiki 目录为基准，点其它页面会继续留在应用里。
 */
@Composable
fun WikiScreen(owner: String, name: String, page: String?) {
    val ctx = rememberCtx()
    val title = Wiki.title(page)
    val html = rememberLoader("wiki:${owner.lowercase()}/${name.lowercase()}/${Wiki.pageName(page)}") { force ->
        val markdown = Wiki.page(owner, name, page, force)
        GitHub.renderMarkdown(Wiki.resolveLinks(markdown, owner, name), "$owner/$name")
    }
    Page(
        title,
        subtitle = "$owner/$name · Wiki",
        actions = {
            OcButton(R.drawable.oc_link_external, { ctx.openBrowser(Wiki.webUrl(owner, name, page)) })
        },
    ) { pad ->
        LoadBox(html, Modifier.padding(pad).fillMaxSize()) { body ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
                HtmlView(body, baseUrl = Wiki.baseUrl(owner, name))
            }
        }
    }
}
