package com.mobilegh.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 上传用的是 git 的对象模型：路径、blob sha、权限位、分批都必须和 `git hash-object` / `git add`
 * 口径一致，否则传上去的文件会多一层目录、脚本丢可执行位，或者对象内容对不上。
 * sha 的期望值是用 `git hash-object` 实测出来的，不是按公式反推的。
 */
class GitUploadTest {
    @Test fun blobShaMatchesGitHashObject() {
        assertEquals("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391", GitUpload.blobSha(ByteArray(0)))
        assertEquals("3b18e512dba79e4c8300dd08aeb37f8e728b8dad", GitUpload.blobSha("hello world\n".toByteArray()))
        assertEquals("78981922613b2afb6025042ff6bd878ac1994e85", GitUpload.blobSha("a\n".toByteArray()))
        assertEquals("039e4d0069c5c26909f86c505b9de66182e6d1f3", GitUpload.blobSha("#!/bin/sh\nexit 0\n".toByteArray()))
    }

    /** 系统报的大小与实际一致时只读一遍；报错了（选完又改了）也要能算出正确 sha */
    @Test fun hashTrustsSizeHintAndRecoversWhenItIsStale() {
        val data = "hello world\n".toByteArray()
        val open = { data.inputStream() }
        val ok = GitUpload.hash(open, data.size.toLong())
        assertEquals(GitUpload.blobSha(data), ok.sha)
        assertEquals(data.size.toLong(), ok.size)

        val stale = GitUpload.hash(open, 999L)
        assertEquals(GitUpload.blobSha(data), stale.sha)
        assertEquals(data.size.toLong(), stale.size)

        // 没有大小信息（个别内容提供者不返回 SIZE）时先量长度再算
        val noHint = GitUpload.hash(open, 0L)
        assertEquals(GitUpload.blobSha(data), noHint.sha)
    }

    @Test fun hashHandlesEmptyFileAndKeepsHeadForModeDetection() {
        val empty = GitUpload.hash({ ByteArray(0).inputStream() }, 0L)
        assertEquals("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391", empty.sha)
        assertEquals(0L, empty.size)

        // head 只留开头 16 字节，够判断 shebang 就行
        val head = GitUpload.hash({ "#!/usr/bin/env pwsh".toByteArray().inputStream() }, 16L)
        assertEquals("#!/usr/bin/env p".toByteArray().toList(), head.head.toList())
    }

    @Test fun unreadableFileIsReportedAsUnreadable() {
        val e = capture { GitUpload.hash({ null }, 10L) }
        assertEquals(-1, (e as ApiException).code)
        assertTrue(e.message!!, e.message!!.contains(GitUpload.UNREADABLE))
    }

    /** 上限是在读的过程中卡的，不能先把整份大文件读进内存再判断 */
    @Test fun hashStopsAtLimitWithoutReadingEverything() {
        val big = ByteArray(1024 * 1024)
        val e = capture { GitUpload.hash({ big.inputStream() }, big.size.toLong(), limit = 100_000L) }
        assertEquals(413, (e as ApiException).code)
        assertTrue(e.message!!, e.message!!.contains("MB"))
    }

    @Test fun pathsAreBuiltFromCurrentDirAndFolderStructure() {
        assertEquals("README.md", GitUpload.pathOf("", emptyList(), "README.md"))
        assertEquals("docs/README.md", GitUpload.pathOf("docs", emptyList(), "README.md"))
        assertEquals("docs/assets/img/logo.png", GitUpload.pathOf("docs", listOf("assets", "img"), "logo.png"))
        assertEquals("a/b/c.txt", GitUpload.pathOf("/a/", listOf("b"), "c.txt"))
        assertNull(GitUpload.pathOf("docs", emptyList(), "   "))
        assertNull(GitUpload.pathOf("docs", listOf(".."), "a.txt"))
        assertNull(GitUpload.pathOf("docs", listOf("."), "a.txt"))
    }

    /** SAF 的名称理论上可以带 `/`（后端存储的内容提供商），直接拼路径会让文件跑到别的目录去 */
    @Test fun namesWithSlashesAreFlattened() {
        assertEquals("a_b.txt", GitUpload.sanitizeName("a/b.txt"))
        assertNull(GitUpload.sanitizeName("."))
        assertNull(GitUpload.sanitizeName(".."))
        assertNull(GitUpload.sanitizeName("   "))
        assertEquals("docs/a_b.txt", GitUpload.pathOf("docs", emptyList(), "a/b.txt"))
    }

    @Test fun triageDropsDuplicatesAndRoutesOversizedFilesToLfs() {
        val items = listOf(
            UploadItem("u1", "a.txt", "dir/a.txt", 10),
            // Git 路径大小写敏感，两项都必须保留
            UploadItem("u2", "A.TXT", "dir/A.TXT", 20),
            UploadItem("u3", "huge.bin", "huge.bin", GitUpload.MAX_FILE_BYTES + 1),
            UploadItem("u4", "bad", "docs/../x", 5),
            UploadItem("u5", "ok.sh", "ok.sh", 5),
            UploadItem("u6", "giant.iso", "giant.iso", GitLfs.MAX_BYTES + 1),
        )
        val r = GitUpload.triage(items)
        assertEquals(5, r.items.size)
        assertEquals(1, r.dropped)
        val byUri = r.items.associateBy { it.uri }
        assertNull(byUri["u1"]!!.skip)
        assertNull(byUri["u2"]!!.skip)
        // 超过 100 MiB 的文件不再被拒绝，而是改走 Git LFS
        assertNull(byUri["u3"]!!.skip)
        assertTrue(byUri["u3"]!!.lfs)
        assertFalse(byUri["u1"]!!.lfs)
        // LFS 自己也有上限：超过就只能排除，并写明原因
        assertTrue(byUri["u6"]!!.skip!!, byUri["u6"]!!.skip!!.contains("LFS"))
        assertEquals(listOf("u1", "u2", "u3", "u5"), GitUpload.usable(r.items).map { it.uri })
    }

    /** 去重要在整理路径之后做：清单里的路径带多余空白或斜杠也拦不住重复 */
    @Test fun triageNormalizesPathBeforeDeduping() {
        val r = GitUpload.triage(listOf(
            UploadItem("u1", "a.txt", "  dir//a.txt  ", 1),
            UploadItem("u2", "a.txt", "/dir/a.txt", 1),
        ))
        assertEquals(1, r.items.size)
        assertEquals(1, r.dropped)
        assertEquals("dir/a.txt", r.items[0].path)
        assertEquals("a.txt", r.items[0].name)
    }

    /** 99 MiB 是允许的：不能拿旧的接口上限拦住本该能传的大文件 */
    @Test fun filesUnderTheRepoCapAreNotBlocked() {
        val r = GitUpload.triage(listOf(
            UploadItem("u", "big.bin", "big.bin", 99L * 1024 * 1024),
            UploadItem("v", "cap.bin", "cap.bin", GitUpload.MAX_FILE_BYTES),
        ))
        assertEquals(2, r.items.size)
        assertTrue(GitUpload.usable(r.items).isEmpty().not())
        r.items.forEach { assertNull(it.skip) }
        // 正好等于仓库上限的文件仍然可以直接提交，不需要 LFS
        r.items.forEach { assertFalse(it.lfs) }
    }

    @Test fun fileModeFollowsShebangAndExistingMode() {
        val shebang = "#!/bin/sh\n".toByteArray()
        val plain = "text".toByteArray()
        assertEquals("100755", GitUpload.modeOf("run.txt", shebang, null))
        // 普通脚本文件也不能仅凭扩展名被改成可执行文件
        assertEquals("100644", GitUpload.modeOf("deploy.py", plain, null))
        assertEquals("100644", GitUpload.modeOf("run.sh", plain, null))
        // 本机不带权限位，但仓库里已经是可执行的不能被打回普通文件
        assertEquals("100755", GitUpload.modeOf("task.ps1", plain, RepoBlob("x", "100755")))
        assertEquals("100644", GitUpload.modeOf("notes.md", plain, RepoBlob("x", "100644")))
        assertEquals("100644", GitUpload.modeOf("photo.jpg", plain, null))
    }

    @Test fun unchangedFilesAreSkippedButModeChangesStillWrite() {
        val existing = RepoBlob("abc", "100644")
        assertFalse(GitUpload.needsWrite("abc", "100644", existing))
        assertTrue(GitUpload.needsWrite("abc", "100755", existing))
        assertTrue(GitUpload.needsWrite("abd", "100644", existing))
        assertTrue(GitUpload.needsWrite("abc", "100644", null))
    }

    @Test fun treeEntriesAreSplitIntoBatches() {
        assertTrue(GitUpload.batches<Int>(emptyList()).isEmpty())
        assertEquals(1, GitUpload.batches((1..10).toList()).size)
        val parts = GitUpload.batches((1..1200).toList())
        assertEquals(3, parts.size)
        assertEquals(500, parts[0].size)
        assertEquals(listOf(1199, 1200), parts[2].takeLast(2))
    }

    /** 一批不能太小也不能无限大；单个对象就超过一批预算时也必须能发出去 */
    @Test fun packGroupsRespectByteBudget() {
        val mb = 1024L * 1024
        fun pending(id: String, sizeMb: Long) = Pending(id, sizeMb * mb) { ByteArray(0).inputStream() }
        assertTrue(GitUpload.packGroups(emptyList()).isEmpty())

        // 合计远小于预算：只有一批
        val small = (1..10L).map { pending("s$it", it) }
        assertEquals(1, GitUpload.packGroups(small, maxBytes = 64 * mb).size)

        val big = (1..5L).map { pending("b$it", 30) }
        val groups = GitUpload.packGroups(big, maxBytes = 64 * mb)
        assertEquals(listOf(2, 2, 1), groups.map { it.size })
        assertEquals(5, groups.flatten().size)
        // 每批都不超预算（除了单文件本身就超的情况）
        assertTrue(groups.filter { it.size > 1 }.all { g -> g.sumOf { o -> o.size } <= 64 * mb })

        // 99 MiB 单文件：自己占一批，不能被丢掉
        val huge = listOf(pending("h", 99), pending("t", 1))
        val g2 = GitUpload.packGroups(huge, maxBytes = 64 * mb)
        assertEquals(listOf(1, 1), g2.map { it.size })
        assertEquals(99 * mb, g2[0][0].size)
    }

    @Test fun commitMessageDoesNotAddAttribution() {
        assertEquals("Add logo", GitUpload.commitMessage("  Add logo  "))
        assertEquals("", GitUpload.commitMessage("   "))
        assertFalse(GitUpload.commitMessage("Add logo").contains("Co-authored-by", ignoreCase = true))
    }

    @Test fun defaultMessageMatchesFileCount() {
        assertEquals("", GitUpload.defaultMessage(emptyList()))
        assertEquals("Add a.txt", GitUpload.defaultMessage(listOf(UploadItem("u", "a.txt", "x/a.txt"))))
        assertEquals("Add 2 files", GitUpload.defaultMessage(listOf(
            UploadItem("u", "a.txt", "a.txt"),
            UploadItem("v", "b.txt", "b.txt"),
        )))
        // 被排除的项不该算进「Add N files」
        assertEquals("Add a.txt", GitUpload.defaultMessage(listOf(
            UploadItem("u", "a.txt", "a.txt"),
            UploadItem("v", "big", "big", GitUpload.MAX_FILE_BYTES + 1, skip = "太大"),
        )))
    }

    @Test fun treeWritePayloadHasGitFields() {
        val json = TreeWrite("docs/a.txt", "100644", "abc").payload().toString()
        assertTrue(json, json.contains("\"path\":\"docs/a.txt\""))
        assertTrue(json, json.contains("\"mode\":\"100644\""))
        assertTrue(json, json.contains("\"type\":\"blob\""))
        assertTrue(json, json.contains("\"sha\":\"abc\""))
    }

    /**
     * 分支引用只允许快进，所以「别人先推了」「受保护」「钩子整体拒收」要分开说；
     * 提交已经生成而分支没跟上时，必须把 sha 讲出来，不能让用户以为什么都没发生。
     */
    @Test fun errorsExplainTheNextStep() {
        val stale = GitUpload.explain(ApiException(422, "Update is not a fast forward"), null)
        assertTrue(stale, stale.contains("新提交"))
        assertFalse(stale, stale.contains("受保护"))

        // 借用指令被拒（对象其实已落库）与引用非快进是同一类下一步
        val lock = GitUpload.explain("ng refs/heads/main cannot lock ref: is at abc but expected def", null)
        assertTrue(lock, lock.contains("重新上传"))

        val protectedBranch = GitUpload.explain(ApiException(403, "Branch is protected"), null)
        assertTrue(protectedBranch, protectedBranch.contains("受保护"))

        // 实测 101 MiB：unpack ok 但钩子拒收，对象没进去
        val hook = GitUpload.explain("ng refs/heads/main pre-receive hook declined", null)
        assertTrue(hook, hook.contains("100"))

        val indexPack = GitUpload.explain("unpack index-pack failed", null)
        assertTrue(indexPack, indexPack.contains("重新选"))

        // 提交已生成、分支没跟上：必须把 sha 说清楚
        val partial = GitUpload.explain(
            ApiException(422, "Update is not a fast forward"),
            "0123456789abcdef0123456789abcdef01234567",
        )
        assertTrue(partial, partial.contains("0123456"))
        assertTrue(partial, partial.contains("分支没有更新"))

        val uncertain = GitUpload.explain(
            ApiException(-1, "timeout"),
            "0123456789abcdef0123456789abcdef01234567",
        )
        assertTrue(uncertain, uncertain.contains("结果未确认"))
        assertFalse(uncertain, uncertain.contains("分支没有更新"))

        val conflict = GitUpload.explain(ApiException(422, "GitRPC::BadObjectState"), null)
        assertTrue(conflict, conflict.contains("路径冲突"))

        // 空仓库现在能直接上传（本地建树与建提交），所以不该再出现「请先新建文件」这种话
        val noRef = GitUpload.explain("refs/heads/main cannot lock ref", null)
        assertTrue(noRef, !noRef.contains("新建文件"))

        val sso = GitUpload.explain(ApiException(403, "SAML"), null)
        assertTrue(sso, sso.contains("SSO"))

        val network = GitUpload.explain(ApiException(-1, ""), null)
        assertTrue(network, network.contains("网络"))

        // 每条都必须承认「上传未完成」
        listOf(stale, lock, protectedBranch, hook, indexPack, partial, conflict, sso, network).forEach {
            assertTrue(it, it.startsWith("上传未完成"))
        }
    }

    @Test fun completeSnapshotRejectsTruncatedTree() {
        val blobs = mapOf("run.sh" to RepoBlob("abc", "100755"))
        assertEquals(blobs, GitUpload.completeSnapshot(TreeIndex(blobs, truncated = false)))
        val e = capture { GitUpload.completeSnapshot(TreeIndex(blobs, truncated = true)) }
        assertTrue(e is ApiException)
        assertTrue(e.message!!, e.message!!.contains("基准目录"))
    }

    @Test fun accountGuardRejectsSwitchBeforeNextWrite() {
        val oldLogin = Session.login
        val oldGeneration = Session.generation
        try {
            Session.login = "first"
            val guard = GitUpload.uploadAccountGuard()
            guard()
            Session.login = "second"
            Session.generation++
            val e = capture { guard() }
            assertTrue(e is ApiException)
            assertTrue(e.message!!, e.message!!.contains("账号已切换"))
        } finally {
            Session.login = oldLogin
            Session.generation = oldGeneration
        }
    }

    /**
     * 上传阶段的进度条必须是「不确定」样式：字节数只在本机读文件时才可信，
     * 打包与发送阶段拿不到已发送量，沿用读阶段的数值会让条停在 100% 而其实刚开始传。
     */
    @Test fun progressIsOnlyDeterminateWhileReading() {
        val reading = UploadProgress(3, 10, 300, 1000, "b.bin")
        assertEquals(UploadStage.Reading, reading.stage)
        assertTrue(com.mobilegh.ui.screens.determinateProgress(reading))
        assertFalse(com.mobilegh.ui.screens.determinateProgress(reading.copy(stage = UploadStage.Sending)))
        // LFS 上传能数出已发送的真实字节数，所以进度条也是确定的
        assertTrue(com.mobilegh.ui.screens.determinateProgress(reading.copy(stage = UploadStage.Lfs)))
        assertFalse(com.mobilegh.ui.screens.determinateProgress(reading.copy(stage = UploadStage.Finalizing)))
        // 总数未知（个别内容提供者不给大小）时也别画假比例
        assertFalse(com.mobilegh.ui.screens.determinateProgress(reading.copy(totalBytes = 0)))
        assertFalse(com.mobilegh.ui.screens.determinateProgress(null))
    }

    /**
     * 身份行形状。这两种写法都是真 git 生成过的提交（本地 `git commit` 验证），
     * 因为这段文本会直接进 commit 对象字节：格式错一个字符就是 GitHub 拒收或丢归属。
     * 带用户号的那形式才会被关联到账号（实测：用错 id 时 `author` 为 null）。
     */
    @Test fun identMatchesTheShapeGitAccepts() {
        assertEquals(
            "x500x <109746464+x500x@users.noreply.github.com> 1762000000 +0000",
            GitUpload.identOf("x500x", 109746464L, 1762000000L),
        )
        // 拿不到用户号：不带前缀，提交仍合法，只是不关联账号
        assertEquals(
            "x500x <x500x@users.noreply.github.com> 1762000000 +0000",
            GitUpload.identOf("x500x", 0L, 1762000000L),
        )
        // 形状必须是 `名字 <邮箱> <秒> <时区>`：四段，且带尖括号
        val parts = GitUpload.identOf("mona", 7L, 42L).split(" ")
        assertEquals(4, parts.size)
        assertTrue(parts[1], parts[1].startsWith("<") && parts[1].endsWith(">"))
        assertEquals("42", parts[2])
        assertEquals("+0000", parts[3])
    }

    /** 同样输入必须得出同样的身份行：不然重传时 commit sha 会变，对不上账 */
    @Test fun identIsDeterministic() {
        assertEquals(GitUpload.identOf("a", 1L, 2L), GitUpload.identOf("a", 1L, 2L))
    }

    /**
     * 空仓库起步时，LFS 文件在 pack 里只放指针；.gitattributes 同时写入，克隆后 git-lfs 才会还原大文件。
     * 这里用一个 1 字节小文件并标成 lfs 来走同一条路径，不需要真造 100 MiB。
     */
    @Test fun lfsFilesBecomePointersAndGetTrackedInGitattributes() = runBlocking {
        val bytes = "hello world\n".toByteArray()
        val big = UploadItem("u-big", "big.bin", "media/big.bin", bytes.size.toLong(), lfs = true)
        val small = UploadItem("u-small", "a.txt", "a.txt", 3)
        val plan = GitUpload.planBootstrap(listOf(big, small), "add", { f -> if (f.uri == "u-big") bytes.inputStream() else "abc".toByteArray().inputStream() })
        val lfs = plan.lfs.single()
        assertEquals("a948904f2f0f479b8f8197694b30184b0d2ed1c1cd2a1ec0fb85d299a192a447", lfs.oid)
        assertEquals(12L, lfs.size)
        assertEquals("media/big.bin", lfs.path)
        // 仓库里放的是指针，不是原文件
        val pointer = GitLfs.pointer(lfs.oid, lfs.size)
        val blobs = plan.objects.filter { it.second.type == GitPack.TYPE_BLOB }.associate { it.first to it.second.open()!!.readBytes() }
        assertTrue(blobs.containsKey(GitUpload.blobSha(pointer)))
        assertFalse(blobs.containsKey(GitUpload.blobSha(bytes)))
        val attrs = blobs.values.map { String(it) }.single { it.contains("filter=lfs") }
        assertEquals("media/big.bin filter=lfs diff=lfs merge=lfs -text\n", attrs)
        assertTrue(blobs.containsKey(GitUpload.blobSha("abc".toByteArray())))
        // 总字节数里包含 LFS 文件，进度才对得上
        assertTrue(plan.total >= bytes.size)
    }

    @Test fun emptyFileListNeverReachesTheApi() = runBlocking {
        val e = capture { GitUpload.run("o", "n", "main", "main", emptyList(), "msg", false, { null }) }
        assertEquals(400, (e as ApiException).code)
    }

    /** ref 名空白时不要拿去拼指令；这属于「先说明再去建第一个文件」的情况 */
    /** 空白分支名是调用方给错了参数，不是「空仓库」：空仓库现在能直接上传 */
    @Test fun blankBaseRefIsRejectedAsBadArgument() = runBlocking {
        val e = capture {
            GitUpload.run("o", "n", "main", "", listOf(UploadItem("u", "a.txt", "a.txt", 1)), "msg", false, { null })
        }
        assertEquals(400, (e as ApiException).code)
        assertTrue(e.message ?: "", e.message!!.contains("分支名无效"))
        // 不能再出现「请先新建文件」这种把实现限制推给用户的说法
        assertFalse(e.message!!, e.message!!.contains("新建文件"))
    }

    @Test fun capsAreTheAdvertisedOnes() {
        // 仓库硬上限 100 MiB；旧的 40 MiB 是 REST 接口的闸，pack 传输不受它限制
        assertEquals(100L * 1024 * 1024, GitUpload.MAX_FILE_BYTES)
        assertEquals(GitPack.MAX_OBJECT_BYTES, GitUpload.MAX_FILE_BYTES)
        assertTrue(GitUpload.TREE_BATCH in 1..1000)
        // 预算不能超过 GitHub 接受的上限；就算超过了，单个满额对象也必须能自己占一批发出去
        // （见 packBatchingKeepsEveryObjectInOrder：拆不开的对象不受预算约束）
        assertTrue(GitUpload.PACK_BATCH_BYTES in 1L..GitUpload.MAX_FILE_BYTES)
    }

    /** 分批不能丢对象、不能重排、也不能让一个满额文件永远发不出去 */
    @Test fun packBatchingKeepsEveryObjectInOrder() {
        val mb = 1024L * 1024
        val objects = listOf(
            Pending("a".repeat(40), GitUpload.MAX_FILE_BYTES) { ByteArray(0).inputStream() },  // 满额
            Pending("b".repeat(40), 1 * mb) { ByteArray(0).inputStream() },
            Pending("c".repeat(40), 1 * mb) { ByteArray(0).inputStream() },
        )
        val groups = GitUpload.packGroups(objects, maxBytes = 64 * mb)
        assertEquals(objects.map { it.sha }, groups.flatten().map { it.sha })
        // 单个对象超过预算时必须自己占一批，而不是被丢掉（对象没法拆开传）
        assertEquals(GitUpload.MAX_FILE_BYTES, groups[0].single().size)
        // 用默认预算时，满额文件也一定能发出去
        assertEquals(1, GitUpload.packGroups(listOf(objects[0])).size)
    }

    private inline fun capture(block: () -> Unit): Throwable = try {
        block()
        fail("没有按计划抛出异常")
        RuntimeException()
    } catch (e: Throwable) {
        e
    }
}
