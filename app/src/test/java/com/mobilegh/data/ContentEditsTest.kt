package com.mobilegh.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class ContentEditsTest {

    @Test
    fun revisionReportsDeletedStateCorrectly() {
        val active = ContentEdits.Revision(
            id = "UCE_1",
            createdAt = "2026-11-08T08:00:00Z",
            editor = "alice",
            body = "Active text",
            deletedAt = null,
            deletedBy = null,
        )
        assertFalse(active.isDeleted)

        val deleted = ContentEdits.Revision(
            id = "UCE_2",
            createdAt = "2026-11-08T08:00:00Z",
            editor = "alice",
            body = null,
            deletedAt = "2026-11-08T08:10:00Z",
            deletedBy = "alice",
        )
        assertTrue(deleted.isDeleted)
    }

    @Test
    fun deleteThrowsWebSessionMissingWhenCookieLacksUserSession() = runBlocking {
        try {
            ContentEdits.delete(
                revisionId = "UCE_123",
                cookieHeader = { "logged_in=yes" },
            )
            fail("Expected WebSessionMissing")
        } catch (e: WebSessionMissing) {
            // expected
        }
    }

    @Test
    fun deleteThrowsWebSessionMissingWhenCookieIsNull() = runBlocking {
        try {
            ContentEdits.delete(
                revisionId = "UCE_123",
                cookieHeader = { null },
            )
            fail("Expected WebSessionMissing")
        } catch (e: WebSessionMissing) {
            // expected
        }
    }

    @Test
    fun deleteDispatchesCorrectDeleteRequestWithHeaders() = runBlocking {
        var recordedMethod: String? = null
        var recordedUrl: String? = null
        var recordedCookie: String? = null
        var recordedVerifiedFetch: String? = null
        var recordedRequestedWith: String? = null
        var recordedOrigin: String? = null
        var recordedReferer: String? = null

        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            recordedMethod = req.method
            recordedUrl = req.url.toString()
            recordedCookie = req.header("Cookie")
            recordedVerifiedFetch = req.header("GitHub-Verified-Fetch")
            recordedRequestedWith = req.header("X-Requested-With")
            recordedOrigin = req.header("Origin")
            recordedReferer = req.header("Referer")

            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"data":{"deleteUserContentEdit":{}}}""".toResponseBody())
                .build()
        }.build()

        ContentEdits.delete(
            revisionId = "UCE_TEST_999",
            cookieHeader = { "user_session=abc123xyz; other=cookie" },
            client = client,
        )

        assertEquals("DELETE", recordedMethod)
        assertEquals("https://github.com/user_content_edits/UCE_TEST_999.json", recordedUrl)
        assertEquals("user_session=abc123xyz; other=cookie", recordedCookie)
        assertEquals("true", recordedVerifiedFetch)
        assertEquals("XMLHttpRequest", recordedRequestedWith)
        assertEquals("https://github.com", recordedOrigin)
        assertEquals("https://github.com/", recordedReferer)
    }

    @Test
    fun deleteThrowsWebSessionMissingOnUnauthorizedOrForbidden() = runBlocking {
        val client401 = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .body("".toResponseBody())
                .build()
        }.build()

        try {
            ContentEdits.delete(
                revisionId = "UCE_TEST",
                cookieHeader = { "user_session=expired" },
                client = client401,
            )
            fail("Expected WebSessionMissing on 401")
        } catch (e: WebSessionMissing) {
            // expected
        }

        val client403 = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(403)
                .message("Forbidden")
                .body("".toResponseBody())
                .build()
        }.build()

        try {
            ContentEdits.delete(
                revisionId = "UCE_TEST",
                cookieHeader = { "user_session=no_access" },
                client = client403,
            )
            fail("Expected WebSessionMissing on 403")
        } catch (e: WebSessionMissing) {
            // expected
        }
    }

    @Test
    fun deleteThrowsIOExceptionOnServerError() = runBlocking {
        val client500 = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(500)
                .message("Server Error")
                .body("Internal Server Error".toResponseBody())
                .build()
        }.build()

        try {
            ContentEdits.delete(
                revisionId = "UCE_TEST",
                cookieHeader = { "user_session=valid" },
                client = client500,
            )
            fail("Expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("500") == true)
        }
    }
}
