# MobileGH v0.40.12

## 更新内容

- **Camo 徽章加载提速与 504 兜底**
  - 从 `camo.githubusercontent.com` URL 末段解码原始 `https://` 地址，并与 `camo` 并发竞速，避免动态徽章因 `camo` 上游 504 长期转圈。
  - 收紧直连单次超时（`10s / 12s`），单候选地址不再在内层重复请求，减少排队阻塞。
- **Star 列表冷启动缓存与静默刷新状态**
  - 为 `Loader` / `Pager` 暴露 `silentRefreshing` 状态，在「全部 Star」「列表目录」与「列表详情」顶部显示「正在后台刷新…」进度条与提示，冷启动先立即展示磁盘缓存再后台同步。
  - `saveStarPager` 使用 `NonCancellable + Dispatchers.IO` 落盘，切换页签不中断缓存写入。
- **应用内更新自动安装**
  - 修复更新任务失败或取消后点击「重试」时未重置已触发标记、导致重试完成后不再自动拉起安装的问题。

## 验证

**已验证**

- JVM 单元测试：`:app:testReleaseUnitTest`（201 个测试类、1339 项测试）全部通过。
- 模拟器测试：`SvgBadgeTest`、`ImageLifecycleTest`、`BusyImageLoadingTest`（8 项）与 `StarredRepoCacheDeviceTest`（冷启动磁盘缓存 + 静默刷新状态 + 更新后收起）全部通过。
- 签名构建：`:app:assembleRelease` 与 `:translation-plugin:assembleRelease` 构建成功。
