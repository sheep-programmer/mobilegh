# Official Feature Gaps Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将官方 APK 差距报告中可验证的 Issue、Actions、Markdown、系统分享、通知、小组件与企业深链能力完整接入 MobileGH。

**Architecture:** 保留现有 REST 详情与原生 Compose 页面，以独立 GraphQL 数据模块补充 Issue 类型/层级、Saved Replies 和 Deployment Review。Android 系统能力分别通过 pending-share store、WorkManager 和现有 Glance/导航基础设施接入，所有账号相关操作检查 `Session.generation` 与 Host。

**Tech Stack:** Kotlin、Jetpack Compose Material 3、Kotlin serialization、GitHub REST/GraphQL、WorkManager、Glance、JUnit。

**Spec:** `docs/superpowers/specs/2026-11-14-official-feature-gaps-design.md`

## Global Constraints

- 保留当前未提交修复，不提交、不推送、不改版本、不发布。
- 新功能默认使用公开 API；Linked Items 私有写入和完整 Copilot 会话不在范围内。
- 原生 Compose 渲染，不用 WebView 替代功能页面。
- 类型、层级、审批等增强数据失败不得阻塞 Issue/PR/Actions 核心内容。
- 所有用户可见新字符串加入 `app/src/main/assets/i18n/en.tsv`。
- 每项实现遵循 RED → GREEN，并运行相关既有测试；最后运行完整 `testDebugUnitTest` 与 `assembleRelease`。

## Review Focus

- GraphQL 返回 null、缺少字段或部分权限失败：核心详情仍可使用；Task 1、2、5 的解析器测试覆盖。
- 跨仓库父子 Issue 与 PR 关闭关联：必须保留每项自己的 owner/name；Task 2、3 测试覆盖。
- 跨夜工作时间、时区与 DST：不得把 `from > to` 当成永不通知；Task 8 用注入时间测试。
- 分享 URI 权限与进程重建：图片先复制到应用 cache，pending-share 只保存本地引用；Task 7 测试一次性消费和草稿优先级。
- 账号切换、退出登录和陈旧 Worker 回调：不得把上一账号通知或数据展示到当前账号；Task 5、8、9 测试 generation/Host 守卫。

---

### Task 1: Issue Type 数据、编辑与筛选

**Files:**
- Create: `app/src/main/java/com/mobilegh/data/IssueTypes.kt`
- Create: `app/src/main/java/com/mobilegh/ui/screens/IssueTypePicker.kt`
- Modify: `app/src/main/java/com/mobilegh/data/IssueQuery.kt`
- Modify: `app/src/main/java/com/mobilegh/ui/screens/IssueFilters.kt`
- Modify: `app/src/main/java/com/mobilegh/ui/screens/IssueScreens.kt`
- Modify: `app/src/main/java/com/mobilegh/ui/screens/ThreadParts.kt`
- Modify: `app/src/main/java/com/mobilegh/ui/screens/NewIssue.kt`
- Test: `app/src/test/java/com/mobilegh/data/IssueTypesTest.kt`
- Test: `app/src/test/java/com/mobilegh/data/IssueQueryTest.kt`

**Interfaces:**
- Produces: `IssueType(id: String, name: String, description: String?, color: String?, enabled: Boolean)`。
- Produces: `IssueTypes.parseOptions(JsonObject): List<IssueType>`、`IssueTypes.parseIssueType(JsonObject?): IssueType?`。
- Produces: `suspend fun GitHub.issueTypeOptions(o: String, n: String): List<IssueType>` 与 `suspend fun GitHub.setIssueType(issueNodeId: String, issueTypeId: String?)`；若 GitHub 为 object 则采用同等 object 函数，不引入无效扩展接收者。
- `IssueQuery.issueType: String?` 保存类型名称；仅 Issue 搜索输出 `type:"<name>"`，PR 查询移除该条件。

- [ ] 在 `IssueTypesTest` 写解析失败测试：完整字段、null、禁用类型、未知颜色字段；断言 ID/名称/启用状态及 null 安全。
- [ ] 在 `IssueQueryTest` 写失败测试：类型引号与转义、Search 激活、active/without/clear/normalize/key，以及 `pulls=true` 时移除类型。
- [ ] 运行 `./gradlew testDebugUnitTest --tests '*IssueTypesTest' --tests '*IssueQueryTest'`，确认因接口/字段不存在而失败。
- [ ] 实现 GraphQL `repository.issueTypes` 分页读取与 `updateIssueIssueType(input:)`；null `issueTypeId` 表示清除，成功后返回最新类型。
- [ ] 扩展 `IssueQuery` 和 Issue-only 筛选对话框，类型选项使用 Task 接口；不假定 REST 列表项含类型字段。
- [ ] 在 Issue 详情元数据中加入类型徽章与 triage 权限编辑；独立加载，失败只显示局部重试。NewIssue 选择类型后在创建成功时以返回的 `nodeId` 设置，设置失败明确提示 Issue 已创建但类型未设置。
- [ ] 重跑上述测试及 `IssueActionsTest`、`IssueBatchScreensTest`、`NavRestoreTest`，确认通过。

### Task 2: 父 Issue / 子 Issue 与完成进度

**Files:**
- Create: `app/src/main/java/com/mobilegh/data/IssueHierarchy.kt`
- Create: `app/src/main/java/com/mobilegh/ui/screens/IssueHierarchyCard.kt`
- Modify: `app/src/main/java/com/mobilegh/ui/screens/IssueScreens.kt`
- Modify: `app/src/main/java/com/mobilegh/nav/Nav.kt`
- Modify: `app/src/main/java/com/mobilegh/ui/screens/NewIssue.kt`
- Modify: `app/src/main/java/com/mobilegh/ui/AppRoot.kt`
- Test: `app/src/test/java/com/mobilegh/data/IssueHierarchyTest.kt`
- Test: `app/src/test/java/com/mobilegh/ui/screens/IssueHierarchyActionsTest.kt`

**Interfaces:**
- Produces: `IssueRef(nodeId, number, title, state, owner, name)`、`SubIssuesSummary(total, completed, percentCompleted)`、`IssueHierarchy(issueType, parent, subIssues, summary)`。
- Produces: `IssueHierarchy.parse(JsonObject): IssueHierarchy`。
- Produces: `issueHierarchy(o,n,num,force)`、`addSubIssue(issueNodeId,subIssueNodeId,replaceParent=false)`、`removeSubIssue(issueNodeId,subIssueNodeId)`。
- `Screen.NewIssue` 增加可选 `parentNodeId: String?`、`parentNumber: Int?`，默认 null 以保持既有导航解码兼容。

- [ ] 写解析失败测试：无父节点、空子列表、进度、跨仓库引用、关闭状态和 null summary。
- [ ] 写关系操作失败测试：移除父节点交换 parent/current ID；添加/移除子节点使用 current/child ID；禁止把当前 Issue 添加为自身子节点。
- [ ] 运行新测试，确认因缺失模型和关系函数失败。
- [ ] 实现公开 `issue { parent subIssues subIssuesSummary }` 查询及 `addSubIssue` / `removeSubIssue`；分页读取子 Issue，解析时保留跨仓库坐标。
- [ ] 实现紧凑 Hierarchy 卡片：父 Issue、进度、子 Issue 行、添加现有 Issue、新建子 Issue、移除/更换父 Issue；操作按 `access.triage` 显示。
- [ ] 新建子 Issue 成功后调用 `addSubIssue`；若关联失败保留新建 Issue 并给出可理解错误。成功后刷新当前详情和仓库 Issue 列表。
- [ ] 运行新测试及既有 Issue/NewIssue/Nav 测试，确认通过。

### Task 3: PR 关闭关联只读信息

**Files:**
- Modify: `app/src/main/java/com/mobilegh/data/IssueHierarchy.kt`
- Modify: `app/src/main/java/com/mobilegh/ui/screens/IssueHierarchyCard.kt`
- Modify: `app/src/main/java/com/mobilegh/ui/screens/IssueScreens.kt`
- Test: `app/src/test/java/com/mobilegh/data/IssueHierarchyTest.kt`

**Interfaces:**
- Produces: `closingIssues(o,n,num,force): List<IssueRef>`，只查询 `PullRequest.closingIssuesReferences`。

- [ ] 写失败测试：多个关闭关联、跨仓库目标、空连接和 null pageInfo。
- [ ] 运行测试确认失败后，实现公开字段查询和分页。
- [ ] 只在 PR 详情展示“关闭关联”卡片，点击打开 `IssueDetail(..., isPull=false)`；不添加通用 Linked Items 写按钮。
- [ ] 检查详情 LazyColumn 的锚点/key，新增卡片不得使评论定位偏移；运行相关测试。

### Task 4: Actions Debug Logging 重跑

**Files:**
- Create: `app/src/main/java/com/mobilegh/data/WorkflowRerun.kt`
- Modify: `app/src/main/java/com/mobilegh/data/GitHub.kt`
- Modify: `app/src/main/java/com/mobilegh/ui/screens/ActionsScreens.kt`
- Test: `app/src/test/java/com/mobilegh/data/WorkflowRerunTest.kt`

**Interfaces:**
- Produces: `WorkflowRerun.request(runId: Long, failedOnly: Boolean, debug: Boolean): ApiRequest`，至少暴露纯数据 `path: String` 与 `body: JsonObject?`，不依赖 Android。
- `GitHub.rerun(..., enableDebugLogging: Boolean = false)` 使用该请求构造器。

- [ ] 写四种组合失败测试：普通/失败任务 × debug 开关；debug=true 时 body 必须含 `enable_debug_logging=true`，false 保持现有空 body。
- [ ] 运行测试确认失败后实现请求构造器并接入 `Api.exec`。
- [ ] 在两个重跑确认入口提供默认关闭的“启用调试日志”选项，成功后沿用现有 reload。
- [ ] 运行 `WorkflowRerunTest` 与 `WorkflowDispatchTest`。

### Task 5: Actions Environment Deployment Review

**Files:**
- Create: `app/src/main/java/com/mobilegh/data/DeploymentReviews.kt`
- Create: `app/src/main/java/com/mobilegh/ui/screens/DeploymentReviewCard.kt`
- Modify: `app/src/main/java/com/mobilegh/data/Models.kt`
- Modify: `app/src/main/java/com/mobilegh/ui/screens/ActionsScreens.kt`
- Test: `app/src/test/java/com/mobilegh/data/DeploymentReviewsTest.kt`

**Interfaces:**
- `Run` 增加 `@SerialName("node_id") val nodeId: String? = null`。
- Produces: `DeploymentReview(environmentId, environmentName, canApprove, reviewers)` 与 `DeploymentReviewInput(workflowRunId: String, environmentIds: List<String>, comment: String?)`。
- Produces: `pendingDeploymentRequests(workflowRunNodeId: String): List<DeploymentReview>`、`reviewDeployments(input, approve: Boolean)`。

- [ ] 写解析失败测试：多环境、reviewer User/Team、无权限、空列表、null workflowRun；明确 Node ID 不得回退成数字 run ID。
- [ ] 写 mutation payload 失败测试：批准/拒绝操作名、`workflowRunId`、环境 ID 列表、空白 comment 转 null。
- [ ] 运行测试确认失败后实现 `node(id:) ... on WorkflowRun.pendingDeploymentRequests` 和公开 `approveDeployments` / `rejectDeployments`。
- [ ] Run 详情独立加载审批信息，在等待状态和已有轮询中刷新；支持多选环境、留言、批准、拒绝与二次确认。无 `nodeId` 或无权限时禁用写操作并说明原因。
- [ ] 运行新测试及 Actions 既有测试，确认普通运行详情不受审批 schema 错误影响。

### Task 6: Saved Replies 读取与光标插入

**Files:**
- Create: `app/src/main/java/com/mobilegh/data/SavedReplies.kt`
- Create: `app/src/main/java/com/mobilegh/ui/components/SavedRepliesPicker.kt`
- Modify: `app/src/main/java/com/mobilegh/ui/components/MarkdownEditor.kt`
- Modify: `app/src/main/java/com/mobilegh/ui/screens/ThreadParts.kt`
- Test: `app/src/test/java/com/mobilegh/data/SavedRepliesTest.kt`
- Test: `app/src/test/java/com/mobilegh/ui/components/SavedReplyInsertTest.kt`

**Interfaces:**
- Produces: `SavedReply(id,title,body)`、`SavedReplyPage(items,endCursor,hasNextPage)`。
- Produces: `savedReplies(after: String? = null): SavedReplyPage`，查询 `viewer.savedReplies(first:30,after:)`。
- Produces: `insertSavedReply(value: TextFieldValue, body: String): TextFieldValue`，替换选区并将光标放在插入文本末尾。

- [ ] 写分页/null/空列表解析失败测试，以及光标插入、选区替换、空回复测试。
- [ ] 运行测试确认失败后实现查询和共享插入函数。
- [ ] 在 `MarkdownToolbar` 增加可选保存回复入口，并确保 `MarkdownEditor`、fullscreen、`CommentComposerBar` 和 PR Review 编辑器均能打开同一 picker。
- [ ] Picker 支持搜索、本地过滤、分页加载、空状态及跳 GitHub Saved Replies 设置；不伪造公开创建/编辑/删除 mutation。
- [ ] 运行新测试及 `AttachmentInsertTest`、相关编辑器测试。

### Task 7: 系统文本/图片分享创建 Issue

**Files:**
- Create: `app/src/main/java/com/mobilegh/nav/ShareIntents.kt`
- Create: `app/src/main/java/com/mobilegh/data/PendingIssueShare.kt`
- Create: `app/src/main/java/com/mobilegh/ui/screens/ShareIssueScreen.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/mobilegh/MainActivity.kt`
- Modify: `app/src/main/java/com/mobilegh/nav/Nav.kt`
- Modify: `app/src/main/java/com/mobilegh/ui/AppRoot.kt`
- Modify: `app/src/main/java/com/mobilegh/ui/screens/NewIssue.kt`
- Test: `app/src/test/java/com/mobilegh/nav/ShareIntentsTest.kt`
- Test: `app/src/test/java/com/mobilegh/data/PendingIssueShareTest.kt`
- Test: `app/src/test/java/com/mobilegh/ui/screens/NewIssueDraftTest.kt`
- Modify: `app/src/androidTest/java/com/mobilegh/ui/FeatureFlowsTest.kt`

**Interfaces:**
- Produces: `IssueShare(text: String, attachments: List<SharedAttachment>)`；`SharedAttachment` 只含 cache 文件路径、MIME、显示名。
- Produces: `PendingIssueShare.save/ peek/ consume`，按 ID 一次性消费，登录与进程恢复期间不丢失。
- `Screen.ShareIssue(shareId)` 负责选仓库；`Screen.NewIssue(..., shareId: String? = null)` 消费预填内容。
- 纯文本 GitHub 链接仍优先走现有链接导航；非链接文本和图片进入建 Issue 流程。

- [ ] 写 Intent 解析失败测试：EXTRA_TEXT、EXTRA_STREAM、ClipData、拒绝不支持的 MIME、空分享、链接优先；Android 类型用可抽离的纯解析层测试。
- [ ] 写 store 一次性消费、覆盖/清理旧缓存、账号登出恢复测试；写分享预填与既有草稿优先级测试。
- [ ] 运行测试确认失败后实现 Manifest `text/plain` 与 `image/png/image/jpeg/image/gif` SEND，复制授权 URI 到专用 cache，校验现有 10 MB/MIME 限制。
- [ ] 实现原生仓库选择与 NewIssue 预填；文本进入正文，图片经现有附件上传并插入光标位置。模板替换正文前明确保留或丢弃分享正文，YAML 表单不错误注入 Markdown。
- [ ] 更新旧“拒绝图片/非链接文本”的仪器测试预期，并运行分享、附件和导航测试。

### Task 8: Opt-in 后台通知与工作时间

**Files:**
- Create: `app/src/main/java/com/mobilegh/data/BackgroundNotificationSettings.kt`
- Create: `app/src/main/java/com/mobilegh/service/NotificationPollingWorker.kt`
- Create: `app/src/main/java/com/mobilegh/ui/screens/NotificationScheduleSection.kt`
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/com/mobilegh/ui/screens/MiscScreens.kt`
- Modify: `app/src/main/java/com/mobilegh/data/Session.kt`
- Test: `app/src/test/java/com/mobilegh/data/BackgroundNotificationSettingsTest.kt`
- Test: `app/src/test/java/com/mobilegh/data/NotificationPollingTest.kt`

**Interfaces:**
- Produces: `WorkingHours(enabled,startMinute,endMinute,days)` 与 `allows(instant: Instant, zone: ZoneId): Boolean`，支持跨夜与星期集合。
- Produces: `NotificationPollDecision` 纯函数处理 enabled、工作时间、前台、generation、`(threadId,updatedAt)` 去重。
- Worker 使用独立 per-device preferences，不进入 SettingsBackup；最小周期 15 分钟、网络约束、唯一任务 REPLACE。

- [ ] 写失败测试： disabled、窗口内/外、跨夜、每天/自定义星期、DST、updatedAt 变化、重复通知、账号切换与前台抑制。
- [ ] 运行测试确认失败后实现纯策略和持久化设置。
- [ ] 添加明确 WorkManager 依赖，Worker 复用 `/notifications`、现有通知路由和系统 channel；点击通知打开对应原生页面，不能修改 GitHub 已读状态直到用户打开。
- [ ] 设置页增加 opt-in、通知权限、工作时间开关、每天/自定义、起止时间；仅在用户开启时请求权限和排程，关闭/登出取消任务。
- [ ] 运行新测试及 `NotificationBulkStopTest`、`WidgetStoreTest`、`SettingsBackupTest`。

### Task 9: PR 与快捷键小组件

**Files:**
- Create: `app/src/main/java/com/mobilegh/widget/PullRequestWidget.kt`
- Create: `app/src/main/java/com/mobilegh/widget/ShortcutWidget.kt`
- Modify: `app/src/main/java/com/mobilegh/widget/Widgets.kt`
- Modify: `app/src/main/java/com/mobilegh/data/WidgetStore.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/xml/pull_request_widget_info.xml`
- Create: `app/src/main/res/xml/shortcut_widget_info.xml`
- Test: `app/src/test/java/com/mobilegh/data/WidgetStoreTest.kt`

**Interfaces:**
- 新增 `WidgetStore.Kind.PullRequests` 与 `Shortcuts`；快照包含账号 generation，切换账号后不展示旧账号内容。
- PR 快照保存少量 open/assigned PR 的仓库、编号、标题、更新时间和 URL；快捷键复用首页 Shortcut 数据。
- 所有点击通过 `WidgetIntents.open()`，不另建导航机制。

- [ ] 写快照失败测试：空列表、数量上限、PR URL、快捷键空状态、账号隔离与按 Kind 刷新。
- [ ] 运行测试确认失败后实现 store 和两个 Glance 列表/空状态。
- [ ] 注册 receiver/meta-data，统一刷新和点击导航；没有快捷键时显示添加快捷键入口，不渲染空黑块。
- [ ] 运行 WidgetStore/现有小组件测试，并用 `appwidget` 或布局渲染检查可发现性。

### Task 10: `*.ghe.com` 企业深链与 Host 守卫

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/mobilegh/nav/Links.kt`
- Modify: `app/src/main/java/com/mobilegh/nav/IncomingLinks.kt`
- Modify: `app/src/main/java/com/mobilegh/MainActivity.kt`
- Test: `app/src/test/java/com/mobilegh/nav/LinksRoutingTest.kt`

**Interfaces:**
- Produces: 企业 URL 识别与 active-host 校验纯函数；`github.com` 和 gist 现有行为不变。
- 仅当前账号 API Host 对应链接 Host 时路由到既有 Screen；不匹配时保留链接并提示切换/登录对应企业账号，不向 github.com 发请求。

- [ ] 写失败测试：有效子域、伪造后缀 `ghe.com.evil.test`、大小写/端口、repo/issue/PR 路径、Host 不匹配及普通 GitHub 回归。
- [ ] 运行测试确认失败后实现严格 Host 后缀解析和 active-host 守卫。
- [ ] Manifest 添加 `*.ghe.com` https 深链；MainActivity 对不匹配账号给出明确提示，不错误路由。
- [ ] 运行全部 Links/Nav/IncomingLinks 测试。

### Task 11: 全量验证与实际界面验收

**Files:**
- Modify only files required by failures found during verification.

- [ ] 运行 `git diff --check` 和完整 `./gradlew testDebugUnitTest`；记录测试类、测试数与失败数。
- [ ] 运行 `./gradlew assembleRelease`，确认 Release 构建成功且版本仍为 `0.40.3 (52)`。
- [ ] 覆盖安装 arm64 Release，保留账号数据；逐项实测能由当前测试账号访问的 Issue 类型/层级、调试重跑、Saved Replies、分享建 Issue、通知设置、小组件和企业链接错误 Host 状态。
- [ ] Deployment Review 仅在存在真实 pending environment 时执行写审批；无数据时验证只读/空状态并明确标注未实测写操作，不制造审批。
- [ ] 检查崩溃日志与关键页面截图，确认增强接口失败不会阻塞核心页面。
- [ ] 汇总已实现、实测、受数据/权限限制未实测的项目；不提交、不推送、不改版本、不发版。
