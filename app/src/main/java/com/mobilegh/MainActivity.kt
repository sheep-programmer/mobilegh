package com.mobilegh

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import com.mobilegh.data.Session
import com.mobilegh.nav.Links
import com.mobilegh.nav.Navigator
import com.mobilegh.ui.AppRoot
import com.mobilegh.ui.theme.MobileGhTheme

class NavVm : ViewModel() {
    private var gen = -1
    private var current = Navigator()

    fun navFor(generation: Int): Navigator {
        if (generation != gen) {
            if (gen != -1) current.popToRoot()
            current = Navigator()
            gen = generation
        }
        return current
    }
}

class MainActivity : ComponentActivity() {
    private val vm: NavVm by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            MobileGhTheme {
                AppRoot(vm.navFor(Session.generation))
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (Session.token == null) return
        val url = intent?.data?.toString() ?: return
        Links.route(url)?.let { vm.navFor(Session.generation).push(it) }
    }
}
