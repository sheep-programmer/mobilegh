package com.mobilegh.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.mobilegh.R
import com.mobilegh.data.AppLog
import com.mobilegh.data.GitHub
import com.mobilegh.data.Session
import com.mobilegh.data.WidgetStore
import com.mobilegh.i18n.tr
import com.mobilegh.widget.Widgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles quick actions directly from GitHub inbox notifications:
 * - Mark thread as read
 * - Inline quick reply on Issue / PullRequest notifications
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val threadId = intent.getStringExtra(EXTRA_THREAD_ID)?.takeIf { it.isNotBlank() } ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, threadId.hashCode())
        val action = intent.action ?: return
        val replyText = if (action == ACTION_QUICK_REPLY) {
            RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY_TEXT)?.toString()?.trim()
        } else null
        if (action == ACTION_QUICK_REPLY && replyText.isNullOrBlank()) return

        val appCtx = context.applicationContext
        val pending: PendingResult? = goAsync()
        scope.launch {
            try {
                Session.init(appCtx)
                when (action) {
                    ACTION_MARK_READ -> {
                        GitHub.markRead(threadId)
                        NotificationManagerCompat.from(appCtx).cancel(notifId)
                        Widgets.update(appCtx, WidgetStore.Kind.Inbox)
                    }
                    ACTION_QUICK_REPLY -> {
                        val owner = intent.getStringExtra(EXTRA_OWNER).orEmpty()
                        val repo = intent.getStringExtra(EXTRA_REPO).orEmpty()
                        val number = intent.getIntExtra(EXTRA_NUMBER, 0)
                        if (owner.isNotBlank() && repo.isNotBlank() && number > 0 && !replyText.isNullOrBlank()) {
                            GitHub.addComment(owner, repo, number, replyText)
                            runCatching { GitHub.markRead(threadId) }
                            NotificationManagerCompat.from(appCtx).cancel(notifId)
                            Widgets.update(appCtx, WidgetStore.Kind.Inbox)
                        }
                    }
                }
            } catch (t: Throwable) {
                AppLog.warn("notify", "Notification action $action failed", t)
                if (action == ACTION_QUICK_REPLY) {
                    @Suppress("MissingPermission")
                    runCatching {
                        NotificationManagerCompat.from(appCtx).notify(
                            notifId,
                            NotificationCompat.Builder(appCtx, "github_inbox_updates")
                                .setSmallIcon(R.drawable.oc_bell)
                                .setContentTitle(tr("快捷回复失败"))
                                .setContentText(t.message ?: tr("请打开应用重试"))
                                .setAutoCancel(true)
                                .build(),
                        )
                    }
                }
            } finally {
                pending?.finish()
            }
        }
    }

    companion object {
        const val ACTION_MARK_READ = "com.mobilegh.action.NOTIFY_MARK_READ"
        const val ACTION_QUICK_REPLY = "com.mobilegh.action.NOTIFY_QUICK_REPLY"
        const val EXTRA_THREAD_ID = "thread_id"
        const val EXTRA_NOTIF_ID = "notif_id"
        const val EXTRA_OWNER = "owner"
        const val EXTRA_REPO = "repo"
        const val EXTRA_NUMBER = "number"
        const val KEY_REPLY_TEXT = "reply_text"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val subjectPattern = Regex("""^https?://[^/]+/repos/([^/]+)/([^/]+)/(?:issues|pulls)/(\d+)(?:[/?#].*)?$""")

        internal fun parseIssueTarget(subjectUrl: String?, subjectType: String?): Triple<String, String, Int>? {
            if (subjectType !in setOf("Issue", "PullRequest", null, "")) return null
            val match = subjectPattern.matchEntire(subjectUrl?.trim().orEmpty()) ?: return null
            val num = match.groupValues[3].toIntOrNull()?.takeIf { it > 0 } ?: return null
            return Triple(match.groupValues[1], match.groupValues[2], num)
        }

        fun markReadIntent(ctx: Context, threadId: String, notifId: Int): PendingIntent {
            val intent = Intent(ctx, NotificationActionReceiver::class.java)
                .setAction(ACTION_MARK_READ)
                .setData(android.net.Uri.parse("mobilegh://notify-action/read/$threadId"))
                .putExtra(EXTRA_THREAD_ID, threadId)
                .putExtra(EXTRA_NOTIF_ID, notifId)
            return PendingIntent.getBroadcast(
                ctx,
                ("read:$threadId").hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        fun quickReplyAction(ctx: Context, threadId: String, notifId: Int, owner: String, repo: String, number: Int): NotificationCompat.Action {
            val intent = Intent(ctx, NotificationActionReceiver::class.java)
                .setAction(ACTION_QUICK_REPLY)
                .setData(android.net.Uri.parse("mobilegh://notify-action/reply/$threadId"))
                .putExtra(EXTRA_THREAD_ID, threadId)
                .putExtra(EXTRA_NOTIF_ID, notifId)
                .putExtra(EXTRA_OWNER, owner)
                .putExtra(EXTRA_REPO, repo)
                .putExtra(EXTRA_NUMBER, number)
            val mutability = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(
                ctx,
                ("reply:$threadId").hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or mutability,
            )
            val remoteInput = RemoteInput.Builder(KEY_REPLY_TEXT)
                .setLabel(tr("快速回复…"))
                .build()
            return NotificationCompat.Action.Builder(R.drawable.oc_comment, tr("快速回复"), pending)
                .addRemoteInput(remoteInput)
                .setAllowGeneratedReplies(false)
                .build()
        }
    }
}
