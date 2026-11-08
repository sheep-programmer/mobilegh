package com.mobilegh.ui.components

import com.mobilegh.data.ContribCalendar
import com.mobilegh.data.ContribDay
import com.mobilegh.data.ContribWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ContributionDetailTest {

    @Test
    fun testWeekdayName() {
        assertEquals("周日", weekdayName(0))
        assertEquals("周一", weekdayName(1))
        assertEquals("周二", weekdayName(2))
        assertEquals("周三", weekdayName(3))
        assertEquals("周四", weekdayName(4))
        assertEquals("周五", weekdayName(5))
        assertEquals("周六", weekdayName(6))
    }

    @Test
    fun testWeekdayBreakdownCalculations() {
        val week1 = ContribWeek(
            listOf(
                ContribDay("2026-09-01", 10, "SECOND_QUARTILE", 2), // Tuesday
                ContribDay("2026-09-02", 5, "FIRST_QUARTILE", 3),  // Wednesday
            )
        )
        val week2 = ContribWeek(
            listOf(
                ContribDay("2026-09-08", 20, "THIRD_QUARTILE", 2), // Tuesday
                ContribDay("2026-09-09", 0, "NONE", 3),           // Wednesday
            )
        )
        val cal = ContribCalendar(
            totalContributions = 35,
            weeks = listOf(week1, week2)
        )

        // Find all Tuesdays (weekday = 2)
        val tuesdays = cal.weeks.flatMap { it.contributionDays }.filter { it.weekday == 2 }
        assertEquals(2, tuesdays.size)
        val totalTuesday = tuesdays.sumOf { it.contributionCount }
        assertEquals(30, totalTuesday)

        val best = tuesdays.maxByOrNull { it.contributionCount }
        assertNotNull(best)
        assertEquals(20, best?.contributionCount)
        assertEquals("2026-09-08", best?.date)
    }

    @Test
    fun testLevelOfCalculation() {
        assertEquals(1, levelOf(ContribDay("2026-01-01", 1, "FIRST_QUARTILE", 1)))
        assertEquals(2, levelOf(ContribDay("2026-01-02", 5, "SECOND_QUARTILE", 2)))
        assertEquals(3, levelOf(ContribDay("2026-01-03", 10, "THIRD_QUARTILE", 3)))
        assertEquals(4, levelOf(ContribDay("2026-01-04", 20, "FOURTH_QUARTILE", 4)))
        assertEquals(0, levelOf(ContribDay("2026-01-05", 0, "NONE", 5)))
    }

    @Test
    fun testBuildContributionSlices() {
        val slices = buildContributionSlices(
            commits = 100,
            prs = 10,
            reviews = 5,
            issues = 2,
            repos = 1,
        )
        assertEquals(5, slices.size)
        assertEquals(100, slices[0].value)
        assertEquals(10, slices[1].value)
        assertEquals(5, slices[2].value)
        assertEquals(2, slices[3].value)
        assertEquals(1, slices[4].value)
        assertEquals(118, slices.sumOf { it.value })
    }
}
