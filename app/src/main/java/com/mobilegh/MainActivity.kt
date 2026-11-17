package com.mobilegh

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import com.mobilegh.data.AppLock
import com.mobilegh.data.Session
import com.mobilegh.data.Downloads
import com.mobilegh.nav.Links
import com.mobilegh.nav.NavRestore
import com.mobilegh.nav.Navigator
import com.mobilegh.nav.IncomingLinks
import com.mobilegh.ui.components.LinkDefaultPrompt
import com.mobilegh.nav.Tab
import com.mobilegh.widget.WidgetIntents
import com.mobilegh.widget.Widgets
import com.mobilegh.ui.components.toast
import com.mobilegh.ui.AppRoot
import com.mobilegh.ui.theme.MobileGhTheme
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf

class NavVm(private val state: SavedStateHandle) : ViewModel() {
    var pendingUrl by mutableStateOf(state.get<String>("pendingUrl"))
        private set
    var pendingShare by mutableStateOf(state.get<String>("pendingShare"))
        private set
    private var gen = -1
    private var current = Navigator()

    fun navFor(generation: Int): Navigator {
        if (generation != gen) {
            current.dispose()
            current = Navigator()
            // 首次创建（包括进程被系统回收后的重建）时恢复上次的导航栈与 Tab；账号切换不恢复
            if (gen == -1) current.restore(NavRestore.decode(state.get<String>(NavRestore.KEY), Session.login))
            gen = generation
        }
        return current
    }

    fun saveNav(text: String) {
        state[NavRestore.KEY] = text
    }

    fun receive(url: String) {
        state["pendingUrl"] = url
        pendingUrl = url
    }

    fun receiveShare(id: String) {
        state["pendingShare"] = id
        pendingShare = id
    }

    fun openPending(nav: Navigator, context: android.content.Context? = null) {
        if (Session.token == null) return
        val share = pendingShare
        if (share != null) {
            state.remove<String>("pendingShare")
            pendingShare = null
            nav.push(com.mobilegh.nav.Screen.ShareIssue(share))
            return
        }
        val url = pendingUrl ?: return
        val enterpriseHost = Links.enterpriseHost(url)
        if (enterpriseHost != null && !Links.enterpriseHostAllowed(url, com.mobilegh.data.Net.apiHost)) {
            // 保留 pendingUrl：切换或登录到对应企业账号后，LaunchedEffect 会再次尝试。
            context?.toast(trf("此链接属于 {0}，请切换或登录对应企业账号", enterpriseHost))
            return
        }
        state.remove<String>("pendingUrl")
        pendingUrl = null
        val destination = com.mobilegh.nav.WidgetRoutes.resolve(url, com.mobilegh.data.HomePrefs.layout(), Session.login.orEmpty())
        if (destination != null) {
            nav.tab = destination.tab
            destination.screen?.let(nav::push)
            return
        }
        val target = Links.fromInput(url, com.mobilegh.data.Net.apiHost) ?: return
        nav.pushLink(target)
    }

    override fun onCleared() {
        current.dispose()
    }
}

// FragmentActivity：androidx.biometric 的验证弹窗需要它
class MainActivity : FragmentActivity() {
    companion object {
        // 升级前可能已经有多个主页面实例；只在全部不可见时停止监控。
        private var startedActivities = 0
    }

    private val vm: NavVm by viewModels()

    override fun onStart() {
        super.onStart()
        if (startedActivities++ == 0) AppLock.onForeground()
        Downloads.setForeground(true)
        com.mobilegh.data.BackgroundNotificationSettings.setForeground(true)
        com.mobilegh.data.MobileApprovalMonitor.setForeground(this, true)
    }

    override fun onStop() {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        Downloads.setForeground(startedActivities > 0)
        com.mobilegh.data.BackgroundNotificationSettings.setForeground(startedActivities > 0)
        com.mobilegh.data.MobileApprovalMonitor.setForeground(this, startedActivities > 0)
        // 旋转、切换深浅色等重建不算离开应用
        if (startedActivities == 0 && !isChangingConfigurations) AppLock.onBackground()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        updateSecureFlags()
    }

    override fun onPause() {
        // Android 13 以下没有 setRecentsScreenshotEnabled：开启应用锁时离开前加 FLAG_SECURE，最近任务里不显示内容
        if (AppLock.enabled && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        super.onPause()
    }

    private fun updateSecureFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(!AppLock.enabled)
        if (AppLock.locked) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val nav = vm.navFor(Session.generation)
            // 栈或 Tab 变化时写入 SavedStateHandle，进程被系统回收后据此恢复。
            // 这里必须同步写：切到后台紧接着 onSaveInstanceState，异步编码会存下上一个页面。
            // 编码本身很快，序列化器由 ScreenCodec 复用，构造开销已在启动时挪到后台线程。
            LaunchedEffect(nav) {
                snapshotFlow { NavRestore.encode(nav.tab, nav.stack.map { it.screen }, Session.login) }
                    .distinctUntilChanged()
                    .collect { vm.saveNav(it) }
            }
            LaunchedEffect(Session.generation, vm.pendingUrl, vm.pendingShare) { vm.openPending(nav, this@MainActivity) }
            // 登录、切换账号、退出登录后刷新桌面小组件
            LaunchedEffect(Session.generation) { Widgets.update(applicationContext) }
            LaunchedEffect(AppLock.enabled, AppLock.locked) { updateSecureFlags() }
            MobileGhTheme {
                AppRoot(nav)
                // 锁定时不弹出询问，解锁后再问
                if (!AppLock.locked) LinkDefaultPrompt()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.dataString
        if (uri != null && com.mobilegh.data.MobileApprovalOAuth.handles(uri)) {
            com.mobilegh.data.MobileApprovalOAuth.complete(this, uri)
            return
        }
        if (intent?.getBooleanExtra(com.mobilegh.data.MobileApprovalNotifications.EXTRA_OPEN, false) == true) {
            val id = intent.getIntExtra(com.mobilegh.data.MobileApprovalNotifications.EXTRA_REQUEST, -1)
            if (id > 0) com.mobilegh.data.MobileApprovalMonitor.openNotification(this, id)
            else vm.navFor(Session.generation).push(com.mobilegh.nav.Screen.Authenticator)
            return
        }
        if (intent?.action == WidgetIntents.ACTION) {
            // 桌面小组件：切到对应 Tab，点按的是某条通知时再打开该会话
            val nav = vm.navFor(Session.generation)
            nav.popToRoot()
            intent.getStringExtra(WidgetIntents.EXTRA_TAB)?.let { name -> Tab.entries.firstOrNull { it.name == name } }?.let { nav.tab = it }
            intent.getStringExtra(WidgetIntents.EXTRA_URL)?.let(vm::receive)
            return
        }
        val link = IncomingLinks.fromIntent(intent, com.mobilegh.data.Net.apiHost)
        val url = link?.url ?: IncomingLinks.enterpriseUrl(intent?.action, intent?.dataString)
        if (url != null) vm.receive(url)
        else if (intent?.action == Intent.ACTION_SEND || intent?.action == Intent.ACTION_SEND_MULTIPLE) {
            // content:// 的临时读取授权只在本 Activity 生命周期可靠，先复制到专用缓存再进入导航。
            lifecycleScope.launch {
                try {
                    val id = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.mobilegh.nav.ShareIntents.import(this@MainActivity, intent)
                    }
                    if (id != null) vm.receiveShare(id) else toast(tr("分享内容无法用于创建 Issue"))
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (e: Exception) {
                    toast(e.message ?: tr("无法读取分享内容"))
                }
            }
        }
    }
}
