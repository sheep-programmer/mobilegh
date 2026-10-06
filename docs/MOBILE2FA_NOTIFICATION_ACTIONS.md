# Mobile2FA：官方通知 Action／RemoteInput 核验

核对日期：2026-10-06。目标是用户在 **MobileGH 自己的 UI 输入两位数**，通过官方 App 在真实通知中明确授予的操作，复用官方设备审批能力。

**本 APK 的认证通知构造路径没有提供应用定义的 `Notification.Action`，也没有数字输入 `RemoteInput` 或提交数字／批准的通知 PendingIntent。** 确实找到官方创建并放入通知的 `contentIntent`：它打开官方 `TwoFactorActivity`，使用不可变 PendingIntent。这个通知点击能力不能完成上述目标。

结论来自原 APK 的 DEX 指令与 builder 数据流；没有采集真实运行时通知、执行 PendingIntent 或进行审批。结论限定为本样本已核对的认证路径，不外推未来版本或服务器下发的其他实现。

## 样本与方法

- APK：`/Users/sheep/Downloads/GitHub.apk.1`；包名 `com.github.android`，版本 `1.275.0`，versionCode `946`，minSdk `32`，targetSdk `37`。
- 本轮重新计算 SHA-256：`7ba0e05047f4b7aa53b89ce2a062a3f8e51b9695e1e7070c8d697156d03b42c3`。
- 使用 `/opt/homebrew/Cellar/jadx/1.5.6/libexec/lib/jadx-1.5.6-all.jar` 中的 `com.android.tools.smali.dexlib2` 只读索引五个 DEX，再定向读取方法指令。没有全 APK 反编译或字符串池导出。
- 临时分析工具和过滤后的指令清单在 `/tmp/mobilegh-notification-actions/`。只输出通知／输入协议白名单字符串；其余字符串统一隐藏。没有读取登录态、Token、Cookie、设备私钥或官方 client secret。
- 参考但未修改 `/tmp/mobilegh-m2fa-scope-check/ScopeCheck.java`；前后 SHA-256 均为 `8e5a920b6e77798597c5ff95899f3f4d85972fd89c42e63ee7eb79c534926ef6`。也未读取该目录的设备授权结果文件。
- 下文 `@` 是方法内 DEX code-unit 偏移，非 APK 文件偏移。混淆类名保留样本原名；角色根据实际类型和调用确定。

## 两个名字的真实用途

| 名字 | 实际证据 | 意义 |
| --- | --- | --- |
| `mobile_device_auth` | `classes3.dex / com.github.android.pushnotifications.f.<clinit> @003c–0044`：ordinal `5` 的实例，字符串写入实例字段 `p`，实例存入静态字段 `u` | 推送类型；认证分支使用 `f.u` |
| `mobile_device_auth` | `f$a.a(String) @0018–001e` 比较各实例的 `p` 与输入；`PushNotificationsService.c @00de–0106` 读取推送 `type` 并调用该解析器 | 将实际推送类型连到认证分支，不依赖名字猜测 |
| `mobile_device_auth` | `x3w.b(f) @0004` switch 的 case `5` 到 `@0015` 返回该字符串；`k3w.i @00a4–00be` 在 feature flag 成立时创建同 ID 的 `NotificationChannel`，importance 为 `4` | 真实通知通道；通道本身不授予批准能力 |
| `PUSH_NOTIFICATION_MOBILE_AUTH_REQUEST` | `MobileSubjectType` enum；`TwoFactorActivity.onCreate @0037–0053` 将它与 `MobileAppElement.NOTIFICATION_PUSH`、`MobileAppAction.PRESS` 组成 `alg`，传给 `yv1.a`；`DeepLinkActivity.Z @00fd`、`@011d–0129` 同样组成事件 | 在这些调用点是通知点击事件的 subject；没有构造成 Android Intent action 或通知按钮 |

本轮只检查上述 Activity 的通知事件引用和通知 Intent 工厂，没有重复 launcher 或组件导出性核验。

## 认证通知完整构造链

除特别注明，以下方法均在 `classes3.dex`。

| 方法／偏移 | 实际行为 | 对 MobileGH 的结果 |
| --- | --- | --- |
| `PushNotificationsService.c @010b–0117` | 比较推送类型与 `f.u`，启动内部 coroutine `g` | 内部推送处理；没有向第三方授予输入或批准句柄 |
| `c @0239–0242` | 取 enum ordinal；`5` 跳到 `@0277` | 明确认定认证专属 PendingIntent 分支 |
| `c @0277–027f` | 调用 `w1h.d()`；为假时 `@029f` 产生 null contentIntent | 即使收到这种推送，点击句柄也受该条件控制 |
| `c @0286–0290` | 调用 `TwoFactorActivity$a.a(context, true, null)` 获取 Intent | 目标是打开官方 2FA UI；没有传入两位数字 |
| `c @0291–0293` | `PendingIntent.getActivity(context, notificationId, intent, 0x14000000)` | 官方创建的 Activity PendingIntent；标志包含不可变性 |
| `c @02c0` | 调用 `x3w.b(f)` 选择通道 | 对 `f.u` 得到 `mobile_device_auth` |
| `c @02fa–031c` | 当有 group 值时，认证类型在 `@02fc–02ff` 将 summary builder 置 null | 不会从普通通知分组路径得到额外 summary 动作 |
| `c @031d–0321` | `x3w.a(f.u)` 为 false，跳到 `@06b4` | **绕过 `@0323–06b3` 的 Live Updates 按钮构造代码** |
| `c @06c1–06e2` | 创建 `hyq.a(...)` builder，填 title、text、BigTextStyle、group；`@06e0` 把上述 PendingIntent 写入 `zxq.g` | 只设置通知内容点击；没有添加 Action 或 RemoteInput |
| `c @0701–071e` | 图片处理 coroutine 分支只针对 `f.q`；认证类型直接调用 `e(...)` | 认证发布前没有该异步改造步骤 |
| `PushNotificationsService.e @0005–0009` | `zxq.a()` 构造 Notification，再交给 `m0r.a` | 把前面构造的数据发布出去 |
| `classes.dex / m0r.a @0044–0046` | 普通发布分支把同一 Notification 交给 `NotificationManager.notify(null, id, notification)` | 发布 wrapper 没有添加审批动作 |

`x3w.a @0000–000c` 只对 `f.v`、`f.w` 返回 true。`f.u` 是另一个 enum 实例，因此认证类型无法进入 Live Updates 按钮路径。这个分支判断是“没有认证动作”的关键证据。

## 通用 builder 确认：不是漏掉混淆后的 addAction

认证分支调用的 `hyq.a @0000–001b` 只新建 `zxq`、设置图标、auto-cancel、颜色和 priority；没有预装按钮。

`classes4.dex / zxq.<init> @0003–0016` 将 `b`、`c`、`d` 初始化为空 ArrayList。通过下游调用可以确认 `b` 是普通通知动作列表：

| 下游证据 | 实际含义 |
| --- | --- |
| `classes.dex / f030.<init>(zxq) @0098–009a` | 把 `zxq.g` 传给平台 `Notification.Builder.setContentIntent` |
| 同方法 `@00d5–00de` | 读取 `zxq.b` 的 size 并决定是否遍历动作；认证 builder 的列表为空，跳过整个动作循环 |
| 同方法 `@0110–0115`、`@016f–0177` | 对列表中的 `txq` 创建平台 `Notification.Action.Builder` 并调用 `Notification.Builder.addAction`；认证路径没有这样的列表元素 |
| 同方法 `@0115–0127` | 仅在动作的 `txq.c` 输入数组非 null 时处理 `RemoteInput`／`addRemoteInput`；认证路径连 Action 都没有创建 |
| `classes4.dex / zxq.a @0000–0014` | 调用上述转换器、应用 style、最终调用平台 builder 的 `build()` |
| `classes4.dex / xxq.k @002b–003f` | 认证使用的 style 分支仅设置 `Notification.BigTextStyle`，没有添加按钮或输入 |

认证构造路径也没有给 `zxq.c` 的兼容扩展动作列表填入元素，或设置数字输入的自定义 RemoteViews、deleteIntent、fullScreenIntent。通用转换器中存在这些字段／API 不代表认证通知使用了它们。

五个 DEX 的平台 `Notification.Builder.addAction` 和 `Notification.Action.Builder.addRemoteInput` 引用均定位到上述 `f030.<init>(zxq)` 通用方法。**包内出现 RemoteInput 类、`android.support.allowGeneratedReplies` 或 reply 相关库符号，不能据此报告 2FA 有输入动作。** 本轮没有把任何聊天 reply 当成认证能力。

## 真实按钮属于其他通知分支

在 `PushNotificationsService.c` 中确实找到 `txq`（兼容 Action）实例，并非笼统声称“APK 没有通知按钮”：

- `@0429–0430`：构造并加入 Live Updates 的 Activity 按钮；`@0410` 将其 RemoteInput 参数置 null。
- `@046d–048e`：为 `DisableLiveUpdatesReceiver` 构造 `com.github.android.action.DISABLE_LIVE_UPDATES` 的 broadcast PendingIntent；`@04de–04e3` 构造并加入相应按钮；`@04c5` 将 RemoteInput 参数置 null。
- 两者均在 `@0321` 对认证类型跳过的区域内，不接受认证数字，也不是登录批准操作。

另外核对 `dtq.j` 中另一个调用 `x3w.b`／`hyq.a` 的本地通知分支：`@041b–0427` 只选择 `f.x/t/s/q/r`，不选择认证 `f.u`；`@049d–04d6` 设置 contentIntent 后发布。不能把仓库通知的 `APPROVAL_REQUESTED` 判断或跳转当成 Mobile2FA 通知动作。

## PendingIntent 授权范围与数字提交

认证通知的 `0x14000000` 为：

```text
0x10000000  FLAG_CANCEL_CURRENT
0x04000000  FLAG_IMMUTABLE
```

本机 Android SDK `sources/android-36.1/android/app/PendingIntent.java` 的 `@240`／`@269` 行定义上述常量；`259–268`、`911–919` 行说明不可变 PendingIntent 会忽略调用者 `send(...)` 的补充 Intent。这里的 SDK 行号是源文件行号，与前面的 DEX 偏移不同。

`TwoFactorActivity$a.a @0000–0004` 创建显式 Activity Intent；通知调用的第三个参数为 null，因此 `@0007` 跳到 `@002b`，跳过请求对象／账号 extras 区域，仅添加 Activity flag 后返回。这个通知 Intent 没有携带来自第三方 UI 的数字、批准指令或审批结果 callback。

若用户允许的系统通知访问机制把**真实官方通知**及其仍有效的 PendingIntent 交给 MobileGH，获得的能力应按官方原有操作理解。本样本的这个句柄等同于点击通知、进入官方 2FA UI。`contentIntent` 与 `Notification.actions[i].actionIntent` 是不同字段；样本没有后者对应的认证批准动作。不可变句柄也不提供额外填入数字或 RemoteInput 结果的契约。

`PendingIntent.OnFinished` 表示 PendingIntent 发送完成，不是服务端 2FA 批准结果；本路径没有另行授予批准结果协议。以上框架语义已用本机 SDK 的 `PendingIntent.java` 和 `Notification.java` 核对，不依赖未验证的网上示例。

本轮没有获取或调用任何通知句柄，没有构造、反射、修改私有 PendingIntent，也没有调用非导出组件。公开授予的 PendingIntent 是否有效，应按真实授权对象判断；本结论的限制在于它没有提供所需的输入／批准操作。

## 对实现目标的回答

| 用户需要的能力 | 本样本认证通知结果 |
| --- | --- |
| 在 MobileGH 自己的 UI 输入两位数，经 RemoteInput 交给官方 | **没有认证 Action／RemoteInput；无 resultKey 或输入提交契约** |
| 经官方公开通知动作复用设备批准能力 | **没有认证批准／拒绝 Action 或相应 actionIntent** |
| 执行真实官方通知的 contentIntent | 有条件存在；只打开官方 2FA UI，不满足本任务 |
| 在点击句柄上附加数字／批准 extras | 不可变 PendingIntent 忽略补充 Intent；没有官方授予的输入协议 |
| 获取服务器批准结果 | 没有通知授予的结果 callback；本轮没有运行时批准测试 |

因此，这条通知委托路径在所检查 APK 中没有形成可供 MobileGH 实现的两位数字输入／批准协议。后续版本若由官方真实通知明确提供认证专属 Action、RemoteInput 和 actionIntent，应重新核验具体授权对象、输入字段与接收实现。

本轮仅新增本文档；未修改 App 或已有报告，未构建 App、提交或发布。
