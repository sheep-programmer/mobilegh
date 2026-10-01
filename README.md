# MobileGH

第三方 GitHub 安卓客户端 —— 轻量、快速、符合 GitHub 设计风格，补齐官方 App 在手机上缺少的功能。

## 为什么用它

官方 GitHub App 在手机上看不到的一些东西，MobileGH 都能看：

- **贡献热力图** —— 完整的年度贡献图 + 活跃天数、单日最高、连续活跃统计，官方 App 移动端不显示热力图
- **加入的仓库** —— 包括你被邀请为协作者的仓库，不只是自己创建的（官方 App 只能看自己建的）
- **流量洞察** —— 仓库的 Clone / 访客统计图表，官方 App 完全没有
- **Actions 构建日志** —— 在手机上直接看 Job 日志、警告高亮，还能触发 workflow_dispatch、重跑、取消

## 功能一览

- **账号密码登录**：App 内打开 GitHub 官方登录页，输入账号密码并按 GitHub 页面完成两步验证；优先选择「验证器应用」，已保存的 TOTP 会自动填入。GitHub Mobile 的数字匹配仍由 GitHub 安全系统控制，MobileGH 不代替批准。网页会话默认保留，减少重复触发登录挑战；也可以用 Personal Access Token 登录
- **默认长期登录**：MobileGH OAuth 应用已关闭固定 8 小时过期，账号登录不申请 `offline_access`，新授权默认没有固定到期时间。已有短期授权请重新登录一次；Personal Access Token 的到期时间由用户设置。设置 → 登录时长可重新授权，也提供应用所有者检查过期设置的入口
- **文件直接下载**：Release 资源、源码压缩包、仓库文件直接下到系统「下载」目录（带进度通知），私有仓库自动带 Token 直连
- **Markdown 编辑器**：新建 Issue、发表评论都有格式工具栏（标题/粗体/斜体/删除线/代码/链接/引用/列表/任务/@）和「编辑 / 预览」切换，预览走 GitHub 官方 GFM 渲染
- **内置 TOTP 验证器**：在设置里存入 GitHub 两步验证密钥后，登录时验证码本地算出、自动填入，一台设备搞定，不必再掏第二台手机确认（标准 RFC 6238，密钥经 Keystore 加密存本机）
- 首页动态、通知（未读角标）
- 仓库浏览：README 渲染（含相对路径图片）、代码高亮、Markdown / 图片预览、分支切换
- Issues / Pull Requests：列表、讨论串、评论、关闭、合并、审查、文件变更 Diff
- Actions：运行列表、Job 日志、手动触发 / 重跑 / 取消
- Releases、Gist、探索与搜索
- 仓库管理、组织、邀请、协作者管理
- 网络加速：可切换加速节点，内置测速（设置页）
- 组织仓库与动态：按加入的组织分组查看全部可访问仓库，支持组织筛选、可见性和 Fork 筛选；列表下方显示所选组织最近的 Push、Issue、PR 和 Release 动态
- 组织列表兼容 `/user/orgs` 与 active membership 两个接口，减少私有组织、隐藏成员身份和 SSO 导致的组织为空问题；组织动态列表使用稳定复合 key，避免重复事件导致页面崩溃
- 提交变更阅读：提交和 Pull Request 的文件变更默认折叠，展开后按文件显示代码块、行号、增删颜色和轻量语法高亮
- 应用内下载：Release 资源、源码、文件和 Actions 产物会弹出应用内进度框，显示速度状态、已下载大小、取消、重试、打开文件和下载记录；系统下载通知不再显示进度
- 稳定性：滚动 Issue/PR 评论时及时销毁离屏 WebView，捕获 Chromium 渲染进程崩溃；大图解码内存不足时清理图片缓存，避免网页内容越刷越容易闪退
- Actions 详情：运行详情可展开每个 Job，查看每个 Step 的编号、状态和耗时；点击 Step 或「打开终端日志」进入可上下滑动的终端日志，支持错误/警告筛选、时间戳、自动换行和复制
- 日志与诊断：设置中可以查看全部应用日志，按网络、认证、下载、Actions、网页渲染、崩溃和应用分类筛选，搜索关键词，展开堆栈详情，清空或导出当前分类/全部日志
- 下载加速：自动模式优先选择测速可用的下载代理，只有所有下载代理不可用时才直连；设置页同时显示源码/图片节点和下载节点，下载日志会记录实际节点与地址主机
- 已内置并测速：ghfast.top、ghproxy.net、gh-proxy.com、gh-proxy.org、gh.llkk.cc、gh.jasonzeng.dev；公开 Release/Raw 下载优先走可用代理，私有资源始终直连 GitHub

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
