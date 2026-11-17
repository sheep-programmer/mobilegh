package com.mobilegh.widget

import com.mobilegh.data.WidgetStore
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetVersionsTest {
    @Test fun updatingOneKindDoesNotInvalidateOtherWidgets() {
        val versions = WidgetVersions()

        versions.update(WidgetStore.Kind.Shortcuts)

        assertEquals(1, versions.value(WidgetStore.Kind.Shortcuts))
        assertEquals(0, versions.value(WidgetStore.Kind.PullRequests))
        assertEquals(0, versions.value(WidgetStore.Kind.Inbox))
        assertEquals(0, versions.value(WidgetStore.Kind.Contributions))
    }

    @Test fun globalUpdateInvalidatesEveryWidgetKind() {
        val versions = WidgetVersions()
        versions.update(WidgetStore.Kind.PullRequests)

        versions.update(null)

        WidgetStore.Kind.entries.forEach { kind ->
            val expected = if (kind == WidgetStore.Kind.PullRequests) 2 else 1
            assertEquals(expected, versions.value(kind))
        }
    }
}
