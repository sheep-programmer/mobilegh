package com.mobilegh.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.data.*
import com.mobilegh.nav.LocalNav
import com.mobilegh.nav.rememberLoader
import com.mobilegh.nav.Loader
import com.mobilegh.ui.components.*
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun SubscriptionDialog(owner: String, name: String, onDismiss: () -> Unit, onSaved: (SubscriptionMode) -> Unit) {
    val account = remember { Session.login to Session.generation }
    val scope = rememberCoroutineScope()
    val state = remember(owner, name) { Loader(scope) { SubscriptionSettings.current(owner, name) } }
    LaunchedEffect(state) { state.load(true) }
    var selected by remember { mutableStateOf<SubscriptionMode?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    GhDialog("仓库订阅", { if (!saving) onDismiss() }, confirm = if (saving) "保存中…" else "保存",
        confirmEnabled = state.data != null && !saving, onConfirm = {
            val target = selected ?: state.data ?: return@GhDialog
            saving = true
            scope.launch {
                try {
                    check(Session.login == account.first && Session.generation == account.second && Session.token != null) { "账号已切换，请重新打开订阅设置" }
                    SubscriptionSettings.set(owner, name, target)
                    check(Session.login == account.first && Session.generation == account.second) { "账号已切换，旧账号的订阅结果不再更新当前页面" }
                    onSaved(target)
                    onDismiss()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message }
                finally { saving = false }
            }
        }) {
        Column {
            if (state.loading && state.data == null) Text("正在读取订阅设置…")
            state.error?.let { Text(it, color = Gh.c.danger); TextLink("重试") { state.load(true) } }
            if (state.data != null) SubscriptionMode.entries.forEach { mode ->
                Column(Modifier.fillMaxWidth().clickable(enabled = !saving) { selected = mode }.padding(vertical = 10.dp)) {
                    Text("${if ((selected ?: state.data) == mode) "●" else "○"} ${mode.title}", color = Gh.c.fg, fontSize = 15.sp)
                    Text(mode.description, color = Gh.c.fgMuted, fontSize = 12.sp)
                }
            }
            error?.let { Text(it, color = Gh.c.danger, fontSize = 12.sp) }
        }
    }
}

private val ProfileFieldsSaver = listSaver<ProfileFields, String>(
    save = { listOf(it.name, it.bio, it.company, it.location, it.website, it.twitter, it.email, it.hireable.toString()) },
    restore = { ProfileFields(it[0], it[1], it[2], it[3], it[4], it[5], it[6], it[7].toBoolean()) },
)
@Composable
fun EditProfileScreen() {
    val nav = LocalNav.current
    val user = rememberLoader("profile-edit") { GitHub.viewer(true) }
    Page("编辑个人资料") { pad ->
        LoadBox(user, Modifier.padding(pad).fillMaxSize()) { original ->
            var fields by rememberSaveable(original.login, stateSaver = ProfileFieldsSaver) { mutableStateOf(ProfileFields.from(original)) }
            var saving by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            val scope = rememberCoroutineScope()
            ProfileEditorForm(fields, { fields = it }, saving, error) {
                saving = true
                error = null
                scope.launch {
                    try { ProfileEditing.save(original, fields); nav.pop() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message }
                    finally { saving = false }
                }
            }
        }
    }
}

@Composable
fun ProfileEditorForm(fields: ProfileFields, onChange: (ProfileFields) -> Unit, saving: Boolean, error: String?, onSave: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        fun change(next: ProfileFields) { if (!saving) onChange(next) }
        GhField(fields.name, { change(fields.copy(name = it)) }, "显示名称")
        GhField(fields.bio, { change(fields.copy(bio = it)) }, "个人简介（最多 160 字符）", singleLine = false, minLines = 3)
        GhField(fields.company, { change(fields.copy(company = it)) }, "公司 / 组织")
        GhField(fields.location, { change(fields.copy(location = it)) }, "所在地")
        GhField(fields.website, { change(fields.copy(website = it)) }, "网站")
        GhField(fields.twitter, { change(fields.copy(twitter = it)) }, "Twitter 用户名")
        GhField(fields.email, { change(fields.copy(email = it)) }, "公开邮箱")
        SwitchRow("接受工作机会", checked = fields.hireable, enabled = !saving) { change(fields.copy(hireable = it)) }
        error?.let { Text(it, color = Gh.c.danger, fontSize = 13.sp) }
        GhButton(if (saving) "保存中…" else "保存资料", Modifier.fillMaxWidth(), primary = true, enabled = !saving, onClick = onSave)
    }
}
