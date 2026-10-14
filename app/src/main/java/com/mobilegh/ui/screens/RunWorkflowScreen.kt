package com.mobilegh.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.data.ApiException
import com.mobilegh.data.AppLog
import com.mobilegh.data.GitHub
import com.mobilegh.data.RepoWrite
import com.mobilegh.data.WorkflowDispatch
import com.mobilegh.data.WorkflowInput
import com.mobilegh.nav.LocalEntry
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.friendly
import com.mobilegh.nav.rememberLoader
import com.mobilegh.nav.retain
import com.mobilegh.ui.components.Dropdown
import com.mobilegh.ui.components.ErrorState
import com.mobilegh.ui.components.GhButton
import com.mobilegh.ui.components.GhField
import com.mobilegh.ui.components.HDivider
import com.mobilegh.ui.components.InlineError
import com.mobilegh.ui.components.Loading
import com.mobilegh.ui.components.Oc
import com.mobilegh.ui.components.Page
import com.mobilegh.ui.components.SwitchRow
import com.mobilegh.ui.components.rememberCtx
import com.mobilegh.ui.components.toast
import com.mobilegh.ui.theme.CodeStyle
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 手动运行工作流：读取工作流 YAML 的 workflow_dispatch.inputs，生成带类型的输入表单 */
@Composable
fun RunWorkflowScreen(owner: String, name: String, workflowId: Long, path: String, title: String, ref0: String?) {
    val nav = LocalNav.current
    val ctx = rememberCtx()
    val entry = LocalEntry.current
    val g = Gh.c
    val repo = rememberLoader("repo") { GitHub.repo(owner, name, it) }
    var picked by rememberSaveable { mutableStateOf(ref0) }
    val ref = picked ?: repo.data?.defaultBranch
    if (ref == null) {
        Page("运行工作流", subtitle = title) { pad ->
            val err = repo.error
            if (err != null) ErrorState(err, Modifier.padding(pad).fillMaxSize()) { repo.load(true) }
            else Loading(Modifier.padding(pad).fillMaxSize())
        }
        return
    }
    var picking by remember { mutableStateOf(false) }
    val values = retain("values") { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    val spec = rememberLoader("workflow:$path:$ref") { _ ->
        try {
            WorkflowDispatch.parse(GitHub.workflowFile(owner, name, path, ref))
        } catch (e: ApiException) {
            if (e.code == 404) throw ApiException(404, "$ref 上没有工作流文件 $path")
            throw e
        }
    }
    val extra = rememberLoader("envs") { _ -> runCatching { GitHub.environments(owner, name) }.getOrDefault(emptyList()) }
    // 输入的新默认值：只在首次拿到某个输入时填入，之后保留用户输入
    val s = spec.data
    if (s != null) {
        s.inputs.forEach { input ->
            if (input.name !in values) values[input.name] = when (input.type) {
                "boolean" -> input.default?.lowercase() ?: "false"
                "choice" -> input.default ?: input.options.firstOrNull().orEmpty()
                else -> input.default.orEmpty()
            }
        }
    }
    val missing = s?.inputs?.filter { it.required && values[it.name].isNullOrBlank() }.orEmpty()
    val badNumber = s?.inputs?.filter { it.type == "number" && (values[it.name] ?: "").isNotEmpty() && (values[it.name] ?: "").toDoubleOrNull() == null }.orEmpty()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Page("运行工作流", subtitle = title) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
            Row(
                Modifier.fillMaxWidth().clickable(enabled = !busy) { picking = true }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Oc(R.drawable.oc_git_branch, g.fgMuted)
                Spacer(Modifier.width(10.dp))
                Text("运行分支 / 标签", Modifier.weight(1f), fontSize = 15.sp, color = g.fg)
                Text(ref, fontSize = 14.sp, color = g.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(4.dp))
                Oc(R.drawable.oc_chevron_down, g.fgMuted, 12.dp)
            }
            HDivider()
            when {
                spec.error != null -> ErrorState(spec.error!!, Modifier.fillMaxWidth().height(240.dp)) { spec.load(true) }
                s == null -> Loading(Modifier.fillMaxWidth().height(160.dp))
                !s.enabled -> Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Oc(R.drawable.oc_alert, g.attention)
                        Spacer(Modifier.width(8.dp))
                        Text("该工作流不能手动运行", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = g.fg)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "$path 在 $ref 上没有声明 workflow_dispatch 触发器。请换一个分支或标签，或在仓库里为该工作流添加 on.workflow_dispatch。",
                        fontSize = 13.sp, lineHeight = 19.sp, color = g.fgMuted,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(path, fontSize = 12.sp, color = g.fgMuted, style = CodeStyle)
                }
                s.inputs.isEmpty() -> Text("该工作流没有输入参数，直接运行即可。", Modifier.padding(16.dp), fontSize = 13.sp, color = g.fgMuted)
                else -> Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    s.inputs.forEach { input -> InputField(input, values, extra.data.orEmpty(), busy) { values[input.name] = it; error = null } }
                }
            }
            Column(Modifier.padding(horizontal = 16.dp)) {
                if (missing.isNotEmpty()) Text("请填写：${missing.joinToString("、") { it.name }}", fontSize = 12.sp, color = g.attention)
                if (badNumber.isNotEmpty()) Text("需要填写数字：${badNumber.joinToString("、") { it.name }}", fontSize = 12.sp, color = g.danger)
                error?.let { InlineError(it) }
                Spacer(Modifier.height(12.dp))
                GhButton(if (busy) "运行中…" else "运行工作流", Modifier.fillMaxWidth(), primary = true, enabled = s?.enabled == true && missing.isEmpty() && badNumber.isEmpty() && !busy) {
                    busy = true
                    error = null
                    entry.scope.launch {
                        try {
                            val inputs = s!!.inputs.associate { it.name to (values[it.name] ?: "") }
                            AppLog.info("actions", "手动触发工作流：" + title + " @ " + ref)
                            GitHub.dispatch(owner, name, workflowId, ref, inputs)
                            RepoWrite.changed(owner, name, RepoWrite.ACTIONS)
                            busy = false
                            ctx.toast("已触发 ${title}")
                            nav.pop()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            busy = false
                            error = e.friendly()
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    if (picking) BranchPicker(owner, name, ref, onDismiss = { picking = false }) { picked = it; picking = false }
}

@Composable
private fun InputField(
    input: WorkflowInput,
    values: MutableMap<String, String>,
    environments: List<String>,
    busy: Boolean,
    set: (String) -> Unit,
) {
    val g = Gh.c
    val v = values[input.name].orEmpty()
    val inset = Modifier.padding(horizontal = 16.dp)
    Column {
        Row(inset, verticalAlignment = Alignment.CenterVertically) {
            Text(input.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = g.fg)
            Spacer(Modifier.width(6.dp))
            Text(typeZh(input.type), fontSize = 11.sp, color = g.fgMuted)
            if (input.required) {
                Spacer(Modifier.width(4.dp))
                Text("必填", fontSize = 11.sp, color = g.attention)
            }
            if (input.options.isNotEmpty() && input.type != "choice") {
                Spacer(Modifier.width(6.dp))
                Text("可选值：${input.options.joinToString(", ")}", fontSize = 11.sp, color = g.fgMuted)
            }
        }
        input.description?.let {
            Text(it.trimEnd('\n'), inset.padding(top = 2.dp), fontSize = 12.sp, lineHeight = 17.sp, color = g.fgMuted)
        }
        Spacer(Modifier.height(6.dp))
        if (input.type == "boolean") {
            SwitchRow(if (v.equals("true", true)) "是（true）" else "否（false）", null, v.equals("true", true), enabled = !busy) { set(if (it) "true" else "false") }
        } else Box(inset) { when (input.type) {
            "choice" -> if (input.options.isEmpty()) {
                GhField(v, set, input.name)
            } else {
                Dropdown(v.ifEmpty { "请选择" }, input.options, { set(input.options[it]) })
            }
            "environment" -> if (environments.isEmpty()) {
                GhField(v, set, "环境名称", placeholder = "例如 production")
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dropdown(v.ifEmpty { "请选择环境" }, environments, { set(environments[it]) })
                    Spacer(Modifier.width(10.dp))
                    GhField(v, set, "或直接输入", Modifier.weight(1f))
                }
            }
            "number" -> GhField(
                v, set, input.name,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next,
                ),
            )
            else -> GhField(v, set, input.name)
        } }
    }
}

private fun typeZh(t: String) = when (t) {
    "boolean" -> "布尔"
    "choice" -> "选项"
    "number" -> "数字"
    "environment" -> "环境"
    else -> "文本"
}
