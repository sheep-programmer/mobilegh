package com.mobilegh.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/** Keep signing and user confirmation in the already registered GitHub Mobile app. */
object GitHubCompanion {
    const val PACKAGE = "com.github.android"
    const val STORE_URL = "https://play.google.com/store/apps/details?id=com.github.android"

    data class Availability(val installed: Boolean, val launchable: Boolean, val version: String? = null)

    fun availability(context: Context): Availability {
        return try {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(PACKAGE, 0)
            Availability(true, context.packageManager.getLaunchIntentForPackage(PACKAGE) != null, info.versionName)
        } catch (_: PackageManager.NameNotFoundException) {
            Availability(false, false)
        } catch (_: SecurityException) {
            Availability(false, false)
        }
    }

    /** No request ids, challenge digits, tokens or private Activity extras leave MobileGH. */
    fun open(context: Context): String? {
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE)
            ?: return "未找到可打开的 GitHub Mobile，请先安装并登录官方 App"
        return try {
            // The package manager supplies the app's exported launcher. Don't guess private routes.
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            AppLog.info("auth", "已打开 GitHub Mobile；等待用户在官方 App 完成验证")
            null
        } catch (_: ActivityNotFoundException) {
            "GitHub Mobile 的入口不可用，请更新或重新启用官方 App"
        } catch (_: SecurityException) {
            "系统阻止打开 GitHub Mobile，请检查官方 App 是否已启用"
        }
    }

    fun openStore(context: Context): Boolean {
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PACKAGE"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
