package com.mobilegh.data

import android.webkit.CookieManager
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.io.IOException

/**
 * 通过 GitHub 网页端组织创建端点创建免费组织。
 * GitHub 公开 REST/GraphQL API 未开放新建组织接口；若账户触发人机验证（Octocaptcha），
 * 本类不会尝试绕过，而是返回 [CreateOrgResult.CaptchaRequired] 交由界面在应用内由用户本人完成。
 */
object OrganizationCreator {
    const val NEW_FREE_ORG_URL = "https://github.com/organizations/new?plan=free"

    sealed interface CreateOrgResult {
        data class Created(val login: String) : CreateOrgResult
        data object CaptchaRequired : CreateOrgResult
        data class Failed(val message: String) : CreateOrgResult
    }

    fun isValidOrgLogin(login: String): Boolean =
        login.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}"))

    suspend fun createFreeOrganization(
        login: String,
        billingEmail: String,
        corporate: Boolean,
        companyName: String = "",
        cookieHeader: () -> String? = { runCatching { CookieManager.getInstance().getCookie("https://github.com") }.getOrNull() },
        client: OkHttpClient? = null,
    ): CreateOrgResult = withContext(Dispatchers.IO) {
        val trimmedLogin = login.trim()
        val trimmedEmail = billingEmail.trim()
        require(isValidOrgLogin(trimmedLogin)) { tr("组织名称只能包含字母、数字和单个连字符，不能以连字符开头或结尾") }
        require(trimmedEmail.contains('@') && trimmedEmail.contains('.')) { tr("请输入有效的联系邮箱") }

        val cookie = cookieHeader()
        if (!WebAttachmentUploader.hasSession(cookie)) {
            throw WebSessionMissing()
        }
        val http = requireNotNull(client ?: Api.http).routed()

        val getReq = Request.Builder()
            .url(NEW_FREE_ORG_URL)
            .header("Cookie", cookie.orEmpty())
            .header("Accept", "text/html,application/xhtml+xml")
            .build()
        val (getUrl, getCode, html) = http.newCall(getReq).await().use { resp ->
            Triple(resp.request.url.toString(), resp.code, resp.body.string())
        }
        if (getCode == 401 || getCode == 403 || getUrl.contains("/login")) {
            throw WebSessionMissing()
        }
        if (getCode !in 200..299) {
            throw IOException(trf("读取创建组织表单失败（HTTP {0}）", getCode))
        }

        val doc = Jsoup.parse(html, "https://github.com")
        val form = doc.select("form[action*=/organizations]").firstOrNull()
        val token = form?.selectFirst("input[name=authenticity_token]")?.attr("value").orEmpty()
        if (token.isBlank()) {
            return@withContext if (containsCaptchaGate(html, doc)) {
                CreateOrgResult.CaptchaRequired
            } else {
                CreateOrgResult.Failed(tr("未能获取 GitHub 创建组织表单令牌"))
            }
        }
        if (containsCaptchaGate(html, doc)) {
            return@withContext CreateOrgResult.CaptchaRequired
        }

        val actionUrl = form?.absUrl("action")?.takeIf { it.isNotBlank() } ?: "https://github.com/organizations"
        val body = FormBody.Builder()
            .add("authenticity_token", token)
            .add("organization[profile_name]", trimmedLogin)
            .add("organization[login]", trimmedLogin)
            .add("organization[billing_email]", trimmedEmail)
            .add("terms_of_service_type", if (corporate) "corporate" else "standard")
            .add("agreed_to_terms", "yes")
            .apply {
                if (corporate && companyName.isNotBlank()) {
                    add("organization[company_name]", companyName.trim())
                }
            }
            .build()

        val postReq = Request.Builder()
            .url(actionUrl)
            .post(body)
            .header("Cookie", cookie.orEmpty())
            .header("Origin", "https://github.com")
            .header("Referer", NEW_FREE_ORG_URL)
            .header("Accept", "text/html,application/xhtml+xml")
            .build()

        val (postUrl, postCode, postHtml) = http.newCall(postReq).await().use { resp ->
            Triple(resp.request.url.toString(), resp.code, resp.body.string())
        }
        if (postCode == 401 || postCode == 403 || postUrl.contains("/login")) {
            throw WebSessionMissing()
        }
        if (postUrl.contains("/orgs/", ignoreCase = true) ||
            postUrl.trimEnd('/').endsWith("/$trimmedLogin", ignoreCase = true)
        ) {
            return@withContext CreateOrgResult.Created(trimmedLogin)
        }
        val postDoc = Jsoup.parse(postHtml, "https://github.com")
        if (containsCaptchaGate(postHtml, postDoc)) {
            return@withContext CreateOrgResult.CaptchaRequired
        }
        val err = postDoc.select(".flash-error, .js-flash-alert, dd.error, p.note.error")
            .map { it.text().trim() }
            .firstOrNull { it.isNotBlank() }
        CreateOrgResult.Failed(err ?: trf("创建组织失败（HTTP {0}）", postCode))
    }

    private fun containsCaptchaGate(html: String, doc: org.jsoup.nodes.Document): Boolean =
        doc.select("input[name=octocaptcha-token], .js-octocaptcha-frame, octocaptcha-frame, [data-captcha]").isNotEmpty() ||
            html.contains("octocaptcha", ignoreCase = true) ||
            html.contains("arkoselabs", ignoreCase = true)
}

/**
 * 组织头像上传：复用 GitHub 网页会话（CookieManager）向 `/upload/policies/avatars` 上传图片并保存为组织头像。
 */
object OrgAvatarUploader {
    fun settingsProfileUrl(orgLogin: String): String =
        "https://github.com/organizations/${Api.encodePath(orgLogin)}/settings/profile"

    suspend fun upload(
        orgId: Long,
        orgLogin: String,
        fileName: String,
        mime: String,
        bytes: ByteArray,
        widthPx: Int,
        heightPx: Int,
        cookieHeader: () -> String? = { runCatching { CookieManager.getInstance().getCookie("https://github.com") }.getOrNull() },
        client: OkHttpClient? = null,
    ) = withContext(Dispatchers.IO) {
        require(orgId > 0) { tr("无法确定组织编号，请刷新后重试") }
        require(bytes.isNotEmpty()) { tr("所选图片为空") }
        val cookie = cookieHeader()
        if (!WebAttachmentUploader.hasSession(cookie)) throw WebSessionMissing()
        val http = requireNotNull(client ?: Api.http).routed()
        val profileUrl = settingsProfileUrl(orgLogin)

        val pageReq = Request.Builder()
            .url(profileUrl)
            .header("Cookie", cookie.orEmpty())
            .header("Accept", "text/html,application/xhtml+xml")
            .build()
        val (pageUrl, pageCode, pageHtml) = http.newCall(pageReq).await().use { resp ->
            Triple(resp.request.url.toString(), resp.code, resp.body.string())
        }
        if (pageCode == 401 || pageCode == 403 || pageUrl.contains("/login")) throw WebSessionMissing()
        if (pageCode !in 200..299) throw IOException(trf("读取组织设置页失败（HTTP {0}）", pageCode))

        val doc = Jsoup.parse(pageHtml, "https://github.com")
        val nonce = doc.selectFirst("meta[name=fetch-nonce]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: Regex("""name=["']fetch-nonce["'][^>]*content=["']([^"']+)["']""").find(pageHtml)?.groupValues?.getOrNull(1)
            ?: throw WebSessionMissing()

        val policyReq = Request.Builder()
            .url("https://github.com/upload/policies/avatars")
            .post(
                MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("name", fileName)
                    .addFormDataPart("size", bytes.size.toString())
                    .addFormDataPart("content_type", mime)
                    .addFormDataPart("owner_type", "Organization")
                    .addFormDataPart("owner_id", orgId.toString())
                    .build(),
            )
            .header("Cookie", cookie.orEmpty())
            .header("Accept", "application/json")
            .header("GitHub-Verified-Fetch", "true")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("X-Fetch-Nonce", nonce)
            .header("Origin", "https://github.com")
            .header("Referer", profileUrl)
            .build()
        val (policyCode, policyBody) = http.newCall(policyReq).await().use { resp -> resp.code to resp.body.string() }
        if (policyCode == 401 || policyCode == 403) throw WebSessionMissing()
        if (policyCode !in 200..299) throw IOException(trf("申请头像上传失败（HTTP {0}）", policyCode))

        val policyJson = Api.plain.parseToJsonElement(policyBody).jsonObject
        val s3Url = (policyJson["upload_url"] as? JsonPrimitive)?.content ?: throw IllegalStateException(tr("上传策略缺少地址"))
        val form = (policyJson["form"] as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: "" }
            ?: throw IllegalStateException(tr("上传策略缺少表单字段"))
        val assetUrlRaw = (policyJson["asset_upload_url"] as? JsonPrimitive)?.content ?: throw IllegalStateException(tr("上传策略缺少确认地址"))
        val assetUrl = if (assetUrlRaw.startsWith("http")) assetUrlRaw else "https://github.com$assetUrlRaw"
        val assetToken = (policyJson["asset_upload_authenticity_token"] as? JsonPrimitive)?.content
            ?: throw IllegalStateException(tr("上传策略缺少确认令牌"))

        val s3Body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            form.forEach { (k, v) -> addFormDataPart(k, v) }
            addFormDataPart("file", fileName, bytes.toRequestBody(mime.toMediaTypeOrNull()))
        }.build()
        http.newCall(Request.Builder().url(s3Url).post(s3Body).build()).await().use { resp ->
            if (!resp.isSuccessful) throw IOException(trf("头像文件上传失败（HTTP {0}）", resp.code))
        }

        val confirmReq = Request.Builder()
            .url(assetUrl)
            .put(MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("authenticity_token", assetToken).build())
            .header("Cookie", cookie.orEmpty())
            .header("Accept", "application/json")
            .header("GitHub-Verified-Fetch", "true")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("X-Fetch-Nonce", nonce)
            .header("Origin", "https://github.com")
            .header("Referer", profileUrl)
            .build()
        val (confirmCode, confirmBody) = http.newCall(confirmReq).await().use { resp -> resp.code to resp.body.string() }
        if (confirmCode !in 200..299) throw IOException(trf("确认头像上传失败（HTTP {0}）", confirmCode))

        val confirmJson = Api.plain.parseToJsonElement(confirmBody).jsonObject
        val avatarId = (confirmJson["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException(tr("GitHub 未返回头像编号，请在应用内设置页完成裁剪"))

        val avatarForm = doc.select("form[action*=/settings/avatars]").firstOrNull()
        val saveToken = avatarForm?.selectFirst("input[name=authenticity_token]")?.attr("value")?.takeIf { it.isNotBlank() }
            ?: assetToken
        val side = minOf(widthPx, heightPx).coerceAtLeast(1)
        val x = ((widthPx - side) / 2).coerceAtLeast(0)
        val y = ((heightPx - side) / 2).coerceAtLeast(0)
        val saveReq = Request.Builder()
            .url("https://github.com/settings/avatars/$avatarId")
            .post(
                FormBody.Builder()
                    .add("authenticity_token", saveToken)
                    .add("op", "save")
                    .add("cropped_x", x.toString())
                    .add("cropped_y", y.toString())
                    .add("cropped_width", side.toString())
                    .add("cropped_height", side.toString())
                    .build(),
            )
            .header("Cookie", cookie.orEmpty())
            .header("Origin", "https://github.com")
            .header("Referer", profileUrl)
            .build()
        http.newCall(saveReq).await().use { resp ->
            if (resp.code == 401 || resp.code == 403) throw WebSessionMissing()
            if (resp.code !in 200..399) throw IOException(trf("保存组织头像失败（HTTP {0}）", resp.code))
        }
    }
}
