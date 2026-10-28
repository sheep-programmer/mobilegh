package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空仓库起步时，tree 与 commit 是本地算出来后随 pack 一起推上去的。
 * 字节格式或排序错一个字符，sha 就变：GitHub 照样收下，但 `git ls-tree` 看到的内容与本机对不上，
 * 而界面上只会是一句「上传失败」。所以金样本全部来自真 `git write-tree` / `git cat-file tree`。
 *
 * 布局：README.md、docs/a/deep.txt、scripts/run.sh（100755）
 * git write-tree 的根树 = b2a4e7905d5cda27371a101531ae448df4c4419a
 */
class GitTreeTest {
    private val readme = "95da0d24b1007752917fe45f42beac836da09d2a"
    private val deep = "4cdb2265d30204be5463b38174b2e8e717982405"
    private val runSh = "4163036efa65bd4a469e752267498f01ea36a55c"
    private val docs = "82457a37c7194c7ca4673ef5428365df05de94b1"
    private val docsA = "6738db2295e2593949ea417b0b14f1dc4ff114ea"
    private val scripts = "31e608648b097abeeae5708b175b2638af0a598f"
    private val root = "b2a4e7905d5cda27371a101531ae448df4c4419a"

    private val entries = listOf(
        GitTree.Entry(listOf("README.md"), GitTree.MODE_FILE, readme),
        GitTree.Entry(listOf("docs", "a", "deep.txt"), GitTree.MODE_FILE, deep),
        GitTree.Entry(listOf("scripts", "run.sh"), GitTree.MODE_EXEC, runSh),
    )

    /** 用到的 blob sha 先自证：这三个值是 `git hash-object` 给的 */
    @Test fun blobShasMatchGitHashObject() {
        assertEquals(readme, GitUpload.blobSha("# from MobileGH\n".toByteArray()))
        assertEquals(deep, GitUpload.blobSha("deep\n".toByteArray()))
        assertEquals(runSh, GitUpload.blobSha("#!/bin/sh\necho hi\n".toByteArray()))
    }

    /** 空树有个全社区公认的 sha：拿它自证 objectSha 的头部格式没写错 */
    @Test fun objectShaMatchesTheFamousEmptyTree() {
        assertEquals("4b825dc642cb6eb9a060e54bf8d69288fbee4904", GitTree.treeSha(ByteArray(0)))
    }

    /** 根树 sha 必须等于 git write-tree —— 这是空仓库起步能不能被 git 正常识别的关键 */
    @Test fun rootTreeShaMatchesGitWriteTree() {
        assertEquals(root, GitTree.buildRootTree(entries).sha)
    }

    /** 每棵子树的字节也要与 git 一致：只看根树的话，中间层排错顺序也可能凑出同样的根 sha 之外的错 */
    @Test fun everyTreeMatchesGitBytes() {
        val built = GitTree.buildRootTree(entries)
        val bySha = built.trees.map { GitTree.treeSha(it.bytes) to it.bytes }.toMap()
        assertEquals(setOf(root, docs, docsA, scripts), bySha.keys)
        assertEquals("31303036343420646565702e74787400$deep", bySha[docsA]!!.hex())
        assertEquals("3130303735352072756e2e736800$runSh", bySha[scripts]!!.hex())
        assertEquals("3430303030206100$docsA", bySha[docs]!!.hex())
        assertEquals(
            "31303036343420524541444d452e6d6400$readme" +
                "343030303020646f637300$docs" +
                "3430303030207363726970747300$scripts",
            bySha[root]!!.hex(),
        )
    }

    /** 目录的比较键带一个尾随 `/`：这条决定 `docs.txt`（文件）与 `docs/`（目录）谁在前 */
    @Test fun directorySortKeyCarriesTrailingSlash() {
        assertTrue(
            "docs.txt 应排在 docs/ 之前（'.' 0x2E < '/' 0x2F）",
            GitTree.sortKey("docs.txt", false).compareBytesTo(GitTree.sortKey("docs", true)) < 0,
        )
        assertTrue(
            "docs0 应排在 docs/ 之后（'0' 0x30 > '/' 0x2F）",
            GitTree.sortKey("docs0", false).compareBytesTo(GitTree.sortKey("docs", true)) > 0,
        )
    }

    /** commit 的形状：tree / author / committer / 可选 parent / 空行 / 信息 */
    @Test fun commitBytesHaveGitShape() {
        val ident = "mona <1+mona@users.noreply.github.com> 1762000000 +0000"
        val first = GitTree.commitBytes(root, "Initial commit\n", emptyList(), ident)
        assertEquals("tree $root\nauthor $ident\ncommitter $ident\n\nInitial commit\n", first.text())
        val child = GitTree.commitBytes(root, "Second", listOf(root), ident)
        assertTrue(child.text(), child.text().contains("parent $root\n\nSecond"))
    }

    /** 同样的字节必须算出同样的 sha，否则重传与对账都会错乱 */
    @Test fun commitShaIsStableAndWellFormed() {
        val ident = "mona <1+mona@users.noreply.github.com> 1762000000 +0000"
        val a = GitTree.commitBytes(root, "msg", emptyList(), ident)
        val b = GitTree.commitBytes(root, "msg", emptyList(), ident)
        val sha = GitTree.commitSha(a)
        assertEquals(GitTree.commitSha(b), sha)
        assertEquals(40, sha.length)
        assertTrue(sha.all { it in "0123456789abcdef" })
    }

    /** 对象长度要等于内容字节数：pack 的对象头就写这个长度，错了服务端会解坏 */
    @Test fun builtObjectsReportTheirLength() {
        val built = GitTree.buildRootTree(entries)
        assertTrue(built.trees.isNotEmpty())
        built.trees.forEach { assertEquals(it.size, it.bytes.size.toLong()) }
        assertTrue(built.trees.all { it.type == GitPack.TYPE_TREE })
    }

    /** 只有平铺文件时不该凭空造出目录层 */
    @Test fun flatEntriesProduceOneTree() {
        val r = GitTree.buildRootTree(listOf(GitTree.Entry(listOf("a.txt"), GitTree.MODE_FILE, readme)))
        assertEquals(1, r.trees.size)
        assertEquals("31303036343420612e74787400$readme", r.trees.single().bytes.hex())
    }

    /** 分段为空的条目要忽略，而不是拼出一个空名字的目录 */
    @Test fun entriesWithNoSegmentsAreSkipped() {
        val r = GitTree.buildRootTree(listOf(GitTree.Entry(emptyList(), GitTree.MODE_FILE, readme)) + entries)
        assertEquals(root, r.sha)
    }

    /** 同名条目后写的覆盖先写的（清单本身已去重，这里保证不会再拼出双份） */
    @Test fun duplicatePathKeepsOneEntry() {
        val r = GitTree.buildRootTree(entries + GitTree.Entry(listOf("README.md"), GitTree.MODE_FILE, deep))
        assertTrue(r.sha != root)
        val t = r.trees.first { GitTree.treeSha(it.bytes) == r.sha }
        assertTrue(t.bytes.hex(), t.bytes.hex().endsWith("3430303030207363726970747300$scripts"))
        assertTrue(t.bytes.hex(), t.bytes.hex().contains("31303036343420524541444d452e6d6400$deep"))
    }

    @Test fun fileAndDirectoryPrefixConflictsAreRejectedInEitherOrder() {
        val file = GitTree.Entry(listOf("foo"), GitTree.MODE_FILE, readme)
        val child = GitTree.Entry(listOf("foo", "bar.txt"), GitTree.MODE_FILE, deep)
        listOf(listOf(file, child), listOf(child, file)).forEach { conflict ->
            val e = runCatching { GitTree.buildRootTree(conflict) }.exceptionOrNull()
            assertTrue("foo 与 foo/bar.txt 不能同时进入一棵树", e is IllegalArgumentException)
            assertTrue(e!!.message!!, e.message!!.contains("路径冲突"))
        }
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private fun ByteArray.text(): String = String(this, Charsets.UTF_8)

    private fun ByteArray.compareBytesTo(other: ByteArray): Int {
        val n = minOf(size, other.size)
        for (i in 0 until n) {
            val d = (this[i].toInt() and 0xFF) - (other[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return size - other.size
    }
}
