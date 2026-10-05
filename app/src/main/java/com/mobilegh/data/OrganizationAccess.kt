package com.mobilegh.data

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.ListSerializer
import java.util.Locale

data class OrganizationDirectory(
    val orgs: List<Org>,
    val warnings: List<String>,
    val scopes: String?,
)

/** Memberships and repository owners are complementary, not proof that an app is approved. */
object OrganizationAccess {
    suspend fun read(force: Boolean): OrganizationDirectory {
        val orgs = linkedMapOf<String, Org>()
        val warnings = mutableListOf<String>()
        var scopes: String? = null
        var succeeded = 0

        suspend fun <T> readPages(path: String, label: String, decode: (String) -> List<T>, accept: (T) -> Unit) {
            try {
                for (page in 1..20) {
                    val r = Api.call("GET", "$path${if ('?' in path) '&' else '?'}per_page=100&page=$page", force = force)
                    r.headers["x-oauth-scopes"]?.let { scopes = it }
                    if (r.headers["x-github-sso"] != null) warnings += "$label：GitHub 提示需要检查 SSO 授权"
                    Api.ensureOk(r)
                    val list = decode(r.body)
                    succeeded++
                    list.forEach(accept)
                    if (list.size < 100) return
                }
                warnings += "$label：已读取 2,000 条，列表可能不完整"
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                warnings += "$label：${e.message ?: "读取失败"}"
            }
        }
        fun add(o: Org) { if (o.login.isNotBlank()) orgs[o.login.lowercase(Locale.ROOT)] = o }
        readPages("/user/orgs", "组织列表", { Api.json.decodeFromString(ListSerializer(Org.serializer()), it) }, ::add)
        readPages("/user/memberships/orgs?state=active", "组织成员身份", { Api.json.decodeFromString(ListSerializer(OrgMembership.serializer()), it) }) {
            if (it.state == "active") add(it.organization)
        }
        // A collaborator may have repositories in an org that isn't in their membership list.
        readPages("/user/repos?affiliation=owner,collaborator,organization_member&sort=pushed", "可访问仓库", { Api.json.decodeFromString(ListSerializer(Repo.serializer()), it) }) { repo ->
            if (repo.isPrivate) Net.markPrivate(repo.fullName)
            if (repo.owner.type == "Organization") add(Org(login = repo.owner.login, avatarUrl = repo.owner.avatarUrl))
        }
        if (succeeded == 0) throw ApiException(-1, warnings.joinToString("\n"))
        val scopeSet = scopes?.split(',')?.map { it.trim() }?.toSet()
        if (scopeSet != null && scopeSet.none { it in setOf("read:org", "write:org", "admin:org", "repo") }) {
            warnings += "当前授权未包含 read:org；组织成员身份可能不完整"
        }
        return OrganizationDirectory(orgs.values.sortedBy { it.login.lowercase(Locale.ROOT) }, warnings.distinct(), scopes)
    }

    data class Check(val name: String, val ok: Boolean, val message: String, val ssoUrl: String? = null)

    suspend fun check(org: String): List<Check> {
        require(org.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}"))) { "组织名称格式无效" }
        return listOf("成员身份" to "/user/memberships/orgs/$org", "仓库列表" to "/orgs/$org/repos?per_page=1&type=all").map { (label, path) ->
            try {
                val r = Api.call("GET", path, force = true)
                val sso = r.headers["x-github-sso"]
                val url = sso?.substringAfter("url=", "")?.substringBefore(';')?.trim()
                    ?.takeIf { it.startsWith("https://github.com/") }
                val message = when {
                    sso?.startsWith("required") == true -> "需要为当前授权完成组织 SSO"
                    r.code == 403 -> "GitHub 拒绝访问，请检查应用批准、Token 策略、权限或 API 配额"
                    r.code == 404 -> "当前授权无法确认此组织或成员身份（不存在、非成员或不可见）"
                    r.code in 200..299 -> if (label == "成员身份") {
                        val member = Api.json.decodeFromString(OrgMembership.serializer(), r.body)
                        "${if (member.state == "active") "已加入" else "状态：${member.state}"} · ${member.role}"
                    } else {
                        val count = Api.json.decodeFromString(ListSerializer(Repo.serializer()), r.body).size
                        if (count > 0) "可读取仓库；这不代表拥有组织内所有仓库权限" else "接口成功，但没有返回仓库；访问范围可能受限"
                    }
                    else -> "请求失败（${r.code}）"
                }
                Check(label, r.code in 200..299 && sso?.startsWith("required") != true, message, url)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { Check(label, false, e.message ?: "读取失败") }
        }
    }
}
