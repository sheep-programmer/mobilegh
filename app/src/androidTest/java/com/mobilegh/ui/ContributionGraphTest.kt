package com.mobilegh.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mobilegh.data.ContributionPoint
import com.mobilegh.ui.screens.WeeklyContributionGraph
import com.mobilegh.ui.theme.MobileGhTheme
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContributionGraphTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun emptyWeeksHaveAnEmptyState() {
        compose.setContent { MobileGhTheme { WeeklyContributionGraph(emptyList(), 42.dp) } }
        compose.onNodeWithText("周趋势暂无可用数据").assertIsDisplayed()
    }

    @Test fun singleWeekShowsItsExactValueWithoutAnUnscaledBar() {
        compose.setContent {
            MobileGhTheme { WeeklyContributionGraph(listOf(point(1, 1234)), 42.dp) }
        }
        compose.onNodeWithText("1,234 次提交", substring = true).assertIsDisplayed()
    }

    @Test fun narrowLargeFontGraphKeepsExactZeroMissingAndLargeValuesReadable() {
        compose.setContent {
            MobileGhTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    Column(Modifier.width(180.dp).verticalScroll(rememberScrollState())) {
                        WeeklyContributionGraph(listOf(point(1, Long.MAX_VALUE), point(8, 0), point(15, null)), 42.dp)
                    }
                }
            }
        }
        compose.onNodeWithText("查看每周数据").performClick()
        compose.onNodeWithText("9,223,372,036,854,775,807 次提交", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("0 次提交", substring = true).assertExists()
        compose.onNodeWithText("数据缺失", substring = true).assertExists()
    }

    private fun point(day: Int, commits: Long?) = ContributionPoint(
        start = LocalDate.of(2026, 1, day), commits = commits,
    )
}
