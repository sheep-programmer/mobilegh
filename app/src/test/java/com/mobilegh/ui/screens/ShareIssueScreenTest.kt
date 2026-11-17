package com.mobilegh.ui.screens

import com.mobilegh.data.Repo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareIssueScreenTest {
    @Test fun successfulEmptyFirstPageStopsLoading() {
        assertFalse(showShareRepositoryLoading(emptyList(), emptyList(), error = null))
    }

    @Test fun failedFirstPageOffersRetryInsteadOfAnEmptyDeadEnd() {
        assertFalse(showShareRepositoryLoading(emptyList(), loadedPage = null, error = "offline"))
        assertTrue(canLoadMoreSharedRepositories(emptyList(), loadedPage = null, error = "offline"))
    }

    @Test fun failedNextPageKeepsRepositoriesAndOffersRetry() {
        val repositories = listOf(Repo(fullName = "octo/hello"))

        assertFalse(showShareRepositoryLoading(repositories, loadedPage = null, error = "offline"))
        assertTrue(canLoadMoreSharedRepositories(repositories, loadedPage = null, error = "offline"))
    }
}
