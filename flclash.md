# 界面说明（朴素 Material 3 界面，FlClash 仅作布局参考）

本文记录界面的实现方式、布局来源和适配范围。Android 后端差异见 [app.md](app.md)，核心扩展见 [libbox.md](libbox.md)，维护约束见 [AGENTS.md](AGENTS.md)。

`ui/plain` 起界面改为朴素的 Material 3 风格，观感参照 [SagerNet/sing-box-for-android](https://github.com/SagerNet/sing-box-for-android)（`dev` 分支 Compose 界面）和 [2dust/v2rayNG](https://github.com/2dust/v2rayNG)。各页面的分区、顺序、按钮位置、底部四个目的地（仪表盘、代理、配置、工具）和平板侧栏仍按原 FlClash 布局；FlClash 只提供页面布局与信息组织参考，不再提供控件、字形、形状、动效或特效。FlClash 的来源与 GPL-3.0 许可保留。

## Kotlin + Jetpack Compose 翻译

界面由 Kotlin + Jetpack Compose 实现，位于 `app/src/main/java/io/nekohasekai/sfa/compose`（`theme/`、`ui/`、`pages/`），仓库不含 Flutter/Dart 代码。页面最初按 FlClash 的 Flutter 页面逐项翻译，`flutter/` 目录随后删除，翻译参考可在 Git 历史中查看。页面对应关系：

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
| record、data_subscription | `ui/Records.kt`、`DashboardPage.kt` 的 `BoxDataSubscription` |

## 朴素界面约定

- 组件全部使用 Material 3 原生实现：卡片为 `Card`（无阴影/elevation），底部导航为 `NavigationBar`，平板侧栏为 `NavigationRail`，页面为 `Scaffold` + `TopAppBar`，对话框为 `AlertDialog`，弹出菜单为 `DropdownMenu`，消息为 `Snackbar`，开关、芯片、分段按钮、进度条均为 Material 3 组件。`ui/` 下的 `CommonCard`、`CommonScaffold`、`CommonDialog`、`BoxMenu` 等只是这些组件的薄封装，供页面沿用原调用点。
- 不使用液态玻璃、AGSL/RuntimeShader、模糊、阴影、装饰图形（M3Shapes/MaterialShapes/超椭圆）、墨迹扩散、InkSparkle、按下膨胀或弹簧动效。点按反馈为系统波纹；圆角统一 12 dp（`theme/Shape.kt` 的 `AppShape`），胶囊/圆形只用于状态点、徽章和颜色选择。
- 页面推入/返回、表单和弹层即时出现与消失，不做转场动画；返回只用系统返回（含预测性返回手势的系统默认行为），没有页内拖动返回。底部标签切换即时完成。
- 图标为 Material icons（`androidx.compose.material:material-icons-extended`），`ui/AppIcons.kt` 按用途命名映射；原 FlClash 派生的自绘字形集（Glyph/AppGlyphs）已删除。
- 外观页保留主题色（含自定义取色）、配色风格、纯黑模式、明暗跟随和字号/字体；“标准/简约”样式开关及其令牌已删除，旧设置与备份中的 `appearanceStyle`、`liquidGlass`、`tabAnimation`、`floatingNavigation`、`sidebarExpanded` 键在读取和恢复时丢弃（[BoxPreferences.kt](app/src/main/java/io/nekohasekai/sfa/bridge/BoxPreferences.kt)）。
- 仍保留的绘制均为功能性内容：网络速度折线图（`ui/LineChart.kt`）、二维码（`ui/QrCode.kt`）、颜色选择器的色板/色谱/滑条，以及订阅额度和扫码进度的 `LinearProgressIndicator`。
- [tools/verify_newui.py](tools/verify_newui.py) 在 CI 中检查上述约束（禁止 Glyph/AppGlyphs、玻璃/着色器/模糊、弹簧与按压特效、装饰形状、样式开关及 graphics-shapes 依赖）。

## 参考版本与来源清单

布局参考固定为 [chen08209/FlClash v0.9.3](https://github.com/chen08209/FlClash/tree/be84736605527034689c95bf27828ff27d912c5e)（`be84736605527034689c95bf27828ff27d912c5e`，2026-11-04），最初翻译依据为 v0.9.2（`7fd417fb9a9a277c6a9339a8ac87f907df7c25f7`）。服务状态、DNS 记录和应用内更新安装会改变功能，未迁入。84e71e4^ 之前的旧 SFA Compose 页面不再使用。

[flclash-manifest.json](flclash-manifest.json) 记录参考版本、原生适配（`adaptedNativeFiles`：`libbox/ui_egress.go` 对 FlClash IP 查询的适配，用 sing-box 路由和 libbox HTTP 替换原传输）和补充参考（`supplementalReferences`：MITM dev、地理数据资源页设计）。原 Flutter 导入的逐文件清单可在 `6902a3d` 的 `flclash-manifest.json` 中查看。

请求/MITM 功能另参考 FlClash `dev` 的 [`63aa150e23288558074d877f745a6d3b5f6418f6`](https://github.com/chen08209/FlClash/tree/63aa150e23288558074d877f745a6d3b5f6418f6)（[`f544861`](https://github.com/chen08209/FlClash/commit/f54486102172566e7342d7f5c138663c3c5dec0b) 的 MITM 应用侧设计、[`1a7556d`](https://github.com/chen08209/FlClash/commit/1a7556d30a38707f39abfba4b7d6a6b8f57774ed) 的请求/连接整合）。该 dev 源码的 `FEATURE_MITM` 默认关闭，B-box 自行实现 sing-box 路由上的 TLS 接管。

FlClash 许可保留在 [licenses/LICENSE](licenses/LICENSE)，字体归属见 [JetBrainsMono-OFL.txt](licenses/JetBrainsMono-OFL.txt) 和 [Twemoji-LICENSE.md](licenses/Twemoji-LICENSE.md)。

## 界面复用与业务适配

页面状态和操作经 Android 桥接接入 Room 与 libbox。没有引入 FlClash/Mihomo 原生库、Clash 配置模型、Mihomo 配置覆写脚本、订阅转换器或 Clash 专用服务。请求/响应脚本的范围见 [核心 HTTP 改写](libbox.md#json-配置中的-http-改写)。

| 区域 | 实现 | 当前适配 |
| --- | --- | --- |
| 首页与状态 | `DashboardPage.kt`、`StatusPanel.kt` | 状态、国家、出口 IP 与质量、网络速率；启停调用原生服务流程 |
| 配置卡与分组概览 | `DashboardPage.kt` | 使用真实配置元数据和内核分组，远端未知数量保持未知 |
| 代理 | `ProxiesPage.kt` | 卡片与列表都使用 libbox 分组；只有可选择的组显示选择操作 |
| 配置管理 | `ProfilesPage.kt`、`ProfileEditor.kt` | 行高、选中态、相对更新时间、添加菜单、表单及排序；编辑和导入各内核配置 |
| 首页编辑 | `DashboardEditor.kt` | 原地编辑卡片：长按拖动排序、移除按钮及可添加列表 |
| 内存与局域网 | `DashboardPage.kt` | 分别使用 Go/进程内存和 sing-box 监听配置，不虚构 Mihomo 内存或全局代理端口 |
| 系统 HTTP 代理 | `DashboardPage.kt` | 图标、启用/禁用副标题及开关，接入内核上报状态及既有 Android 桥接 |
| 外观与关于 | `AppearancePage.kt`、`SettingsPages.kt` | 主题色、配色风格、明暗与字号；版本/许可信息，Release 来源为 [koko123as/fk-box](https://github.com/koko123as/fk-box/releases) |
| 备份 | `SettingsPages.kt` | 本地/远程分区、WebDAV 表单、连通状态、恢复范围及策略，后端使用 `bbox-1` 格式 |
| 请求调试 | `RequestsPage.kt`、`ui/Records.kt` | 请求列表/详情、本机筛选、HTTPS MITM 与证书、诊断、域名选择及 HAR/cURL 导出 |

### 导航与交互约定

- 布局断点按 FlClash v0.9.3 的布局：窗口宽度 ≤600 为手机、≤840 为 laptop、更宽为 desktop（`ui/Layout.kt` 的 `ViewMode`/`LocalViewMode`），卡片间距 8/12/16。非手机宽度在左侧显示 `NavigationRail`；侧栏比手机多出“请求”（仅 sing-box 内核，其他内核在工具页灰显）和“日志”，工具页相应隐藏这两项。每个目的地在非手机宽度有自己的导航栈（`NestedNavigatorHost`），推入的页面留在内容区，侧栏保持可见；表单、弹窗和菜单在根导航器上。
- 消息使用 Material 3 `Snackbar`（`BoxApp.kt`）：普通消息短时显示，错误长时显示，新消息替换当前消息。
- 文本选择使用 Compose 平台默认的选择菜单与放大镜。
- 列表基础设施（布局参考 FlClash v0.9.3 的记录筛选）：`CommonScaffold` 新增 `titleMenuItems`（点按标题弹出菜单切换视图，标题后带 20/16 dp 下拉箭头，供请求页切换 最近/运行中/抓包）和 `keywords`（栏下的关键词条，36 dp 胶囊，显示时正文不再位于栏下）。`ui/RecordFilter.kt` 的 `RecordFilterController`：点按行内的值（日志级别；连接的网络、规则、应用、代理链各出站）添加筛选条件，同一维度内为“或”、不同维度为“且”；手机宽度只显示已选条件（可移除），非手机宽度固定显示所有可选值作切换；菜单中有“筛选”（网络、规则；日志为级别，按出现次数取前 12 个）和“分组方式”（连接：应用/主机/规则/出口），分组以可折叠标题（名称和数量，默认折叠）显示，所选分组按页面保存在 `recordGroupings` 界面偏好。原有关键词搜索、活动/已关闭、排序、日志级别菜单、暂停、清除、导出及关闭连接保持不变；连接记录没有 MITM 标记，MITM 维度留给抓包列表。
- 请求页（布局参考 FlClash v0.9.3）合并请求与连接：点按标题切换“最近请求”（已关闭连接，菜单可改为全部）、“运行中”（活动连接，带关闭全部）和 fk-box 的“已抓取”（原 HTTP 抓包列表，抓包开关、设置、MITM 证书、抓包日志、导出 HAR、清除及详情不变；Xray/mihomo 下该项置灰并显示原因，远程会话显示仅限本机）。所选视图保存在 `requestFeed` 界面偏好。每个视图有独立的搜索、`RecordFilterController` 和分组：连接视图沿用网络/规则/应用/代理链维度，抓包视图为 MITM（MITM/HTTP/仅域名）与应用维度，可按应用或主机分组。侧栏的“请求”在各内核均显示，工具页只保留一个“请求”入口（手机宽度）。抓包记录没有连接 ID，连接详情仍为原字段，未加入每连接的 HTTP 交换标签页。
- 快速编辑（参考 FlClash v0.9.3 的快速编辑）：`ui/QuickEdit.kt` 的 `QuickEditPage`/`ListQuickEditPage` 以等宽文本编辑整个列表，每行一项（行首 `- `、行尾逗号和引号会去掉，逗号也分隔，空行和重复项忽略）；无效项以“第 n 行：原因”列出前三条，并询问是否放弃更改，选择取消时编辑器保持打开。入口：抓包设置的 HTTPS 域名、HTTPS 排除域名和端口（端口仍校验 1–65535）各有“快速编辑”行，结果写回表单草稿后仍经原保存与修订检查；抓包应用页工具栏可按包名逐行编辑；HTTP 改写页新增 MITM 域名/排除域名（`mitm.hosts`、`mitm.excluded_hosts` 字符串数组）；高级配置的各对象列表（HTTP 改写规则、DNS 服务器与规则、路由规则、规则集、入站/出站/端点）工具栏可每行一个 JSON 对象整体编辑（参考版 `parseLineInput` 保持每行完整），保存经 `ProfileConfigStore.set` 的修订检查。保存的数据格式与原有校验不变。
- 首次启动直接进入仪表盘，没有免责声明接受门槛。Android 默认显示状态、网络速度、出站模式、配置和代理组五张卡；内存与内网 IP 可选。连接从工具页进入，不提供首页连接卡，旧布局/备份忽略该卡，下一次布局保存清理其键。
- 仪表盘宽屏网格按 FlClash v0.9.3 桌面仪表盘布局（`pages/DashboardGrid.kt`，`ui/Layout.kt` 的 `dashboardDensityOf`）：内容宽度（除以字号缩放）<640 为手机单列（最宽 560，可编辑卡片）；≥860 为 4 列，否则 2 列；4 列且 ≥1040 宽、且高度 ≥824 为 standard，否则 compact。每列最宽 440 × 字号缩放并居中，行高 顶部 208/176、控制 64/56、中部 176/152、汇总 120/112，宽屏不提供编辑（手机的卡片选择和顺序不受影响）。4 列为 [状态 ×2｜上传｜下载] / [出站模式条 ×2｜系统 HTTP 代理 ×2（占 FlClash 的 VPN 位）] / [内网 IP｜配置 ×2｜内存] / [连接统计 ×2｜代理组 ×2]：窗口高度放得下时底行填满剩余高度，并在能容纳代理组列表（`connectionsTableMinHeight`）时显示代理组磁贴列表，否则显示代理组汇总；2 列按成对堆叠。上传、下载卡片显示当前速度、近 30 次采样峰值和累计。连接统计只有“实时连接”和“最近请求”两格，取自请求页同一连接数据流（`BoxData.Connections` 引用计数订阅），点按打开对应视图。fk-box 没有服务状态探测和 DNS 记录，不显示服务状态卡和 DNS 计数，配置卡占用空出的中部格；不显示连接表格卡。出站模式或系统 HTTP 代理不可用时另一项占满控制行。
- 出站模式只显示内核提供的值。规则、全局、直连使用 Material 图标，名称和选中态忽略大小写，排列和切换参数保留内核原值。系统 HTTP 代理卡片按内核能力显示，支持首页编辑中的排序、移除和重新添加；能力暂不可用时保留顺序和隐藏偏好。卡片为图标、标题、启用/禁用副标题及开关。
- 服务按钮的暂停图形表示停止服务，不新增暂停核心能力。远程会话、启停过渡和不可用操作必须反映真实状态。
- 工具页按 v0.9.3 的工具页布局：顶部分组是导航“更多”项（手机宽度的请求、资源、手机宽度的日志；侧栏已显示的项不重复），随后是 fk-box 自有工具：运行中有数据的端点（Tailscale（含 Taildrop）、OpenVPN、OpenConnect、USB/IP）、终端和远程控制。设置分组依次为外观、访问控制、分流规则（FK-box 自有页面，见 [app.md](app.md#分流规则)）、备份与恢复、常规（参考版的语言项不迁入，沿用系统语言；Clash 高级配置不适用）。其他分组为网络质量、STUN 测试、崩溃/内存/电量报告和关于（参考版在此放开发者页；没有免责声明页）。终端设置在终端内，仪表盘编辑在首页工具栏。配置卡与仪表盘配置卡在副标题前/名称后显示配置实际使用的内核（sing-box、Xray 或 mihomo）标签。资源页按参考版只保留地理数据部分（自动更新及小时间隔、GeoIP/GeoSite/MMDB/ASN 四行的大小、更新时间与来源、单独更新、改地址并可恢复默认、复制链接，右上角全部更新），参考版的图标集、规则提供者、脚本和 MITM 模块不迁入；文件机制见 [libbox.md](libbox.md#地理数据) 与 [app.md](app.md#地理数据资源)。高级配置（含 sing-box 规则集）仍从配置的“编辑”页进入，不在资源页重复。
- 抓包列表为合并请求页的“已抓取”视图；列表沿用连接视图的卡片、左右边距和行间距，支持搜索和分批加载。详情使用同一点击复制行及反馈，长 URL、头部与 Cookie 分行显示；右上角菜单切换请求、响应和 Cookies，请求与响应 Cookie 分组展示，保留重复 `Set-Cookie`、空值、值中的等号及属性，点击 Cookie 复制该条原文。抓取开关等待原生确认，停服时保留历史记录；远程会话显示不可用。
- 抓包设置使用共享分组表单，提供 HTTPS/MITM、证书管理、域名包含/排除、应用、原始目标端口、正文限额、可见域名提取及可选 QUIC TCP 回退。保存检查本机修订，失败保留草稿，运行中不能改设置。证书入口也在请求菜单中；生成 CA 是明确操作，重新生成需要确认，打开页面不生成、不安装、不启用抓包。证书页显示有效期、公钥 SHA-256、用户/系统信任及私钥可用性，提供 CRT/系统模块导出和 Android 设置入口；返回前台可刷新信任，远程会话不读本机证书，迟到结果不覆盖新会话。平台边界见 [app.md](app.md#请求抓取与-mitm)。
- 请求菜单提供抓包设置、MITM 证书、“抓包日志”、当前搜索的 HAR 导出和清空请求。日志页集中展示当前后端、实际 TLS 尝试/失败、解密连接/正文字节、QUIC 回退和解析/筛选/保存计数，支持搜索、复制、导出与独立清空。启动或握手失败可在日志中查看，不把开启开关当作收到 HTTPS 请求；日志来源及限额见 [libbox.md](libbox.md#httphttps-请求调试)。
- 请求详情保留重复头部、Cookie、正文状态及 trailers，显示原路由/应用、真实 SNI、双方 TLS 版本、ALPN 和上游证书 SHA-256，字段支持复制。已有记录中保存过的 PID/socket 字段仍可读，缺失值保持未知。域名观察与握手失败标为“仅域名”，不能导出 cURL；请求正文/头部不完整、截断或含 trailers 也不能导出 cURL。页面复用现有共享组件，没有修改 FlClash 直接导入文件。
- 常规服务页使用即时应用的开关，保存和重启期间显示进度并禁用开关，底部没有“应用服务设置”按钮；启动确认及失败恢复见 [app.md](app.md#配置服务应用与备份)。
- 日志页默认选择“跟随配置”，显示内核上报的日志级别及更严重的记录；菜单同时显示当前级别，仍可手动选择“全部”或单一级别，手动筛选不会被后续状态更新覆盖。搜索、暂停和导出沿用同一筛选结果；默认级别尚未收到时保留已接收记录。日志来源及本地重载同步见 [app.md](app.md#配置服务应用与备份)。
- 应用始终跟随系统语言并响应运行时变化。旧设置及备份中的语言选项丢弃，其他偏好保留。
- 代理列表保留圆角吸顶分组标题、固定高度的懒加载行、折叠及填充/普通/隐藏分组图标。折叠状态按选中配置保存，定位节点通过行偏移实现，不要求节点预先进入视口。
- 访问控制按上游 SFA 的列表/扫描分离方式加载，进入页面直接显示应用与已保存的托管选择；“重新扫描托管应用”为独立操作，扫描期间保留列表。筛选/搜索/排序结果在列表及相关条件未变化时复用，流量等无关状态更新不重新排序。图标保持按可见行请求，在 IO 线程绘制为位图（不经 PNG 编解码）并存入按字节计的 8 MiB `LruCache`，切换显示范围或无修改地关闭查询设置不清空图标；查询策略变更或申请权限后关闭设置只刷新一次。原生查询与托管更新约定见 [app.md](app.md#配置服务应用与备份)。
- 首页编辑保留卡片的全局 key，防止重复出口请求及丢失状态。改动立即保存；保存失败保留草稿，点击完成再次尝试。
- sing-box 专属 Tailscale、Taildrop、终端、认证、USB/IP、诊断和报告使用统一 Compose 页面，复用原生后端；能力边界见 [app.md](app.md)。

`BoxController` 按应用状态、启动/外观、流量、内存、连接和日志分开通知；流量采样与仪表盘累计值只更新相关组件，日志和连接变化不触发应用根部、代理或配置列表重建。出口查询监听应用状态变化，不因流量或内存采样重新计算路由标识。显式批量通知仍刷新全部依赖。

`BoxDataSubscription`（`ui/Records.kt`/`DashboardPage.kt`）按页面可见状态和应用生命周期合并内存/日志订阅；卡片与详情页共享一个原生采样请求，最后一个可见消费者退出时停止。日志增量保留既有记录对象，暂停快照独立于后续追加和淘汰，恢复可见时重新同步原生缓冲。原生采样与消息边界见 [app.md](app.md#配置服务应用与备份)。

抓包轮询（`ui/Records.kt`）仅在本地请求列表/详情或抓包日志页活动且应用前台时每两秒查询；调用不重叠，筛选变化合并刷新，并忽略旧筛选或会话的迟到结果。日志使用独立的 `capture.logs` 查询，请求列表不拉取日志历史。隐藏页面停止轮询，抓取引擎与日志记录继续运行至用户或服务停止。

正文预览（`ui/Records.kt`）放在等宽、自动折行、有界滚动的卡片中，展示内容类型、保存/总字节数及 Base64、截断、未完成状态。完整 JSON 可切换格式化与原文；格式化仅调整 JSON 字符串外的空白，保留数值字面量、重复键及转义，无法解析或超过格式化限额时显示原文。长预览分批展开，边界不拆开 UTF-16 代理对；复制始终使用内核返回的原始预览，不受当前格式或已展开长度影响，也不代表未保存的完整正文。

### 配置页与内核差异

配置列表按参考版布局使用两行卡片、固定行高、选中徽标及最多三列的自适应网格。普通配置页首次显示时定位已选配置，切换配置使用弹层。添加按钮和空状态按钮均打开菜单，新建入口标为“新建配置”；排序使用分组背景和拖动手柄。长按菜单依次提供编辑、更新（远程配置）、复制链接和二维码（远程配置）、分享、导出及删除，删除标为危险操作。编辑页显示配置摘要，提供元数据分组表单及原始/高级配置入口。

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

原始 JSON 编辑器（`ProfileEditor.kt`）显示从 1 开始的逻辑行号，空行和末尾换行保留编号；长行折行不增加行号，行号随正文滚动。行号布局与正文使用相同的字形、字号比例和换行宽度，仅创建可见编号；搜索、格式化、撤销及修订校验沿用原编辑流程。

高级配置新增“HTTP 改写”，由 `Configuration.kt` 的 HTTP 改写页复用共享分组、规则列表、排序及 JSON 表单，编辑配置启用状态、MITM 范围、Header/URL/Body Rewrite、Map Remote、Map Local/API Mock、JS 和内嵌资源。新建规则提供禁用的示例，用户修改匹配条件与内容后启用；完整 JSON 仍可直接编辑。字段及执行语义统一见 [libbox.md](libbox.md#json-配置中的-http-改写)。

所有修改继续调用 `ProfileConfigStore.set`，保留其他 JSON 字段、内容修订和原生校验/失败恢复；关闭失败表单前保留草稿。页面中的证书入口打开既有本机证书页，不把 CA 写入配置。请求页和诊断页单独显示 `rewriteActive`，说明停记录后规则仍生效；映射请求详情把连接出站/规则标为原连接，避免与映射目标的新路由混淆。证书页在配置处理运行时禁用重新生成。

### 状态卡片与出口查询

查询来源、字段解析及质量判定参考上述固定版本。IP 竞速由 [ui_egress.go](libbox/ui_egress.go) 负责：Cloudflare、IP.SB、IPinfo、ipwho.is、GeoJS、country.is、IPQuery、ident.me 共八源，B-box 的完整解析与失败回退、限额及传输约束见 [libbox.md](libbox.md#出口-ip-和质量查询传输)。

质量查询在 `compose/common/IpQuality.kt` 中依次分批使用 ident.me/ip-api.com/IPQuery、IPLocate、ipapi.is、proxycheck.io；等待一秒或当前请求全部失败后进入后续批次，单请求最多八秒。保留参考版组织/ASN 解析、显式类型优先、住宅类型推断及 Tor/abuser 质量规则。

`IpQualityCache` 将成功质量结果按 IP 持久缓存三天，失败仅在内存冷却五分钟，手动重试可跳过失败缓存。先显示 IP，再补充质量；质量失败保留 IP，详情展示来源和错误。

刷新与会话约定：

- 停止的本地服务通过设备当前网络查询，即使没有配置也可查询和手动重试；运行中通过 sing-box 路由查询。启停时取消旧探测，等待稳定服务状态再查询。远程会话保留占位，不展示本机出口。
- 返回前台保留未变路由的成功 IP 和质量；IP 查询失败才自动重试，间隔至少五秒，手动刷新可跳过。质量缓存到期只刷新质量。
- 短暂失焦不取消请求；已经发出的请求允许在后台完成，新查询等待前台。UI 在后台断开连接时保留本地分组快照，用于返回时比较。
- 启停、运行中的配置/模式/节点及网络变化取消旧请求、忽略迟到结果，并合并 300 毫秒内的变化后查询新出口，不受旧路由冷却限制。分组排序、延迟更新和停服时选择配置不触发无关的出口失效。

### 内网 IP 与备份展示

内网 IP 卡片提供开关、端口副标题、Wi-Fi 图标及复制按钮（Material 图标按钮）。共享开关修改选中配置的 mixed/http/socks listen；无代理入站时先显示端口表单，明确新增 mixed 入站。运行时复制已成功启动配置的端点，停服/远程会话复制设备 IP，详情保留多入站/多端口。配置变换和保存约束分别见 [libbox.md](libbox.md) 与 [app.md](app.md)。

备份页沿用参考版的本地/远程布局、文件名、范围和兼容/覆盖策略，实际格式是 B-box `bbox-1`。覆盖会在页面确认后移除备份中不存在的配置；取消选择、写入失败或上传未确认时不显示成功。事务、文件恢复和传输限额由 Android/libbox 实现，见 [app.md](app.md)。

## 验证与升级参考版本

当前界面检查为 [verify_newui.py](tools/verify_newui.py) 的 Compose 源码约束（由 [newui.yml](.github/workflows/newui.yml) 和 [build-debug-apk.yml](.github/workflows/build-debug-apk.yml) 运行），以及 [newui-android.yml](.github/workflows/newui-android.yml) 的 JVM 测试与模拟器 UI smoke，见 [app.md](app.md#验证与历史记录)。Compose 页面没有截图或像素比较测试；朴素界面改版尚未在实机上做视觉验收。删除 `flutter/` 前的 Flutter 测试与截图记录可在 Git 历史中查看，不代表当前源码已通过同等测试。

升级布局参考版本时只核对页面布局与信息组织，不再迁入控件、字形、动效或特效；同步审查来源清单和许可，按实际 Actions 运行记录结果。
