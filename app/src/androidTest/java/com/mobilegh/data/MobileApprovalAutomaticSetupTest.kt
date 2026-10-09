package com.mobilegh.data

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in handoff to a real browser; no token or device bundle is imported. */
class MobileApprovalAutomaticSetupTest {
    @Test fun startsNativePkceAuthorizationForAutomaticDeviceRegistration() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("automatic2faSetup") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        var url = ""
        instrumentation.runOnMainSync { url = MobileApprovalOAuth.begin(context) }
        assertTrue(url.startsWith("https://github.com/login/oauth/authorize?"))
        assertTrue(url.contains("code_challenge_method=S256"))
        File(context.filesDir, "2fa-test-authorization-url").writeText(url)
    }
}
