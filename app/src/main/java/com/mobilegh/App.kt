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
import com.mobilegh.service.ImportServices
import android.os.Handler
import android.os.Looper
import android.view.Choreographer

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        com.mobilegh.i18n.I18n.init(this)
        // 日志与崩溃处理器尽早安装：之后的初始化出错也能留下记录
        AppLog.init(this)
        // 导航栈序列化器在后台线程先建好：它要构造 75 个页面类型的描述符，
        // 首次在主线程上做会卡住冷启动（卡顿看门狗实测抓到过 5 秒）
        Thread({ com.mobilegh.nav.ScreenCodec.warmUp() }, "screen-codec-warmup").apply { isDaemon = true }.start()
        // Remove translations left by versions that cached document text on disk.
        Thread({
            runCatching { java.io.File(noBackupFilesDir, "translated-paragraphs").deleteRecursively() }
        }, "translation-cache-cleanup").apply { isDaemon = true }.start()
        Api.cacheDir = cacheDir
        com.mobilegh.data.ImageFetch.init(cacheDir)
        com.mobilegh.data.AppNetwork.init(this)
        Net.init(this)
        com.mobilegh.data.OfflineTranslation.init(this)
        Session.init(this)
        AppLock.init(this)
        WidgetStore.init(this)
        com.mobilegh.data.NotificationStore.init(this)
        com.mobilegh.data.BackgroundNotificationSettings.init(this)
        WidgetStore.listener = { kind -> Widgets.update(this, kind) }
        com.mobilegh.data.MobileApprovalMonitor.reload(this)
        History.init(this)
        com.mobilegh.data.SavedItems.init(this)
        com.mobilegh.data.HomePrefs.init(this)
        Update.init(this)
        LinkDefault.init(this)
        Downloads.init(this)
        // 仓库导入：共享 runtime + 启动时把上次中断的任务标记为可重试
        ImportServices.init(this)
        // 成就缓存与网页连接需要在 Web 初始化后使用
        com.mobilegh.data.Web.init(this)
        scheduleDeferredStartupWork()
    }

    /** Keep optional maintenance work away from the first rendered frame. */
    private fun scheduleDeferredStartupWork() {
        val main = Handler(Looper.getMainLooper())
        Choreographer.getInstance().postFrameCallback {
            main.post {
                com.mobilegh.service.NotificationPollingWorker.sync(this)
                Update.removeInstalledPackage(this)
                Update.checkQuietly()
                Net.speedTestAsync()
                com.mobilegh.data.Web.warmUp()
                com.mobilegh.data.WebAttachmentUploader.renewSessionAsync()
                com.mobilegh.data.HomePrefs.warmFavoritesAsync()
            }
        }
    }
}
