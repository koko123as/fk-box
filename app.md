# 与 sing-box-for-android 的差异

本文记录 Android 应用相对上游的当前差异。项目约束见 [AGENTS.md](AGENTS.md)，核心补丁见 [libbox.md](libbox.md)，FlClash 界面的来源及 Flutter 界面情况见 [flclash.md](flclash.md)。

## 比较基线与应用身份

| 项目 | 当前值 |
| --- | --- |
| 仓库 | [koko123as/fk-box](https://github.com/koko123as/fk-box)（`main`；直接前身 [giturass/B-box](https://github.com/giturass/B-box)，经 [koko123as/B-box](https://github.com/koko123as/B-box) 的 `fk-box` 分支迁入并保留完整历史） |
| Android 上游 | [SagerNet/sing-box-for-android](https://github.com/SagerNet/sing-box-for-android)，`dev` 分支 |
| 已合并的比较基线 | [`a548a7903e96266a0046018562c73b192edbfc18`](https://github.com/SagerNet/sing-box-for-android/commit/a548a7903e96266a0046018562c73b192edbfc18)，`1.15.0-alpha.13` |
| FlClash 界面参考 | [v0.9.3 `be84736`](https://github.com/chen08209/FlClash/tree/be84736605527034689c95bf27828ff27d912c5e) + [prism_ui `63450c0`](https://github.com/chen08209/prism_ui/tree/63450c0ac0e8e1b2c0f3ed4ad7926dc94e083185)，见 [flclash.md](flclash.md#参考版本与来源清单) |
| FK-box 版本 / versionCode | `1.15.4` / `754`，以 [version.properties](version.properties) 为准 |
| 应用名 / Gradle 根项目名 / APK 前缀 | `FK-box` |
| 安装包名 / applicationId | `io.fkbox.app` |
| 源码 namespace / 包路径 | `io.nekohasekai.sfa` |
| Android / flavor | API 24（Android 7.0）起，`play` / `other` |

应用基线与核心标签、FlClash 参考提交分别维护；FK-box 的 versionCode 不必等于上游基线的值。以上描述当前源码，不表示已有 Release 包含全部改动。

品牌、安装身份及组件约定：

- 应用标签、Gradle 根项目名和 APK 前缀统一使用 FK-box。图标原图为 [artwork/bbox.png](artwork/bbox.png)，[GenerateLauncherIcons.java](tools/GenerateLauncherIcons.java) 生成普通/圆形、自适应、主题单色及通知图标。保留上游许可和非官方分支说明，不改写真实来源名称。
- namespace、Kotlin/Java 类路径、AIDL 描述符及 RootServer 反射类名继续使用 `io.nekohasekai.sfa`。Provider authorities 使用 `${applicationId}`，服务控制和 USB/Taildrop 等广播使用 `BuildConfig.APPLICATION_ID` 或运行时包名。
- 可与原版 SFA 并行安装，私有配置和数据相互独立；原版数据需导出/导入。Flutter 迁移沿用 B-box 既有安装身份、数据库及配置，不以换 UI 为由清空数据。

## FK-box（本仓库 main）

- 安装身份 `io.fkbox.app`，应用标签、Gradle 根项目名与 APK 前缀为 `FK-box`；源码 namespace、类路径与 AIDL 描述符仍为 `io.nekohasekai.sfa`。应用内更新检查读取 [koko123as/fk-box](https://github.com/koko123as/fk-box/releases) 的 `FK-box-*` 发布资产。
- 界面为 [flutter/](flutter) 下的 Flutter 模块（Dart 包 `fk_box_ui`），按此前 Kotlin + Jetpack Compose 版本（`94f7f6d`）逐页对齐后替换了 Compose 界面，页面对应关系见 [flclash.md](flclash.md#flutter-界面)。[BoxChannel.kt](app/src/main/java/io/nekohasekai/sfa/bridge/BoxChannel.kt) 注册 MethodChannel `io.koko123as.flbox/box` 与 EventChannel `io.koko123as.flbox/events`，把命令分派给 `BoxBridge` 门面。
- [build-debug-apk.yml](.github/workflows/build-debug-apk.yml) 构建核心、Flutter AAR 与应用（ARM64 debug）；[newui.yml](.github/workflows/newui.yml) 在 `main` 推送和 PR 时验证核心，运行源码约束检查、Flutter 分析与测试。

## 界面替换与 Android 桥接

上游与 FK-box 自身的 Compose 页面、导航、编辑器、CameraX/MLKit 原生扫码及相关依赖和无引用资源已移除，所有页面由 `flutter/lib/pages` 的 FlClash Flutter 界面提供（不是旧 SFA 页面）。`compose/` 仅保留 [MainActivity.kt](app/src/main/java/io/nekohasekai/sfa/compose/MainActivity.kt) 和 [ShareActivity.kt](app/src/main/java/io/nekohasekai/sfa/compose/ShareActivity.kt) 两个稳定 Android 组件名，均继承 `BoxActivity`。

下表的原生路径以 `app/src/main/java/io/nekohasekai/sfa/` 为前缀。

| 区域 | 路径 | 当前职责 |
| --- | --- | --- |
| 界面宿主 | `bridge/BoxActivity.kt`（`FlutterFragmentActivity`，加载 Flutter 引擎） | 启动器/分享入口、文档选择、权限、服务状态及配置应用 |
| 通道与命令 | `bridge/BoxChannel.kt`、`bridge/BoxBridge.kt` | MethodChannel 命令与 EventChannel 消息分派到 `BoxBridge` 门面，对接配置、工具和设置 |
| 原生状态 | `backend/` | 仪表盘、分组、连接、日志、Tailscale/Taildrop、诊断和认证状态模型 |
| 平台视图 | `bridge/BoxPlatformViews.kt`、`bridge/BoxBrowserFactory.kt`、`bridge/BoxTerminalFactory.kt` | OpenConnect WebView 与 Ghostty 终端注册为平台视图 `io.koko123as.flbox/openconnect`、`/ghostty`，嵌入 Flutter 页面 |
| 服务与数据 | `bg/`、`database/`、`utils/` | 保留 sing-box 服务、Room、订阅更新及本地/远程命令目标 |
| 应用查询 | [vendor/](app/src/main/java/io/nekohasekai/sfa/vendor/) | 普通、Root/Shizuku 应用查询和托管列表扫描；API 24+ 实现及依赖由 `play` / `other` 共用 |

配置按 `TypedProfile.core` 交给 sing-box、Xray 或 mihomo（识别与校验见 [libbox.md](libbox.md#多内核xray-与-mihomo)），分组、可选节点、延迟和模式由当前内核经 libbox 提供；自动组不可手动选择。VPN 授权、前台服务、连接管理、日志、流量、本地/远程会话及路由保持原有能力边界。具体布局、语言、卡片与工具入口见 [flclash.md](flclash.md)。

Tailscale 端点/节点/出口节点/Ping、Taildrop 收发与分享、SSH/Root/Shizuku 终端、Ghostty 设置和导入字体、OpenConnect/OpenVPN 认证、USB/IP、网络诊断、崩溃/OOM/电量报告均通过新页面使用现有后端。历史 MOD-014 的功能移除已回退，不能按旧裁剪记录删除这些能力。

## 配置、服务应用与备份

[BoxProfiles.kt](app/src/main/java/io/nekohasekai/sfa/bridge/BoxProfiles.kt) 负责导入、编辑、手动订阅更新、配置切换及局域网代理保存；[ProfileContent.kt](app/src/main/java/io/nekohasekai/sfa/database/ProfileContent.kt) 的互斥锁和内容替换流程由前台编辑和后台订阅更新共用。

- 保存前检查内容修订和完整 `Libbox.checkConfig`，以原子写入替换内容。高级配置编辑原生 DNS、路由、规则集、入站、出站、端点和日志；原始 JSON 编辑保留配置元数据及订阅 URL。
- 顶层为对象数组的 JSON（v2rayNG 多个自定义配置导出，或订阅返回此格式）：文件、剪贴板、扫码和外部打开经 `BoxProfiles.decode`/`decodeText` 拆分（[ProfileContent.splitArray](app/src/main/java/io/nekohasekai/sfa/database/ProfileContent.kt) 调 libbox `SplitCoreConfigArray`），每个元素单独识别内核并校验，任一元素失败则整批不保存并指出第几个；`profiles.saveAll` 一次创建全部本地配置，界面提示导入数量。只差一个节点的 Xray 数组由 libbox 合并为一个配置（规则见 [libbox.md](libbox.md#多内核xray-与-mihomo)），以来源名（文件名或远程配置名）命名，节点在一个组里。其他情况名称取元素的 `remarks`，没有时为“来源名 #序号”（[ProfileArray.kt](app/src/main/java/io/nekohasekai/sfa/database/ProfileArray.kt)）。新建远程配置的首次获取是数组时，同一 URL 生成多个远程配置；之后手动/自动更新在 `ProfileContent.replaceLocked` 中按已保存内容的 `remarks`、再按配置名、再按“#序号”回退名选回各自的元素，单元素数组总是匹配，找不到时更新失败并保留原内容。合并后的远程配置每次更新都重新合并；此前按元素拆出的同一 URL 远程配置，更新后各自都会变成合并版，可删去多余的。单个配置不保存数组，原始编辑器粘贴数组会报错。
- 本地配置创建时在 Room 记录时间，原始/高级编辑及局域网入站变更成功保存内容时更新 `lastUpdated`；无内容变化和仅编辑名称等元数据不刷新时间。保存或应用失败与内容一起恢复旧时间。远程配置的该字段仍表示订阅更新时间，手动 JSON 编辑不覆盖它；没有时间记录的旧配置保持未知。界面展示见 [flclash.md](flclash.md#配置页与内核差异)。
- 运行中修改选中配置需应用到服务：模式相同时重载，模式变化时停止并重新走权限/启动流程。需要重启时通过 [ServiceStartResult.kt](app/src/main/java/io/nekohasekai/sfa/bridge/ServiceStartResult.kt) 等待 Started 回调；失败或超时恢复旧文件/设置并尝试恢复原服务。停服时普通保存不会自动启动 VPN。
- 原始/高级 JSON 编辑、手动/自动订阅更新和配置切换共用 [ProfileChange.kt](app/src/main/java/io/nekohasekai/sfa/database/ProfileChange.kt) 的失败恢复流程。订阅保存或应用失败恢复旧内容和更新时间；切换失败恢复原选择及服务模式。恢复在不可取消的协程上下文完成，保留原错误及恢复错误；持久数据恢复失败时不启动未恢复的配置。
- 配置切换在配置互斥区内、修改选中配置前读取最新服务状态；启动中或停止中拒绝切换，避免界面状态延迟导致启动配置与保存的选择不一致。界面禁用行为见 [flclash.md](flclash.md#配置页与内核差异)。
- 订阅流量/到期：手动更新、自动更新和新建远程配置都经 `HTTPClient.getSubscription`（`Libbox.uiSubscriptionGet`）获取，三个内核相同。[SubscriptionInfo.kt](app/src/main/java/io/nekohasekai/sfa/database/SubscriptionInfo.kt) 解析 `subscription-userinfo`（键不区分大小写，容忍空格、缺键和小数，负数/非数字忽略，`expire=0` 表示长期有效）；没有该响应头就不显示，也不从节点名推测，本次获取无头时清除旧数据。`profile-update-interval`（小时）仅在用户未手动设置间隔（`intervalUserSet`）时生效，最小 15 分钟；`profile-web-page-url` 仅接受 http/https。这些字段与内容、更新时间一起经 `ProfileChange` 保存，失败时一并回滚。`TypedProfile` Parcel 版本 3 增加 upload/download/total/expire/webPageUrl/intervalUserSet；读取 v1/v2 数据原值不变，非 60 分钟的旧间隔视为用户设置。
- [UpdateProfileWork.kt](app/src/main/java/io/nekohasekai/sfa/bg/UpdateProfileWork.kt) 在同一互斥区内完成每个订阅的保存、应用和恢复，应用失败请求重试。[BackgroundProfileService.kt](app/src/main/java/io/nekohasekai/sfa/bg/BackgroundProfileService.kt) 通过本机服务连接确认状态，模式相同重载，模式变化及失败恢复时等待停止和 Started 回调；后台重启所需 VPN/通知权限未授予时更新失败并回滚。更新未选中配置或停服时不启动服务，远程会话不接收本机订阅的重载。
- 常规服务页的 VPN 绕过、自动重定向和动态通知开关保存后，运行中的本地服务自动停止并重新走权限/启动流程，确认 Started 后完成操作。[ServiceSettingsChange.kt](app/src/main/java/io/nekohasekai/sfa/bridge/ServiceSettingsChange.kt) 在保存或重启失败时恢复原设置，重启失败时再尝试恢复原服务；停止状态只保存设置，远程会话不触发本机或远端重启。
- Root 自动重定向的系统 BPF map 兼容范围和核心补丁见 [libbox.md](libbox.md#android-自动重定向兼容)。Android 桥接继续沿用上游的启动、错误传递及 Root 服务死亡清理流程。
- 局域网代理使用 libbox 原生入站变换，不引入全局 Clash 端口设置。运行时端点来自已成功启动的配置快照，避免将保存失败或未应用的配置显示为正在监听。
- [DashboardConfigCache.kt](app/src/main/java/io/nekohasekai/sfa/bridge/DashboardConfigCache.kt) 仅缓存选中配置和已启动配置的内容、修订及最近一组接口地址的 LAN 解析结果，相同内容共用缓存。前台两秒轮询保留网络/热点检测，以配置 ID、路径、文件时间/大小及写入代次检查保存配置；未变化时不重读内容、计算哈希或调用 LAN 解析。地址变化复用内容和修订，重新调用 LAN 解析；运行配置按成功启动快照更新。普通写入及备份恢复/回滚共用 `ProfileContent.write` 推进代次，避免同大小、同时间戳的修改漏刷。解析错误也缓存，配置/地址变化或手动刷新时重试，取消不缓存。缓存条目同时保存 `Libbox.detectCoreConfig` 的结果（每份内容只识别一次），判断 LAN 开关是否可用时不再每次轮询解析整份配置。启动快照 `ready()` 并行读取配置列表与设置，不再扫描 LAN；内网 IP 卡片的可见轮询负责加载。
- 二进制 `.bpf` 导入保留订阅 URL、更新间隔、自动更新及时间戳。动态 QRS 通过 [BoxQrScanner.kt](app/src/main/java/io/nekohasekai/sfa/bridge/BoxQrScanner.kt) 按相机会话收集有界帧、校验 CRC，并支持原压缩文件封装。

[BoxBackups.kt](app/src/main/java/io/nekohasekai/sfa/bridge/BoxBackups.kt) 提供 `bbox-1` 格式，配置条目经 [ProfileWireFields.kt](app/src/main/java/io/nekohasekai/sfa/bridge/ProfileWireFields.kt) 携带并恢复 `core` 与订阅流量/到期/官网字段（旧备份缺少时为 `auto`/无），按声明的内核校验内容；恢复覆盖已有配置时复制全部 `TypedProfile` 字段。包含稳定配置身份、界面/应用设置、终端设置与导入字体，以及可选 WebDAV 设置。仅恢复配置时不改其他设置。恢复先暂存文件，再使用 Room 事务；应用失败回滚文件、数据库和设置。兼容策略保留其他配置，覆盖策略在用户确认后删除备份中不存在的配置。

界面偏好的读取、保存及备份恢复共用 [BoxPreferences.kt](app/src/main/java/io/nekohasekai/sfa/bridge/BoxPreferences.kt) 校验，卡片布局接受 `System HTTP proxy` 键，外观样式 `appearanceStyle` 只接受 `standard`/`minimal`（界面见 [flclash.md](flclash.md#导航与交互约定)）。首页卡片交互见 [flclash.md](flclash.md#导航与交互约定)。

备份读取/传输上限为 64 MiB，WebDAV 使用 [BoxWebDAV.kt](app/src/main/java/io/nekohasekai/sfa/bridge/BoxWebDAV.kt) 与核心扩展；取消文件选择和未确认上传不能报告成功。HTTP 行为见 [libbox.md](libbox.md)。

原生日志保留所有收到的等级供 Flutter 日志页过滤，`CommandClient` 将当前会话内核的 `setDefaultLogLevel` 回调与日志记录一起传给界面。默认值来自内核实际使用的配置（配置未设级别时三个内核均按 warn 运行，见 [libbox.md](libbox.md#服务层第-2-阶段)；sing-box 高级配置的日志页在未设置时也显示 warn），不自行解析本地文件来推断远端状态；日志客户端只在日志页可见（且应用在前台）时连接，内核在每次订阅时重放已保存的行，缓冲（去 ANSI、环形追加、快照）在后台单线程执行；本地配置成功重载后，通过已启动配置快照的变化重新订阅日志，更新默认级别。界面筛选规则见 [flclash.md](flclash.md#导航与交互约定)。

连接页的事件批次、重置和清空在 `ConnectionsViewModel` 的单线程通道内按到达顺序处理；`ConnectionStore` 按 libbox `Connections.ApplyEvents` 维护 ID → 连接映射，只有 NEW 和带快照的 CLOSED 经 JNI 读取连接，UPDATE 按增量复制该行，关闭 5 分钟后淘汰，DNS 连接不显示；未变化的行保持同一实例与桥接映射。

日志原生缓冲保留最近 3000 条记录；[LogStream.kt](app/src/main/java/io/nekohasekai/sfa/bridge/LogStream.kt) 仅在前台日志页请求数据时生成桥接消息，首次订阅及恢复可见时发送快照，随后发送新增记录、最早保留 ID 和默认等级。清空或缓冲身份重置发送替换消息，单纯等级变化不重新编码记录。隐藏日志页不影响原生缓冲和当前会话的等级同步。

[BoxDashboard.kt](app/src/main/java/io/nekohasekai/sfa/bridge/BoxDashboard.kt) 的详细内存采样独立于网络/热点轮询，由 Flutter 内存卡片和详情页共享订阅。[VisiblePolling.kt](app/src/main/java/io/nekohasekai/sfa/bridge/VisiblePolling.kt) 仅在订阅可见、应用前台且屏幕亮起时每两秒采样；重新满足条件立即采样，隐藏或断开 EventChannel 时停止。手动释放仍单次执行 libbox 回收并更新统计，不增加自动强制 GC。

应用列表通过 [AppListCache.kt](app/src/main/java/io/nekohasekai/sfa/bridge/AppListCache.kt) 复用一份包查询快照，参考上游基线的 `PackageCache`，先按系统应用/联网权限过滤，再按需读取并缓存应用名称。进入页面和手动刷新重新查询，切换显示范围复用快照；查询策略或语言改变后的查询不复用旧快照，查询失败/取消允许重试。排除应用自身及无 `ApplicationInfo` 的条目。Flutter 应用列表的排序和图标使用方式见 [flclash.md](flclash.md#导航与交互约定)。

托管应用模式仍在激活前完成扫描，安装/卸载广播沿用自动更新；列表查询完成后同步最新的已保存设置，包含后台更新的托管结果，打开页面不触发 APK/DEX 扫描。手动重新扫描通过既有桥接更新设置。查询策略继续持久化；Android 12 精确定位同时申请 fine/coarse 权限；远程配置卡不使用本地配置名。

## 请求抓取与 MITM

使用入口为“工具 → 请求”。先在“MITM 证书”中生成 CA，导出并通过 Android 设置或 Root 管理器安装，返回后刷新信任；随后在抓包设置中开启 HTTPS 并设置范围，启动本地服务和抓包，再重连目标应用。安装路径和目标应用的信任限制见下文；HTTP 抓取不需要 CA。

[BoxCapture.kt](app/src/main/java/io/nekohasekai/sfa/bridge/BoxCapture.kt) 提供本地请求启停、筛选、列表/详情、域名、HAR/cURL 导出以及 CA 管理。开始抓包需本地服务已 Started；设置和证书准备完成后，在配置互斥区复核服务实例、运行配置和当前选择，再调用该服务的 MITM 绑定。启动失败、返回期间取消或状态确认失败会回收本次已启动会话。切换远程会话停止本地抓包，远程会话拒绝读取或操作本机记录与证书。核心协议、路由、限额和停止行为见 [libbox.md](libbox.md#httphttps-请求调试)。

[CaptureSettings.kt](app/src/main/java/io/nekohasekai/sfa/bridge/CaptureSettings.kt) 将 version 2 的 HTTPS、应用/端口、域名包含/排除、QUIC 回退、域名提取和正文限额保存到私有 `noBackupFilesDir/http-debug/settings.json`，最多 64 KiB。多个 Activity 共用操作互斥锁，保存检查内容修订并用 AtomicFile；读取恢复中断写入的备份，再核对大小。抓包期间不能改设置，保存不启动抓包或重载配置。旧版本设置迁移保留有效筛选，同时关闭 HTTPS，避免将被动观察设置直接变成主动 MITM；旧库路径、函数偏移、改写、脚本等字段被删除，新保存拒绝退役字段。配置和备份不携带抓包设置、记录或证书。旧配置扩展与遗留数据的清理见 [核心兼容说明](libbox.md#旧抓包配置清理)。

[MitmCertificate.kt](app/src/main/java/io/nekohasekai/sfa/bridge/MitmCertificate.kt) 只在用户点击生成时创建 CA。P-256 根私钥使用随机独立 alias 保存到 Android Keystore，不可导出；Go 传入 SHA-256 摘要，由 `NONEwithECDSA` 签名，再由 Go 验证。API 24 起所需操作依据 [AOSP Android 7 Keystore 实现](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-7.0.0_r1/keystore/java/android/security/keystore/AndroidKeyStoreECDSASignatureSpi.java)。公钥证书和 alias 保存于不参与备份的 `http-debug/mitm-authority.json`，AtomicFile 记录最多 32 KiB。重新生成检查修订、先创建新密钥并签发，原子写入成功后才删除旧 alias；失败保留旧记录，清理本次新密钥。运行中不能重新生成，生成本身不安装或启用抓包。

证书快照分别报告有效期、私钥可用性、SHA-256、公钥身份及 AndroidCAStore 中按完整 DER 匹配的用户/系统信任。匹配遍历所有 alias，兼容 subject-hash 冲突；进程内信任库存在该证书不代表任意目标应用会信任。Android 7 起很多应用默认不信任用户 CA，pinning、自有证书库、工作资料和独立 namespace 也会限制抓取。界面安装/刷新流程见 [flclash.md](flclash.md#导航与交互约定)。

CA 导出仅包含公钥 CRT。Android 7–10 使用 KeyChain 安装入口，Android 11 及以后打开安全设置，由用户先导出、再在设置中选择 CA 文件；返回值仅表示设置已打开，不能作为安装成功证明。[MitmCertificateModule.kt](app/src/main/java/io/nekohasekai/sfa/bridge/MitmCertificateModule.kt) 可导出 `bbox_mitm_ca` ZIP，交给 Magisk、KernelSU 或 APatch 管理器安装并重启，应用不执行自动 Root 注入。

[模块脚本](app/src/main/assets/mitm-ca/inject.sh) 参考当前 FlClash 的 system/Conscrypt APEX namespace 处理，在各实际 namespace 内复制其原有证书集合到独立 tmpfs，补入公钥 CA、处理 hash.N 冲突并设权限/SELinux 标签，准备成功后才 bind 到目标。post-fs-data 处理启动继承路径，service 补入已运行应用 namespace；不先清空证书目录或写系统分区。挂载失败保留原证书库并记录 `mount.log`。更新/删除模块后需重启，旧版或手工安装的其他模块由用户在原管理器中移除。模块 ZIP 包含来源说明和 GPL-3.0 许可；逐文件参考见 [来源清单](flclash-manifest.json)。该路径仍需实际 Root 管理器、系统版本与目标应用验收。

Root/eBPF 采集器、目标 TLS 库发现、FD 会话和手动函数偏移已移除。[IRootService](app/src/main/aidl/io/nekohasekai/sfa/bg/IRootService.aidl) 的事务 10 退役，不复用；原 2/3 空缺保持，普通 Root/Shizuku、VPN 授权、自动重定向和 Shell 能力保留。

HAR/cURL、域名、CRT 和模块导出使用 Android 文档选择器，取消返回 false，写入失败不报告成功；证书在选择文件后再次检查修订。请求导出的私有临时文件用后删除。独立 `capture.logs` / `capture.logs.clear` 读取和清空抓包诊断，CA/服务准备失败也写入日志；日志读取不依赖请求存储，清日志不删除请求。

### 配置驱动的 HTTP 改写

配置支持 B-box 的 `experimental.http_rewrite` 扩展，字段、完整示例、Surge 参考及 JS 支持范围统一见 [libbox.md](libbox.md#json-配置中的-http-改写)。规则与内嵌资源属于配置，因此随原始/高级编辑、分享、订阅更新及配置备份保存；本机抓包设置、CA 和记录仍不进入备份。旧 `http_debug` 和旧抓包设置中的脚本不会自动迁移或执行。

[BoxService.kt](app/src/main/java/io/nekohasekai/sfa/bg/BoxService.kt) 在启动/重载时检查该配置是否需要 MITM CA，通过新增 OverrideOptions 的 RewriteDirectory/RewriteCertificate/RewriteSigner 传入私有存储路径、公钥证书与 Keystore 签名回调。没有证书时启用 HTTPS 会启动失败，应用不自动生成密钥或安装证书。libbox 先校验规则/CA 并准备存储，再启动核心，成功后在同一生命周期锁内激活改写，最后向 Android 返回成功。

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

关于页仍提供独立 [BoxUpdates.kt](app/src/main/java/io/nekohasekai/sfa/bridge/BoxUpdates.kt) 检查：读取 [koko123as/fk-box](https://github.com/koko123as/fk-box/releases) Releases（含预发布），以 `FK-box-version-metadata.json` 的 `application_id`、`version_code`、`version_name` 核对安装身份和版本，再匹配设备 ABI 的 `FK-box-<版本>-<ABI>.apk`。没有匹配 APK 时不提供下载资产。它不恢复原更新器的后台下载/静默安装链路。

## 地理数据资源

Xray 配置里的 `geoip:`/`geosite:` 规则和 mihomo 的 GEOIP/GEOSITE/IP-ASN 规则需要地理数据文件。目录与下载校验由 libbox 提供（见 [libbox.md](libbox.md#地理数据)），应用侧参照 FlClash 的 `initGeo` 与资源页：

- 内置：构建时 [app/build.gradle.kts](app/build.gradle.kts) 的 `prepare<变体>GeoAssets` 任务按 [geo-assets.properties](app/geo-assets.properties) 从 `MetaCubeX/meta-rules-dat` release 分支的固定提交下载四个文件，逐个核对 SHA-256，连同 `manifest.json`（提交、提交时间、各文件哈希/大小）放入 APK 的 `assets/geo/`。文件不入库，缓存在 `.gradle/geo-assets/<提交>`。更新固定版本用 `python3 tools/bump_geo_assets.py [提交]`（`--dry-run` 只打印）。
- 解压：[GeoResources.kt](app/src/main/java/io/nekohasekai/sfa/bg/GeoResources.kt) 在应用启动（后台）、打开资源页以及 Xray/mihomo 启动前把内置文件解压到 `Libbox.geoDataDir()`，边解压边算 SHA-256，与清单一致才 rename 覆盖。状态记在同目录的 `geo-resources.json`（来源 builtin/download、哈希、数据时间、上次成功检查时间）。规则（`GeoPolicy.shouldExtract`）：文件缺失一定解压；已记录的文件只在其数据时间早于内置提交时间且哈希不同才被替换，所以较新的下载版不会被内置文件覆盖，而应用升级带来的更新内置数据会替换更旧的文件；没有记录的文件（mihomo 自己下载的）按修改时间同样判断。
- 下载：资源页的单独更新、全部更新和自动更新都调 `Libbox.newGeoUpdate().fetch(...)`，传入上次记录的 SHA-256、`ETag`、`Last-Modified`（`geo-resources.json` 的 `etag`/`lastModified`），文件仍与记录一致时发条件请求，304 视为“已是最新”且不下载。失败保留原文件并显示错误；成功后记为“已下载”。协程取消（离开资源页、WorkManager 停止任务）会调用 `cancel()` 中止 Go 侧下载。下载经应用自身网络，服务运行时按分应用设置可能经过当前代理。内置文件检查每个进程只完整做一次，之后 `geo.list` 只在有文件缺失时重新检查。
- 设置：`geoAutoUpdate`（默认关）、`geoUpdateInterval`（小时，1–8760，默认 24）、`geoUrls`（只存与默认不同的地址，仅 http/https）。三项由 `settings()` 提供并随备份导出/恢复，经 [BoxSettings.kt](app/src/main/java/io/nekohasekai/sfa/bridge/BoxSettings.kt) 校验。桥接命令 `geo.list`、`geo.update`、`geo.setUrl`。
- 自动更新：[UpdateGeoWork.kt](app/src/main/java/io/nekohasekai/sfa/bg/UpdateGeoWork.kt) 用 WorkManager 周期任务（间隔即设置的小时数，需联网，失败指数退避），每次只更新上次成功检查早于间隔的文件；关闭时取消任务。应用启动和修改这两项设置时重新登记。
- 界面：工具页顶部分组的“资源”（[resources.dart](flutter/lib/pages/resources.dart)），说明见 [flclash.md](flclash.md#导航与交互约定)。
- 单元测试：[GeoPolicyTest.kt](app/src/test/java/io/nekohasekai/sfa/bg/GeoPolicyTest.kt) 覆盖解压判断、到期判断、状态和地址的读写容错；[BoxSettingsTest.kt](app/src/test/java/io/nekohasekai/sfa/bridge/BoxSettingsTest.kt) 覆盖三项设置的校验。
- APK 体积：内置四个文件共约 38.3 MB（geoip.dat 16.5 MB、geosite.dat 4.4 MB、geoip.metadb 8.4 MB、ASN 9.1 MB），APK 中压缩存放；首次解压约占 38 MB 存储。2026-11-06 实测（ARM64 debug，Artifact 未压缩打包）：630f444 为 78,069,486 字节，59d5b01 为 93,208,464 字节，增加 15,138,978 字节（约 14.4 MiB）；源码 SHA 59d5b01 的 [newui.yml](https://github.com/koko123as/fk-box/actions/runs/42207100362) 与 [build-debug-apk.yml](https://github.com/koko123as/fk-box/actions/runs/42207110939) 均通过（含 `TestCoreGeo*` 与 JVM 测试）。未覆盖：真机解压耗时、下载、自动更新、Xray/mihomo 实际读取 geo 规则。

## 构建与发布

[app/build.gradle.kts](app/build.gradle.kts) 只从本地 `app/libs/libbox.aar` 消费核心，界面来自 [build-flutter-aar](.github/actions/build-flutter-aar/action.yml) 以 `flutter build aar --target-platform android-arm64` 生成的本地 Maven 仓库（`flutter/build/host/outputs/repo`，坐标 `io.fkbox.ui:flutter_debug` / `flutter_release`），Flutter 版本固定为 3.47.6 并使用已提交的 `pubspec.lock`。核心必须包含 [libbox.md](libbox.md) 的当前扩展，并同时编入 sing-box、Xray、mihomo。

Gradle 默认 ABI 过滤为 ARMv7、ARM64、x86_64 并生成 universal；`-Parm64Only=true` 只生成 ARM64，`-PemulatorOnly=true` 只生成 x86_64，二者都关闭 universal。libbox 目前只构建 arm64（见 [libbox.md](libbox.md#多内核xray-与-mihomo)），因此 CI 的 Debug、Release 与模拟器构建均使用 `-Parm64Only=true`。

[build-release.yml](.github/workflows/build-release.yml) 仅手动触发，发布 Android 7.0+ 的 ARM64 `otherRelease`。Release 与模拟器构建共用 [build-libbox](.github/actions/build-libbox/action.yml)，核心使用 JDK 17，应用使用 JDK 21；具体 SDK/Build Tools/NDK 版本以共享 Action 和 Gradle 为准。本机未经明确要求不构建，见 [AGENTS.md](AGENTS.md)。

发布签名使用以下仓库 Actions Secrets：

| Secret | 用途 |
| --- | --- |
| `KEYSTORE_BASE64` | 发布密钥文件的 Base64 内容 |
| `KEYSTORE_PASS` | 密钥文件密码 |
| `ALIAS_NAME` | 发布密钥 alias |
| `ALIAS_PASS` | 对应密钥密码 |

工作流将已有密钥解码至 `app/release.keystore`，结束时清理，不上传密钥。Gradle 属性优先读取直接环境变量，再回退 `LOCAL_PROPERTIES` 和 `local.properties`。本地签名文件不入库，不重新生成发布密钥。

Release 构建开启 R8 与 `isShrinkResources`（[keep.xml](app/src/main/res/raw/keep.xml) 保留系统可见的图标；代码中没有按名称查找资源）；`useLegacyPackaging` 保持原样。

应用自身的 Baseline Profile 位于 [baseline-prof.txt](app/src/main/baseline-prof.txt)，AGP 将其与库的配置合并进 `assets/dexopt/baseline.prof`，由 `profileinstaller` 在旁加载安装时编译。当前文件为手写的启动/常用页面规则（CI 模拟器为 x86_64，arm64 libbox 无法运行，不能在 CI 中采集）。仓库不含 Baseline Profile 生成模块，规则需手工维护。

Release 先调用 [newui.yml](.github/workflows/newui.yml) 完成多内核、核心扩展、MITM 测试、源码约束检查及 Flutter 分析/测试，再构建 arm64 libbox，运行 `testOtherDebugUnitTest` 及保留 R8 的签名 Release 构建；单元测试成功或失败后均上传 HTML/XML 报告至 `release-unit-tests` Artifact（报告已生成时），保留 30 天。签名密钥在单元测试通过后才准备。[verify_release.py](tools/verify_release.py) 在 APK 上传前校验唯一 APK、最低 API 24、ARM64、应用包名/版本/标签、启动 Activity、MITM 模块 assets、内置地理数据（清单提交与 `geo-assets.properties` 一致、四个文件哈希相符）、退役采集器未打包及签名。APK 文件为 `FK-box-<版本>-arm64-v8a.apk`（安装身份 `io.fkbox.app`、标签 `FK-box`），所在 Artifact 名为 `FK-box-<版本>-arm64-v8a-release`，保留 30 天。

`publish_release` 默认开启；设为 `false` 时仍构建、校验并上传 Actions Artifact，仅跳过发布。[publish_release.py](tools/publish_release.py) 要求 Release tag 严格等于 `VERSION_NAME`（不加 `v`）且指向本次源码 SHA；已有 tag 指向其他提交时失败，不移动版本标签。同一提交重跑可重传资产。发布 APK 及 `FK-box-version-metadata.json`，alpha/beta/rc 标为预发布。

## 验证与历史记录

| 入口 | 配置的验证范围 |
| --- | --- |
| [verify_newui.py](tools/verify_newui.py) | Flutter 相对导入与字形、Flutter 命令均有原生处理、`MainActivity`/`ShareActivity` 进入 `BoxActivity`、Compose 界面及旧 UI/更新依赖已移除、退役抓包实现残留、FlClash 直接导入文件哈希及补充参考来源的静态检查 |
| [newui.yml](.github/workflows/newui.yml) | `main` push、目标为 `main` 的 PR、手动触发或 Release 复用；仅 Markdown 的 push/PR 跳过。运行多内核 gRPC 覆盖检查、内核识别/转换/校验、HTTP/MITM/改写/JS 与自动重定向布局等 Go 测试，以及源码约束检查、Flutter 分析与测试 |
| [newui-android.yml](.github/workflows/newui-android.yml) | 手动触发；JVM 回归、`play` Kotlin 编译、`other` ARM64 APK/仪器测试构建及 API 31/35 模拟器 smoke（只有 arm64 核心，x86_64 镜像从 API 30 起才有 ARM 转译） |
| [build-debug-apk.yml](.github/workflows/build-debug-apk.yml) | 手动触发；源码约束检查、Flutter AAR、ARM64 `otherDebug` APK（Artifact `FK-box-<版本>-<SHA>-arm64-v8a-debug`）及 JVM 测试 |
| [build-release.yml](.github/workflows/build-release.yml) | 手动触发；复用 newui.yml 的核心/源码验证，运行 JVM 测试、签名 ARM64 Release、安装身份/产物校验及可选发布 |

JVM 回归覆盖服务启动确认/失败/超时、设置、日志等级/边界和 QRS；[ServiceSettingsChangeTest.kt](app/src/test/java/io/nekohasekai/sfa/bridge/ServiceSettingsChangeTest.kt) 覆盖服务开关保存后的启动确认、失败/超时/取消恢复及恢复错误保留；[ProfileChangeTest.kt](app/src/test/java/io/nekohasekai/sfa/database/ProfileChangeTest.kt) 覆盖配置持久化失败、应用超时/取消、恢复确认及恢复错误保留。[ProfileRecoveryTest.kt](app/src/androidTest/java/io/nekohasekai/sfa/bridge/ProfileRecoveryTest.kt) 覆盖实际 libbox 二进制导入、查询策略持久化、端口占用导致的 JSON 编辑/配置切换/手动与自动订阅/完整备份恢复失败，以及停服和未选中配置更新的服务状态。界面验证范围见 [flclash.md](flclash.md)。

模拟器统一入口为 [android_smoke.py](tools/android_smoke.py)：安装应用和仪器测试 APK，分别重置应用数据后运行 UI smoke 与原生回归，前一组失败不跳过后一组。UI 覆盖首次启动、远程 JSON 导入、VPN 授权、启停、实际内核分组及进程重启后配置保留；原生组运行全部仪器测试并拒绝零测试结果。`build/android-smoke/` 保存截图、logcat、失败堆栈、仪器测试输出及带源码 SHA 的 `results.json`，超时也保留日志。启动阶段允许截图后关闭一次 Google APIs 模拟器的 Pixel Launcher ANR 弹窗，再重新打开 FK-box；不关闭 FK-box 自身的 ANR 或其他错误。脚本仅用于 CI 模拟器，不能对保有用户数据的设备执行。

查询策略回归在每次切换 Root/Shizuku 并刷盘后重新打开 Room 数据库读取持久值，同时核对状态流，不以读取同一内存缓存代替持久化验证。

`ProfileRecoveryTest.logLevelFollowsSuccessfulConfigurationReloads` 另覆盖真实内核日志订阅的默认级别、运行中修改 `log.level` 后刷新，以及移除该字段后恢复当前核心的默认值。

`ProfileRecoveryTest.localProfileTimesFollowSuccessfulContentChanges` 覆盖本地创建/内容编辑的时间记录、无变化保存和重命名不刷新时间、应用失败恢复原内容与时间；二进制订阅测试另检查手动 JSON 编辑保留导入的订阅时间。

`ProfileRecoveryTest.captureSettingsAreLocalAndCheckRevisions` 使用实际 libbox 和 AtomicFile，覆盖 version 2 设置迁移/保存/重新打开、修订冲突、主动 MITM 默认关闭和退役选项拒绝。[MitmCertificateTest.kt](app/src/androidTest/java/io/nekohasekai/sfa/bridge/MitmCertificateTest.kt) 在 API 24/31/35 使用实际 Android Keystore 与 gomobile，验证 CA 自签名、预哈希签名、私钥不可导出、无自动生成、修订冲突/轮换、AtomicFile 恢复、密钥缺失、大小限制，以及 CRT/模块仅含公钥和完整脚本；不安装设备证书或 Root 模块。

`ProfileRecoveryTest.routedMITMUsesKeystoreAndTheOriginalOutbound` 将实际 Android TLS 客户端经 mixed CONNECT 入口送入本地服务，使用 Keystore CA 解密、原 selector/direct 出站与独立证书库验证 HTTPS 测试服务器，再核对 URL、响应、重复 Cookie、原路由、TLS 元数据、cURL 导出及重载停抓。[android_smoke.py](tools/android_smoke.py) 在 CI 临时目录生成独立测试服务器证书，经 ADB reverse 提供 HTTPS 服务，只把公钥 CA 传给仪器测试；私钥不写入仓库、设备或上传产物，套件结束删除。单独运行这条仪器测试需要脚本提供的 `mitmOriginPort` / `mitmOriginCA` 参数。模拟器中的测试客户端显式信任本次 CA，不代表第三方目标应用信任或 Root 模块已经实机通过。

[DashboardConfigCacheTest.kt](app/src/test/java/io/nekohasekai/sfa/bridge/DashboardConfigCacheTest.kt) 通过调用计数覆盖重复轮询不再读取/哈希/解析、地址变化、配置切换、写入/回滚、运行快照更新及错误/取消重试；`ProfileRecoveryTest.lanCacheTracksEqualSizeWritesSuccessfulReloadsAndRecovery` 使用实际文件和 libbox，覆盖同大小同时间戳写入、保存与运行配置分离、成功重载、失败恢复和停服后的 LAN 状态。它们由 Android smoke 的 JVM/仪器测试步骤运行，Release 运行其中的 JVM 测试。

[AppListCacheTest.kt](app/src/test/java/io/nekohasekai/sfa/bridge/AppListCacheTest.kt) 通过查询/名称读取计数覆盖隐藏应用不读取名称、筛选切换复用、刷新及查询策略/语言失效、失败/取消重试和并发查询合并，由 Android smoke 和 Release 的 JVM 测试运行。它不衡量设备上的实际加载耗时。

[LogStreamTest.kt](app/src/test/java/io/nekohasekai/sfa/bridge/LogStreamTest.kt) 覆盖日志快照、增量、淘汰边界、等级更新、清空、重新订阅和缓冲重置，并检查 3000 条缓冲新增一条时只编码一条。[VisiblePollingTest.kt](app/src/test/java/io/nekohasekai/sfa/bridge/VisiblePollingTest.kt) 用虚拟时间检查不可见/后台/熄屏时零采样、恢复立即采样及取消在途任务；两者由 Android smoke 和 Release 的 JVM 测试运行，删除前 Flutter 版的通知与订阅测试记录见 [flclash.md](flclash.md#验证与升级参考版本)。这些调用计数不代表实测内存或帧率收益。

上述为已配置的检查，不是当前提交的通过声明。记录实际结果时附源码 SHA、Actions 链接和未验证范围，不能沿用旧裁剪 APK、旧 Compose 或旧 Flutter 验证记录。

以下两节是迁入本仓库前、在 B-box 仓库（含 `giturass/B-box` 的 Actions）中的历史验证记录，仅作参考，不代表本仓库当前源码已通过；本仓库的验证以 [koko123as/fk-box Actions](https://github.com/koko123as/fk-box/actions) 为准。

### JSON 配置改写验证

2026-11-03，配置改写的验证源码为 `e62ddbe724a1b5f61e3bff96e48b1ba8e82c94fc`，包含最后的标题重叠修正。下表记录实际运行结果；后续仅记录结果的文档提交不改变应用源码。

| Actions | 检出源码 SHA | 实际结果 |
| --- | --- | --- |
| [Core and Flutter validation](https://github.com/giturass/B-box/actions/runs/41575286785) | `e62ddbe724a1b5f61e3bff96e48b1ba8e82c94fc` | Go 扩展/schema、HTTP/MITM/改写/JS 的 `-race`、原路由及重定向布局回归通过；Flutter 分析、203 项测试及 ARM64 Flutter AAR 构建通过 |
| [Android regression and smoke](https://github.com/giturass/B-box/actions/runs/41575670538) | `e62ddbe724a1b5f61e3bff96e48b1ba8e82c94fc` | 44 项 JVM 测试无失败或跳过，`play`/`other` 编译和模拟器应用/仪器测试 APK 构建通过；API 24/31/35 的 UI smoke 与各 18 项仪器测试全部通过 |

已核对三个 `android-smoke-api-*` Artifact 的 `results.json`：源码 SHA 一致，UI/native 均成功；每份 `native-tests.txt` 都记录 18 项通过，且 `profileHTTPRewritingUsesKeystoreRoutingAndReloadRecovery` 成功。该回归的实际覆盖见 [配置驱动的 HTTP 改写](#配置驱动的-http-改写)。模拟器中的自有 TLS 客户端显式信任本次 CA，测试没有安装系统证书或 Root 模块。

本机完成 `git diff --check`、`python3 tools/verify_newui.py`、Go 格式、Python 语法、配置示例/来源清单 JSON 及文档链接静态检查，未在本机构建或执行 Go/Flutter/Android 测试。最终截图的检查范围见 [flclash.md](flclash.md#验证与升级参考版本)。本次未运行签名 ARM64 Release/R8，未发布 APK 或 GitHub Release；下文旧 Release 不包含本次配置改写。ARM64 实机、真实 Root CA 模块安装与第三方应用信任仍未验收。Surge 配置/脚本支持范围以 [核心文档](libbox.md#json-配置中的-http-改写) 为准。

### MITM 分支验证

以下为 2026-11-03 在 JSON 配置改写加入前的请求录制与 CA 验证，不覆盖上述新扩展。三个 SHA 的 Android 主应用、Go 扩展和 Flutter 生产源码相同；`8869e69` 之后只调整了 Android 仪器测试、smoke 脚本、Flutter CI 的 NDK 准备及文档，未把测试修正记为新的应用实现。后续仅记录结果的文档提交不改变 APK。

| Actions | 检出源码 SHA | 实际结果 |
| --- | --- | --- |
| [Core and Flutter validation](https://github.com/giturass/B-box/actions/runs/41549420394) | `b3dfe817c7838a7485497eced0aa1bcebe1ae6d6` | Go 扩展、HTTP/MITM `-race`、原路由回归、Flutter 分析/交互/布局测试和 ARM64 Flutter AAR 全部通过；显式安装 NDK 后打包成功 |
| [Android regression and smoke](https://github.com/giturass/B-box/actions/runs/41548479075) | `8cc2ca6b64963891d479a671b4a6a58521a16ba1` | 44 项 JVM 测试、`play`/`other` Kotlin、模拟器应用及测试 APK 编译通过；API 24/31/35 的 UI smoke 和各 17 项仪器测试全部通过，含实际 Keystore 签名与原出站 HTTPS MITM |
| [ARM64 Release 构建](https://github.com/giturass/B-box/actions/runs/41546076303) | `8869e6992a59327c11d39ad98457a22f402a23cb` | 复用核心/Flutter 验证，44 项 JVM 测试、R8 签名构建、API 24/ARM64/安装身份/模块资源及签名校验通过；`publish_release=false`，发布步骤跳过 |

三个模拟器的 `mitm-origin.json` 均记录独立 HTTPS 服务器只收到一次 `/probe?raw=1;2`，Host 保留 `origin.test:<实际端口>`；仪器测试同时核对叶证书由本次 Keystore CA 签发、重复 Cookie、响应正文、原 selector 路由、TLS 元数据、cURL 和配置重载停抓。完整结果与诊断在对应运行的 `android-smoke-api-*` Artifact 中。Flutter 截图检查范围见 [flclash.md](flclash.md#验证与升级参考版本)。

[签名 APK Artifact](https://github.com/giturass/B-box/actions/runs/41546076303/artifacts/13189531900) 包含 `B-box-1.15.0-alpha.13-arm64-v8a.apk`，SHA-256 为 `28065565867c0642f1a4f1e784309823b23d4e3d3a63a0a399bd44f76b3621c6`，保留期按 Release 工作流的 30 天设置。该构建未发布 GitHub Release。

本机完成 `git diff --check`、`python3 tools/verify_newui.py`、Python/模块脚本语法、来源清单及文档链接静态检查。模拟器测试使用显式信任本次 CA 的自有客户端；尚未验收真实 Magisk/KernelSU/APatch 安装与重启、第三方目标应用信任、ARM64 实机运行或完整系统证书设置交互。协议支持与限制见 [libbox.md](libbox.md#httphttps-请求调试)，不能由这些通过记录推断全应用均可解密。

原 `MODIFICATIONS.md` 与 `NEWUI.md` 已按领域归入这四份文档。历史 MOD-001～004、007～008、012 的 Compose 界面由 Flutter 替代；MOD-005/006/009 的品牌、安装身份及发布约定在本文继续维护；MOD-011/015/016 的删除项以本次迁移后的现状为准；MOD-013/014 的核心与工具裁剪已回退；MOD-017 的 UI 适配见 [flclash.md](flclash.md)。旧实现、逐次构建日志与产物哈希可通过 `git log --all -- MODIFICATIONS.md NEWUI.md` 和对应提交的文件内容查询，不再混入当前项目约束。
