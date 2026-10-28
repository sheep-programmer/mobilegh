package com.mobilegh.data

import okio.Buffer
import org.junit.Test
import java.io.File

/**
 * 不是断言测试：把**生产代码**为空仓库起步准备好的那个 pack（blob + 本地构造的 tree/commit）
 * 原样写成请求体落盘，交给脚本真的发到 git-receive-pack。
 *
 * 需要这一步的原因：GitTreeTest 只证明「本地算出的树 sha 与真 git 一致」，
 * 证明不了 GitHub 会接受 `ZERO <commit> refs/heads/<分支>` 这条建分支指令，
 * 也证明不了 ReceiveBody 能把 tree / commit 这类非 blob 对象完整送达。
 *
 * 关键是这里调的就是上传时那段 `GitUpload.planBootstrap`，不是测试里另抄一份构造逻辑——
 * 抄的那份通了不等于生产那份通了。
 *
 * 只在设置 E2E_BOOT / E2E_SRC 时执行；平时跑测试不写文件、不联网。
 */
class GitBootstrapE2ETest {
    private class Spec(val path: String, val text: String)

    @Test fun dumpBootstrapPackWhenAsked() {
        val out = System.getenv("E2E_BOOT") ?: return
        val root = File(System.getenv("E2E_SRC") ?: return)
        val branch = System.getenv("E2E_BRANCH") ?: "main"

        // 权限位不注入：让生产代码自己去推断（shebang / 后缀），正好验一次 100755 推得对不对
        val specs = listOf(
            Spec("README.md", "# bootstrapped by MobileGH\n"),
            Spec("docs/a/deep.txt", "deep content\n"),
            Spec("scripts/run.sh", "#!/bin/sh\necho hi\n"),
        )
        val files = specs.map { s ->
            val f = File(root, s.path)
            f.parentFile?.mkdirs()
            f.writeText(s.text)
            UploadItem(f.absolutePath, s.path.substringAfterLast('/'), s.path, f.length())
        }
        val open: (UploadItem) -> java.io.InputStream? =
            { runCatching { File(it.uri).inputStream() }.getOrNull() }

        val plan = kotlinx.coroutines.runBlocking {
            GitUpload.planBootstrap(files, "Initial commit via MobileGH", open)
        }
        val body = GitPack.ReceiveBody(GitPack.createCommand(branch, plan.commitSha), plan.objects) { _, _ -> }
            .let { b -> Buffer().also { b.writeTo(it) }.readByteArray() }

        File(out).writeBytes(body)
        // 只交核对需要的最小信息：期望的根提交 sha，以及本地算出的 blob sha（脚本比对仓库内容用）
        File("$out.sha").writeText(buildString {
            append("commit\t").append(plan.commitSha).append('\n')
            append("written\t").append(plan.written).append('\n')
            plan.objects.filter { it.second.type == GitPack.TYPE_BLOB }.forEach { append("blob\t").append(it.first).append('\n') }
            plan.objects.filter { it.second.type == GitPack.TYPE_COMMIT }.forEach { append("commitObj\t").append(it.first).append('\n') }
        })
    }
}
