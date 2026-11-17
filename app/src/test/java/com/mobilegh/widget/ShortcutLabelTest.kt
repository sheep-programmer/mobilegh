package com.mobilegh.widget

import com.mobilegh.data.Api
import com.mobilegh.data.ShortcutWidgetItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShortcutLabelTest {
    @Test fun storedBuiltInLabelIsResolvedAgainWhenLanguageChanges() {
        val item = ShortcutWidgetItem("activity", "", "mobilegh://shortcut/activity", labelKey = "动态")
        assertEquals("动态", shortcutLabel(item) { it })
        assertEquals("Activity", shortcutLabel(item) { key -> mapOf("动态" to "Activity").getValue(key) })
        assertEquals("动态", item.labelKey)
    }

    @Test fun customAndLegacyLabelsRemainVerbatim() {
        val custom = ShortcutWidgetItem("search:abc", "评审队列", "mobilegh://shortcut/search/abc")
        assertNull(custom.labelKey)
        assertEquals("评审队列", shortcutLabel(custom) { "translated" })

        val legacy = Api.plain.decodeFromString(
            ShortcutWidgetItem.serializer(),
            """{"id":"issues","label":"Legacy Issues","url":"mobilegh://shortcut/issues"}"""
        )
        assertNull(legacy.labelKey)
        assertEquals("Legacy Issues", shortcutLabel(legacy) { "translated" })
    }
}
