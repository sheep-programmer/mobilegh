package com.mobilegh.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Serializes token rotation for OkHttp worker threads. A dedicated auth client avoids recursion. */
object OAuthRefresh {
    private val lock = Any()
    private var lastFailureAt = 0L
    private var failedToken: String? = null

    fun renew(expectedToken: String): String? = synchronized(lock) {
        // A parallel request already refreshed this account; retry with that token.
        if (Session.token != expectedToken) {
            return@synchronized Session.oauth?.takeIf { it.token == Session.token && it.previousToken == expectedToken }?.token
        }
        val current = Session.oauth?.takeIf { it.token == expectedToken && it.clientId.isNotBlank() } ?: return@synchronized null
        if (failedToken == expectedToken && System.currentTimeMillis() - lastFailureAt < 30_000) return@synchronized null
        try {
            runBlocking(Dispatchers.IO) {
                val r = DeviceAuth.refresh(current.clientId, current.refreshToken)
                if (r !is DevicePoll.Authorized) {
                    AppLog.warn("auth", "OAuth 刷新未成功，需要重新授权")
                    failedToken = expectedToken; lastFailureAt = System.currentTimeMillis()
                    return@runBlocking null
                }
                withContext(Dispatchers.Main) { Session.renewOAuth(expectedToken, r) }
            }
        } catch (e: Exception) {
            // Do not log the refresh response/body: it contains rotating credentials.
            AppLog.warn("auth", "OAuth 刷新请求未完成")
            failedToken = expectedToken; lastFailureAt = System.currentTimeMillis()
            null
        }
    }
}
