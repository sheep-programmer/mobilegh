package com.mobilegh.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class UserStatusTest {
    @Test
    fun userStatusEmptyDetection() {
        val empty1 = UserStatus()
        assertTrue(empty1.isEmpty)

        val empty2 = UserStatus(emoji = "", message = "  ")
        assertTrue(empty2.isEmpty)

        val withEmoji = UserStatus(emoji = "🎯", message = "")
        assertFalse(withEmoji.isEmpty)

        val withMessage = UserStatus(emoji = null, message = "Focusing")
        assertFalse(withMessage.isEmpty)
    }

    @Test
    fun userStatusSerialization() {
        val json = Json { ignoreUnknownKeys = true }
        val status = UserStatus(
            emoji = "🌴",
            message = "On vacation",
            indicatesLimitedAvailability = true,
            expiresAt = "2026-11-10T00:00:00Z"
        )
        val encoded = json.encodeToString(status)
        val decoded = json.decodeFromString<UserStatus>(encoded)

        assertEquals("🌴", decoded.emoji)
        assertEquals("On vacation", decoded.message)
        assertTrue(decoded.indicatesLimitedAvailability)
        assertEquals("2026-11-10T00:00:00Z", decoded.expiresAt)
    }

    @Test
    fun colonEmojiDecodedCorrectly() {
        val s1 = UserStatus(emoji = ":yum:", message = "eating grass now...")
        assertEquals("😋", s1.displayEmoji)

        val s2 = UserStatus(emoji = ":palm_tree:")
        assertEquals("🌴", s2.displayEmoji)

        val s3 = UserStatus(emoji = "🎯")
        assertEquals("🎯", s3.displayEmoji)

        assertEquals("🚀", GitHubEmoji.decode(":rocket:"))
        assertEquals("😋", GitHubEmoji.decode("yum"))
        assertEquals("💻", GitHubEmoji.decode(":computer:"))
    }
}
