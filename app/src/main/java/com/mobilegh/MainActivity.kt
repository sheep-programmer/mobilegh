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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mobilegh.data.AppLock
import com.mobilegh.data.Session
import com.mobilegh.data.Downloads
import com.mobilegh.nav.Links
import com.mobilegh.nav.Navigator
import com.mobilegh.nav.IncomingLinks
import com.mobilegh.nav.Tab
import com.mobilegh.widget.WidgetIntents
import com.mobilegh.widget.Widgets
import com.mobilegh.ui.components.toast
import com.mobilegh.ui.AppRoot
import com.mobilegh.ui.theme.MobileGhTheme
import com.mobilegh.i18n.tr

class NavVm(private val state: SavedStateHandle) : ViewModel() {
    var pendingUrl by mutableStateOf(state.get<String>("pendingUrl"))
        private set
    private var gen = -1
    private var current = Navigator()

    fun navFor(generation: Int): Navigator {
        if (generation != gen) {
            current.dispose()
            current = Navigator()
            gen = generation
        }
        return current
    }

    fun receive(url: String) {
        state["pendingUrl"] = url
        pendingUrl = url
    }

    fun openPending(nav: Navigator) {
        if (Session.token == null) return
        val url = pendingUrl ?: return
        state.remove<String>("pendingUrl")
        pendingUrl = null
        val target = Links.fromInput(url) ?: return
        if (nav.top.screen != target) nav.push(target)
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
        com.mobilegh.data.MobileApprovalMonitor.setForeground(this, true)
    }

    override fun onStop() {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        Downloads.setForeground(startedActivities > 0)
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
            LaunchedEffect(Session.generation, vm.pendingUrl) { vm.openPending(nav) }
            // 登录、切换账号、退出登录后刷新桌面小组件
            LaunchedEffect(Session.generation) { Widgets.update(applicationContext) }
            LaunchedEffect(AppLock.enabled, AppLock.locked) { updateSecureFlags() }
            MobileGhTheme {
                AppRoot(nav)
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
        val link = IncomingLinks.fromIntent(intent)
        if (link != null) vm.receive(link.url)
        else if (intent?.action == Intent.ACTION_SEND) toast(tr("分享内容中没有可打开的 GitHub 链接"))
    }
}
