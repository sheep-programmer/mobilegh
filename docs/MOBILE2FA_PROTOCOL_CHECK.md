# Mobile2FA：实际 APK 协议与服务器能力核对

核对日期：2026-10-06。范围已扩展为定向 APK 代码／DEX 方法读取：确认 transport endpoint、header、GraphQL 请求与客户端分支。仅新增本文档，不改认证代码或 `AUTH_CAPABILITIES.md`，不干预父任务构建，不执行任何注册、批准、拒绝、删除等 mutation。

## 当前可立即使用的结果

```http
POST https://api.github.com/graphql
GraphQL-Features: merge_queue,issues_close_state,copilot_iap_max_sku,issues_copilot_assignment_api_support,coding_agent_model_selection,graphql_pr_comment_positioning,copilot_education_sku_mobile,pull_request_stacks_graphql
```

这组 endpoint／header 来自实际 APK 的方法指令，不是根据公开 schema 推测。header 列表中没有 Mobile2FA 专用 flag。认证使用调用者自己的凭据；上面没有凭据值，也不包含官方 OAuth 应用身份。

**当前判断：注册、获取和审批请求契约确实存在；普通 MobileGH OAuth／PAT 是否能合法注册自行生成的新密钥，服务器授权条件仍未证实。** 公开 schema 缺失只说明公开支持面没有列出这些能力，不能证明技术上不可实现。本人账号、自行生成密钥和明确确认的注册行为，也不能直接被归类为 2FA 绕过。

## 样本与读取方法

- 用户分析文档：`/Users/sheep/Downloads/GitHub_Mobile_Mobile2FA_逆向分析文档.md`，595 行。
- APK：`/Users/sheep/Downloads/GitHub.apk.1`；前轮清单核对为 `com.github.android`、版本 `1.275.0`、versionCode `946`。
- APK SHA-256：`7ba0e05047f4b7aa53b89ce2a062a3f8e51b9695e1e7070c8d697156d03b42c3`。
- 原文的 `ddcroot` 是其反编译输出包装；本 APK 的相应 DEX 类实际为默认包 `wn0`、`w5q`、`pv4` 等。
- 使用 DEX 类／方法索引定位，再用 JADX 随附 dexlib2 读取所选方法的真实指令、字符串引用及方法引用；不依赖伪反编译控制流。未完成全 APK JADX 反编译，也未导出任意字符串池。
- 只输出协议相关常量。没有提取或复制第一方 OAuth secret／身份、Token、Cookie、已有设备私钥，未读取登录态凭据；临时分析文件位于仓库之外。

下文的 `@` 地址是方法内的 DEX code-unit 偏移，不是文件字节偏移；便于按相同方法复核。

## Endpoint 与 transport header

| APK 证据 | 实际结果 | 解释范围 |
| --- | --- | --- |
| `classes.dex / ems.k(mia0) @0034` | 返回 `https://api.github.com/graphql` | GitHub.com 分支的实际 URL；未发现需要改用 `github.com/_graphql` 的证据 |
| `classes3.dex / aib0.G() @0037–0039` | 将相同 URL 设置到请求 builder | 与 endpoint 选择一致；该方法另从运行时字段构造 Bearer header，没有暴露凭据值 |
| `ems.k @001e`、`@002d` | 企业分支模板为 `https://api.%s/graphql`、`https://%s/api/graphql` | 不把企业模板当作 GitHub.com 的另一个审批 endpoint |
| `classes4.dex / zqf.a @0015`、`@0090`、`@010b` | Apollo client builder 的分支调用 `ems.k(mia0)` | 将 URL 选择连接到实际 GraphQL client 构造，而非只找到一个孤立字符串 |
| `classes3.dex / c4b.a @002a–0050` | 8 个 feature 常量组成数组，以 `,` 连接，再写入 `GraphQL-Features` | 这是实际 HTTP header；是否跳过添加由请求 tag 的布尔分支控制，不能说每个请求无条件携带 |
| `c4b.a @001c–0020` | 从配置字段设置 `User-Agent` | 没有复制官方 User-Agent 身份；能力检查应保留 MobileGH 自己的标识 |
| `c4b.a` 的其他 discriminator 分支 | 包含另一个服务的 `X-GitHub-Api-Version` 设置逻辑 | 不能把该分支的版本日期当作 Mobile2FA 必需 header |

`GraphQL-Features` 的真实数组顺序为：

| code-unit 偏移 | feature 名称 |
| --- | --- |
| `002e` | `merge_queue` |
| `0030` | `issues_close_state` |
| `0032` | `copilot_iap_max_sku` |
| `0034` | `issues_copilot_assignment_api_support` |
| `0036` | `coding_agent_model_selection` |
| `0038` | `graphql_pr_comment_positioning` |
| `002a` | `copilot_education_sku_mobile` |
| `002c` | `pull_request_stacks_graphql` |

`mobile_device_auth` 的引用定位到 `com.github.android.pushnotifications.f` 以及 `k3w.i`／`x3w.b` 等推送处理路径。没有在上述 GraphQL header 数组中发现它；不能据其名字虚构 `GraphQL-Features: mobile_device_auth` 的支持契约。

官方 [Forming calls with GraphQL](https://docs.github.com/en/graphql/guides/forming-calls-with-graphql) 独立确认 GitHub.com endpoint，并说明 GraphQL 可以使用 PAT、GitHub App 或 OAuth Token。该通用认证说明没有承诺下面的 Mobile 专用字段对所有这些凭据开放。

## APK 中的实际 GraphQL 请求

### 注册：`wn0.d()`，`classes4.dex @0005`

以下是 APK 请求字符串的排版整理，没有改变字段或变量类型：

```graphql
mutation AddMobileDevicePublicKey(
  $publicKey: String!
  $type: MobileDeviceKeyType!
  $verificationSignature: String!
  $verificationMessage: String!
  $deviceName: String!
  $deviceModel: String!
  $isHardwareBacked: Boolean!
) {
  addMobileDevicePublicKey(input: {
    publicKey: $publicKey
    verificationSignature: $verificationSignature
    verificationMessage: $verificationMessage
    type: $type
    deviceName: $deviceName
    deviceModel: $deviceModel
    deviceOs: ANDROID
    isHardwareBacked: $isHardwareBacked
  }) {
    expiresAt
  }
}
```

这里明确引用的 enum 类型是 `MobileDeviceKeyType`。请求内联构造 `input`，**没有引用 `AddMobileDevicePublicKeyInput` 这个命名类型**。该命名 input 类型实际叫什么，必须读取可见 mutation 字段的参数类型才能确定；不能仅按字段名惯例认定。

这证明客户端有上传公钥及所有权验证材料的契约，不证明普通 OAuth／PAT 已获得该字段权限，也不证明复制官方 secret 是必要条件。本文不提交这条 mutation。

### 获取：`w5q.d()`，`classes4.dex @0005`

```graphql
query MobileAuthRequests {
  viewer {
    email
    primaryEmail
    login
    mobileAuthStatus {
      hasValidDeviceAuthKey
      hasExpiredAuthRequest
      activeAuthRequest {
        id
        payload
        challengeRequired
        type
      }
    }
    id
    __typename
  }
  id
  __typename
}
```

`MobileAuthRequests` 是客户端操作名；实际服务器字段是 `User.mobileAuthStatus`。只读能力检查可以缩小为 `viewer { mobileAuthStatus { hasValidDeviceAuthKey } }`，不需要读取邮箱或待批请求详情。

### 批准：`pv4.d()`／`qv4.d()`，`classes4.dex`／`classes3.dex @0005`

```graphql
mutation ApproveMobileAuthDeviceRequest(
  $requestId: Int!
  $signature: String!
) {
  approveMobileAuthDeviceRequest(input: {
    requestId: $requestId
    signature: $signature
    signatureVersion: V1
  }) {
    clientMutationId
  }
}
```

**确定的类型细节：** `requestId` 是 `Int!`，不是根据名字猜测的 `ID!`；`signatureVersion` 是内联 enum 值 `V1`，不是字符串 `"V1"`。此请求同样不引用命名的 `ApproveMobileAuthDeviceRequestInput`。服务器字段的真实 input 类型和签名版本 enum 类型，需要在相应凭据上下文中读到参数定义后确认。

生成的 `rv4`／`sv4` AST 类也引用 `requestId`、`signature`、`signatureVersion`；它们不是独立 transport。仅凭协议形状不能判定签名编码、服务器密钥资格或授予权限；本文不提交批准请求。

## 更正本地 gate／feature flag 的混淆

`ld3.a–g` 的分支用 `addAuthPublicKey`、`addRecoveryPublicKey`、`deleteAuthPublicKey`、`fetchMobileAuthRequest`、`approveMobileAuth`、`rejectMobileAuth` 和 `"3.12"` 调用 `vvb.f0`。

实际 `vvb.f0 @0000–0017` 在拼接含 `" not supported in "` 的消息并构造返回对象；`ld3` 的这些路径没有设置 HTTP header，也没有构造 GraphQL 网络请求。因此 **不能把这些操作标签认作 feature header 的值，也不能把这段本地不支持处理当作 GitHub.com 服务器拒绝普通凭据的证据**。具体服务器版本／能力路由应与真实实现分支分别核对。

`j6q` 的真实实现类还包括 `re3`／`ae3`；`ld3` 属于不同实现分支。分析应追踪所用分支，不能以 stub 分支替代实际 GraphQL 请求。

真实操作连接已核对：`ae3.c` 构造 `wn0` 注册操作、`ae3.f` 构造 `w5q` 获取操作、`ae3.a` 构造 `pv4` 批准操作；`re3` 的对应路径使用 `xn0`／`x5q`／`qv4`。这补充了请求字符串确实被实现路径引用的证据；仍不代表第三方 Token 被服务器接受。

## 已有服务器观察与仍然未知的授权

父任务提供的真实、已认证观察：

| 凭据／检查 | 已报告结果 | 能证明什么 |
| --- | --- | --- |
| GitHub CLI 的 OAuth 凭据，查询 `viewer.mobileAuthStatus` | `undefinedField`，类型为 `User` | 被测 endpoint、headers、账号、CLI OAuth 上下文不暴露此字段 |
| 同一上下文 introspection | `AddMobileDevicePublicKeyInput`、`ApproveMobileAuthDeviceRequestInput`、`MobileAuthStatus` 为 null | 这些命名类型在该上下文不可见；前两个类型名并非 APK operation 的直接引用 |
| MobileGH 自己通过设备流程取得的真实 OAuth 凭据 | 本 sidecar 尚无该上下文的服务器结果 | 不能用 CLI OAuth 的结果替代它 |
| 用户自己的 Classic／细粒度 PAT | 本 sidecar 尚无对比结果 | 两者也不应混为一个已经被测的凭据类别 |

本 sidecar 没有重放或独立核验父任务的认证响应，没有读取任何凭据。父任务未在消息中提供完整 headers，因此不能把上述结果标为“已携带本次提取的 8 项 feature header”。

仍未知：字段是否按 OAuth 应用身份、Token 类别／scope、账号 rollout、设备资格或其他服务器条件开放；是否需要额外未定位到的 header；可见字段是否仍在 resolver 阶段限制注册。以上是待区分的假设，不是已证明的 gate。

## 下一次能力检查：只读、同一 endpoint、明确凭据身份

1. 用普通合法流程取得 **MobileGH 自己注册的 OAuth 应用**签发的真实 Token；记录凭据类别与 scope 信息，但不记录 Token 值。CLI OAuth 与 MobileGH OAuth 是不同上下文。
2. 对 `https://api.github.com/graphql` 做基准 `query { viewer { login } }`，确认被测凭据实际有效。使用 MobileGH 自己的 User-Agent，不借用官方 OAuth identity。
3. 在同一凭据下分别不带和带上文 **实际提取的完整 `GraphQL-Features`** 做只读查询，保持其他条件一致。该列表不是已知 Mobile2FA 解锁开关；比较只是验证 header 是否影响可见 schema。
4. 查询实际字段，并 introspect `User`／`Mutation` 的字段、`args` 及 `type`／`ofType`，确认实际参数类型；额外检查 APK 直接引用的 `MobileDeviceKeyType`，不只检查按惯例猜出的 input 类型名。
5. 若本人 PAT 的对比确有需要，分别标记 Classic 与细粒度 PAT。保留响应中的 HTTP 状态、GraphQL error code／path／类型，以及是否使用 feature header；不保存 Authorization 或完整账户响应。

最小只读字段检查：

```graphql
query MobileGHMobile2FAVisibility {
  viewer {
    mobileAuthStatus {
      hasValidDeviceAuthKey
    }
  }
}
```

若依然 `undefinedField`，结论应限定为 **这个真实 MobileGH OAuth／PAT 上下文目前不暴露该字段**。若字段可见，只证明 schema 可见，不证明可以注册密钥。最终回答“是否能注册本人新密钥”需要后续明确授权的有效自有密钥注册实验，或可直接适用的服务器 gate／官方支持说明；本轮不执行该 mutation。

实际参数类型的只读检查可用以下请求；在本地仅筛选 `mobileAuthStatus`、`addMobileDevicePublicKey`、`approveMobileAuthDeviceRequest` 的结果，不需要打印整个 schema：

```graphql
query MobileGHMobile2FAParameterTypes {
  keyKind: __type(name: "MobileDeviceKeyType") {
    name
    kind
    enumValues { name }
  }
  userType: __type(name: "User") {
    fields(includeDeprecated: true) {
      name
      type { kind name ofType { kind name } }
    }
  }
  mutationType: __type(name: "Mutation") {
    fields(includeDeprecated: true) {
      name
      args {
        name
        type { kind name ofType { kind name ofType { kind name } } }
      }
    }
  }
}
```

**实现决策依据：** 目前可以实现自己的正常 OAuth／TOTP；同类 Mobile 设备审批的请求契约已找到，第三方服务器资格仍待验证。不能报告“已经可用”，也不能因公开文档或公开 schema 缺失报告“技术上绝对不可能”。

## 官方资料的作用范围

- [Forming calls with GraphQL](https://docs.github.com/en/graphql/guides/forming-calls-with-graphql)：确认公开 endpoint、请求形式及通用 OAuth／PAT 认证；不证明 Mobile 专用字段权限。
- [Public schema](https://docs.github.com/en/graphql/overview/public-schema)：公开支持面的证据；不代替某个 OAuth 应用上下文的实时 schema，也不证明内部字段无法被合法调用。
- [Configuring two-factor authentication](https://docs.github.com/en/authentication/securing-your-account-with-two-factor-authentication-2fa/configuring-two-factor-authentication)：官方 Mobile 使用公钥认证的说明；不提供上述第三方注册资格的判定条件。
- [Authorizing OAuth apps](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps)：MobileGH 获取自己真实设备流程授权的依据；不借用第一方应用身份。

## 主代理对真实请求的补充核验

2026-10-06 使用当前账号的 GitHub CLI OAuth 授权、MobileGH 自己的 User-Agent，分别不带/带本报告从 APK 提取的完整 8 项 GraphQL-Features，对 `viewer.mobileAuthStatus` 作只读查询。两者都得到 GraphQL `undefinedField`，错误类型 User、字段 mobileAuthStatus。带相同请求头查询实际 MobileDeviceKeyType 及 User/Mutation 字段：枚举为 null，相关注册/批准/状态字段未返回。没有发送注册或批准 mutation，也没有使用官方 App 身份、现有设备私钥或其他人的凭据。

这个结果证明完整已知请求头未对该 CLI OAuth 上下文开放能力；尚不能替代 MobileGH 自己签发 Token 的对照，也不能证明所有内部接口技术上永久不可实现。

## Classic PAT 与组织批准的补充验证

在用户完成当前 GitHub 身份复核后，已给 iyuca-cn、cursimple 两个组织批准 MobileGH。授权连接页中两者均从 Grant/Restricted 变成 Revoke/Allowed，保留组织其他应用的访问限制。

另创建并实际验证了仅含 read:org、repo、user 的临时 Classic PAT（账号为 sheep-programmer）。基准和 APK 的完整 8 项 GraphQL-Features 请求下，viewer.mobileAuthStatus 均返回 undefinedField；MobileDeviceKeyType 枚举仍为 null，User/Mutation 的 mobile 字段为空。该 PAT 能读取两组织。没有注册/批准/删除任何 2FA 密钥；仅撤销本次临时 PAT，原有 Token 未动。

这进一步证明当前默认 PAT 登录上下文不提供文档中的设备审批接口，仍不宣称所有未来接口或其他授权上下文永久不可实现。

## MobileGH 自有 OAuth 的实测结果

已通过 MobileGH 自己的 Client ID（Ov23litcWlPUB3KqPlQ2）的公开设备流程，在本人当前网页会话正常授权并获得真实 OAuth Token。通过 `/user` 核对账号 sheep-programmer；实际 scopes 为 gist、notifications、read:org、repo、user、workflow。响应没有 expires_in 或 refresh_token，符合当前服务端关闭固定到期的设置；没有称此授权永不撤销。

与 CLI/PAT 的测试保持同 endpoint 和 MobileGH User-Agent，分别不带、带 APK 的完整八项 GraphQL-Features。两个请求均返回 User.mobileAuthStatus 的 undefinedField。MobileDeviceKeyType 为 null；User 和 Mutation 的相关 mobile 字段均未返回。未注册或修改 2FA 设备，也未尝试借用官方客户端身份。

组织批准后，该真实 MobileGH OAuth 可以读取 iyuca-cn、cursimple；当前分别返回 9 和 6 个仓库，共 15 个，其中 iyuca-cn 有 4 个私有仓库。仓库数量是本次查询的快照，未来可变化。这是本应用自己的授权结果，不再以 CLI 或 Classic PAT 的返回代替 OAuth 验证。

**当前实现结论：组织/仓库访问已在该账号上打通；数字匹配设备注册/批准仍没有在以上真实凭据上下文找到可调用契约，客户端流程尚未达到注册成功阶段。本地生成密钥和签名可实现，但它不能改变当前服务端暴露的接口集合。此结论限定为本轮验证的账号、凭据和请求条件，不宣称任何未来 GitHub 接口永远无法支持第三方。**
