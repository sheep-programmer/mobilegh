package com.mobilegh.data

import android.content.Context
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
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.IdentityHashMap
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
    private val clients = IdentityHashMap<OkHttpClient, OkHttpClient>()

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
        clients.values.forEach { it.connectionPool.evictAll() }
        clients.clear()
        current = valid
        AppLog.info("network", trf("传输线路已切换：{0}", valid.mode.label))
    }

    @Synchronized
    internal fun client(base: OkHttpClient): OkHttpClient {
        val selected = current
        if (selected.mode == ProxyMode.System) return base
        return clients.getOrPut(base) {
            base.newBuilder().proxy(selected.proxy()).connectionPool(ConnectionPool()).build()
        }
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
                val client = OkHttpClient.Builder().proxy(valid.proxy())
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
