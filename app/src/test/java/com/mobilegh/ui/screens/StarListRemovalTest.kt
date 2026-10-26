package com.mobilegh.ui.screens

import com.mobilegh.data.Repo
import com.mobilegh.data.User
import com.mobilegh.nav.Pager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class StarListRemovalTest {
    @Test fun removalUpdatesBothRetainedDirectionsAndLeavesOtherListsAlone() = runBlocking {
        val removed = Repo(1, "Hello-World", "octocat/Hello-World", User(login = "octocat"))
        val kept = Repo(2, "Keep", "octocat/Keep", User(login = "octocat"))
        val forward = Pager<Repo>(this) { _, _ -> emptyList() }
        val backward = Pager<Repo>(this) { _, _ -> emptyList() }
        val otherList = Pager<Repo>(this) { _, _ -> emptyList() }
        forward.items += listOf(removed, kept)
        backward.items += listOf(kept, removed)
        otherList.items += removed
        val bag = mutableMapOf<String, Any>(
            "rl-list:list-1:false" to forward,
            "rl-list:list-1:true" to backward,
            "rl-list:list-2:false" to otherList,
        )

        removeRetainedListRepository(bag, "list-1", removed.fullName)

        assertEquals(listOf(kept), forward.items)
        assertEquals(listOf(kept), backward.items)
        assertEquals(listOf(removed), otherList.items)
    }
}
