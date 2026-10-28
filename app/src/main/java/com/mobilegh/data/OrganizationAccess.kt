package com.mobilegh.data

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.ListSerializer
import java.util.Locale
import com.mobilegh.i18n.tr
import com.mobilegh.i18n.trf

data class OrganizationDirectory(
    val orgs: List<Org>,
    val warnings: List<String>,
    val scopes: String?,
)

/** Memberships and repository owners are complementary, not proof that an app is approved. */
object OrganizationAccess {
    suspend fun read(force: Boolean, includeRepositoryOwners: Boolean = true): OrganizationDirectory {
        val orgs = linkedMapOf<String, Org>()
        val warnings = mutableListOf<String>()
        var scopes: String? = null
        var succeeded = 0

        suspend fun <T> readPages(path: String, label: String, decode: (String) -> List<T>, accept: (T) -> Unit) {
            try {
                for (page in 1..20) {
                    val r = Api.call("GET", "$path${if ('?' in path) '&' else '?'}per_page=100&page=$page", force = force)
                    r.headers["x-oauth-scopes"]?.let { scopes = it }
                    if (r.headers["x-github-sso"] != null) warnings += trf("{0}：GitHub 提示需要检查 SSO 授权", label)
                    Api.ensureOk(r)
                    val list = decode(r.body)
                    succeeded++
                    list.forEach(accept)
                    if (list.size < 100) return
                }
                warnings += trf("{0}：已读取 2,000 条，列表可能不完整", label)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                warnings += "$label：${e.networkHint() ?: e.message ?: tr("读取失败")}"
            }
        }
        fun add(o: Org) { if (o.login.isNotBlank()) orgs[o.login.lowercase(Locale.ROOT)] = o }
        readPages("/user/orgs", tr("组织列表"), { Api.json.decodeFromString(ListSerializer(Org.serializer()), it) }, ::add)
        readPages("/user/memberships/orgs?state=active", tr("组织成员身份"), { Api.json.decodeFromString(ListSerializer(OrgMembership.serializer()), it) }) {
            if (it.state == "active") add(it.organization)
        }
        // Fork 的目的地只来自组织成员关系；完整权限诊断才需要遍历可访问仓库。
        if (includeRepositoryOwners) {
            // A collaborator may have repositories in an org that isn't in their membership list.
            readPages("/user/repos?affiliation=owner,collaborator,organization_member&sort=pushed", tr("可访问仓库"), { Api.json.decodeFromString(ListSerializer(Repo.serializer()), it) }) { repo ->
                if (repo.isPrivate) Net.markPrivate(repo.fullName)
                if (repo.owner.type == "Organization") add(Org(login = repo.owner.login, avatarUrl = repo.owner.avatarUrl))
            }
        }
        if (succeeded == 0) throw ApiException(-1, warnings.joinToString("\n"))
        val scopeSet = scopes?.split(',')?.map { it.trim() }?.toSet()
        if (scopeSet != null && scopeSet.none { it in setOf("read:org", "write:org", "admin:org", "repo") }) {
            warnings += tr("当前授权未包含 read:org；组织成员身份可能不完整")
        }
        return OrganizationDirectory(orgs.values.sortedBy { it.login.lowercase(Locale.ROOT) }, warnings.distinct(), scopes)
    }

    data class Check(val name: String, val ok: Boolean, val message: String, val ssoUrl: String? = null)

    suspend fun check(org: String): List<Check> {
        require(org.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}"))) { tr("组织名称格式无效") }
        return listOf(tr("成员身份") to "/user/memberships/orgs/$org", tr("仓库列表") to "/orgs/$org/repos?per_page=1&type=all").map { (label, path) ->
            try {
                val r = Api.call("GET", path, force = true)
                val sso = r.headers["x-github-sso"]
                val url = sso?.substringAfter("url=", "")?.substringBefore(';')?.trim()
                    ?.takeIf { it.startsWith("https://github.com/") }
                val message = when {
                    sso?.startsWith("required") == true -> tr("需要为当前授权完成组织 SSO")
                    r.code == 403 -> tr("GitHub 拒绝访问，请检查应用批准、Token 策略、权限或 API 配额")
                    r.code == 404 -> tr("当前授权无法确认此组织或成员身份（不存在、非成员或不可见）")
                    r.code in 200..299 -> if (label == tr("成员身份")) {
                        val member = Api.json.decodeFromString(OrgMembership.serializer(), r.body)
                        "${if (member.state == "active") tr("已加入") else trf("状态：{0}", member.state)} · ${member.role}"
                    } else {
                        val count = Api.json.decodeFromString(ListSerializer(Repo.serializer()), r.body).size
                        if (count > 0) tr("可读取仓库；这不代表拥有组织内所有仓库权限") else tr("接口成功，但没有返回仓库；访问范围可能受限")
                    }
                    else -> trf("请求失败（{0}）", r.code)
                }
                Check(label, r.code in 200..299 && sso?.startsWith("required") != true, message, url)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { Check(label, false, e.networkHint() ?: e.message ?: tr("读取失败")) }
        }
    }
}
