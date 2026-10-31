package com.mobilegh.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.ProxyInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf

enum class ProxyMode(private val labelZh: String) { System("跟随系统"), Http("HTTP"), Socks("SOCKS5");
    val label: String get() = tr(labelZh) }

data class NetworkProxy(val mode: ProxyMode = ProxyMode.System, val host: String = "127.0.0.1", val port: Int = 7890) {
    fun validated(): NetworkProxy {
        if (mode == ProxyMode.System) return this
        val address = host.trim().removeSurrounding("[", "]")
        require(address.isNotBlank() && address.none { it.isWhitespace() || it in "/\\@?#" }) { tr("请输入代理主机名或 IP，不要填写网址、路径或账号") }
        require(port in 1..65535) { tr("端口应为 1–65535") }
        val authority = if (':' in address) "[$address]" else address
        val url = "http://$authority:$port".toHttpUrlOrNull()
        require(url != null && url.host.isNotBlank()) { tr("代理地址无效") }
        return copy(host = url.host)
    }

    internal fun proxy(): Proxy? = if (mode == ProxyMode.System) null else Proxy(
        if (mode == ProxyMode.Http) Proxy.Type.HTTP else Proxy.Type.SOCKS,
        InetSocketAddress.createUnresolved(host, port),
    )
}

/** 只改变传输线路，不改请求域名，不继承 API 的 Token 拦截器或内容镜像设置。 */
object AppNetwork {
    private lateinit var context: Context
    @Volatile private var current = NetworkProxy()
    var config by mutableStateOf(current)
        private set
    /** 经 [routed] 接入的全部客户端（弱引用：客户端被丢弃后自动移除），代理变化时清空它们的空闲连接 */
    private val clients: MutableSet<OkHttpClient> = Collections.newSetFromMap(WeakHashMap<OkHttpClient, Boolean>())

    val description: String get() = if (config.mode == ProxyMode.System) tr("跟随系统代理 / VPN") else "${config.mode.label} · ${config.host}:${config.port}"

    fun init(context: Context) {
        this.context = context.applicationContext
        val prefs = context.getSharedPreferences("network_transport", Context.MODE_PRIVATE)
        val stored = NetworkProxy(
            ProxyMode.entries.firstOrNull { it.name == prefs.getString("mode", null) } ?: ProxyMode.System,
            prefs.getString("host", "127.0.0.1") ?: "127.0.0.1", prefs.getInt("port", 7890),
        )
        current = runCatching { stored.validated() }.getOrDefault(NetworkProxy())
        config = current
    }

    suspend fun save(value: NetworkProxy) = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main.immediate) {
        val valid = value.validated()
        withContext(Dispatchers.IO) { persist(valid) }
        config = valid
    }

    @Synchronized
    private fun persist(valid: NetworkProxy) {
        check(context.getSharedPreferences("network_transport", Context.MODE_PRIVATE).edit()
            .putString("mode", valid.mode.name).putString("host", valid.host).putInt("port", valid.port).commit()) { tr("无法保存网络设置") }
        // 空闲连接按路由（含代理）复用，这里清掉旧路由的连接，之后的请求只会按新设置建立连接
        clients.forEach { it.connectionPool.evictAll() }
        current = valid
        AppLog.info("network", trf("传输线路已切换：{0}", valid.mode.label))
    }

    @Synchronized
    internal fun client(base: OkHttpClient): OkHttpClient {
        // 所有客户端都带同一个选择器，这里不再单独设置代理，避免两套规则不一致；只登记，供代理变化时清理连接池
        clients.add(base)
        return base
    }

    /**
     * 所有 OkHttp 客户端共用的代理选择：手动代理优先；「跟随系统」时读取当前网络的系统代理。
     *
     * OkHttp 默认只看 JVM 的代理属性，Android 上 Wi-Fi 手动代理和设备全局代理都不会进入其中，
     * 所以之前即使在系统里设了代理，应用也是直连。VPN 不需要这里处理：它在网络层接管流量。
     */
    fun proxySelector(fixed: NetworkProxy? = null): ProxySelector = object : ProxySelector() {
        // 不在创建时固定设置：Api.http 等客户端可能早于用户修改代理就已创建，必须每次请求时读取当前值
        override fun select(uri: URI?): List<Proxy> {
            val value = fixed ?: current
            if (value.mode != ProxyMode.System) return listOf(value.proxy() ?: Proxy.NO_PROXY)
            val info = systemProxyInfo()
            return systemProxies(info?.host, info?.port ?: -1)
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
    }

    private fun systemProxyInfo(): ProxyInfo? {
        if (!::context.isInitialized) return null
        return runCatching {
            (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).defaultProxy
        }.getOrNull()
    }

    /** 系统给出了主机和端口时使用它；PAC 自动配置脚本无法在这里执行，只能直连。 */
    internal fun systemProxies(host: String?, port: Int): List<Proxy> {
        val name = host?.takeIf { it.isNotBlank() }
        return if (name != null && port in 1..65535) listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(name, port)))
        else listOf(Proxy.NO_PROXY)
    }

    data class Connection(val label: String, val reachable: Boolean, val message: String)

    /** 仅在用户点按时检查官方 HTTPS 地址，不携带登录凭据，也不跟随跳转。 */
    suspend fun check(value: NetworkProxy): List<Connection> = coroutineScope {
        val valid = value.validated()
        // 数字审批注册推送设备还需要 Google 的两个接口，一并检查避免「GitHub 全绿却启用失败」
        listOf(
            tr("GitHub 登录") to "https://github.com/",
            "GitHub API" to "https://api.github.com/",
            tr("Google 推送注册") to "https://android.clients.google.com/",
            tr("Google 设备凭证") to "https://firebaseinstallations.googleapis.com/",
        ).map { (label, url) ->
            async(Dispatchers.IO) {
                val client = OkHttpClient.Builder().proxySelector(proxySelector(valid))
                    .connectTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
                    .followRedirects(false).build()
                try {
                    val request = Request.Builder().url(url).head().header("User-Agent", "MobileGH").build()
                    client.newCall(request).await().use {
                        Connection(label, true, trf("已连通（HTTP {0}）", it.code))
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Connection(label, false, e.networkHint() ?: tr("连接失败，请检查代理地址及端口"))
                } finally {
                    client.connectionPool.evictAll()
                    client.dispatcher.executorService.shutdown()
                }
            }
        }.map { it.await() }
    }
}

fun OkHttpClient.routed(): OkHttpClient = AppNetwork.client(this)
