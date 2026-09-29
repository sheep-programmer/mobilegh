# MobileGH

第三方 GitHub 安卓客户端 —— 轻量、快速、符合 GitHub 设计风格，补齐官方 App 在手机上缺少的功能。

## 为什么用它

官方 GitHub App 在手机上看不到的一些东西，MobileGH 都能看：

- **贡献热力图** —— 完整的年度贡献图 + 活跃天数、单日最高、连续活跃统计，官方 App 移动端不显示热力图
- **加入的仓库** —— 包括你被邀请为协作者的仓库，不只是自己创建的（官方 App 只能看自己建的）
- **流量洞察** —— 仓库的 Clone / 访客统计图表，官方 App 完全没有
- **Actions 构建日志** —— 在手机上直接看 Job 日志、警告高亮，还能触发 workflow_dispatch、重跑、取消

## 功能一览

- **账号密码登录**：App 内打开 GitHub 官方登录页，输入账号密码（支持两步验证），点一下授权即可；密码只提交给 github.com，App 拿到的只是 OAuth Token。也可以用 Personal Access Token 登录
- 首页动态、通知（未读角标）
- 仓库浏览：README 渲染（含相对路径图片）、代码高亮、Markdown / 图片预览、分支切换
- Issues / Pull Requests：列表、讨论串、评论、关闭、合并、审查、文件变更 Diff
- Actions：运行列表、Job 日志、手动触发 / 重跑 / 取消
- Releases、Gist、探索与搜索
- 仓库管理、组织、邀请、协作者管理
- 网络加速：可切换加速节点，内置测速（设置页）

## 技术栈

Kotlin + Jetpack Compose（Material 3，GitHub Primer 配色），最低 Android 8.0（API 26）。

- 账号登录走 GitHub OAuth 设备码流程（不需要在 App 里内置 client secret）
- Token 使用 Android Keystore 加密存储
- API 请求带 ETag 缓存，重复请求不消耗配额

## 下载

去 [Releases](../../releases) 页面下载。默认装 `arm64-v8a`（绝大多数现代手机）；老 32 位设备用 `armeabi-v7a`，模拟器 / x86 设备用 `x86_64`，不确定就用 `universal`。

## 自行构建

```bash
./gradlew :app:assembleRelease
```

发布签名：复制 `keystore.properties` 并填入自己的密钥信息即可；未配置时自动回退到 debug 签名。
