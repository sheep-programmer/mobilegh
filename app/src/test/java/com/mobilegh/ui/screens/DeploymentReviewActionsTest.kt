package com.mobilegh.ui.screens

import com.mobilegh.data.DeploymentReview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeploymentReviewActionsTest {
    private val allowed = DeploymentReview("E_prod", "production", canApprove = true, reviewers = listOf("mona"))
    private val denied = DeploymentReview("E_dev", "development", canApprove = false, reviewers = emptyList())

    @Test fun stalePendingReviewsForANonWaitingRunAreHiddenAndNotSubmittable() {
        assertEquals(
            DeploymentReviewDisplay.Hidden,
            deploymentReviewDisplay("completed", "WR_node", listOf(allowed), error = null),
        )
        assertNull(deploymentReviewInput("completed", "WR_node", listOf(allowed), "ready"))
    }

    @Test fun missingWorkflowRunNodeIdDisablesReviewSubmission() {
        assertNull(deploymentReviewInput("waiting", null, listOf(allowed), "ready"))
        assertNull(deploymentReviewInput("waiting", "", listOf(allowed), "ready"))
    }

    @Test fun environmentsWithoutApprovalPermissionAreNotSubmittable() {
        assertNull(deploymentReviewInput("waiting", "WR_node", listOf(denied), "ready"))
        val input = deploymentReviewInput("waiting", "WR_node", listOf(denied, allowed), "ready")
        assertEquals(listOf("E_prod"), input?.environmentIds)
        assertEquals("WR_node", input?.workflowRunId)
    }

    @Test fun multipleApprovableEnvironmentsShareOneReview() {
        val staging = allowed.copy(environmentId = "E_staging", environmentName = "staging")
        val input = deploymentReviewInput("waiting", "WR_node", listOf(allowed, staging), "ready")
        assertEquals(listOf("E_prod", "E_staging"), input?.environmentIds)
        assertEquals("ready", input?.comment)
    }

    @Test fun failedInitialRequestDoesNotDisplayAnEmptyReviewCard() {
        assertEquals(
            DeploymentReviewDisplay.Error,
            deploymentReviewDisplay("waiting", "WR_node", pending = null, error = "offline"),
        )
    }

    @Test fun waitingRunWithoutNodeIdShowsTheExistingExplanation() {
        assertEquals(
            DeploymentReviewDisplay.Content,
            deploymentReviewDisplay("waiting", null, pending = null, error = null),
        )
    }

    @Test fun successfulEmptyWaitingRunShowsTheEmptyReviewCard() {
        assertEquals(
            DeploymentReviewDisplay.Content,
            deploymentReviewDisplay("waiting", "WR_node", pending = emptyList(), error = null),
        )
    }
}
