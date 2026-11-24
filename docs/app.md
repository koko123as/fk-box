# 与 sing-box-for-android 的差异

本文记录 Android 应用相对上游的当前差异。项目约束见 [AGENTS.md](../AGENTS.md)，核心补丁见 [libbox.md](libbox.md)，界面实现与 FlClash 布局参考见 [flclash.md](flclash.md)。

## 比较基线与应用身份

| 项目 | 当前值 |
| --- | --- |
| 仓库 | [koko123as/fk-box](https://github.com/koko123as/fk-box)（`main`；直接前身 [giturass/B-box](https://github.com/giturass/B-box)，经 [koko123as/B-box](https://github.com/koko123as/B-box) 的 `fk-box` 分支迁入并保留完整历史） |
| Android 上游 | [SagerNet/sing-box-for-android](https://github.com/SagerNet/sing-box-for-android)，`dev` 分支 |
| 已合并的比较基线 | [`a548a7903e96266a0046018562c73b192edbfc18`](https://github.com/SagerNet/sing-box-for-android/commit/a548a7903e96266a0046018562c73b192edbfc18)，`1.15.0-alpha.13` |
| FlClash 布局参考 | [v0.9.3 `be84736`](https://github.com/chen08209/FlClash/tree/be84736605527034689c95bf27828ff27d912c5e)（仅页面布局；控件为 Material 3），见 [flclash.md](flclash.md#参考版本与来源清单) |
| fk-box 版本 / versionCode | `1.15.5-beta.1` / `755`，以 [version.properties](../version.properties) 为准 |
| 应用名 / Gradle 根项目名 / APK 前缀 | `fk-box` |
| 安装包名 / applicationId | `io.fkbox.app` |
| 源码 namespace / 包路径 | `io.nekohasekai.sfa` |
| Android / flavor | API 24（Android 7.0）起，`play` / `other` |

应用基线与核心标签、FlClash 参考提交分别维护；fk-box 的 versionCode 不必等于上游基线的值。以上描述当前源码，不表示已有 Release 包含全部改动。

品牌、安装身份及组件约定：

- 应用标签、Gradle 根项目名和 APK 前缀统一使用 fk-box。应用图标为白底蓝色笔刷字母 S；自适应图标的主题单色层（`ic_launcher_monochrome`）与通知/快捷开关小图标（`ic_menu`，24dp 画布内 20dp 高）都是去掉白底的 S 剪影。保留上游许可和非官方分支说明，不改写真实来源名称。
- namespace、Kotlin/Java 类路径、AIDL 描述符及 RootServer 反射类名继续使用 `io.nekohasekai.sfa`。Provider authorities 使用 `${applicationId}`，服务控制和 USB/Taildrop 等广播使用 `BuildConfig.APPLICATION_ID` 或运行时包名。
- 可与原版 SFA 并行安装，私有配置和数据相互独立；原版数据需导出/导入。Flutter 迁移沿用 B-box 既有安装身份、数据库及配置，不以换 UI 为由清空数据。

## fk-box（本仓库 main）

- 安装身份 `io.fkbox.app`，应用标签、Gradle 根项目名与 APK 前缀为 `fk-box`；源码 namespace、类路径与 AIDL 描述符仍为 `io.nekohasekai.sfa`。应用内更新检查读取 [koko123as/fk-box](https://github.com/koko123as/fk-box/releases) 的 `fk-box-*` 发布资产，同时接受改名前的 `FK-box-*`（见 [关于页更新检查](#已移除能力与兼容入口)）。
- 界面已从 Flutter、Jetpack Compose 迁移到 Rust + Slint（`crates/client-ui/`，编译为 `libfkui.so`），`flutter/` 与 Compose 页面均已删除，页面对应关系见 [flclash.md](flclash.md#rust--slint-实现)。`ui/m3-rewrite` 上界面为 Material Design 3，基于 Slint 1.19，手机端为悬浮导航栏，浮在滚动内容之上的表面为不透明的 M3 色调表面（不使用 `backdrop-blur`），见 [flclash.md](flclash.md#material-design-3-界面约定)。`BoxBridge` 保留原 MethodChannel 命令和 EventChannel 消息名，只把通道外壳换成 `call()` / `listen()`。
- [build-debug-apk.yml](../.github/workflows/build-debug-apk.yml) 仅构建核心与 Slint 应用（ARM64 debug），不再等待 Flutter AAR；[newui.yml](../.github/workflows/newui.yml) 在 `main` 推送时验证核心并运行界面源码检查。

## 界面替换与 Android 桥接

上游 Compose 页面、导航、编辑器、CameraX/MLKit 原生扫码及相关依赖和无引用资源已移除，所有页面由 `apps/android/native/src/pages` 与 `crates/client-ui/ui` 提供（按 FlClash 布局、以 Material 3 风格 Slint 组件实现，不是旧 SFA 页面）。`compose/` 保留 [MainActivity.kt](../apps/android/app/src/main/java/io/nekohasekai/sfa/compose/MainActivity.kt) 和 [ShareActivity.kt](../apps/android/app/src/main/java/io/nekohasekai/sfa/compose/ShareActivity.kt) 两个稳定 Android 组件名，均继承 `BoxActivity`。

下表的原生路径以 `apps/android/app/src/main/java/io/nekohasekai/sfa/` 为前缀。

| 区域 | 路径 | 当前职责 |
| --- | --- | --- |
| 界面宿主 | `bridge/BoxActivity.kt`（承载 Slint 界面） | 启动器/分享入口、文档选择、权限、服务状态及配置应用 |
| 通道与命令 | `bridge/BoxBridge.kt` | 沿用原 MethodChannel 命令与 EventChannel 消息名，经 `call()` / `listen()`（不注册平台通道）对接配置、工具和设置 |
| 原生状态 | `backend/` | 仪表盘、分组、连接、日志、Tailscale/Taildrop、诊断和认证状态模型 |
| 平台视图 | `bridge/BoxBrowserFactory.kt`、`bridge/BoxTerminalFactory.kt` | OpenConnect WebView 与 Ghostty 终端经 `BoxController.browserView` / `terminalView` 以原生视图叠加在 Slint 页面上 |
| 服务与数据 | `bg/`、`database/`、`utils/` | 保留 sing-box 服务、订阅更新及本地/远程命令目标；Room 已移除，设置、配置元数据与远程服务器经 `database/CoreStore.kt`（JNI）存于 `crates/client-core` 的客户端存储 |
| 应用查询 | [vendor/](../apps/android/app/src/main/java/io/nekohasekai/sfa/vendor/) | 普通、Root/Shizuku 应用查询和托管列表扫描；API 24+ 实现及依赖由 `play` / `other` 共用 |

阶段 6 起所有配置由单一 sing-box 内核运行，Rust 大脑负责导入、规则、DNS 和分组（`TypedProfile.core` 只保留存储值，不再切换内核，见 [brain-android.md](architecture/brain-android.md)）；分组、可选节点和延迟来自大脑的 `fkb_brain_groups`/`fkb_brain_group_select`/`fkb_brain_group_test`，自动组不可手动选择，模式切换已不存在。VPN 授权、前台服务、连接管理、日志、流量、本地/远程会话及路由保持原有能力边界。具体布局、语言、卡片与工具入口见 [flclash.md](flclash.md)。

桥接载荷只携带 Rust 界面读取的字段（以 `apps/android/native/src` 中的键名为准）；USB/IP 设备、OpenConnect/OpenVPN 隧道信息和连接的进程信息由界面整体列出，保留完整字段。只供已删除 Compose 页面使用的状态与载荷字段（加载标志、Tailscale 头像/密钥到期/最后在线、Ping 历史、测速精度、扫码裁剪框回调、未接入的设置 DAO 方法等）已删除，新增字段时先确认界面会读取。设置存储 [StorePreferenceDataStore](../apps/android/app/src/main/java/io/nekohasekai/sfa/database/preference/StorePreferenceDataStore.kt) 读写客户端存储的 `settings` 表（类型码沿用旧 `KeyValueEntity`），内存缓存与按序异步写入的行为不变。

### 客户端存储与旧数据导入

设置、配置列表元数据、远程控制服务器、界面偏好与备份身份存于 `crates/client-core` 的 SQLite 存储（`files/fkbox/client.db`，`PRAGMA user_version` 为结构版本）。`libfkui.so` 中只有一个存储实例：Kotlin `CoreStore` 首次访问时加载 `libfkui.so` 并打开（服务在没有界面时也能启动），Rust 界面使用同一实例，直接读配置列表与界面偏好、写偏好与排序，远程控制服务器的列表与表单保存也直接读写（地址校验在 client-core `remote_servers.rs`）；涉及配置内容或服务的修改仍经 Kotlin，之后发送不带载荷的 `profiles` 事件，界面重新读取。配置的“解析内核”由 Kotlin（libbox 检测）按文件大小与修改时间缓存到存储中。

首次打开时，若存储为空，从旧 Room `profiles.db`（结构 1–3，`typed` 列为 `TypedProfile` Parcel）、`settings.db`（`KeyValueEntity`）和 SharedPreferences `flclash_ui` 导入：原文件（含 `-wal`/`-shm`）先复制到缓存目录再读取，原文件不改；导入在一个事务内完成并写入标记 `legacy_import`，配置 ID 与 AUTOINCREMENT 计数保持不变，已选配置等设置因此继续有效。导入失败不写标记，存储保持为空，下次启动重试；存储已有数据时不导入。旧文件保留到后续版本，降级到旧版本时旧版本读取的是升级前的数据。

Tailscale 端点/节点/出口节点/Ping、Taildrop 收发与分享、SSH/Root/Shizuku 终端、Ghostty 设置和导入字体、OpenConnect/OpenVPN 认证、USB/IP、网络诊断、崩溃/OOM/电量报告均通过新页面使用现有后端。报告导出的“为 sing-box 维护者加密”开关默认关闭：上游 `Libbox.createZipArchive` 的 age 收件人是 sing-box 维护者的密钥，加密后的 `.zip.age` 只有他们能解开；崩溃报告导出总是包含 `jvm.log`（崩溃堆栈，“包含日志”只控制 `go.log`），并附带界面（libfkui）最近一次 panic 的 `fkui-panic.txt`（panic 钩子写入应用 files 目录，被捕获的 panic 也会写入）。历史 MOD-014 的功能移除已回退，不能按旧裁剪记录删除这些能力。

## 配置、服务应用与备份

[BoxProfiles.kt](../apps/android/app/src/main/java/io/nekohasekai/sfa/bridge/BoxProfiles.kt) 负责导入、编辑、手动订阅更新、配置切换及局域网代理保存；[ProfileContent.kt](../apps/android/app/src/main/java/io/nekohasekai/sfa/database/ProfileContent.kt) 的互斥锁由前台编辑和后台订阅更新共用；内容替换流程（修订校验、数组元素跟随、原子写入、更新时间与订阅元数据、回滚）在 client-core [content.rs](../crates/client-core/src/config/content.rs)，新建/编辑表单保存、批量创建与删除在 [profiles.rs](../crates/client-core/src/config/profiles.rs)，内核相关的拉取、拆分、转换与校验经 [CoreKernel.kt](../apps/android/app/src/main/java/io/nekohasekai/sfa/database/CoreKernel.kt) 调 libbox，服务应用与恢复留在 `ProfileContent.replaceLocked`。保存只写该配置行的更新时间和订阅列，不写排序及其他字段，后台更新与界面重新排序互不覆盖。

- 保存前检查内容修订和完整 `Libbox.checkConfig`，以原子写入替换内容。高级配置编辑原生 DNS、路由、规则集、入站、出站、端点和日志；原始 JSON 编辑保留配置元数据及订阅 URL。
- 顶层为对象数组的 JSON（v2rayNG 多个自定义配置导出，或订阅返回此格式）：文件、剪贴板、扫码和外部打开经 `BoxProfiles.decode`/`decodeText` 拆分（[ProfileContent.splitArray](../apps/android/app/src/main/java/io/nekohasekai/sfa/database/ProfileContent.kt) 调 libbox `SplitCoreConfigArray`），每个元素单独识别内核并校验，任一元素失败则整批不保存并指出第几个；`profiles.saveAll` 一次创建全部本地配置，界面提示导入数量。只差一个节点的 Xray 数组由 libbox 合并为一个配置（规则见 [libbox.md](libbox.md#多内核xray-与-mihomo)），以来源名（文件名或远程配置名）命名，节点在一个组里。其他情况名称取元素的 `remarks`，没有时为“来源名 #序号”（client-core [profile_array.rs](../crates/client-core/src/config/profile_array.rs)）。新建远程配置的首次获取是数组时，同一 URL 生成多个远程配置；之后手动/自动更新在 client-core 的保存流程中按已保存内容的 `remarks`、再按配置名、再按“#序号”回退名选回各自的元素，单元素数组总是匹配，找不到时更新失败并保留原内容。合并后的远程配置每次更新都重新合并；此前按元素拆出的同一 URL 远程配置，更新后各自都会变成合并版，可删去多余的。单个配置不保存数组，原始编辑器粘贴数组会报错。
- 本地配置创建时在客户端存储记录时间，原始/高级编辑及局域网入站变更成功保存内容时更新 `lastUpdated`；无内容变化和仅编辑名称等元数据不刷新时间。保存或应用失败与内容一起恢复旧时间。远程配置的该字段仍表示订阅更新时间，手动 JSON 编辑不覆盖它；没有时间记录的旧配置保持未知。界面展示见 [flclash.md](flclash.md#配置页与内核差异)。
- 运行中修改选中配置需应用到服务：模式相同时重载，模式变化时停止并重新走权限/启动流程。需要重启时通过 [ServiceStartResult.kt](../apps/android/app/src/main/java/io/nekohasekai/sfa/bridge/ServiceStartResult.kt) 等待 Started 回调；失败或超时恢复旧文件/设置并尝试恢复原服务。停服时普通保存不会自动启动 VPN。
- 原始/高级 JSON 编辑、手动/自动订阅更新和配置切换共用 [ProfileChange.kt](../apps/android/app/src/main/java/io/nekohasekai/sfa/database/ProfileChange.kt) 的失败恢复流程。订阅保存或应用失败恢复旧内容和更新时间；切换失败恢复原选择及服务模式。恢复在不可取消的协程上下文完成，保留原错误及恢复错误；持久数据恢复失败时不启动未恢复的配置。
- 配置切换在配置互斥区内、修改选中配置前读取最新服务状态；启动中或停止中拒绝切换，避免界面状态延迟导致启动配置与保存的选择不一致。界面禁用行为见 [flclash.md](flclash.md#配置页与内核差异)。
- 界面只经绑定的服务连接得知服务状态。启动中或停止中时 `BoxActivity.watchTransition()` 每 4 秒核对一次：连接的服务类与当前模式选择的不一致（如无 TUN 入站的 sing-box 配置切到总在 VPN 服务中运行的 mihomo/Xray）就重新绑定；连续两次读到的服务状态与界面不同则以服务为准，启动中读到已停止视为启动失败。
- 仪表盘内存：内核与界面同进程，`BoxDashboard` 无论服务状态如何都从进程 RSS 中扣除 Go 运行时内存（`Libbox.readUIRuntimeMemory`）作为内核部分。
- 订阅流量/到期：手动更新、自动更新和新建远程配置都经 client-core 发起、由 `CoreKernel` 调 `Libbox.uiSubscriptionGet`（UA 见 `HTTPClient.userAgentFor`）获取，三个内核相同。client-core [subscription.rs](../crates/client-core/src/subscription/mod.rs) 解析 `subscription-userinfo`（键不区分大小写，容忍空格、缺键和小数，负数/非数字忽略，`expire=0` 表示长期有效）；没有该响应头就不显示，也不从节点名推测，本次获取无头时清除旧数据。`profile-update-interval`（小时）仅在用户未手动设置间隔（`intervalUserSet`）时生效，最小 15 分钟；`profile-web-page-url` 仅接受 http/https。这些字段与内容、更新时间一起保存，服务应用失败时一并回滚（`ProfileChange`）。`TypedProfile` Parcel 版本 3 增加 upload/download/total/expire/webPageUrl/intervalUserSet；读取 v1/v2 数据原值不变，非 60 分钟的旧间隔视为用户设置。
- [UpdateProfileWork.kt](../apps/android/app/src/main/java/io/nekohasekai/sfa/bg/UpdateProfileWork.kt) 只做 WorkManager 调度：周期与首次延迟、到期的配置由 client-core（`subscription::schedule`/`due`）决定；在同一互斥区内完成每个订阅的保存、应用和恢复，应用失败请求重试。[BackgroundProfileService.kt](../apps/android/app/src/main/java/io/nekohasekai/sfa/bg/BackgroundProfileService.kt) 通过本机服务连接确认状态，模式相同重载，模式变化及失败恢复时等待停止和 Started 回调；后台重启所需 VPN/通知权限未授予时更新失败并回滚。更新未选中配置或停服时不启动服务，远程会话不接收本机订阅的重载。
- 常规服务页的 VPN 绕过、自动重定向和动态通知开关保存后，运行中的本地服务自动停止并重新走权限/启动流程，确认 Started 后完成操作。[ServiceSettingsChange.kt](../apps/android/app/src/main/java/io/nekohasekai/sfa/bridge/ServiceSettingsChange.kt) 在保存或重启失败时恢复原设置，重启失败时再尝试恢复原服务；停止状态只保存设置，远程会话不触发本机或远端重启。
- 常规服务页的断网保护（Kill switch，`kill_switch`，默认关，不需重启服务）：VPN 模式下启动时先建立一个无人读取的全路由 TUN，内核建立自己的 TUN 后替换它；内核启动或重载失败（命令服务器失败除外）时关闭内核、保留阻断 TUN，前台通知显示“断网保护：网络已阻断”，仍弹出失败提示，用户停止服务后才恢复网络。判断在 mobile-ffi `service_state.rs`（`startGuard`、`startFailure.block`、`blockedNotification`），TUN 由 [VPNService.kt](../apps/android/app/src/main/java/io/nekohasekai/sfa/bg/VPNService.kt) `openBlockingTun` 建立。进程被杀或崩溃不在覆盖范围，需要系统的“始终开启 VPN + 阻止未使用 VPN 的连接”。运行中切换配置成功后通知标题随之更新。
- Root 自动重定向的系统 BPF map 兼容范围和核心补丁见 [libbox.md](libbox.md#android-自动重定向兼容)。Android 桥接继续沿用上游的启动、错误传递及 Root 服务死亡清理流程。
- 局域网代理使用 libbox 原生入站变换，不引入全局 Clash 端口设置。运行时端点来自已成功启动的配置快照，避免将保存失败或未应用的配置显示为正在监听。
- [DashboardConfigCache.kt](../apps/android/app/src/main/java/io/nekohasekai/sfa/bridge/DashboardConfigCache.kt) 仅缓存选中配置和已启动配置的内容、修订及最近一组接口地址的 LAN 解析结果，相同内容共用缓存。前台两秒轮询保留网络/热点检测，以配置 ID、路径、文件时间/大小及写入代次检查保存配置；未变化时不重读内容、计算哈希或调用 LAN 解析。地址变化复用内容和修订，重新调用 LAN 解析；运行配置按成功启动快照更新。普通写入及备份恢复/回滚共用 `ProfileContent.write` 推进代次，避免同大小、同时间戳的修改漏刷。解析错误也缓存，配置/地址变化或手动刷新时重试，取消不缓存。缓存条目同时保存 `Libbox.detectCoreConfig` 的结果（每份内容只识别一次），判断 LAN 开关是否可用时不再每次轮询解析整份配置。启动快照 `ready()` 并行读取配置列表与设置，不再扫描 LAN；内网 IP 卡片的可见轮询负责加载。
- 二进制 `.bpf` 导入保留订阅 URL、更新间隔、自动更新及时间戳。动态 QRS 通过 [BoxQrScanner.kt](../apps/android/app/src/main/java/io/nekohasekai/sfa/bridge/BoxQrScanner.kt) 按相机会话收集有界帧、校验 CRC，并支持原压缩文件封装。

`bbox-1` 格式的打包、校验与恢复在 client-core [backup.rs](../crates/client-core/src/storage/backup.rs)（Android 侧步骤在 [BoxBackups.kt](../apps/android/app/src/main/java/io/nekohasekai/sfa/bridge/BoxBackups.kt)），配置条目携带并恢复 `core` 与订阅流量/到期/官网字段（旧备份缺少时为 `auto`/无，官网只保留 http/https 绝对地址），按声明的内核校验内容；恢复覆盖已有配置时复制全部 `TypedProfile` 字段。包含稳定配置身份、界面/应用设置、终端设置与导入字体，以及可选 WebDAV 设置。仅恢复配置时不改其他设置。恢复先暂存文件，再使用客户端存储事务（事务期间界面自身的偏好/排序写入会被拒绝并提示稍后重试）；应用失败回滚文件、数据库和设置。兼容策略保留其他配置，覆盖策略在用户确认后删除备份中不存在的配置。

界面偏好的读取、保存及备份恢复共用 [BoxPreferences.kt](../apps/android/app/src/main/java/io/nekohasekai/sfa/bridge/BoxPreferences.kt) 校验，卡片布局接受仪表盘的全部卡片键（含 `VPN`、`Service status`、`Connection stats`、`System HTTP proxy` 及已退役仍被接受的 `Connections`）；已退役的外观键（`appearanceStyle`、`liquidGlass`、`tabAnimation`、`floatingNavigation`、`sidebarExpanded`、`locale`）在读取和恢复时丢弃（界面见 [flclash.md](flclash.md#朴素界面约定)）；新的页面切换偏好为 `tabMotion`（`slide`/`fade`），与退役的 `tabAnimation` 无关。`BoxActivity` 读取 `Settings.Global.ANIMATOR_DURATION_SCALE`，在 `locale` 回复中以 `animatorScale` 提供，回到前台或重新连接界面时若有变化再发 `motion` 事件（`{"scale": …}`），界面按其缩放全部动效（见 [flclash.md](flclash.md#动效)）。首页卡片交互见 [flclash.md](flclash.md#导航与交互约定)。

备份读取/传输上限为 64 MiB，WebDAV 账号与流程在 client-core [webdav.rs](../crates/client-core/src/storage/webdav.rs)（请求经核心扩展，密码由 Keystore 加密）；取消文件选择和未确认上传不能报告成功。HTTP 行为见 [libbox.md](libbox.md)。

原生日志保留所有收到的等级供日志页过滤，`CommandClient` 将当前会话内核的 `setDefaultLogLevel` 回调与日志记录一起传给界面。默认值来自内核实际使用的配置（配置未设级别时三个内核均按 warn 运行，见 [libbox.md](libbox.md#服务层第-2-阶段)；sing-box 高级配置的日志页在未设置时也显示 warn），不自行解析本地文件来推断远端状态；日志客户端只在日志页可见（且应用在前台）时连接（各命令订阅何时连接由 mobile-ffi `sessions.rs` 决定，见 [docs/architecture/phase3.md](architecture/phase3.md)），内核在每次订阅时重放已保存的行；本地配置成功重载后，通过已启动配置快照的变化重新订阅日志，更新默认级别。界面筛选规则见 [flclash.md](flclash.md#导航与交互约定)。

连接页的事件批次在 Kotlin `bridge/BoxStreams.kt` 的单线程通道内按到达顺序转为纯值（只有 NEW 和带快照的 CLOSED 经 JNI 读取连接）后原样转发（`stream` 事件），由 mobile-ffi [runtime.rs](../crates/mobile-ffi/src/runtime.rs) 转为 `connectionEvents`（流断开或本地服务停止时发重置）；界面用 client-core [connections.rs](../crates/client-core/src/connection/connections.rs) 按 libbox `Connections.ApplyEvents` 维护 ID → 连接映射并算出列表与仪表盘计数：UPDATE 按增量更新该行，关闭 5 分钟后淘汰，DNS 连接不显示；本地服务的 DNS 计数仍取内核自己的统计，远程会话按连接列表计数。

日志记录由界面持有：`BoxStreams` 把内核的日志行、清空和默认等级原样转发，mobile-ffi `runtime.rs` 暂存（最多 3000 行，清空丢弃暂存并标记重置），界面最多每秒取一次 `logEvents`（`reset`、变化后的 `defaultLogLevel`、新行）；界面用 client-core [logs.rs](../crates/client-core/src/connection/logs.rs) 去 ANSI、编号、按 3000 条环形保留，ID 跨清空递增。隐藏日志页不清空界面记录，也不影响当前会话的等级同步。

仪表盘与代理组：Kotlin 只上报 libbox 状态原值（`DashboardUiState`、`OutboundGroup` 转换后的列表），界面用 client-core [dashboard.rs](../crates/client-core/src/controller/dashboard.rs) 推导显示（模式仅在可切换时列出、系统代理仅本地服务、远程会话的启动时间），用 [groups.rs](../crates/client-core/src/controller/groups.rs) 在选择节点、测速和切换模式前检查分组、节点与模式；宿主不再重复检查，已失效的选择由内核拒绝。

[BoxDashboard.kt](../apps/android/app/src/main/java/io/nekohasekai/sfa/bridge/BoxDashboard.kt) 的详细内存采样独立于网络/热点轮询，由内存卡片和详情页共享订阅。[VisiblePolling.kt](../apps/android/app/src/main/java/io/nekohasekai/sfa/bridge/VisiblePolling.kt) 仅在订阅可见、应用前台且屏幕亮起时每两秒采样；重新满足条件立即采样，隐藏或断开 EventChannel 时停止。手动释放仍单次执行 libbox 回收并更新统计，不增加自动强制 GC。

应用列表通过 [AppListCache.kt](../apps/android/app/src/main/java/io/nekohasekai/sfa/bridge/AppListCache.kt) 复用一份包查询快照，参考上游基线的 `PackageCache`，先按系统应用/联网权限过滤，再按需读取并缓存应用名称。进入页面和手动刷新重新查询，切换显示范围复用快照；查询策略或语言改变后的查询不复用旧快照，查询失败/取消允许重试。排除应用自身及无 `ApplicationInfo` 的条目。应用列表的排序和图标使用方式见 [flclash.md](flclash.md#导航与交互约定)。

托管应用模式仍在激活前完成扫描，安装/卸载广播沿用自动更新；列表查询完成后同步最新的已保存设置，包含后台更新的托管结果，打开页面不触发 APK/DEX 扫描。手动重新扫描通过既有桥接更新设置。查询策略继续持久化；Android 12 精确定位同时申请 fine/coarse 权限；远程配置卡不使用本地配置名。

## 分流规则

“工具 → 设置 → 分流规则”（[routing.rs](../apps/android/native/src/pages/routing.rs)）为 sing-box、Xray、mihomo 各维护一套独立规则，参照 [Lemonochka/keqdroid](https://github.com/Lemonochka/keqdroid)（AGPL-3.0）的 TrafficRoute / RoutingProfile 设计（只借鉴数据模型，不含其代码）。编译规则、字段映射与已知限制见 [libbox.md](libbox.md#分流规则)。

- 数据：`fkbox-client-core` 的 [routing.rs](../crates/client-core/src/rules/routing.rs)（模型、校验与序列化；界面 [routing.rs](../apps/android/native/src/pages/routing.rs) 与 Kotlin 都用它，Kotlin 经 [ServiceSettings.kt](../apps/android/app/src/main/java/io/nekohasekai/sfa/database/ServiceSettings.kt) 调用；按内核编译仍在 Go `core_routing*.go`）。每个内核一组 `{mode, final, rules}`：`mode` 为 `prepend`（默认，用户规则放在配置自身规则之前，配置规则保留）、`override`（替换配置规则，保留 sing-box 开头的 sniff/hijack-dns/resolve 规则和配置的兜底）或 `off`；`final` 为兜底：`config`（默认，沿用配置）、`proxy`、`direct`、`block` 或 `node`（指定节点/组的 tag）。每条规则含启用开关、名称、内容（`all` 全部流量；`match` 域名/IP/CIDR/`geosite:`/`geoip:`/`full:`/`keyword:`/`regexp:`；`preset` 预设；`list` 规则列表 URL）、目标（同上，无 `config`）、应用包名、网络（any/tcp/udp）、端口（`443,8000-9000`，最多 28 段）；列表顺序即匹配顺序。保存在设置键 `routing_rules`（JSON，版本 1），经服务设置快照（client-core [service_settings.rs](../crates/client-core/src/config/service_settings.rs)）的 `routingRules` 随完整备份导出/恢复并由同一模型校验（每核最多 500 条，每条最多 5000 项）；存储文本与旧版 Kotlin 写出的逐字节相同；旧备份没有该键时不改动现有规则。
- 预设（国内常用）：绕过大陆（`geosite:cn`、`geoip:cn`、`geosite:private`、`geoip:private` → 直连）、GFW 列表走代理其余直连（`geosite:gfw` → 代理，再加一条“全部流量 → 直连”）、广告拦截（`geosite:category-ads-all` → 拦截）、局域网直连（`geosite:private`、`geoip:private` → 直连）。预设只是插入普通规则，目标可改。
- 界面：顶部三个内核分段按钮；其下一行显示上次启动的分流状态（已应用 N 条与警告数；未生效时红字），点开为状态页，列出内核、时间、未生效原因、全部警告，并可“重载服务”重试；然后是合并方式与兜底两行，规则列表可点开编辑、开关、长按或拖动手柄排序；右上角添加规则，菜单中有四个预设、“复制自 <内核>”（替换当前内核规则前确认，复制后各自独立）和预览。规则在页面内编辑草稿，点保存才写入。编辑“指定节点或组”时的候选来自 `routingTargets(core)`（libbox `CoreRoutingTargets`，读所选配置，仅当它运行在该分页的内核上时显示；mihomo 不列 proxy-provider 里的节点）。条目输入框每行一条，一行内也可用逗号或空格分隔；以 `regexp:` 开头的行整行为一条（正则可含逗号和空格）。“匹配方式”选“域名、IP 或 geo”而条目留空、但选了应用/网络/端口时，保存为按这些条件匹配的规则（内容 `all` 加条件，即“仅按应用”的规则；libbox 对条目为空但有条件的 `match` 作同样处理，应用全部无效时跳过该条而不变成全部流量）；条目与条件都没有时不能保存。列表中未命名的仅应用规则以应用名称（`appLabels`）为标题和摘要。Xray 无法按应用分流，Xray 分页中带应用的规则保存时提示移除应用或改用 sing-box/mihomo（或访问控制）。预览调用 `routingPreview`，对当前选中配置按其实际内核编译草稿，显示内核、应用条数、警告和交给内核的完整内容。
- 生效：[BoxService.kt](../apps/android/app/src/main/java/io/nekohasekai/sfa/bg/BoxService.kt) 在解析出实际内核后调用 `CommandServer.startOrReloadCoreServiceWithRouting(core, content, 该内核的规则 JSON, overrides)`，只改交给内核的内容，不写磁盘上的配置；链接转换的配置、Xray 合并数组、重载、切换配置/内核都走同一入口，所以切换后自动使用新内核的规则。成功后服务日志出现“已应用 <内核> 分流规则 N 条”，被跳过或调整的规则逐条以“分流规则：…”警告记录。返回的 `CoreRoutingResult` 存入 `BoxService.routingStatus`（分流页状态行与状态页读取）；规则未能应用（编译失败、规则 JSON 读不出，或内核带规则起不来而按原配置重启）时服务照常运行，并弹出 Toast“分流规则未生效，已按原配置运行”及原因（同一原因只提示一次）。保存时本地服务正在运行则重载（[ServiceSettingsChange](../apps/android/app/src/main/java/io/nekohasekai/sfa/bridge/ServiceSettingsChange.kt)），失败时恢复原规则并按原规则恢复服务；重载失败且规则或配置有变化时，沿用上次成功启动的配置与规则（`StartedConfig.routing`）。远程会话不使用本机规则。
- 测试：client-core `routing::tests` 覆盖编码/解码（含与 Kotlin 旧文本一致）、备份线格式往返、校验、端口写法、条目拆分、预设；编译结果由 Go 的 `TestCoreRouting*` 用各内核自己的检查器验证（见 libbox.md）。

## 请求抓取与 MITM

使用入口为“工具 → 请求”。先在“MITM 证书”中生成 CA，导出并通过 Android 设置或 Root 管理器安装，返回后刷新信任；随后在抓包设置中开启 HTTPS 并设置范围，启动本地服务和抓包，再重连目标应用。安装路径和目标应用的信任限制见下文；HTTP 抓取不需要 CA。

[BoxCapture.kt](../apps/android/app/src/main/java/io/nekohasekai/sfa/bridge/BoxCapture.kt) 提供本地请求启停、筛选、列表/详情、域名、HAR/cURL 导出以及 CA 管理。开始抓包需本地服务已 Started；设置和证书准备完成后，在配置互斥区复核服务实例、运行配置和当前选择，再调用该服务的 MITM 绑定。启动失败、返回期间取消或状态确认失败会回收本次已启动会话。切换远程会话停止本地抓包，远程会话拒绝读取或操作本机记录与证书。核心协议、路由、限额和停止行为见 [libbox.md](libbox.md#httphttps-请求调试)。

抓包设置由 client-core [capture.rs](../crates/client-core/src/tools/capture.rs) 读写（[CaptureSettings.kt](../apps/android/app/src/main/java/io/nekohasekai/sfa/bridge/CaptureSettings.kt) 只做调用封装和退役 Keystore 密钥清理），将 version 2 的 HTTPS、应用/端口、域名包含/排除、QUIC 回退、域名提取和正文限额保存到私有 `noBackupFilesDir/http-debug/settings.json`，最多 64 KiB。多个 Activity 共用操作互斥锁，保存检查内容修订并用 AtomicFile；读取恢复中断写入的备份，再核对大小。抓包期间不能改设置，保存不启动抓包或重载配置。旧版本设置迁移保留有效筛选，同时关闭 HTTPS，避免将被动观察设置直接变成主动 MITM；旧库路径、函数偏移、改写、脚本等字段被删除，新保存拒绝退役字段。配置和备份不携带抓包设置、记录或证书。旧配置扩展与遗留数据的清理见 [核心兼容说明](libbox.md#旧抓包配置清理)。

[MitmCertificate.kt](../apps/android/app/src/main/java/io/nekohasekai/sfa/bridge/MitmCertificate.kt) 只在用户点击生成时创建 CA。P-256 根私钥使用随机独立 alias 保存到 Android Keystore，不可导出；Go 传入 SHA-256 摘要，由 `NONEwithECDSA` 签名，再由 Go 验证。API 24 起所需操作依据 [AOSP Android 7 Keystore 实现](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-7.0.0_r1/keystore/java/android/security/keystore/AndroidKeyStoreECDSASignatureSpi.java)。公钥证书和 alias 保存于不参与备份的 `http-debug/mitm-authority.json`，AtomicFile 记录最多 32 KiB。重新生成检查修订、先创建新密钥并签发，原子写入成功后才删除旧 alias；失败保留旧记录，清理本次新密钥。运行中不能重新生成，生成本身不安装或启用抓包。

证书快照分别报告有效期、私钥可用性、SHA-256、公钥身份及 AndroidCAStore 中按完整 DER 匹配的用户/系统信任。匹配遍历所有 alias，兼容 subject-hash 冲突；进程内信任库存在该证书不代表任意目标应用会信任。Android 7 起很多应用默认不信任用户 CA，pinning、自有证书库、工作资料和独立 namespace 也会限制抓取。界面安装/刷新流程见 [flclash.md](flclash.md#导航与交互约定)。

CA 导出仅包含公钥 CRT。Android 7–10 使用 KeyChain 安装入口，Android 11 及以后打开安全设置，由用户先导出、再在设置中选择 CA 文件；返回值仅表示设置已打开，不能作为安装成功证明。[MitmCertificateModule.kt](../apps/android/app/src/main/java/io/nekohasekai/sfa/bridge/MitmCertificateModule.kt) 可导出 `bbox_mitm_ca` ZIP，交给 Magisk、KernelSU 或 APatch 管理器安装并重启，应用不执行自动 Root 注入。

[模块脚本](../apps/android/app/src/main/assets/mitm-ca/inject.sh) 参考当前 FlClash 的 system/Conscrypt APEX namespace 处理，在各实际 namespace 内复制其原有证书集合到独立 tmpfs，补入公钥 CA、处理 hash.N 冲突并设权限/SELinux 标签，准备成功后才 bind 到目标。post-fs-data 处理启动继承路径，service 补入已运行应用 namespace；不先清空证书目录或写系统分区。挂载失败保留原证书库并记录 `mount.log`。更新/删除模块后需重启，旧版或手工安装的其他模块由用户在原管理器中移除。模块 ZIP 包含来源说明和 GPL-3.0 许可；逐文件参考见 [来源清单](../resources/flclash-manifest.json)。该路径仍需实际 Root 管理器、系统版本与目标应用验收。

Root/eBPF 采集器、目标 TLS 库发现、FD 会话和手动函数偏移已移除。[IRootService](../apps/android/app/src/main/aidl/io/nekohasekai/sfa/bg/IRootService.aidl) 的事务 10 退役，不复用；原 2/3 空缺保持，普通 Root/Shizuku、VPN 授权、自动重定向和 Shell 能力保留。

HAR/cURL、域名、CRT 和模块导出使用 Android 文档选择器，取消返回 false，写入失败不报告成功；证书在选择文件后再次检查修订。请求导出的私有临时文件用后删除。独立 `capture.logs` / `capture.logs.clear` 读取和清空抓包诊断，CA/服务准备失败也写入日志；日志读取不依赖请求存储，清日志不删除请求。

### 配置驱动的 HTTP 改写

配置支持 B-box 的 `experimental.http_rewrite` 扩展，字段、完整示例、Surge 参考及 JS 支持范围统一见 [libbox.md](libbox.md#json-配置中的-http-改写)。规则与内嵌资源属于配置，因此随原始/高级编辑、分享、订阅更新及配置备份保存；本机抓包设置、CA 和记录仍不进入备份。旧 `http_debug` 和旧抓包设置中的脚本不会自动迁移或执行。

[BoxService.kt](../apps/android/app/src/main/java/io/nekohasekai/sfa/bg/BoxService.kt) 在启动/重载时检查该配置是否需要 MITM CA，通过新增 OverrideOptions 的 RewriteDirectory/RewriteCertificate/RewriteSigner 传入私有存储路径、公钥证书与 Keystore 签名回调。没有证书时启用 HTTPS 会启动失败，应用不自动生成密钥或安装证书。libbox 先校验规则/CA 并准备存储，再启动核心，成功后在同一生命周期锁内激活改写，最后向 Android 返回成功。

保存及应用继续经过 ProfileContent 的修订校验、完整 `Libbox.checkConfig`、原子写入与既有失败恢复；配置应用、恢复及后台启动使用同一服务入口。切换或重载配置会结束旧拦截连接并加载新规则，恢复旧配置也会重新加载旧规则。启动失败不能留下仅部分生效的改写或报告服务成功。

`ReadUICaptureStatus` 新增 `rewriteActive` / `rewriteRules`，原 `active` 仍表示手动请求记录。开始记录时合并本机记录范围与配置处理范围，各自按自己的筛选执行；停止记录恢复为仅配置处理。服务关闭结束两者，切换到远程会话仍禁止操作本机记录/证书并停止手动记录；已经运行的本地配置规则随其本地服务生命周期继续生效，不作为远端状态展示。

CA 管理与服务启动共用 `MitmCertificate.accessLock`，防止加载签名器期间轮换密钥；运行中的手动抓包或配置处理会拒绝重新生成 CA。界面为原有证书页增加相应禁用状态。HTTP 改写编辑与状态展示见 [flclash.md](flclash.md#配置页与内核差异)。

`ProfileRecoveryTest.profileHTTPRewritingUsesKeystoreRoutingAndReloadRecovery` 使用实际 gomobile、Keystore、mixed CONNECT 和独立本地 HTTP 服务器，覆盖 HTTPS 离线静态/脚本 Mock、跨域 Map Remote 重新匹配 sing-box 入站/目标规则、Body Rewrite 后的 JS 响应改写、停记录后继续处理、证书轮换拒绝、端口占用导致的配置回滚和服务恢复。该用例由 API 24/31/35 smoke 运行，测试的存在不代表当前源码已执行通过。

## 已移除能力与兼容入口

- **Xposed/LSPosed：** 删除模块 API、Hook、Provider/AIDL、模块注册、vendored `libxposed-api`、Gradle 子项目/依赖及特权增强页面/设置。普通 VPN、Root/Shizuku 查询、桥接/自动重定向、Shell 和报告继续保留。
- **Android 5/6：** 删除 `otherLegacy`、`src/minApi21`、`src/otherLegacy`、legacy 核心依赖及 API 21 专用依赖版本。`play` 和 `other` 均使用 API 24+ 实现。Gradle 的 `useLegacyPackaging` 是原生库压缩方式，不代表仍支持旧 Android 分支。
- **旧更新下载/静默安装：** 删除 GitHub/F-Droid/Play 旧更新器、APK 下载和安装工具、对应 Root/Shizuku Binder 方法、Manifest 权限、设置及资源。设置/启动器不再显示旧自动更新询问、弹窗和徽标。
- **旧任务兼容：** 启动时取消 WorkManager 的 `AutoUpdate`。原 `UpdateWorker` 占位类已删除：残留任务若在取消前被调度，WorkManager 找不到该类，只会把它记为失败，不会崩溃，也不会下载或安装。配置订阅的 `UpdateProfileWork` 仍正常工作。
- **Binder/设置兼容：** Root/Shizuku 已退役的安装事务编号 2 不复用；Root 的模块专用编号 3 与采集器编号 10 也保持空缺，其余编号不重排。删除功能的历史设置不再读取，不清空无关设置和数据库。

关于页仍提供独立检查（client-core [updates.rs](../crates/client-core/src/update/updates.rs)，请求经核心扩展 `UIHTTPGet`）：读取 [koko123as/fk-box](https://github.com/koko123as/fk-box/releases) Releases（含预发布），以 `fk-box-version-metadata.json` 的 `application_id`、`version_code`、`version_name` 核对安装身份和版本，再匹配设备 ABI 的 `fk-box-<版本>-<ABI>.apk`。改名前的 Release 使用 `FK-box-version-metadata.json` 与 `FK-box-<版本>-<ABI>.apk`，两种前缀都接受（新前缀优先）：REST 列表按 Release 实际列出的资产选 APK，`latest/download` 在新名称返回 404 时再取旧名称的元数据，并按其前缀拼 APK 名。没有匹配 APK 时不提供下载资产。它不恢复原更新器的后台下载/静默安装链路。

## 地理数据资源

Xray 配置里的 `geoip:`/`geosite:` 规则和 mihomo 的 GEOIP/GEOSITE/IP-ASN 规则需要地理数据文件。目录与下载校验由 libbox 提供（见 [libbox.md](libbox.md#地理数据)），应用侧参照 FlClash 的 `initGeo` 与资源页：

- 内置与按需：四个文件都按 [geo-assets.properties](../apps/android/app/geo-assets.properties) 固定在 `MetaCubeX/meta-rules-dat` release 分支的同一提交并记录 SHA-256，但 APK 只带 `GEO_BUNDLED` 列出的 `geosite.dat`（Xray 与 mihomo 的 GEOSITE 共用）和 `geoip.metadb`（mihomo 的 GEOIP）。`geoip.dat` 与 `geoip.metadb` 是同一份 IP 数据的两种格式，只有 Xray 读前者；`ASN.mmdb` 只用于 mihomo 的 IP-ASN 规则，二者改为首次使用时下载。构建时 [apps/android/app/build.gradle.kts](../apps/android/app/build.gradle.kts) 的 `prepare<变体>GeoAssets` 任务只下载内置的两个文件，逐个核对 SHA-256，放入 APK 的 `assets/geo/`，并写入 `manifest.json`：`files` 为内置文件（哈希/大小），`remote` 为按需文件的固定地址（`https://raw.githubusercontent.com/<仓库>/<提交>/<文件>`，与构建使用的地址相同）和 SHA-256，另有提交与提交时间。文件不入库，缓存在 `.gradle/geo-assets/<提交>`。更新固定版本用 `python3 scripts/bump_geo_assets.py [提交]`（`--dry-run` 只打印），四个文件的哈希都要更新。
- 何时放到磁盘：[GeoResources.kt](../apps/android/app/src/main/java/io/nekohasekai/sfa/bg/GeoResources.kt) 不在应用启动、打开资源页或自动更新时解压，只在 Xray/mihomo 启动或重载前（`prepareForCoreBlocking`，在 MITM 证书锁之外）按 `GeoPolicy.needed`（client-core [geo.rs](../crates/client-core/src/geodata/geo.rs) `needed`）准备该内核用到的文件：Xray 只在配置内容或编译后的分流规则含 `geosite:`/`geoip:`（或 `geosite.dat`/`geoip.dat` 的 `ext:` 写法）时需要对应文件；mihomo 总是准备 `geosite.dat` 和 `geoip.metadb`（GEOSITE/GEOIP 规则与 DNS fallback-filter 会读取），含 `IP-ASN` 时加 `ASN.mmdb`，`geodata-mode: true` 时加 `geoip.dat`；sing-box 用远程规则集，不需要这些文件。所以只用 sing-box 的用户磁盘上没有解压副本，内置文件也只解压该内核要的那几个。
- 解压：内置文件解压到 `Libbox.geoDataDir()`，边解压边算 SHA-256，与清单一致才 rename 覆盖。状态记在同目录的 `geo-resources.json`（来源 builtin/download、哈希、数据时间、上次成功检查时间）。规则（client-core `geo::should_extract`）：文件缺失一定解压；已记录的文件只在其数据时间早于内置提交时间且哈希不同才被替换，所以较新的下载版不会被内置文件覆盖，而应用升级带来的更新内置数据会替换更旧的文件；没有记录的文件（mihomo 自己下载的）按修改时间同样判断。每个内置文件每个进程只完整检查一次，之后只在文件缺失时重新检查。
- 首次使用时下载：需要的文件不在磁盘上且不在 APK 中时，先下载清单 `remote` 中的固定文件（libbox `GeoUpdate.fetchExpected`：SHA-256 与清单不符则拒绝，不替换任何文件），失败再用资源页配置的地址（默认 latest，按格式校验）；用户改过该资源的地址（镜像）时先试其地址。全部下载共用 90 秒预算（服务启动确认的上限为 120 秒），非最后一次尝试最多用剩余时间的三分之二，超时会中止 Go 侧下载。下载写临时文件、校验后 rename，失败不留下半个文件。成功后记为“已下载”，固定文件的数据时间记为固定提交时间。失败时服务仍照常启动：弹出 Toast“地理数据下载失败，可在 工具 › 资源 中手动下载或更换下载地址”及原因；内核若因此启动失败，错误提示末尾附同样说明。下次启动仍缺失时会再次尝试。已有固定文件不会因应用升级换了固定提交而重新下载，按资源页或自动更新刷新。
- 下载（资源页）：资源页的单独更新、全部更新和自动更新都调 `Libbox.newGeoUpdate().fetch(...)`，传入上次记录的 SHA-256、`ETag`、`Last-Modified`（`geo-resources.json` 的 `etag`/`lastModified`），文件仍与记录一致时发条件请求，304 视为“已是最新”且不下载。失败保留原文件并显示错误；成功后记为“已下载”。协程取消（离开资源页、WorkManager 停止任务）会调用 `cancel()` 中止 Go 侧下载。下载经应用自身网络，服务运行时按分应用设置可能经过当前代理。资源页列表本身不解压也不下载：尚未放到磁盘的内置文件显示内置大小与提交时间，按需文件显示“首次使用时下载”；“全部更新”跳过尚未下载的按需文件，单独更新仍可下载。
- 设置：`geoAutoUpdate`（默认关）、`geoUpdateInterval`（小时，1–8760，默认 24）、`geoUrls`（只存与默认不同的地址，仅 http/https）。三项属于 client-core 的服务设置快照（[service_settings.rs](../crates/client-core/src/config/service_settings.rs)），随备份导出/恢复并由其校验；地址的读取、校验（与 `java.net.URI` 规则一致）和写入在 [geo.rs](../crates/client-core/src/geodata/geo.rs)。桥接命令 `geo.list`、`geo.update`、`geo.setUrl`。
- 自动更新：开关、间隔与哪些文件到期由 client-core `geo::schedule`/`geo::due` 决定（读 `geo-resources.json` 的记录）；[UpdateGeoWork.kt](../apps/android/app/src/main/java/io/nekohasekai/sfa/bg/UpdateGeoWork.kt) 只是 WorkManager 入口，用周期任务（间隔即设置的小时数，需联网，失败指数退避），每次只更新已在磁盘上、且上次成功检查早于间隔的文件（没有内核用过的文件不下载）；关闭时取消任务。应用启动和修改这两项设置时重新登记。
- 界面：工具页顶部分组的“资源”（[resources.rs](../apps/android/native/src/pages/resources.rs)），说明见 [flclash.md](flclash.md#导航与交互约定)。
- 单元测试：client-core `geo::tests` 覆盖解压判断、到期判断、地址的读写容错与校验、各内核需要的文件（`needed`），`service_settings::tests` 覆盖三项设置的校验；[GeoPolicyTest.kt](../apps/android/app/src/test/java/io/nekohasekai/sfa/bg/GeoPolicyTest.kt) 只剩记录文件与含/不含 `remote` 的清单解析。
- APK 体积：之前内置四个文件共约 38.3 MB（geoip.dat 16.5 MB、geosite.dat 4.4 MB、geoip.metadb 8.4 MB、ASN 9.1 MB），2026-11-06 实测（ARM64 debug，Artifact 未压缩打包）四个文件使 APK 从 78,069,486 字节（630f444）增至 93,208,464 字节（59d5b01），增加约 14.4 MiB，首次运行另解压约 38 MB。现在只内置 geosite.dat 与 geoip.metadb（约 12.8 MB，未压缩），geoip.dat 与 ASN.mmdb（约 25.5 MB）首次使用时下载；APK 实际减少量与内置文件的压缩比有关，尚未在 CI 实测，需以下一次构建的 APK 大小为准。未覆盖：真机解压耗时、首次使用下载、自动更新、Xray/mihomo 实际读取 geo 规则。

## 构建与发布

[apps/android/app/build.gradle.kts](../apps/android/app/build.gradle.kts) 只从本地 `apps/android/app/libs/fkhost.aar` 消费内核（阶段 6：单 sing-box 内核 + Rust 大脑，见 [brain-android.md](architecture/brain-android.md)），界面为 `crates/client-ui/` 的 Rust + Slint 源码（经 `buildRustUi` 编译为 `libfkui.so` 打包），不再依赖 Flutter SDK、Flutter AAR 或其 Maven 仓库。三内核 libbox（sing-box、Xray、mihomo 各自完整运行）已在阶段 6 移除。

Gradle 默认 ABI 过滤为 ARMv7、ARM64、x86_64 并生成 universal；`-Parm64Only=true` 只生成 ARM64，`-PemulatorOnly=true` 只生成 x86_64，二者都关闭 universal。fkhost 只构建 arm64，因此 CI 的 Debug、Release 与模拟器构建均使用 `-Parm64Only=true`。

[build-release.yml](../.github/workflows/build-release.yml) 仅手动触发，发布 Android 7.0+ 的 ARM64 `otherRelease`。Release 与模拟器构建共用 [build-fkhost](../.github/actions/build-fkhost/action.yml)，内核使用 JDK 17，应用使用 JDK 21；具体 SDK/Build Tools/NDK 版本以共享 Action 和 Gradle 为准。本机未经明确要求不构建，见 [AGENTS.md](../AGENTS.md)。

CI 缓存（均为 GitHub Actions 缓存，不含密钥或签名文件）：

- `fkhost.aar`：[build-fkhost](../.github/actions/build-fkhost/action.yml) 以 `version.properties` 中的核心/Go/Xray/mihomo 版本（不含 `VERSION_NAME`/`VERSION_CODE`）以及 `apps/android/fkhost/**`、`crates/brain-ffi/**`、`crates/client-core/**`、`Cargo.lock`/`Cargo.toml` 和该 Action 文件的哈希为键；命中时跳过 Rust 大脑与 gomobile 构建，未命中时构建后立即保存。
- Rust：`Swatinem/rust-cache` 缓存 `~/.cargo`（含 `cargo-ndk`）与 `target/`，在安装 `cargo-ndk` 之前恢复；键随 `Cargo.lock` 与 rustc 版本变化。Slint 的 Skia 预编译包由 `skia-bindings` 下载到其 `OUT_DIR`（`target/<目标>/release/build/skia-bindings-*/out`，下载文件在 `.cache/`），依赖的构建输出随该缓存保留，命中时不再下载；不使用指向本地文件的 `SKIA_BINARIES_URL`，因为文件名不匹配时 `skia-bindings` 会退回从源码完整构建 Skia。`ui-slint` 自身与 `vendor/` 中的 Slint 补丁 crate 仍每次重新编译。
- Gradle：`gradle/actions/setup-gradle`，`main` 与 `ui/*` 分支写入，其他分支只读；内置地理数据的下载目录 `apps/android/.gradle/geo-assets` 按 [geo-assets.properties](../apps/android/app/geo-assets.properties) 的哈希缓存。

发布签名使用以下仓库 Actions Secrets：

| Secret | 用途 |
| --- | --- |
| `KEYSTORE_BASE64` | 发布密钥文件的 Base64 内容 |
| `KEYSTORE_PASS` | 密钥文件密码 |
| `ALIAS_NAME` | 发布密钥 alias |
| `ALIAS_PASS` | 对应密钥密码 |

工作流将已有密钥解码至检出目录之外的 `$RUNNER_TEMP/release.keystore`，以 `KEYSTORE_FILE` 告知 Gradle（未设置时回退未入库的本地 `apps/android/app/release.keystore`），结束时清理，不上传密钥。仓库不再跟踪任何发布密钥文件。Gradle 属性优先读取直接环境变量，再回退 `LOCAL_PROPERTIES` 和 `local.properties`。本地签名文件不入库，不重新生成发布密钥。

Release 构建开启 R8 与 `isShrinkResources`（[keep.xml](../apps/android/app/src/main/res/raw/keep.xml) 保留系统可见的图标；代码中没有按名称查找资源）；`useLegacyPackaging` 保持原样。

应用自身的 Baseline Profile 位于 [baseline-prof.txt](../apps/android/app/src/main/baseline-prof.txt)，AGP 将其与库的配置合并进 `assets/dexopt/baseline.prof`，由 `profileinstaller` 在旁加载安装时编译。当前文件为手写的启动/常用页面规则（CI 模拟器为 x86_64，arm64 libbox 无法运行，不能在 CI 中采集）。[baselineprofile](../apps/android/baselineprofile/) 模块提供 Macrobenchmark `BaselineProfileRule` 生成器，仅在 `-PbaselineProfile=true` 时加入构建（同时定义应用的 `benchmark` 构建类型：Release 代码、不混淆、`profileable`、CI 调试签名），常规与 Release 构建不受影响。在 API 33+ 的 ARM64 设备上于 `apps/android/` 目录运行 `./gradlew -PbaselineProfile=true -Parm64Only=true :baselineprofile:connectedOtherBenchmarkAndroidTest`，再把输出的 `*-baseline-prof.txt` 替换到 `apps/android/app/src/main/baseline-prof.txt`。

Release 先调用 [newui.yml](../.github/workflows/newui.yml) 完成多内核、核心扩展、MITM 测试及界面源码检查，再构建 arm64 libbox，运行 `testOtherDebugUnitTest` 及保留 R8 的签名 Release 构建；单元测试成功或失败后均上传 HTML/XML 报告至 `release-unit-tests` Artifact（报告已生成时），保留 30 天。签名密钥在单元测试通过后才准备。[verify_release.py](../scripts/verify_release.py) 在 APK 上传前校验唯一 APK、最低 API 24、ARM64、应用包名/版本/标签、启动 Activity、MITM 模块 assets、内置地理数据（清单提交与 `geo-assets.properties` 一致；`GEO_BUNDLED` 的文件已打包且哈希相符，其余文件未打包、清单 `remote` 记有固定地址与哈希）、退役采集器未打包及签名。APK 文件为 `fk-box-<版本>-arm64-v8a.apk`（安装身份 `io.fkbox.app`、标签 `fk-box`），所在 Artifact 名为 `fk-box-<版本>-arm64-v8a-release`，保留 30 天。

`publish_release` 默认开启；设为 `false` 时仍构建、校验并上传 Actions Artifact，仅跳过发布。版本号按发布日（北京时间）自动生成：[release_version.py](../scripts/release_version.py) 在构建前把 `VERSION_NAME` 设为当天第一次发布 `YYYY.MM.DD`、之后依次 `YYYY.MM.DD.2`、`.3`……，`VERSION_CODE` 为 `YYMMDDNN`（始终大于旧的 1.x 版本码）；同一提交当天重跑沿用已有 tag。仓库里的 `version.properties` 只是本地构建的默认值。[publish_release.py](../scripts/publish_release.py) 要求 Release tag 严格等于 `VERSION_NAME`（不加 `v`）且指向本次源码 SHA；已有 tag 指向其他提交时失败，不移动版本标签。同一提交重跑可重传资产。发布 APK 及 `fk-box-version-metadata.json`，alpha/beta/rc 标为预发布；另以单独、失败不阻断的上传附带同内容的 `FK-box-version-metadata.json`，让改名前的安装仍能发现新版本（它们只认 `FK-box-*` APK，因此只会看到“有更新”并打开 Release 页面，不会直接下载）。

## 验证与历史记录

| 入口 | 配置的验证范围 |
| --- | --- |
| [verify_newui.py](../scripts/verify_newui.py) | `flutter/` 已删除且无 Flutter 依赖、Slint 界面入口、Material Design 3 界面约束（禁止着色器/`blur()` 滤镜与 `backdrop-blur`，悬浮导航栏与顶部应用栏为不透明 `Surface`，可点按表面带水波纹，列表增删与底部表单拖动的动效接入，Slint ≥ 1.19 且 vendored crate 同版本；阴影只经 `Theme.shadow-*` 令牌；页面不写十六进制颜色，取色器与二维码除外；M3 令牌与组件存在）、占位页、旧 UI/更新依赖、退役抓包实现残留及补充参考来源的静态检查 |
| [newui.yml](../.github/workflows/newui.yml) | `main` push、目标为 `main` 的 PR、手动触发或 Release 复用；仅 Markdown 的 push/PR 跳过。运行多内核 gRPC 覆盖检查、内核识别/转换/校验、HTTP/MITM/改写/JS 与自动重定向布局等 Go 测试，以及界面源码检查 |
| [newui-android.yml](../.github/workflows/newui-android.yml) | 手动触发；JVM 回归、`play` Kotlin 编译、`other` ARM64 APK/仪器测试构建及 API 31/35 模拟器 smoke（只有 arm64 核心，x86_64 镜像从 API 30 起才有 ARM 转译） |
| [build-debug-apk.yml](../.github/workflows/build-debug-apk.yml) | 手动触发；界面源码检查、ARM64 `otherDebug` APK（Artifact `fk-box-<版本>-<SHA>-arm64-v8a-debug`）及 JVM 测试 |
| [build-release.yml](../.github/workflows/build-release.yml) | 手动触发；复用 newui.yml 的核心/源码验证，运行 JVM 测试、签名 ARM64 Release、安装身份/产物校验及可选发布 |

JVM 回归覆盖服务启动确认/失败/超时、设置、日志等级/边界和 QRS；[ServiceSettingsChangeTest.kt](../apps/android/app/src/test/java/io/nekohasekai/sfa/bridge/ServiceSettingsChangeTest.kt) 覆盖服务开关保存后的启动确认、失败/超时/取消恢复及恢复错误保留；[ProfileChangeTest.kt](../apps/android/app/src/test/java/io/nekohasekai/sfa/database/ProfileChangeTest.kt) 覆盖配置持久化失败、应用超时/取消、恢复确认及恢复错误保留。[ProfileRecoveryTest.kt](../apps/android/app/src/androidTest/java/io/nekohasekai/sfa/bridge/ProfileRecoveryTest.kt) 覆盖实际 libbox 二进制导入、查询策略持久化、端口占用导致的 JSON 编辑/配置切换/手动与自动订阅/完整备份恢复失败，以及停服和未选中配置更新的服务状态。界面验证范围见 [flclash.md](flclash.md)。

模拟器统一入口为 [android_smoke.py](../scripts/android_smoke.py)：安装应用和仪器测试 APK，分别重置应用数据后运行 UI smoke 与原生回归，前一组失败不跳过后一组。UI 覆盖首次启动、远程 JSON 导入、VPN 授权、启停、实际内核分组及进程重启后配置保留；原生组运行全部仪器测试并拒绝零测试结果。`build/android-smoke/` 保存截图、logcat、失败堆栈、仪器测试输出及带源码 SHA 的 `results.json`，超时也保留日志。启动阶段允许截图后关闭一次 Google APIs 模拟器的 Pixel Launcher ANR 弹窗，再重新打开 fk-box；不关闭 fk-box 自身的 ANR 或其他错误。脚本仅用于 CI 模拟器，不能对保有用户数据的设备执行。

查询策略回归在每次切换 Root/Shizuku 并刷盘后直接读取客户端存储中的持久值，同时核对状态流，不以读取同一内存缓存代替持久化验证。

`ProfileRecoveryTest.logLevelFollowsSuccessfulConfigurationReloads` 另覆盖真实内核日志订阅的默认级别、运行中修改 `log.level` 后刷新，以及移除该字段后恢复当前核心的默认值。

`ProfileRecoveryTest.localProfileTimesFollowSuccessfulContentChanges` 覆盖本地创建/内容编辑的时间记录、无变化保存和重命名不刷新时间、应用失败恢复原内容与时间；二进制订阅测试另检查手动 JSON 编辑保留导入的订阅时间。

`ProfileRecoveryTest.captureSettingsAreLocalAndCheckRevisions` 使用实际 libbox 和 AtomicFile，覆盖 version 2 设置迁移/保存/重新打开、修订冲突、主动 MITM 默认关闭和退役选项拒绝。[MitmCertificateTest.kt](../apps/android/app/src/androidTest/java/io/nekohasekai/sfa/bridge/MitmCertificateTest.kt) 在 API 24/31/35 使用实际 Android Keystore 与 gomobile，验证 CA 自签名、预哈希签名、私钥不可导出、无自动生成、修订冲突/轮换、AtomicFile 恢复、密钥缺失、大小限制，以及 CRT/模块仅含公钥和完整脚本；不安装设备证书或 Root 模块。

`ProfileRecoveryTest.routedMITMUsesKeystoreAndTheOriginalOutbound` 将实际 Android TLS 客户端经 mixed CONNECT 入口送入本地服务，使用 Keystore CA 解密、原 selector/direct 出站与独立证书库验证 HTTPS 测试服务器，再核对 URL、响应、重复 Cookie、原路由、TLS 元数据、cURL 导出及重载停抓。[android_smoke.py](../scripts/android_smoke.py) 在 CI 临时目录生成独立测试服务器证书，经 ADB reverse 提供 HTTPS 服务，只把公钥 CA 传给仪器测试；私钥不写入仓库、设备或上传产物，套件结束删除。单独运行这条仪器测试需要脚本提供的 `mitmOriginPort` / `mitmOriginCA` 参数。模拟器中的测试客户端显式信任本次 CA，不代表第三方目标应用信任或 Root 模块已经实机通过。

[DashboardConfigCacheTest.kt](../apps/android/app/src/test/java/io/nekohasekai/sfa/bridge/DashboardConfigCacheTest.kt) 通过调用计数覆盖重复轮询不再读取/哈希/解析、地址变化、配置切换、写入/回滚、运行快照更新及错误/取消重试；`ProfileRecoveryTest.lanCacheTracksEqualSizeWritesSuccessfulReloadsAndRecovery` 使用实际文件和 libbox，覆盖同大小同时间戳写入、保存与运行配置分离、成功重载、失败恢复和停服后的 LAN 状态。它们由 Android smoke 的 JVM/仪器测试步骤运行，Release 运行其中的 JVM 测试。

[AppListCacheTest.kt](../apps/android/app/src/test/java/io/nekohasekai/sfa/bridge/AppListCacheTest.kt) 通过查询/名称读取计数覆盖隐藏应用不读取名称、筛选切换复用、刷新及查询策略/语言失效、失败/取消重试和并发查询合并，由 Android smoke 和 Release 的 JVM 测试运行。它不衡量设备上的实际加载耗时。

mobile-ffi `runtime.rs` 的 Rust 测试覆盖日志暂存的增量发送、清空重置、等级只发一次与 3000 行上限，以及仪表盘、代理组与连接的流规则；记录本身（去 ANSI、环形保留、ID 跨清空递增）由 client-core `logs.rs` 的 Rust 测试覆盖。[VisiblePollingTest.kt](../apps/android/app/src/test/java/io/nekohasekai/sfa/bridge/VisiblePollingTest.kt) 用虚拟时间检查不可见/后台/熄屏时零采样、恢复立即采样及取消在途任务；这些 JVM 测试由 Android smoke 和 Release 的 JVM 测试运行，删除前 Flutter 版的通知与订阅测试记录见 [flclash.md](flclash.md#验证与升级参考版本)。这些调用计数不代表实测内存或帧率收益。

上述为已配置的检查，不是当前提交的通过声明。记录实际结果时附源码 SHA、Actions 链接和未验证范围，不能沿用旧裁剪 APK 或旧界面验证记录。

以下两节是迁入本仓库前、在 B-box 仓库（含 `giturass/B-box` 的 Actions）中的历史验证记录，仅作参考，不代表本仓库当前源码已通过；本仓库的验证以 [koko123as/fk-box Actions](https://github.com/koko123as/fk-box/actions) 为准。

### JSON 配置改写验证

2026-11-03，配置改写的验证源码为 `e62ddbe724a1b5f61e3bff96e48b1ba8e82c94fc`，包含最后的标题重叠修正。下表记录实际运行结果；后续仅记录结果的文档提交不改变应用源码。

| Actions | 检出源码 SHA | 实际结果 |
| --- | --- | --- |
| [Core and Flutter validation](https://github.com/giturass/B-box/actions/runs/41575286785) | `e62ddbe724a1b5f61e3bff96e48b1ba8e82c94fc` | Go 扩展/schema、HTTP/MITM/改写/JS 的 `-race`、原路由及重定向布局回归通过；Flutter 分析、203 项测试及 ARM64 Flutter AAR 构建通过 |
| [Android regression and smoke](https://github.com/giturass/B-box/actions/runs/41575670538) | `e62ddbe724a1b5f61e3bff96e48b1ba8e82c94fc` | 44 项 JVM 测试无失败或跳过，`play`/`other` 编译和模拟器应用/仪器测试 APK 构建通过；API 24/31/35 的 UI smoke 与各 18 项仪器测试全部通过 |

已核对三个 `android-smoke-api-*` Artifact 的 `results.json`：源码 SHA 一致，UI/native 均成功；每份 `native-tests.txt` 都记录 18 项通过，且 `profileHTTPRewritingUsesKeystoreRoutingAndReloadRecovery` 成功。该回归的实际覆盖见 [配置驱动的 HTTP 改写](#配置驱动的-http-改写)。模拟器中的自有 TLS 客户端显式信任本次 CA，测试没有安装系统证书或 Root 模块。

本机完成 `git diff --check`、`python3 scripts/verify_newui.py`、Go 格式、Python 语法、配置示例/来源清单 JSON 及文档链接静态检查，未在本机构建或执行 Go/Flutter/Android 测试。最终截图的检查范围见 [flclash.md](flclash.md#验证与升级参考版本)。本次未运行签名 ARM64 Release/R8，未发布 APK 或 GitHub Release；下文旧 Release 不包含本次配置改写。ARM64 实机、真实 Root CA 模块安装与第三方应用信任仍未验收。Surge 配置/脚本支持范围以 [核心文档](libbox.md#json-配置中的-http-改写) 为准。

### MITM 分支验证

以下为 2026-11-03 在 JSON 配置改写加入前的请求录制与 CA 验证，不覆盖上述新扩展。三个 SHA 的 Android 主应用、Go 扩展和 Flutter 生产源码相同；`8869e69` 之后只调整了 Android 仪器测试、smoke 脚本、Flutter CI 的 NDK 准备及文档，未把测试修正记为新的应用实现。后续仅记录结果的文档提交不改变 APK。

| Actions | 检出源码 SHA | 实际结果 |
| --- | --- | --- |
| [Core and Flutter validation](https://github.com/giturass/B-box/actions/runs/41549420394) | `b3dfe817c7838a7485497eced0aa1bcebe1ae6d6` | Go 扩展、HTTP/MITM `-race`、原路由回归、Flutter 分析/交互/布局测试和 ARM64 Flutter AAR 全部通过；显式安装 NDK 后打包成功 |
| [Android regression and smoke](https://github.com/giturass/B-box/actions/runs/41548479075) | `8cc2ca6b64963891d479a671b4a6a58521a16ba1` | 44 项 JVM 测试、`play`/`other` Kotlin、模拟器应用及测试 APK 编译通过；API 24/31/35 的 UI smoke 和各 17 项仪器测试全部通过，含实际 Keystore 签名与原出站 HTTPS MITM |
| [ARM64 Release 构建](https://github.com/giturass/B-box/actions/runs/41546076303) | `8869e6992a59327c11d39ad98457a22f402a23cb` | 复用核心/Flutter 验证，44 项 JVM 测试、R8 签名构建、API 24/ARM64/安装身份/模块资源及签名校验通过；`publish_release=false`，发布步骤跳过 |

三个模拟器的 `mitm-origin.json` 均记录独立 HTTPS 服务器只收到一次 `/probe?raw=1;2`，Host 保留 `origin.test:<实际端口>`；仪器测试同时核对叶证书由本次 Keystore CA 签发、重复 Cookie、响应正文、原 selector 路由、TLS 元数据、cURL 和配置重载停抓。完整结果与诊断在对应运行的 `android-smoke-api-*` Artifact 中。Flutter 截图检查范围见 [flclash.md](flclash.md#验证与升级参考版本)。

[签名 APK Artifact](https://github.com/giturass/B-box/actions/runs/41546076303/artifacts/13189531900) 包含 `B-box-1.15.0-alpha.13-arm64-v8a.apk`，SHA-256 为 `28065565867c0642f1a4f1e784309823b23d4e3d3a63a0a399bd44f76b3621c6`，保留期按 Release 工作流的 30 天设置。该构建未发布 GitHub Release。

本机完成 `git diff --check`、`python3 scripts/verify_newui.py`、Python/模块脚本语法、来源清单及文档链接静态检查。模拟器测试使用显式信任本次 CA 的自有客户端；尚未验收真实 Magisk/KernelSU/APatch 安装与重启、第三方目标应用信任、ARM64 实机运行或完整系统证书设置交互。协议支持与限制见 [libbox.md](libbox.md#httphttps-请求调试)，不能由这些通过记录推断全应用均可解密。

原 `MODIFICATIONS.md` 与 `NEWUI.md` 已按领域归入这四份文档。历史 MOD-001～004、007～008、012 的 Compose 界面由 Flutter 替代；MOD-005/006/009 的品牌、安装身份及发布约定在本文继续维护；MOD-011/015/016 的删除项以本次迁移后的现状为准；MOD-013/014 的核心与工具裁剪已回退；MOD-017 的 UI 适配见 [flclash.md](flclash.md)。旧实现、逐次构建日志与产物哈希可通过 `git log --all -- MODIFICATIONS.md NEWUI.md` 和对应提交的文件内容查询，不再混入当前项目约束。
