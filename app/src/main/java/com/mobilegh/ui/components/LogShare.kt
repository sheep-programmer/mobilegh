package com.mobilegh.ui.components

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.mobilegh.data.AppLog
import com.mobilegh.data.LogExport
import com.mobilegh.i18n.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 日志内容只写入 EXTRA_STREAM 指向的 .log 文件，不再塞进有长度限制的 EXTRA_TEXT。 */
fun Context.shareLog(scope: CoroutineScope, kind: String, content: suspend () -> String) {
    scope.launch {
        try {
            val file = withContext(Dispatchers.IO) {
                LogExport.write(File(cacheDir, "log-exports"), kind, content())
            }
            val uri = FileProvider.getUriForFile(this@shareLog, packageName + ".fileprovider", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply { clipData = ClipData.newRawUri(tr("分享日志"), uri) }
            startActivity(Intent.createChooser(send, tr("分享日志")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            AppLog.warn("app", tr("日志文件导出失败"), e)
            toast(tr("日志导出失败：") + (e.message ?: e.javaClass.simpleName))
        }
    }
}
