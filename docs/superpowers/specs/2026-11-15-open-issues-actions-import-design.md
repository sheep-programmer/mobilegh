# MobileGH #32 Actions 设置与 #31 Import 仓库设计

## 目标与边界

- #32：仓库管理员可在原生 Compose 页面管理「通用权限、Secrets、Variables、Runners、Caches」。只读账号不显示写入口；组织策略、权限和仓库可见性导致的错误必须原样进入错误状态，不能假报成功。
- #31：从公开或凭据可访问的 HTTPS Git 仓库创建未初始化目标仓库，在设备端迁移完整提交历史、所有分支和标签，并提供可恢复的任务状态。
- 保留现有所有修改与未跟踪文件，沿用 Kotlin、Compose、现有 `Api`/`GitHub`/`Navigator`/`RepoWrite` 架构，不使用 WebView 代替页面。
- 不实现已于 2024-04-12 停止服务的 Source Imports `/import` API，不伪造 GraphQL import mutation，不把 Fork 或模板当作通用导入。
- 所有行为严格 RED→GREEN；只做本地修改和验证，不提交、不推送、不打标签、不改版本、不发布、不评论或关闭 Issue。

## Actions 设置

### 导航与权限

新增可序列化 `Screen.ActionsSettings(owner, name, section)`，`section` 为 General、Secrets、Variables、Runners、Caches。Actions 顶栏仅对 `repo.permissions?.admin == true && !repo.archived` 显示入口。`repoHomeOf` 返回对应仓库首页，进程恢复覆盖所有 section。

页面顶部切换五组。每组分别加载、显示 loading/error/empty/content，写操作成功后只刷新受影响数据并调用 `RepoWrite.changed(..., ACTIONS)`。owner、repo 由现有仓库路径helper编码；secret/variable 名称按单个路径段使用 `Api.q(...).replace("+", "%20")` 编码，不能用会保留 `/` 的全路径编码。列表循环读取所有页，Variables 每页 30，其余每页 100。

### 通用权限

读取并更新：

- 是否启用 Actions；`allowed_actions = all/local_only/selected`；`sha_pinning_required`。
- `selected` 时编辑 `github_owned_allowed`、`verified_allowed` 和逐行 `patterns_allowed`。`patterns_allowed` 仅在公开仓库展示。
- 私有仓库 workflow access：`none/user/organization`。
- artifact/log retention：整数天数，显示 `maximum_allowed_days`；本地只拒绝空值、非整数、非正数和超过上限，其他服务端约束交给 API。
- fork PR contributor approval：三种公开枚举。
- 私有仓库 fork PR workflows 的四个布尔项。
- 默认 workflow token 的 read/write 与 `can_approve_pull_request_reviews`。409 等组织策略错误不清除用户输入、不显示成功。

各端点独立读取；私有专用端点在公开仓库不发请求。某一项不可读时保留其他项，并在该组显示失败与重试。

### Secrets

- 列出仓库 secrets 和组织授予当前仓库的 secrets；只显示名称与 created/updated 时间，组织 secrets 只读。
- 新建或覆盖仓库 secret：输入名称和值，先取 `public-key` 的 `key_id/key`，再用 LibSodium sealed box 加密，PUT `{encrypted_value,key_id}`。201/204 都算成功。
- 删除仓库 secret 需确认；值永不读取回显。
- 明文只存在于当前对话框的 `remember` 状态和本次调用的局部变量；不进入 `rememberSaveable`、导航、任务 JSON、普通持久化、日志或异常消息。加密失败只显示通用失败，不附带明文。
- 不为文档未给出的名称规则虚构服务端 schema；仅拒绝空名称和空值。

### Variables

- 列出 name/value/created/updated，值可复制。
- 新建 name/value；编辑允许改值和重命名，PATCH 只包含发生变化的字段；删除需确认。
- 仅拒绝空名称；值允许为空。保留服务端真实校验错误。

### Runners

- 分页列出 id、名称、OS、status、busy、labels、ephemeral；支持名称过滤。仓库 runner list 响应不提供已安装 runner 应用版本，因此不展示或虚构 version。
- 删除 runner 需确认并处理 422。
- 注册操作请求 registration-token，token 与 expires_at 仅在当前对话框中短暂展示、可复制，关闭即丢弃；不持久化。提供 downloads 列表供用户获取对应 OS/架构包，不生成或保存含 token 的脚本。
- 不实现需要桌面 runner 环境的实际安装。

### Caches

- 显示 active cache 数量与字节数；按 key/ref 过滤并分页列出 ref/key/version/时间/大小。
- 支持按 id 删除、按 key（可带 ref）删除，均需确认；按 key 删除后使用返回列表刷新，不把它当作 204。
- 读取并更新 `max_cache_retention_days` 与 `max_cache_size_gb`，分别使用 `days` 以外的准确字段；PUT 204 为成功。数值只拒绝空、非整数和非正数。

## Import 仓库

### 输入与目标

- 新增 `Screen.ImportRepo`，在仓库列表和新建仓库页提供入口。
- 接受严格 HTTP/HTTPS Git URL；去除片段，不允许 URL userinfo。默认只提交 HTTPS，HTTP 显示明确的不安全提示且必须用户确认。SSH、本地路径和 `file://` 不在本功能承诺范围。
- 私有源使用可选 username/password-or-token；密码字段只使用 `remember`，不得进入 SavedState、任务或日志。
- 目标 owner 从当前用户与可创建仓库的组织中选择，输入名称、描述、private；调用现有 `GitHub.createRepo(..., autoInit = false)`。
- 目标 Git URL 优先使用创建响应的 `clone_url`，缺失时按 `Net.loginBase` 与 owner/repo 构造。目标认证使用创建任务时绑定的 GitHub 会话凭据，绝不向源站发送。

### 迁移引擎

使用 Eclipse JGit 的 bare、全历史 clone：`setBare(true)`、`setCloneAllBranches(true)`，不设 depth、不初始化 submodule。push 仅构造显式 `refs/heads/*` 与 `refs/tags/*` refspec，不推送 `refs/pull/*` 等宿主内部 refs。

首次推送不 force。已存在且内容相同的目标 ref 可视为 up-to-date；non-fast-forward、拒绝或部分成功必须显示为未完成，不能自动覆盖目标新增历史。源 HEAD 指向分支时，在分支全部推送成功后通过公开仓库 PATCH 将目标 `default_branch` 更新为对应分支；该步骤失败也保留部分完成状态并允许重试。

迁移不包含 issues、PR、releases、wiki、LFS 对象、GitHub 设置或 submodule 内容。UI 在开始前明确说明这些边界。完整 Git 历史包含可达 Git 对象；源服务未通过 Smart HTTP 暴露的对象不能承诺迁移。

### 任务、生命周期与恢复

新增非敏感 `ImportTask` store，字段包括 id、绑定 account login、sourceUrl、destination owner/name/cloneUrl/defaultBranch、visibility、phase、message、created/updated 时间。phase 至少区分 Creating、CreationUnknown、Cloning、Pushing、UpdatingDefaultBranch、Completed、Interrupted、Failed、Cancelled。目标创建响应成功解析后立即同步保存目标信息，再开始 clone；因此后续失败绝不会通过“重试”再创建同名仓库。

创建 POST 还可能已获服务端接受、但响应丢失或 201 body 无法解析。此类结果必须进入 `CreationUnknown`，不得直接再次 POST。重试前先通过公开仓库读取 reconciliation：若目标不存在，才允许重新创建；若存在，只有它匹配本次请求的 owner/name/visibility、具备目标写权限且为空仓库时才可采用并同步持久化，否则要求更换目标，绝不向不确定归属的仓库推送。reconciliation 本身失败则保留 `CreationUnknown`。进程在 POST 后、状态写入前死亡也走同一恢复分支；UI 必须展示“创建结果待确认”，不能宣称未创建。

采用专用 dataSync 前台服务配合应用内单任务 executor。服务只接收 task id，不接收源或目标凭据。活跃任务在进程重启后转换为 Interrupted；临时 clone 目录按 task id 隔离，启动新尝试前清理同任务旧目录，完成、取消或明确删除任务时清理。磁盘不足和取消都转成可见状态，删除临时目录失败不覆盖原始错误。

每个任务记录 account login；恢复时账号不匹配则禁止执行。目标 token 每次执行从当前绑定会话取得，不写入任务。私有源在进程恢复、取消后重试或凭据缺失时必须重新输入；UI 明确提示。任务列表可打开目标仓库、重试、取消、删除已完成/失败记录。

引擎通过 `ProgressMonitor` 更新非敏感阶段/百分比，并用可取消 transport/monitor 传播取消；阻塞 Git 操作运行在 IO dispatcher。不能安全中断时取消请求先停止状态推进，待调用返回后清理，不宣称瞬间停止。

### 网络与兼容验证

JGit 只处理用户明确提交的源 URL 和创建响应/受信配置派生的目标 URL。认证按源、目标分别创建 `CredentialsProvider`，不得跨 host 复用。所有异常展示前经过 URL userinfo 与凭据脱敏。

在依赖进入生产前先做编译/单元 RED，再完成 JVM 本地多分支、多标签、默认分支和拒绝 non-fast-forward 集成测试；Android instrumentation 验证真实 native 加密与本地 HTTP Git 迁移。正常 Release（无测试 keep 配置）验证 R8、arm64-v8a/armeabi-v7a/x86_64 native 库、安装与冷启动。未通过这些验证前不宣称 Android/JGit/LazySodium 兼容。

## 测试与交付

- 纯请求构造测试覆盖所有方法、路径、查询和 JSON，含特殊名称、分页、增量 PATCH 和私有-only 行为。
- loopback HTTP 测试覆盖成功、204、403/404/409/422、写后解析失败不盲重试。
- 导航恢复、repo home、i18n、账号切换、敏感信息不序列化测试。
- JVM Git 集成与 Android 真实加密/Git 测试；Compose 设备测试覆盖管理员入口、五组、错误状态、对话框明文清理和 Import 恢复。
- 最终运行完整 JVM、相关 instrumentation、正常 Release、`git diff --check`，确认 versionCode 52/versionName 0.40.3。执行 fresh-context 全量正确性审查，所有可行动正确性问题（包括有代价或标为 Minor 的）均修复后再复验。
