package com.mobilegh.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mobilegh.data.ApiException
import com.mobilegh.data.GitLinks
import com.mobilegh.i18n.tr
import com.mobilegh.nav.rememberLoader
import com.mobilegh.ui.components.ErrorState
import com.mobilegh.ui.components.Loading
import com.mobilegh.ui.components.Page

/**
 * blob / tree / raw 链接的入口。先确定分支（或标签、提交）、路径，以及它是文件还是目录，再交给对应页面：
 * 文件进入文件查看，目录进入文件列表（例如 README 里指向 docs/ 的相对链接）。
 */
@Composable
fun BlobScreen(owner: String, name: String, rest: String, lines: IntRange?) {
    val resolved = rememberLoader("blob:$rest") { f ->
        GitLinks.resolve(owner, name, rest, f)
            ?: throw ApiException(404, tr("未找到该路径（可能不存在，或分支名与路径无法区分）"))
    }
    val target = resolved.data
    if (target == null) {
        Page(tr("文件"), subtitle = "$owner/$name") { pad ->
            val error = resolved.error
            if (error != null) ErrorState(error, Modifier.padding(pad).fillMaxSize()) { resolved.load(true) }
            else Loading(Modifier.padding(pad).fillMaxSize())
        }
    } else if (target.isDir) {
        FilesScreen(owner, name, target.path, target.ref)
    } else {
        FileViewScreen(owner, name, target.path, target.ref, lines)
    }
}
