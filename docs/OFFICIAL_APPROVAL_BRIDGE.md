# 官方 GitHub Mobile 审批桥接

核对日期：2026-10-06。目标是保留并打开官方 App，由用户完成两位数字确认，继续 MobileGH 的正常 OAuth；本轮只读检查清单、相关 DEX 路由方法和官方文档，仅新增本文档，未修改应用代码、构建 MobileGH／官方 APK、修改 APK、启动设备组件或自动审批。

## 可直接用于实现的结论

**采用系统 launcher 打开包 `com.github.android`。** 样本的前台启动组件是导出的 `com.github.android.main.MainActivity`，具有 `MAIN`／`LAUNCHER` intent-filter。实现优先使用 `PackageManager.getLaunchIntentForPackage("com.github.android")`，而非绑定某个内部审批类。

GitHub 官方说明：安装并登录 GitHub Mobile 后，点击认证推送或打开官方 App 会显示批准／拒绝提示；提示可能要求输入浏览器显示的两位数字，用户批准后浏览器继续登录。这正是复用官方 App 所需的支持路径。[GitHub Mobile 登录验证][mobile2fa]

**本次没有确认可直接打开审批页的公开深链 URI。** `github:` 接收器是导出的，但其代码将 scheme 改成 `https` 后交给普通 URL 路由；不能把宽泛 scheme 注册理解为任意审批路径都可用。应诚实提供“打开官方 GitHub App”及“点击官方认证通知”的入口，不承诺直达特定请求。

## 清单证据

输入 APK：`/Users/sheep/Downloads/GitHub.apk.1`；前轮核对为 `com.github.android`、版本 `1.275.0`、versionCode `946`。SHA-256：`7ba0e05047f4b7aa53b89ce2a062a3f8e51b9695e1e7070c8d697156d03b42c3`。本轮读取父任务导出的 `/tmp/mobilegh-official-manifest.txt`；没有修改它。

下列行号是该文本文件的行号，不是 XML 原始行号。

| 组件 | 导出／过滤器证据 | 对桥接的意义 |
| --- | --- | --- |
| `com.github.android.main.MainActivity` | 109–119 行；`exported=true`，`MAIN`／`LAUNCHER` | 官方 App 的公开启动入口，可供系统 launcher 打开 |
| `com.github.android.activities.DeepLinkActivity` | 164–177 行；`exported=true`，`VIEW`、`DEFAULT`、`BROWSABLE`，scheme 为 `github` | 公开 custom-scheme 接收器；具体路由仍由代码决定 |
| `com.github.android.DeepLinkAliasActivityApi35` | 178 行起；导出，样本初始 `enabled=false`；目标为 DeepLinkActivity | HTTPS App Links 别名；实际启用状态取决于运行时配置，不能仅凭静态清单保证其可解析 |
| `com.github.android.DeepLinkAliasActivity` | 337 行起；导出，样本初始 `enabled=false`；目标为 DeepLinkActivity | 同上；过滤规则包含对登录／认证路径的排除逻辑 |
| `net.openid.appauth.RedirectUriReceiverActivity` | 127–142 行；导出，`github` + `com.github.android` + `/oauth` | `github://com.github.android/oauth` 是官方 OAuth 回调，不是审批桥接 URI |
| `com.github.android.agents.ahp.auth.AHPGitHubAppAuthorizationCallbackActivity` | 147–163 行；导出，`github://com.github.android/appauth` | App 授权回调，不是批准／拒绝界面 |
| `com.github.android.activities.ShareImageActivity` | 776 行起；导出，分享过滤器 | 与认证审批无关 |
| `com.github.android.twofactor.TwoFactorActivity` | 793–795 行；**没有 `exported` 属性，也没有 intent-filter** | 不是跨应用可直接启动的审批组件 |

Android 官方文档说明，没有 intent-filter 时 activity 的 `exported` 默认值为 false。因此这里不是“属性未写所以未知”，也不是声称清单显式写了 `exported=false`。[Activity exported 规则][activity]

## custom-scheme dispatcher 的实际行为

复用前轮选择性 DEX 读取工具，仅解析相关方法指令及路由常量；没有进行全 APK JADX 反编译。下文 `@` 是方法内 DEX code-unit 偏移。

### `DeepLinkActivity.Z(Intent)`，`classes3.dex`

- `@0012–002f`：优先读取 URL extra，否则读取 `Intent.getData()`。
- `@0047–006a`：只继续处理 HTTPS 或不区分大小写的 `github` scheme，其余结束 activity。
- `@0097–00bc`：将 `github` scheme 通过 `Uri.buildUpon().scheme("https")` 改成 HTTPS；host 和路径沿用原 URI。
- `@0133–0148`：交给 `com.github.android.a.d(...)` 的普通 URL 分发。
- 这里的 `PUSH_NOTIFICATION_MOBILE_AUTH_REQUEST` 分支属于推送事件分类／统计，不等于存在批准 UI 的公开 URI 路由。

因此 `github://github.com/某路径` 的效果是把该路径作为普通 GitHub HTTPS URL 处理。`github://two-factor` 等猜测会把 `two-factor` 当作 host，并没有被确认的审批语义。本文不推荐构造这些 URI。

### `com.github.android.a` URL router，`classes.dex`

检查 `d`／`i` 及委托的 `e`／`f`／`g`／`h`：

- `d @00da–0101` 根据 `uu80` 的首段路径集合处理特殊网页路径；该集合包含 `auth`、`login`、`oauth`、`password_reset`、`session`、`sessions`。
- `i @001b` 调用外部 URL 处理；随后对 `session`、`sessions`、`login`、`auth`、`oauth` 等进行认证深链分类，并生成 `InvalidAuthDeepLinkException` 等诊断。
- 所检查的 dispatcher、router 和上述四个 helper 没有引用 `TwoFactorActivity`／`TwoFactorDialog`；未定位到把某个公开 URI 转换为审批 activity 的分支。
- 有限范围的路由／URI 常量检查找到明确的 `/oauth`、`/appauth` 回调，没有找到经过确认的专用审批 URI。

这支持“本轮未确认直达审批深链”，不证明所有其他版本或尚未检查的路径绝对没有此能力。GitHub 当前公开 Mobile 文档说明了打开 App／推送的审批方式，没有在所读资料中提供 Android 审批 URI。[GitHub Mobile 文档][mobile]、[登录验证][mobile2fa]

### 官方内部审批 intent

`TwoFactorActivity$a.a(...)` 构造指向 `TwoFactorActivity` 的内部显式 intent，可携带 `key_auth_request` 的 Parcelable、`key_auth_user` 和 `key_from_push_notification`。这说明官方内部可以携带已经取得的请求状态；**这些 extra 不是公开的跨应用调用协议**。本桥接不制造该 Parcelable，不通过 root 调用非导出组件，也不重放内部推送 payload。

## 父任务的 companion-launcher 流程

1. 用户在 MobileGH 发起正常 OAuth 设备流程；系统浏览器完成 GitHub 登录及授权步骤。
2. 当用户选择 GitHub Mobile 验证、浏览器显示两位数字时，用户主动点击“打开官方 GitHub App”，或直接打开官方认证推送。
3. MobileGH 用系统包启动入口打开 `com.github.android`。不要传密码、Token、请求签名、审批请求对象或两位数字；用户自行确认官方 App 显示的账号和请求。
4. 用户在官方 App 输入浏览器的数字并选择批准／拒绝。若没有看到请求，确认官方 App 登录了对应账号，或从官方认证通知打开；不要把能启动 App 等同于请求已经存在。
5. 用户返回原系统浏览器继续完成 OAuth 授权。MobileGH 恢复原设备流程的轮询，遵守原到期时间和轮询间隔；只有 GitHub 返回的真实授权成功才能建立应用会话。

返回 MobileGH 或成功打开官方 App 都不是“已经批准”的证据；此桥接没有官方审批结果 callback。浏览器的两位数字也不同于 MobileGH 的 OAuth 设备 `user_code` 和 TOTP 验证码。

包启动方式的说明性片段如下；本文没有把它写入任何代码文件：

```kotlin
val launch = context.packageManager
    .getLaunchIntentForPackage("com.github.android")
if (launch != null) {
    context.startActivity(launch)
} else {
    // 显示未安装／当前不可解析，并提供官方安装入口。
}
```

从非 Activity context 调用时，父任务应添加正常的 `FLAG_ACTIVITY_NEW_TASK` 并处理启动失败。为准确检测已安装的官方包，Android 11+ 的包可见性可声明特定包查询，而非申请查询全部应用：

```xml
<queries>
    <package android:name="com.github.android" />
</queries>
```

Android 官方 API 将 `getLaunchIntentForPackage` 定义为获取包的前台启动入口；特定包可见性声明是正常跨应用集成机制。[PackageManager API][pm]、[包可见性][visibility]

安装入口可使用 GitHub 文档链接的 [官方 Mobile 页面](https://github.com/mobile)。用户须先完成官方 App 自己的登录及 GitHub Mobile 2FA 配置；本轮不代办这些账号操作。[Mobile 安装说明][mobile]

## 验证范围与交付

已核对导出标志、launcher 过滤器、custom-scheme dispatcher、URL router 和官方支持说明。未运行真实登录／审批流程，也未在模拟器中启动任何组件；因此没有端到端成功、账号状态或安装设备结果的声明。

当前可交付的支持方案是 **官方包 launcher + 官方推送 + 用户手动审批 + 原浏览器 OAuth 继续**。专用审批深链仍未确认；没有必要因此重实现官方设备注册或复制官方身份／密钥。

[mobile2fa]: https://docs.github.com/en/authentication/securing-your-account-with-two-factor-authentication-2fa/accessing-github-using-two-factor-authentication#verifying-with-github-mobile
[mobile]: https://docs.github.com/en/get-started/using-github/github-mobile
[activity]: https://developer.android.com/guide/topics/manifest/activity-element?hl=en#exported
[pm]: https://developer.android.com/reference/android/content/pm/PackageManager?hl=en#getLaunchIntentForPackage(java.lang.String)
[visibility]: https://developer.android.com/training/package-visibility/declaring?hl=en

## 主代理运行验证

签名 v0.21 已在独立 Android 36 模拟器安装。与用户提供的 GitHub 1.275.0 同机安装后，原生界面显示“已安装”。从 MobileGH 点“打开 GitHub Mobile”，应用日志记录启动，系统活动栈出现 com.github.android/.auth.SimplifiedLoginActivity，进程属于 com.github.android。没有从本机发起或批准真实账号的 2FA 请求，因此验证的是跨应用启动及安装检测，不宣称完整两位数字审批已在设备上跑通。

## v0.22 授权衔接检查

授权请求改为由导航条目保留，并在条目销毁或用户取消时停止。系统因主题等配置变化重建 Activity 时，当前请求和原始截止时间保留；进程被系统终止后的恢复仍需要重新开始授权，不把设备码或 Token 放进 Android 保存状态。网络错误采用退避重试，连续超时继续降低轮询频率；GitHub 的 slow_down 永久增加该请求的最小轮询间隔。过期、拒绝和无效凭据不重试。服务端签发 Token 后仅重试账号核验，不再轮询已兑换的设备码。

签名 v0.22 在 Android 36.1 模拟器以 360dp 宽度覆盖安装。真实设备授权请求取得后切换深浅主题，界面显示同一个 user_code。打开官方 App 后返回，页面提供“继续浏览器授权”；该按钮实际启动 Chrome。Chrome 初次启动出现 FirstRunActivity，待其初始化后重新切回 MobileGH，仍显示同一个请求及继续倒计时。取消请求后回到登录方式选择页。该检查没有登录官方 App，没有批准任何两位数字请求，没有新增账号会话；仅证明状态保留、跨应用跳转与取消行为。

101 项单元测试通过，覆盖断网恢复、连续超时、服务端降速、原期限到期、拒绝、取消竞态、重复点击、账号核验重试及设备授权期限／地址校验。Release 构建和 lint（0 errors）通过；四个 ABI 签名包证书与 v0.21 一致。
