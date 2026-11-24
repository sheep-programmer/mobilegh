package com.mobilegh.data

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenAttachmentUploaderTest {
    @Test fun uploadsStreamWithTokenAndReturnsGitHubAssetUrl() = runBlocking {
        val seen = AtomicReference<okhttp3.Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            seen.set(chain.request())
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(201)
                .message("Created")
                .body("{\"url\":\"https://github.com/user-attachments/assets/test\"}".toResponseBody())
                .build()
        }.build()

        val result = TokenAttachmentUploader(client, token = { "token-value" }).upload(
            repositoryId = 1394134859L,
            fileName = "屏幕截图.png",
            mime = "image/png",
            size = 3,
            open = { "abc".byteInputStream() },
        )

        assertEquals("https://github.com/user-attachments/assets/test", result)
        val request = checkNotNull(seen.get())
        assertEquals("Bearer token-value", request.header("Authorization"))
        assertEquals(3L, request.body?.contentLength())
        assertTrue(request.url.toString().contains("repository_id=1394134859"))
        assertTrue(request.url.toString().contains("content_type=image%2Fpng"))
        assertTrue(request.url.toString().contains("%E5%B1%8F%E5%B9%95%E6%88%AA%E5%9B%BE.png"))
    }
}
