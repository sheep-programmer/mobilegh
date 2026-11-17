package com.mobilegh.data

import java.io.File

/** 把诊断文本写成独立日志文件，供系统分享面板作为附件发送。 */
object LogExport {
    fun write(directory: File, kind: String, content: String): File {
        check((directory.exists() || directory.mkdirs()) && directory.isDirectory) { "日志导出目录不可用" }
        val file = File.createTempFile("mobilegh-$kind-${System.currentTimeMillis()}-", ".log", directory)
        return try {
            file.writeText(content, Charsets.UTF_8)
            prune(directory, System.currentTimeMillis(), current = file)
            file
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    fun prune(
        directory: File,
        now: Long,
        current: File? = null,
        maxAgeMs: Long = 30 * 24 * 60 * 60 * 1000L,
        maxFiles: Int = 20,
    ) {
        val logs = directory.listFiles { file ->
            file.isFile && file.extension == "log" && file.absoluteFile != current?.absoluteFile
        }.orEmpty().toMutableList()
        // 先清过期文件，再限制数量；刚写入并即将授权的日志永远不参与清理。
        logs.filter { it.lastModified() < now - maxAgeMs }.forEach {
            it.delete()
            logs.remove(it)
        }
        logs.sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name })
            .drop(maxFiles).forEach(File::delete)
    }
}
