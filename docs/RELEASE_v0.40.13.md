# MobileGH v0.40.13

## 更新内容

- **SVG 2 与网页导出图表兼容渲染**
  - 新增 `SvgCompat` 归一化处理：兼容 `<marker orient="auto-start-reverse">`、`width`/`height`/`rx`/`ry` 的 `auto` / `fit-content`、CSS 风格 `transform`（`none`、`px`/`deg` 单位、`translateX`/`translateY`/`scaleX`/`scaleY`）以及 XML 未定义的常见 HTML 命名实体（`&nbsp;`、`&mdash;`、`&copy;` 等），修复架构图等 SVG 无法渲染的问题。
- **渐进式加载与网络体验优化**
  - **主内容秒出、其余懒加载**：重构 `IssueDetailScreen`（标题与正文先出，PR 信息、Checks、Reviews、评论独立异步加载）、`PullChecksScreen`（标题与 SHA 先出）、`CommitDetailScreen` 与 `CompareScreen`（头信息与提交列表先出，大 Diff 后台 `produceState` 异步解析）。
  - **并发与超时调优**：为 `Api.http` 配置 `Dispatcher(maxRequests = 64, maxRequestsPerHost = 16)` 与连接池，消除同主机请求排队；收紧连接/读取超时至 `10s / 20s`；细分 `429`（附 `retry-after` 秒数）、`502/503/504`、PAT/管理员权限及 HTML 劫持响应的中文错误提示。
- **PR 与代码阅读新能力**
  - **PR 更新分支（Update branch）**：支持在 PR 菜单与 `behind` 状态提示下直接合入目标分支最新提交。
  - **Check Runs 行内代码标注**：在 `PullChecksScreen` 展开失败/警告 Check Run 的代码 Annotations，点击直接跳转到对应文件行号。
  - **PR 按单个 Commit 筛选 Diff**：`PullFilesScreen` 支持在「全部提交」与单个 Commit 的文件变更间切换审查。
  - **代码符号大纲（File Outline）**：支持在源码页提取类、接口、函数、结构体及 Markdown 标题并关键词筛选跳转。
  - **代码片段 / 仓库名片分享图**：支持导出 1080px 深浅色自适应 PNG 分享卡片并一键系统分享。
- **通知与工作流效率增强**
  - **通知栏快捷操作**：收件箱系统通知支持直接「标为已读」，Issue/PR 通知支持 `RemoteInput`「快速回复」。
  - **通知自动降噪规则**：支持自动静音 Bot 通知（`dependabot`、`renovate`、`github-actions` 等）以及自定义静音仓库和关键词，后台推送与收件箱列表同步生效。
  - **Workflow 参数记忆**：`RunWorkflowScreen` 按仓库与工作流自动记住上次触发的分支与输入参数，并支持一键恢复默认。

## 验证

- **JVM 单元测试**：`:app:testReleaseUnitTest` 与 `:app:testDebugUnitTest` 全部通过（含 `SvgCompatTest`、`CodeOutlineTest`、`NotificationActionTest`、`ApiErrorHandlingTest`、`NotificationMuteRulesTest`）。
- **模拟器实测（`CetUiReview34`）**：`SvgBadgeTest`（5/5）与 `NewFeaturesLiveTest`（7/7）全部通过。
- **签名构建**：`:app:assembleRelease` 与 `:translation-plugin:assembleRelease` 构建成功。
