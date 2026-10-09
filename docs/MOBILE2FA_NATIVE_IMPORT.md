# 自动注册、全局数字批准与系统通知

当前入口已取消设备文件导入。首次打开申请通知权限及后台运行授权；默认 GitHub 登录完成后，App 自动注册本机、
生成 Android Keystore P-256 私钥并开启监听。前台任意页面收到请求时弹出数字确认框，
后台发系统通知，点击通知进入对应请求。设置 → 两步验证提供监听开关与系统通知设置入口。

## 当前实现与实测

- 清空 App 数据后，首次打开出现 `POST_NOTIFICATIONS` 权限提示，允许后正常登录。
- App 发起带 PKCE S256 和随机 state 的授权，GitHub 返回回调；原生代码校验 state、回调地址和 issuer 后交换凭据。
- App 自动生成本机密钥、公钥自证、注册请求接收配置；未导入 token/设备文件，未安装官方 APK。
- 自动注册后进入真实首页，并启动 `specialUse` 前台监听服务；优先接收经过 TLS 和 HMAC 验证的 FCM MCS 认证消息。前台或有待批请求时每 4 秒补偿查询，后台推送连接正常时每 60 秒检查，断线时每 15 秒补偿并退避重连。
- Google 接收会话与推送加密材料同样加密保存；连接刷新 check-in，定期心跳，去重已收消息。未使用官方 APK、无障碍或读取其他 App 通知。
- 前台请求 `173912146`：首页自动弹出数字输入框，同时发高优先级系统通知；实际输入数字并点击批准，13.25 秒内成功。
- 后台请求 `173914942`：另一个测试 APK 的 Activity 保持前台，MobileGH 后台检测并发出真实系统通知；发送该通知原有的 `contentIntent` 打开 App 弹窗，系统键盘输入数字并点击批准，17.95 秒内成功。插桩测试通过，批准后请求通知清除。
- 设置关闭监听，后台服务与常驻通知停止；重新开启后恢复前台服务。系统通知权限状态随返回 App 刷新。
- 强制 Doze / 息屏请求 `173922751`：在已授予电池优化豁免的测试条件下，真实推送到达、原生 HMAC 校验解密成功、系统通知出现；唤醒后手动输入数字批准，11.49 秒完成。接收时 `mWakefulness=Asleep`、`mState=IDLE`，日志先记录认证推送后记录请求读取。
- 145 项 JVM 单元测试通过；覆盖 PKCE 标准向量、回调绑定、重复/伪造参数、注册协议解析、签名与请求替换，以及推送 HMAC 篡改拒绝、重复 protobuf 字段和帧边界。

后台测试镜像 AOSP ATD 没有标准 System UI 通知托盘与系统 Settings Activity，测试使用 Android NotificationManager 中的实际通知及其 PendingIntent 验证打开流程；未声称在该镜像上手动点击了通知托盘。Doze 测试通过 shell 模拟已批准后台运行豁免，实际手机使用 Android 提供的授权对话框和设置入口。

系统通知及后台运行豁免需用户允许；首次打开依次请求，拒绝后可从设置入口开启。常驻监听有低优先级状态通知。
关闭监听会停止服务；Android 强制停止 App、系统省电或网络不可用时可能暂时收不到提醒，重新打开 App 后恢复。
实现不使用系统悬浮窗、后台强行启动 Activity、短信或无障碍读取。

平台依据：[通知运行时权限](https://developer.android.com/develop/ui/compose/notifications/notification-permission)、
[前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)。
[Doze 与后台运行豁免](https://developer.android.com/training/monitoring-device-state/doze-standby)。
数字审批使用具有 Mobile Auth 资格的原生客户端授权上下文；普通 MobileGH 自有 OAuth 的公开接口限制仍存在。
以下保留前期脚本及导入方式的验证历史，当前用户无需执行这些步骤。

当前证据：[首次通知权限提示](../screens/2fa-first-notification-permission.png)、[全局弹窗](../screens/2fa-global-dialog.png)、
[后台通知打开弹窗](../screens/2fa-background-notification-dialog.png)、[设置权限及监听开关](../screens/2fa-settings.png)、
[息屏 Doze 推送后批准](../screens/2fa-doze-push-dialog.png)。

## 前期验证历史

2026-10-10，`../github-mobile-2fa` 脚本成功批准请求 `173881007`：真实
`TWO_FACTOR_SUDO_CHALLENGE` mutation 返回结果，浏览器进入受 sudo 保护的页面。
FCM 通知的解密 `thread_id` 与该编号一致。

本轮进一步完成了正式 Android App 的真实批准，以及 MobileGH 自有 OAuth 设备登录，结果见下方实测记录。

### 原导入流程（已替换）

1. 使用脚本注册设备并上传 FCM token。
2. 执行 `gh_mobile_2fa.py export --output <文件>.mgh2fa`，把文件传到手机。
3. 在“两步验证”页导入设备配置；OAuth 等待页也有相同入口。
4. 保持 App 页面打开。在浏览器选择 GitHub Mobile，输入当前数字，点击“确认并批准”。
5. GitHub 返回真实批准结果后，返回浏览器继续登录或授权。

当前实现复用本工具生成并注册的设备公钥、私钥与其 OAuth 凭据。Android 从 PKCS8 导入
EC P-256 密钥到 Android Keystore，证书与私钥先通过签名验签确认匹配；Token 和推送路由配置
使用已有 Keystore AES-GCM 加密保存。源文件不保存在 App 中。
配置文件含私钥和令牌，仅适合本人设备间传输；不能入库，导入后可删除传输副本。

### 原阶段行为和边界

- 前台每 4 秒查询 `viewer.mobileAuthStatus.activeAuthRequest`；页面恢复时刷新该设备的推送路由。
- 使用专用凭据和固定 `api.github.com/graphql`，不复用普通登录账号 token、API 镜像、网页 cookie。
- 数字输入按整数规范化；签名前复查完整请求，拒绝过期或已替换的请求。
- 批准必须手动确认，mutation 仅发一次；成功状态要求服务器返回批准对象。
- 正常批准不依赖桌面脚本常驻或官方 App。尚无 Android 后台推送/通知功能，必须打开批准页面。
- MobileGH 自有 OAuth Client ID 的接口资格限制仍存在。这是导入已验证设备后的可用流程，尚未实现 App 内独立注册新设备。
- 2FA 批准完成后，第三方 OAuth 的权限确认和回调仍由浏览器与第三方执行。

### 原阶段验证

Python 测试覆盖错误账号私钥、签名消息、请求替换、推送 MAC 与解密。
Android JVM 测试采用相同字节向量，验证数字规范化、请求绑定、错误输入和 ECDSA 签名。
138 项 Android JVM 单元测试全部通过，debug 与 androidTest APK 构建成功。
在现有 arm64 模拟器 emulator-5584 上，用真实导出配置运行插桩测试：
设备导入、Android Keystore 签名与脚本公钥验签、实时请求读取全部通过（1 项，3.333 秒）。
其参数 `mobilegh2faBundle` 只接受 `/data/local/tmp/` 下的测试文件路径，默认跳过，不包含任何仓库内凭据。

### 首次正式 App 的真实验证记录

2026-10-10，在独立 arm64 / Android 14 模拟器 `emulator-5556` 上完成：

1. 安装正式 debug APK，导入此前脚本注册的设备；设备未安装 `com.github.android`。
2. 从 GitHub 自己的重新验证组件创建新的 `TWO_FACTOR_SUDO_CHALLENGE`。
3. 正式 App 前台读取到请求 `173903079`，在实际输入框通过系统键盘输入网页数字 `52`，系统点击“确认并批准”。
4. 生产代码使用 Android Keystore 签名并提交 GitHub；服务端返回成功，原生页面显示“验证已批准，返回浏览器继续登录”。从触发到成功共 24.77 秒。
5. 在正式 App 登录页选择“GitHub 授权”，由 App 发起自有 OAuth 设备请求；浏览器填写 App 生成的授权码并确认授权。
6. GitHub 跳转 `/login/device/success`，显示 “Your device is now connected”；App 的生产轮询取得授权凭据、验证本人账号，自动进入真实首页，显示 `@sheep-programmer`、50 个仓库和 4 位关注者。

此次数字批准由 App 完成，桌面脚本没有签名或代发批准 mutation；随后完成的是 MobileGH 自身的 OAuth 设备登录。
浏览器已有账号会话，因此这两项是连续的真实批准和授权测试，并非重新输入密码的 `TWO_FACTOR_LOGIN` 挑战，
也未测试其他第三方网站的登录回调。后台通知仍未接入。

证据：[App 数字输入](../screens/2fa-native-input.png)、[App 批准成功](../screens/2fa-native-approved.png)、
[GitHub OAuth 成功](../screens/2fa-oauth-browser-success.png)、[App 自动进入首页](../screens/2fa-oauth-home.png)。

测试镜像 AOSP ATD 默认关闭 HWUI 绘制，导致黑屏和 UI 自动化失败；
最终在专用模拟器 `/data/local.prop` 设置 `debug.hwui.drawing_enabled=1` 后重启，恢复实际界面操作。
该配置仅用于测试模拟器，没有加入 App。过期请求实测也被原生客户端拒绝。
