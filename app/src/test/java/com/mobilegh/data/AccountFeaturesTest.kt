package com.mobilegh.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AccountFeaturesTest {
    @Test fun subscriptionModesKeepIgnoredSeparateFromUnsubscribed() {
        assertEquals(SubscriptionMode.Participating, RepositorySubscription().mode)
        assertEquals(SubscriptionMode.Watching, RepositorySubscription(subscribed = true).mode)
        assertEquals(SubscriptionMode.Ignored, RepositorySubscription(subscribed = true, ignored = true).mode)
        assertNull(SubscriptionSettings.body(SubscriptionMode.Participating))
        assertEquals(JsonPrimitive(true), SubscriptionSettings.body(SubscriptionMode.Ignored)?.get("ignored"))
        assertEquals(JsonPrimitive(false), SubscriptionSettings.body(SubscriptionMode.Watching)?.get("ignored"))
    }
    @Test fun profileEditsSendOnlyChangedFields() {
        val original = ProfileFields(name = "Old", bio = "Keep", website = "example.com", hireable = true)
        val body = original.copy(name = "New").patch(original)
        assertEquals(setOf("name"), body.keys)
        assertEquals("New", body["name"]?.jsonPrimitive?.content)
    }
    @Test fun clearingFieldsAndChangingWebsiteUseTheCorrectApiKeys() {
        val original = ProfileFields(bio = "Old", twitter = "mona")
        val body = original.copy(bio = "", twitter = "", website = "example.com", hireable = true).patch(original)
        assertEquals(JsonNull, body["twitter_username"])
        assertEquals("https://example.com", body["blog"]?.jsonPrimitive?.content)
        assertEquals("", body["bio"]?.jsonPrimitive?.content)
        assertEquals(JsonPrimitive(true), body["hireable"])
    }
    @Test fun invalidWebsiteAndOversizedBioDoNotProduceARequest() {
        assertThrows(IllegalArgumentException::class.java) { ProfileFields(website = "javascript://example.com").patch(ProfileFields()) }
        assertThrows(IllegalArgumentException::class.java) { ProfileFields(bio = "x".repeat(161)).patch(ProfileFields()) }
    }
}
