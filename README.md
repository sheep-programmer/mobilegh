# MobileGH

MobileGH 是一款用 Kotlin 和 Jetpack Compose 构建的第三方 GitHub Android 客户端。提供原生渲染的仓库与代码阅读体验、完整的 Issue / PR / Discussions 协作、Actions 设置与日志诊断、加速下载与离线翻译等核心能力。

简体中文 · [English](README.en.md) · [下载最新版](https://github.com/sheep-programmer/mobilegh/releases/latest) · [反馈问题](https://github.com/sheep-programmer/mobilegh/issues)

## 核心亮点

- **原生阅读与离线翻译**：原生 Compose 渲染 README（目录跳转、图片/表格缩放、动态徽章竞速兜底、视频与公式支持）；可选独立离线翻译组件，端侧完成中英互译与按需多语种翻译。
- **缓存优先，冷启秒开**：首页、Star 列表、仓库列表与列表详情均支持磁盘与进程快照缓存，冷启动先展示缓存内容并在后台静默刷新，同时给出非阻塞刷新状态提示。
- **完整代码与仓库工作流**：源码高亮与逐行归因（Blame）、分支对比（统一/分栏 Diff）、多文件/文件夹提交与 Git LFS（最高 2 GiB）、以及跨平台仓库导入。
- **Actions 全链路管理**：查看 Job/Step 日志、跳到失败步骤、搜索并导出日志；支持 Actions 通用权限、Secrets 端侧加密写入、Variables、Runners、Caches 与部署审批。
- **Issue / PR 深度协作**：支持 YAML/Issue 模板、Issue Type、父子 Issue 层级、Saved Replies、行内审查与代码建议、PR 文件已阅状态同步与系统分享直接建 Issue。
- **加速镜像、下载与在线更新**：网络设置独立配置「文件与图片」「下载与克隆」加速线路；Release 附件筛选、断点续传、SHA-256 校验、Actions 产物提取 APK，以及独立更新界面与下载完成后自动拉起安装。
- **安全与宽屏适配**：多账号切换、应用锁、本机 TOTP 验证器、后台通知与工作时间、以及平板/折叠屏自适应左侧导航栏。

## 界面预览

截图取自最新模拟器构建，使用虚构账号和本地测试数据。

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/zh/01-home.png" alt="首页与我的工作快捷入口" width="240"><br>首页工作台</td>
    <td align="center"><img src="docs/screenshots/zh/03-repo.png" alt="仓库详情与快捷入口" width="240"><br>仓库详情</td>
    <td align="center"><img src="docs/screenshots/zh/19-file-readme.png" alt="原生渲染 Markdown 与 README" width="240"><br>原生 README</td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/zh/04-issue.png" alt="Issue 正文、评论与操作" width="240"><br>Issue 讨论</td>
    <td align="center"><img src="docs/screenshots/zh/05-diff-split.png" alt="提交的左右分栏代码对比" width="240"><br>分栏 Diff</td>
    <td align="center"><img src="docs/screenshots/zh/21-job-log-dark.png" alt="深色模式下的 Actions 作业日志" width="240"><br>Actions 深色日志</td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/zh/07-notifications.png" alt="按仓库分组的通知" width="240"><br>通知收件箱</td>
    <td align="center"><img src="docs/screenshots/zh/12-star-overview.png" alt="Star 列表目录与搜索排序" width="240"><br>Star 列表</td>
    <td align="center"><img src="docs/screenshots/zh/08-insights.png" alt="贡献者统计与提交趋势" width="240"><br>贡献统计</td>
  </tr>
</table>

<details>
<summary>查看更多界面截图（代码浏览、分支切换、搜索、成就、设置与平板横屏）</summary>

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/zh/02-repos.png" alt="仓库列表" width="240"><br>仓库列表</td>
    <td align="center"><img src="docs/screenshots/zh/18-file-code.png" alt="源码高亮阅读" width="240"><br>源码阅读</td>
    <td align="center"><img src="docs/screenshots/zh/06-compare.png" alt="分支与提交比较" width="240"><br>分支比较</td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/zh/03b-branch-picker-sheet.png" alt="分支与标签选择器" width="240"><br>分支选择</td>
    <td align="center"><img src="docs/screenshots/zh/03c-readme-toc-sheet.png" alt="README 目录跳转" width="240"><br>README 目录</td>
    <td align="center"><img src="docs/screenshots/zh/09-star-list.png" alt="Star 列表详情" width="240"><br>Star 列表详情</td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/zh/16-search-results.png" alt="全局搜索结果" width="240"><br>搜索结果</td>
    <td align="center"><img src="docs/screenshots/zh/22-issues.png" alt="Issue 列表筛选" width="240"><br>Issue 筛选</td>
    <td align="center"><img src="docs/screenshots/zh/23-pushes.png" alt="最近推送记录" width="240"><br>最近推送</td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/zh/25-achievements.png" alt="GitHub 成就徽章" width="240"><br>成就徽章</td>
    <td align="center"><img src="docs/screenshots/zh/10-settings.png" alt="应用设置" width="240"><br>应用设置</td>
    <td align="center"><img src="docs/screenshots/zh/11-login.png" alt="登录与授权" width="240"><br>登录页</td>
  </tr>
</table>

<img src="docs/screenshots/zh/26-tablet-settings.png" alt="平板横屏使用左侧导航，设置页占满其余宽度" width="800">

</details>

## 下载与登录

支持 **Android 8.0（API 26）及以上**。v0.40.12 的 ARM64 主安装包和各架构校验值见最新 Release。 [Release 说明](docs/RELEASE_v0.40.12.md)

| 设备 | 安装包 |
| --- | --- |
| 大多数安卓手机和平板 | `app-arm64-v8a-release.apk` |
| 旧款 32 位 ARM 设备 | `app-armeabi-v7a-release.apk` |
| x86-64 设备或模拟器 | `app-x86_64-release.apk` |
| 不确定架构 | `app-universal-release.apk` |

每个 Release 附有 `SHA256SUMS.txt`。安装后可用 GitHub 授权登录，也可以使用个人访问令牌（PAT）。仓库操作取决于账号权限、授权范围和组织策略。 [下载页](https://github.com/sheep-programmer/mobilegh/releases/latest) · [认证与权限说明](docs/AUTH_CAPABILITIES.md)

## 主要功能

| 场景 | 在 MobileGH 中可以做什么 |
| --- | --- |
| 浏览仓库 | 原生阅读 README（支持目录跳转、`data-path` 相对路径、图片/表格缩放与动态徽章竞速兜底），查找源码、查看逐行归因（Blame），按时间、Star、Watch 或名称排列 Fork。 |
| 协作与审查 | 阅读和编辑 Issue、PR 与 Discussions，支持 Issue 模板/YAML 表单、Issue Type、父子 Issue、Saved Replies、Markdown 草稿、行内审查、代码建议、统一/分栏 Diff 与 PR 已阅状态同步。 |
| Actions 与导入 | 查看 Job 和 Step、跳到失败步骤、搜索并导出日志，按权限触发、重跑（含 Debug Logging）或审批部署；原生配置 Actions 权限、Secrets（端侧加密）、Variables、Runners 与 Caches；支持仓库导入。 |
| 上传与仓库维护 | 编辑文件、创建分支、管理 Release。多选文件或文件夹一次提交，超出 GitHub 普通文件限制时自动走 Git LFS（最高 2 GiB）。 |
| 日常工作台 | 首页自定义快捷入口与收藏夹；GitHub 与本机 Star 列表、冷启动缓存与后台静默刷新；通知按仓库分组与多选处理；个人主页贡献热力图与成就徽章。 |
| 账号与安全 | 多账号切换、应用锁、本机 TOTP 验证器、后台通知与工作时间设置；支持诊断日志导出（`.log` 文件）与崩溃现场记录。 |
| 阅读、加速与更新 | 中英文、深色模式、悬浮底栏、可选离线翻译及平板左侧导航；分场景加速镜像（文件与图片 / 下载与克隆）、Release 筛选、断点续传、SHA-256 校验、独立更新界面与自动安装。 |

### 离线翻译

默认关闭。在「设置 → 外观与语言 → 阅读」开启后，按提示下载并安装独立组件，再下载中英互译模型。模型通过完整性校验和双向试译后，开关才会开启。其他语言可按需下载。

开启后，页面右上角的「⋯」可在译文和原文之间切换。正文在设备上翻译，译文只在本次会话内存中缓存。独立组件及下载的语言模型保留在设备上，主安装包无需携带翻译引擎。

### 下载与更新

从首页右上角可随时打开下载记录。下载详情和记录面板可用右上角横线图标最小化，也可彻底关闭面板，任务和文件仍可从下载记录找回。GitHub 提供 SHA-256 的附件会在保存前校验。

应用内在线更新使用独立于普通下载的更新提示与进度界面，更新安装包下载完成后会自动拉起系统安装器。网络设置支持分别配置「文件与图片」和「下载与克隆」加速镜像。

## 构建与贡献

需要 JDK 17 或更高版本和 Android SDK 36。仓库包含 `app`（Kotlin、Compose UI 与 GitHub API）及 `translation-plugin`（可单独安装的离线翻译组件）。

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:assembleRelease :translation-plugin:assembleRelease
```

维护 Release 时，将 `translation-plugin/build/outputs/apk/release/translation-plugin-release.apk` 以 `mobilegh-offline-translation.apk` 的名称加入同一 Release。主包与组件须使用同一签名证书，GitHub Release 资产须提供 SHA-256 摘要。构建读取本地 `keystore.properties` 或 `MOBILEGH_*` 签名环境变量，缺少发布密钥时回退到调试签名。

可用 `tools/readme_screenshots.sh` 在专用模拟器上生成中英文截图。提交问题时请附版本号、Android 版本、复现步骤和截图。 [功能清单](docs/FEATURES.md) · [发布回归清单](docs/RELEASE_REGRESSION.md) · [提交 Issue](https://github.com/sheep-programmer/mobilegh/issues/new/choose)

## 许可证

MobileGH 项目自有代码采用 **GNU Lesser General Public License v3.0 或更高版本（`LGPL-3.0-or-later`）**。分发修改版本时须遵守许可证的源代码和通知要求。与其他代码链接使用时，适用 LGPL 的附加许可及其条件。 [LGPL 正文](LICENSE) · [GPL v3 基础条款](COPYING)

第三方代码、依赖和资源沿用各自的许可证或使用条款，包括可选翻译组件使用的 Google ML Kit。 [内置网页资源的第三方声明](app/src/main/assets/web/THIRD_PARTY_LICENSES.txt)
