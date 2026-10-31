package com.mobilegh.ui.components

import com.mobilegh.data.ContribCalendar
import com.mobilegh.data.ContribDay
import com.mobilegh.data.ContribWeek
import org.junit.Assert.assertEquals
import org.junit.Test

class ChartSummaryTest {

    @Test
    fun barSummaryWithoutLabelsReportsTotalAndPeakWithUnit() {
        assertEquals("柱状图，合计 6 次，最高 3 次", barChartSummary(listOf(1, 3, 2), emptyList(), " 次"))
    }

    @Test
    fun barSummaryNamesPeakAndRangeWhenLabelsGiven() {
        val labels = listOf("周一", "周二", "周三")
        assertEquals(
            "柱状图，合计 6，最高 3（周二），范围 周一 至 周三",
            barChartSummary(listOf(1, 3, 2), labels, ""),
        )
    }

    @Test
    fun barSummaryForEmptyDataSaysNoData() {
        assertEquals("暂无数据", barChartSummary(emptyList(), emptyList()))
    }

    @Test
    fun lineSummaryReportsTotalOfFirstSeriesAndPeaksOfBoth() {
        val labels = listOf("d1", "d2", "d3")
        assertEquals(
            "折线图，总数合计 8，总数峰值 5，独立峰值 4，范围 d1 至 d3",
            lineChartSummary(labels, listOf(2, 5, 1), listOf(1, 1, 4), "总数", "独立"),
        )
    }

    @Test
    fun lineSummaryWithoutLabelsOmitsRange() {
        assertEquals(
            "折线图，A合计 3，A峰值 2，B峰值 1",
            lineChartSummary(emptyList(), listOf(1, 2), listOf(1, 0), "A", "B"),
        )
    }

    @Test
    fun lineSummaryForEmptyDataSaysNoData() {
        assertEquals("暂无数据", lineChartSummary(emptyList(), emptyList(), emptyList(), "A", "B"))
    }

    @Test
    fun heatmapSummaryReportsRangeTotalActiveDaysAndBestDay() {
        val cal = ContribCalendar(
            totalContributions = 7,
            weeks = listOf(
                ContribWeek(
                    listOf(
                        ContribDay("2026-01-01", 0, "NONE", 4),
                        ContribDay("2026-01-02", 5, "FOURTH_QUARTILE", 5),
                    ),
                ),
                ContribWeek(listOf(ContribDay("2026-01-08", 2, "SECOND_QUARTILE", 4))),
            ),
        )
        assertEquals(
            "贡献热力图，2026-01-01 至 2026-01-08，共 7 次贡献，2 天有贡献，最多 5 次（2026-01-02）",
            heatmapSummary(cal),
        )
    }

    @Test
    fun heatmapSummaryWithoutAnyContributionOmitsBestDay() {
        val cal = ContribCalendar(
            totalContributions = 0,
            weeks = listOf(ContribWeek(listOf(ContribDay("2026-03-01", 0, "NONE", 0)))),
        )
        assertEquals("贡献热力图，2026-03-01 至 2026-03-01，共 0 次贡献，0 天有贡献", heatmapSummary(cal))
    }

    @Test
    fun heatmapSummaryForEmptyCalendarSaysNoData() {
        assertEquals("暂无贡献数据", heatmapSummary(ContribCalendar()))
    }
}
