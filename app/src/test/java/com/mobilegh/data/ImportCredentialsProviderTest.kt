package com.mobilegh.data

import org.eclipse.jgit.transport.CredentialItem
import org.eclipse.jgit.transport.URIish
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportCredentialsProviderTest {
    @Test
    fun clearingCredentialItemsDoesNotConsumeTheProviderPassword() {
        val provider = ImportCredentialsProvider("source-user", "source-password")

        repeat(2) {
            val username = CredentialItem.Username()
            val password = CredentialItem.Password()
            assertTrue(provider.supports(username, password))
            assertTrue(
                provider.get(
                    URIish("https://example.com/repository.git"),
                    username,
                    password,
                ),
            )
            assertEquals("source-user", username.value)
            assertEquals("source-password", String(password.value))
            password.clear()
        }
    }
}
