package com.mobilegh.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SavedItemsTest {
    @Test
    fun savedItemSerializationAndKinds() {
        val json = Json { ignoreUnknownKeys = true }
        val item = SavedItem(
            id = "issue:owner/repo#42",
            kind = SavedKind.Issue,
            owner = "owner",
            name = "repo",
            number = 42,
            title = "Test Issue",
            state = "open"
        )
        val encoded = json.encodeToString(item)
        val decoded = json.decodeFromString<SavedItem>(encoded)

        assertEquals("issue:owner/repo#42", decoded.id)
        assertEquals(SavedKind.Issue, decoded.kind)
        assertEquals("owner", decoded.owner)
        assertEquals("repo", decoded.name)
        assertEquals(42, decoded.number)
        assertEquals("Test Issue", decoded.title)
        assertEquals("open", decoded.state)
    }

    @Test
    fun listSerialization() {
        val json = Json { ignoreUnknownKeys = true }
        val items = listOf(
            SavedItem("issue:o/r#1", SavedKind.Issue, "o", "r", 1, "Issue 1"),
            SavedItem("repo:o/r", SavedKind.Repo, "o", "r", null, "Repo 1"),
            SavedItem("pull:o/r#2", SavedKind.PullRequest, "o", "r", 2, "PR 2"),
            SavedItem("discussion:o/r#3", SavedKind.Discussion, "o", "r", 3, "Disc 3")
        )
        val encoded = json.encodeToString(items)
        val decoded = json.decodeFromString<List<SavedItem>>(encoded)

        assertEquals(4, decoded.size)
        assertEquals(SavedKind.Repo, decoded[1].kind)
        assertEquals(SavedKind.Discussion, decoded[3].kind)
    }
}
