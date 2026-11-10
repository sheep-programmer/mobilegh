package com.mobilegh

import android.app.Application
import com.mobilegh.data.Api
import com.mobilegh.data.AppLock
import com.mobilegh.data.WidgetStore
import com.mobilegh.widget.Widgets
import com.mobilegh.data.AppLog
import com.mobilegh.data.Downloads
import com.mobilegh.data.Net
import com.mobilegh.data.Session
import com.mobilegh.data.Update
import com.mobilegh.data.History
import com.mobilegh.data.LinkDefault

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        com.mobilegh.i18n.I18n.init(this)
        // 日志与崩溃处理器尽早安装：之后的初始化出错也能留下记录
        AppLog.init(this)
        Api.cacheDir = cacheDir
        com.mobilegh.data.ImageFetch.init(cacheDir)
        com.mobilegh.data.AppNetwork.init(this)
        Net.init(this)
        Session.init(this)
        AppLock.init(this)
        WidgetStore.init(this)
        com.mobilegh.data.NotificationStore.init(this)
        WidgetStore.listener = { kind -> Widgets.update(this, kind) }
        com.mobilegh.data.MobileApprovalMonitor.reload(this)
        History.init(this)
        com.mobilegh.data.SavedItems.init(this)
        com.mobilegh.data.HomePrefs.init(this)
        Update.init(this)
        LinkDefault.init(this)
        Downloads.init(this)
        // 上次更新的安装包：若已是新版本在运行，删除安装包以省空间
        Update.removeInstalledPackage(this)
        // 启动后台静默检查一次新版本（走加速节点，失败不影响使用）
        Update.checkQuietly()
        // 每次启动静默测速，自动选择最快的加速节点
        Net.speedTestAsync()
        // 成就缓存与 github.com 连接预热：成就页第一次打开不用再等握手
        com.mobilegh.data.Web.init(this)
        com.mobilegh.data.Web.warmUp()
        // 自动静默续期 GitHub 网页会话（防止超过 14 天未使用闲置过期）
        com.mobilegh.data.WebAttachmentUploader.renewSessionAsync()
    }
}
