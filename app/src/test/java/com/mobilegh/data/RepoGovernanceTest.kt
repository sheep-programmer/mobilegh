package com.mobilegh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepoGovernanceTest {
    @Test fun severityNamesFromDifferentApisMapToOneLevel() {
        assertEquals(AlertLevel.Critical, alertLevel("critical"))
        assertEquals(AlertLevel.Medium, alertLevel("moderate"))
        assertEquals(AlertLevel.High, alertLevel(" HIGH "))
        assertEquals(AlertLevel.Unknown, alertLevel(null))
        assertEquals(AlertLevel.Unknown, alertLevel("something-new"))
    }

    @Test fun dependabotPrefersTheVulnerabilitySeverity() {
        val alert = Api.json.decodeFromString<DependabotAlert>(
            """{"number":3,"state":"open","security_advisory":{"severity":"low"},"security_vulnerability":{"severity":"critical"}}""",
        )
        assertEquals(AlertLevel.Critical, dependabotLevel(alert))
    }

    @Test fun codeScanningUsesSecuritySeverityLevelFirst() {
        val alert = Api.json.decodeFromString<CodeScanningAlert>(
            """{"number":1,"rule":{"severity":"warning","security_severity_level":"high","description":"SQL injection"},"tool":{"name":"CodeQL"}}""",
        )
        assertEquals(AlertLevel.High, codeScanLevel(alert))
        assertEquals("SQL injection", alert.rule?.description)
    }

    @Test fun rulesetEnforcementMapsKnownValues() {
        assertEquals(RulesetEnforcement.Active, rulesetEnforcement("active"))
        assertEquals(RulesetEnforcement.Evaluate, rulesetEnforcement("evaluate"))
        assertEquals(RulesetEnforcement.Disabled, rulesetEnforcement("DISABLED"))
        assertEquals(RulesetEnforcement.Unknown, rulesetEnforcement(""))
    }

    @Test fun branchProtectionDecodesTheNestedRules() {
        val p = Api.json.decodeFromString<BranchProtection>(
            """
            {
              "required_status_checks": {"strict": true, "contexts": ["ci/build"]},
              "required_pull_request_reviews": {"required_approving_review_count": 2, "dismiss_stale_reviews": true},
              "enforce_admins": {"enabled": false},
              "allow_force_pushes": {"enabled": false},
              "allow_deletions": {"enabled": false}
            }
            """.trimIndent(),
        )
        assertEquals(listOf("ci/build"), p.requiredStatusChecks?.contexts)
        assertTrue(p.requiredStatusChecks?.strict == true)
        assertEquals(2, p.requiredPullRequestReviews?.requiredApprovingReviewCount)
        assertTrue(p.requiredPullRequestReviews?.dismissStaleReviews == true)
        assertFalse(p.enforceAdmins?.enabled == true)
        assertNull(p.requiredLinearHistory)
    }

    @Test fun pullRequestCarriesAutoMergeMethodWhenEnabled() {
        val on = Api.json.decodeFromString<Pull>("""{"number":7,"node_id":"PR_1","auto_merge":{"merge_method":"SQUASH"}}""")
        assertEquals("SQUASH", on.autoMerge?.mergeMethod)
        val off = Api.json.decodeFromString<Pull>("""{"number":8,"node_id":"PR_2","auto_merge":null}""")
        assertNull(off.autoMerge)
    }

    @Test fun autoMergeMutationsUseTheRightOperation() {
        assertTrue(ENABLE_AUTO_MERGE.contains("enablePullRequestAutoMerge"))
        assertTrue(ENABLE_AUTO_MERGE.contains("PullRequestMergeMethod!"))
        assertTrue(DISABLE_AUTO_MERGE.contains("disablePullRequestAutoMerge"))
        assertEquals(listOf("MERGE", "SQUASH", "REBASE"), AUTO_MERGE_METHODS)
    }

    @Test fun assetUploadUrlGoesToUploadsHostAndEncodesTheName() {
        assertEquals(
            "https://uploads.github.com/repos/octo/app/releases/42/assets?name=a+b%2Bc.zip",
            releaseAssetUploadUrl("octo", "app", 42, "a b+c.zip"),
        )
    }
}
