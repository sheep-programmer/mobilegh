package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadApiTest {
    @Test fun subscriptionPathAndBodyIgnoreAndRestore() {
        assertEquals("/notifications/threads/42/subscription", threadSubscriptionPath("42"))
        assertEquals("""{"ignored":true}""", subscriptionBody(ignored = true).toString())
        assertEquals("""{"ignored":false}""", subscriptionBody(ignored = false).toString())
    }

    @Test fun repoNotificationsPathAndLastReadBody() {
        assertEquals("/repos/octo/hello/notifications", repoNotificationsPath("octo", "hello"))
        assertEquals(
            """{"last_read_at":"2026-10-31T08:00:00Z"}""",
            repoReadBody("2026-10-31T08:00:00Z").toString(),
        )
    }
}
