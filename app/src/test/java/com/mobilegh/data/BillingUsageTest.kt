package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BillingUsageTest {
    @Test fun decodesActionsStorageAndPackagesBilling() {
        val actionsJson = """
            {"total_minutes_used":305,"total_paid_minutes_used":0,"included_minutes":2000,"minutes_used_breakdown":{"UBUNTU":205,"MACOS":10,"WINDOWS":90}}
        """.trimIndent()
        val storageJson = """
            {"days_left_in_billing_cycle":12,"estimated_paid_storage_for_month":0.0,"estimated_storage_for_month":0.42}
        """.trimIndent()
        val packagesJson = """
            {"total_gigabytes_bandwidth_used":0.5,"total_paid_gigabytes_bandwidth_used":0.0,"included_gigabytes_bandwidth":1.0}
        """.trimIndent()

        val actions = Api.json.decodeFromString<ActionsBilling>(actionsJson)
        val storage = Api.json.decodeFromString<SharedStorageBilling>(storageJson)
        val packages = Api.json.decodeFromString<PackagesBilling>(packagesJson)

        assertEquals(305, actions.totalMinutesUsed)
        assertEquals(2000, actions.includedMinutes)
        assertEquals(205, actions.minutesUsedBreakdown["UBUNTU"])
        assertEquals(12, storage.daysLeftInBillingCycle)
        assertEquals(0.42, storage.estimatedStorageForMonth, 0.001)
        assertEquals(1.0, packages.includedGigabytesBandwidth, 0.001)
    }

    @Test fun mapsEnhancedBillingUsageSummaryIntoQuotas() {
        val summaryJson = """
            {
              "usageItems": [
                {"product":"Actions","sku":"Actions Linux","unitType":"minutes","quantity":150.0,"pricePerUnit":0.008,"grossAmount":1.2,"discountAmount":1.2,"netAmount":0.0},
                {"product":"Actions","sku":"Actions macOS","unitType":"minutes","quantity":10.0,"pricePerUnit":0.08,"grossAmount":0.8,"discountAmount":0.0,"netAmount":0.8},
                {"product":"Shared Storage","sku":"Shared Storage","unitType":"gigabyte-hours","quantity":360.0,"pricePerUnit":0.0,"grossAmount":0.0,"discountAmount":0.0,"netAmount":0.0},
                {"product":"Packages","sku":"Packages data transfer","unitType":"gigabytes","quantity":0.25,"pricePerUnit":0.0,"grossAmount":0.0,"discountAmount":0.0,"netAmount":0.0}
              ]
            }
        """.trimIndent()
        val report = Api.plain.decodeFromString<BillingUsageReport>(summaryJson)
        val usage = BillingUsage.fromEnhancedReport(report, "free")

        assertNotNull(usage.actions)
        assertEquals(160, usage.actions?.totalMinutesUsed)
        assertEquals(10, usage.actions?.totalPaidMinutesUsed)
        assertEquals(2000, usage.actions?.includedMinutes)
        assertEquals(150, usage.actions?.minutesUsedBreakdown?.get("LINUX"))
        assertEquals(10, usage.actions?.minutesUsedBreakdown?.get("MACOS"))
        assertNotNull(usage.storage)
        assertEquals(0.5, usage.storage!!.estimatedStorageForMonth, 0.01)
        assertNotNull(usage.packages)
        assertEquals(0.25, usage.packages!!.totalGigabytesBandwidthUsed, 0.01)
    }

    @Test fun parsesWebBillingRenderedTextAndEmbeddedJson() {
        val domText = """
            Metered usage
            GitHub Actions
            320 / 2,000 included minutes
            Shared Storage
            256 MB of 500 MB
            Packages
            0.3 / 1.0 GB
        """.trimIndent()
        val parsed = BillingWebReader.parseBillingText(domText, "free")
        assertNotNull(parsed)
        assertEquals(320, parsed?.actions?.totalMinutesUsed)
        assertEquals(2000, parsed?.actions?.includedMinutes)
        assertEquals(0.25, parsed?.storage?.estimatedStorageForMonth ?: 0.0, 0.01)
        assertEquals(0.3, parsed?.packages?.totalGigabytesBandwidthUsed ?: 0.0, 0.01)

        val html = """
            <html><body>
            <script type="application/json" data-target="react-app.embeddedData">
            {"payload":{"usageSummary":{"includedMinutes":3000,"totalMinutesUsed":45,"totalPaidMinutesUsed":0}}}
            </script>
            </body></html>
        """.trimIndent()
        val fromHtml = BillingWebReader.parseBillingHtml(html, "pro")
        assertEquals(45, fromHtml?.actions?.totalMinutesUsed)
        assertEquals(3000, fromHtml?.actions?.includedMinutes)
    }
}
