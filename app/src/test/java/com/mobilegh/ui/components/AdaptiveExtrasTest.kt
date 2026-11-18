package com.mobilegh.ui.components

import androidx.compose.ui.graphics.luminance
import androidx.lifecycle.SavedStateHandle
import com.mobilegh.MainDispatcherRule
import com.mobilegh.NavVm
import com.mobilegh.data.AccentStyle
import com.mobilegh.nav.NavRestore
import com.mobilegh.nav.Screen
import com.mobilegh.nav.Tab
import com.mobilegh.ui.theme.DarkGh
import com.mobilegh.ui.theme.LightGh
import com.mobilegh.ui.theme.withAccent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AdaptiveExtrasTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun connectionRecoveryIsShownOnlyAfterAnOutage() {
        assertEquals(ConnectivityNotice.Hidden, connectivityNotice(ConnectivityNotice.Hidden, online = true))
        assertEquals(ConnectivityNotice.Offline, connectivityNotice(ConnectivityNotice.Hidden, online = false))
        assertEquals(ConnectivityNotice.Recovered, connectivityNotice(ConnectivityNotice.Offline, online = true))
    }

    @Test fun accentsKeepBackgroundAndStatusColorsAndReadableContrast() {
        for (base in listOf(LightGh, DarkGh)) for (style in AccentStyle.entries) {
            val palette = withAccent(base, style)
            assertEquals(base.canvas, palette.canvas)
            assertEquals(base.success, palette.success)
            assertEquals(base.danger, palette.danger)
            val luminance = listOf(palette.accent.luminance(), palette.canvas.luminance()).sorted()
            assertTrue((luminance[1] + 0.05f) / (luminance[0] + 0.05f) >= 4.5f)
        }
    }

    @Test fun restoringANavigationStackTakesPriorityOverTheStartPage() {
        val target = Screen.Repo("octocat", "hello")
        val state = SavedStateHandle(mapOf(NavRestore.KEY to NavRestore.encode(Tab.Repos, listOf(target), null)))
        val nav = NavVm(state).navFor(1)
        assertEquals(Tab.Repos, nav.tab)
        assertEquals(target, nav.top.screen)
    }
}
