# MobileGH 独立数字批准要求

用户已明确选择：必须独立实现，不使用辅助控制。此前官方 App 启动入口不满足这个需求，辅助 UI 方案排除。

## 交付条件

1. 手机未安装官方 GitHub Mobile 时，MobileGH 仍能完成设备注册、读取本人待批准请求、输入两位数字并取得 GitHub 的真实批准结果。
2. 认证使用 MobileGH 自有应用凭据和用户授予的权限；设备签名密钥由 MobileGH 在 Android Keystore 内生成与使用。
3. 原生页面展示真实请求、账号和可获取的请求上下文，用户手动输入数字并确认；签名绑定服务端挑战及当前请求。
4. 设备注册、批准成功、过期或拒绝均以服务端确认的结果为准；页面切换、按钮点击或本地签名生成不构成批准成功。
5. 不引入辅助控制、界面自动点击、官方 App 依赖或私有组件委托；不复制官方应用凭据、已有设备私钥或冒用官方身份。

## 当前阻塞

已使用 MobileGH 自有 Client ID 完成设备 OAuth 和 S256 PKCE 网页 OAuth，申请官方 APK 的请求范围并核对实际签发权限。两种上下文在基准与完整已知 APK feature headers 下，都未取得 `mobileAuthStatus`、`MobileDeviceKeyType`、`addMobileDevicePublicKey` 或 `approveMobileAuthDeviceRequest` 的可用契约。

本地可以实现密钥生成、消息编码和签名，但现有结果不足以完成服务端注册和批准。需要取得可供 MobileGH 自有身份使用的服务端能力或新的合法协议证据；当前不得宣称独立审批已完成。结论限定于已测试的协议和授权上下文。

## 验证记录

- [授权方式与请求范围对照](MOBILE2FA_OAUTH_SCOPE_CHECK.md)
- [脱敏响应](MOBILE2FA_OAUTH_TEST_RESULTS.json)
- [实际 APK 协议](MOBILE2FA_PROTOCOL_CHECK.md)
- [已排除的辅助方案静态记录](MOBILE2FA_ASSISTED_UI_FEASIBILITY.md)
