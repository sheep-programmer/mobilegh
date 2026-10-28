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

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        com.mobilegh.i18n.I18n.init(this)
        Api.cacheDir = cacheDir
        AppLog.init(this)
        com.mobilegh.data.AppNetwork.init(this)
        Net.init(this)
        Session.init(this)
        AppLock.init(this)
        WidgetStore.init(this)
        com.mobilegh.data.NotificationStore.init(this)
        WidgetStore.listener = { kind -> Widgets.update(this, kind) }
        com.mobilegh.data.MobileApprovalMonitor.reload(this)
        History.init(this)
        com.mobilegh.data.HomePrefs.init(this)
        Downloads.init(this)
        // 启动后台静默检查一次新版本（走加速节点，失败不影响使用）
        Update.checkQuietly()
        // 每次启动静默测速，自动选择最快的加速节点
        Net.speedTestAsync()
    }
}
