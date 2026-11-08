package com.mobilegh.ui.screens

import androidx.activity.compose.BackHandler
import com.mobilegh.ui.components.verticalScrollWithBar
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
import com.mobilegh.nav.friendly
import com.mobilegh.ui.components.*
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf

@Composable
fun SubscriptionDialog(owner: String, name: String, onDismiss: () -> Unit, onSaved: (SubscriptionMode) -> Unit) {
    val account = remember { Session.login to Session.generation }
    val scope = rememberCoroutineScope()
    val state = remember(owner, name) { Loader(scope) { SubscriptionSettings.current(owner, name) } }
    LaunchedEffect(state) { state.load(true) }
    var selected by remember { mutableStateOf<SubscriptionMode?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    GhDialog(tr("仓库订阅"), { if (!saving) onDismiss() }, confirm = if (saving) tr("保存中…") else tr("保存"),
        confirmEnabled = state.data != null && !saving, onConfirm = {
            val target = selected ?: state.data ?: return@GhDialog
            saving = true
            scope.launch {
                try {
                    check(Session.login == account.first && Session.generation == account.second && Session.token != null) { tr("账号已切换，请重新打开订阅设置") }
                    SubscriptionSettings.set(owner, name, target)
                    check(Session.login == account.first && Session.generation == account.second) { tr("账号已切换，旧账号的订阅结果不再更新当前页面") }
                    onSaved(target)
                    onDismiss()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.friendly() }
                finally { saving = false }
            }
        }) {
        Column {
            if (state.loading && state.data == null) Loading(Modifier.fillMaxWidth().height(96.dp))
            state.error?.let { Text(it, color = Gh.c.danger); TextLink(tr("重试")) { state.load(true) } }
            if (state.data != null) SubscriptionMode.entries.forEach { mode ->
                RadioRow((selected ?: state.data) == mode, mode.title, mode.description, enabled = !saving) { selected = mode }
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
    // 与原始资料不一致即为「有未保存修改」：顶栏返回和系统返回都先确认，不直接丢弃
    var dirty by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    fun leave() { if (dirty) confirmLeave = true else nav.pop() }
    BackHandler(enabled = dirty) { leave() }
    Page(tr("编辑个人资料"), onBack = { leave() }) { pad ->
        LoadBox(user, Modifier.padding(pad).fillMaxSize()) { original ->
            var fields by rememberSaveable(original.login, stateSaver = ProfileFieldsSaver) { mutableStateOf(ProfileFields.from(original)) }
            var saving by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            val scope = rememberCoroutineScope()
            SideEffect { dirty = fields != ProfileFields.from(original) }
            ProfileEditorForm(fields, { fields = it }, saving, error) {
                saving = true
                error = null
                scope.launch {
                    try { ProfileEditing.save(original, fields); dirty = false; nav.pop() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.friendly() }
                    finally { saving = false }
                }
            }
        }
    }
    if (confirmLeave) GhDialog(tr("放弃修改？"), { confirmLeave = false }, confirm = tr("放弃"), danger = true, onConfirm = {
        confirmLeave = false
        dirty = false
        nav.pop()
    }) {
        Text(tr("离开后，未保存的资料修改会丢失。"), color = Gh.c.fg)
    }
}

/** 与资料接口的简介上限一致（[ProfileFields.patch] 对 bio 的 160 字符检查） */
private const val BIO_MAX = 160

@Composable
fun ProfileEditorForm(fields: ProfileFields, onChange: (ProfileFields) -> Unit, saving: Boolean, error: String?, onSave: () -> Unit) {
    val bioOver = fields.bio.length - BIO_MAX
    Column(Modifier.fillMaxSize().verticalScrollWithBar().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        fun change(next: ProfileFields) { if (!saving) onChange(next) }
        GhField(fields.name, { change(fields.copy(name = it)) }, tr("显示名称"))
        Column {
            GhField(fields.bio, { change(fields.copy(bio = it)) }, tr("个人简介（最多 160 字符）"), singleLine = false, minLines = 3)
            // 超限时写明是哪个字段、超了多少，保存按钮同时禁用，不再等服务端报内部错误
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), horizontalArrangement = Arrangement.End) {
                Text("${fields.bio.length}/$BIO_MAX", fontSize = 12.sp, color = if (bioOver > 0) Gh.c.danger else Gh.c.fgMuted)
            }
            if (bioOver > 0) Text(trf("个人简介不能超过 {0} 个字符", BIO_MAX), color = Gh.c.danger, fontSize = 13.sp)
        }
        GhField(fields.company, { change(fields.copy(company = it)) }, tr("公司 / 组织"))
        GhField(fields.location, { change(fields.copy(location = it)) }, tr("所在地"))
        GhField(fields.website, { change(fields.copy(website = it)) }, tr("网站"))
        GhField(fields.twitter, { change(fields.copy(twitter = it)) }, tr("Twitter 用户名"))
        GhField(fields.email, { change(fields.copy(email = it)) }, tr("公开邮箱"))
        SwitchRow(tr("接受工作机会"), checked = fields.hireable, enabled = !saving) { change(fields.copy(hireable = it)) }
        error?.let { Text(it, color = Gh.c.danger, fontSize = 13.sp) }
        GhButton(if (saving) tr("保存中…") else tr("保存资料"), Modifier.fillMaxWidth(), primary = true, enabled = !saving && bioOver <= 0, onClick = onSave)
    }
}
