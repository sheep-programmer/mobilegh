# MobileGH 官方功能差距补齐设计

## 目标

将 2026-11-14 官方 GitHub Android APK 对照报告中能够由公开 GitHub API、Android 系统能力或 MobileGH 已有会话能力可靠实现的功能补齐，同时保持原生 Compose 界面与现有交互风格。

## 范围

1. Issue Type：详情显示、编辑、按类型筛选；创建 Issue 时可选择类型。
2. Issue 层级：父 Issue、子 Issue、完成进度、添加现有 Issue、新建子 Issue、更换或移除父 Issue。
3. PR 关闭关联：通过公开 `closingIssuesReferences` 只读展示，不冒充通用 Linked Items 写入。
4. Actions：全部重跑和仅失败任务重跑均可启用 Debug Logging。
5. Deployment Review：在 Workflow Run 详情读取待审批环境，支持留言、批准、拒绝和权限状态。
6. Saved Replies：读取当前账号的保存回复，在 Issue、PR、Review 等 Markdown 编辑器中按光标插入；管理入口跳 GitHub 设置。
7. 系统分享：文本和 PNG/JPEG/GIF 图片进入原生新建 Issue 流程；复用现有附件检查、缓存和上传能力。
8. 后台通知：用户明确开启后通过 WorkManager 轮询 GitHub 通知；支持本机工作时间、跨夜时段、去重、账号隔离和通知深链。
9. 小组件：新增待处理 PR 与首页快捷键小组件，复用现有 Glance 和 `WidgetIntents` 导航。
10. 企业深链：识别 `*.ghe.com`，只在链接 Host 与当前账号 Host 相符时路由，避免用错误账号请求企业资源。

## 非目标与边界

- 不实现公开文档未核实的 `linkIssueOrPullRequest` / `unlinkIssueOrPullRequest` 写入。
- 不以假界面代替完整 Copilot Chat、Agent Session、Tasks、Subagents 或 Custom Skills。
- 不复用官方 Firebase 推送服务，不增加语音、订阅购买或 Analytics SDK。
- 后台通知是本机轮询，工作时间只控制本机通知，不宣称与官方 App 云端同步。
- 不重复实现已有 Issue 模板、应用锁、PR Review、普通 Actions 重跑、Star Lists、Achievements、Watch、贡献和通知小组件。
- 保留现有未提交的推送崩溃修复和日志文件导出；不提交、不推送、不改版本、不发布。

## 架构决定

- GraphQL 功能使用轻量专用模型和 `Api.graphql`，不要套用 snake_case REST 解码器。
- Issue 类型、层级或部署审批加载失败时保留核心详情内容，并显示可重试的局部错误。
- 所有 GraphQL 写操作使用 Node ID，不能使用 REST 数字 ID 代替。
- 新界面使用独立、聚焦的文件，避免继续膨胀 `IssueScreens.kt`、`ActionsScreens.kt` 和 `MiscScreens.kt`。
- 分享图片只把缓存文件引用放入 pending-share store，不把图片字节塞入导航状态。
- WorkManager 使用网络约束和系统最小周期间隔；不申请精确闹钟，不要求电池优化白名单。
- 每个行为先写失败测试，再实现；最终运行完整单元测试、Release 构建和针对真实账号可用数据的原机验证。
