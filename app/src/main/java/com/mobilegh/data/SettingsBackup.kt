package com.mobilegh.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf

/**
 * 设置备份的内容。
 *
 * 只包含可以安全带走的偏好：应用网络代理、API 反向代理地址、加速节点选择、自定义加速节点、应用锁开关。
 * 不包含 GitHub Token、两步验证密钥和应用锁验证信息（应用锁由系统验证，本应用不保存密码）。
 */
@Serializable
data class SettingsBackupPayload(
    val format: String = "",
    val version: Int = 0,
    val network: NetworkBackup = NetworkBackup(),
    val apiProxy: String = "",
    val acceleration: AccelerationBackup = AccelerationBackup(),
    val customNodes: List<CustomNodeBackup> = emptyList(),
    /** 仅供参考：导入时不会改变应用锁，它只能在本机通过系统验证后开关 */
    val appLockEnabled: Boolean = false,
)

/** [mode] 为 [ProxyMode] 的枚举名 */
@Serializable
data class NetworkBackup(val mode: String = ProxyMode.System.name, val host: String = "127.0.0.1", val port: Int = 7890)

/** 文件使用 [mode]/[manualId]；下载使用独立字段。旧备份缺省时会继承原全局选择。 */
@Serializable
data class AccelerationBackup(
    val mode: Int = 0,
    val manualId: String = "",
    val downloadMode: Int = mode,
    val downloadManualId: String = manualId,
)

/** [id] 只用于把手动选择的自定义节点对应到导入后的新节点，导入时节点 id 会重新生成 */
@Serializable
data class CustomNodeBackup(val id: String = "", val name: String, val base: String)

/** 解析后的节点与最终模式；手动缺失时固定直连，只有旧 jsDelivr 下载迁移回自动。 */
internal data class ImportedNodeSelection(val id: String?, val mode: Int)

internal fun resolveImportedNodeSelection(
    requestedMode: Int,
    requestedId: String,
    mappedCustomId: String?,
    localNodes: List<CdnNode>,
    builtins: List<CdnNode>,
    download: Boolean,
    legacyDownloadFallback: Boolean = false,
): ImportedNodeSelection {
    fun compatible(node: CdnNode) = !download || node.type != NodeType.JsDelivr
    val mapped = mappedCustomId?.let { id -> localNodes.firstOrNull { it.id == id && compatible(it) } }
    val builtin = builtins.firstOrNull { it.id == requestedId && compatible(it) }
    val node = mapped ?: builtin
    if (node != null) return ImportedNodeSelection(node.id, requestedMode)
    if (requestedMode == 1) {
        if (legacyDownloadFallback) return ImportedNodeSelection(Net.DEFAULT_DL, 0)
        return ImportedNodeSelection(Net.DIRECT.id, 1)
    }
    return ImportedNodeSelection(null, requestedMode)
}

internal fun isLegacyJsDelivrDownloadBackup(
    backup: AccelerationBackup,
    builtins: List<CdnNode>,
): Boolean = backup.downloadMode == 1 && backup.downloadManualId == backup.manualId &&
    builtins.any { it.id == backup.manualId && it.type == NodeType.JsDelivr }

/** 纯 JSON 编解码与校验，不依赖 Android，便于单元测试。 */
object SettingsBackupCodec {
    const val FORMAT = "mobilegh-settings"
    const val VERSION = 1

    private const val MAX_CUSTOM_NODES = 50
    private const val MAX_NODE_NAME = 60
    private const val MAX_NODE_BASE = 300
    private const val MAX_NODE_ID = 100

    private val ROOT_KEYS = setOf("format", "version", "network", "apiProxy", "acceleration", "customNodes", "appLockEnabled")
    private val NETWORK_KEYS = setOf("mode", "host", "port")
    private val ACCELERATION_KEYS = setOf("mode", "manualId", "downloadMode", "downloadManualId")
    private val NODE_KEYS = setOf("id", "name", "base")

    private val pretty = Json { prettyPrint = true; encodeDefaults = true }

    fun encode(payload: SettingsBackupPayload): String =
        pretty.encodeToString(SettingsBackupPayload.serializer(), payload)

    /**
     * 解析并校验。未知字段、JSON 格式错误、字段类型或取值不合法都会抛出 [IllegalArgumentException]，
     * 消息是可以直接显示给用户的中文说明。
     */
    fun decode(text: String): SettingsBackupPayload {
        val root = runCatching { Json.parseToJsonElement(text.trim()) }.getOrNull() as? JsonObject
            ?: throw IllegalArgumentException(tr("不是有效的设置内容（需要 JSON 对象）"))
        checkKeys(root, ROOT_KEYS)
        root["network"]?.let { checkKeys(it, NETWORK_KEYS) }
        root["acceleration"]?.let { checkKeys(it, ACCELERATION_KEYS) }
        val nodes = root["customNodes"] ?: JsonArray(emptyList())
        (nodes as? JsonArray ?: throw IllegalArgumentException(tr("自定义节点的格式不正确"))).forEach { checkKeys(it, NODE_KEYS) }
        val payload = try {
            Json.decodeFromJsonElement(SettingsBackupPayload.serializer(), root)
        } catch (e: IllegalArgumentException) {
            // SerializationException 也属于 IllegalArgumentException，统一换成用户能看懂的说法
            throw IllegalArgumentException(tr("设置内容的字段类型不正确"))
        }
        validate(payload)
        return payload
    }

    /** 取值校验：和应用内对应输入框的规则保持一致 */
    fun validate(p: SettingsBackupPayload) {
        require(p.format == FORMAT) { tr("这不是 MobileGH 的设置文件") }
        require(p.version in 1..VERSION) { tr("设置文件版本无效，或来自更新的应用版本") }
        val mode = ProxyMode.entries.firstOrNull { it.name == p.network.mode }
            ?: throw IllegalArgumentException(trf("未知的代理模式：{0}", p.network.mode))
        NetworkProxy(mode, p.network.host, p.network.port).validated()
        Net.apiProxyError(p.apiProxy)?.let { throw IllegalArgumentException(it) }
        require(p.acceleration.mode in 0..2) { trf("加速模式无效：{0}", p.acceleration.mode) }
        require(p.acceleration.downloadMode in 0..2) { trf("加速模式无效：{0}", p.acceleration.downloadMode) }
        require(p.acceleration.manualId.length <= MAX_NODE_ID) { tr("手动选择的节点标识过长") }
        require(p.acceleration.downloadManualId.length <= MAX_NODE_ID) { tr("手动选择的节点标识过长") }
        require(p.customNodes.size <= MAX_CUSTOM_NODES) { trf("自定义节点最多 {0} 个", MAX_CUSTOM_NODES) }
        p.customNodes.forEach { node ->
            require(node.name.length <= MAX_NODE_NAME) { tr("自定义节点名称过长") }
            val base = node.base.trim()
            require(base.startsWith("https://") && base.length <= MAX_NODE_BASE && base.none { it.isWhitespace() }) {
                tr("地址必须以 https:// 开头")
            }
        }
    }

    private fun checkKeys(element: JsonElement, allowed: Set<String>) {
        val obj = element as? JsonObject ?: throw IllegalArgumentException(tr("设置内容的结构不正确"))
        obj.keys.firstOrNull { it !in allowed }?.let { throw IllegalArgumentException(trf("不认识的设置项：{0}", it)) }
    }
}

/**
 * 设置备份的导出与导入（依赖应用状态的部分）。
 *
 * 导出的每一项都从内存中的状态读取；导入前先整体校验（见 [SettingsBackupCodec.decode]），通过后才开始写入。
 */
object SettingsBackup {
    /** 当前可备份设置的快照 */
    fun snapshot(): SettingsBackupPayload {
        val cfg = AppNetwork.config
        return SettingsBackupPayload(
            format = SettingsBackupCodec.FORMAT,
            version = SettingsBackupCodec.VERSION,
            network = NetworkBackup(cfg.mode.name, cfg.host, cfg.port),
            apiProxy = Net.apiProxy.trim(),
            acceleration = AccelerationBackup(
                mode = Net.rawMode,
                manualId = Net.manualRawId,
                downloadMode = Net.downloadMode,
                downloadManualId = Net.manualDownloadId,
            ),
            customNodes = Net.custom.map { CustomNodeBackup(it.id, it.name, it.base) },
            appLockEnabled = AppLock.enabled,
        )
    }

    fun export(): String = SettingsBackupCodec.encode(snapshot())

    /** 写入已经通过校验的设置。应用锁不在这里改变。 */
    suspend fun apply(p: SettingsBackupPayload) {
        AppNetwork.save(NetworkProxy(ProxyMode.valueOf(p.network.mode), p.network.host, p.network.port))
        if (p.apiProxy.trim() != Net.apiProxy.trim()) Net.setApi(p.apiProxy)

        // 自定义节点按地址去重后添加；节点 id 会重新生成，所以记下「备份中的 id → 本机的 id」，手动选择才能对上
        val newIdByBackupId = HashMap<String, String>()
        for (node in p.customNodes) {
            val base = node.base.trim().let { if (it.endsWith("/")) it else "$it/" }
            if (Net.custom.none { it.base == base }) Net.addCustom(node.name, node.base)
            Net.custom.firstOrNull { it.base == base }?.let { newIdByBackupId[node.id] = it.id }
        }
        val raw = resolveImportedNodeSelection(
            p.acceleration.mode,
            p.acceleration.manualId,
            newIdByBackupId[p.acceleration.manualId],
            Net.nodes,
            Net.builtins,
            download = false,
        )
        val legacyDownload = isLegacyJsDelivrDownloadBackup(p.acceleration, Net.builtins)
        val download = resolveImportedNodeSelection(
            p.acceleration.downloadMode,
            p.acceleration.downloadManualId,
            newIdByBackupId[p.acceleration.downloadManualId],
            Net.nodes,
            Net.builtins,
            download = true,
            legacyDownloadFallback = legacyDownload,
        )
        raw.id?.let(Net::setRawManual)
        download.id?.let(Net::setDownloadManual)
        Net.changeRawMode(raw.mode)
        Net.changeDownloadMode(download.mode)
    }
}
