## MobileGH v0.40.7

- **Actions 设置与汉化** — 顶栏下拉切换重构为统一规范的下划线页签 `GhTopTabBar`。Fork PR 审批策略、Actions 允许范围、私有仓库访问级别及 Workflow 读写权限等后端英文枚举全面自然汉化；配置项采用卡片化排版，统一全应用视觉风格。
- **全屏评论输入** — 紧凑收起底部工具栏与模式栏，大幅释放手机纵向浏览空间；点击输入框胶囊直接弹起全屏 Markdown 编辑器，返回时平滑恢复紧凑状态。
- **登录交互与稳定性** — 优化 GitHub 授权、OAuth PKCE 与 Token 登录流程；返回时自动取消未完成的授权流程并即刻恢复主按钮，底层防护防锁死重入，避免界面卡死。
- **开源许可协议** — 明确采用 GNU Lesser General Public License v3.0 或更高版本（`LGPL-3.0-or-later`），补充 LICENSE 与 COPYING 规范文件。

## 下载

现代 Android 手机通常选 `app-arm64-v8a-release.apk`。另外提供 `armeabi-v7a`、`x86_64` 和通用包。`mobilegh-offline-translation.apk` 是可选翻译组件，可在「设置 → 外观与语言 → 阅读」开启离线翻译时下载。各文件的 SHA-256 见 `SHA256SUMS.txt`。
