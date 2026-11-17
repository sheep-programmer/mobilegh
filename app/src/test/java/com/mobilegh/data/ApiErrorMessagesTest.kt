package com.mobilegh.data

import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** 用户最终看到的错误提示：常见状态码要有可操作的中文原因，而不是直接甩英文原文。 */
class ApiErrorMessagesTest {
    private fun failure(code: Int, body: String): ApiException =
        assertThrows(ApiException::class.java) {
            Api.ensureOk(Resp(code, body, Headers.headersOf()))
        }

    @Test fun rateLimitedForbiddenExplainsTheLimitInsteadOfRawMessage() {
        val error = failure(403, """{"message":"API rate limit exceeded for 1.2.3.4."}""")

        assertEquals(403, error.code)
        assertEquals(tr("API 调用次数已达上限，请稍后再试"), error.message)
    }

    @Test fun samlForbiddenPointsAtTheSsoAuthorizationPage() {
        val error = failure(403, """{"message":"Resource protected by organization SAML enforcement"}""")

        assertEquals(tr("该组织启用了 SAML SSO，请在 GitHub 网页上为 Token 授权 SSO"), error.message)
    }

    @Test fun missingCodeScanningAnalysisIsNotReportedAsAMissingRepository() {
        val analysis = failure(404, """{"message":"no analysis found for ref refs/heads/main"}""")
        val missing = failure(404, """{"message":"Not Found"}""")

        assertEquals(tr("该仓库还没有代码扫描分析，需要先在仓库里配置 CodeQL 工作流"), analysis.message)
        assertEquals(tr("未找到内容（可能不存在或 Token 没有权限）"), missing.message)
    }

    @Test fun invalidTokenIsReportedOnceWithoutTheEnglishBody() {
        val error = failure(401, """{"message":"Bad credentials"}""")

        assertEquals(tr("认证失败：Token 无效或已过期"), error.message)
    }

    @Test fun validationFailureKeepsTheSpecificFieldReason() {
        val error = failure(
            422,
            """{"message":"Validation Failed","errors":[{"message":"name already exists on this account"}]}""",
        )

        assertEquals(trf("请求无效：{0}", "Validation Failed：name already exists on this account"), error.message)
    }
}
