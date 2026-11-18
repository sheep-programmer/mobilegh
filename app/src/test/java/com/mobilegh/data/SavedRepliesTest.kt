package com.mobilegh.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedRepliesTest {
    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject
    private fun data(text: String) = json("""{"data":$text}""")

    @Test fun readsViewerRepliesAndPageCursor() = runBlocking {
        val page = SavedReplies.savedReplies("cursor-1") { query, variables ->
            assertTrue(query.contains("viewer"))
            assertTrue(query.contains("savedReplies(first: 30, after: \$after)"))
            assertEquals("cursor-1", variables["after"])
            data("""
                {"viewer":{"savedReplies":{"nodes":[
                  {"id":"SR_one","title":"Thanks","body":"Thank you!"},
                  {"id":"SR_two","title":"Needs info","body":"Please add reproduction steps."}
                ],"pageInfo":{"hasNextPage":true,"endCursor":"cursor-2"}}}}
            """)
        }
        assertEquals(listOf("SR_one", "SR_two"), page.items.map { it.id })
        assertEquals("Thanks", page.items[0].title)
        assertEquals("Please add reproduction steps.", page.items[1].body)
        assertEquals("cursor-2", page.endCursor)
        assertTrue(page.hasNextPage)
    }

    @Test fun firstPageUsesANullCursor() = runBlocking {
        val page = SavedReplies.savedReplies { _, variables ->
            assertNull(variables["after"])
            data("""{"viewer":{"savedReplies":{"nodes":[],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}""")
        }
        assertTrue(page.items.isEmpty())
        assertFalse(page.hasNextPage)
        assertNull(page.endCursor)
    }

    @Test fun nullViewerConnectionAndNodesBecomeAnEmptyPage() = runBlocking {
        for (response in listOf(
            """{"viewer":null}""",
            """{"viewer":{"savedReplies":null}}""",
            """{"viewer":{"savedReplies":{"nodes":null}}}""",
        )) {
            val page = SavedReplies.savedReplies { _, _ -> data(response) }
            assertTrue(page.items.isEmpty())
            assertFalse(page.hasNextPage)
            assertNull(page.endCursor)
        }
        Unit
    }

    @Test fun missingScopeWithPartialDataDoesNotLookLikeAnEmptyList() = runBlocking {
        val result = runCatching {
            SavedReplies.savedReplies { _, _ ->
                json("""
                    {"data":{"viewer":{"savedReplies":null}},"errors":[{
                      "type":"INSUFFICIENT_SCOPES",
                      "message":"Your token has not been granted the required scopes to execute this query. The 'savedReplies' field requires one of the following scopes: ['read:user']."
                    }]}
                """)
            }
        }
        val error = result.exceptionOrNull() as ApiException
        assertTrue(error.message.contains("read:user"))
        assertTrue(error.message.contains("保存的回复"))
    }
}
