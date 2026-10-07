package com.mobilegh.nav

import android.content.Intent

object IncomingLinks {
    fun fromIntent(intent: Intent?): Links.InputLink? = runCatching {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString?.let(Links::inputLink)
            Intent.ACTION_SEND -> if (intent.type == "text/plain") intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let(Links::sharedLink) else null
            else -> null
        }
    }.getOrNull()
}
