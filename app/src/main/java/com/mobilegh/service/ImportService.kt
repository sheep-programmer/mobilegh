package com.mobilegh.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mobilegh.MainActivity
import com.mobilegh.R
import com.mobilegh.data.GitRepositoryImporter
import com.mobilegh.data.IMPORT_PUSH_PROGRESS_TASK
import com.mobilegh.data.ImportCredentialsProvider
import com.mobilegh.data.ImportPhase
import com.mobilegh.data.ImportSourceCredentials
import com.mobilegh.data.ImportTask
import com.mobilegh.data.ImportTasks
import com.mobilegh.data.ImportTransferResult
import com.mobilegh.data.RepositoryImportCoordinator
import com.mobilegh.data.Session
import com.mobilegh.i18n.tr
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.lib.ProgressMonitor

fun interface ImportTransfer {
    suspend fun run(
        task: ImportTask,
        sourceCredentials: ImportSourceCredentials?,
        destinationToken: String,
        workspace: File,
        progress: ProgressMonitor,
    ): ImportTransferResult
}

object JGitImportTransfer : ImportTransfer {
    override suspend fun run(
        task: ImportTask,
        sourceCredentials: ImportSourceCredentials?,
        destinationToken: String,
        workspace: File,
        progress: ProgressMonitor,
    ): ImportTransferResult {
        val sourceProvider = sourceCredentials
            ?.takeIf { it.password.isNotEmpty() || it.token.isNotEmpty() }
            ?.let {
                ImportCredentialsProvider(
                    username = it.username.ifBlank { "git" },
                    password = it.password.ifEmpty { it.token },
                )
            }
        return GitRepositoryImporter().cloneAndPush(
            task = task,
            sourceCredentials = sourceProvider,
            destinationCredentials = ImportCredentialsProvider("x-access-token", destinationToken),
            workspace = workspace,
            progress = progress,
        )
    }
}

class ImportExecutor(
    private val tasks: ImportTasks,
    private val coordinator: RepositoryImportCoordinator =
        RepositoryImportCoordinator(tasks),
    private val transfer: ImportTransfer = JGitImportTransfer,
) {
    suspend fun execute(
        id: String,
        accountLogin: String,
        destinationToken: String,
        sourceCredentials: ImportSourceCredentials? = null,
        progress: ProgressMonitor = NullProgressMonitor.INSTANCE,
    ): ImportTask {
        val original = requireNotNull(tasks.get(id, accountLogin)) {
            "Import task does not belong to this account"
        }
        if (original.sourceRequiresCredentials) {
            require(sourceCredentials != null &&
                (sourceCredentials.password.isNotEmpty() || sourceCredentials.token.isNotEmpty())
            ) { "Source credentials are required to resume this import" }
        }
        try {
            val prepared = coordinator.resume(id, destinationToken)
            // 目标仓库已创建后，失败或取消的任务必须能再次执行传输；之前把 Failed 也拦在这里，
            // 界面上的「重试」对失败任务就成了空操作。CreationUnknown 仍需先做对账，不能直接重传。
            if (prepared.destination == null || prepared.phase == ImportPhase.CreationUnknown) {
                return prepared
            }
            val active = tasks.update(id, accountLogin) {
                it.copy(phase = ImportPhase.Cloning, message = null)
            }
            val workspace = tasks.prepareWorkspace(id)
            val result = transfer.run(
                active,
                sourceCredentials,
                destinationToken,
                workspace,
                progress,
            )
            val rejectedRefs = result.refs.filter { ref ->
                ref.status == com.mobilegh.data.ImportRefStatus.Rejected
            }
            val rejectedMessage = rejectedRefs.joinToString("\n") { ref ->
                listOfNotNull(ref.ref, ref.message).joinToString(": ")
            }
            if (rejectedRefs.any { it.ref.startsWith("refs/heads/") }) {
                return tasks.update(id, accountLogin) {
                    it.copy(phase = ImportPhase.Failed, message = rejectedMessage)
                }
            }
            val transferredDefaultBranch = result.sourceDefaultBranch?.takeIf { branch ->
                result.refs.any { ref ->
                    ref.ref == "refs/heads/$branch" &&
                        (ref.status == com.mobilegh.data.ImportRefStatus.Pushed ||
                            ref.status == com.mobilegh.data.ImportRefStatus.UpToDate)
                }
            }
            val completed = coordinator.updateDefaultBranch(id, transferredDefaultBranch, destinationToken)
            if (completed.phase != ImportPhase.Completed || rejectedRefs.isEmpty()) return completed
            return tasks.update(id, accountLogin) { it.copy(message = rejectedMessage) }
        } catch (cancelled: CancellationException) {
            // 协调器已标记“创建结果未知”，不能再用取消态覆盖掉可 reconciliation 的证据。
            if (tasks.get(id, accountLogin)?.phase != ImportPhase.CreationUnknown) {
                tasks.cancel(id)
            }
            throw cancelled
        } catch (error: Exception) {
            return tasks.update(id, accountLogin) {
                it.copy(
                    phase = ImportPhase.Failed,
                    message = sanitizeImportError(
                        error.message ?: error.javaClass.simpleName,
                        destinationToken,
                        sourceCredentials?.password.orEmpty(),
                        sourceCredentials?.token.orEmpty(),
                    ),
                )
            }
        } finally {
            tasks.workspace(id).deleteRecursively()
        }
    }
}

internal fun sanitizeImportError(message: String, vararg secrets: String): String {
    var sanitized = message.take(1000)
    secrets.filter { it.isNotEmpty() }.forEach { secret ->
        sanitized = sanitized.replace(secret, "[redacted]")
    }
    return sanitized.replace(Regex("://[^/@\\s]+@"), "://")
}

/** 单次导入执行的抽象：生产走 [ImportExecutor]，测试可替换为记录型实现。 */
fun interface ImportExecution {
    suspend fun execute(
        id: String,
        accountLogin: String,
        destinationToken: String,
        sourceCredentials: ImportSourceCredentials?,
        progress: ProgressMonitor,
    ): ImportTask
}

interface ImportUiActions {
    fun start(context: Context, id: String, sourceCredentials: ImportSourceCredentials?)
    fun cancel(context: Context, id: String)
    fun isActive(id: String): Boolean
}

/**
 * 源仓库凭据的一次性内存通道。Intent、任务 JSON、savedInstanceState 一律不携带凭据；
 * [AndroidImportUiActions.start] 写入，[ImportService] 取出即删。
 */
object PendingImportCredentials {
    private val pending = ConcurrentHashMap<String, ImportSourceCredentials>()

    fun put(id: String, credentials: ImportSourceCredentials) {
        pending[id] = credentials
    }

    fun take(id: String): ImportSourceCredentials? = pending.remove(id)

    fun isEmpty(): Boolean = pending.isEmpty()

    fun clear() = pending.clear()
}

object AndroidImportUiActions : ImportUiActions {
    const val ACTION_START = "com.mobilegh.service.action.IMPORT_START"
    const val ACTION_CANCEL = "com.mobilegh.service.action.IMPORT_CANCEL"
    const val EXTRA_TASK_ID = "task_id"
    private val requestedStarts = ConcurrentHashMap<String, Int>()

    override fun start(context: Context, id: String, sourceCredentials: ImportSourceCredentials?) {
        requestedStarts.merge(id, 1, Int::plus)
        if (sourceCredentials != null) PendingImportCredentials.put(id, sourceCredentials)
        val intent = Intent(context, ImportService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_TASK_ID, id)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (_: Exception) {
            // 无论共享 runtime 是否存在，都必须取走一次性明文凭据。
            val credentials = PendingImportCredentials.take(id)
            val runtime = ImportServices.runtime
            if (runtime != null) runtime.start(id, credentials) else startHandled(id)
        }
    }

    override fun isActive(id: String): Boolean =
        requestedStarts.containsKey(id) || ImportServices.runtime?.isActive(id) == true

    internal fun startHandled(id: String) {
        requestedStarts.compute(id) { _, count ->
            if (count == null || count <= 1) null else count - 1
        }
    }

    override fun cancel(context: Context, id: String) {
        val intent = Intent(context, ImportService::class.java)
            .setAction(ACTION_CANCEL)
            .putExtra(EXTRA_TASK_ID, id)
        try {
            context.startService(intent)
        } catch (_: Exception) {
            ImportServices.runtime?.cancel(id)
        }
    }
}

/** 每任务单 Job 的去重执行器；绑定当前 Session，账号隔离由 [ImportTasks.get] 保证。 */
class ImportRuntime(
    private val tasks: ImportTasks,
    private val execution: ImportExecution = ImportExecution { id, accountLogin, destinationToken, sourceCredentials, progress ->
        ImportExecutor(tasks).execute(id, accountLogin, destinationToken, sourceCredentials, progress)
    },
    private val onIdle: () -> Unit = {},
    private val onFinished: (ImportTask) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val cancelled = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    val hasActive: Boolean get() = jobs.values.any { it.isActive }

    fun isActive(id: String): Boolean = jobs[id]?.isActive == true

    fun start(id: String, sourceCredentials: ImportSourceCredentials?) {
        try {
            val login = Session.login ?: return
            if (tasks.get(id, login) == null) return
            if (jobs[id]?.isActive == true) return
            val destinationToken = Session.token ?: return
            cancelled.remove(id)
            var jobRef: Job? = null
            val progressLock = Any()
            var taskTotalWork = 0
            var taskCompletedWork = 0
            var lastPercent = -1
            fun reportProgress(force: Boolean = false) {
                val percent = if (taskTotalWork > 0) taskCompletedWork * 100 / taskTotalWork else taskCompletedWork
                if (!force && percent == lastPercent) return
                lastPercent = percent
                val progressText = if (taskTotalWork > 0) {
                    "$taskCompletedWork/$taskTotalWork"
                } else {
                    taskCompletedWork.toString()
                }
                tasks.update(id, login) { it.copy(message = "${tr("已处理对象")}: $progressText") }
            }
            val monitor = object : ProgressMonitor {
                override fun start(totalTasks: Int) {}
                override fun beginTask(title: String?, totalWork: Int) {
                    synchronized(progressLock) {
                        if (title == IMPORT_PUSH_PROGRESS_TASK) {
                            tasks.update(id, login) { it.copy(phase = ImportPhase.Pushing, message = null) }
                        }
                        taskTotalWork = totalWork
                        taskCompletedWork = 0
                        lastPercent = -1
                        // JGit 标题可能含 URL 或服务器文本，只允许内部阶段标记和数字进入任务记录。
                        reportProgress(force = true)
                    }
                }
                override fun update(completed: Int) {
                    synchronized(progressLock) {
                        taskCompletedWork += completed
                        reportProgress()
                    }
                }
                override fun endTask() {}
                override fun showDuration(enabled: Boolean) {}
                override fun isCancelled(): Boolean = cancelled.contains(id) || jobRef?.isActive == false
            }
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    execution.execute(id, login, destinationToken, sourceCredentials, monitor)
                } catch (ce: CancellationException) {
                    runCatching {
                        if (tasks.get(id)?.phase != ImportPhase.CreationUnknown) tasks.cancel(id)
                    }
                    throw ce
                }
            }
            jobRef = job
            jobs[id] = job
            job.invokeOnCompletion {
                jobs.remove(id, job)
                tasks.get(id)?.let(onFinished)
                if (jobs.isEmpty()) onIdle()
            }
            job.start()
        } finally {
            AndroidImportUiActions.startHandled(id)
        }
    }

    fun cancel(id: String) {
        val job = jobs[id]
        cancelled.add(id)
        if (job?.isActive == true) {
            job.cancel()
        }
    }
}

object ImportServices {
    @Volatile var runtime: ImportRuntime? = null
    var tasks: ImportTasks? = null
        private set

    fun init(context: Context) {
        val app = context.applicationContext
        val store = ImportTasks(File(app.filesDir, "import-tasks"))
        runCatching { store.recoverActive() }
        tasks = store
        runtime = ImportRuntime(
            tasks = store,
            onIdle = { app.stopService(Intent(app, ImportService::class.java)) },
            onFinished = { task -> ImportService.notifyFinished(app, task) },
        )
    }
}

/** 导入前台服务：API-aware startForeground，任务结束后自动停止。 */
class ImportService : Service() {
    override fun onCreate() {
        super.onCreate()
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runtime = ImportServices.runtime
        when (intent?.action) {
            AndroidImportUiActions.ACTION_START -> {
                val notification = ongoing(this)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(ONGOING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(ONGOING_ID, notification)
                }
                val id = intent.getStringExtra(AndroidImportUiActions.EXTRA_TASK_ID)
                if (id != null && runtime != null) {
                    runtime.start(id, PendingImportCredentials.take(id))
                }
            }
            AndroidImportUiActions.ACTION_CANCEL -> {
                intent.getStringExtra(AndroidImportUiActions.EXTRA_TASK_ID)?.let { runtime?.cancel(it) }
            }
        }
        if (runtime == null || !runtime.hasActive) stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ONGOING = "imports_progress"
        const val CHANNEL_DONE = "imports_done"
        const val ONGOING_ID = 7301
        private val doneId = AtomicInteger(7400)

        fun createChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ONGOING, tr("仓库导入"), NotificationManager.IMPORTANCE_LOW)
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_DONE, tr("仓库导入结果"), NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        fun ongoing(context: Context): Notification = NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.oc_repo_push)
            .setContentTitle(tr("正在导入仓库"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
            .build()

        @SuppressLint("MissingPermission")
        fun notifyFinished(context: Context, task: ImportTask) {
            if (task.phase != ImportPhase.Completed && task.phase != ImportPhase.Failed) return
            createChannels(context)
            val title = if (task.phase == ImportPhase.Completed) tr("导入完成") else tr("导入失败")
            val notification = NotificationCompat.Builder(context, CHANNEL_DONE)
                .setSmallIcon(R.drawable.oc_repo_push)
                .setContentTitle(title)
                .setContentText("${task.owner}/${task.name}")
                .setAutoCancel(true)
                .setContentIntent(openApp(context))
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(doneId.incrementAndGet(), notification) }
        }
    }
}
