package com.mobilegh.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganizationCreatorTest {
    @Test fun validatesGitHubOrganizationHandleRules() {
        assertTrue(OrganizationCreator.isValidOrgLogin("my-team-1"))
        assertTrue(OrganizationCreator.isValidOrgLogin("A"))
        assertFalse(OrganizationCreator.isValidOrgLogin(""))
        assertFalse(OrganizationCreator.isValidOrgLogin("-leading"))
        assertFalse(OrganizationCreator.isValidOrgLogin("trailing-"))
        assertFalse(OrganizationCreator.isValidOrgLogin("double--dash"))
        assertFalse(OrganizationCreator.isValidOrgLogin("bad name"))
    }

    @Test fun orgFieldsPatchEmitsOnlyChangedFieldsAndNormalizesBlogAndTwitter() {
        val original = OrgFields(
            name = "CurSimple",
            description = "Old bio",
            location = "Beijing",
        )
        val updated = original.copy(
            description = "New org description",
            website = "cursimple.github.io",
            twitter = "@cursimple",
            billingEmail = "team@example.com",
        )
        val patch = updated.patch(original)
        assertTrue("name" !in patch)
        assertTrue("location" !in patch)
        org.junit.Assert.assertEquals("New org description", patch["description"]?.toString()?.trim('"'))
        org.junit.Assert.assertEquals("https://cursimple.github.io", patch["blog"]?.toString()?.trim('"'))
        org.junit.Assert.assertEquals("cursimple", patch["twitter_username"]?.toString()?.trim('"'))
        org.junit.Assert.assertEquals("team@example.com", patch["billing_email"]?.toString()?.trim('"'))
    }
}
