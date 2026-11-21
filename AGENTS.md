# fk-box 项目约束

本文件适用于整个仓库（[koko123as/fk-box](https://github.com/koko123as/fk-box)），记录开发和维护约束。项目以 sing-box-for-android 为 Android 后端，以 Rust + Slint 的 Material Design 3 界面（`ui/`，按 [Material Design 3 UI skill](https://github.com/skydashnet/material-design-3-ui-skill) 重写，页面布局参考 FlClash）构建界面；三个上游边界分别维护，不能将界面参考版本当作核心或应用的比较基线。

## 文档职责

| 文档 | 内容 |
| --- | --- |
| [README.md](README.md) | 项目介绍、下载入口和许可声明 |
| [AGENTS.md](AGENTS.md) | 项目约束、工作方式和验证要求 |
| [libbox.md](libbox.md) | 官方 sing-box/libbox 的使用方式、原分支功能的去向、分流与 HTTP 改写配置、升级内核 |
| [flclash.md](flclash.md) | 界面实现（Material Design 3）、FlClash 布局参考版本、适配范围及验证 |
| [app.md](app.md) | 相对 sing-box-for-android 的应用差异、兼容性及构建发布 |


## 目录结构

| 目录 | 内容 |
| --- | --- |
| `core/` | fk-box 自己的 Go 模块（`core/go.mod`），宿主进程 `fk-core`，不 import 任何内核（见 [docs/kernel-isolation.md](docs/kernel-isolation.md)、[core/README.md](core/README.md)） |
| `protocol/` | UI 协议定义（`methods.json`），由 [protocol.py](tools/protocol.py) 生成 Go/Kotlin 代码并检查 Rust |
| `ui/` | Rust + Slint 界面 crate，编为 `libfkui.so` |
| `clients/android/` | Android Gradle 工程根（`app/`、`baselineprofile/`、Gradle Wrapper）；Android Studio 打开此目录，命令行在此目录运行 `./gradlew` |
| `tools/` | 生成、校验、补丁与发布脚本，从仓库根目录运行 |

[version.properties](version.properties) 留在仓库根目录：它同时固定核心（sing-box/Go/Xray/mihomo）与应用版本，由 CI、`tools/` 与 Android 构建共用。

开始任务先阅读适用的上级 `AGENTS.md`、本文件和所涉及领域的文档。修改跨越多个领域时同步更新对应文档；同一事实由所属文档维护，其他文档使用链接引用。文档描述当前源码，撤销或替代的实现不再列为当前要求；历史细节通过 Git 查询。

## 工作环境与改动范围

- 本机是 Android Termux，使用支持的命令和工具，例如 `pkg`；不要依赖 `systemctl` 等完整 Linux 系统服务。
- 临时文件统一放在 `$HOME/tmp`，同时遵守当前会话的文件系统权限；不要把本机 SDK、工具链路径或签名配置写入仓库和 CI。
- 未得到用户明确要求，不在本机执行编译构建。Go 测试、Android 编译和设备测试使用 GitHub Actions。文档、源码和差异的静态检查可在本机执行。
- 默认在 `main` 分支上工作并推送到 `koko123as/fk-box` 的 `main`；用户明确要求时可另开分支（如朴素界面分支 `ui/plain`、M3 重写分支 `ui/m3-rewrite`），不建立其他长期分支。原 `koko123as/B-box` 仓库及其分支只读，不向其推送。
- 开始前检查工作树，保留用户未提交的改动。上游同步固定比较 SHA，区分已提交差异和工作树差异，核实远端地址后再按任务授权推送。
- 接口、构建方式和兼容性判断应有当前源码或对应版本的官方文档依据，不凭空增加接口，不将旧验证结果当作当前版本结果。
- 不提交密钥、密码、`local.properties`、`.local-signing/`、`.core/`、SDK、AAR 或生成的 APK。发布沿用既有签名密钥，不重新生成替代密钥。

## 架构与兼容性约束

1. 界面由 Rust + Slint 提供，位于 `ui/`（`ui/*.slint` 布局、`src/` 状态与页面逻辑，编为 `libfkui.so`），页面布局按 FlClash 翻译，风格为 Material Design 3（语义颜色角色、M3 字阶、形状与间距令牌、状态层、48 dp 触控目标、标准 M3 组件）与 Material 图标；手机端为悬浮的不透明导航栏（surface-container 加阴影，内容从其下滚过），较宽窗口为 M3 导航侧栏；浮在滚动内容之上的表面（悬浮导航栏、滚动后的顶部应用栏、菜单、对话框、行页面底部提示条）一律是 `ui/kit.slint` 的不透明 `Surface`（M3 surface-container 色调角色，需要时加 `Theme.shadow-*` 阴影），禁止 `backdrop-blur`、半透明“玻璃”表面、液态玻璃着色器、其他模糊滤镜或装饰形状——模糊会在每帧滚动时重读并过滤下方内容；阴影只用于浮在内容之上的表面（菜单、消息条、FAB、提升卡片/按钮、悬浮导航栏、行页面底部提示条）（约定见 [flclash.md](flclash.md#material-design-3-界面约定)，由 `tools/verify_newui.py` 检查）。动效参考 FlClash 的动效与交互手感用 Slint 实现（状态过渡、可点按表面的水波纹、淡入切换/滑动分页、代理组展开、列表项增删、底部表单拖动关闭、数值补间、仪表盘编辑晃动与拖动让位、容器变换），时长与曲线一律取 `ui/theme.slint` 的 motion 令牌，所有时长乘以宿主传入的系统动画时长缩放（`ANIMATOR_DURATION_SCALE`，为 0 时关闭动画）（见 [flclash.md](flclash.md#动效)）。Slint 为 1.19，Skia 渲染器与 Android 后端以带 fk-box 补丁的 vendored 副本使用（[slint-manifest.md](slint-manifest.md#fonts-and-rendering-slint-ui-only)）。Jetpack Compose 界面已删除，`compose/` 只保留稳定组件名 `compose.MainActivity`、`compose.ShareActivity`（进入 `BoxActivity`，即 GameActivity）和扫码用的 `compose/qr/`。原生状态模型在 `backend/`，命令与事件经 `bridge/BoxBridge.kt` 与 `bridge/SlintHost.kt`（JNI）提供给 Rust。
2. libbox（含其配置存储，由 Room `profiles.db` 一次性导入，Room 保留不动以便回退，见 [core/fk](core/fk/core_profile_store.go)）与 Room 设置库是配置、服务和会话数据的来源。`libbox.aar` 同时编入 sing-box、Xray、mihomo 三个内核，同一时间只运行一个（多内核设计与分阶段进度见 [libbox.md](libbox.md#多内核xray-与-mihomo)）。在多内核接入完成前，配置仍为 sing-box JSON 并经过 `Libbox.checkConfig`；不能静默把一种内核的配置转换成另一种。
3. 模式、代理组、可选节点和延迟来自当前内核；自动组不能手动选择。保留本地/远程会话区分，不把本机配置、出口 IP 或演示数据当作远端状态。
4. 保留 Android VPN 权限、前台服务和服务状态确认流程。配置及备份应用须保留修订冲突检查、原子写入和失败恢复；不能在需要重启服务时把发出启动请求视为启动成功。
5. fk-box 的安装身份为 `io.fkbox.app`，源码 namespace、类路径及 AIDL 描述符保留 `io.nekohasekai.sfa`。任务未涉及这些约定时保持兼容；不要重排或复用已退役的 Binder 方法编号，也不要清空无关设置和数据库。
6. 保留完整 sing-box 协议和 Tailscale/Taildrop、终端、普通 Root/Shizuku 能力。历史核心裁剪与 Tailscale/终端移除已回退；Android 5/6、legacy AAR、Xposed/LSPosed 和旧更新下载/静默安装链路则已移除。具体范围见 [libbox.md](libbox.md) 和 [app.md](app.md)。
7. FlClash 只作页面布局参考，保留其来源和许可；[flclash-manifest.json](flclash-manifest.json) 记录参考版本、`adaptedNativeFiles` 及补充参考（[flclash.md](flclash.md#参考版本与来源清单)）。编译成功或源码检查都不能代替视觉和实机验收。

## 上游同步与验证

- Android 上游比较基线维护在 [app.md](app.md)，核心标签由 [version.properties](version.properties) 决定，FlClash 布局参考提交维护在 [flclash.md](flclash.md) 和来源清单。更新其中一个上游时，不自动改变另两个基线。
- 应用使用 sing-box 官方、未修改的 `libbox.aar`，不再维护 libbox 分支或补丁；更新核心按 [libbox.md](libbox.md#升级内核) 的清单进行，并同步检查 Release、源码验证和 Android smoke 工作流。
- 更新 FlClash 布局参考时核对页面布局、信息组织及动效时序（[flclash.md](flclash.md#动效)），不迁入其控件、字形或特效；同步更新来源清单与许可。
- 按受影响范围验证。本机可运行 `git diff --check` 和 `python3 tools/verify_newui.py`；文档变更还应检查链接和旧引用。后者仅检查源码约束，不证明运行行为。
- [.github/workflows/newui.yml](.github/workflows/newui.yml) 负责 Go 扩展测试及 Compose 源码检查；[newui-android.yml](.github/workflows/newui-android.yml) 负责 JVM、Android 编译和 API 31/35 smoke；[build-release.yml](.github/workflows/build-release.yml) 负责签名 ARM64 Release 和产物校验。
- 验证记录应说明源码 SHA、实际运行的检查、Actions 链接及未覆盖范围。已有 Release、旧提交的成功构建或测试代码的存在，都不等于当前源码已通过验收。仅构建下载产物时注意 Release 工作流的 `publish_release` 默认开启，具体发布规则见 [app.md](app.md)。
