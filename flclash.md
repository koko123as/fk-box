# FlClash UI 使用情况

本文记录界面来源、复用和适配范围。Android 后端差异见 [app.md](app.md)，核心扩展见 [libbox.md](libbox.md)，维护约束见 [AGENTS.md](AGENTS.md)。

## Kotlin + Jetpack Compose 翻译

本仓库（[koko123as/fk-box](https://github.com/koko123as/fk-box) 的 `main`）已把下文列出的全部 Flutter 页面和组件用 Kotlin + Jetpack Compose 重写（仓库不再包含任何 Flutter/Dart 代码，只参考并翻译 FlClash 的设计与组件行为），位于 `app/src/main/java/io/nekohasekai/sfa/compose`（`theme/`、`ui/`、`pages/`），`flutter/` 目录随后删除。下文中的 `*.dart` 文件名指删除前的参考源码，可在 Git 历史中查看；对应关系：

| 原 Flutter 页面 | Compose 实现 |
| --- | --- |
| dashboard、status_panel 等首页组件 | `pages/DashboardPage.kt`、`StatusPanel.kt`、`DashboardComponents.kt` |
| proxies、proxy_card、proxy_list | `pages/ProxiesPage.kt` |
| profiles、profile_details、profile_editor、configuration | `pages/ProfilesPage.kt`、`ProfileEditor.kt`、`Configuration.kt` |
| tools、connections、logs | `pages/ToolsPage.kt` |
| requests、request_settings、capture_domains、capture_logs、mitm_certificate、captured_body、capture_poll、http_message | `pages/RequestsPage.kt`、`ui/Records.kt` |
| terminal、terminal_settings | `pages/TerminalPage.kt` |
| diagnostics、reports、endpoints、taildrop、authentication、settings 中的远程控制 | `pages/ToolPages.kt` |
| settings（常规/服务/核心）、about、backup、access | `pages/SettingsPages.kt` |
| appearance、theme_preview、palette、segmented_control | `pages/AppearancePage.kt` |
| record.dart、data_subscription.dart | `ui/Records.kt`、`DashboardPage.kt` 的 `BoxDataSubscription` |

已知与 Flutter 版的差异：搜索水滴变形和液态玻璃弹窗以弹簧缩放近似；列表行展开用侧滑；抽屉不随内容变短，宽屏列表单列；开关不支持拖动；导航坞和外观预览没有液态玻璃模糊（预览只画色调和高光边）；仪表盘卡片编辑器的进出场用 Compose 展开动画近似，拖动时不自动滚动；开源许可页改为列出主要组件及其许可和源码链接。

## 参考版本与来源清单

固定参考 [chen08209/FlClash v0.9.3](https://github.com/chen08209/FlClash/tree/be84736605527034689c95bf27828ff27d912c5e)，提交 `be84736605527034689c95bf27828ff27d912c5e`（2026-11-04），其界面套件为子模块 [chen08209/prism_ui](https://github.com/chen08209/prism_ui/tree/63450c0ac0e8e1b2c0f3ed4ad7926dc94e083185) `63450c0ac0e8e1b2c0f3ed4ad7926dc94e083185`（v0.9.3 把 `lib/ui/` 移入 `plugins/prism_ui`）。FK-box 的 Compose 界面最初按 v0.9.2（`7fd417fb9a9a277c6a9339a8ac87f907df7c25f7`，2026-10-24）逐项翻译，`flclash-ui` 分支第 1–8、10 阶段再按 v0.9.3/prism_ui 对齐外壳、动效、按压、文本选择、横幅、记录筛选、请求页、快速编辑、宽屏仪表盘及工具页；服务状态、DNS 记录和应用内更新安装会改变功能，未迁入。84e71e4^ 之前的旧 SFA Compose 仿制按钮和卡片不再定义界面。

[flclash-manifest.json](flclash-manifest.json) 是逐文件来源清单（记录翻译所依据的原 Flutter 导入/适配文件；`files`/`adaptedFiles` 的哈希仍是最初导入的 v0.9.2 源文件，`previousReference` 记录该提交）：

| 清单字段 | 使用方式 |
| --- | --- |
| `files` | 直接导入的共享组件、两个工具文件、字体和 glass shader；哈希对应将 Dart 包引用从 `fl_clash` 机械改名为 `b_box_ui` 后的文件 |
| `adaptedFiles` | 仪表盘、配置/代理、外观、备份、IP 质量查询及参考测试等适配文件；记录原始来源哈希和实际改动说明 |
| `adaptedNativeFiles` | `libbox/ui_egress.go` 对 FlClash IP 查询的适配来源；用 sing-box 路由和 libbox HTTP 替换原传输 |
| `supplementalReferences` | 独立于整体 UI 基线的补充参考（MITM dev；地理数据资源页与更新设计，FlClash `be84736605527034689c95bf27828ff27d912c5e`），按原样导入、改写或设计参考记录源文件哈希；本地路径相对仓库根目录 |

清单中 `files` 和 `adaptedFiles` 的本地路径相对已删除的 `flutter/`（可在删除前的 Git 历史中查看），`adaptedNativeFiles` 的本地路径相对仓库根目录；`source`/`sources` 指向固定参考提交内的上游路径。清单记录逐文件哈希和改动来源，本文维护使用说明，两者一并保留。

本次请求/MITM 功能补充核对 FlClash `dev` 的 [`63aa150e23288558074d877f745a6d3b5f6418f6`](https://github.com/chen08209/FlClash/tree/63aa150e23288558074d877f745a6d3b5f6418f6)，相关提交为 [`f54486102172566e7342d7f5c138663c3c5dec0b`](https://github.com/chen08209/FlClash/commit/f54486102172566e7342d7f5c138663c3c5dec0b) 的 MITM 应用侧设计和 [`1a7556d30a38707f39abfba4b7d6a6b8f57774ed`](https://github.com/chen08209/FlClash/commit/1a7556d30a38707f39abfba4b7d6a6b8f57774ed) 的请求/连接整合。该 dev 源码的 `FEATURE_MITM` 默认关闭，公开 core 不提供完整 MITM 实现；B-box 自行实现 sing-box 路由上的 TLS 接管，不能把上游页面当成可用核心。此次采用证书信任、报文展示和 CA 模块设计，当时保留独立连接页（现已按 v0.9.3 合并入请求页，见上文），不整体迁移该版本的 UI 依赖。整体 FlClash UI、Android 上游和核心三个基线均不变。

共享组件原位于 `flutter/lib/ui`，现由 `compose/ui` 与 `compose/theme` 翻译实现。原始许可保留在 [licenses/LICENSE](licenses/LICENSE)，字体归属见 [JetBrainsMono-OFL.txt](licenses/JetBrainsMono-OFL.txt) 和 [Twemoji-LICENSE.md](licenses/Twemoji-LICENSE.md)。Twemoji 原始图形来自 Twitter，字体转换由 Mozilla 提供，图形未修改。这些文件同时用于应用内许可证页，不再单独保存重复副本。

## 界面复用与业务适配

Compose 承担仪表盘、代理、配置、工具及所有子页面的导航（下文 `*.dart` 名称指翻译参考源文件）。复用 FlClash 的控件、字形、形状、排版、字体、动效、弹层及导航；页面状态和操作经 Android 桥接接入 B-box 的 Room 与 libbox。没有引入 FlClash/Mihomo 原生库、Clash 配置模型、Mihomo 配置覆写脚本、订阅转换器或 Clash 专用服务。B-box 请求/响应脚本的范围见 [核心 HTTP 改写](libbox.md#json-配置中的-http-改写)。

| 区域 | 主要实现 | 当前适配 |
| --- | --- | --- |
| 首页与状态 | `dashboard.dart`、`status_panel.dart`、`start_toggle.dart` | 复用状态布局、国家徽章、服务按钮形变及网络速率展示；启停调用原生服务流程 |
| 配置卡与分组概览 | `profile_panel.dart`、`proxy_groups_panel.dart` | 使用真实配置元数据和内核分组，远端未知数量保持未知 |
| 代理 | `proxies.dart`、`proxy_card.dart`、`proxy_list.dart` | 卡片与列表都使用 libbox 分组；只有可选择的组显示选择操作 |
| 配置管理 | `profiles.dart`、`profile_card.dart`、`profile_details.dart` | 复用行高、选中态、相对更新时间、添加菜单、分组表单及排序样式，编辑和导入 sing-box JSON |
| 首页编辑 | `dashboard_editor.dart` → `DashboardEditor.kt` | 原地编辑卡片、摇摆、长按排序、移除徽章及添加列表 |
| 内存与局域网 | `memory_panel.dart`、`intranet_panel.dart` | 分别使用 Go/进程内存和 sing-box 监听配置，不虚构 Mihomo 内存或全局代理端口 |
| 系统 HTTP 代理 | `system_proxy_panel.dart` | 采用参考版 VPN 卡片的布局、状态徽章和紧凑开关，使用 B-box 自有的大陆轮廓地球字形；开关接入内核上报状态及既有 Android 桥接 |
| 外观与关于 | `appearance.dart`、`about.dart` | 复用主题预览、调色和版本/许可布局，保持 FK-box 品牌及 [koko123as/fk-box](https://github.com/koko123as/fk-box/releases) 的 Release 来源 |
| 备份 | `backup.dart`、`form.dart` | 复用本地/远程分区、WebDAV 表单、连通状态、恢复范围及策略，后端使用 `bbox-1` 格式 |
| 请求调试 | `requests.dart`、`request_settings.dart`、`mitm_certificate.dart`、`capture_logs.dart`、`capture_domains.dart` | 请求列表/详情、本机筛选、HTTPS MITM 与证书、诊断、域名选择及 HAR/cURL 导出；复用共享列表、表单和导航 |

### 导航与交互约定

- 布局断点按 FlClash v0.9.3 `lib/common/layout.dart`（[`be84736`](https://github.com/chen08209/FlClash/commit/be84736)）：窗口宽度 ≤600 为手机、≤840 为 laptop、更宽为 desktop（`ui/Layout.kt` 的 `ViewMode`/`LocalViewMode`），卡片间距 8/12/16（仪表盘、配置网格、代理组）。非手机宽度使用 prism_ui `63450c0` `sidebar.dart` 的侧栏（`ui/Sidebar.kt`）：48 dp 图标栏，desktop 宽度可展开为 220 dp 带标签并保存 `sidebarExpanded`，laptop 宽度的展开按钮以浮层显示；chrome 表面色加末端分隔线。侧栏比手机多出“请求”（仅 sing-box 内核，其他内核仍在工具页灰显）和“日志”，工具页相应隐藏这两项。每个目的地在非手机宽度有自己的导航栈（`NestedNavigatorHost`），推入的页面留在内容区，侧栏保持可见；表单、弹窗和菜单仍在根导航器上。
- 页面动效按 prism_ui `63450c0`（FlClash v0.9.3 `be84736` 使用）的 iOS 27 卡片路由：`ui/PageTransition.kt` 以 300 ms 临界阻尼弹簧（起速为距离的 9 倍/秒）推入和返回，被覆盖页后移 30% 并加 10% 黑色遮罩，浅色主题下前缘有 sigma 12 的 4% 阴影，移动中的页面在 API 31+ 取屏幕圆角；`ui/DragBack.kt` 在页面任意位置向阅读方向拖动返回（30 dp 起拖，距离加 0.35 s 速度达到宽度 0.4665 即返回，超过 1.5 倍宽度/秒的轻扫加速 4.25 倍，反向拖动橡皮筋），横向滚动控件优先取得手势，有编辑守卫的页面先回弹再询问；Android 14+ 的预测性返回手势驱动同一跟随。与 v0.9.3 一致，所有入口均使用推入（v0.9.3 的 `OpenContainer` 调用都用默认 `push`），未使用的缩放转场未移植。工具栏按钮按 `bar_item_presence.dart` 从 1.2 倍和模糊以 0.416 s 弹簧进入，字形变化时轻微膨胀。手机根导航器、平板各目的地导航栈及表单内推入的页面共用这些动效。
- 按压反馈按 prism_ui `63450c0`（FlClash v0.9.3 `be84736` 用其替换全部 `InkWell`）：`ui/Press.kt` 的 `PressIndication` 是应用默认 `LocalIndication`，Material 波纹（`LocalRippleConfiguration`）全局关闭。卡片、列表行、记录行和标签用 ink：按下 150 ms 后以 200 ms 显出 10% 墨色，悬停 4%，在内容下方绘制，Android 13+ 另从按下点发出 617 ms 的 InkSparkle（`ink_sparkle.frag` 的 AGSL 移植）；图标按钮、文字/填充按钮和确认对话框按钮用 tint：浅色淡出 25%、深色提亮 20%，松开 250 ms 回落；键盘移动焦点时绘制 2 dp 主色焦点环。侧边栏和弹出菜单行用 `PressRegionIndication`（按下 6%/焦点 10%/悬停 4%，50 ms 渐变；菜单行的按下仍由菜单自身填充）。按下膨胀（`elasticPress`）只保留在 prism_ui 使用 `ElasticPress` 的位置：Tonal 按钮及按钮组、工具栏操作、搜索框、启停按钮和出站模式连接按钮。功能与点击行为不变。
- 外观“样式”（`appearanceStyle`：`standard` 标准 / `minimal` 简约，默认标准，即时生效并随备份恢复）：[theme/Appearance.kt](app/src/main/java/io/nekohasekai/sfa/compose/theme/Appearance.kt) 的 `AppearanceTokens` 经 `LocalAppearance` 提供，主题形状、液态玻璃、阴影、动效和按压代码读取同一组令牌，页面不各自判断样式。简约样式：不使用液态玻璃（导航坞、菜单、弹窗为纯色表面，不运行 AGSL 着色器）；阴影/投影（面板、对话框、菜单、横幅、导航坞、页面前缘阴影、侧栏浮层）改为 outlineVariant 细线；去掉状态卡背景装饰图形、墨迹扩散、InkSparkle 和 `elasticPress` 膨胀，按压只显示静态按下暗化；圆角约减半（`AppShape` 统一换算，减半后不低于 4 dp，原本不足 4 dp 的不变；胶囊形保持）。动效改为 160 ms 渐显 / 120 ms 渐隐，无弹簧、平移、缩放和模糊：页面推入/返回、表单面板、横幅、菜单与编辑菜单、对话框、工具栏项与搜索框、列表增删（`itemMotion()`，其余行不位移）、分组展开和导航坞选中项；底部标签切换固定为短渐变。拖动返回手势保留但页面不跟随手指，松开后按同一判定以渐隐返回。颜色、明暗、布局（手机、平板侧栏、仪表盘网格）、功能指示（开关、进度、加载、错误横幅）及全部功能不变。
- 文本选择按 prism_ui `63450c0` `text_selection_menu.dart`/`edit_menu.dart`：`ui/TextSelection.kt` 的 `EditMenuHost` 在应用根部提供 Compose `LocalTextContextMenuToolbarProvider`，所有 `SelectionArea`（原 `SelectionContainer` 的位置，另加订阅信息详情和错误页消息，对应 FlClash 的 `SelectionArea`/`SelectableText`）及输入框的触摸选择菜单都改为 iOS 27 编辑菜单：44 dp 胶囊，按钮间 18 dp 分隔线，放在选区上方或下方 15 dp，以 0.2 倍缩放 405 ms 弹簧弹出、150 ms 收起；放不下时显示更多箭头，展开为 250 dp 宽、42 dp 行高带字形的卡片。剪切/复制/粘贴/全选使用 prism 文案和字形，内核提供的其他项（如自动填充）保留原文；菜单外按下即关闭。放大镜沿用平台实现，未使用液态玻璃变体。复制、粘贴等操作仍由 Compose 文本控件执行。
- 消息横幅按 prism_ui `63450c0` `banner.dart` 的 `BannerHost`（`ui/MessageBanner.kt`），替换原底部 Snackbar：从状态栏下方顶部弹簧落下（stiffness 256、damping 27.2），170 ms 升出；手机一次一条，非手机宽度最多叠放三条（FlClash `application.dart` 的 `stackedBanners`）。普通消息 3 s、错误 6 s；队列最多缓存 8 条（先丢弃非错误），错误优先；位置占满后最早一条显示 3150 ms 即让位；与正在显示的同文同级消息合并并重新计时。向上轻扫或拖过一半即关闭，向下拖为橡皮筋。消息文本、中文翻译、错误样式（errorContainer 底色）、字形徽章、最多 4 行与关闭按钮保持原有内容和行为。
- 列表基础设施按 FlClash v0.9.3（`be84736`）`lib/models/record_filter.dart`、`lib/widgets/record_filter.dart` 和 `lib/ui/scaffold/`：`CommonScaffold` 新增 `titleMenuItems`（点按标题弹出菜单切换视图，标题后带 20/16 dp 下拉箭头，供第 6 阶段合并后的请求页切换 最近/运行中/抓包）和 `keywords`（栏下的关键词条，36 dp 胶囊，显示时正文不再位于栏下）。`ui/RecordFilter.kt` 的 `RecordFilterController`：点按行内的值（日志级别；连接的网络、规则、应用、代理链各出站）添加筛选条件，同一维度内为“或”、不同维度为“且”；手机宽度只显示已选条件（可移除），非手机宽度固定显示所有可选值作切换；菜单中有“筛选”（网络、规则；日志为级别，按出现次数取前 12 个）和“分组方式”（连接：应用/主机/规则/出口），分组以可折叠标题（名称和数量，默认折叠）显示，所选分组按页面保存在 `recordGroupings` 界面偏好。列表行以 `animateItem` 键控增删和位移。原有关键词搜索、活动/已关闭、排序、日志级别菜单、暂停、清除、导出及关闭连接保持不变；连接记录没有 MITM 标记，MITM 维度留给抓包列表。
- 请求页按 FlClash v0.9.3（`be84736`）`lib/views/connection/requests.dart` 合并请求与连接：点按标题切换“最近请求”（已关闭连接，菜单可改为全部）、“运行中”（活动连接，带关闭全部）和 fk-box 的“已抓取”（原 HTTP 抓包列表，抓包开关、设置、MITM 证书、抓包日志、导出 HAR、清除及详情不变；Xray/mihomo 下该项置灰并显示原因，远程会话显示仅限本机）。所选视图保存在 `requestFeed` 界面偏好。每个视图有独立的搜索、`RecordFilterController` 和分组：连接视图沿用网络/规则/应用/代理链维度，抓包视图为 MITM（MITM/HTTP/仅域名）与应用维度，可按应用或主机分组。侧栏的“请求”在各内核均显示，工具页只保留一个“请求”入口（手机宽度）。抓包记录没有连接 ID，连接详情仍为原字段，未加入每连接的 HTTP 交换标签页。
- 快速编辑按 FlClash v0.9.3（`be84736`）`lib/features/form/quick_edit.dart` 与 `lib/common/input_entries.dart`：`ui/QuickEdit.kt` 的 `QuickEditPage`/`ListQuickEditPage` 以等宽文本编辑整个列表，每行一项（行首 `- `、行尾逗号和引号会去掉，逗号也分隔，空行和重复项忽略）；无效项以“第 n 行：原因”列出前三条，并询问是否放弃更改，选择取消时编辑器保持打开。入口：抓包设置的 HTTPS 域名、HTTPS 排除域名和端口（端口仍校验 1–65535）各有“快速编辑”行，结果写回表单草稿后仍经原保存与修订检查；抓包应用页工具栏可按包名逐行编辑；HTTP 改写页新增 MITM 域名/排除域名（`mitm.hosts`、`mitm.excluded_hosts` 字符串数组）；高级配置的各对象列表（HTTP 改写规则、DNS 服务器与规则、路由规则、规则集、入站/出站/端点）工具栏可每行一个 JSON 对象整体编辑（参考版 `parseLineInput` 保持每行完整），保存经 `ProfileConfigStore.set` 的修订检查。保存的数据格式与原有校验不变。
- 首次启动直接进入仪表盘，没有免责声明接受门槛。Android 默认显示状态、网络速度、出站模式、配置和代理组五张卡；内存与内网 IP 可选。连接从工具页进入，不提供首页连接卡，旧布局/备份忽略该卡，下一次布局保存清理其键。
- 仪表盘宽屏网格按 FlClash v0.9.3 `lib/views/dashboard/dashboard.dart` `_DesktopDashboard` 与 `layout.dart` `DashboardDensity`（`pages/DashboardGrid.kt`，`ui/Layout.kt` 的 `dashboardDensityOf`）：内容宽度（除以字号缩放）<640 为手机单列（最宽 560，可编辑卡片）；≥860 为 4 列，否则 2 列；4 列且 ≥1040 宽、且高度 ≥824 为 standard，否则 compact。每列最宽 440 × 字号缩放并居中，行高 顶部 208/176、控制 64/56、中部 176/152、汇总 120/112，卡片圆角 32，宽屏不提供编辑（手机的卡片选择和顺序不受影响）。4 列为 [状态 ×2｜上传｜下载] / [出站模式条 ×2｜系统 HTTP 代理 ×2（占 FlClash 的 VPN 位）] / [内网 IP｜配置 ×2｜内存] / [连接统计 ×2｜代理组 ×2]：窗口高度放得下时底行填满剩余高度，并在能容纳代理组列表（`connectionsTableMinHeight`）时显示代理组磁贴列表，否则显示代理组汇总；2 列按成对堆叠。上传、下载卡片显示当前速度、近 30 次采样峰值和累计。连接统计只有“实时连接”和“最近请求”两格，取自请求页同一连接数据流（`BoxData.Connections` 引用计数订阅），点按打开对应视图。fk-box 没有服务状态探测和 DNS 记录，不显示服务状态卡和 DNS 计数，配置卡占用空出的中部格；不显示连接表格卡。出站模式或系统 HTTP 代理不可用时另一项占满控制行。
- 出站模式只显示内核提供的值。规则、全局、直连使用参考版字形，名称和选中态忽略大小写，排列和切换参数保留内核原值。系统 HTTP 代理卡片按内核能力显示，支持首页编辑中的排序、移除和重新添加；能力暂不可用时保留顺序和隐藏偏好。卡片沿用参考版 VPN 卡片的 48 像素徽章、加粗标题、启用/禁用副标题及紧凑开关；启用时徽章为主题色六瓣形，禁用时为低强调圆形。卡片和首页编辑列表共用 `system_proxy_glyph.dart` 中的大陆轮廓地球字形，使用共享 Glyph 的线宽与圆角；全局出站模式保留参考版经纬线球体字形。
- 服务按钮的暂停图形表示停止服务，不新增暂停核心能力。远程会话、启停过渡和不可用操作必须反映真实状态。
- 工具页按 v0.9.3 `lib/views/tools.dart`：顶部分组是导航“更多”项（手机宽度的请求、资源、手机宽度的日志；侧栏已显示的项不重复），随后是 fk-box 自有工具：运行中有数据的端点（Tailscale（含 Taildrop）、OpenVPN、OpenConnect、USB/IP）、终端和远程控制。设置分组依次为外观、访问控制、备份与恢复、常规（参考版的语言项不迁入，沿用系统语言；Clash 高级配置不适用）。其他分组为网络质量、STUN 测试、崩溃/内存/电量报告和关于（参考版在此放开发者页；没有免责声明页）。终端设置在终端内，仪表盘编辑在首页工具栏。配置卡与仪表盘配置卡在副标题前/名称后显示配置实际使用的内核（sing-box、Xray 或 mihomo）标签。资源页按参考版 `views/resources.dart` 只保留地理数据部分（自动更新及小时间隔、GeoIP/GeoSite/MMDB/ASN 四行的大小、更新时间与来源、单独更新、改地址并可恢复默认、复制链接，右上角全部更新），参考版的图标集、规则提供者、脚本和 MITM 模块不迁入；文件机制见 [libbox.md](libbox.md#地理数据) 与 [app.md](app.md#地理数据资源)。高级配置（含 sing-box 规则集）仍从配置的“编辑”页进入，不在资源页重复。
- 抓包列表为合并请求页的“已抓取”视图；列表沿用连接视图的卡片、左右边距、行间距和字形，支持搜索和分批加载。详情使用同一点击复制行及反馈，长 URL、头部与 Cookie 分行显示；右上角菜单切换请求、响应和 Cookies，请求与响应 Cookie 分组展示，保留重复 `Set-Cookie`、空值、值中的等号及属性，点击 Cookie 复制该条原文。抓取开关等待原生确认，停服时保留历史记录；远程会话显示不可用。
- 抓包设置使用共享分组表单，提供 HTTPS/MITM、证书管理、域名包含/排除、应用、原始目标端口、正文限额、可见域名提取及可选 QUIC TCP 回退。保存检查本机修订，失败保留草稿，运行中不能改设置。证书入口也在请求菜单中；生成 CA 是明确操作，重新生成需要确认，打开页面不生成、不安装、不启用抓包。证书页显示有效期、公钥 SHA-256、用户/系统信任及私钥可用性，提供 CRT/系统模块导出和 Android 设置入口；返回前台可刷新信任，远程会话不读本机证书，迟到结果不覆盖新会话。平台边界见 [app.md](app.md#请求抓取与-mitm)。
- 请求菜单提供抓包设置、MITM 证书、“抓包日志”、当前搜索的 HAR 导出和清空请求。日志页集中展示当前后端、实际 TLS 尝试/失败、解密连接/正文字节、QUIC 回退和解析/筛选/保存计数，支持搜索、复制、导出与独立清空。启动或握手失败可在日志中查看，不把开启开关当作收到 HTTPS 请求；日志来源及限额见 [libbox.md](libbox.md#httphttps-请求调试)。
- 请求详情保留重复头部、Cookie、正文状态及 trailers，显示原路由/应用、真实 SNI、双方 TLS 版本、ALPN 和上游证书 SHA-256，字段支持复制。已有记录中保存过的 PID/socket 字段仍可读，缺失值保持未知。域名观察与握手失败标为“仅域名”，不能导出 cURL；请求正文/头部不完整、截断或含 trailers 也不能导出 cURL。页面复用现有共享组件，没有修改 FlClash 直接导入文件。
- 常规服务页使用即时应用的开关，保存和重启期间显示进度并禁用开关，底部没有“应用服务设置”按钮；启动确认及失败恢复见 [app.md](app.md#配置服务应用与备份)。
- 日志页默认选择“跟随配置”，显示内核上报的日志级别及更严重的记录；菜单同时显示当前级别，仍可手动选择“全部”或单一级别，手动筛选不会被后续状态更新覆盖。搜索、暂停和导出沿用同一筛选结果；默认级别尚未收到时保留已接收记录。日志来源及本地重载同步见 [app.md](app.md#配置服务应用与备份)。
- 应用始终跟随系统语言并响应运行时变化。旧设置及备份中的语言选项丢弃，其他偏好保留。
- 代理列表保留圆角吸顶分组标题、固定高度的懒加载行、折叠动效及填充/普通/隐藏分组图标。折叠状态按选中配置保存，定位节点通过行偏移实现，不要求节点预先进入视口。
- 访问控制按上游 SFA 的列表/扫描分离方式加载，进入页面直接显示应用与已保存的托管选择；“重新扫描托管应用”为独立操作，扫描期间保留列表。筛选/搜索/排序结果在列表及相关条件未变化时复用，流量等无关状态更新不重新排序。图标保持按可见行请求和缓存，切换显示范围或无修改地关闭查询设置不清空图标；查询策略变更或申请权限后关闭设置只刷新一次。原生查询与托管更新约定见 [app.md](app.md#配置服务应用与备份)。
- 首页编辑保留卡片的全局 key，防止重复出口请求及丢失状态。改动立即保存；保存失败保留草稿，点击完成再次尝试。
- sing-box 专属 Tailscale、Taildrop、终端、认证、USB/IP、诊断和报告使用统一 Flutter 页面，复用原生后端；能力边界见 [app.md](app.md)。

`box.dart` 按应用状态、启动/外观、流量、内存、连接和日志分开通知；流量采样与仪表盘累计值只更新相关组件，日志和连接变化不触发应用根部、代理或配置列表重建。出口查询监听应用状态变化，不因流量或内存采样重新计算路由标识。显式批量通知仍刷新全部依赖。

`data_subscription.dart` 按页面活动状态、TickerMode 和应用生命周期合并内存/日志订阅；卡片与详情页共享一个原生采样请求，最后一个可见消费者退出时停止。日志增量保留既有记录对象，暂停快照独立于后续追加和淘汰，恢复可见时重新同步原生缓冲。原生采样与消息边界见 [app.md](app.md#配置服务应用与备份)。

`capture_poll.dart` 仅在本地请求列表/详情或抓包日志页活动、TickerMode 开启且应用前台时每两秒查询；调用不重叠，筛选变化合并刷新，并忽略旧筛选或会话的迟到结果。日志使用独立的 `capture.logs` 查询，请求列表不拉取日志历史。隐藏页面停止轮询，抓取引擎与日志记录继续运行至用户或服务停止。页面为 B-box 业务代码，没有直接修改 FlClash 导入文件。

`captured_body.dart` 将正文预览放在等宽、自动折行、有界滚动的卡片中，展示内容类型、保存/总字节数及 Base64、截断、未完成状态。完整 JSON 可切换格式化与原文；`http_message.dart` 仅调整 JSON 字符串外的空白，保留数值字面量、重复键及转义，无法解析或超过格式化限额时显示原文。长预览分批展开，边界不拆开 UTF-16 代理对；复制始终使用内核返回的原始预览，不受当前格式或已展开长度影响，也不代表未保存的完整正文。

### 配置页与内核差异

配置列表按参考版使用两行卡片、固定行高、选中徽标、更新动画及最多三列的自适应网格。普通配置页首次显示时定位已选配置，切换配置的弹层从顶部打开。添加按钮和空状态按钮均打开菜单，新建入口标为“新建配置”；排序使用分组背景、拖动手柄及拖动浮起效果。长按菜单依次提供编辑、更新（远程配置）、复制链接和二维码（远程配置）、分享、导出及删除，删除标为危险操作。编辑页显示配置摘要，提供元数据分组表单及原始/高级配置入口。

服务启动中或停止中，配置列表和已打开的切换弹层禁用配置选择，首页切换配置按钮同步禁用；服务进入运行或停止状态后恢复。原生状态复核见 [app.md](app.md#配置服务应用与备份)。

| 参考版能力 | B-box 的适配边界 |
| --- | --- |
| 链接、文件、二维码导入 | 使用 sing-box JSON / 分享链接，保留 `.bpf`、动态 QRS 和剪贴板入口；不转换 Clash YAML |
| 自定义配置、提供者与覆写 | “新建配置”创建本地 sing-box JSON；通过已有原始/高级配置编辑器修改，不引入 Mihomo 配置生成、提供者或覆写脚本 |
| 配置预览与文件编辑 | 从编辑页的“打开配置内容”入口读取保存的 JSON，不将其称为运行时配置 |
| 自动更新 | 保留 Android 后端至少 15 分钟的限制；关闭后隐藏间隔输入，无效草稿不阻止关闭，保留原有效间隔及导入元数据；批量更新按原生互斥写入流程串行执行 |
| 流量用量与到期徽标 | 当前 Room/桥接没有订阅额度和到期字段，卡片显示真实本地/远程类型，不用会话流量代替额度；这是当前元数据能力的差异，不表示 sing-box 无法支持订阅信息展示 |
| 上次更新时间 | 首页和配置列表均显示 Room 时间戳，采用参考版紧凑相对时间并提供完整时间提示；本地配置显示创建/内容保存时间，远程配置显示订阅更新时间。没有历史记录时显示未知，时间记录和失败恢复见 [app.md](app.md#配置服务应用与备份) |
| 分享与导出 | 复制链接得到原始订阅 URL，分享/二维码保留 sing-box 导入链接和名称，导出仍为 JSON |

编辑保留 B-box 已有的本地/远程类型切换；不会因视觉对齐加入参考版的 age 密钥等未接入能力。保存、修订冲突、配置应用及失败恢复继续由既有桥接处理，见 [app.md](app.md#配置服务应用与备份)。

原始 JSON 编辑器使用 `code_editor.dart` 显示从 1 开始的逻辑行号，空行和末尾换行保留编号；长行折行不增加行号，行号随正文滚动。行号布局与正文使用相同的字形、字号比例和换行宽度，仅创建可见编号；搜索、格式化、撤销及修订校验沿用原编辑流程。

高级配置新增“HTTP 改写”，由 `configuration.dart` 的 `HttpRewriteConfigPage` 复用共享分组、规则列表、排序及 JSON 表单，编辑配置启用状态、MITM 范围、Header/URL/Body Rewrite、Map Remote、Map Local/API Mock、JS 和内嵌资源。新建规则提供禁用的示例，用户修改匹配条件与内容后启用；完整 JSON 仍可直接编辑。字段及执行语义统一见 [libbox.md](libbox.md#json-配置中的-http-改写)。

所有修改继续调用 `ProfileConfigStore.set`，保留其他 JSON 字段、内容修订和原生校验/失败恢复；关闭失败表单前保留草稿。页面中的证书入口打开既有本机证书页，不把 CA 写入配置。请求页和诊断页单独显示 `rewriteActive`，说明停记录后规则仍生效；映射请求详情把连接出站/规则标为原连接，避免与映射目标的新路由混淆。证书页在配置处理运行时禁用重新生成。没有改动 FlClash 直接导入组件，来源清单中的相关设计说明已同步。

`configuration_test.dart` 覆盖改写开关通过配置修订保存、保留其他字段及失败恢复；`requests_test.dart` 覆盖仅改写运行时的状态和证书禁用，并生成 `requests/profile-rewriting-recording-off.png`。这些新增测试由 Core and Flutter validation 执行；其视觉与运行结果须按实际源码 SHA 记录，不能沿用前次 MITM 验证。

### 状态卡片与出口查询

查询来源、字段解析及质量判定参考上述固定版本。IP 竞速由 [ui_egress.go](libbox/ui_egress.go) 负责：Cloudflare、IP.SB、IPinfo、ipwho.is、GeoJS、country.is、IPQuery、ident.me 共八源，B-box 的完整解析与失败回退、限额及传输约束见 [libbox.md](libbox.md#出口-ip-和质量查询传输)。

质量查询在 `ip_quality.dart` 中依次分批使用 ident.me/ip-api.com/IPQuery、IPLocate、ipapi.is、proxycheck.io；等待一秒或当前请求全部失败后进入后续批次，单请求最多八秒。保留参考版组织/ASN 解析、显式类型优先、住宅类型推断及 Tor/abuser 质量规则，模型见 `ip_quality_models.dart`。

`ip_quality_cache.dart` 将成功质量结果按 IP 持久缓存三天，失败仅在内存冷却五分钟，手动重试可跳过失败缓存。先显示 IP，再补充质量；质量失败保留 IP，详情展示来源和错误。

刷新与会话约定：

- 停止的本地服务通过设备当前网络查询，即使没有配置也可查询和手动重试；运行中通过 sing-box 路由查询。启停时取消旧探测，等待稳定服务状态再查询。远程会话保留占位，不展示本机出口。
- 返回前台保留未变路由的成功 IP 和质量；IP 查询失败才自动重试，间隔至少五秒，手动刷新可跳过。质量缓存到期只刷新质量。
- 短暂失焦不取消请求；已经发出的请求允许在后台完成，新查询等待前台。UI 在后台断开连接时保留本地分组快照，用于返回时比较。
- 启停、运行中的配置/模式/节点及网络变化取消旧请求、忽略迟到结果，并合并 300 毫秒内的变化后查询新出口，不受旧路由冷却限制。分组排序、延迟更新和停服时选择配置不触发无关的出口失效。

### 内网 IP 与备份展示

内网 IP 卡片复用参考版共享开关、端口副标题、Wi-Fi 徽章及复制操作。复制按钮沿用 `DashboardIconButton` 的 32 像素正方形点击区、18 像素图标、低强调前景色及向右 8 像素偏移，不受标题栏高度压缩。共享开关修改选中配置的 mixed/http/socks listen；无代理入站时先显示端口表单，明确新增 mixed 入站。运行时复制已成功启动配置的端点，停服/远程会话复制设备 IP，详情保留多入站/多端口。配置变换和保存约束分别见 [libbox.md](libbox.md) 与 [app.md](app.md)。

备份页沿用参考版的本地/远程布局、文件名、范围和兼容/覆盖策略，实际格式是 B-box `bbox-1`。覆盖会在页面确认后移除备份中不存在的配置；取消选择、写入失败或上传未确认时不显示成功。事务、文件恢复和传输限额由 Android/libbox 实现，见 [app.md](app.md)。

## 验证与升级参考版本

FK-box 删除 `flutter/` 后不再运行 Flutter 分析、Widget 测试、截图/像素比较或 AAR 构建，[newui.yml](.github/workflows/newui.yml) 也不再上传 `newui-validation` / `flutter-aar-arm64`。当前界面检查为 [verify_newui.py](tools/verify_newui.py) 的 Compose 源码约束（由 newui.yml 和 build-debug-apk.yml 运行），以及 [newui-android.yml](.github/workflows/newui-android.yml) 的 JVM 测试与模拟器 UI smoke，见 [app.md](app.md#验证与历史记录)；Compose 页面尚无对应的截图或像素比较测试。

以下 `*_test.dart` 段落记录删除前 Flutter 版的测试覆盖，测试文件可在 Git 历史中查看，不代表当前 Compose 源码已通过同等测试。

`profiles_test.dart` 覆盖新建与二进制导入的内容/元数据、添加菜单与取消导入、菜单能力、原始订阅链接、相对时间边界、自动更新校验、选中配置定位、列表及弹层在启停期间禁止切换与状态稳定后恢复、切换失败、排序冲突及远程会话隔离。`navigation_test.dart` 另输出配置编辑与排序弹层在窄屏、大字号和宽屏下的截图；这些是布局回归产物，配置页尚未声明与上游逐像素一致。

`proxies_test.dart` 集中验证自动组禁止手选、延迟测试目标、分组切换、列表折叠及视口外节点定位。`connections_test.dart` 检查关闭连接页后释放订阅；`status_panel_test.dart` 将启动按钮的独立无障碍点击入口与配置可用性、实际桥接调用一起验证。

配置测试另覆盖本地时间的未知占位及原生元数据事件刷新；`code_editor_test.dart` 对照实际 `RenderEditable` 检查行号在折行、宽度/字号变化及滚动后的对齐，并覆盖空行、末尾换行和替换内容。`intranet_panel_test.dart` 检查大字号下复制按钮的尺寸与位置，以及运行/停服/远程会话的复制内容；这些几何断言不代替实机视觉验收。

`service_settings_test.dart` 覆盖三个服务开关的即时应用、等待原生确认期间禁用开关及移除手动应用按钮；`box_test.dart` 检查重复编辑、停止/远程会话、启停过渡及失败后的原生设置恢复。实际服务重启与恢复的 Android 验证范围见 [app.md](app.md#验证与历史记录)。

`logs_test.dart` 覆盖本地/远程日志状态的默认阈值、保留更严重的记录、手动筛选及恢复跟随配置，并检查暂停、搜索、导出和清空；实际内核级别与配置重载由 Android 仪器测试覆盖。

`state_updates_test.dart` 检查高频数据只重建对应订阅者、服务状态仍通知、应用根部不随遥测变化重建且外观设置继续生效，并覆盖日志增量淘汰、暂停快照与重置。`data_subscription_test.dart` 检查卡片/详情共享订阅、隐藏页面、TickerMode、前后台和销毁时的订阅切换。测试验证通知和调用行为，实际 PSS/RSS、CPU 和帧耗时仍需在同设备、同配置下测量。

`access_test.dart` 覆盖进入托管页面不扫描、加载期间接收最新托管选择、显示范围与隐藏选择保留、保存失败后重试、图标复用、扫描等待/成功/失败及查询设置返回后的刷新。它验证交互和调用行为，不代替实机加载耗时或视觉验收。

`requests_test.dart` 覆盖工具入口、远程隔离、本机设置修订、HTTPS 域名选择、端口和启动确认；检查日志统计、历史/搜索/复制/导出、清日志与清请求隔离、后台/遮挡轮询及迟到结果。证书测试检查明确生成、取消导出不报成功、运行中禁止重生成、远程切换/迟到快照和信任刷新。其他回归保留 HAR 搜索、已有 socket 元数据读取、报文菜单、字段/Cookie 复制、正文格式切换和 Unicode 分批展开。窄屏、大字号深色和宽屏截图在 `build/screenshots/requests/`，包含设置、证书/导出、日志、列表、详情、头部、正文和 Cookies，显式加载 Roboto 和 JetBrains Mono。桥接替身与截图不等于实际 TLS 或系统证书安装验收；验证分工见 [libbox.md](libbox.md#构建同步和验证) 与 [app.md](app.md#验证与历史记录)。

HTTP 改写另覆盖停止录制后的独立状态及 CA 轮换禁用；上述 requests 截图包含处理状态，以及 320 宽、1.3 倍字号深色主题下的配置规则与内嵌资源入口。该配置页将列表视口放在共用标题栏下方，避免滚动文字透过渐变标题相互重叠；测试同时核对视口与标题的几何位置。`configuration_test.dart` 检查启用改写保留原出站和其他 experimental 字段、写入携带配置修订，以及校验失败后保留已有内容。

`http_message_test.dart` 检查 JSON 数值/重复键/转义保真、无效或过大内容回退，以及重复 Cookie、空值、等号和 Expires 逗号。请求正文不完整或截断时不提供 cURL 导出。

`status_panel_parity_test.dart` 与冻结的 `flclash_status_reference.dart` 比较像素，覆盖中英文、深浅主题、0.8/1.0/1.4 字号比例、IPv6、查询等待/失败、组织/ASN 回退及质量等级；停止按钮另行比较。停服状态包含无配置首次启动和设备网络查询的成功/等待/失败情况。

参考渲染器独立保留原版标题、chips、徽章、形变、卡片表面和排版，不导入 B-box 的适配仪表盘组件。比较使用相同视口、字号、语言、主题及 Roboto/Noto Sans SC 字体，按卡片自然高度截图并断言高度，双方图像上传为 CI Artifact。

这些测试只覆盖声明的页面和情景，不等于全应用逐像素一致或已经完成实机验收。升级参考版本时同步审查来源清单、适配文件、许可和独立参考渲染器，按实际 Actions 运行记录结果；Android 验证入口见 [app.md](app.md)。

2026-11-03 的配置改写视觉检查读取 [Actions 41575286785](https://github.com/giturass/B-box/actions/runs/41575286785) 在 `e62ddbe724a1b5f61e3bff96e48b1ba8e82c94fc` 生成的 `newui-validation` 截图：核对停止录制后的改写状态，以及 320 宽、1.3 倍字号深色主题下的配置页首屏和资源区域；规则名称/数量可读，资源入口可滚动访问，标题与滚动内容不再重叠。产物名称为 `profile-rewriting-recording-off.png`、`profile-rewriting-config.png` 和 `profile-rewriting-config-resources.png`，均在 requests 截图目录。这些为英文 Flutter fixture，未覆盖中文实机或系统证书设置；完整结果与未覆盖范围见 [JSON 配置改写验证](app.md#json-配置改写验证)。

此前的 MITM 分支视觉检查读取 [Actions 41545838495](https://github.com/giturass/B-box/actions/runs/41545838495) 在 `8869e6992a59327c11d39ad98457a22f402a23cb` 生成的 `newui-validation` 截图：核对 320 宽、1.3 倍字号的抓包诊断，以及 360 宽、1.4 倍字号的深色设置、证书和导出区域。日志统计标签改为名称和值分行，避免窄列拆词；长证书指纹和说明可换行，导出区域可滚动访问。这些是英文 Flutter fixture 截图，未覆盖系统证书设置页面或 Root 管理器。其他检查及各次源码差异见 [MITM 分支验证](app.md#mitm-分支验证)。
