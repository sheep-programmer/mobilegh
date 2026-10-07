package com.mobilegh.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.URI

enum class SubscriptionMode(val title: String, val description: String) {
    Participating("参与及 @我", "接收参与的讨论和提及你的通知"),
    Watching("关注全部", "接收这个仓库的所有活动通知"),
    Ignored("忽略", "屏蔽这个仓库的通知"),
}
@Serializable
data class RepositorySubscription(val subscribed: Boolean = false, val ignored: Boolean = false) {
    val mode: SubscriptionMode get() = when { ignored -> SubscriptionMode.Ignored; subscribed -> SubscriptionMode.Watching; else -> SubscriptionMode.Participating }
}
object SubscriptionSettings {
    fun body(mode: SubscriptionMode): JsonObject? = when (mode) {
        SubscriptionMode.Participating -> null
        else -> buildJsonObject { put("subscribed", mode == SubscriptionMode.Watching); put("ignored", mode == SubscriptionMode.Ignored) }
    }
    suspend fun current(owner: String, name: String): SubscriptionMode {
        return try { Api.get<RepositorySubscription>("${CodeReading.repoPath(owner, name)}/subscription", true).mode }
        catch (e: ApiException) { if (e.code == 404) SubscriptionMode.Participating else throw e }
    }
    suspend fun set(owner: String, name: String, mode: SubscriptionMode) {
        val path = "${CodeReading.repoPath(owner, name)}/subscription"
        if (mode == SubscriptionMode.Participating) {
            val result = Api.call("DELETE", path)
            if (result.code != 404) Api.ensureOk(result)
        } else Api.exec("PUT", path, body(mode))
    }
}

data class ProfileFields(
    val name: String = "", val bio: String = "", val company: String = "", val location: String = "",
    val website: String = "", val twitter: String = "", val email: String = "", val hireable: Boolean = false,
) {
    companion object {
        fun from(user: User) = ProfileFields(user.name.orEmpty(), user.bio.orEmpty(), user.company.orEmpty(), user.location.orEmpty(),
            user.blog.orEmpty(), user.twitterUsername.orEmpty(), user.email.orEmpty(), user.hireable == true)
    }
    fun patch(original: ProfileFields): JsonObject = buildJsonObject {
        fun changed(key: String, value: String, old: String, max: Int) {
            if (value != old) {
                require(value.length <= max && (key == "bio" || value.none(Char::isISOControl))) { "$key 的内容过长或格式无效" }
                put(key, value.trim())
            }
        }
        changed("name", name, original.name, 255)
        changed("bio", bio, original.bio, 160)
        changed("company", company, original.company, 255)
        changed("location", location, original.location, 255)
        changed("email", email, original.email, 254)
        if (website != original.website) {
            var value = website.trim()
            require(value.length <= 2048) { "网站地址过长" }
            if (value.isNotBlank()) {
                if (!value.contains("://")) value = "https://$value"
                val uri = runCatching { URI(value) }.getOrNull()
                require(uri?.scheme in setOf("http", "https") && !uri?.host.isNullOrBlank() && uri?.userInfo == null) { "请输入有效的 http 或 https 网站地址" }
            }
            put("blog", value)
        }
        if (twitter != original.twitter) {
            val clean = twitter.trim().removePrefix("@")
            require(clean.isEmpty() || clean.matches(Regex("[A-Za-z0-9_]{1,15}"))) { "Twitter 用户名格式无效" }
            put("twitter_username", if (clean.isEmpty()) JsonNull else JsonPrimitive(clean))
        }
        if (hireable != original.hireable) put("hireable", hireable)
    }
}
object ProfileEditing {
    suspend fun save(original: User, fields: ProfileFields): User {
        val login = Session.login
        val generation = Session.generation
        check(original.login.equals(login, true) && Session.token != null) { "账号已切换，请重新打开资料编辑" }
        val body = fields.patch(ProfileFields.from(original))
        val result = if (body.isEmpty()) original else Api.send<User>("PATCH", "/user", body)
        check(result.login.equals(login, true) && Session.generation == generation) { "账号已切换，当前页面不再显示旧账号的结果" }
        Session.profileChanged()
        return result
    }
}
