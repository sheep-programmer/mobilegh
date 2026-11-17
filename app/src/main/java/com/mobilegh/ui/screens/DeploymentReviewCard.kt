package com.mobilegh.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.DeploymentReview
import com.mobilegh.data.DeploymentReviewInput
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.GhDialog
import com.mobilegh.ui.components.GhField
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.theme.Gh

/** 即使界面状态被旧数据污染，也只能提交当前仍可审批的环境；绝不使用数字 Run ID 代替 Node ID。 */
internal fun deploymentReviewInput(
    status: String?,
    workflowRunNodeId: String?,
    selected: List<DeploymentReview>,
    comment: String,
): DeploymentReviewInput? {
    val environmentIds = selected.filter { it.canApprove }.map { it.environmentId }.distinct()
    if (status != "waiting" || workflowRunNodeId.isNullOrBlank() || environmentIds.isEmpty()) return null
    return DeploymentReviewInput(workflowRunNodeId, environmentIds, comment)
}

internal enum class DeploymentReviewDisplay { Hidden, Loading, Error, Content }

internal fun deploymentReviewDisplay(
    status: String?,
    workflowRunNodeId: String?,
    pending: List<DeploymentReview>?,
    error: String?,
): DeploymentReviewDisplay = when {
    status != "waiting" -> DeploymentReviewDisplay.Hidden
    workflowRunNodeId.isNullOrBlank() -> DeploymentReviewDisplay.Content
    pending == null && error != null -> DeploymentReviewDisplay.Error
    pending == null -> DeploymentReviewDisplay.Loading
    else -> DeploymentReviewDisplay.Content
}

@Composable
fun DeploymentReviewCard(
    status: String?,
    workflowRunNodeId: String?,
    reviews: List<DeploymentReview>,
    busy: Boolean,
    onReview: (DeploymentReviewInput, Boolean) -> Unit,
) {
    val g = Gh.c
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var comment by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf<Boolean?>(null) }
    val selected = reviews.filter { it.environmentId in selectedIds }
    val input = deploymentReviewInput(status, workflowRunNodeId, selected, comment)

    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()
            .border(1.dp, g.border, RoundedCornerShape(8.dp)),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Oc(R.drawable.oc_clock, g.attention, 16.dp)
            Spacer(Modifier.width(8.dp))
            Text(tr("部署审批"), color = g.fg, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        if (reviews.isEmpty()) {
            Text(tr("当前没有待审批环境"), Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = g.fgMuted, fontSize = 13.sp)
        } else reviews.forEachIndexed { index, review ->
            if (index > 0) HDivider(Modifier.padding(start = 12.dp))
            val enabled = review.canApprove && !workflowRunNodeId.isNullOrBlank() && !busy
            Row(
                Modifier.fillMaxWidth().clickable(enabled = enabled) {
                    selectedIds = if (review.environmentId in selectedIds) selectedIds - review.environmentId else selectedIds + review.environmentId
                }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = review.environmentId in selectedIds && review.canApprove,
                    onCheckedChange = { checked ->
                        selectedIds = if (checked) selectedIds + review.environmentId else selectedIds - review.environmentId
                    },
                    enabled = enabled,
                )
                Column(Modifier.weight(1f)) {
                    Text(review.environmentName, color = g.fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(
                        if (review.reviewers.isEmpty()) tr("未列出审批人") else trf("审批人：{0}", review.reviewers.joinToString("、")),
                        color = g.fgMuted,
                        fontSize = 12.sp,
                    )
                    if (!review.canApprove) Text(tr("当前账号不能审批此环境"), color = g.attention, fontSize = 12.sp)
                }
            }
        }
        if (workflowRunNodeId.isNullOrBlank()) {
            Text(tr("缺少 WorkflowRun Node ID，无法提交部署审批"), Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = g.attention, fontSize = 12.sp)
        } else if (reviews.isNotEmpty() && reviews.none { it.canApprove }) {
            Text(tr("当前账号没有这些环境的审批权限，只能查看待审批请求"), Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = g.fgMuted, fontSize = 12.sp)
        }
        if (reviews.any { it.canApprove }) {
            GhField(
                comment,
                { comment = it },
                tr("审批留言（可选）"),
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                singleLine = false,
                minLines = 2,
                enabled = !busy && !workflowRunNodeId.isNullOrBlank(),
            )
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhButton(tr("批准部署"), primary = true, enabled = input != null && !busy, icon = R.drawable.oc_check) { confirmation = true }
                GhButton(tr("拒绝部署"), danger = true, enabled = input != null && !busy, icon = R.drawable.oc_x) { confirmation = false }
            }
        }
    }

    confirmation?.let { approve ->
        val confirmedInput = input
        GhDialog(
            if (approve) tr("批准所选环境的部署？") else tr("拒绝所选环境的部署？"),
            onDismiss = { if (!busy) confirmation = null },
            confirm = if (approve) tr("批准部署") else tr("拒绝部署"),
            danger = !approve,
            confirmEnabled = confirmedInput != null && !busy,
            dismissEnabled = !busy,
            onConfirm = {
                if (confirmedInput != null) {
                    confirmation = null
                    onReview(confirmedInput, approve)
                }
            },
        ) {
            Column {
                Text(selected.filter { it.canApprove }.joinToString("、") { it.environmentName }, color = g.fg)
                if (comment.isNotBlank()) Text(trf("留言：{0}", comment.trim()), Modifier.padding(top = 8.dp), color = g.fgMuted)
            }
        }
    }
}
