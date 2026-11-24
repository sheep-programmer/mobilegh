package com.mobilegh.data

import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseAssetManagementTest {
    @Test fun assetUpdateUsesReleaseAssetEndpointAndNullableLabel() {
        assertEquals(
            "/repos/octo/app/releases/assets/42",
            releaseAssetPath("octo", "app", 42),
        )
        val body = releaseAssetUpdateBody("mobile.zip", null)
        assertEquals("mobile.zip", body["name"]?.toString()?.trim('"'))
        assertTrue(body["label"] === JsonNull)
    }

    @Test fun duplicateNamesAreRejectedWithoutTreatingTheCurrentAssetAsDuplicate() {
        val assets = listOf(
            Asset(id = 7, name = "mobile.zip"),
            Asset(id = 8, name = "checksums.txt"),
        )

        assertEquals(
            listOf("mobile.zip", "checksums.txt"),
            releaseAssetNameConflicts(assets, listOf("mobile.zip", "checksums.txt")),
        )
        assertTrue(releaseAssetNameConflicts(assets, listOf("mobile.zip"), replacingAssetId = 7).isEmpty())
        assertFalse(releaseAssetNameConflicts(assets, listOf("new.zip")).isNotEmpty())
    }
}
