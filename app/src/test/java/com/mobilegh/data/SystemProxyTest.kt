package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.Proxy

class SystemProxyTest {
    @Test fun aSystemHttpProxyIsUsedWhenTheNetworkReportsOne() {
        val proxies = AppNetwork.systemProxies("10.0.2.2", 8888)
        assertEquals(Proxy.Type.HTTP, proxies.single().type())
        assertEquals("10.0.2.2", (proxies.single().address() as java.net.InetSocketAddress).hostString)
        assertEquals(8888, (proxies.single().address() as java.net.InetSocketAddress).port)
    }

    @Test fun noReportedProxyMeansDirectSoAVpnCarriesTheTraffic() {
        assertEquals(listOf(Proxy.NO_PROXY), AppNetwork.systemProxies(null, -1))
        assertEquals(listOf(Proxy.NO_PROXY), AppNetwork.systemProxies("", 8888))
    }

    @Test fun invalidPortsAreIgnored() {
        assertEquals(listOf(Proxy.NO_PROXY), AppNetwork.systemProxies("10.0.2.2", 0))
        assertEquals(listOf(Proxy.NO_PROXY), AppNetwork.systemProxies("10.0.2.2", 70000))
    }
}
