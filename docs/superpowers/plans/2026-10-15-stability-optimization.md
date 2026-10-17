# Stability Optimization Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this approved optimization batch. User explicitly opts out of tests and has not authorized commits or publishing.

**Goal:** 修复已确认的登录、写入与分页问题，改善常用列表和通知状态一致性。

**Architecture:** 保留现有 Compose/Entry/Session 架构和独立认证客户端。请求以操作序号约束状态发布，网络响应以外层所有权保证关闭；不根据网络失败推断远端写入未发生。

**Tech Stack:** Kotlin, Compose, coroutines, OkHttp, SharedPreferences.

**Spec:** 本次对话中用户批准的优化清单；现有代理方案保持不变。

## Global Constraints
- 不运行测试、设备或网络探测；仅编译和静态检查。
- 不 commit、tag、push、发版，不改变版本号和签名。
- 保留工作区已有代理与中文网络错误修改；不向镜像发送认证凭据。
- 账号切换后，旧请求不得覆盖新会话或写入新账号的本地状态。

## Review Focus
- 旧 Token 非空但失效时，重新登录必须安装新凭据。
- 提交成功但响应丢失时，必须保留新建分支。
- 取消旧刷新后，其 finally 不得清除新请求的 busy 状态。
- 刷新失败和追加失败分别重试原操作。
- 通知的本机状态按账号保存；桌面隐私偏好不得泄露标题。

### Task 1: OAuth session intent
- [x] 区分登录/添加账号、重新授权和审批专用授权；审批专用才限制当前身份。
- [x] 在异步边界验证发起会话；登录/重授权安装核对后的新凭据。
- [x] 静态检查取消、失败重试和账号切换路径。

### Task 2: Safe repository writes and HTTP ownership
- [x] 文件提交异常时保留新分支并提示检查结果，不自动删除。
- [x] Api.call 在进入可取消的 dispatcher 前建立响应的 finally 关闭保障。

### Task 3: Paging and loading correctness
- [x] Loader/Pager 操作序号屏蔽已取消任务的状态收尾及结果。
- [x] Pager 暴露 retry()，分别重试刷新、追加或加载全部；通用列表使用该入口。

### Task 4: Daily navigation and repository feedback
- [x] 只处理新的 Tab 重选事件，返回列表不消费历史滚顶事件。
- [x] Star 状态查询拆分加载/失败，提供重试并纳入下拉刷新。
- [x] 筛选空态提供清除筛选而不是新建入口。

### Task 5: Local notification consistency and widget privacy
- [x] 按账号持久化本机未读/已完成状态，不保存未提交的等待撤销操作。
- [x] 小组件尊重本机未读覆盖；本地变更触发同步。
- [x] 增加小组件隐藏通知详情偏好并更新展示。

### Task 6: Verification
- [x] 静态审阅实际 diff，检查账号边界、取消语义和凭据处理。
- [x] 运行 compileDebugKotlin / compileReleaseKotlin 与 git diff --check。
- [x] 如实报告已完成内容和未验证的真机/API行为，不声称运行验证通过。

## Execution record
- Ruling: 在新建本地分支 fix/stability-optimization 中保留原有未提交改动，不创建基线提交或额外工作树；本次禁止测试和提交优先于技能默认流程。
- 静态复核发现并处理：审批设备发布的取消/账号检查窗口、Star 旧查询结果回放、撤销窗口污染持久小组件快照、失去离线快照。
- Verification: compileDebugKotlin + compileReleaseKotlin → BUILD SUCCESSFUL；git diff --check → 无输出。未运行测试、App 或真实 API。
- Deferred: 分支/标签超过 100 项的分页、账号切换缓存 IO、OAuth 进程死亡后的凭据恢复，以及全量图标按钮的精细动作名称；本批仅为常用图标及登录敏感操作补语义。
