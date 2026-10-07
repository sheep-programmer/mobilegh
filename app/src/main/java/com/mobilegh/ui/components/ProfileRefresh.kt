package com.mobilegh.ui.components

import androidx.compose.runtime.*
import com.mobilegh.data.Session
import com.mobilegh.data.User
import com.mobilegh.nav.Loader
import com.mobilegh.nav.retain

@Composable
fun RefreshProfileOnChange(loader: Loader<User>) {
    val observed = retain("observed-profile-revision") { mutableIntStateOf(Session.profileRevision) }
    LaunchedEffect(Session.profileRevision) {
        if (observed.intValue != Session.profileRevision) {
            observed.intValue = Session.profileRevision
            loader.refresh()
        }
    }
}
