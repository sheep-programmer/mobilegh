# 发版前回归

把上个发布版新增的功能和本次变更都列入检查；分别记录 **真实 GitHub + App**、**模拟器测试数据** 和 **未覆盖**。单元测试全绿不代表 GitHub 权限、套餐限制或真实写入已验证。

| 范围 | 发版前检查 | 验证条件 |
| --- | --- | --- |
| Actions 设置 | 私有仓库与公开仓库分别检查适用的权限选项；切换设置分区、滑到底、保存后重新读取；Secret 和 Variable 创建、修改、删除；Runner 下载、注册与缓存限制 | 在 `sheep-programmer/mgh-create-test` 跑 `ActionsLiveSmokeTest`；用 `ReleaseLiveSmokeTest` 验证离线 Runner 删除、缓存删除、失败任务重跑和全部重跑。组织策略需有专门测试仓库 |
| Issue 与 PR | 创建父子 Issue、读回关系、完成子任务、返回父任务确认进度、解绑；另测 Issue 类型、关闭关联、部署审批和 PR 文件已阅 | 父子关系和 PR 已阅用真实仓库；组织 Issue 类型、待审批部署需对应仓库或流程 |
| 关注与 Star | 非成员公开仓库的 Watch 名单和游标翻页；订阅设置独立打开；不把 Star 名单不可见、真实空名单和网络错误混为一谈 | `RepositoryWatchersTest` 联网测公开仓库和自己的仓库，另用本地数据测缺权限或限流 |
| 整合与下载 | 仓库导入与恢复、系统分享建 Issue、通知调度、组件、镜像分开配置、`.log` 导出；下载暂停续传、摘要校验、Actions 产物 APK 识别 | 单元 + Android 测试；续传需要先启动 `tools/download_test_server.py`，证书须与 debug 信任配置一致 |
| 阅读与界面 | 更新公告读到末尾、原生图片与媒体验证、视频点播放不全屏、README 图片外链、离线恢复、正文翻译、悬浮底栏、窄屏换行 | Android 测试；离线切换需模拟器；真实翻译需 ML Kit 模型。在 GitHub 原图返回 404 时，正确结果是说明源图不可用，不承诺能显示 |

先运行 `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`。用 `adb install -r` 安装匹配 ABI 的 debug APK 和测试 APK，不清除现有账号数据。全量设备测试使用 `adb shell am instrument -w -r … com.mobilegh.test/androidx.test.runner.AndroidJUnitRunner`，保存输出并检查 `FAILURES` 与跳过项，不能只看 `adb` 的退出码。

联网测试需主动启用。为 `actionsLiveFixture`、`issuesLiveFixture` 传入本轮唯一的 `MOBILEGH_SMOKE_<时间戳>`；`watchersLive` 传 `true`。仅在受控模拟器的应用私有缓存中传入 `actions-live-credential`、`issues-live-credential`、`watchers-live-credential`，不要作为命令行参数或日志输出。测试读取后删除凭据，结束后核对临时数据已清理、Actions 设置已恢复。`translationSmoke` 与 `offlineSmoke` 传 `true` 开启对应设备测试。

`ReleaseLiveSmokeTest` 只接受个人私有测试仓库的 `mobilegh-smoke-<时间戳>` 分支。准备未合并的临时 PR（含 `.github/workflows/mobilegh-smoke.yml`）、仅首轮失败的运行、以临时分支名为 key 的缓存、同名离线 Runner；传入 `releaseLiveBranch`、`releaseLivePull`、`releaseLiveRun`、`releaseLiveRunner`。四项各用应用私有缓存中的一次性 `release-live-{pull,run,cache,runner}` 凭据。建议先重跑，再测缓存删除，避免重跑重建缓存。结束后核对 `main` 没变，再关闭临时 PR、移除临时分支、运行记录与剩余资源。`savedRepliesLive=true` 使用 App 已登录的测试账号，只读，不打印回复；缺合适账号时跳过。

## v0.40.4 后续改动：2026-11-18 回归记录

- 修复 PR 已阅后的 debug 构建通过；JVM 单元测试 1,274 项通过。Android 全量发现 244 项，实际通过 200 项、失败 0 项、按条件跳过 44 项（截图采集 34、需另行授权的 2FA 3、需单独准备的联网夹具 7）。补充联网测试结果单列，不把跳过项计为通过。
- **真实 GitHub + App**：在个人私有测试仓库切换 Actions 分区并滚动，保存、回读和还原 Actions 策略，创建、覆盖或编辑、删除 Secret 和 Variable，读取 Runner 下载信息、申请注册令牌，检查缓存页和套餐限制提示。父子 Issue 实测创建、关联、关闭子任务、回读进度和解绑。测试结束后临时 Issue、Secret 和 Variable 均为 0，Actions 策略已还原。
- **公开仓库**：用非成员账号实测 Watch 名单和 GraphQL 游标翻页，个人订阅保持独立；Star 名单不可见时保留仓库人数，不显示成零人。Fork 的四种 REST 排序和名称 GraphQL 排序分别请求真实 GitHub；五种排序之间切换和名称翻页由 Android 测试验证。
- **设备回归**：验证断网恢复、ML Kit 翻译和恢复原文、更新公告滚动、README 图片交互、视频默认内联播放、下载和日志操作。`lobehub/lobe-icons` PR #469 截图中的八张 Recraft 原图均返回 HTTP 404，不计为 App 加载成功。
- **补充真实 GitHub 验证**：Actions 从首轮失败重跑到第二次成功，再全量重跑到第三次成功。App 删除真实缓存和已注册的离线 Runner 后，列表刷新并通过 GitHub 回读确认。临时 PR #11 的文件已阅首测发现 GraphQL 把输入对象作为字符串发出；改为 JSON 对象后，设置和取消已阅都从 App 操作并回读通过。
- **清理结果**：测试仓库 `main` 未改；临时 PR #11 已关闭、未合并；删除了临时分支、运行记录和重跑生成的剩余缓存。Runner、缓存、Secret 和 Variable 数量均为 0，Actions 策略还原为允许全部。
- **未完成联网验证**：待审批部署、真实自托管 Runner 执行任务、组织级策略、组织 Issue 类型没有对应夹具；Saved Replies 因模拟器未登录指定测试账号，未验证真实成功读取。模拟测试不替代这些联网结果。
