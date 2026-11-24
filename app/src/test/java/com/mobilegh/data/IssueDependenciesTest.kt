package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Test

class IssueDependenciesTest {
    @Test fun pathsUseTheIssueNumberAndDependencyId() {
        assertEquals(
            "/repos/octo/app/issues/12/dependencies/blocked_by",
            IssueDependencyPaths.blockedBy("octo", "app", 12),
        )
        assertEquals(
            "/repos/octo/app/issues/12/dependencies/blocking",
            IssueDependencyPaths.blocking("octo", "app", 12),
        )
        assertEquals(
            "/repos/octo/app/issues/12/dependencies/blocked_by/88",
            IssueDependencyPaths.removeBlockedBy("octo", "app", 12, 88),
        )
    }
}
