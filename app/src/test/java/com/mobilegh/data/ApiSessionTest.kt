package com.mobilegh.data

import java.nio.file.Files
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApiSessionTest {
    private fun setToken(value: String?) {
        Session::class.java.getDeclaredField("token").apply { isAccessible = true }.set(null, value)
    }

    private fun respond(authorization: String? = null, beforeResponse: () -> Unit = {}) {
        Api.cacheDir = Files.createTempDirectory("api-session-test").toFile().apply { deleteOnExit() }
        val client = Api.http.newBuilder().addInterceptor { chain ->
            beforeResponse()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").header("X-OAuth-Scopes", "repo")
                .body("{}".toResponseBody()).build()
        }.build()
        val request = Request.Builder().url("https://api.github.com/user")
        authorization?.let { request.header("Authorization", it) }
        client.newCall(request.build()).execute().close()
    }

    private fun withSession(test: () -> Unit) {
        val token = Session.token
        val generation = Session.generation
        val scopes = Session.grantedScopes
        val login = Session.login
        try {
            Session.login = null // No persisted account is needed for these response checks.
            setToken("current-token")
            Session.applyScopes(null)
            test()
        } finally {
            setToken(token)
            Session.generation = generation
            Session.applyScopes(scopes)
            Session.login = login
        }
    }

    @Test fun explicitOtherTokenCannotSetCurrentScopes() = withSession {
        respond(authorization = "Bearer other-token")
        assertNull(Session.grantedScopes)
    }

    @Test fun accountSwitchDiscardsOldResponseScopes() = withSession {
        respond { setToken("next-account-token"); Session.generation++ }
        assertNull(Session.grantedScopes)
    }

    @Test fun switchingAwayAndBackDiscardsPreviousGenerationScopes() = withSession {
        respond { Session.generation += 2 }
        assertNull(Session.grantedScopes)
    }

    @Test fun currentAccountResponseFillsUnknownScopes() = withSession {
        respond()
        assertEquals(setOf("repo"), Session.grantedScopes)
    }
}
