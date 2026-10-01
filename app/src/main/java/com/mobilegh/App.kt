package com.mobilegh

import android.app.Application
import com.mobilegh.data.Api
import com.mobilegh.data.AppLog
import com.mobilegh.data.Downloads
import com.mobilegh.data.Net
import com.mobilegh.data.Session

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Api.cacheDir = cacheDir
        AppLog.init(this)
        Net.init(this)
        Session.init(this)
        Downloads.init(this)
        // 每次启动静默测速，自动选择最快的加速节点
        Net.speedTestAsync()
    }
}
