# MobileGH Costed Follow-ups Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close all nine deferred Minor findings and the actionable routing, widget, schema, scheduling, and device-verification rulings without losing any existing uncommitted work.

**Architecture:** Keep changes at the existing ownership boundaries: transactional pending-share storage behind the share importer, stable widget snapshot keys behind render-time localization, a pure log-retention function behind export, real HTTP cache control behind GraphQL, and configured-account host validation behind link routing. Widget work is evidence-led: coalesce only application-owned same-kind refreshes, preserve Glance's per-appWidgetId sessions, and add ID-specific reproduction evidence before claiming a rendering fix.

**Tech Stack:** Kotlin, Android/Jetpack Compose, Glance 1.2.0, WorkManager, DataStore Preferences, kotlinx.serialization, OkHttp, JUnit4, Android instrumentation.

**Spec:** `docs/superpowers/specs/2026-11-14-costed-followups-design.md`

## Global Constraints

- Preserve every existing uncommitted change. Execute in the existing working tree; do not create a clean worktree that omits it.
- Do not commit, tag, push, change `versionCode = 52` / `versionName = "0.40.3"`, or publish a release. Replace every normal plan commit step with focused tests plus a ledger entry.
- Every production behavior change requires an observed RED for the intended reason, then minimal GREEN. Characterization tests for already-correct forward-schema behavior may pass immediately and require no production change.
- Do not fabricate private GitHub write APIs, fake official-only screens, official Firebase push, WebView substitutes, voice/subscription/Analytics features, or unsafe URL/upload relaxations.
- Keep all test/build output in this plan's SDD workspace and record exact results in its ledger. Do not claim unavailable live GitHub or hardware behavior was verified.

## Review Focus

- A share provider that reports missing, stale, negative, or deceptive metadata must still be bounded by actual streamed bytes and must not destroy an older valid draft; covered by Task 1's unknown-size, aggregate-size, and failed-replacement tests.
- A saved Shortcut snapshot from an older version, a custom Saved Search, or a language change must render the correct owner-specific label; covered by Task 2's legacy/custom/render-key tests.
- A non-default-port, userinfo, lookalike, public-GitHub, or unconfigured-enterprise URL must not gain trust from string suffixes; covered by Task 5's routing matrix.
- A refresh burst or account/generation change must not mix widget contents or serialize unrelated widget kinds indefinitely; covered by Task 6's per-kind latest-wins tests and account-isolation regressions.
- A disabled/logged-out notification configuration must cancel real scheduled work, while enabled configuration enqueues one periodic worker; covered by Task 7's Android WorkManager test rather than a fake-only assertion.

---

### Task 1: Bounded, Transactional Share Import

**Files:**
- Modify: `app/src/main/java/com/mobilegh/nav/ShareIntents.kt`
- Modify: `app/src/main/java/com/mobilegh/data/PendingIssueShare.kt`
- Modify: `app/src/main/java/com/mobilegh/MainActivity.kt`
- Modify: `app/src/main/assets/i18n/en.tsv`
- Test: `app/src/test/java/com/mobilegh/nav/ShareIntentsTest.kt`
- Test: `app/src/test/java/com/mobilegh/data/PendingIssueShareTest.kt`
- Test: `app/src/test/java/com/mobilegh/i18n/I18nTest.kt`

**Interfaces:**
- Produces: `ShareIntents.MAX_ATTACHMENTS = 10`, `ShareIntents.MAX_TOTAL_SIZE = 50 * 1024 * 1024L`; import continues to return a pending-share ID or `null` and throws a readable validation exception on failure.
- Produces: `PendingIssueShare.Store(root, newId = { UUID... })` with the default unchanged for production; the deterministic ID seam is used only to force metadata-write failure in tests.
- Consumes: existing `CommentAttachments.MAX_SIZE` (10 MiB), `IssueShare`, and `PendingIssueShare.peek/finish` lifecycle.

- [ ] **Step 1: Write failing storage and pure-policy tests.** Add `failedReplacementKeepsPreviousShareAndFiles`: save a valid old share, precreate a directory at the deterministic new `<id>.json`, assert `save` throws, then assert old `peek` and attachment bytes remain. Add share-policy tests for more than 10 distinct URIs, known per-file size above 10 MiB, aggregate known size above 50 MiB, unsupported MIME, duplicate URIs counting once, and unknown `OpenableColumns.SIZE` falling through to streamed enforcement. Where Android resolver plumbing is required, place the executable cases in Task 8 and keep pure metadata/policy helpers here.
- [ ] **Step 2: Observe RED.** Run `./gradlew testDebugUnitTest --tests '*ShareIntentsTest' --tests '*PendingIssueShareTest' --tests '*I18nTest'`. Expected: current `Store.save` deletes old state before the forced write failure, and the new policy/API cases fail for the intended missing behavior.
- [ ] **Step 3: Implement transactional replacement and prevalidation.** Write the new metadata completely before deleting any old metadata/attachments; only after successful persistence remove the previous pending share. In `ShareIntents`, validate all distinct URI MIME/name/known-size metadata before copying, reject unknown/unsupported image types and negative sizes, enforce 10 files/50 MiB aggregate, retain the 10 MiB streamed limit for unknown or false metadata, and delete every newly copied file on parse/copy/save failure. Never follow a supplied filename outside `cacheDir/issue-share`.
- [ ] **Step 4: Own Activity import work with `lifecycleScope`.** Replace `MainScope()` with `lifecycleScope.launch`, perform copying on `Dispatchers.IO`, rethrow cancellation, and deliver success/error only while the same Activity is alive. Add the new validation messages to `en.tsv` and required-key assertions.
- [ ] **Step 5: Verify GREEN and no commit.** Re-run Step 2 plus `./gradlew testDebugUnitTest --tests '*NewIssueDraftTest' --tests '*NavRestoreTest'`; expected PASS. Record RED/GREEN evidence and changed files in the ledger.

### Task 2: Localized Shortcut Snapshot Labels

**Files:**
- Modify: `app/src/main/java/com/mobilegh/data/WidgetStore.kt`
- Modify: `app/src/main/java/com/mobilegh/widget/ShortcutWidget.kt`
- Test: `app/src/test/java/com/mobilegh/data/WidgetStoreTest.kt`
- Test: `app/src/test/java/com/mobilegh/widget/ShortcutLabelTest.kt`
- Test: `app/src/test/java/com/mobilegh/i18n/I18nTest.kt`

**Interfaces:**
- Produces: `ShortcutWidgetItem(id, label, url, labelKey: String? = null)`; built-ins persist `labelKey` and no language-dependent display text, custom `search:` items keep `label` and use `labelKey = null`.
- Produces: internal `shortcutLabel(item: ShortcutWidgetItem): String` used by the Glance renderer; it resolves built-in keys through `tr()` and preserves custom/legacy labels.

- [ ] **Step 1: Write failing snapshot and label tests.** Assert every built-in snapshot has its stable translation key, custom Saved Search names remain verbatim, legacy snapshots without `labelKey` remain readable, and every built-in key has a non-empty English translation. Include a language-switch test that resolves the same stored item under two locales without regenerating its snapshot.
- [ ] **Step 2: Observe RED.** Run `./gradlew testDebugUnitTest --tests '*WidgetStoreTest' --tests '*ShortcutLabelTest' --tests '*I18nTest'`; expected missing `labelKey`/resolver behavior and hardcoded English assertions fail.
- [ ] **Step 3: Implement stable keys and render-time resolution.** Replace the English label map with built-in ID→translation-key mapping, retain custom names, and render `shortcutLabel(item)` in `ShortcutContent`. Keep default `labelKey = null` for backward compatibility.
- [ ] **Step 4: Verify GREEN and no commit.** Re-run Step 2 and `./gradlew testDebugUnitTest --tests '*WidgetRoutesTest'`; expected PASS. Ledger the exact result.

### Task 3: Log Export Retention and Complete Strings

**Files:**
- Modify: `app/src/main/java/com/mobilegh/data/LogExport.kt`
- Modify: `app/src/main/java/com/mobilegh/ui/components/LogShare.kt`
- Modify: `app/src/main/assets/i18n/en.tsv`
- Test: `app/src/test/java/com/mobilegh/data/LogExportTest.kt`
- Test: `app/src/test/java/com/mobilegh/i18n/I18nTest.kt`

**Interfaces:**
- Produces: `LogExport.prune(directory: File, now: Long, current: File? = null, maxAgeMs: Long = 30 * 24 * 60 * 60 * 1000L, maxFiles: Int = 20)`; `write` invokes pruning only after a successful write.

- [ ] **Step 1: Write failing retention and resource tests.** Cover 30-day age boundary, newest-first 20-file count, age before count, current-file preservation, two ordinary consecutive exports, and ignoring directories/non-`.log` files. Require English values for `分享日志`, `日志文件导出失败`, and `日志导出失败：`.
- [ ] **Step 2: Observe RED.** Run `./gradlew testDebugUnitTest --tests '*LogExportTest' --tests '*I18nTest'`; expected missing pruning/API and dictionary keys fail.
- [ ] **Step 3: Implement minimal retention.** Prune only regular `.log` files directly inside the export directory; apply age, then count by modified time descending, always exclude `current`. Localize the `ClipData` label with `tr("分享日志")`. Do not delete the current file after launching the chooser.
- [ ] **Step 4: Verify GREEN and no commit.** Re-run Step 1's tests; expected PASS and ledger entry.

### Task 4: Remove Dead Widget API and Make GraphQL Force Real

**Files:**
- Modify: `app/src/main/java/com/mobilegh/data/WidgetStore.kt`
- Modify: `app/src/main/java/com/mobilegh/data/Api.kt`
- Modify: `app/src/main/java/com/mobilegh/data/IssueHierarchy.kt`
- Test: `app/src/test/java/com/mobilegh/data/ApiSessionTest.kt` or a focused new `GraphQLForceTest.kt`
- Test: `app/src/test/java/com/mobilegh/data/IssueHierarchyTest.kt`

**Interfaces:**
- Produces: `Api.graphql(query, vars = emptyMap(), force: Boolean = false)` and `Api.graphqlRoot(query, vars = emptyMap(), force: Boolean = false)`; `force = true` sends real OkHttp `Cache-Control: no-cache`.
- Produces: hierarchy GraphQL injection `suspend (String, Map<String, Any?>, Boolean) -> JsonObject`; `force` is not a GraphQL variable.
- Removes: `WidgetStore.savePullRequests` and any test solely exercising that dead API.

- [ ] **Step 1: Write failing HTTP and hierarchy tests.** Use the existing intercepted-OkHttp pattern to observe an actual GraphQL request and assert `force = true` yields `no-cache`, default does not, and request variables contain no `force`. Add hierarchy tests proving `force` reaches the injected request boundary without entering variables. Add a source-level dead-code check only if no compile/API regression can pin removal.
- [ ] **Step 2: Observe RED.** Run `./gradlew testDebugUnitTest --tests '*GraphQLForceTest' --tests '*IssueHierarchyTest' --tests '*ApiSessionTest'`; expected missing cache header and fake `force` variable fail.
- [ ] **Step 3: Implement real propagation and delete dead code.** Thread the Boolean through `graphql/graphqlRoot` to `call`, update hierarchy injection/callers, remove `force` from GraphQL variable maps, and remove `savePullRequests` without changing `loadPullRequests` caching.
- [ ] **Step 4: Verify GREEN and no commit.** Re-run Step 2 plus `./gradlew testDebugUnitTest --tests '*WidgetStoreTest' --tests '*IssueHierarchyActionsTest'`; expected PASS and ledger entry.

### Task 5: Configured GHES Hosts and Public API Routing

**Files:**
- Modify: `app/src/main/java/com/mobilegh/nav/Links.kt`
- Test: `app/src/test/java/com/mobilegh/nav/LinksRoutingTest.kt`
- Test: `app/src/test/java/com/mobilegh/nav/IncomingLinksTest.kt`

**Interfaces:**
- Consumes: the active account API/Web host passed as `activeApiHost`.
- Produces: exact host normalization shared by enterprise validation and bare-host input; public `github.com`/`api.github.com` routing remains independent of that account.

- [ ] **Step 1: Write failing configured-host and bare-input tests.** Cover `git.example.corp/owner/repo/issues/7`, its `api.` equivalent, uppercase/default HTTPS port, and account configured in either Web/API form. Reject unconfigured hosts, `evilgit.example.corp`, HTTP, userinfo, non-default ports, malformed hosts, and `api.` stripping beyond one prefix. Add public `api.github.com/repos/o/r/issues/1`, pulls/releases/users, and bare-public-host cases with an unrelated GHES account configured.
- [ ] **Step 2: Observe RED.** Run `./gradlew testDebugUnitTest --tests '*LinksRoutingTest' --tests '*IncomingLinksTest'`; expected configured non-`ghe.com` full/bare hosts fail while public cases characterize existing behavior.
- [ ] **Step 3: Implement exact account-host validation.** Normalize the configured host through the same strict URI parser; require HTTPS/default port/no userinfo, exact normalized Web/API host equality, and retain API-form path parsing only for the actual API-form host. Extend bare-host normalization to a syntactically valid host plus `/...`, then let normal routing/account validation decide; do not make arbitrary bare text a trusted enterprise link.
- [ ] **Step 4: Verify GREEN and no commit.** Re-run Step 2 plus `./gradlew testDebugUnitTest --tests '*NavRestoreTest'`; expected PASS and ledger entry.

### Task 6: Widget Refresh Ownership and Same-ID Session Verification

**Files:**
- Modify: `app/src/main/java/com/mobilegh/widget/Widgets.kt`
- Create if needed for focused concurrency tests: `app/src/main/java/com/mobilegh/widget/WidgetRefresh.kt`
- Test: `app/src/test/java/com/mobilegh/widget/WidgetRefreshTest.kt`
- Test: `app/src/test/java/com/mobilegh/data/WidgetStoreTest.kt`
- Device evidence: this plan workspace's same-ID logcat/dumpsys/UI captures.

**Interfaces:**
- Produces: an internal per-`WidgetStore.Kind?` refresh coordinator with latest-wins coalescing and non-cancelling sequential execution of the selected provider updates; `Widgets.update` increments version once per request and submits through it.
- Preserves: Glance `updateAll` provider selection, appWidgetId ownership, `SessionWorker` unique keys, and account/generation checks in snapshots.

- [ ] **Step 1: Encode the investigation conclusion.** Ledger that later worker pairs are separate IDs and not defects; same-ID DataStore collision is observed but its original trigger is unproven; blank/cross-provider publication remains unproven. Do not patch cross-provider selection based on guesswork.
- [ ] **Step 2: Write failing refresh tests.** With a blocking fake update boundary, prove a same-kind burst executes at most one active plus one trailing latest refresh, different kinds are not incorrectly merged, a failing refresh does not kill later ones, and real content changes still invoke a trailing update. Verify snapshot provider/account/generation isolation remains intact.
- [ ] **Step 3: Observe RED and implement the coordinator.** Run `./gradlew testDebugUnitTest --tests '*WidgetRefreshTest' --tests '*WidgetStoreTest'`; expected concurrent same-kind executions under current independent `scope.launch`. Use a `SupervisorJob` owner and per-kind queue/actor; do not introduce another `MainScope`, globally serialize unrelated kinds, or cancel real content updates.
- [ ] **Step 4: Reproduce same-ID behavior on the available emulator.** Add/hide/rebuild widgets and issue controlled same-kind refresh bursts against the Release build; capture logs keyed by appWidgetId, receiver, session, and DataStore filename. If a defect remains, write a failing regression at the demonstrated boundary and fix only that root cause. If no collision/blank/mismatch reproduces, report that exact negative result without calling the old symptoms fixed.
- [ ] **Step 5: Verify GREEN and no commit.** Re-run Step 3 and `./gradlew testDebugUnitTest --tests '*WidgetRoutesTest'`; expected PASS. Ledger evidence separately from unresolved external rendering preconditions.

### Task 7: Forward Schema and Reproducible Flow/Scheduling Regressions

**Files:**
- Test: existing `PullRequestStatus`, issue/PR parsing, `WorkflowRerunTest`, `DeploymentReviewsTest`, `NewIssueDraftTest`, `NotificationPollingTest`, and account-isolation test files; create focused test files only where ownership is unclear.
- Modify production only if a new regression exposes a defect.
- Add Android scheduling coverage in `app/src/androidTest/java/com/mobilegh/service/NotificationSchedulingTest.kt` using real WorkManager.

**Interfaces:**
- Consumes: existing `Api.plain/Api.json` (`ignoreUnknownKeys`, `explicitNulls = false`, `coerceInputValues`) and existing write/upload/retry implementations.
- Preserves: real Actions rerun bodies, Deployment Review mutations, shared-image resume index, notification decisions, and account/generation isolation.

- [ ] **Step 1: Add forward-schema characterization tests.** Parse unknown review/CI enum values to safe `NONE`, ignore additional JSON fields, and cover nullable/missing optional enhanced fields while required core detail remains usable. Run them before production changes; immediate PASS is valid characterization evidence, not a RED violation.
- [ ] **Step 2: Audit and strengthen existing flow tests only where assertions are missing.** Actions must assert rerun path and `enable_debug_logging=true`; Deployment Review must assert approve/reject mutation variables, environment IDs/comments, and invalid input; sharing must fail on image 2, resume at index 1, and not duplicate completed attachments; notification/account tests must cross login and generation boundaries. Any production repair exposed by these tests gets its own observed RED first.
- [ ] **Step 3: Add real scheduling instrumentation.** In an isolated Android test WorkManager configuration, enable scheduling and query the unique periodic work until ENQUEUED, then disable or log out and query until CANCELLED. Restore global settings and cancel test work in `finally`; do not substitute a fake's state for real enqueue/cancel semantics.
- [ ] **Step 4: Verify locally and no commit.** Run focused JVM tests and compile the instrumentation suite with `./gradlew testDebugUnitTest --tests '*WorkflowRerunTest' --tests '*DeploymentReviewsTest' --tests '*NewIssueDraftTest' --tests '*NotificationPollingTest' --tests '*BackgroundNotificationSettingsTest' assembleDebugAndroidTest`. Execute scheduling on an available emulator in Task 9. Ledger exact results.

### Task 8: Restore Android-Level Share Flow Coverage

**Files:**
- Modify: `app/src/androidTest/java/com/mobilegh/ui/FeatureFlowsTest.kt`
- Create: `app/src/androidTest/java/com/mobilegh/nav/ShareImportTest.kt` and a test-only `ContentProvider` if resolver ownership is clearer there.
- Create if needed: `app/src/androidTest/AndroidManifest.xml` for the test provider.

**Interfaces:**
- Consumes: Task 1's real `ShareIntents.import`, pending-share storage, and validation limits.
- Produces: real content URIs with controllable MIME, display name, known/unknown size, and stream bytes; no mocked `ContentResolver` return value is treated as proof of file cleanup.

- [ ] **Step 1: Write failing end-to-end import tests.** Use real test-provider content URIs and actual `Intent`/`ClipData` to verify successful persisted text+image import, `peek` before `finish`, `finish` deleting metadata/files, multiple attachments, unsupported type, per-file/aggregate/count rejection, stream failure rollback, and failed replacement preserving the old valid share.
- [ ] **Step 2: Observe RED on the emulator.** Run the focused connected test before production changes; expected current prevalidation/rollback cases fail for the intended reasons. Do not count a provider setup error as a valid RED.
- [ ] **Step 3: Apply only the missing Task 1 behavior needed by the reproduction.** Keep provider code test-only and pass content URIs through the same public importer used by users.
- [ ] **Step 4: Verify GREEN and no commit.** Re-run the focused connected tests and existing `FeatureFlowsTest`; expected PASS. Save XML results and ledger device/API details.

### Task 9: Full Regression, Release Reinstall, Device Rendering, and Final Review

**Files:**
- Evidence only: this plan workspace's suite/build/device/review artifacts and ledger.
- No version, release, or git-history modifications.

**Interfaces:**
- Consumes: every prior task's tests and the unmodified Release signing/build pipeline.

- [ ] **Step 1: Run complete JVM verification.** Run `./gradlew testDebugUnitTest` and parse all `app/build/test-results/testDebugUnitTest/*.xml`; expected zero failures/errors. Run `git diff --check` and verify version remains 52/0.40.3.
- [ ] **Step 2: Build and install Release.** Run `./gradlew assembleRelease`; install `app/build/outputs/apk/release/app-arm64-v8a-release.apk` with `adb install -r` on responding devices, cold-start, and confirm package/version plus retained account state. Do not publish the APK.
- [ ] **Step 3: Exercise actual device flows.** On each available display configuration, inspect light/dark layouts, log `.log` sharing through the system chooser, real share-import success/failure/cleanup, notification enable/disable scheduling, and widget add/hide/rebuild/refresh. Execute Task 7/8 instrumentation. Check logcat for FATAL/ANR/DataStore collisions and look at captured screenshots/UI XML.
- [ ] **Step 4: Record external preconditions precisely.** Distinguish verified local behavior from unavailable physical hardware, missing pending deployment review, absent Web session/upload authority, or unsafe/absent live rerun targets. Do not convert unverified into broken.
- [ ] **Step 5: Perform one fresh-context whole-change review.** Package the complete uncommitted diff with plan/spec/ledger and dispatch one fresh reviewer. Re-grade findings; because the user explicitly requires costed findings fixed, address every reproducible correctness finding with its own RED→GREEN and rerun the full suite/build as needed. Do not commit.
- [ ] **Step 6: Final evidence report.** Ledger test totals, build/install/device results, review fixes, and any exact remaining external precondition. Report all decisions honestly; leave the working tree uncommitted, unpushed, version unchanged, and unreleased.
