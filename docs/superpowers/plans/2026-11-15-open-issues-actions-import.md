# MobileGH Open Issues Actions & Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完整实现 #32 的五组 Actions 设置和 #31 的原生可恢复 Git 仓库导入。

**Architecture:** Actions 使用可测试的 REST 请求构造、DTO 与仓库级 Compose 设置页；Secrets 通过 LibSodium sealed box 写入。Import 使用非敏感任务存储、前台服务与 JGit bare full-history clone + 显式 branch/tag push，目标创建和迁移分阶段持久化。

**Tech Stack:** Kotlin 2.3.21、Jetpack Compose/Material 3、kotlinx.serialization、JUnit4、JGit、LazySodium Android、Android instrumentation、R8。

**Spec:** `docs/superpowers/specs/2026-11-15-open-issues-actions-import-design.md`

## Global Constraints

- 保留现有所有修改/未跟踪文件；BASE `c8ca28e9d6774f004fd2f7c971dc35fc4331f67b`。
- 每个新生产行为先写测试、运行并确认预期 RED，再最小实现、跑 focused 与回归；日志保存在本计划 SDD workspace。
- 不使用 retired `/import`、私有/虚构 API 或 WebView 替代页面。
- Secrets 与私有源凭据不得进入日志、普通持久化、导航状态、WorkManager inputData 或错误消息。
- 不提交、不推送、不打标签、不改版本、不发布、不评论或关闭 Issue；版本固定 52 / 0.40.3。
- 所有可行动正确性发现都必须修复，不因成本或 Minor 标签延后。

## Review Focus

- 部分成功：目标已创建或部分 refs 已推送时，重试不得重建目标或 force 覆盖；由 Task 7 `partialPushRemainsRecoverable` 与 Task 8 `resumeReusesDestination` 固定。
- 秘密生命周期：配置变化、进程恢复和异常不能保存/泄露明文；由 Task 2 `plaintextNeverAppearsInFailure` 与 Task 8 `interruptedPrivateSourceRequiresCredentials` 固定。
- 服务端策略差异：403/404/409/422 不得显示成功；由 Task 1 请求/loopback 测试和 Task 3 `workflowPolicyConflictStaysVisible` 固定。
- Git refs：只推 branches/tags，保护默认分支和 non-fast-forward；由 Task 7 本地真实仓库集成测试固定。
- Android 原生依赖：R8/ABI 与真实加密/Git transport 不能由 JVM 测试替代；由 Task 9 instrumentation、Release 包内容与设备测试固定。

---

### Task 1: Actions REST 合约与数据层

**Files:**
- Create: `app/src/main/java/com/mobilegh/data/ActionsSettings.kt`
- Modify: `app/src/main/java/com/mobilegh/data/Models.kt`, `app/src/main/java/com/mobilegh/data/GitHub.kt`
- Test: `app/src/test/java/com/mobilegh/data/ActionsSettingsTest.kt`

**Interfaces:**
- Produces: `ActionsRequests` 纯请求构造；`ActionPermissions`、`ActionSecret`、`ActionVariable`、`ActionRunner`、`ActionCache` 等 DTO；`GitHub` 五组 suspend 读取/写入方法。
- Consumes: 现有 `Api.get/send/sendWrite/exec`、单路径段编码 `Api.q(...).replace("+", "%20")`、`RepoWrite.changed(..., ACTIONS)`。

- [ ] **Step 1:** 写失败测试，逐项断言 permissions/access/retention/fork/selected/workflow、secrets、variables、runners、cache usage/list/limits/delete 的 method/path/query/body；覆盖 Variables `per_page=30`、特殊名称编码、变量增量 rename、cache delete-by-key 返回列表。
- [ ] **Step 2:** 运行 `./gradlew :app:testDebugUnitTest --tests com.mobilegh.data.ActionsSettingsTest`，确认因缺少类型/方法编译 RED，保存日志。
- [ ] **Step 3:** 实现最小 DTO、请求构造和 GitHub facade；所有列表遍历完整分页，写入成功失效 Actions 缓存，204 不解析响应体。
- [ ] **Step 4:** 加 loopback 204 与 403/404/409/422 测试，先确认缺失错误/成功语义 RED，再修正；禁止对写后解析失败盲重试。
- [ ] **Step 5:** 重跑 focused 及 `WorkflowRerunTest`、`DeploymentReviewsTest`、`GraphQLForceTest`，确认 GREEN 并记 ledger；不提交。

### Task 2: Secrets sealed-box 加密

**Files:**
- Create: `app/src/main/java/com/mobilegh/data/ActionSecretCrypto.kt`
- Modify: `app/build.gradle.kts`, `gradle/libs.versions.toml`, `app/proguard-rules.pro`（仅在真实 R8 证据要求时）, `app/src/main/java/com/mobilegh/data/ActionsSettings.kt`
- Test: `app/src/test/java/com/mobilegh/data/ActionSecretCryptoTest.kt`, `app/src/androidTest/java/com/mobilegh/data/ActionSecretCryptoDeviceTest.kt`

**Interfaces:**
- Produces: `fun interface ActionSecretSealer { fun seal(plaintext: ByteArray, publicKeyBase64: String): String }`；`LazySodiumActionSecretSealer`；写入只接收局部明文与公钥响应。
- Consumes: Task 1 secret public-key 与 PUT 合约。

- [ ] **Step 1:** 先写引用 `ActionSecretSealer` 的 JVM/Android 测试：非空密文、Base64、真实设备生成密钥对可解密、错误公钥/加密失败不包含明文。
- [ ] **Step 2:** 运行 JVM 测试确认缺少接口/实现 RED；添加明确版本 LazySodium 后实现最小 sealer。
- [ ] **Step 3:** 重跑 JVM focused GREEN；执行 `connectedDebugAndroidTest` 的真实加解密，若 native/ABI 失败先定位依赖而非改测试。
- [ ] **Step 4:** 用假 sealer 验证 PUT 只含 encrypted_value/key_id，明文不出现在失败信息、task/navigation JSON。
- [ ] **Step 5:** 跑 Actions 数据层回归与 `assembleDebugAndroidTest`，记 GREEN；不提交。

### Task 3: General 设置、导航与管理员入口

**Files:**
- Create: `app/src/main/java/com/mobilegh/ui/screens/ActionSettingsGeneral.kt`
- Modify: `app/src/main/java/com/mobilegh/nav/Nav.kt`, `app/src/main/java/com/mobilegh/ui/AppRoot.kt`, `app/src/main/java/com/mobilegh/ui/screens/ActionsScreens.kt`, `app/src/main/assets/i18n/en.tsv`
- Test: `app/src/test/java/com/mobilegh/nav/NavRestoreTest.kt`, `app/src/test/java/com/mobilegh/nav/RepoHomeTest.kt`, `app/src/androidTest/java/com/mobilegh/ui/ActionSettingsUiTest.kt`, `app/src/test/java/com/mobilegh/i18n/I18nTest.kt`

**Interfaces:**
- Produces: `Screen.ActionsSettings(owner,name,section)`、`ActionsSettingsScreen`、General section；admin/non-archived 入口。
- Consumes: Task 1 General API。

- [ ] **Step 1:** 写五 section 序列化 round-trip、repo home、admin 入口与 General loading/error/content 测试；先运行确认路由/入口 RED。
- [ ] **Step 2:** 实现路由、ScreenHost、入口和五组切换骨架，保持非管理员不可见。
- [ ] **Step 3:** 先写并运行 General 编辑 RED：selected patterns 仅公开、private-only 请求、retention 上限、workflow 409 保留输入且不 toast 成功；再实现独立加载与增量写入。
- [ ] **Step 4:** 补齐英文翻译，运行 navigation/i18n/Compose focused GREEN。
- [ ] **Step 5:** 跑现有 Actions 与导航回归并记 ledger；不提交。

### Task 4: Secrets 与 Variables 页面

**Files:**
- Create: `app/src/main/java/com/mobilegh/ui/screens/ActionSecretsVariables.kt`
- Modify: `app/src/main/java/com/mobilegh/ui/screens/ActionSettingsGeneral.kt`（仅 section host）、`app/src/main/assets/i18n/en.tsv`
- Test: `app/src/androidTest/java/com/mobilegh/ui/ActionSecretsVariablesUiTest.kt`, `app/src/test/java/com/mobilegh/data/ActionsSettingsTest.kt`

**Interfaces:**
- Consumes: Task 1 CRUD、Task 2 sealer、Task 3 section host。
- Produces: secret 写-only 新建/覆盖/删除与组织只读列表；variable 新建/改值/重命名/删除。

- [ ] **Step 1:** 写 UI RED：secret 值不可回显、组织 secret 无编辑/删除、关闭/成功后明文状态清空、201/204 都成功；variable 空 value 合法、rename 只 PATCH delta。
- [ ] **Step 2:** 实现两组的独立 loading/error/empty/content、确认对话框与账号保护写操作。
- [ ] **Step 3:** 运行设备 focused，确认 secret 明文不进入 SavedState/日志，服务端错误不清空表单。
- [ ] **Step 4:** 运行数据层、i18n、Compose 回归 GREEN；不提交。

### Task 5: Runners 与 Caches 页面

**Files:**
- Create: `app/src/main/java/com/mobilegh/ui/screens/ActionRunnersCaches.kt`
- Modify: `app/src/main/java/com/mobilegh/ui/screens/ActionSettingsGeneral.kt`（section host）、`app/src/main/assets/i18n/en.tsv`
- Test: `app/src/androidTest/java/com/mobilegh/ui/ActionRunnersCachesUiTest.kt`, `app/src/test/java/com/mobilegh/data/ActionsSettingsTest.kt`

**Interfaces:**
- Consumes: Task 1 runner/cache API 与 Task 3 host。
- Produces: runner 列表/过滤/注册 token/downloads/删除；cache usage/过滤/两种删除/limits。

- [ ] **Step 1:** 写 UI RED：runner token 关闭即丢失且不持久化、422 可见；cache 按 key 删除解析列表、按 id 204、两个 limit 字段不混用。
- [ ] **Step 2:** 实现 runner 与 cache 页面和确认流程，所有 token 仅 `remember`，敏感字符串不写日志。
- [ ] **Step 3:** 跑设备 focused 与数据层回归；覆盖空列表、分页、过滤和写后刷新。
- [ ] **Step 4:** 跑五组 Compose 汇总与 i18n GREEN，记 ledger；不提交。

### Task 6: Import 输入、目标创建与非敏感任务存储

**Files:**
- Create: `app/src/main/java/com/mobilegh/data/RepositoryImport.kt`, `app/src/main/java/com/mobilegh/data/ImportTasks.kt`
- Modify: `app/src/main/java/com/mobilegh/data/Models.kt`（仅确有需要）
- Test: `app/src/test/java/com/mobilegh/data/RepositoryImportTest.kt`, `app/src/test/java/com/mobilegh/data/ImportTasksTest.kt`

**Interfaces:**
- Produces: `ImportSource.parse`、`ImportTask/ImportPhase`、`ImportTasks` persistence/recovery seam、destination-created transition。
- Consumes: `GitHub.createRepo(... autoInit=false)`、`Repo.cloneUrl`、`Net.loginBase`、Session account identity。

- [ ] **Step 1:** 写 RED：严格 URL/userinfo/HTTP 确认、默认 repo 名、目标创建 autoInit=false、创建后立即记录 destination、malformed-201/丢失响应进入 CreationUnknown 且重试先 reconciliation、凭据不序列化、活跃任务重启变 Interrupted。
- [ ] **Step 2:** 实现纯解析、任务 DTO/store 和创建协调器；失败在创建前不生成虚假目标，创建后不得回滚成“未创建”；不确定创建只有在目标不存在时重新 POST，或匹配本次请求、可写且为空时采用目标，其他情况禁止推送。
- [ ] **Step 3:** 写并运行账号隔离、损坏记录降级、取消/删除清理语义 RED→GREEN。
- [ ] **Step 4:** 跑 focused 与 Downloads/Session/Nav 回归 GREEN；不提交。

### Task 7: JGit 全历史迁移引擎

**Files:**
- Create: `app/src/main/java/com/mobilegh/data/GitRepositoryImporter.kt`
- Modify: `app/build.gradle.kts`, `gradle/libs.versions.toml`, `app/proguard-rules.pro`（仅真实 R8 证据要求时）
- Test: `app/src/test/java/com/mobilegh/data/GitRepositoryImporterTest.kt`, `app/src/androidTest/java/com/mobilegh/data/GitRepositoryImporterDeviceTest.kt`

**Interfaces:**
- Produces: `GitRepositoryImporter.cloneAndPush(task, sourceCredentials?, destinationCredentials, workspace, progress): ImportTransferResult`；只传显式 branch/tag refspec，返回源默认分支与逐 ref 结果。
- Consumes: Task 6 task/destination，JGit CredentialsProvider/ProgressMonitor。

- [ ] **Step 1:** 用本地真实源/目标 bare 仓库写集成 RED：多提交、多分支、 annotated/lightweight tags、非 main 默认分支；断言完整 SHA 可达且无 refs/pull。
- [ ] **Step 2:** 添加 JGit 并实现 bare clone-all/full-history、显式 refspec push；先跑 JVM focused GREEN。
- [ ] **Step 3:** 先写 RED 再实现 non-fast-forward 不 force、相同 ref 幂等、部分 push 可重试、default_branch 更新失败保留状态、取消/磁盘异常清理。
- [ ] **Step 4:** 运行 Android 本地 HTTP 源/目标 instrumentation，验证真实 transport 与凭据 host 隔离；失败先定位 Android/JGit 根因。
- [ ] **Step 5:** 跑 JVM/Git/设备 focused GREEN，记录不迁移 LFS/wiki 等明确边界；不提交。

### Task 8: Import 页面、前台执行与恢复

**Files:**
- Create: `app/src/main/java/com/mobilegh/ui/screens/ImportRepoScreen.kt`, `app/src/main/java/com/mobilegh/service/ImportService.kt`
- Modify: `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/mobilegh/App.kt`, `app/src/main/java/com/mobilegh/nav/Nav.kt`, `app/src/main/java/com/mobilegh/ui/AppRoot.kt`, `app/src/main/java/com/mobilegh/ui/screens/ReposScreens.kt`, `app/src/main/assets/i18n/en.tsv`
- Test: `app/src/test/java/com/mobilegh/nav/NavRestoreTest.kt`, `app/src/androidTest/java/com/mobilegh/ui/ImportRepoUiTest.kt`, `app/src/androidTest/java/com/mobilegh/service/ImportServiceTest.kt`, `app/src/test/java/com/mobilegh/i18n/I18nTest.kt`

**Interfaces:**
- Consumes: Task 6 store/coordinator、Task 7 engine。
- Produces: `Screen.ImportRepo`、输入/任务列表/重试/取消/删除、dataSync 前台通知和进程恢复。

- [ ] **Step 1:** 写导航/i18n/UI RED：两个入口、迁移范围提示、私有字段不 Saveable、`resumeReusesDestination`、`interruptedPrivateSourceRequiresCredentials`、账号不匹配禁止恢复。
- [ ] **Step 2:** 实现页面与服务注册；intent 只含 task id，账号/目标 token 在执行时绑定，源凭据每次重新输入。
- [ ] **Step 3:** 写服务 RED 再实现单任务执行、通知、取消、进程重启 Interrupted、临时目录清理和完成打开目标。
- [ ] **Step 4:** 运行真实设备 UI/service/process-recreation focused，核对任务文件与日志无私有源密码。
- [ ] **Step 5:** 跑 Import 全套与现有 Downloads/Nav/i18n 回归 GREEN；不提交。

### Task 9: 全量回归、Release 与 fresh-context 审查

**Files:**
- Modify: 仅审查发现对应的现有文件和测试
- Test: 全部 JVM、相关 Android instrumentation、正常 Release

**Interfaces:**
- Consumes: Tasks 1–8 全部公开接口。

- [ ] **Step 1:** 运行完整 `testDebugUnitTest`，汇总 suite/test/failure/error/skipped 数；任何失败逐项 RED→GREEN 修复。
- [ ] **Step 2:** 运行相关及完整设备回归，分别报告 runner 与 XML testcase/skipped，跳过的测试不算通过；验证管理员/只读账号、五组设置、真实加解密、Git 迁移与恢复。
- [ ] **Step 3:** 无测试 init/keep 配置运行正常 `assembleRelease`，检查三种 ABI 与 universal APK 的 libsodium 库、R8 映射/缺失类、安装冷启动和实际页面；保持版本 52/0.40.3。
- [ ] **Step 4:** 生成完整变更包，派 fresh-context reviewer 对照 spec/plan/ledger 审查；重评严重级别，所有可行动正确性问题（含 Minor）各写 RED 后修复，再跑完整回归。
- [ ] **Step 5:** `git diff --check`、确认所有既有文件仍在、记录最终测试/APK 哈希和未执行项。不得提交、推送、改版本、发布或管理 Issue。
