# MobileGH 功能核对

本轮基于用户提供的 APK 清单/界面资源、分析文档和 GitHub 公开 schema 核对。APK 中存在某个组件，不代表第三方凭据具有相同服务端权限。该表描述实际实现与剩余差距，不宣称完整替代全部官方能力。

| 功能 | 当前实现 | 范围与限制 |
| --- | --- | --- |
| 登录 | 原生 Token 主入口、OAuth 原生授权码与进度、系统浏览器登录/2FA | 账号认证页面由 GitHub 控制，网络需能访问 github.com；不再用 WebView 缓存密码/自动授权 |
| OAuth 续期 | 保存实际返回的刷新凭据，401 时按设备流程刷新并重试一次 | 不为没有刷新凭据的 PAT 或旧 OAuth 凭据伪造续期；未完成真实过期用户授权的端到端测试 |
| 验证器 | 登录前/设置均可打开、多账号、6 位码、倒计时、复制 | 用户需配置 GitHub TOTP 并主动导入密钥；不是官方数字批准 |
| 组织与仓库 | 分页组织/成员身份/仓库所有者目录，按组织分组、筛选、刷新、指定组织诊断 | 当前凭据实际可见范围；权限、SSO 或请求失败会展示原因，不保证全部组织都可读 |
| 动态 | 首页/个人/组织的类型筛选，列表去重、稳定标识 | GitHub 事件 API 有范围和延迟限制；不承诺实时推送 |
| 成就 | 公开主页徽章、等级、倍数 | 无公开成就 API；隐藏徽章不可读取，网页结构变化时可重试 |
| 自定义列表 | 原生 GitHub Lists 与本机分类 | GitHub 列表用公开 GraphQL；本机分类按账号保存并明确标注，列表成员变化与取消 Star 是不同操作 |
| 贡献者 | 仓库底部前三位卡片、累计提交、周趋势、增删行、占比、时间范围、活跃周、逐笔提交 | 周统计不能冒充逐日精度；202/缺失/截断单独提示，未知指标显示 —；明细按 UTC 日期精确查询 |
| 版本比较 | 分支/标签/SHA 输入，分页提交、默认折叠代码 Diff | API 文件列表最多 300 个，达到上限提示；二进制或缺失 Patch 不伪造差异 |
| Discussions | 分类/回答筛选、加载内搜索、分页、正文、评论/回复、创建与回复 | 服务端限制仍生效；提交失败保留内容，不自动重复发布 |
| Issue / PR | Markdown、评论、表情、标签/指派/里程碑、审查、合并、折叠 Diff | 更高级的审查线程、Projects 关联与批量管理仍需完善 |
| 下载 / 更新 | 应用内进度、资源详情、打开、历史/文件清理、按架构在线更新 | 公开下载与图片按节点策略加速，私有资源带凭据直连；后台仍由 Android DownloadManager 传输 |
| 诊断 | 分类日志、脱敏、清理、导出、崩溃记录 | 用户主动导出；未捕获异常记录不能替代真实设备复现 |

继续完善的公开能力包括 Projects v2 原生看板、代码搜索与 Blame、更多审查线程、细粒度订阅、个人资料/状态编辑和更多管理界面。Copilot、官方推送、Mobile 数字匹配审批、某些图片上传和第一方特权属于另一类服务端能力，必须分别核实支持路径。

证据：[认证报告](AUTH_CAPABILITIES.md)、[GitHub UserList](https://docs.github.com/en/graphql/reference/users#userlist)、[贡献统计](https://docs.github.com/en/rest/metrics/statistics)、[提交比较](https://docs.github.com/en/rest/commits/commits#compare-two-commits)、[Discussions](https://docs.github.com/en/graphql/guides/using-the-graphql-api-for-discussions)。
