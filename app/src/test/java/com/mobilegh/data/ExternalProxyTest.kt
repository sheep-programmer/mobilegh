package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalProxyTest {
    @Test fun manualProxyIsPassedToCompanionUnchanged() {
        val proxy = NetworkProxy(ProxyMode.Http, "127.0.0.1", 7890)
        assertEquals(proxy, AppNetwork.externalProxy(proxy, "10.0.0.2", 8080))
    }

    @Test fun systemProxyIsConvertedToAnHttpProxyForTheCompanion() {
        assertEquals(
            NetworkProxy(ProxyMode.Http, "10.0.0.2", 8080),
            AppNetwork.externalProxy(NetworkProxy(), "10.0.0.2", 8080),
        )
    }
}
