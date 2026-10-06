完善官方数字批准与 MobileGH OAuth 的衔接。

- 打开官方 GitHub Mobile 后，等待页提供「继续浏览器授权」按钮，并重新复制当前设备授权码。
- 修复系统因主题等配置变更重建页面时，OAuth 请求与倒计时丢失的问题；授权任务随页面保留，退出页面或取消时停止。应用原有旋转适配保留。
- 等待授权期间短暂断网或 GitHub 暂时不可用时，保留同一个请求并逐步延长重试间隔，授权码有效期不变；拒绝缺失或无效的期限，不再本地延长短有效期。
- 遵守 GitHub 的 slow_down 间隔；过期、拒绝及无效请求结束授权，避免重复申请。
- GitHub 已签发 Token 后，账号核验可重试短暂网络错误，不重复兑换设备码。
- 区分浏览器设备授权码与官方 App 的两位数字，简化批准入口文案。

使用条件：保留已登录同一账号的官方 GitHub Mobile，用户手动完成数字批准，再在浏览器完成 MobileGH 授权。本版没有内嵌官方审批代码，也没有注册 MobileGH 为独立数字批准设备。

提供 arm64-v8a（推荐）、armeabi-v7a、x86_64、universal 签名包，沿用原证书，可覆盖升级。

实现依据：[GitHub OAuth 设备流程](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps#device-flow)、[RFC 8628](https://www.rfc-editor.org/rfc/rfc8628.html#section-3.5)。

验证：101 项单元测试通过；Release 构建、lint（0 errors）通过；四个架构的 V2/V3 签名核验通过，证书与 v0.21 相同。

设备检查：签名包在 Android 36.1 模拟器覆盖安装，360dp 宽度下检查等待页布局；取得真实 GitHub 设备授权码后切换系统深浅主题，授权码保持一致。打开官方 GitHub Mobile、返回 MobileGH、通过新增按钮打开 Chrome，再返回时仍是同一个等待请求；取消正常结束。官方 App 未登录，未执行真实数字匹配批准，也未以切回应用作为批准成功的证据。模拟器初始遇到 Android 系统进程／System UI 无响应，处理系统弹窗后继续验证，未将此记录归为 MobileGH 崩溃。
