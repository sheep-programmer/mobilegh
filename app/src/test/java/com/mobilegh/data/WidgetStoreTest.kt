package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetStoreTest {
    private fun week(vararg counts: Int) = ContribWeek(counts.mapIndexed { i, c -> ContribDay("2026-01-0${i + 1}", c, if (c > 0) "FIRST_QUARTILE" else "NONE", i) })

    @Test fun recentWeeksKeepsTailAndRecountsTotal() {
        val cal = ContribCalendar(totalContributions = 999, weeks = List(30) { week(1, 0, 2) } + week(5))
        val recent = WidgetStore.recentWeeks(cal, 20)
        assertEquals(20, recent.weeks.size)
        assertEquals(19 * 3 + 5, recent.totalContributions)
        assertEquals(5, recent.weeks.last().contributionDays.single().contributionCount)
    }

    @Test fun recentWeeksHandlesShortCalendars() {
        val recent = WidgetStore.recentWeeks(ContribCalendar(weeks = listOf(week(3))), 20)
        assertEquals(1, recent.weeks.size)
        assertEquals(3, recent.totalContributions)
    }

    @Test fun inboxTakesLatestUnreadSubjects() {
        fun n(id: String, unread: Boolean, at: String) = Notification(
            id, unread, updatedAt = at,
            subject = Subject("标题 $id", "https://api.github.com/repos/o/r/issues/$id"),
            repository = Repo(fullName = "o/r"),
        )
        val list = listOf(n("1", true, "2026-01-01T00:00:00Z"), n("2", false, "2026-01-05T00:00:00Z"), n("3", true, "2026-01-03T00:00:00Z"),
            n("4", true, "2026-01-04T00:00:00Z"), n("5", true, "2026-01-02T00:00:00Z"))
        val snap = WidgetStore.inboxOf("octocat", list, more = false, now = 42)
        assertEquals(4, snap.unread)
        assertEquals(listOf("标题 4", "标题 3", "标题 5"), snap.items.map { it.title })
        assertEquals("o/r", snap.items.first().repo)
        assertTrue(snap.items.first().url!!.endsWith("/issues/4"))
        assertEquals(42L, snap.at)
    }
}
