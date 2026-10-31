package com.mobilegh.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 添加验证器时保存按钮不可用的原因 */
class AuthenticatorDraftTest {
    private val secret = "JBSWY3DPEHPK3PXP"

    @Test fun validAccountAndSecretHaveNoProblem() {
        assertNull(authenticatorDraftProblem("alice", secret))
        assertNull(authenticatorDraftProblem("alice-2", "otpauth://totp/GitHub:alice?secret=$secret&issuer=GitHub"))
        assertNull(authenticatorDraftProblem("a".repeat(39), secret))
    }

    @Test fun blankOrBadAccountIsExplained() {
        assertNotNull(authenticatorDraftProblem("", secret))
        assertNotNull(authenticatorDraftProblem("bad_name", secret))
        assertNotNull(authenticatorDraftProblem("-leading", secret))
        assertNotNull(authenticatorDraftProblem("a".repeat(40), secret))
    }

    @Test fun missingOrBrokenSecretIsExplained() {
        assertNotNull(authenticatorDraftProblem("alice", ""))
        assertNotNull(authenticatorDraftProblem("alice", "not a secret!"))
        assertNotNull(authenticatorDraftProblem("alice", "otpauth://totp/GitHub:alice?secret=$secret&digits=8"))
    }

    @Test fun accountProblemIsReportedFirst() {
        assertEquals("用户名只能包含字母、数字和连字符，最多 39 个字符", authenticatorDraftProblem("bad_name", ""))
    }
}
