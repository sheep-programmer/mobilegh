package com.mobilegh.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstOpenGlowTest {
    @Test fun eachBadgeGlowsOnlyOnItsFirstOpen() {
        assertTrue(FirstOpenGlow.claim("test-user/pull-shark"))
        assertFalse(FirstOpenGlow.claim("test-user/pull-shark"))
        assertTrue(FirstOpenGlow.claim("test-user/quickdraw"))
    }
}
