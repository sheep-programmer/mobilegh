package com.mobilegh.ui.components

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobilegh.R
import com.mobilegh.i18n.tr
import com.mobilegh.ui.theme.Gh
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

internal enum class ConnectivityNotice { Hidden, Offline, Recovered }

internal fun connectivityNotice(previous: ConnectivityNotice, online: Boolean): ConnectivityNotice = when {
    !online -> ConnectivityNotice.Offline
    previous == ConnectivityNotice.Offline -> ConnectivityNotice.Recovered
    else -> ConnectivityNotice.Hidden
}

private fun connectivity(manager: ConnectivityManager) = callbackFlow {
    val lock = Any()
    var current: Network? = null
    var callbackReady = false
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(lock) {
                callbackReady = true
                current = network
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            synchronized(lock) {
                if (current == network) trySend(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            }
        }

        override fun onLost(network: Network) {
            synchronized(lock) {
                callbackReady = true
                if (current == null || current == network) {
                    current = null
                    trySend(false)
                }
            }
        }
    }
    try {
        manager.registerDefaultNetworkCallback(callback)
    } catch (_: RuntimeException) {
        close()
        return@callbackFlow
    }
    synchronized(lock) {
        if (!callbackReady) {
            current = manager.activeNetwork
            trySend(manager.getNetworkCapabilities(current)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
        }
    }
    awaitClose { manager.unregisterNetworkCallback(callback) }
}.distinctUntilChanged()

@Composable
internal fun ConnectivityBanner(modifier: Modifier = Modifier) {
    val context = rememberCtx()
    val notice by produceState(ConnectivityNotice.Hidden, context) {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return@produceState
        connectivity(manager).collectLatest { online ->
            if (!online) delay(350)
            value = connectivityNotice(value, online)
            if (value == ConnectivityNotice.Recovered) {
                delay(2_500)
                value = ConnectivityNotice.Hidden
            }
        }
    }
    if (notice == ConnectivityNotice.Hidden) return
    val offline = notice == ConnectivityNotice.Offline
    val colors = Gh.c
    val tint = if (offline) colors.attention else colors.success
    Row(
        modifier.fillMaxWidth()
            .background(if (offline) colors.attentionSubtle else colors.successSubtle)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Oc(if (offline) R.drawable.oc_alert else R.drawable.oc_check_circle_fill, tint, 14.dp)
        Text(tr(if (offline) "网络已断开，已加载内容仍可查看" else "网络已恢复"), color = colors.fg, fontSize = 12.sp)
    }
}
