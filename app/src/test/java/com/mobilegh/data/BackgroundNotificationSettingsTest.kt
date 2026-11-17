package com.mobilegh.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundNotificationSettingsTest {
    private val utc: ZoneId = ZoneId.of("UTC")
    private fun at(text: String) = Instant.parse(text)

    @Test fun disabledWorkingHoursAllowNotificationsAtAnyTime() {
        val hours = WorkingHours(enabled = false, startMinute = 9 * 60, endMinute = 17 * 60, days = emptySet())
        assertTrue(hours.allows(at("2026-11-14T03:00:00Z"), utc))
    }

    @Test fun daytimeWindowIncludesStartAndExcludesEnd() {
        val hours = WorkingHours(true, 9 * 60, 17 * 60)
        assertTrue(hours.allows(at("2026-11-16T09:00:00Z"), utc))
        assertTrue(hours.allows(at("2026-11-16T16:59:00Z"), utc))
        assertFalse(hours.allows(at("2026-11-16T17:00:00Z"), utc))
        assertFalse(hours.allows(at("2026-11-16T08:59:00Z"), utc))
    }

    @Test fun overnightWindowUsesTheDayOnWhichTheWindowStarted() {
        val mondayOnly = WorkingHours(true, 22 * 60, 6 * 60, setOf(DayOfWeek.MONDAY))
        assertTrue(mondayOnly.allows(at("2026-11-16T23:00:00Z"), utc))
        assertTrue(mondayOnly.allows(at("2026-11-17T05:59:00Z"), utc))
        assertFalse(mondayOnly.allows(at("2026-11-17T23:00:00Z"), utc))
        assertFalse(mondayOnly.allows(at("2026-11-18T05:00:00Z"), utc))
    }

    @Test fun everyDayAndCustomWeekdaysAreRespected() {
        val everyDay = WorkingHours(true, 8 * 60, 20 * 60)
        assertTrue(everyDay.allows(at("2026-11-15T12:00:00Z"), utc))
        val weekdays = WorkingHours(true, 8 * 60, 20 * 60, setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY))
        assertFalse(weekdays.allows(at("2026-11-15T12:00:00Z"), utc))
        assertTrue(weekdays.allows(at("2026-11-16T12:00:00Z"), utc))
    }

    @Test fun localWallClockSurvivesDaylightSavingTransitions() {
        val zone = ZoneId.of("America/New_York")
        val hours = WorkingHours(true, 60, 120)
        // 2026-03-08 春季切换后的 01:30；2026-11-01 秋季回拨后的第一个 01:30。
        assertTrue(hours.allows(at("2026-03-08T06:30:00Z"), zone))
        assertTrue(hours.allows(at("2026-11-01T05:30:00Z"), zone))
        assertFalse(hours.allows(at("2026-11-01T07:30:00Z"), zone))
    }
}
