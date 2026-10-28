package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * packfile 与 pkt-line 是二进制协议：写错一个字节，GitHub 那边就是整包作废或对象内容不对，
 * 而这些错误在界面上只会变成一句「上传失败」。所以这里对着真 git 的输出逐字节核对。
 *
 * 金样本由 `git pack-objects --stdout` 生成（本地仓库，对象用 `git hash-object -w` 写入），
 * 不是我自己推算的，否则等于自己给自己打分。
 */
class GitPackTest {
    private fun blob(bytes: ByteArray) = PackObject(GitPack.TYPE_BLOB, bytes.size.toLong()) { bytes.inputStream() }

    private val emptyBytes = ByteArray(0)
    private val helloBytes = "hello world\n".toByteArray()
    private val scriptBytes = "#!/bin/sh\nexit 0\n".toByteArray()

    /** git 真包：单个 hello world（sha 3b18e512…），53 字节 */
    private val gitHelloPack =
        "5041434b00000002000000013c789ccb48cdc9c95728cf2fca49e102001e7204675b29736a7a8e498d3ceca7890ac5b7bd1a526071"

    /** git 真包：空对象（e69de29b…），41 字节 */
    private val gitEmptyPack = "5041434b000000020000000130789c0300000000011163065f24157cc2f4576f363464a2e70031eabc"

    /** git 真包：空对象 + 脚本 + hello 三个对象，89 字节 */
    private val gitThreePack =
        "5041434b000000020000000330789c030000000001b101789c5356d44fcaccd32fcee04aadc82c5130e002002bc304d5" +
            "3c789ccb48cdc9c95728cf2fca49e102001e7204677b1e81794734547681baea6d27fff7f75ef8c75b"

    @Test fun objectHeaderMatchesGitByteForByte() {
        // 上面三个真包里对象的头依次是 0x30、0xb1 0x01、0x3c
        assertEquals("30", GitPack.objectHeader(GitPack.TYPE_BLOB, 0L).hex())
        assertEquals("3c", GitPack.objectHeader(GitPack.TYPE_BLOB, 12L).hex())
        assertEquals("b101", GitPack.objectHeader(GitPack.TYPE_BLOB, 17L).hex())
    }

    /**
     * 首字节之后按 **7 位**一组继续，不是 4 位。
     * 期望值全部来自 `git pack-objects --stdout` 生成的真 pack 里切出来的头，
     * 覆盖 0/12/17/250/1000/1MiB/44MiB：写错会在 >=300 字节的文件上静默产出坏对象。
     */
    @Test fun objectHeaderUsesSevenBitContinuation() {
        val git = mapOf(
            0L to "30", 1L to "31", 2L to "32", 11L to "3b", 12L to "3c",
            16L to "b001", 17L to "b101", 127L to "bf07", 128L to "b008",
            250L to "ba0f", 251L to "bb0f", 1000L to "b83e",
            65535L to "bfff1f", 65536L to "b08020",
            1_000_000L to "b0a4e803", 46_137_344L to "b08080b001",
        )
        git.forEach { (size, expected) ->
            assertEquals("长度 $size", expected, GitPack.objectHeader(GitPack.TYPE_BLOB, size).hex())
        }
    }

    @Test fun packHeaderIsMagicVersionAndCount() {
        val h = GitPack.packHeader(3)
        assertEquals("PACK", String(h, 0, 4, Charsets.US_ASCII))
        assertEquals("5041434b0000000200000003", h.hex())
    }

    /** 我写出的包必须和真 git 的字节完全相同，三种长度都核对 */
    @Test fun buildPackEqualsGitGeneratedBytes() {
        assertEquals(gitHelloPack, GitPack.buildPack(listOf(blob(helloBytes))).hex())
        assertEquals(gitEmptyPack, GitPack.buildPack(listOf(blob(emptyBytes))).hex())
        assertEquals(
            gitThreePack,
            GitPack.buildPack(listOf(blob(emptyBytes), blob(scriptBytes), blob(helloBytes))).hex(),
        )
    }

    /** 反方向也要成立：能解真 git 生成的包，说明位置计算不是只对自己的输出凑巧对 */
    @Test fun parsePackReadsGitGeneratedPack() {
        val parsed = GitPack.parsePack(gitThreePack.decodeHex())
        assertEquals(listOf(0, 17, 12), parsed.map { it.size })
        assertTrue(parsed.all { it.type == GitPack.TYPE_BLOB })
        assertEquals("", String(parsed[0].bytes))
        assertEquals("#!/bin/sh\nexit 0\n", String(parsed[1].bytes))
        assertEquals("hello world\n", String(parsed[2].bytes))
    }

    @Test fun buildPackRoundTripsEverySize() {
        val data = listOf(
            ByteArray(0),
            byteArrayOf(0),
            helloBytes,
            ByteArray(16),
            ByteArray(17),
            ByteArray(300) { (it % 7).toByte() },
            ByteArray(70_000) { (it * 31).toByte() },   // 需要多字节长度头 + 压缩后仍大于输入缓冲区
        )
        val parsed = GitPack.parsePack(GitPack.buildPack(data.map { blob(it) }))
        assertEquals(data.size, parsed.size)
        parsed.forEachIndexed { i, o ->
            assertEquals(data[i].size, o.size)
            assertEquals("第 $i 个对象内容不符", data[i].toList(), o.bytes.toList())
        }
    }

    /**
     * 坏包必须当场拒绝，不能当成合法包发出去。两种破坏分别对应两种检查：
     * - 只动 trailer → 校验和不符；
     * - 动内容字节 → deflate 流自己就报坏了（早于校验和）。
     */
    @Test fun corruptPackIsRejected() {
        val pack = GitPack.buildPack(listOf(blob(helloBytes)))
        assertFalse(GitPack.parsePack(pack).isEmpty())

        val badSum = pack.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        val e1 = capture { GitPack.parsePack(badSum) }
        assertTrue(e1.message ?: "", e1.message!!.contains("校验和"))

        val badData = pack.copyOf().also { it[14] = (it[14].toInt() xor 0x01).toByte() }
        val e2 = capture { GitPack.parsePack(badData) }
        assertTrue("内容坏了也要报错，不能悄悄放过：${e2.message}", e2.message!!.contains("解压"))
    }

    @Test fun pktLinePrefixesItsOwnTotalLengthInHex() {
        // 长度包含这 4 个字符自己：4 + 10 = 14 = 0x000e
        assertEquals("000e" + "a".repeat(10), String(GitPack.pktLine("a".repeat(10)), Charsets.ISO_8859_1))
        assertEquals("0008aaaa", String(GitPack.pktLine("aaaa"), Charsets.ISO_8859_1))
        val long = GitPack.pktLine("x".repeat(1000))
        assertEquals(1004, long.size)
        assertEquals("%04x".format(1004), String(long, 0, 4, Charsets.US_ASCII))
        assertEquals("0000", String(GitPack.FLUSH, Charsets.US_ASCII))
    }

    /** 借用指令必须落在服务端确实存在的 ref 上；否则整包请求不合法 */
    @Test fun pickRefOnlyEverReturnsSomethingTheServerAdvertised() {
        val refs = listOf(
            GitPack.Ref("a".repeat(40), "refs/heads/main"),
            GitPack.Ref("b".repeat(40), "refs/heads/dev"),
            GitPack.Ref("c".repeat(40), "refs/tags/v1"),
        )
        assertEquals("refs/heads/dev", GitPack.pickRef(refs, "dev")?.name)
        assertEquals("refs/heads/main", GitPack.pickRef(refs, "main")?.name)
        // 查看标签时 baseRef 是标签名：借标签，oid 用它自己的值，仍然是空操作
        assertEquals("refs/tags/v1", GitPack.pickRef(refs, "v1")?.name)
        // 都不存在就退到任意分支，绝不编一条不存在的 ref
        assertEquals("refs/heads/main", GitPack.pickRef(refs, "nope")?.name)
        assertEquals("b".repeat(40), GitPack.pickRef(refs, "dev")?.oid)
        assertNull(GitPack.pickRef(emptyList(), "main"))
    }

    /** advertise-refs 的真实形状：首行是服务声明，capabilities 行带 NUL，peeled tag 以 ^{} 结尾 */
    @Test fun advertiseParsingSkipsServiceLineCapsAndPeeledTags() {
        val bytes = java.io.ByteArrayOutputStream().apply {
            write(GitPack.pktLine("# service=git-receive-pack"))
            write(GitPack.FLUSH)
            write(GitPack.pktLine("0".repeat(40) + " capabilities^{}"))
            write(GitPack.pktLine("a".repeat(40) + " refs/heads/main"))
            write(GitPack.pktLine("b".repeat(40) + " refs/tags/v1"))
            write(GitPack.pktLine("c".repeat(40) + " refs/tags/v1^{}"))
            write(GitPack.FLUSH)
        }.toByteArray()
        val refs = GitPack.parseAdvertised(String(bytes, Charsets.ISO_8859_1))
        assertEquals(listOf("refs/heads/main", "refs/tags/v1"), refs.map { it.name })
        assertEquals("a".repeat(40), refs[0].oid)
    }

    @Test fun advertiseParsingToleratesGarbage() {
        assertEquals(emptyList<GitPack.Ref>(), GitPack.parseAdvertised(""))
        assertEquals(emptyList<GitPack.Ref>(), GitPack.parseAdvertised("not a pktline"))
        // oid 不合法的行不能拿去当 old 值写指令
        val junk = String(GitPack.pktLine("zzzz not/a/ref"), Charsets.ISO_8859_1)
        assertEquals(0, GitPack.parseAdvertised(junk).size)
    }

    /**
     * 把几行消息编成真实的 pkt-line 回执：每行是 4 位十六进制总长（含这 4 个字符与行尾换行）+ 内容。
     * “unpack ok”(9) → 000e，“ok refs/heads/main”(18) → 0017，与本地用真 git 拓到的一致。
     */
    private fun reportOf(vararg lines: String): String =
        lines.joinToString("") { "%04x".format(it.length + 5) + it + "\n" } + "0000"

    private fun receiptOf(vararg lines: String) = GitPack.Receipt(200, parseGitReport(reportOf(*lines)))

    /**
     * 回执解析。输入必须是**带长度前缀的原始字节**：之前的 bug 就是直接对原始报文做字符串
     * 匹配，`ok refs/heads/main` 看到的是 `0017ok refs/heads/main`，以 `ok ` 开头的判断永远不成立。
     * 只有拿真字节做输入，改回字符串匹配时才会报错。
     */
    @Test fun reportLinesDropThePktLineLengthPrefix() {
        val raw = reportOf("unpack ok", "ok refs/heads/main")
        assertTrue(raw, raw.startsWith("000eunpack ok\n0017ok refs/heads/main\n"))
        assertEquals(listOf("unpack ok", "ok refs/heads/main"), parseGitReport(raw))
    }

    /** 真 git 拓的实际回执带 side-band 通道字节，也要能解 */
    @Test fun reportParsesTheRealCapturedBytes() {
        val real = "\u0001" + reportOf("unpack ok", "ok refs/heads/main")
        assertEquals(listOf("unpack ok", "ok refs/heads/main"), parseGitReport(real))
    }

    @Test fun reportToleratesGarbage() {
        assertEquals(emptyList<String>(), parseGitReport(""))
        assertEquals(listOf("unpack ok"), parseGitReport(reportOf("unpack ok") + "zzzztail"))
    }

    /** 分支到底建出来没有：必须看解析后的行。这一条就是用户看到的「unpack ok 却报失败」 */
    @Test fun refUpdatedReadsTheParsedOkLine() {
        assertTrue(receiptOf("unpack ok", "ok refs/heads/main").refUpdated)
        assertFalse(receiptOf("unpack ok", "ng refs/heads/main cannot lock ref").refUpdated)
    }

    /**
     * 只看 unpack ok 会把失败说成成功：实测 101 MiB 那次 GitHub 回了 unpack ok
     * 但钩子整体拒收，对象并没有落库；反过来借用的 ref 被拒成 cannot lock ref 时对象是落库的。
     */
    /** HTTP 成功但一行也没解出来时，不能说「服务端拒绝（HTTP 200）」这种自相矛盾的话 */
    @Test fun unparsableSuccessSaysRetryInsteadOfContradiction() {
        val weird = GitPack.Receipt(200, emptyList())
        assertTrue(weird.declined)
        val why = weird.failure!!
        assertFalse(why, why.contains("200"))
        assertTrue(why, why.contains("重试"))
        // 真实拒绝仍然要带上状态码，便于对账
        assertTrue(GitPack.Receipt(401, emptyList()).failure!!.contains("401"))
    }

    @Test fun receiptDistinguishesLandedObjectsFromRefComplaints() {
        val ok = receiptOf("unpack ok", "ok refs/heads/main")
        assertTrue(ok.unpackOk)
        assertFalse(ok.declined)
        assertNull(ok.failure)

        // 借用的 ref 被拒不影响对象落库，可以继续
        val lock = receiptOf("unpack ok", "ng refs/heads/main cannot lock ref: is at abc but expected def")
        assertFalse(lock.declined)
        assertNull(lock.failure)

        // 钩子整体拒收：即使回了 unpack ok，对象也没进去
        val hook = receiptOf("unpack ok", "ng refs/heads/main pre-receive hook declined")
        assertTrue(hook.declined)
        // 原因不能是 `unpack ok` 本身，否则会拼出「上传未完成。unpack ok」这种自相矛盾的话
        assertTrue(hook.failure!!, hook.failure!!.contains("pre-receive"))
        assertFalse(hook.failure!!, hook.failure!!.startsWith("unpack ok"))

        assertTrue(receiptOf("unpack index-pack failed", "ng refs/heads/main failed").declined)
        // HTTP 层就失败、没有任何回执时也要给得出原因
        val http = GitPack.Receipt(403, emptyList())
        assertTrue(http.declined)
        assertTrue(http.failure!!, http.failure!!.contains("403"))
    }

    @Test fun objectSizeCapIsTheAdvertisedOne() {
        assertEquals(100L * 1024 * 1024, GitPack.MAX_OBJECT_BYTES)
        assertEquals(GitPack.MAX_OBJECT_BYTES, GitUpload.MAX_FILE_BYTES)
    }

    /** 对象头里的长度必须与实际字节数一致：文件中途被改要当场失败，不能产出内容对不上的对象 */
    @Test fun packRejectsSizeMismatch() {
        val lying = PackObject(GitPack.TYPE_BLOB, 99L) { "short".toByteArray().inputStream() }
        val e = capture { GitPack.buildPack(listOf(lying)) }
        assertTrue(e.message ?: "", e.message!!.contains("改动"))
    }

    @Test fun receiveBodyRejectsSameSizeContentThatDoesNotMatchOid() {
        val expected = GitUpload.blobSha("content-A".toByteArray())
        val body = GitPack.ReceiveBody(
            "x y refs/heads/main",
            listOf(expected to blob("content-B".toByteArray())),
        ) { _, _ -> }
        val e = capture { body.writeTo(okio.Buffer()) }
        assertTrue(e.message ?: "", e.message!!.contains("改动"))
    }

    @Test fun unreadableObjectFailsWithClearReason() {
        val gone = PackObject(GitPack.TYPE_BLOB, 5L) { null }
        val e = capture { GitPack.buildPack(listOf(gone)) }
        assertTrue(e.message ?: "", e.message!!.contains(GitUpload.UNREADABLE))
    }

    /** 流式请求体的形状：长度不可知（→ chunked）、可重开（→ 允许重试） */
    @Test fun receiveBodyIsChunkedAndRetryable() {
        val body = GitPack.ReceiveBody(
            "${"a".repeat(40)} ${"a".repeat(40)} refs/heads/main",
            listOf("3b18e512dba79e4c8300dd08aeb37f8e728b8dad" to blob(helloBytes)),
        ) { _, _ -> }
        assertEquals(-1L, body.contentLength())
        assertFalse(body.isOneShot())
        assertEquals("application/x-git-receive-pack-request", body.contentType()?.type + "/" + body.contentType()?.subtype)

        val sink = okio.Buffer()
        var seen = -1
        body.writeTo(sink)
        val out = sink.readByteArray()
        // 指令 + flush + pack：与 buildPack 的差别只在前面多了两行
        val pack = GitPack.buildPack(listOf(blob(helloBytes)))
        val expected = GitPack.pktLine("a".repeat(40) + " " + "a".repeat(40) + " refs/heads/main") +
            GitPack.FLUSH + pack
        assertEquals(expected.toList(), out.toList())
        seen = out.size
        assertTrue(seen > pack.size)
    }

    /** 打包时进度回调要按对象顺序报到 sha，界面用它显示在传哪个文件 */
    @Test fun receiveBodyReportsEachObjectInOrder() {
        val a = GitUpload.blobSha("a\n".toByteArray())
        val b = GitUpload.blobSha("b\n".toByteArray())
        val seen = ArrayList<String>()
        val body = GitPack.ReceiveBody("x y refs/heads/main", listOf(a to blob("a\n".toByteArray()), b to blob("b\n".toByteArray()))) { _, sha -> seen.add(sha) }
        body.writeTo(okio.Buffer())
        assertEquals(listOf(a, b), seen)
    }

    /**
     * 更新指令必须是 `<old> <new> <ref>` + NUL + 能力 + 换行。
     * 真实事故：漏掉 NUL + report-status 时 GitHub 回的是
     * `unpack no voting servers succeeded`，看着像服务端故障，实际是命令不合法，
     * 很容易被误判成「GitHub 抽风，重试一下就好」。
     */
    @Test fun updateCommandCarriesNullThenCapabilities() {
        val oid = "a".repeat(40)
        val cmd = GitPack.updateCommand(oid, oid, "refs/heads/main")
        assertTrue(cmd, cmd.startsWith("$oid $oid refs/heads/main" + 0.toChar()))
        assertTrue(cmd, cmd.contains("report-status"))
        assertTrue(cmd, cmd.endsWith("\n"))
        val withCaps = GitPack.updateCommand(oid, oid, "refs/heads/main", "report-status", "side-band-64k")
        assertTrue(withCaps, withCaps.contains("report-status side-band-64k"))
        // NUL 也算进 pkt-line 的长度：漏掉它服务端就解不出这一行
        assertEquals(cmd.toByteArray(Charsets.UTF_8).size + 4, GitPack.pktLine(cmd).size)
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private fun String.decodeHex(): ByteArray {
        require(length % 2 == 0) { "十六进制长度应为偶数" }
        return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private inline fun capture(block: () -> Unit): Throwable = try {
        block()
        fail("没有按计划抛出异常")
        RuntimeException()
    } catch (e: Throwable) {
        e
    }
}
