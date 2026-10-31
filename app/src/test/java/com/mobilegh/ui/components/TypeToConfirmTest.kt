package com.mobilegh.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 输入确认的判定：确认按钮只在输入与目标名称一致时亮起。 */
class TypeToConfirmTest {
    @Test fun exactFullNameEnablesConfirm() {
        assertTrue(typeToConfirmMatches("octo/repo", "octo/repo", ignoreCase = true))
    }

    @Test fun repoFullNameIgnoresCaseAndSurroundingSpaces() {
        // GitHub 仓库名大小写不敏感；键盘误带的首尾空格不应让按钮永远不亮
        assertTrue(typeToConfirmMatches("  Octo/REPO ", "octo/repo", ignoreCase = true))
    }

    @Test fun branchNameIsCaseSensitive() {
        assertFalse(typeToConfirmMatches("Main", "main", ignoreCase = false))
        assertTrue(typeToConfirmMatches("main", "main", ignoreCase = false))
    }

    @Test fun repoNameAloneIsNotEnoughForFullName() {
        assertFalse(typeToConfirmMatches("repo", "octo/repo", ignoreCase = true))
    }

    @Test fun partialInputDoesNotEnableConfirm() {
        assertFalse(typeToConfirmMatches("octo/rep", "octo/repo", ignoreCase = true))
        assertFalse(typeToConfirmMatches("octo/repo2", "octo/repo", ignoreCase = true))
    }

    @Test fun emptyInputOrEmptyTargetNeverMatches() {
        assertFalse(typeToConfirmMatches("", "octo/repo", ignoreCase = true))
        assertFalse(typeToConfirmMatches("", "", ignoreCase = true))
        assertFalse(typeToConfirmMatches("   ", "", ignoreCase = false))
    }
}
