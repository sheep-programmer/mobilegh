新增「复用 GitHub Mobile 数字批准」入口。

- 登录前：点“两步验证与数字批准”，打开 GitHub Mobile。
- OAuth 等待授权时：直接打开官方 App，处理浏览器发起的两位数字验证，再返回浏览器完成授权；MobileGH 继续轮询 GitHub 的授权结果。
- 设置 → 两步验证：同样可以进入官方批准入口。
- Android 11+ 仅查询 com.github.android，未安装时提供官方安装页；从商店返回会重新检测安装状态。
- 官方 App 保留自己的登录态、设备密钥和批准界面。MobileGH 不传递 Token、密码、挑战或数字，不调用未导出的内部 Activity；切回 MobileGH 也不会误标“已批准”。

使用条件：手机上保留已登录同一账号的 GitHub Mobile，浏览器先发起确认请求。浏览器的两位数字在官方 App 输入；OAuth 的八位授权码仍用于浏览器设备授权。该版本没有把审批代码嵌入 MobileGH，也不能在卸载官方 App 后独立完成数字批准。

继续提供 arm64-v8a（推荐）、armeabi-v7a、x86_64、universal，沿用原证书，可覆盖升级。

验证：Release 构建、Android lint（0 errors）通过；4 架构 APK 的 V2/V3 签名及与 v0.20 的证书一致性已核验。

设备验证：在独立 Android 36 模拟器安装用户提供的官方 APK（1.275.0）和本版签名包，原生两步验证页正确显示“已安装”及“打开 GitHub Mobile”。点击该按钮后，系统已启动 com.github.android 的 SimplifiedLoginActivity（官方 App 未登录时的正常页面）；没有登录该官方 App 或执行真实两位数审批。
