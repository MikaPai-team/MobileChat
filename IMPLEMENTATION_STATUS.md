# MobileChat 实现与验证状态

## LaTeX 与中文 Markdown 兼容修复 0.1.6（2026-11-22）

- 原实现未接入数学扩展，Markdown 将 `\[...\]` / `\(...\)` 的括号转义消耗后，公式中的 `\mathrm`、上下标与箭头成为普通文字。现在接入 Markwon JLatexMath 与 Inline Parser，在解析前兼容四种常见公式分隔符，区分行内和独立公式。
- 公式使用当前主题文字颜色与 16 sp 字号，独立公式适配文本区域宽度；绘制使用共享的两个后台线程。公式库通过非导出的初始化 Provider 加载随 APK 打包的字体与配置，不需要运行时联网。
- 截图中 `**① 写：**写出…` 等中文标点后的加粗结束符不符合 CommonMark 的闭合条件。仅在显示前补齐闭合条件，令步骤标签正确成为加粗文本；本地保存和复制的原始回答保持原文。
- 兼容处理保护反引号/波浪号代码围栏、行内代码、缩进代码、转义符和价格文本；未完成的公式分隔符继续保留，待流式输出闭合后渲染。保留现有标题、列表、引用、分隔线、表格与语法高亮。
- 用户明确排除“回到最新消息”按钮，未修改 `ChatScreen.kt`；文件 SHA-256 与修改前一致。
- 递增为 `0.1.6` / versionCode 7，两种非调试构建仍共用包名和原签名。

运行 `testDebugUnitTest` 并仅选择 `MarkdownCompatTest` 与 `CodeLanguagesTest`：共 7 项，0 失败、0 错误。检查化学公式分隔符与正文边界、行内离子电荷、中文步骤标签的实际 CommonMark 加粗节点、代码/转义/价格保护、基础 Markdown 保持和语法包原文完整性。这是 JVM 源码检查，不是优化后 APK 或公式图像的运行验证；没有运行完整协议测试集或设备测试。

运行 `scripts/package.ps1 -Variant both -Offline`，Gradle 在 1 分 48 秒内完成。两个版本的 Kotlin / Java 编译与 Lint Vital 通过；R8 开启版实际执行代码优化及资源缩减。两版 BuildConfig 均为 `DEBUG=false`，R8 标记分别为 true / false。

对最终 `dist` 副本使用 aapt2、apksigner 和 SHA-256 核对：包名均为 `com.mobilechat.app`，版本均为 `0.1.6` / versionCode 7，均为非调试 APK。签名有效并与 0.1.5 相同，证书 SHA-256 为 `d982fc124c1234397db5614bc048e86ee39afb1f24d1736842cf88c70a6b630f`。副本与原始产物、同名 `.sha256` 文件哈希一致。

两个交付 APK 均包含 `JLatexMathInitProvider`、包内初始化 authority、`DefaultTeXFont.xml` 与 30 个公式 TTF 字体，R8 开启版的资源缩减没有丢失这些文件。

| 版本 | 交付文件 | 大小 | SHA-256 |
| --- | --- | --- | --- |
| R8 开启 | `dist/MobileChat-0.1.6-r8-on.apk` | 2,656,859 字节 | `47415d4d93341ff335b469225b86a5f5e2d94768d20bfd1fee4b37b36a84e2b6` |
| R8 关闭 | `dist/MobileChat-0.1.6-r8-off.apk` | 17,267,339 字节 | `9e3fa4c45bf218c423da0850f0d3e20fb3864ca2a05fa385bffcff1a45b802bb` |

日志：`math-tests.log`、`package-0.1.6.log`。报告：`dist/package-report.json`、`dist/math-0.1.6-verification.json`。当前 ADB 未连接设备，未安装或进行公式显示、复制、浅深主题与滚动的设备复测；编译及资源核对通过不能视为真机性能测试通过。

## 深色主题与 Markdown 代码卡片 0.1.5（2026-11-22）

- 根因：`MaterialTheme` 只提供配色与字体样式，原页面没有提供 Material 的默认 `LocalContentColor`。在主题入口统一提供 `onBackground`，让页面标题、卡片主文字、返回/删除等图标随浅深主题切换；已有显式色彩继续生效。
- 保留 Markwon 正文、标题、列表、链接和表格，将顶层围栏代码块与缩进代码块分离成圆角卡片，按原顺序穿插在正文中。卡片包含语言标识、复制按钮、等宽字体、文本选择与双向滚动，内容高度上限为 360 dp。复制保留完整代码原文，不带卡片标题。
- 使用 Markwon Syntax Highlight / Prism4j 的语言规则，打包其全部 25 个内置语法：Python、JavaScript、Java、Kotlin、C、C++、C#、Go、Swift、Dart、Scala、Groovy、SQL、JSON、YAML、HTML/XML、CSS、CSS extras、Markdown、LaTeX、Makefile、Git、Clojure、Brainfuck、C-like。补充 `py`、`kt`、`kts`、`js`、`c++`、`c#`、`cs`、`yml`、`md` 等语言别名。未声明或未注册语言保持纯文本。
- 浅深主题使用独立的关键字、字符串、数字、函数、注释与标点配色；切换主题会重新渲染代码和正文。嵌套于列表或引用内的代码继续由 Markwon 渲染并高亮，独立卡片用于顶层代码块。
- 正文仍保留回答入场及新增文字渐显效果；通过 `setParsedMarkdown` 保留表格的渲染调度回调。高亮语法类通过 Java 注解处理生成并直接引用，没有使用反射加载。
- 新依赖的旧版 `annotations-java5` 与项目现有 JetBrains 注解重名，首次构建失败；仅在高亮依赖树中排除旧副本后恢复构建。
- 版本递增为 `0.1.5` / versionCode 6，两种非调试构建仍共用原包名与签名。新增 `CodeLanguagesTest.kt`，用于检查已打包语言规则、语法令牌及包含 Unicode/空白的原文完整性；本次没有运行新的单元测试或设备测试，该测试源码也未编译执行。

成功命令：`scripts/package.ps1 -Variant both -Offline`，Gradle 在 1 分 38 秒内完成。两个版本的 Kotlin / Java 编译及 Lint Vital 通过；开启版实际执行 R8 和资源缩减。首次非离线构建已完成新依赖下载。

对最终 `dist` 副本使用 aapt2、apksigner 与 SHA-256 再次核对：包名均为 `com.mobilechat.app`，版本均为 `0.1.5` / versionCode 6，均为非调试 APK。签名有效且与 0.1.4 相同，证书 SHA-256 为 `d982fc124c1234397db5614bc048e86ee39afb1f24d1736842cf88c70a6b630f`。两版交付副本的哈希与原始产物及同名 `.sha256` 文件一致。

| 版本 | 交付文件 | 大小 | SHA-256 |
| --- | --- | --- | --- |
| R8 开启 | `dist/MobileChat-0.1.5-r8-on.apk` | 2,217,749 字节 | `18ed908ef1a6ce22180ce52f98c4852f97f8aadf33a0c4e174bd8a1511f0c1c7` |
| R8 关闭 | `dist/MobileChat-0.1.5-r8-off.apk` | 16,778,701 字节 | `70a3eccd63ba9879442b906206ff00e54c30d5e5922db6d0f56bb2fc25de11cb` |

日志：`package-0.1.5.log`。打包报告：`dist/package-report.json`。最终副本核对报告：`dist/markdown-0.1.5-verification.json`。未连接 Android 设备、未安装 APK；深色界面、代码高亮、复制、选择及滚动操作尚未设备复测。编译打包成功不能视为真机性能测试通过。

## 玻璃渲染修复 0.1.4（2026-11-22）

- 确认 Miuix 0.9.4 的模糊采样填充会向组件边界外绘制；原实现没有在整个效果外裁剪。共享 `chrome` 修饰符现在先执行圆角裁剪，限制模糊纹理、覆色和边缘高光的范围，处理截图中的灰块与矩形溢出。
- 背景采样改为包含页面底色、空会话提示与消息列表的完整内容层，避免透明底色参与模糊合成；顶栏和输入栏独立绘制在采样层外。
- 玻璃模糊半径由 20 dp 调为 16 dp，浅色覆色透明度由 0.72 降至 0.32，深色为 0.46；增加适配浅深主题的玻璃边缘高光和适度饱和度。输入框覆色为 0.18，避免遮住输入栏的玻璃效果。
- 聊天内容整体应用状态栏边距并裁剪在可用区域，顶栏高度及消息留白同步计算，避免滚动内容与状态栏图标重叠。非硬件加速设备继续使用实色背景。
- 等待同目录的 0.1.3 打包结束后才合入修复，保留该版本的会话命令、思考进展、动画、相机和任意文件功能。递增到 `0.1.4` / versionCode 5，没有修改包名或签名。

运行 `scripts/package.ps1 -Variant both -Offline`，Gradle 在 2 分 12 秒内完成双版本编译打包，两个版本的 Lint Vital 均通过。R8 开启版执行 `minifyReleaseWithR8` 与资源缩减；两者 BuildConfig 都是 `DEBUG=false`，R8 标记分别为 true / false。本次没有运行新的单元测试或设备测试。

对最终 `dist` 副本使用 aapt2、apksigner 和 SHA-256 再次核对：两者包名均为 `com.mobilechat.app`，版本均为 `0.1.4` / versionCode 5，均为非调试 APK。签名有效且证书与 0.1.3 相同，证书 SHA-256 为 `d982fc124c1234397db5614bc048e86ee39afb1f24d1736842cf88c70a6b630f`。交付副本与原始输出以及同名 `.sha256` 文件的哈希一致。

| 版本 | 交付文件 | 大小 | SHA-256 |
| --- | --- | --- | --- |
| R8 开启 | `dist/MobileChat-0.1.4-r8-on.apk` | 2,152,213 字节 | `6f715d9db94ad6bcafb5110b5386ae7fbfc05cd8518befdead10c5be89e23e60` |
| R8 关闭 | `dist/MobileChat-0.1.4-r8-off.apk` | 16,729,549 字节 | `38341e7d427ccff70085c4458fa4f66aeffc9970f06c3916687ae775f72c081a` |

`package-0.1.4.log` 为打包日志，`dist/package-report.json` 为打包报告，`dist/glass-0.1.4-verification.json` 为最终副本核对报告。未连接 Android 设备、未安装 APK；玻璃外观、浅深主题、滚动和键盘行为仍待真机复测，打包通过不能视为真机性能测试通过。

## 0.1.2 修改及打包结果（2026-11-22）

- 首次修改阶段按用户要求未测试、未编译。随后用户明确要求打包两个 APK，本次只运行双版本打包及最终产物核对；未运行单元测试或设备测试，也未向真实中转站发送连接检查请求。
- 站点增加可保存的「Codex 兼容」，旧配置默认关闭；连接检查、聊天和重新生成均经由同一请求封装。
- 参照 Codex CLI `rust-v0.98.0` 的 HTTP 形态，补充 `User-Agent` / `originator` / `session_id`、`prompt_cache_key`、类型化消息、工具及 include 字段。系统提示词发送为空字符串时仍保留字段，兼容模式省略输出上限；聊天与重新生成共用会话 ID。
- 区分客户端限制与 API Key 认证错误；兼容模式已开启但仍受拒绝时提示核对站点客户端权限，错误详情继续隐藏 Key。
- 页面改用可保存的返回栈及 Compose 可寻址切换动画，支持 Android 14+ 预测性返回进度、左右边缘、取消回弹和上一页预览；普通返回使用相同切换动画。应用显式启用系统返回回调。
- 页面状态保存覆盖编辑字段与列表位置；取消手势不弹栈，返回首页的动画完成后才清空选中会话。
- 新增四项测试源码，覆盖兼容请求、默认字段、旧配置读取和错误提示；未执行。导航与真实站点兼容性仍待真机验证。
- 版本递增至 `0.1.2` / versionCode 3，按项目约定生成 R8 开 / 关两种非调试版本。

本次命令：`scripts/package.ps1 -Variant both -Offline`，Gradle 在 1 分 37 秒内完成，两个版本的 Kotlin 编译及 Lint Vital 通过。R8 开启版实际执行 `minifyReleaseWithR8` 与资源缩减；关闭版没有 minify 任务。两者 BuildConfig 均为 `DEBUG=false`，R8 标记分别为 true / false。

最终交付副本再次使用 aapt2、apksigner 和 SHA-256 核对：包名均为 `com.mobilechat.app`，版本均为 `0.1.2` / versionCode 3，均为非调试 APK。签名有效且证书与修改前相同，证书 SHA-256 为 `d982fc124c1234397db5614bc048e86ee39afb1f24d1736842cf88c70a6b630f`。

| 版本 | 交付文件 | 大小 | SHA-256 |
| --- | --- | --- | --- |
| R8 开启 | `dist/MobileChat-0.1.2-r8-on.apk` | 2,135,345 字节 | `3bd636f7055d245786441e7b233a78c9b9893454c1a08e03561a5d27a16fc6cc` |
| R8 关闭 | `dist/MobileChat-0.1.2-r8-off.apk` | 16,712,689 字节 | `f609b58afdb2172dc05e6b679cb9a4325067e76b16e2685f4583d8e2349be4b2` |

每个 APK 均有同名 `.sha256` 文件；交付副本与 Gradle 原始输出哈希一致。`dist/package-report.json` 为本次打包报告，`package-0.1.2.log` 为本次日志。未安装 APK，未验证真实站点、返回手势或真机性能。

公开协议参考：

- [Codex 默认客户端](https://github.com/openai/codex/blob/rust-v0.98.0/codex-rs/core/src/default_client.rs)
- [Codex Responses 请求](https://github.com/openai/codex/blob/rust-v0.98.0/codex-rs/codex-api/src/requests/responses.rs)
- [Codex 会话请求头](https://github.com/openai/codex/blob/rust-v0.98.0/codex-rs/codex-api/src/requests/headers.rs)

## 上次打包记录（不覆盖本轮改动）

日期：2026-11-22。此前 R8 开启 / 关闭的两个非调试 APK 已生成并通过核对；今后默认打包同时提供这两个版本。下方验证结果与 APK 均属于修改前的 `0.1.1`。

## 已实现

- 独立 Android 工程，包名 `com.mobilechat.app`，版本 `0.1.1` / versionCode 2，minSdk 26、targetSdk 37。
- Miuix 会话首页、聊天、站点配置与外观设置；浅色/深色/系统主题；可选玻璃（仅 API 33+ 且硬件加速，默认关闭）。
- 多站点、任意模型名称、默认 `gpt-6.1-sol` / `gpt-5.6-sol`；完整 HTTP(S) Responses 地址、Bearer Key、系统提示词、输出上限和推理强度。
- Responses SSE 与完整 JSON、Markdown/表格/代码复制、停止、手动重试、重新生成、会话重命名/删除。
- 图片/PDF/文本附件、Base64 流式请求、本地历史续聊、断流及进程中断恢复。
- Room、DataStore、Keystore AES-GCM；私有附件目录清理；Android 12+ 云备份与设备迁移排除规则。

## 已验证

当前验证及打包命令：

```powershell
.\scripts\package.ps1 -Variant r8 -Offline
.\scripts\package.ps1 -Offline
.\gradlew.bat :app:testDebugUnitTest :app:lintRelease --offline --console=plain
```

- `assembleRelease` 和 `assembleReleaseNoR8` 均成功；默认双版本脚本实际执行通过，PowerShell 语法检查通过。
- 修改后重新运行 JVM 测试：19 项，0 失败、0 错误。该项目默认注册 Debug 单元测试任务；这些测试验证源码协议及逻辑，不能视为已运行优化后 APK。
- Release Lint：0 错误、2 提示。Release 打包所需的 Lint Vital 也通过。
- R8 开启版实际执行 `minifyReleaseWithR8` 和资源缩减；映射文件记录编译器 R8 9.3.16。
- R8 关闭版没有执行 minify 任务；生成的 BuildConfig 为 `DEBUG=false`、`R8_ENABLED=false`。
- 两者包名 `com.mobilechat.app`、版本 `0.1.1` / versionCode 2，均为非调试构建，minSdk 26、targetSdk 37。
- 两者均通过 APK v2 签名校验，证书与旧 Debug APK 相同；升级版本号后支持覆盖安装，保留本机配置和历史。
- R8 保留规则仅补充反射使用的 ChatViewModel 构造函数；Room 数据库及生成实现的默认保留规则也已在 seeds 中确认，未使用全量 keep 或全局忽略警告。
- 5 项 Android 设备测试源码此前编译通过，实际设备测试尚未执行。

## 安装包

| 版本 | 交付文件 | 大小 | SHA-256 |
| --- | --- | --- | --- |
| R8 开启 | `dist/MobileChat-0.1.1-r8-on.apk` | 2,118,929 字节 | `b925623870ca8e6213c6bf92189a6d1ac171aa7f15fdc21941963297a7368e4d` |
| R8 关闭 | `dist/MobileChat-0.1.1-r8-off.apk` | 16,696,273 字节 | `6cd042f43b9eddf3e1bff79e3260659d338382ec1a44cc1a44c141fa5fad9999` |

- 两个 APK 均有同名 `.sha256` 校验文件，交付副本与 Gradle 原始输出的哈希一致。
- `dist/package-report.json` 记录两者的版本、R8 状态、非调试标记、证书及校验值。
- 证书 SHA-256：`d982fc124c1234397db5614bc048e86ee39afb1f24d1736842cf88c70a6b630f`。沿用现有 Android Debug 证书以支持安装升级；运行配置是非调试 Release。
- R8 开启版同时启用资源缩减，关闭版不启用；APK 体积变化不能代表帧率提升幅度。
- 原始输出位于 `app/build/outputs/apk/release` 和 `app/build/outputs/apk/releaseNoR8`；R8 映射位于 `app/build/outputs/mapping/release/mapping.txt`。
- 旧 `dist/MobileChat-0.1.0-debug.apk` 作为历史产物保留。

报告：

- `app/build/reports/tests/testDebugUnitTest/index.html`
- `app/build/reports/lint-results-release.html`
- 本机日志：`r8-build.log`、`paired-build.log`、`r8-verification.log`

## 打包后待验证

当前未连接 Android 设备，因此未安装或进行界面检查。真机上仍需验证 Keystore、Room/附件清理、键盘/旋转/字体放大与玻璃效果。

尚未提供真实中转站地址和 Key，因此目前协议验证使用 MockWebServer；真实模型与图片/PDF 能力须由实际中转站验证。
