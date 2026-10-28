package com.mobilegh.data

import okio.Buffer
import org.junit.Test
import java.io.File

/**
 * 把**生产代码** [GitPack.ReceiveBody] 写出的请求体原样落盘，由外部脚本 POST 到
 * `https://github.com/<owner>/<repo>.git/git-receive-pack`。单测能证明「对象头与 pack 的字节
 * 和真 git 一致」，但服务端认不认这一整个请求体（trailer 只覆盖 pack 字节、指令行必须带
 * NUL + report-status），只有真发一次才算数。脚本不入库：它需要凭据和一个一次性测试仓库。
 *
 * 单测能证明「对象头与 pack 和真 git 逐字节相同」，但服务端认不认这一整个请求体
 * （trailer 只覆盖 pack 字节、指令行要带 NUL + report-status），只有真发一次才算数。
 *
 * 只在设置 E2E_OUT 时执行；平时跑测试不写文件、不联网。
 * E2E_BIG 指定一个大文件路径时，请求体里只带这一个对象，用来验证 >40 MiB 这条 REST 走不通的路。
 */
class GitPackBodyE2ETest {
    @Test fun dumpProductionBodyWhenAsked() {
        val out = System.getenv("E2E_OUT") ?: return
        val oid = System.getenv("E2E_OID") ?: error("需要 E2E_OID")
        val ref = System.getenv("E2E_REF") ?: "refs/heads/main"
        val command = GitPack.updateCommand(oid, oid, ref)

        val bigPath = System.getenv("E2E_BIG")
        val objects = if (bigPath != null) {
            val f = File(bigPath)
            val bytes = f.readBytes()
            listOf(GitUpload.blobSha(bytes) to PackObject(GitPack.TYPE_BLOB, bytes.size.toLong()) { f.inputStream() })
        } else {
            val text = "uploaded by MobileGH git-pack path\n".toByteArray()
            val script = "#!/bin/sh\necho hi\n".toByteArray()
            val pseudo = ByteArray(4 * 1024 * 1024 + 7)
            var s = 0x1234567L
            for (i in pseudo.indices) {
                s = (s * 6364136223846793005L + 1442695040888963407L) and Long.MAX_VALUE
                pseudo[i] = ((s ushr 33) and 0xFF).toByte()
            }
            listOf(text, script, pseudo).map { d ->
                GitUpload.blobSha(d) to PackObject(GitPack.TYPE_BLOB, d.size.toLong()) { d.inputStream() }
            }
        }

        val body = GitPack.ReceiveBody(command, objects) { _, _ -> }.let { b ->
            Buffer().also { b.writeTo(it) }.readByteArray()
        }
        File(out).writeBytes(body)
        File("$out.sha").writeText(objects.joinToString("\n") { "${it.first}\t${it.second.size}" } + "\n")
    }
}
