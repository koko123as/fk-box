# fk-box 项目约束

本文件适用于整个仓库（[koko123as/fk-box](https://github.com/koko123as/fk-box)），记录开发和维护约束。项目以 sing-box-for-android 为 Android 后端，以 Rust + Slint 的 Material Design 3 界面（`crates/client-ui/`，按 [Material Design 3 UI skill](https://github.com/skydashnet/material-design-3-ui-skill) 重写，页面布局参考 FlClash）构建界面；三个上游边界分别维护，不能将界面参考版本当作核心或应用的比较基线。

## 文档职责

| 文档 | 内容 |
| --- | --- |
| [README.md](README.md) | 项目介绍、下载入口和许可声明 |
| [AGENTS.md](AGENTS.md) | 项目约束、工作方式和验证要求 |
| [libbox.md](docs/libbox.md) | 三内核 libbox 已退役（阶段 6）；仍保留的 sing-tun 补丁及原因 |
| [flclash.md](docs/flclash.md) | 界面实现（Material Design 3）、FlClash 布局参考版本、适配范围及验证 |
| [app.md](docs/app.md) | 相对 sing-box-for-android 的应用差异、兼容性及构建发布 |

开始任务先阅读适用的上级 `AGENTS.md`、本文件和所涉及领域的文档。修改跨越多个领域时同步更新对应文档；同一事实由所属文档维护，其他文档使用链接引用。文档描述当前源码，撤销或替代的实现不再列为当前要求；历史细节通过 Git 查询。

## 工作环境与改动范围

- 本机是 Android Termux，使用支持的命令和工具，例如 `pkg`；不要依赖 `systemctl` 等完整 Linux 系统服务。
- 临时文件统一放在 `$HOME/tmp`，同时遵守当前会话的文件系统权限；不要把本机 SDK、工具链路径或签名配置写入仓库和 CI。
- 未得到用户明确要求，不在本机执行编译构建。Go 测试、Android 编译和设备测试使用 GitHub Actions。文档、源码和差异的静态检查可在本机执行。
- 默认在 `main` 分支上工作并推送到 `koko123as/fk-box` 的 `main`；用户明确要求时可另开分支（如朴素界面分支 `ui/plain`、M3 重写分支 `ui/m3-rewrite`），不建立其他长期分支。原 `koko123as/B-box` 仓库及其分支只读，不向其推送。
- 开始前检查工作树，保留用户未提交的改动。上游同步固定比较 SHA，区分已提交差异和工作树差异，核实远端地址后再按任务授权推送。
- 接口、构建方式和兼容性判断应有当前源码或对应版本的官方文档依据，不凭空增加接口，不将旧验证结果当作当前版本结果。
- 不提交密钥、密码、`local.properties`、`.local-signing/`、`.core/`、SDK、AAR 或生成的 APK。发布沿用既有签名密钥，不重新生成替代密钥。

## 目录结构

目录按 [Clash-of-Rust](https://github.com/Fu021/Clash-of-Rust) 的分层组织（仅参考结构，不复制其代码）：`crates/` 放平台无关的 Rust crate，`apps/<平台>/` 放各平台外壳。

| 路径 | 内容 |
| --- | --- |
| `Cargo.toml`、`Cargo.lock` | Rust workspace（成员见下），`[profile.release]` 与 `[patch.crates-io]` 在此 |
| `crates/client-ui/` | `fkbox-client-ui`：纯 Slint 界面：组件树 `ui/`（theme、icons、components、models、pages、mobile/android）、`i18n/`、M3 配色（`theme.rs`）与字体（`fonts.rs`）；不含页面逻辑 |
| `crates/client-core/` | `fkbox-client-core`：平台无关业务层（按领域分目录：config、connection、controller、geodata、rules、storage、subscription、update、tools）：客户端存储（SQLite：服务设置、配置元数据、远程服务器、界面偏好）及其从 Room/SharedPreferences 的一次性导入、订阅（响应头解析、自动更新策略、数组配置命名与跟随、配置内容保存流程，内核相关部分经平台的 `content::Kernel` 调 libbox）；路由规则模型（校验、存储与线格式；按内核编译仍在 libbox）、Geo 资源策略、服务设置快照/校验/写入（Android 副作用留 Kotlin）、`bbox-1` 备份打包/校验/恢复、WebDAV 账号与流程、更新检查、终端设置（HTTP 请求、Keystore 加密与 Ghostty 检查经 `CoreKernel` 回调平台）；第二阶段计划见 [docs/architecture/phase2.md](docs/architecture/phase2.md)，第三阶段见 [docs/architecture/phase3.md](docs/architecture/phase3.md)，第四阶段（按 Clash-of-Rust 分层）见 [docs/architecture/phase4.md](docs/architecture/phase4.md) |
| `crates/mobile-ffi/` | `fkbox-mobile-ffi`：移动端会话层：`NativeHost` trait、进程内客户端存储会话及一次性导入、供宿主调用的 JSON 接口（`api`）与 libbox 命令订阅计划（`sessions`）；不含 JNI/Slint |
| `crates/brain-ffi/` | `fkbox-brain-ffi`：单内核架构阶段 1 的 Rust 大脑 C ABI（`include/fkbox_brain.h`，固定 C 结构体、无 JSON）：`decide`/`resolve` 入口、规则快照（`ArcSwap` 原子替换）、DNS 缓存、宿主回调封装；静态库供 Go 宿主（cgo）链接，暂未接入应用。规则引擎在 `crates/client-core/src/rules/engine/`（统一规则表示、Clash/mihomo 与 sing-box 导入、编译匹配），订阅导入与最小 sing-box 配置生成在 `crates/client-core/src/config/brain/`。契约见 [brain-ffi.md](docs/architecture/brain-ffi.md)、[brain-config.md](docs/architecture/brain-config.md) |
| `apps/android/native/` | `fkbox-android`：Android cdylib（库名 `fkui`，即 `libfkui.so`），含 `android_main`、界面 JNI（`jni_host.rs`）、客户端存储 JNI（`jni_store.rs`，供 Kotlin `database/CoreStore.kt`，并回调 `database/CoreKernel.kt`）、logcat 初始化，以及驱动 Slint 组件树的展示层（`app.rs`、`nav.rs`、`rows.rs`、`forms.rs`、`pages/`；主机截图工具 `examples/shot.rs`，`--features shot`） |
| `apps/android/app/` | Kotlin 宿主（Gradle 模块 `:app`，包名/namespace `io.nekohasekai.sfa`，安装 ID `io.fkbox.app`） |
| `apps/android/baselineprofile/` | Baseline Profile 生成模块（`:baselineprofile`） |
| `apps/android/core/` | 阶段 6b 后只剩保留的 `sing-tun-android-ingress.patch` 及其测试（`scripts/patch_sing_tun.py`，原因见 [brain-android.md](docs/architecture/brain-android.md)）；三内核 libbox 的 Go 适配已删除 |
| `apps/android/fkhost/` | 单内核架构阶段 3 的 Go 宿主（独立 Go 模块，做法 A：自建 `box.Context` + `daemon.NewStartedService` + Android `PlatformInterface`）与 fkbrain/fkdns/mihomo/xray 插件、链接 Rust 大脑的 cgo 桥（`brainffi`）；不打补丁、无本地监听；`mobile/` 经 gomobile 打成 `fkhost.aar`（`.github/actions/build-fkhost`）供应用使用（阶段 6）。见 [brain-host.md](docs/architecture/brain-host.md)、[brain-android.md](docs/architecture/brain-android.md) |
| `vendor/` | 带 fk-box 补丁的 Slint crate（`FKBOX-PATCH.md`），经根 `Cargo.toml` 的 `[patch.crates-io]` 使用 |
| `scripts/` | 构建、核心补丁、发布与源码检查脚本（`python3 scripts/<名称>.py`，在仓库根目录运行） |
| `resources/` | 非代码资源：`config/`（detekt 配置）、`licenses/`（组件许可文本）、`flclash-manifest.json`（FlClash 来源清单） |
| `docs/` | 设计与差异文档：[app.md](docs/app.md)、[libbox.md](docs/libbox.md)、[flclash.md](docs/flclash.md)、[slint-manifest.md](docs/slint-manifest.md)、`architecture/` |
| `examples/` | 配置示例 |
| `version.properties` | 应用与核心版本（留在仓库根目录，CI 与 Gradle 都从这里读取） |

Gradle 根是 `apps/android/`（`settings.gradle.kts`、`build.gradle.kts`、`gradle/`、`gradlew`、`gradle.properties`），仓库根目录没有 Gradle 文件。构建在该目录运行（`cd apps/android && ./gradlew :app:...`，CI 以 `working-directory: apps/android` 运行），任务名不变；`app/build.gradle.kts` 经 `repoRoot`（Gradle 根上两级）读取根目录的 `version.properties`、Cargo workspace 与 `vendor/`，detekt 配置取自 `resources/config/detekt/`。

## 架构与兼容性约束

1. 界面由 Rust + Slint 提供，布局位于 `crates/client-ui/ui/`，状态与页面逻辑位于 `apps/android/native/src/`，一并编为 `libfkui.so`，页面布局按 FlClash 翻译，风格为 Material Design 3（语义颜色角色、M3 字阶、形状与间距令牌、状态层、48 dp 触控目标、标准 M3 组件）与 Material 图标；手机端为悬浮的不透明导航栏（surface-container 加阴影，内容从其下滚过），较宽窗口为 M3 导航侧栏；浮在滚动内容之上的表面（悬浮导航栏、滚动后的顶部应用栏、菜单、对话框、行页面底部提示条）一律是 `ui/components/kit.slint` 的不透明 `Surface`（M3 surface-container 色调角色，需要时加 `Theme.shadow-*` 阴影），禁止 `backdrop-blur`、半透明“玻璃”表面、液态玻璃着色器、其他模糊滤镜或装饰形状——模糊会在每帧滚动时重读并过滤下方内容；阴影只用于浮在内容之上的表面（菜单、消息条、FAB、提升卡片/按钮、悬浮导航栏、行页面底部提示条）（约定见 [flclash.md](docs/flclash.md#material-design-3-界面约定)，由 `scripts/verify_newui.py` 检查）。动效参考 FlClash 的动效与交互手感用 Slint 实现（状态过渡、可点按表面的水波纹、淡入切换/滑动分页、代理组展开、列表项增删、底部表单拖动关闭、数值补间、仪表盘编辑晃动与拖动让位、容器变换），时长与曲线一律取 `ui/theme/theme.slint` 的 motion 令牌，所有时长乘以宿主传入的系统动画时长缩放（`ANIMATOR_DURATION_SCALE`，为 0 时关闭动画）（见 [flclash.md](docs/flclash.md#动效)）。Slint 为 1.19，Skia 渲染器与 Android 后端以带 fk-box 补丁的 vendored 副本使用（[slint-manifest.md](docs/slint-manifest.md#fonts-and-rendering-slint-ui-only)）。Jetpack Compose 界面已删除，`compose/` 只保留稳定组件名 `compose.MainActivity`、`compose.ShareActivity`（进入 `BoxActivity`，即 GameActivity）和扫码用的 `compose/qr/`。原生状态模型在 `backend/`，命令与事件经 `bridge/BoxBridge.kt` 与 `bridge/SlintHost.kt`（JNI）提供给 Rust。
2. 客户端存储（`crates/client-core`，文件 `files/fkbox/client.db`）与内核（`fkhost.aar`：单 sing-box + Rust 大脑）是配置、服务和会话数据的来源；旧版 Room `profiles.db`/`settings.db` 与 SharedPreferences `flclash_ui` 只在首次启动时导入一次，原文件保留不改（供降级的旧版本读取），不要删除或写入。阶段 6 起只有一个 sing-box 内核，由 Rust 大脑导入 Clash/mihomo YAML、sing-box JSON 与分享链接并生成配置（mihomo/Xray 协议经插件运行）；配置按原样保存，保存前经 mobile-ffi `brain.check` 校验（见 [brain-android.md](docs/architecture/brain-android.md)）。
3. 模式、代理组、可选节点和延迟来自当前内核；自动组不能手动选择。保留本地/远程会话区分，不把本机配置、出口 IP 或演示数据当作远端状态。
4. 保留 Android VPN 权限、前台服务和服务状态确认流程。配置及备份应用须保留修订冲突检查、原子写入和失败恢复；不能在需要重启服务时把发出启动请求视为启动成功。
5. fk-box 的安装身份为 `io.fkbox.app`，源码 namespace、类路径及 AIDL 描述符保留 `io.nekohasekai.sfa`。任务未涉及这些约定时保持兼容；不要重排或复用已退役的 Binder 方法编号，也不要清空无关设置和数据库。
6. 阶段 6（用户 2026-11-24 决定）：Tailscale/Taildrop、OpenVPN、OpenConnect、终端（Tailscale SSH）、USB/IP、抓包/MITM/HTTP 改写、远程控制和 root 自动重定向不在单内核路径上，入口移除、用户数据不删；普通 Root/Shizuku 包查询保留。Android 5/6、legacy AAR、Xposed/LSPosed 和旧更新下载/静默安装链路已移除。具体范围见 [brain-android.md](docs/architecture/brain-android.md) 和 [app.md](docs/app.md)。
7. FlClash 只作页面布局参考，保留其来源和许可；[flclash-manifest.json](resources/flclash-manifest.json) 记录参考版本、`adaptedNativeFiles` 及补充参考（[flclash.md](docs/flclash.md#参考版本与来源清单)）。编译成功或源码检查都不能代替视觉和实机验收。

## 上游同步与验证

- Android 上游比较基线维护在 [app.md](docs/app.md)，核心标签由 [version.properties](version.properties) 决定，FlClash 布局参考提交维护在 [flclash.md](docs/flclash.md) 和来源清单。更新其中一个上游时，不自动改变另两个基线。
- 更新核心时核对 legacy 补丁、所有 `apps/android/core/ui_*.go` 扩展及生成的绑定，并同步检查 Release、源码验证和 Android smoke 工作流。官方原版 AAR 缺少 fk-box（继承自 B-box）新增绑定，不能仅凭版本相同直接替换。
- 更新 FlClash 布局参考时核对页面布局、信息组织及动效时序（[flclash.md](docs/flclash.md#动效)），不迁入其控件、字形或特效；同步更新来源清单与许可。
- 按受影响范围验证。本机可运行 `git diff --check` 和 `python3 scripts/verify_newui.py`；文档变更还应检查链接和旧引用。后者仅检查源码约束，不证明运行行为。
- [.github/workflows/brain.yml](.github/workflows/brain.yml) 负责规则引擎、配置生成与大脑 C ABI（`crates/client-core` 的 `rules::engine` 与 `config::brain`、`crates/brain-ffi`）的单元测试、头文件与静态库 C 链接检查、真实 geo 数据格式检查、decide 基准，以及生成的配置经 sing-box 解析与 `box.New`（`probes/stage2`）（ubuntu-24.04-arm；目标为 `main` 或 `arch/**` 的 PR 及 `arch/**` 分支推送）；[.github/workflows/fkhost.yml](.github/workflows/fkhost.yml) 负责 `apps/android/fkhost` 的编译、各插件 TCP/UDP 连通（本地回环测试夹具）与 protect 计数、经真实 TUN 设备的端到端测试（sing-box → fkbrain → Rust decide → 出站）（触发条件同上）；[.github/workflows/newui.yml](.github/workflows/newui.yml) 负责 Go 扩展测试及 Compose 源码检查；[newui-android.yml](.github/workflows/newui-android.yml) 负责 JVM、Android 编译和 API 31/35 smoke；[build-release.yml](.github/workflows/build-release.yml) 负责签名 ARM64 Release 和产物校验。
- 验证记录应说明源码 SHA、实际运行的检查、Actions 链接及未覆盖范围。已有 Release、旧提交的成功构建或测试代码的存在，都不等于当前源码已通过验收。仅构建下载产物时注意 Release 工作流的 `publish_release` 默认开启，具体发布规则见 [app.md](docs/app.md)。
