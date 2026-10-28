package com.mobilegh.data

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * 本地构造 git 的 tree 与 commit 对象。
 *
 * 只有在**空仓库**上才需要它：那种仓库没有任何提交，Data API 既不接受写对象（409），
 * 也没有一条已存在的 ref 可以借来发空操作指令，于是「服务端 base_tree 合并」这条路走不通。
 * 反过来说，空仓库里没有既有文件，自己拼树不可能漏掉别人的东西——这是唯一安全的场景。
 * 非空仓库仍然用 `createTree(base_tree = …)`，让服务端决定哪些旧文件保留。
 *
 * 字节格式与排序都跟真 git 核对过（GitTreeTest 拿 `git write-tree` 当 oracle）：
 * 每项是 `<mode> <name>\0<20 字节二进制 sha>`，目录的比较键要额外带一个 `/`。
 */
object GitTree {
    const val MODE_FILE = "100644"
    const val MODE_EXEC = "100755"
    const val MODE_DIR = "40000"

    /** 一个待写入的条目：仓库内路径分段、权限位、内容 blob 的 sha */
    class Entry(val segments: List<String>, val mode: String, val sha: String)

    /** 一个 git 对象：类型号 + 未压缩长度 + 内容（进 pack 与算 sha 都用它） */
    class Object(val type: Int, val size: Long, val bytes: ByteArray)

    /** 根树 sha + 需要一并写进 pack 的 tree 对象（含这棵根树本身） */
    class TreeResult(val sha: String, val trees: List<Object>)

    /** 对象 sha：`sha1("<type> <长度>\0" + 内容)` */
    fun objectSha(typeWord: String, bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("$typeWord ${bytes.size}\u0000".toByteArray(Charsets.UTF_8))
        md.update(bytes)
        return md.digest().hex()
    }

    fun treeSha(bytes: ByteArray): String = objectSha("tree", bytes)

    fun commitSha(bytes: ByteArray): String = objectSha("commit", bytes)

    /**
     * 从条目集合自底向上造出整棵树：子目录先成树，再作为条目进父树。
     * 分段为空的条目直接跳过（调用方给的路径已经过校验，这里只是不崩）。
     */
    fun buildRootTree(entries: List<Entry>): TreeResult {
        class Node {
            val files = LinkedHashMap<String, Pair<String, String>>()   // 名 -> (mode, sha)
            val dirs = LinkedHashMap<String, Node>()
        }
        val root = Node()
        val trees = ArrayList<Object>()

        for (e in entries) {
            if (e.segments.isEmpty()) continue
            var node = root
            for (s in e.segments.dropLast(1)) {
                require(!node.files.containsKey(s)) { "路径冲突：$s 同时是文件和目录" }
                node = node.dirs.getOrPut(s) { Node() }
            }
            val leaf = e.segments.last()
            require(!node.dirs.containsKey(leaf)) { "路径冲突：$leaf 同时是文件和目录" }
            node.files[leaf] = e.mode to e.sha
        }

        fun emit(node: Node): String {
            val items = ArrayList<Triple<String, String, String>>(node.files.size + node.dirs.size)
            node.files.forEach { (name, v) -> items.add(Triple(v.first, name, v.second)) }
            node.dirs.forEach { (name, sub) -> items.add(Triple(MODE_DIR, name, emit(sub))) }
            val bytes = serializeTree(items)
            trees.add(Object(GitPack.TYPE_TREE, bytes.size.toLong(), bytes))
            return treeSha(bytes)
        }
        return TreeResult(emit(root), trees)
    }

    /** 排序键：名字字节序，目录多带一个 `/`（实测决定 `docs.txt` 排在 `docs/` 之前） */
    internal fun sortKey(name: String, dir: Boolean): ByteArray =
        name.toByteArray(Charsets.UTF_8) + if (dir) byteArrayOf('/'.code.toByte()) else ByteArray(0)

    /** 按 git 的字节序排序后拼出树内容 */
    internal fun serializeTree(items: List<Triple<String, String, String>>): ByteArray {
        val out = ByteArrayOutputStream()
        items.sortedWith { a, b ->
            compareBytes(sortKey(a.second, a.first == MODE_DIR), sortKey(b.second, b.first == MODE_DIR))
        }.forEach { out.write(rawEntry(it.first, it.second, it.third)) }
        return out.toByteArray()
    }

    /** 一项的原始字节：`mode 空格 名 NUL sha(20 字节二进制)`。sha 是二进制，不是十六进制文本 */
    private fun rawEntry(mode: String, name: String, shaHex: String): ByteArray =
        "$mode $name\u0000".toByteArray(Charsets.UTF_8) + shaHex.hexToBytes()

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return a.size - b.size
    }

    /**
     * 造 commit 对象内容。空仓库的根提交没有 `parent` 行。
     * [ident] 是 `<名字> <邮箱> <秒级时间> <时区>`，带时间戳，所以同一份内容每次上传会得出
     * 不同的提交 sha——这是正常的（提交本来就该每次是新的）；去重是按 blob sha 做的，不依赖提交 sha。
     */
    fun commitBytes(treeSha: String, message: String, parents: List<String>, ident: String): ByteArray =
        buildString {
            append("tree ").append(treeSha).append('\n')
            append("author ").append(ident).append('\n')
            append("committer ").append(ident).append('\n')
            parents.forEach { append("parent ").append(it).append('\n') }
            append('\n').append(message)
        }.toByteArray(Charsets.UTF_8)

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray {
        require(length == 40) { "sha 长度不对：$this" }
        return ByteArray(20) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
