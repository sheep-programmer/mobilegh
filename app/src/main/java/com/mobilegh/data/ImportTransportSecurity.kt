package com.mobilegh.data

import java.io.InputStream
import java.io.OutputStream
import java.net.Proxy
import java.net.URL
import java.security.SecureRandom
import java.util.Locale
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.KeyManager
import javax.net.ssl.TrustManager
import org.eclipse.jgit.api.TransportConfigCallback
import org.eclipse.jgit.transport.Transport
import org.eclipse.jgit.transport.TransportHttp
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.http.HttpConnection
import org.eclipse.jgit.transport.http.HttpConnectionFactory
import org.eclipse.jgit.transport.http.HttpConnectionFactory2

/**
 * JGit clears cached Basic authorization when a redirect changes host, but retains it when only
 * the port changes. Bind every request to the original URL origin so clone and push redirects
 * cannot forward either repository's Authorization header to a different origin.
 */
internal object ImportTransportSecurity : TransportConfigCallback {
    override fun configure(transport: Transport) {
        val http = transport as? TransportHttp ?: return
        http.httpConnectionFactory = OriginBoundHttpConnectionFactory(
            delegate = http.httpConnectionFactory,
            origin = HttpOrigin(http.uri),
        )
    }
}

private data class HttpOrigin(
    val scheme: String,
    val host: String,
    val port: Int,
) {
    constructor(uri: URIish) : this(
        scheme = uri.scheme.orEmpty().lowercase(Locale.ROOT),
        host = uri.host.orEmpty().lowercase(Locale.ROOT),
        port = normalizedPort(uri.scheme, uri.port),
    )

    constructor(url: URL) : this(
        scheme = url.protocol.lowercase(Locale.ROOT),
        host = url.host.lowercase(Locale.ROOT),
        port = normalizedPort(url.protocol, url.port),
    )

    companion object {
        private fun normalizedPort(scheme: String?, port: Int): Int {
            if (port >= 0) return port
            return when (scheme?.lowercase(Locale.ROOT)) {
                "http" -> 80
                "https" -> 443
                else -> -1
            }
        }
    }
}

private class OriginBoundHttpConnectionFactory(
    private val delegate: HttpConnectionFactory,
    private val origin: HttpOrigin,
) : HttpConnectionFactory2 {
    override fun create(url: URL): HttpConnection =
        wrap(delegate.create(url), url)

    override fun create(url: URL, proxy: Proxy): HttpConnection =
        wrap(delegate.create(url, proxy), url)

    override fun newSession(): HttpConnectionFactory2.GitSession {
        val delegateSession = (delegate as? HttpConnectionFactory2)?.newSession()
        return object : HttpConnectionFactory2.GitSession {
            override fun configure(connection: HttpConnection?, sslVerify: Boolean): HttpConnection? {
                if (delegateSession == null) return connection
                val bounded = connection as? OriginBoundHttpConnection
                // JDKHttpConnectionFactory requires its concrete JDKHttpConnection, not our wrapper.
                val configured = delegateSession.configure(bounded?.delegate ?: connection, sslVerify)
                return if (bounded != null && configured != null) {
                    OriginBoundHttpConnection(configured, bounded.allowAuthorization)
                } else {
                    configured
                }
            }

            override fun close() = delegateSession?.close() ?: Unit
        }
    }

    private fun wrap(connection: HttpConnection, url: URL): HttpConnection =
        OriginBoundHttpConnection(connection, allowAuthorization = origin == HttpOrigin(url))
}

private class OriginBoundHttpConnection(
    val delegate: HttpConnection,
    val allowAuthorization: Boolean,
) : HttpConnection {
    override fun setRequestProperty(key: String, value: String) {
        if (!allowAuthorization && key.equals("Authorization", ignoreCase = true)) return
        delegate.setRequestProperty(key, value)
    }

    override fun getResponseCode(): Int = delegate.responseCode

    override fun getURL(): URL = delegate.url

    override fun getResponseMessage(): String? = delegate.responseMessage

    override fun getHeaderFields(): MutableMap<String, MutableList<String>>? = delegate.headerFields

    override fun setRequestMethod(method: String) = delegate.setRequestMethod(method)

    override fun setUseCaches(useCaches: Boolean) = delegate.setUseCaches(useCaches)

    override fun setConnectTimeout(timeout: Int) = delegate.setConnectTimeout(timeout)

    override fun setReadTimeout(timeout: Int) = delegate.setReadTimeout(timeout)

    override fun getContentType(): String? = delegate.contentType

    override fun getInputStream(): InputStream = delegate.inputStream

    override fun getHeaderField(name: String): String? = delegate.getHeaderField(name)

    override fun getHeaderFields(name: String): MutableList<String>? = delegate.getHeaderFields(name)

    override fun getContentLength(): Int = delegate.contentLength

    override fun setInstanceFollowRedirects(followRedirects: Boolean) =
        delegate.setInstanceFollowRedirects(followRedirects)

    override fun setDoOutput(doOutput: Boolean) = delegate.setDoOutput(doOutput)

    override fun setFixedLengthStreamingMode(contentLength: Int) =
        delegate.setFixedLengthStreamingMode(contentLength)

    override fun getOutputStream(): OutputStream = delegate.outputStream

    override fun setChunkedStreamingMode(chunkLength: Int) = delegate.setChunkedStreamingMode(chunkLength)

    override fun getRequestMethod(): String? = delegate.requestMethod

    override fun usingProxy(): Boolean = delegate.usingProxy()

    override fun connect() = delegate.connect()

    override fun configure(
        keyManagers: Array<KeyManager>?,
        trustManagers: Array<TrustManager>?,
        secureRandom: SecureRandom?,
    ) = delegate.configure(keyManagers, trustManagers, secureRandom)

    override fun setHostnameVerifier(verifier: HostnameVerifier?) =
        delegate.setHostnameVerifier(verifier)
}
