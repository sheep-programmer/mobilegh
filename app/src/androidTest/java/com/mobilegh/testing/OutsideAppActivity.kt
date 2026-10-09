package com.mobilegh.testing

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** Separate test APK Activity: proves MobileGH receives requests with its own UI stopped. */
class OutsideAppActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Background verification test"; textSize = 24f; setPadding(24, 48, 24, 24) })
    }
}
