package com.mobilegh.data

import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

/**
 * Keeps the provider reusable across JGit's multiple authenticated HTTP requests.
 * JGit clears Password items after Basic authorization, so each item gets a copy.
 */
internal class ImportCredentialsProvider(
    username: String,
    password: String,
) : UsernamePasswordCredentialsProvider(username, password) {
    override fun get(uri: URIish?, vararg items: CredentialItem?): Boolean {
        val result = super.get(uri, *items)
        items.filterIsInstance<CredentialItem.Password>().forEach { item ->
            item.value = item.value?.copyOf()
        }
        return result
    }
}
