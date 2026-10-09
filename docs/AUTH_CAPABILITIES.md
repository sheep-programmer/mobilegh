# MobileGH 认证能力与组织仓库访问

2026-10-10 更新：已真实验证 App 内自动设备注册、全局数字弹窗、后台系统通知及 Keystore 签名批准。
首次 GitHub 授权返回后自动注册本机，无需导入文件或官方 App；后台通过可关闭的前台服务检测请求。
这是具有 Mobile Auth 能力的原生客户端 OAuth 上下文下的运行时验证；下文关于自有第三方 Client ID 和公开 API 的资格限制仍成立。
见[自动注册与实测记录](MOBILE2FA_NATIVE_IMPORT.md)。

核对日期：2026-10-06（Asia/Taipei）。范围：GitHub.com 的公开支持能力、用户提供的分析文档，以及指定 APK 的清单、UI 资源和认证组件名称。本次仅新增本文档；没有修改认证代码、登录账号、调用私有认证接口或发布产物。

## 结论

**目前没有查到 GitHub 向第三方客户端公开支持的 GitHub Mobile 数字匹配设备注册、请求获取或批准／拒绝 API。** 官方文档描述的是安装并登录 GitHub Mobile 后进行公钥认证；公开 GraphQL schema 中未找到分析文档所列的相关字段。这个结论是对公开支持边界的判断，不代表内部接口不存在，也不代表以后不会开放。[配置 2FA][s1]、[公开 schema][s3]

**公开支持结论：MobileGH 当前不能依赖已发布的第三方 API 实现同类 Mobile 公钥注册和数字匹配审批。这不是对内部接口技术可行性的最终结论，完整端点、功能开关和服务端授权条件正在继续核对。** 依据是缺少公开的注册／审批契约及支持文档，不是“只差提取一个官方客户端 secret”。拥有自己的 OAuth 应用、Token 或自行生成的设备公钥，也不能被当作该能力已经获得公开支持的证明；本次没有测试内部接口的运行时接受条件。

这一结论不等于“逆向工程一概被禁止”，也不声称内部协议在技术上绝对无法被研究或调用。本任务明确限制为公开支持能力研究和 APK 名称级检查，不实施官方客户端身份仿冒或绕过；这是本次授权范围，不是对逆向工程作普遍禁令。

MobileGH 可以提供原生登录入口、OAuth 设备授权状态和 TOTP 验证器，把 GitHub 登录、2FA、组织 SAML 和授权确认交给系统浏览器。用户仍可在官方 GitHub Mobile 中完成浏览器提出的数字匹配审批；这不会把 MobileGH 注册成同一种审批设备。[OAuth 设备流程][s4]、[GitHub Mobile 登录验证][s2]

**通过 2FA 不会增加仓库权限。PAT 也受组织策略限制。** 仓库访问取决于用户本身的权限、Token 授权范围、组织／企业策略及所需 SSO 授权。官方客户端的 OAuth 特权不能由第三方应用自行取得。[Token 权限][s5]、[PAT 策略][s6]、[特权 OAuth 应用][s8]

## 当前本地实现

主代理在实现期间确认：当前本地实现已采用原生 OAuth 设备流程和系统浏览器，不再使用 WebView hooks；登录前可打开原生 TOTP 验证器。本文为这条实现提供支持边界与组织访问说明，不建议回退到网页注入、密码缓存或自动批准授权。此前 `WebLogin.kt` 的表述在下文作为历史说法更正，不代表当前登录实现。

## 能力边界

| 用户期望 | 公开支持情况 | MobileGH 可行方式 |
| --- | --- | --- |
| 原生入口登录 GitHub | 有公开 OAuth 授权流程 | 原生页面展示进度，系统浏览器完成登录与授权，应用通过设备流程取得自己的授权 |
| 在 MobileGH 内批准 GitHub 的数字匹配请求 | 未找到第三方公开注册／审批路径 | 提示用户在官方 GitHub Mobile 审批，或在 GitHub 页面选择已经配置的替代认证方式 |
| 在手机上生成 GitHub TOTP | GitHub 明确支持任意兼容 TOTP 应用 | 使用用户主动提供的设置密钥／二维码，本地生成标准 TOTP |
| 查看组织、协作及私有仓库 | 公开 API 支持，但受用户权限和凭据策略约束 | 查询授权范围内的仓库；针对缺失权限给出具体补救入口 |
| 用 PAT 保证与官方 App 相同的访问范围 | 没有这种保证 | 根据 Token 类型核对权限、组织政策、到期时间与 SSO |
| 建立组织长期集成 | GitHub 推荐 GitHub App | 由组织安装并批准所需仓库和权限；不提供 Mobile 数字匹配能力 |

以上支持情况依据 [2FA 文档][s1]、[OAuth 文档][s4]、[PAT 文档][s5]、[仓库 REST API][s14]。系统浏览器方案也符合原生 OAuth 应用使用外部用户代理的规范。[RFC 8252][s16]

### 三种容易混淆的“验证码”

| 类型 | 官方说明 | 实际作用 |
| --- | --- | --- |
| GitHub Mobile 数字匹配 | 浏览器可能显示两位数字，用户在官方 Mobile 审批提示中输入 | 确认某次登录请求；不是可离线生成的 TOTP |
| TOTP | SHA1、6 位、30 秒周期 | 用户配置的验证器根据共享设置密钥生成第二因素 |
| OAuth 设备流程 `user_code` | 8 个字符，中间有连字符 | 在 GitHub 授权页面关联本次应用授权；不替代登录、2FA 或组织批准 |

数字匹配采用公钥认证这一点来自 GitHub 官方说明；本文未重新验证其内部签名协议。[GitHub Mobile 2FA][s1]、[两位数字提示][s2]、[设备授权字段][s4]

## 公开 API 核对

### GraphQL schema

来源为官方 [Public schema 页面][s3]链接的 [schema.docs.graphql][schema]。以不携带 Authorization、Token 或 Cookie 的普通公开 GET 下载；没有使用登录态 introspection，也没有向 GraphQL 服务发送认证操作。

本次下载返回 HTTP 200，内容是 GraphQL SDL，包含 `Query`、`Mutation`、`Repository` 定义。检查覆盖整个 SDL 的标识符，并核对查询和 mutation 根字段；不是仅检查某页 UI 或搜索结果。

| 核对项 | 下载结果 |
| --- | --- |
| 文件字节数 | `1558210` |
| SHA-256 | `2e1ddaaa59fcc4f427cfc63b8add318b5268e386b80984eb9fe939204857bc1a` |
| `addMobileDevicePublicKey` | 0 次；未见公开 mutation 字段 |
| `approveMobileAuthDeviceRequest` | 0 次；未见公开 mutation 字段 |
| `mobileAuthStatus` | 0 次 |
| `activeAuthRequest` | 0 次 |
| `hasValidDeviceAuthKey` | 0 次 |
| `hasExpiredAuthRequest` | 0 次 |
| `MobileAuthRequests` | 0 次；这是分析文档中的客户端操作名，单独缺失不能证明某字段不存在 |

另对包含 `MobileAuth`、`MobileDevicePublicKey`、`AuthDeviceRequest`、`DeviceAuthKey` 的标识符作不区分大小写的搜索，未找到匹配。根字段中的企业 2FA 策略设置操作用于企业管理，不能据此推导出个人设备审批能力。

**解释边界：** GraphQL 操作名可由客户端自行命名；真正有关的是服务器公开的字段和输入类型。APK 中出现操作名、工作任务或 UI 资源，既不证明普通第三方 OAuth／PAT 可以调用，也不构成支持承诺。结合公开 schema 和官方 2FA 文档，目前应把同类设备注册／审批列为“无公开支持路径”，而不是尝试复用官方应用身份。[公开 schema][s3]、[GitHub Mobile 配置][s1]

以下只读取公开 schema，可复核上述直接标识符检查。schema 会变化，未来下载的哈希可能不同：

```bash
python3 - <<'PY'
import hashlib
import re
import urllib.request

url = "https://docs.github.com/public/fpt/schema.docs.graphql"
with urllib.request.urlopen(url, timeout=30) as response:
    data = response.read()
schema = data.decode("utf-8")
assert re.search(r"^type Query\b", schema, re.M)
assert re.search(r"^type Mutation\b", schema, re.M)
assert re.search(r"^type Repository\b", schema, re.M)
print("bytes:", len(data))
print("sha256:", hashlib.sha256(data).hexdigest())
for name in (
    "addMobileDevicePublicKey", "approveMobileAuthDeviceRequest",
    "mobileAuthStatus", "activeAuthRequest", "hasValidDeviceAuthKey",
    "hasExpiredAuthRequest", "MobileAuthRequests",
):
    print(name, len(re.findall(r"\b" + re.escape(name) + r"\b", schema)))
PY
```

### REST 交叉检查

补充下载 GitHub 官方仓库发布的 [GitHub.com REST OpenAPI][s17]，同样不携带凭据。文件为有效 OpenAPI 3.0.3，包含 816 个路径；检查路径及操作名称、摘要、描述，未找到 MobileAuth、Mobile 设备认证或数字匹配审批操作，上述公钥注册／批准名称也均为 0 次。文件大小为 `13014127` 字节，SHA-256 为 `f3efa055b46b43f5f133ecf792a36a7f50cf8a4378cbd177390bc2bf8c6097cd`。这是公开 REST 支持面的交叉证据，不是对任何内部服务的探测。

## 更正既有说法

研究开始时，仓库注释和用户分析文档中存在以下需要限定的说法。工作区有并行改动，这里记录的是研究发现，不把旧注释当作当前实现保证。

| 既有说法／来源 | 正确表述与依据 |
| --- | --- |
| `WebLogin.kt`：Classic PAT 因不受第三方 OAuth 限制而能看到全部组织仓库，范围与官方 App 一致 | OAuth 应用批准与 PAT 政策是不同机制。组织可分别禁止 Classic／细粒度 PAT，还可限制寿命；Classic PAT 可能需要 SSO 授权。用户自身和 Token 范围也限制访问，不能保证官方 App 的范围。[PAT 政策][s6]、[PAT SSO][s7] |
| 把完成 2FA 当成取得组织／仓库授权 | 2FA 验证身份，可满足组织准入要求，但不会赋予成员身份、团队访问、仓库读写权限或 Token scope。[2FA][s1]、[Token 能力][s5] |
| `GitHub.kt`：`/user/memberships/orgs` 与 `/user/orgs` 的差集就是“组织未批准应用” | 差集只能是诊断线索。`/user/orgs` 对细粒度 Token 明确返回 `200` 和空列表；membership 还可能是 pending，且分页、请求失败、SSO 和 scope 影响结果。不能直接给每个缺失组织贴上未批准标签。[组织列表][s11]、[成员关系][s12]、[REST 认证][s13] |
| `GitHub.kt`：换成 `/user/repos?affiliation=organization_member` 即可取到所有组织仓库 | 该接口列出授权可见且用户有权限的仓库。更换枚举入口有助于发现仓库，但不会扩大授权或绕过策略。外部协作者还应考虑 `collaborator`，而不是只使用 `organization_member`。[仓库枚举][s14] |
| 官方 App 是特权应用，因此第三方仿照它即可取得相同权限 | 官方特权应用名单确实包含 GitHub for Android／iOS，且可豁免 OAuth 应用访问限制；这不是 MobileGH 的授权，也不能推导为豁免全部组织安全策略。[特权应用][s8] |
| 用户分析文档把所有私钥描述为必然处于 TEE／StrongBox，或把数字匹配等同于 passkey | 本次名称级检查无法验证硬件驻留、可导出性、签名绑定、运行时生物识别或抗钓鱼属性。官方确认公钥认证，但未据此承诺这些细节；不把它与浏览器 passkey 注册混为一谈。[2FA 配置][s1] |

## 已采用的可行登录与 TOTP 方案

### OAuth：原生入口，系统浏览器完成认证

1. 使用 **MobileGH 自己注册的 OAuth 应用**，在该应用设置中启用设备流程。GitHub 文档主要把此流程用于无浏览器／headless 客户端；用于 MobileGH 的实际取舍是由原生界面发起并把浏览器步骤交给外部浏览器。
2. 调用公开 `POST https://github.com/login/device/code`，携带自己的 `client_id` 和必要 scopes；以响应的 `expires_in`、`interval` 为准。设备流程不要求 `client_secret`。
3. 原生页面显示 `user_code`，提供复制和“在系统浏览器中继续”入口，打开响应的 `verification_uri`。设备流程无需从回调页面、Cookie 或网页 DOM 抓取 Token。
4. 用户在 GitHub 页面选择账号、完成既有 2FA／passkey／SAML，并确认 MobileGH 的授权。若选择 Mobile 数字匹配，由已注册的官方 GitHub Mobile 批准；用户也可选择已配置的 TOTP 等入口。
5. 原生应用按规定间隔轮询公开 `POST https://github.com/login/oauth/access_token`。正确处理 pending、`slow_down`（增加至少 5 秒）、拒绝、到期、取消及设备流程未启用；到期后由用户重新开始。
6. 成功后通过 `/user` 确认本次授权账号，再建立该账号的应用会话。账号验证成功只证明凭据可识别该账号，不能证明所有组织／仓库访问已获准。

当前本地实现已按用户确认采用系统浏览器设备流程；以上说明其公开支持路径和必要行为。本次资料核对仅访问公开页面，无需浏览器登录或 sudo；真实登录中是否需要额外身份确认，由 GitHub 页面决定。本研究没有修改认证代码或执行账号端到端验证。[OAuth 文档][s4]、[原生应用外部浏览器规范][s16]

按功能请求 scopes：组织成员信息通常需要 `read:org`，私有仓库的 Classic／OAuth 授权通常需要 `repo`，通知功能需要相应 scope。Classic／OAuth 的 `repo` 不是只读源码 scope；不要把组织管理、删除仓库等权限当作浏览仓库的必要条件。[组织 API][s11]、[OAuth 授权范围][s10]

Token 生命周期也应读取实际响应和应用设置。当前 GitHub 支持 OAuth 过期 Token 与刷新：可由应用设置或 `offline_access` 请求启用；设备流程签发的 Token 刷新不需要客户端密钥。刷新支持只应依据真实成功授权响应中的 `refresh_token`、`expires_in` 等字段启用，不能从构造的无效 Token 报错推断。使用实际返回的刷新凭据，并在刷新成功后替换旧 Token 对；官方文档明确旧访问 Token 和旧刷新 Token 随之失效。没有固定到期时间也不等于永不撤销。[OAuth Token 生命周期][s4]

### TOTP：独立的原生验证器入口

GitHub 对 TOTP 应用无品牌限制，公开参数为 SHA1、6 位、30 秒。用户可在 GitHub 的 **Settings → Password and authentication** 主动配置验证器，并把设置二维码／setup key 提供给自己选择的验证器；已有 TOTP 的新增设备应按 GitHub 设置流程重新配置。[TOTP 设置][s1]

当前本地实现提供登录前的原生 TOTP 入口；这一入口可用于显示本地生成的验证码、剩余时间并让用户复制到系统浏览器。按账号存储用户主动导入的设置密钥，保持在本机受保护存储中；密钥、二维码和验证码不进入诊断日志。这里的设置密钥来自用户授权的 TOTP 配置，不从官方客户端、登录 Cookie 或设备私钥中提取。

这提供了受支持的另一种第二因素，不会迁移官方 Mobile 的设备注册，也不会自动获得组织权限。浏览器仍负责判断此次登录可用的认证方式；遇到失效方法应使用 GitHub 提供的替代／恢复流程。[登录时使用 2FA][s2]

## 组织仓库访问的补救路径

| 诊断结果 | 用户／管理员的下一步 |
| --- | --- |
| 账号本身无仓库权限、邀请未接受或 membership 是 pending | 核对登录账号，接受邀请；请组织／仓库管理员授予所需团队、协作者或仓库权限。组织成员资格不等于能访问全部私有仓库 |
| OAuth 应用被组织访问限制挡住 | 先为个人账号授权 MobileGH，再进入 **Settings → Applications → Authorized OAuth Apps → MobileGH → Request access**；由组织 owner 审核。外部协作者能否申请还取决于组织设置。[申请批准][s9] |
| OAuth 已批准但组织使用 SAML | 先进入该组织通过 IdP 建立活动 SAML 会话，再授权应用。必要时按 GitHub 文档撤销该应用的旧授权，在 SAML 登录后重新授权；组织应用批准与 SAML 是两个条件。[OAuth 与 SAML][s10] |
| Classic PAT 受限制、寿命不合规或 scope 不足 | 核对组织是否允许 Classic PAT、有效期是否满足政策及端点所需 scopes；改用组织允许的 OAuth／GitHub App／细粒度 PAT。额外的 2FA 或换列表接口不能修复这种限制。[PAT 政策][s6] |
| Classic PAT 尚未获 SAML 授权 | 先通过组织 IdP 建立关联身份，在 **Settings → Developer settings → Personal access tokens → Configure SSO → Authorize** 为该组织授权。修改 scope、重建或到期可能要求重新授权。[PAT SSO][s7] |
| 细粒度 PAT 仅能访问部分仓库或仍 pending | 核对 resource owner、所选仓库及端点权限，等待组织 owner 批准。SSO 授权在创建时完成，组织审核是另一项要求；一个 Token 不能跨多个 resource owner／组织。[PAT 类型][s5]、[审核政策][s6] |

仓库枚举可使用 `/user/repos` 的 `owner,collaborator,organization_member` 分类并完成分页；按组织过滤显示已授权结果。`/user/orgs`、`/user/memberships/orgs` 提供辅助成员信息，不能作为仓库可见范围或批准状态的完整证明。[仓库 API][s14]、[成员 API][s12]

错误提示也应区分：`401` 常表示凭据无效；`403` 可能是权限、策略或限流；`404` 也可能是在隐藏无权访问的私有资源。Classic PAT 缺少 SAML 授权可能得到 `403`／`404`，多组织请求也可能返回部分结果；依据响应状态、`X-GitHub-SSO` 和端点／Token 类型诊断，不把空列表统一解释成“未批准 MobileGH”。诊断只保留状态与原因类别，不记录 Authorization、Cookie 或 Token。[REST 认证][s13]、[错误排查][s15]

## APK／UI 名称级清单与差距

输入分析文档：`/Users/sheep/Library/Containers/com.tencent.qq/Data/Downloads/GitHub_Mobile_Mobile2FA_逆向分析文档.md`。已读取，其反编译结论作为用户提供的线索；本文不把内部协议描述当作第三方支持证明。

APK：`/Users/sheep/Downloads/GitHub.apk.1`，大小 `41718693` 字节；清单为 `com.github.android`、`1.275.0`、versionCode `946`、minSdk `32`、target／compileSdk `37`。SHA-256：`7ba0e05047f4b7aa53b89ce2a062a3f8e51b9695e1e7070c8d697156d03b42c3`。包名、版本及大小与分析文档相符；没有核验发布者签名，因此不以包名或哈希证明官方来源。

方法：Android SDK `aapt dump badging`、`aapt dump xmltree ... AndroidManifest.xml`、`aapt dump --values resources`；仅查看相关清单项、UI 资源名称／文案。DEX 仅解析类型／类定义名称，没有反编译方法体、搜索任意字符串池、提取客户端密钥或设备私钥，也没有运行 APK。

| 名称级证据 | 能说明的功能／MobileGH 差距 |
| --- | --- |
| `com.github.android.auth.SimplifiedLoginActivity`、`ReLoginActivity`；`net.openid.appauth.RedirectUriReceiverActivity`、`AuthorizationManagementActivity` | 登录、重新登录及浏览器授权组件存在；名称不能证明其内部授权参数可复用。MobileGH 应使用自己的公开流程 |
| `com.github.android.twofactor.TwoFactorActivity`、`TwoFactorDialog`、`twofactor.worker.RegisterTwoFactorWorker`；`com.github.domain.twofactor.RegisterAuthCertLocalException`／`ServiceException`／`GeneralException` | 存在原生 2FA UI、注册任务和错误分类；无法由这些名称推导第三方可注册同类设备 |
| `layout/activity_two_factor`；`input_confirm_digits_hint`；`two_factor_dialog_new_sign_in_request`／`new_device_verification_request`／`password_reset_request` | 有确认数字、登录验证和密码重置请求的 UI 资源；完整审批能力仍受服务器支持边界约束 |
| `alert_two_factor_fetching_request_title`、`no_requests_title`、`approved_title`、`approval_failed_title`、`rejection_title`、`rejection_failure_title` | 资源覆盖加载、无请求、批准／拒绝及失败状态，可参考其状态完整性设计自己的 OAuth 等待／失败界面 |
| `com.github.android.pushnotifications.PushNotificationsService`；`POST_NOTIFICATIONS`、`com.google.android.c2dm.permission.RECEIVE`；`home_missed_two_factor_header`、`notification_setting_two_factor` | 有推送与错过认证请求的提示资源；普通推送能力不等于能订阅 GitHub Mobile 认证请求。清单未声明 `READ_SMS`／`RECEIVE_SMS` 或自定义无障碍服务 |
| `sign_in_with_github_enterprise`、`sign_in_enter_enterprise_url`、`sign_in_error_dialog_message_browser_oauth_exception`／`invalid_oauth_state`／`two_factor`；`com.github.service.auth.AuthenticatorService` | 有企业登录和浏览器／状态／密钥设置错误入口；AuthenticatorService 的名称不证明 TOTP 算法或运行行为 |
| `organizations.OrganizationsFragment`、`repositories.fragments.RepositoriesFragment`；`actions.repositoryworkflows.RepositoryWorkflowsFragment`、`actions.checklog.CheckLogFragment`；`actions_logs_view_raw`、`actions_logs_view_show_timestamps`、`actions_rerun_all_jobs`、`actions_cancel_workflow_button_label` | 样本包含组织、仓库及 Actions 日志／重跑／取消资源，不能再笼统声称官方 App 完全没有 Actions 日志或只具备个人仓库页。是否在某账号上可见、可执行，需要另行运行验证 |

该清单用于识别 UI 和产品差距，不能证明端点权限、服务端特性开关、布局细节、硬件密钥属性或实际账号访问结果。可补齐的是公开 OAuth 状态、TOTP 入口和组织权限补救提示；同类 Mobile 数字匹配注册／审批需要 GitHub 另行提供第三方支持。

对照当前本地实现，差异是明确的：MobileGH 原生页等待自己的 OAuth 设备授权完成，登录前验证器显示用户配置的 TOTP；样本的 `TwoFactorActivity` 则展示服务器下发的认证请求、确认数字及批准／拒绝结果。前者取得应用 API 授权，后者是官方 Mobile 的第二因素审批入口，两者不能只靠替换 UI 或添加本地密钥互换。样本还具备认证推送与注册任务的名称证据；MobileGH 当前公开支持方案通过系统浏览器和用户既有认证方式完成这一环节。[设备流程][s4]、[Mobile 审批][s2]

## 官方证据链接

下列资料均在核对日期读取；公开 schema 的具体下载结果已记录在上文。

- [配置 2FA：TOTP 参数、GitHub Mobile 公钥认证][s1]
- [使用 2FA 登录：GitHub Mobile 两位数字与批准／拒绝][s2]
- [Public schema 页面][s3]与 [GraphQL SDL 下载][schema]
- [OAuth 授权、设备流程及 Token 生命周期][s4]
- [PAT 类型、用户权限上限及细粒度限制][s5]
- [组织 PAT 禁用、寿命及审核政策][s6]
- [Classic PAT 的 SAML SSO 授权][s7]
- [GitHub 特权 OAuth 应用名单][s8]
- [申请组织批准 OAuth 应用][s9]
- [OAuth 授权范围、组织访问与 SAML 会话][s10]
- [REST：已认证用户的组织列表][s11]
- [REST：已认证用户的组织成员关系][s12]
- [REST 认证与 SSO／部分结果][s13]
- [REST：已认证用户的仓库枚举][s14]
- [REST 错误排查][s15]
- [RFC 8252：原生 OAuth 应用使用外部用户代理][s16]
- [GitHub 官方 GitHub.com REST OpenAPI][s17]

[s1]: https://docs.github.com/en/authentication/securing-your-account-with-two-factor-authentication-2fa/configuring-two-factor-authentication
[s2]: https://docs.github.com/en/authentication/securing-your-account-with-two-factor-authentication-2fa/accessing-github-using-two-factor-authentication
[s3]: https://docs.github.com/en/graphql/overview/public-schema
[schema]: https://docs.github.com/public/fpt/schema.docs.graphql
[s4]: https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps
[s5]: https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens
[s6]: https://docs.github.com/en/organizations/managing-programmatic-access-to-your-organization/setting-a-personal-access-token-policy-for-your-organization
[s7]: https://docs.github.com/en/enterprise-cloud@latest/authentication/authenticating-with-single-sign-on/authorizing-a-personal-access-token-for-use-with-single-sign-on
[s8]: https://docs.github.com/en/apps/oauth-apps/using-oauth-apps/privileged-oauth-apps
[s9]: https://docs.github.com/en/account-and-profile/how-tos/organization-membership/requesting-organization-approval-for-oauth-apps
[s10]: https://docs.github.com/en/apps/oauth-apps/using-oauth-apps/authorizing-oauth-apps
[s11]: https://docs.github.com/en/rest/orgs/orgs#list-organizations-for-the-authenticated-user
[s12]: https://docs.github.com/en/rest/orgs/members#list-organization-memberships-for-the-authenticated-user
[s13]: https://docs.github.com/en/rest/authentication/authenticating-to-the-rest-api
[s14]: https://docs.github.com/en/rest/repos/repos#list-repositories-for-the-authenticated-user
[s15]: https://docs.github.com/en/rest/using-the-rest-api/troubleshooting-the-rest-api
[s16]: https://www.rfc-editor.org/rfc/rfc8252#section-4.1
[s17]: https://raw.githubusercontent.com/github/rest-api-description/main/descriptions/api.github.com/api.github.com.json

## 当前实现补充

当前本机 gh CLI 凭据是 OAuth Token（`gho_` 类型），不是 Classic PAT。用它读到组织，只能证明该 OAuth 授权可访问那些组织，不能作为“PAT 无条件绕过组织限制”的证据。

公开 schema 同时提供了 GitHub Lists 的 `createUserList`、`updateUserList`、`deleteUserList` 和 `updateUserListsForItem`，本轮使用这些接口实现原生列表；成就仍按公开主页读取。[UserList 官方文档](https://docs.github.com/en/graphql/reference/users#userlist)

## 本轮后续验证

[实际 APK 协议和真实凭据对照](MOBILE2FA_PROTOCOL_CHECK.md)已补齐 CLI OAuth、临时 Classic PAT 和 MobileGH 自有 OAuth 的 baseline/原版请求头测试。三者的已知设备批准字段均不可见；临时 PAT 已撤销。用户完成身份复核后，两组织已批准 MobileGH，实际自有 OAuth 读取到 2 组织、15 仓库（4 私有）。组织访问已解决，设备批准尚未实现。

[官方完整请求范围与授权方式的后续对照](MOBILE2FA_OAUTH_SCOPE_CHECK.md)进一步验证了 MobileGH 自有设备授权，以及带 S256 PKCE 的网页授权。两者实际签发同一组扩展范围，但状态、注册、批准字段仍返回 `undefinedField`；请求中的 `user:assets` 没有出现在实际 scope 列表。两枚本次测试 Token 已定向撤销，原有授权保留。当前 APK 仍未提供独立的两位数字批准，不把打开官方 App 当成该功能的完成证明。
