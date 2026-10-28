package com.mobilegh.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.ui.theme.Gh
import com.mobilegh.i18n.tr

/**
 * GitHub 风格的可见性徽章：灰色描边的小胶囊，私有仓库前面带一把锁。
 * 仓库列表、置顶仓库、仓库页标题、项目、Gist 等所有标示"私有"的地方都用它，样式一致。
 */
@Composable
fun VisibilityBadge(private: Boolean, modifier: Modifier = Modifier, label: String = if (private) tr("私有") else tr("公开")) {
    val g = Gh.c
    Row(
        modifier
            .border(1.dp, g.border, RoundedCornerShape(50))
            .padding(start = if (private) 6.dp else 8.dp, end = 8.dp, top = 1.dp, bottom = 1.dp)
            .semantics(mergeDescendants = true) { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (private) {
            Oc(R.drawable.oc_lock, g.fgMuted, 11.dp)
            Spacer(Modifier.width(3.dp))
        }
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = g.fgMuted, maxLines = 1)
    }
}
