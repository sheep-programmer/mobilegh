package com.mobilegh.data

import java.net.Proxy
import java.net.URL
import org.eclipse.jgit.transport.Transport
import org.eclipse.jgit.transport.TransportHttp
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.http.HttpConnection
import org.eclipse.jgit.transport.http.HttpConnectionFactory2
import org.eclipse.jgit.transport.http.JDKHttpConnectionFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportTransportSecurityTest {
    @Test
    fun originBoundFactoryKeepsHttp2SessionLifecycle() {
        var sessions = 0
        var configurations = 0
        var closed = false
        val delegateSession = object : HttpConnectionFactory2.GitSession {
            override fun configure(connection: HttpConnection?, sslVerify: Boolean): HttpConnection? {
                configurations += 1
                return connection
            }

            override fun close() {
                closed = true
            }
        }
        val delegate = object : HttpConnectionFactory2 {
            override fun create(url: URL): HttpConnection = error("not used")

            override fun create(url: URL, proxy: Proxy): HttpConnection = error("not used")

            override fun newSession(): HttpConnectionFactory2.GitSession {
                sessions += 1
                return delegateSession
            }
        }
        val transport = Transport.open(URIish("https://source.example/repository.git")) as TransportHttp
        try {
            transport.httpConnectionFactory = delegate
            ImportTransportSecurity.configure(transport)

            val factory = transport.httpConnectionFactory
            assertTrue(factory is HttpConnectionFactory2)
            val session = (factory as HttpConnectionFactory2).newSession()
            assertNull(session.configure(null, true))
            session.close()
            assertEquals(1, sessions)
            assertEquals(1, configurations)
            assertTrue(closed)
        } finally {
            transport.close()
        }
    }

    @Test
    fun jdkSessionConfiguresTheUnderlyingConnectionBeforeRewrapping() {
        val transport = Transport.open(URIish("https://source.example/repository.git")) as TransportHttp
        try {
            transport.httpConnectionFactory = JDKHttpConnectionFactory()
            ImportTransportSecurity.configure(transport)
            val factory = transport.httpConnectionFactory as HttpConnectionFactory2
            val connection = factory.create(URL("https://source.example/repository.git/info/refs"))
            val session = factory.newSession()

            assertNotNull(session.configure(connection, true))
            session.close()
        } finally {
            transport.close()
        }
    }
}
