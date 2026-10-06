# Mobile2FA：官方 APK 的公开 IPC 检查

核对日期：2026-10-06。目标限定为：用户在 **MobileGH 自己的界面输入两位数字**，通过官方 App 合法公开的 IPC 取得请求、委托批准或获得签名／审批结果。

**本样本没有找到满足这个目标的公开 IPC 契约。** 完整清单中的 service／provider 没有可供普通独立签名第三方 App 直接调用的审批入口；定向 DEX 检查也没有确认数字输入、委托签名、批准或审批结果 callback 的公开协议。存在的系统组件、临时文件共享和 SDK 动态广播，具体证据如下。

本结论限定为所检查 APK 的声明与下述静态实现，不推断未来版本、其他产品或未检查的运行时实现。已有 launcher、普通深链、内部审批 Activity 和 OAuth／PAT 的 GraphQL 观察不作为本轮新证据，也不作为满足本目标的实现方案。

## 样本与可复核依据

- APK：`/Users/sheep/Downloads/GitHub.apk.1`；包名 `com.github.android`，版本 `1.275.0`，versionCode `946`，minSdk `32`，targetSdk `37`。
- 本轮重新计算 SHA-256：`7ba0e05047f4b7aa53b89ce2a062a3f8e51b9695e1e7070c8d697156d03b42c3`。
- 使用 SDK `35.0.0/aapt dump xmltree` 直接读取 APK 的 `AndroidManifest.xml`，**输出与已有 `/tmp/mobilegh-official-manifest.txt` 逐字一致**。下表行号均指该文本文件；不把不同工具的格式差异当作清单差异。
- 直接读取 `res/xml/authenticator.xml`、`res/xml/ahp_attachment_paths.xml`；使用本机 JADX 随附 dexlib2，在内存中读取五个 DEX 的类／方法索引及所选指令。未做全 APK 反编译或导出字符串池。
- `@` 为方法内 DEX code-unit 偏移。静态索引发现 37 个 Binder 派生类，以及 22 处平台 `registerReceiver` 调用引用；后者包括 SDK 版本分支和仅查询 sticky broadcast 的空 receiver，并不代表 22 个运行中的公开接口。

## 完整 service 清单：2 个显式导出，均有系统权限门槛

| service | 清单文本行 | exported／权限 | 对第三方审批的意义 |
| --- | --- | --- | --- |
| `com.github.service.auth.AuthenticatorService` | 809–817 | `false`；AccountAuthenticator filter | 无法由 MobileGH 直接绑定；认证方法另见下文 |
| `com.github.android.pushnotifications.PushNotificationsService` | 818–823 | `false`；`com.google.firebase.MESSAGING_EVENT` | 官方推送处理内部入口 |
| `androidx.appcompat.app.AppLocalesMetadataHolderService` | 830–836 | `false`，`enabled=false` | 本地化元数据 |
| `com.google.firebase.messaging.FirebaseMessagingService` | 904–911 | `false` | Firebase 内部消息处理 |
| `com.google.firebase.components.ComponentDiscoveryService` | 912–948 | `false` | Firebase 组件发现 |
| `com.google.android.gms.measurement.AppMeasurementService` | 953–956 | `false` | 测量 SDK |
| `com.google.android.gms.measurement.AppMeasurementJobService` | 957–961 | `false`；`BIND_JOB_SERVICE` | 测量任务 |
| `com.google.firebase.sessions.SessionLifecycleService` | 966–969 | `false`，`enabled=false` | Firebase 生命周期 |
| `androidx.glance.appwidget.GlanceRemoteViewsService` | 1005–1008 | **`true`**；`android.permission.BIND_REMOTEVIEWS` | 系统 widget 视图工厂，非批准服务 |
| `androidx.work.impl.background.systemjob.SystemJobService` | 1009–1014 | **`true`**；`android.permission.BIND_JOB_SERVICE` | 系统 JobScheduler 入口，非批准服务 |
| `androidx.work.impl.foreground.SystemForegroundService` | 1015–1019 | `false` | WorkManager 前台任务 |
| `androidx.core.widget.RemoteViewsCompatService` | 1045–1047 | 未写 exported，**没有 intent-filter，默认 `false`**；`BIND_REMOTEVIEWS` | 不算第三个公开导出的 service |
| `androidx.room.MultiInstanceInvalidationService` | 1054–1057 | `false` | 有真实 Binder，但仅数据库失效通知 |
| `com.google.android.datatransport.runtime.backends.TransportBackendDiscovery` | 1079–1084 | `false` | 传输 SDK 发现 |
| `com.google.android.datatransport.runtime.scheduling.jobscheduling.JobInfoSchedulerService` | 1085–1088 | `false`；`BIND_JOB_SERVICE` | SDK 任务调度 |
| `es.voghdev.pdfviewpager.library.service.CopyAssetService` | 1092–1094 | `false` | PDF 素材复制 |

总计 16 个：13 个显式 `false`、1 个默认 `false`、2 个显式 `true`。不存在遗漏的、无权限限制的公开 service。

Android 的 [service 声明规则](https://developer.android.com/guide/topics/manifest/service-element) 明确区分导出状态和调用权限；无 filter 时默认不导出。`BIND_REMOTEVIEWS` 是 `signature|privileged` 权限，供系统绑定 widget 服务；`BIND_JOB_SERVICE` 在 [AOSP 权限声明](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-qpr2-release/core/res/AndroidManifest.xml) 中为 `signature`。普通 MobileGH 不能靠在自己的清单添加 `<uses-permission>` 获得这些绑定资格。[RemoteViews 权限说明](https://developer.android.com/reference/android/Manifest.permission#BIND_REMOTEVIEWS)、[JobService 契约](https://developer.android.com/reference/android/app/job/JobService#PERMISSION_BIND)

DEX 的 `classes.dex / GlanceRemoteViewsService.onGetViewFactory` 实际读取 view ID、size 信息并返回 `RemoteViewsFactory`；`SystemJobService` 的父类是 `android.app.job.JobService`，实现 `onStartJob`／`onStopJob`。这些接口类型不提供批准数字或签名结果参数。

## AccountAuthenticator：有框架 Binder，没有审批实现

`res/xml/authenticator.xml` 只声明 `accountType="com.github.android"`、图标与标签；未声明 Mobile2FA 能力或自定义审批参数。清单的 `android.accounts.AccountAuthenticator` action／metadata 不能覆盖 `exported=false`。

本轮对原 APK 独立复核了用户分析文档所述空实现，补充其可达性和实际指令证据：

| DEX 证据，均在 `classes3.dex` | 实际行为 |
| --- | --- |
| `AuthenticatorService.onCreate @0000–0005` | 创建 `o85`，存入字段 `p` |
| `AuthenticatorService.onBind @0003–000b` | 读取 `p`，调用框架 `AbstractAccountAuthenticator.getIBinder()`，返回该 Binder |
| `o85` 类声明 | 父类为 `android.accounts.AbstractAccountAuthenticator` |
| `o85.addAccount`、`confirmCredentials`、`editProperties`、`getAuthToken`、`hasFeatures`、`updateCredentials`，各 `@0000–0005` | 只有 `new Bundle`、无参构造、返回；没有读取入参或填充结果 |
| `o85.getAuthTokenLabel @0000–0002` | 返回空字符串 |

这不是委托签名接口：上述方法没有调用审批实现或签名实现，也没有写入请求 ID、数字、签名、审批状态或 callback。系统 AccountManager 的合法中转与第三方直接绑定是不同路径；即使系统中转到这些方法，这些实现仍只返回空结果。本轮没有调用 AccountManager 或读取任何账号凭据。

## ContentProvider：全部不导出；唯一 URI 授权用于附件

| provider／authority | 清单文本行 | 声明与实现 |
| --- | --- | --- |
| `AHPAttachmentFileProvider`／`com.github.android.ahp-attachments` | 101–108 | `exported=false`、`grantUriPermissions=true`；继承 `gdi`，自身仅声明构造函数 |
| `androidx.startup.InitializationProvider`／`com.github.android.androidx-startup` | 843–861 | `exported=false`；组件初始化 |
| `FirebaseInitProvider`／`com.github.android.firebaseinitprovider` | 970–975 | `exported=false`；Firebase 初始化 |

三个 provider 均直接或经 `gdi` 继承 `ContentProvider`，没有在这些继承链中实现自定义 `call(...)`。未找到 `ContentResolver.call(method, arg, extras)` 形式的公开审批协议。

附件 provider 的新证据：

- `classes.dex / gdi.attachInfo @0003–0005` 检查 `ProviderInfo.exported`；为真跳到 `@0048` 构造并抛出 `SecurityException`。`@0007–0009` 检查 URI 授权开关，未开启也抛出异常。
- APK 的 `res/xml/ahp_attachment_paths.xml` 仅有 `<cache-path name="ahp_camera_attachments" path="ahp_camera_attachments/" />`。
- `gdi.openFile @0052` 调用 `ParcelFileDescriptor.open(File, int)`；其查询／MIME／文件操作是文件共享接口，没有审批 Bundle 或签名回传方法。

**可以描述的条件式跨应用协议仅为附件 URI：** `content://com.github.android.ahp-attachments/ahp_camera_attachments/<编码后的相对文件路径>`。官方 App 必须先明确授予具体 URI 的访问权限，接收方才能按授权模式使用 `ContentResolver.openFileDescriptor` 等文件 API。URI 形状本身不会授予访问权；本轮未获得或请求任何 URI 授权，也未读取附件。`grantUriPermissions=true` 不表示存在公开签名服务。[FileProvider 的 URI 与临时授权契约](https://developer.android.com/reference/androidx/core/content/FileProvider)

## Bound service／AIDL：真实 IPC 的用途与门槛

| 新证据 | 可确认的接口 | 审批能力／调用条件 |
| --- | --- | --- |
| `classes.dex / MultiInstanceInvalidationService.onBind @0003–0005` 返回 `xkq`；`icl.<clinit> @0004–000a` 构造 descriptor | `androidx.room.IMultiInstanceInvalidationService` | service 不导出；数据库通知，不可由 MobileGH 直接绑定 |
| `classes.dex / xkq.onTransact` | callback 注册／注销，以及 `int + String[]` 的失效广播；操作字段为数据库名／表名 | 没有批准请求或签名结果协议 |
| `classes4.dex / kec0.a(lec0) @0000–0008` 比较 `Binder.getCallingUid()` 与 `Process.myUid()`；不相等转到 `@003b–0042` 抛 `SecurityException` | Firebase 消息 Binder；成功分支交给 `FirebaseMessagingService` executor | 明确的同 UID 门槛，不能合法作为第三方批准通道 |
| `bcl`／`ecl` 静态 descriptor；`llcl` 静态 descriptor | `android.support.customtabs.ICustomTabsCallback`／`ICustomTabsService`、`android.support.v4.app.INotificationSideChannel` | 库中的接口类不等于该 APK 导出了相应 service；完整 service 清单没有这种公开入口 |
| Binder 派生类中的 descriptor | GMS 测量、Google Play 更新／评论、Billing、配置等 SDK 接口与 callback | 未确认 GitHub Mobile2FA Binder descriptor 或可达的批准服务 |

Binder 类存在、`onBind` 返回非空，均不足以证明普通第三方能取得 Binder；还必须满足组件导出与权限／UID 检查。本轮没有发送 Binder transaction、绑定组件或绕过这些门槛。

## 广播：静态导出均受权限保护；动态 SDK 广播确实存在

完整清单有 12 个 receiver：9 个不导出，3 个导出。全部静态导出的 receiver 如下：

| receiver | 清单文本行 | action／权限 | 结论 |
| --- | --- | --- | --- |
| `FirebaseInstanceIdReceiver` | 894–903 | `com.google.android.c2dm.intent.RECEIVE`、`com.google.android.gms.cloudmessaging.FINISHED_AFTER_HANDLED`；要求 `com.google.android.c2dm.permission.SEND` | Google 推送入口；不是向第三方开放的批准服务 |
| `androidx.work.impl.diagnostics.DiagnosticsReceiver` | 1033–1041 | `androidx.work.diagnostics.REQUEST_DIAGNOSTICS`；要求 `android.permission.DUMP` | WorkManager 诊断 |
| `androidx.profileinstaller.ProfileInstallReceiver` | 1061–1078 | `INSTALL_PROFILE`、`SKIP_FILE`、`SAVE_PROFILE`、`BENCHMARK_OPERATION`，前缀 `androidx.profileinstaller.action.`；要求 `DUMP` | profile 安装／诊断 |

`DUMP` 官方说明不供第三方普通应用使用；AOSP 声明为 `signature|privileged|development`。GCM 的官方历史契约也明确要求 receiver 使用 SEND 权限以限制发送者为 GCM 框架；本 APK 仍保留这个权限门槛。[DUMP 权限说明](https://developer.android.com/reference/android/Manifest.permission#DUMP)、[GCM receiver 契约](https://android.googlesource.com/platform/frameworks/base/+/f094eefc34013c7167effa558b488709eaa54751/docs/html/google/gcm/gs.jd)

定向检查 22 处平台注册引用及其 filter 来源，得到这些类别：

| 方法／DEX | 已确认的 filter 来源或 action |
| --- | --- |
| `b60.u`／`bm4.l`，`classes.dex` | `TIME_SET`、`TIMEZONE_CHANGED`、`TIME_TICK`、`POWER_SAVE_MODE_CHANGED` 等系统时间／节电通知 |
| `lx5.a/c/e`，`classes.dex` | 电池、充电、存储状态；其中查询初始状态的注册 receiver 为 null |
| `ig4.D @0035` → `zcl.<clinit>`，`classes3/4.dex` | `DEVICE_IDLE_MODE_CHANGED`、`LIGHT_DEVICE_IDLE_MODE_CHANGED`、`LOW_POWER_STANDBY_ENABLED_CHANGED` |
| `qwb.b @000f`，`classes3.dex` | `BATTERY_CHANGED`，receiver 为 null，查询初始状态 |
| `yyi.e @0028`、`xnd0.V @0034`，`classes3/4.dex` | `USER_UNLOCKED` |
| `abd0.<init> @000b`／`abd0.a`，`classes4.dex` | `com.google.android.play.core.install.ACTION_INSTALL_STATUS` |
| `ksa.a @0061/@0073`，`classes4.dex` | `com.google.android.gms.phenotype.UPDATE` |
| `n1k.a @03f6/@03fb/@0409` → `p4p.M`，`classes4/3.dex` | `com.google.android.gms.measurement.TRIGGERS_AVAILABLE`、`BATCHES_AVAILABLE` |
| `py5.k` → `d2d0.a`，`classes4.dex` | Play Billing 的 `PURCHASES_UPDATED`、`LOCAL_BROADCAST_PURCHASES_UPDATED`、`ALTERNATIVE_BILLING`；部分注册要求 `PLAY_BILLING_LIBRARY_BROADCAST` 权限 |
| `q560.a`、`qsd0.N`、`sd70.run`，`classes4.dex` | `android.net.conn.CONNECTIVITY_CHANGE` |

这里有**真正使用导出标志的动态 SDK 广播**：例如 `p4p.M @0008` 在 API 33+ 设置 flag `2`（`RECEIVER_EXPORTED`），权限参数为 null，调用发生在 `@000c`；实际调用者 `n1k.a` 提供的是上表两个测量 action。`ksa.a` 的 phenotype UPDATE 注册也设置 flag `2`。不能报告“这个 APK 的所有动态 receiver 都不导出”。

但在这些已核对 filter／receiver 路径中，未确认 Mobile2FA action、数字入参、批准处理或审批结果回传。广播注册能接收 SDK 事件不构成受支持的第三方审批协议，本轮未向这些 receiver 发送广播。

清单 78–82 行另声明 `com.github.android.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`，protectionLevel `0x2`（signature）。这只是该自定义权限的证据，**不能据此宣称它保护了所有动态注册**；上面的 null-permission 导出注册就是反例。

## 对 MobileGH 可调用协议的回答

| MobileGH 所需操作 | 本轮公开 IPC 结果 |
| --- | --- |
| 获取待批准登录请求／挑战 | 未确认公开 service、provider method、广播请求／响应或 Binder 方法 |
| 提交两位数字并由官方 App 批准 | 未确认合法可达的公开 IPC 契约 |
| 委托官方设备密钥签名 | 未确认公开签名接口；AccountAuthenticator 为空，消息 Binder 有同 UID 检查 |
| 取得签名／批准结果 callback | 未确认公开 callback action、结果 Bundle 或可取得的 Binder |

因此本轮没有可交给 MobileGH 实现的批准 Intent、`content://` 审批 URI、AIDL descriptor／transaction 或 callback 格式。存在的附件 URI 协议只在官方明确授予文件访问权时可用，不能承担上表任何操作。

本轮仅新增本文件。未修改 MobileGH 代码或已有报告，未读取现有 Token／Cookie／设备私钥、复制官方 client secret、调用非导出组件、发起注册／批准 mutation、构建、提交或发布。检查为静态只读分析，没有把运行时未测试标记为调用成功。
