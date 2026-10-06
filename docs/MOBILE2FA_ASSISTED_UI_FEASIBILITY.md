# Mobile2FA：官方可见 UI 的辅助控制可行性检查

核对日期：2026-10-06。**结论：已确认官方数字输入、批准／拒绝与结果提示的静态 UI 链路；普通 Android 辅助服务能否实际读取、输入、点击并可靠确认结果，仍未验证。不能承诺“在 MobileGH 输入两位数即可批准”可用。**

**用户已明确选择独立实现，不使用辅助控制。本方案已排除，不进入开发或运行时验证。** 以下仅保留此前的静态分析记录，没有实现 App 或运行任何设备组件。

此前评估的候选架构是保留官方 App，由用户明确启用辅助控制，并在 MobileGH 对每笔请求手动输入数字、核对请求和确认后，辅助操作官方可见界面。它不满足当前独立实现要求。

主流程的真实 MobileGH OAuth 对照与凭据清理已完成，本轮不重新执行授权或访问账号。此前三个字段 `User.mobileAuthStatus`、`Mutation.addMobileDevicePublicKey`、`Mutation.approveMobileAuthDeviceRequest` 返回 `undefinedField`，公开 IPC 与通知 RemoteInput 也未形成数字提交／批准契约。辅助 UI 是另一个待验证的交互路径，不能改变上述接口观察。已有依据见 [OAuth 检查](MOBILE2FA_OAUTH_SCOPE_CHECK.md)、[公开 IPC 检查](MOBILE2FA_PUBLIC_IPC_CHECK.md)、[通知 Action 检查](MOBILE2FA_NOTIFICATION_ACTIONS.md)。

## 样本、边界与证据定位

- APK：`/Users/sheep/Downloads/GitHub.apk.1`。
- 本轮重新计算 SHA-256：`7ba0e05047f4b7aa53b89ce2a062a3f8e51b9695e1e7070c8d697156d03b42c3`。
- 本轮 `aapt dump badging`：包名 `com.github.android`，版本 `1.275.0`，versionCode `946`，minSdk `32`，targetSdk `37`。
- 资源证据直接来自 SDK `35.0.0/aapt` 对 APK 的 manifest、`res/layout/activity_two_factor.xml` 和所选资源值的只读解析。
- DEX 使用 `/opt/homebrew/Cellar/jadx/1.5.6/libexec/lib/jadx-1.5.6-all.jar` 中的 `com.android.tools.smali.dexlib2`，经 JShell 在内存中索引五个 DEX。指令读取限定在 TwoFactor UI、其共享输入／按钮／弹窗构造链，以及敏感语义和窗口标志相关方法；没有全 APK 反编译或字符串池导出。按用户后续指示停止扩展扫描。
- 下文 `@` 为方法内 DEX code-unit 偏移。混淆名保留样本原名，角色由实际父类、字段类型、指令及调用链确认；共享混淆类有多个分支，只有明确关联 TwoFactor 的分支作为 UI 证据。

本轮没有安装／启动 APK、连接设备、采集运行时无障碍树、填数字、点击批准／拒绝、发起 mutation、获取或导出运行时 token／key、复制官方 client secret、构建或发布。本轮仅写入本文件，保留主流程对既有文件的变更。

## 输入与审批确实连到官方 UI

| 原 APK 证据 | 可确认的行为 | 限制 |
| --- | --- | --- |
| `classes3.dex / TwoFactorActivity.<init> @000e–0011`、`a0 @0000–0002` | 页面布局为 `0x7f0d002f`，即 `layout/activity_two_factor` | 页面存在不证明第三方能够合法启动或操作它 |
| `classes.dex / bz5.onCreate @0000–000e`；`classes3.dex / TwoFactorActivity.onCreate @0007–0012` | 基类加载布局；Activity 取 binding 的 `TwoFactorDialog` 并设置完成回调 | 完成回调是官方内部 UI 生命周期回调，不是 MobileGH 的服务端批准 callback |
| `classes3.dex / TwoFactorDialog` 继承 `r30`；`classes.dex / r30` 继承 `android.view.ViewGroup`，有 composition 生命周期；`r30.<init> @000a–000b` | 这是 Compose 宿主，设置 `importantForAccessibility=1`（YES） | 宿主的重要性不能保证每个虚拟子节点可读／可操作 |
| `classes3.dex / TwoFactorDialog.l @018e–01b7` | 构造带 `input_confirm_digits_hint` 的输入状态 `gjl`；批准按钮启用值来自 `n(当前输入)`，另有拒绝按钮 | 不是一个已有固定 XML EditText ID 的布局 |
| `classes.dex / e5b.q` 的 TwoFactor 分支 `@0050–0066`、`@007b–0089`、`@00b7–00e4` | 读取 `y75.c` 输入值，连接文本变化／键盘提交回调，再调用共享弹窗 `zl60.e` | 分支由 `TwoFactorDialog.l @01dc` 的 selector `18` 建立，不能把其他分支算进认证界面 |
| `classes3.dex / zl60.e @00fa–0119` → `classes.dex / fm0.q @0050–0074` → `classes3.dex / zl60.d @00ba–00d3` → `classes.dex / tl60.g @0406–04b7` → `classes3.dex / zbt.b @0342`、`zbt.a @0245` | 输入状态确实进入共享 Compose 文本输入组件；提示、文本值和变化回调沿链传递 | 无障碍服务实际获得的 hint、文本、角色、SetText action 未采集 |
| `classes3.dex / ju70.j @0014–004b` | 文本变化允许有效输入或空串，更新官方 ViewModel 的输入状态 | 无效输入被忽略；辅助输入后的状态必须重新核对 |
| `classes3.dex / TwoFactorDialog.l @009b–00b7`；`ku70.a @0009–0014`；`b.N @0000–0079` | 批准按钮回调进入官方 `b.N`，读取当前请求／输入，启动官方审批协程；另一分支存在不要求数字的审批 | 不能把当前屏幕上的任意 Approve 当作本次数字匹配请求 |
| `classes3.dex / ju70.j @0009–0010` | 键盘提交回调也调用 `b.N()` | Enter／IME 提交可能批准，不可当成无副作用的“完成输入”动作 |
| `classes3.dex / ku70.a @0015–004e` | 拒绝回调读取当前请求，启动官方拒绝处理 | 拒绝同样有真实副作用；遇到不确定性应停止，不自动拒绝 |

### 官方输入校验并非“恰好两位”

`classes3.dex / TwoFactorDialog.n(String)` 的完整校验为：

1. `@0000–0008` 排除 null／空白。
2. `@0009–000d` 使用 `TextUtils.isDigitsOnly`。
3. `@000f–0014` 要求长度 **小于 6**。
4. `@0016–001c` 调用十进制整型解析；能解析才返回 true。

因此这个客户端校验接受可解析的 **1–5 位数字**，不是恰好两位，也没有在此方法比较数字与服务器挑战。`b.N @0035` 解析数字、`@0062` 转回字符串，说明批准处理也不是简单原样转发输入文本。前导零及服务端实际挑战规则未做运行时验证。

MobileGH 的“两位数”是自己的交互要求，不能从这里推导成官方已经保证两位匹配。若未来实现，用户输入应限定为明确的两位 ASCII 数字，且输入前后仍须核对请求；禁止猜测、枚举、补齐或自动取得挑战数字。

## 输入、按钮、成功与失败文案

以下值直接来自 APK `resources.arsc` 的默认配置及 `zh-rCN`，不是实机截屏；其他语言与实际语言回退未验证。表中资源 ID 是**字符串资源**，不是这些控件的 `viewIdResourceName`。

| 资源名／ID | 默认英文 | `zh-rCN` |
| --- | --- | --- |
| `input_confirm_digits_hint`／`0x7f1305a5` | Confirm digits | 确认数字 |
| `button_approve`／`0x7f130227` | Approve | 批准 |
| `button_reject`／`0x7f130236` | Reject | 拒绝 |
| `button_cancel`／`0x7f130228` | Cancel | 取消 |
| `button_close`／`0x7f13022a` | Close | 关闭 |
| `alert_two_factor_approved_title`／`0x7f1301e1` | Verification request approved | 登录已批准 |
| `alert_two_factor_approved_message`／`0x7f1301e0` | Authentication request was approved. | 登录到 GitHub 的请求获得批准。 |
| `alert_two_factor_approval_failed_title`／`0x7f1301df` | Unable to approve | 无法批准 |
| `alert_two_factor_approval_failed_message`／`0x7f1301de` | Failed to approve the request to sign in to GitHub. | 无法批准登录到 GitHub 的请求。 |
| `alert_two_factor_rejection_title`／`0x7f1301e9` | Request Rejected | 登录被拒绝 |
| `alert_two_factor_rejection_message`／`0x7f1301e8` | Authentication request was rejected. | 登录到 GitHub 的请求被拒绝。 |
| `alert_two_factor_rejection_failure_title`／`0x7f1301e7` | Unable to Reject | 无法拒绝 |
| `alert_two_factor_rejection_failure_message`／`0x7f1301e6` | Rejecting the request did not complete. | 拒绝登录 GitHub 的请求未完成。 |
| `alert_two_factor_fetching_request_title`／`0x7f1301e2` | Loading verification request | 正在加载登录请求 |
| `alert_two_factor_no_requests_title`／`0x7f1301e5` | No verification requests found | 未找到登录请求 |
| `alert_two_factor_generic_failure_title`／`0x7f1301e4` | Something went wrong | 出错了。 |
| `alert_two_factor_generic_failure_message`／`0x7f1301e3` | Your authentication request was not completed. | 您的登录请求未完成。 |

这些资源不只是未使用字符串：`TwoFactorDialog.l @00f0–00fd` 构造拒绝完成内容，`@0108–0114` 构造批准完成内容，`@011e–012a`／`@013c–014b` 构造拒绝／批准失败内容。`classes.dex / yqt.q` 的 TwoFactor 标题分支 `@0149`／`@0158` 读取拒绝／批准完成标题，`@0167`／`@0176` 读取对应失败标题。

`TwoFactorDialog$a.<clinit>` 定义 `FETCH`、`FETCH_ERROR`、`CHOICE_INPUT`、`CHOICE_NO_INPUT`、`LOADING_APPROVED`、`LOADING_REJECTED`、`ERROR_APPROVED`、`ERROR_REJECTED`、`FINISHED_APPROVED`、`FINISHED_REJECTED`、`UNKNOWN`，`TwoFactorDialog.m` 根据请求与处理状态选择这些状态。存在明确的完成／失败分支，但本轮没有触发其中任何一个。

请求标题并不都表示普通登录。`yqt.q @011c–0121` 读取 `MobileAuthRequestType`，缺失时使用 UNKNOWN；`@019e–01cf` 根据类型选择以下资源：

| 请求标题资源／ID | 默认英文 | `zh-rCN` |
| --- | --- | --- |
| `two_factor_dialog_new_sign_in_request`／`0x7f130ede` | New sign in request | 新登录请求 |
| `two_factor_dialog_new_device_verification_request`／`0x7f130edd` | Sign-in Verification | 登录验证 |
| `two_factor_dialog_password_reset_request`／`0x7f130ee0` | New Password Reset Request | 新密码重置请求 |
| `two_factor_dialog_new_unknown_request`／`0x7f130edf` | Authentication Request | 身份验证请求 |

另有请求描述分支：`classes.dex / ti70.g @0126–013f` 将 `f5q.r`、`f5q.s` 拼成 `text_dot_text`（`0x7f130e50`，`%1$s · %2$s`）。本轮未确认这两个字段的完整业务含义，不能擅称它们一定是账号、来源、设备、IP 或唯一 request ID；也未证明 UI 向辅助服务提供足以绑定请求的标识。

## 语义标签与 resource ID：确认到哪一层

`res/layout/activity_two_factor.xml` 只有外层 ConstraintLayout 和 `TwoFactorDialog`：

| XML 行／资源 | 实际含义 |
| --- | --- |
| line 8：`0x7f0a017d` → `com.github.android:id/container` | Activity 布局根视图 ID |
| line 17：`0x7f0a01b8` → `com.github.android:id/dialog` | Compose 宿主 ID |

**没有从这个布局或所查 TwoFactor 构造分支确认数字输入、Approve、Reject 的固定控件 ID 或专用 testTag。** 上述两个 ID 只能定位外层视图，不能据此构造 `id/approve`、`id/digits` 等选择器。提示／按钮文字已连入构造链，但是否出现在实际可访问节点上仍未知。

共享按钮链 `classes3.dex / wl60.q @0021–0039`、`@0058–0071` 读取 `md6` 的点击回调、启用状态并交给 Compose 按钮构造；数字输入走前述 Compose 输入链。Android 官方说明基础／Material 组件通常生成语义树，且节点合并会影响服务观察到的结构；这是候选定位依据，不能代替该 APK 的实机树。[Compose 语义说明](https://developer.android.com/develop/ui/compose/accessibility/semantics)

APK 内存在通用 `TestTagsAsResourceId`：`classes.dex / cy20.<clinit> @0005–000a`。`vz1.q @0589` 读取此设置，但在所查 TwoFactor 分支没有确认开启它或给输入／按钮指定 tag。库支持不等于这个页面有可靠 ID。平台 `FLAG_REPORT_VIEW_IDS` 也只是请求报告既有 ID，不能创建缺失的控件 ID。[Android ID 报告契约](https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo#FLAG_REPORT_VIEW_IDS)

因此实际 `hintText`、`contentDescription`、`text`、`stateDescription`、角色、editable、enabled、clickable、`ACTION_SET_TEXT`／`ACTION_CLICK`、节点唯一性以及重绘后的稳定性，全部列为待验证条件。

## 敏感视图、敏感语义与窗口保护

### 布局存在遮挡触摸保护，不能忽略敏感默认值

目标 XML 根视图（line 8）明确设置 `android:filterTouchesWhenObscured=true`（`0x010102c4`，boolean true）。该 XML 没有显式 `accessibilityDataSensitive`、`contentSensitivity`、password 或 contentDescription 属性。

`accessibilityDataSensitive` 在 API 34 引入；当前 Android 官方文档说明 AUTO 模式会考虑视图的遮挡触摸保护以及父视图的敏感状态。敏感视图只允许具有 `isAccessibilityTool=true` 属性的服务交互。因此“没有显式调用 setter”不能证明普通辅助控制被允许；各目标 Android／OEM 版本的实际行为仍需验证。[AUTO 的判定条件](https://developer.android.com/reference/android/view/View#ACCESSIBILITY_DATA_SENSITIVE_AUTO)、[敏感交互设置](https://developer.android.com/reference/android/view/View#setAccessibilityDataSensitive(int))

**这里的根视图属性也不能直接证明数字输入必然被封锁。** 默认弹窗链是 `TwoFactorDialog.l` → `zl60.e @0181` → `wn6.a @01c4` → `ut1.c @0182` → `ut1.d @006d` → 默认实现 `a5d.a @0037` → `classes4.dex / twd0.H @00b0–00ce`。末端创建独立 `n9e` Dialog 和其 `i9e` Compose 宿主，数字输入不应被简单假定仍是 Activity XML 根视图的普通子 View。实际采用的弹窗实现、窗口／父子关系和敏感属性传播，均未实测。

### APK 中有真实的 Compose 敏感节点门槛

| DEX 证据（均为 `classes.dex`） | 实际含义 |
| --- | --- |
| `by20.<clinit> @0086–008b` | 建立 `IsSensitiveData` 语义键，存入 `by20.o` |
| `vz1.q @006e–00a1` | 读取该语义；API 34+ 的敏感节点在请求不是 accessibility tool 时可返回空；对生成节点调用敏感标记 setter |
| `vz1.v @00bd–00de` | action 处理也读取敏感语义；API 34+、敏感且请求不是 tool 时，停止并返回 false |
| `x50.o @0000` | 实际调用 `AccessibilityManager.isRequestFromAccessibilityTool()` |
| `x50.v @0000`／`x50.u @0000` | 实际调用 `AccessibilityNodeInfo.setAccessibilityDataSensitive(boolean)`／`AccessibilityEvent.setAccessibilityDataSensitive(boolean)` |
| `a02.o @0040–0057` | 按该语义给事件设置敏感标记；另在 `@0033–003b` 设置 password 事件属性 |

这是实际限制路径，不是仅在包里发现名字。本轮未在所查 TwoFactor 输入／按钮构造链确认显式赋值 `IsSensitiveData=true` 或 Password 语义；定向平台 API 引用检查也未发现该路径直接调用 `View.setAccessibilityDataSensitive`、`View.setContentSensitivity`。**这些限定的未发现不能证明最终节点不敏感**：框架默认值、宿主／祖先、虚拟节点处理、运行时数据及系统版本仍可能影响结果。

`isAccessibilityTool` 表示服务用于帮助残障用户，不能为了审批或读取敏感视图而伪称这种用途。敏感节点也不能由服务收到后再改标记来解锁：Android 明确规定收到的节点不可由服务修改该属性。[tool 属性含义](https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo#isAccessibilityTool())、[节点敏感 setter 限制](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#setAccessibilityDataSensitive(boolean))

### `FLAG_SECURE` 与 Accessibility 是不同条件

- `TwoFactorActivity` 的继承链通向 `com.github.android.activities.g`。`classes.dex / g.onWindowFocusChanged @0003` 使用 `0x2000`；焦点为真时 `@000b` 清除该位，焦点丢失且状态 `dp4.a` 为真时 `@0029` 设置该位。**存在条件切换，不是已经证明全程 secure 或全程非 secure**；该状态的实际值未读取。
- 共享 Dialog 的 `j9e.<init> @0015–0017` 使用 `qh20.p`；`qh20.<clinit> @0002–0008` 确认它是 `Inherit`。`n9e.f @0004–000f` 据宿主窗口判断继承值，`@0027–002f` 对 Dialog 设置 `FLAG_SECURE` 位。这也受实际窗口和更新时机影响。
- Android 将 `FLAG_SECURE` 定义为限制截屏及非安全显示；它本身不是“必然禁止无障碍读取／点击”的证据。`View.setContentSensitivity` 保护屏幕共享／录制，也不能与无障碍敏感属性混为一谈。[FLAG_SECURE 契约](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE)、[内容敏感设置](https://developer.android.com/reference/android/view/View#setContentSensitivity(int))

不得通过清除保护位、修改官方视图、伪装 tool、hook／root 或其他权限绕过验证可行性。若读取或动作被敏感门槛禁止，应判定该设备条件下辅助批准不可用。

## 未知的运行时条件

以下都是**未验证**，不是已通过的检查；本轮不运行设备来补齐。

| 条件 | 需要真实 UI 证明的内容 |
| --- | --- |
| 官方账号／设备资格 | 官方 App 已登录正确账号，设备已合法注册，并且当前有用户本人发起的有效请求；MobileGH OAuth 成功不能替代这些条件 |
| 用户是否接受架构 | 已明确拒绝辅助控制；此方案排除，其他运行时条件不再继续验证 |
| 系统与服务配置 | 实际 Android／OEM 版本、服务真实声明和用户启用状态；窗口内容能力与 Dialog 窗口事件可用，不冒充 accessibility tool |
| 实际窗口与敏感传播 | Activity 与 Dialog 哪个窗口承载输入，是否敏感、是否因遮挡或窗口切换受限；XML 根保护对该弹窗的实际影响 |
| 节点可读性与定位 | 正常服务能否合法读取当前输入与按钮；文字、hint、角色、动作、状态及节点唯一性；语言、字号、横竖屏和键盘对结构的影响 |
| 请求关联 | 用户确认的账号／请求类型／请求上下文能否与正在显示的官方请求唯一对应；两位数字本身不足以绑定请求 |
| 输入与提交 | SetText 是否存在且有效，输入后当前请求和实际文本能否再次核对；是否可能误触键盘提交或无数字审批分支 |
| 账号锁与系统认证 | 官方 App 锁、生物识别、系统认证、锁屏或其他弹窗是否中断；这些步骤必须由用户在真实系统界面完成 |
| 生命周期与并发 | 请求到期、重复推送、并发请求、账号切换、旋转／重建／前后台切换时，是否能检测并撤销本次待操作意图 |
| 结果判定 | 官方当前请求的完成／失败 UI 能否可靠关联；点击返回值、窗口消失及完成回调都不能单独代表服务器批准成功；发起登录的一端是否确实完成仍未验证 |

现有证据可以支撑讨论这项架构及其验证门槛，不能支撑宣称已可用。若最终 UI 无法提供足够的请求关联信息，或普通服务被敏感门槛限制，应收敛为用户在官方界面手动完成。

## 必须 fail-closed 的条件

fail-closed 的意思是停止辅助动作、作废本次待操作确认，并交还用户处理；**不是**改走盲点坐标、OCR／录屏、手势或私有接口继续批准，也不是自动拒绝请求。

| 触发条件 | 必须行为 |
| --- | --- |
| 用户未接受此架构、未明确启用服务，或只有全局启用授权而没有本笔请求的手动数字及明确确认 | 不输入、不批准；服务启用不等于批准任何后来请求 |
| 请求与账号不能唯一对应，只有通用标题／两位数字，或出现多个可能目标 | 不猜测目标，不选择“最新一笔”；停止并由用户核对 |
| 请求类型 UNKNOWN、无数字分支或密码重置等超出当前确认范围 | 不将已有确认用于另一种请求；停止 |
| 官方包／页面／窗口身份不符，App 版本或节点结构不在已验证条件内，节点缺失／重复／不可见／禁用 | 不沿用旧节点或假定控件位置；停止 |
| 敏感节点被隐藏、返回 null、action 拒绝，或必须伪称 `isAccessibilityTool` 才能继续 | 判定该条件下不可用；禁止突破敏感视图权限 |
| 输入没有按预期更新、无法合法回读核对、长度或内容不符，或必须发送 Enter／IME 才能继续 | 不批准；不得把可能触发批准的键盘提交当作输入确认捷径 |
| 输入到点击之间账号、请求、类型、窗口或上下文改变，请求到期／重复／并发、服务重连或进程重建 | 作废本次确认和数字；不得把它们迁移到新请求或自动恢复审批 |
| 官方 App 锁／生物识别／系统认证、锁屏、遮挡或用户接管界面 | 暂停并交给用户；不绕过或代为完成系统认证 |
| 批准动作结果不确定、超时、官方报错，或无法把成功提示关联到本次请求 | 不自动重试，不上报成功；提示结果未知或失败，并交给用户核实 |
| 未确认的通知到达、后台事件或定时任务要求批准 | 不启动自动审批；只允许用户明确确认的当前请求 |

不获取或导出 token／设备私钥，不复制官方 client secret，不把官方登录态转交 MobileGH。未来任何实现均必须受每笔确认、当前可见请求、合法节点权限和上述停止条件约束；本轮只提供静态证据与待验证条件。
