# Mobile2FA：官方授权范围与 MobileGH 自有 OAuth 对照

核对日期：2026-10-06。本轮目标是 MobileGH 内输入两位数字后直接批准 GitHub 登录；launcher 跳转不作为满足目标的实现。

用户最终约束：必须独立实现，不使用辅助控制。公开 IPC、通知委托和辅助 UI 不作为交付路线。验收需在不依赖官方 GitHub Mobile 的情况下，由 MobileGH 自有登录授权、自己的设备密钥和服务端确认完成批准。当前实测尚未达到该条件。

## APK 中的新证据

直接读取用户提供的 GitHub 1.275.0 APK 的 DEX，过滤输出仅包含授权范围常量与方法引用，不输出客户端凭据、现有 Token 或设备私钥。

官方原生登录构造器 `classes.dex / mbo.a(String, b95, Context) @008f` 使用以下范围：

```text
user repo notifications admin:org read:discussion user:assets project workflow
```

后续指令调用字符串拆分、集合转换及 AppAuth 请求构造，再通过 `b95.e` 打开授权流程。因此这是实际登录请求的范围，而非孤立字符串。`com.github.android.auth.k.x @00cf` 与 `auth.f.x @00ce` 在成功分支把同一范围写入本地授权记录。另有旧范围：

```text
user repo notifications admin:org read:discussion user:assets
```

这与前轮被测 MobileGH Token 的 `gist,notifications,read:org,repo,user,workflow` 不完全一致。范围不同只是新的待验证因素，不证明设备批准接口已开放，也不证明需要复制官方 OAuth 身份。

## 实际自有应用请求

本轮只使用 MobileGH 自己的 Client ID `Ov23litcWlPUB3KqPlQ2`，向 GitHub 公开设备授权端点申请上述新范围。服务端返回 HTTP 200、真实设备授权码和 899 秒期限。没有使用官方 Client ID 或 client secret。

浏览器显示 MobileGH 正请求额外的项目管理、团队讨论读取权限，两组织仍显示 Allowed。点击自己的应用授权后，GitHub 要求 `Confirm access` 身份复核，提供 GitHub Mobile、验证器与密码方式。已切换到验证器输入页并把浏览器交还用户；验证码只应由用户在 GitHub 网页输入。

## 用户完成复核后的真实结果

用户在浏览器完成身份复核后，原设备码已超过期限，兑换返回 `expired_token`。重新申请同一范围并使用已验证的会话完成授权，实际取得 MobileGH 自有 OAuth Token，通过 `/user` 核对账号为 `sheep-programmer`。

另外，官方 APK 的原生登录实际采用 AppAuth 网页授权，而不是设备授权。为排除授权方式差异，使用 MobileGH 自己的 Client ID、自己的 client secret、S256 PKCE 和注册回调地址完成网页授权，校验回调 state 后兑换第二个真实 Token。没有使用官方应用身份或 client secret。

两种流程实际签发的范围均为：

```text
admin:org,notifications,project,read:discussion,repo,user,workflow
```

请求中的 `user:assets` 没有出现在 Token 响应或 `X-OAuth-Scopes` 中。不根据这一点推定其内部归一化或准入规则，也不把“请求被接受”当成“所有官方权限已经取得”。

| 检查 | 设备授权，不带／带 APK features | 网页授权，不带／带 APK features |
| --- | --- | --- |
| `viewer.mobileAuthStatus` | `undefinedField` | `undefinedField` |
| `MobileDeviceKeyType` 类型 | `null` | `null` |
| 注册 mutation `addMobileDevicePublicKey` | `undefinedField` | `undefinedField` |
| 批准 mutation `approveMobileAuthDeviceRequest` | `undefinedField` | `undefinedField` |

注册／批准检查不仅查询 schema，也提交了刻意不满足输入类型和必填签名要求的编译校验请求：注册探针把公钥变量声明为 Int，批准探针使用非请求 ID 的字符串，均不提供密钥或签名。服务端在执行前返回 mutation 字段不存在；没有注册任何密钥、批准或拒绝请求。

这说明**本轮两种 MobileGH 自有 OAuth 上下文都不能通过已知协议完成独立数字批准**。不是因为用户没有完成验证，也不是“签名算法还没写好”。不能把该结果扩展成所有将来版本、所有未知入口或其他授权上下文永远不可实现。

还在用户本人已登录的网页会话做了只读补充检查：`/_graphql` 对普通 query 文档以及 APK 中实际的 MobileAuthRequests 操作 ID，均返回 HTTP 404／`unknownQuery`，没有返回批准状态。没有导出浏览器 Cookie；这仅证明这两个请求形状未在该网页端点取得结果。

[脱敏实测记录](MOBILE2FA_OAUTH_TEST_RESULTS.json)包含请求模式、实际 scope、字段错误与清理结果，不含 Token、client secret、OAuth code、验证码或 Cookie。

## 凭据清理

应用所有者 API 核对本次两个 Token 的账号、Client ID、创建时间和 ID 后，只定向撤销这两个测试 Token：`5690492034`、`5690732884`。两个 DELETE 请求均返回 204，随后这两个 Token 的 `/user` 请求均返回 401。没有撤销整个 MobileGH 应用授权、其他 Token 或组织访问批准；本次临时 Token、设备码和 PKCE 数据的本地文件已删除。

网页 OAuth 和定向撤销需要 MobileGH 自有 client secret，本轮在用户自己拥有的 MobileGH 应用生成了一个。GitHub 页面提示不能删除唯一的 client secret，因此将其保留在仓库之外的本地配置 `/Users/sheep/.config/mobilegh/oauth-owner.json`（目录 0700、文件 0600）。它没有写入源码、证据 JSON 或 APK。此前应用没有其他 client secret，没有替换已有密钥。

## 并行核验的委托路径

- [公开 IPC 检查](MOBILE2FA_PUBLIC_IPC_CHECK.md)：未找到向独立签名 MobileGH 提供数字提交、设备签名或批准结果的公开服务／provider／Binder 契约。
- [通知动作检查](MOBILE2FA_NOTIFICATION_ACTIONS.md)：认证通知没有批准 Action／RemoteInput；不可变 contentIntent 仅进入官方 UI。

这些委托路径没有满足当前目标，不据此伪造可用的原生批准按钮或发布新的功能完成声明。

资料：[GitHub OAuth 范围](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/scopes-for-oauth-apps)、[特权 OAuth 应用](https://docs.github.com/en/apps/oauth-apps/using-oauth-apps/privileged-oauth-apps)。后者说明第一方应用存在特殊权限，不能单凭该文档认定 Mobile2FA 字段的具体 gate。
