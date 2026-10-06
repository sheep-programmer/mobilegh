# GitHub 第三方独立 Mobile2FA 能力询问草稿

此文本供用户审核后联系 GitHub，尚未发送。需要解决的是应用级接口可用性，现阶段无需用户再次输入验证码重复已有 OAuth 对照。

## Request for supported third-party mobile device approval

We are developing MobileGH, an independent Android GitHub client:

- Repository: https://github.com/sheep-programmer/mobilegh
- Our OAuth Client ID: `Ov23litcWlPUB3KqPlQ2`
- Intended behavior: the user reviews their own pending sign-in request and manually enters the matching two digits in MobileGH. MobileGH signs the challenge using its own Android Keystore key and submits the approval to GitHub.
- The implementation must work without GitHub Mobile installed and without accessibility automation.

On October 6, 2026, we tested both the device authorization flow and the web authorization flow with S256 PKCE, using our own application credentials and an authenticated account. We requested the scopes used by the provided GitHub Mobile APK. The issued scopes were `admin:org,notifications,project,read:discussion,repo,user,workflow`; `user:assets` was absent from the returned scope list.

At `https://api.github.com/graphql`, both authorization contexts returned `undefinedField` for `User.mobileAuthStatus`, `Mutation.addMobileDevicePublicKey`, and `Mutation.approveMobileAuthDeviceRequest`. `MobileDeviceKeyType` introspection returned null. The same results occurred with the known feature headers from the APK. Contract-validation probes used deliberately invalid inputs and never supplied a real public key, request ID, or signature. Both temporary test tokens have been revoked.

Could you clarify:

1. Is there a supported way for an independently registered third-party application to enroll its own Android device key and approve number-matching requests?
2. Which application eligibility, permissions, authentication method, or supported SDK are required?
3. If these contracts are restricted to GitHub-owned applications, is there a partner access process or planned public integration?

Our intended enrollment and approval flow uses user consent, non-exportable device keys, the actual hardware security level, request-bound signatures, and server-confirmed results. We can provide sanitized request/response evidence. All application and account credentials remain private.
