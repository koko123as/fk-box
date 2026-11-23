# 界面说明（Material Design 3 界面，FlClash 仅作布局参考）

本文记录界面的实现方式、布局来源和适配范围。Android 后端差异见 [app.md](app.md)，核心扩展见 [libbox.md](libbox.md)，维护约束见 [AGENTS.md](../AGENTS.md)。

`ui/plain` 起界面改为朴素的 Material 3 风格；`ui/m3-rewrite` 按 [skydashnet/material-design-3-ui-skill](https://github.com/skydashnet/material-design-3-ui-skill)（v1.1.1）把视觉层重写为完整的 Material Design 3 系统（见下文“Material Design 3 界面约定”）。各页面的分区、顺序、按钮位置、底部四个目的地（仪表盘、代理、配置、工具）和平板侧栏仍按原 FlClash 布局；FlClash 提供页面布局、信息组织以及动效与交互手感的参考（动效用 Slint 重新实现，见下文“动效”），不提供控件、字形、形状或特效。FlClash 的来源与 GPL-3.0 许可保留。

## Rust + Slint 实现

界面由 Rust + Slint 实现，位于 [crates/client-ui/](../crates/client-ui/)：`ui/*.slint` 为布局，`src/` 为状态投影与页面逻辑，编译为 `libfkui.so`，由 Kotlin 的 `bridge/BoxActivity.kt`（GameActivity）加载；命令与事件经 `bridge/BoxBridge.kt` 和 `bridge/SlintHost.kt`（JNI）往返。Jetpack Compose 界面已删除，`compose/` 只保留稳定组件名 `MainActivity`、`ShareActivity` 和扫码用的 `qr/`；扫码相机、Ghostty 终端和 OpenConnect 登录 WebView 是 `bridge/NativePages.kt` 中的原生 Activity。仓库不含 Flutter/Dart 代码，最初按 FlClash 的 Flutter 页面翻译、随后的 Compose 实现均可在 Git 历史中查看；逐文件的 Compose → Slint 对应与已知差异见 [slint-manifest.md](slint-manifest.md)。页面对应关系：

| 原 Flutter 页面 | Slint 实现（`crates/client-ui/` 下） |
| --- | --- |
| dashboard、status_panel 等首页组件 | `src/pages/dashboard.rs`、`src/pages/egress.rs`、`ui/pages/dashboard.slint` |
| proxies、proxy_card、proxy_list | `src/pages/proxies.rs`、`ui/pages/proxies.slint` |
| profiles、profile_details、profile_editor、configuration | `src/pages/profiles.rs`、`src/pages/config.rs`、`src/forms.rs`、`ui/pages/profiles.slint` |
| tools、connections、logs | `src/pages/tools.rs`、`src/pages/requests.rs`、`ui/pages/tools.slint`、`ui/pages/requests.slint` |
| requests、request_settings、capture_domains、capture_logs、mitm_certificate、captured_body、capture_poll、http_message | `src/pages/requests.rs`、`src/records.rs` |
| terminal、terminal_settings | `src/pages/terminal.rs`（终端表面为原生 `TerminalActivity`） |
| diagnostics、reports、endpoints、taildrop、authentication、settings 中的远程控制 | `src/pages/tools.rs` |
| settings（常规/服务/核心）、about、backup、access | `src/pages/settings.rs`、`src/pages/routing.rs`、`src/pages/resources.rs` |
| appearance、theme_preview、palette、segmented_control | `src/pages/appearance.rs`、`ui/pages/appearance.slint` |
| record、data_subscription | `src/records.rs`、`src/pages/mod.rs` 的可见性订阅（`sync_visibility`） |

大部分子页为 Rust 构建的通用行页面（`src/rows.rs` 的 `Page`/`Row`，由 `ui/pages/rows.slint` 绘制）；导航、对话框、菜单和消息在 `src/nav.rs`、`ui/mobile/android/routes.slint` 与 `ui/app.slint`。

## Material Design 3 界面约定

`ui/m3-rewrite` 按 Material Design 3 UI skill 重写视觉层，浮在内容之上的表面一律为不透明的 M3 色调表面（见下文“不透明表面”，不做背景模糊）；Rust↔Slint 接口（导出组件、属性、回调、结构体字段与全局）保持不变，页面布局与信息组织仍按 FlClash。

- 令牌（`ui/theme/theme.slint`）：颜色全部为 M3 语义角色，由 `src/theme.rs` 用 Material Color Utilities 的动态配色从主题色生成明暗两套（primary/secondary/tertiary/error 及其 container 与 on- 角色、surface 与 surface-dim/bright、surface-container-lowest…highest、outline/outline-variant、inverse 与 scrim/shadow）；配色风格映射为 Fidelity（鲜艳）、Content（着色）、TonalSpot（柔和，M3 默认）和 Monochrome；成功/警告是向主题色协调（harmonize）的 M3 自定义颜色；纯黑模式只压暗深色方案的表面。`theme::tests::role_pairs_meet_contrast` 检查各风格、明暗与纯黑下 on- 角色的对比度（文字 ≥ 4.5:1，outline ≥ 3:1）。字体为 M3 字阶（display/headline/title/body/label 各 large/medium/small，字号、行高、字重、字距），`TextRole` 即这 15 个角色；形状为 M3 圆角刻度（extra-small 4、small 8、medium 12、large 16、extra-large 28、full）；间距为 4 dp 网格；状态层不透明度为悬停 8%、聚焦 10%、按下 10%、拖动 16%，禁用内容 38%、禁用容器 12%；最小触控目标 48 dp。
- 组件（`ui/components/kit.slint`）：M3 小型顶部应用栏（64 dp，title-large 标题，48 dp 图标按钮；页面内容从栏下滚过，滚动后栏在 `motion-short` 内过渡为不透明的 surface-container，即 M3 的滚动态容器色）、搜索栏（56 dp 全圆角）、按钮（填充/色调/描边/文字/提升，40 dp 高、全圆角、label-large、触控高度 ≥ 48 dp）、图标按钮（标准/色调/填充）、开关（52×32 轨道，选中带勾）、单选、复选、筛选/输入纸片（32 dp，small 圆角）、分段按钮、卡片（填充/提升/描边，medium 圆角）、分组列表项（surface-container-low 容器，首尾 large 圆角，内缩分隔线，单行 56 / 双行 72 dp）、描边文本框（56 dp，extra-small 圆角，标签落在描边上，错误同时有文字说明）、线性/环形进度。可交互组件有悬停/按下/聚焦状态层，可用 Tab 聚焦（3 dp secondary 聚焦环，Enter/空格触发），并声明无障碍角色与名称。
- 导航（`ui/mobile/android/home.slint`）：窗口宽度 ≤ 600 为悬浮的导航栏：72 dp 全圆角胶囊，距左右 16 dp（`Nav.bar-side`，最宽 480 dp 居中）、距手势区上方 12 dp（`Nav.bar-margin`），不透明的 surface-container 加 2 级阴影（`Surface`），内含 M3 目的地（64×32 secondary-container 指示器，选中图标为实心、未选为描边，label-medium 标签）。首页各页内容延伸到栏下滚动，末尾留出 `Nav.home-bottom`（手势区 + 间距 + 栏高），最后一项可滚到栏上方；消息条位于栏上方。更宽为 M3 导航侧栏（80 dp，56×32 指示器；展开为 220 dp，图标与标签同行），侧栏与页面并排、没有内容从其下经过，因此保持不透明的 surface。目的地顺序在两种形态下一致。
- 覆盖层（`ui/app.slint`）：菜单为 extra-small 圆角的不透明 surface-container 加 2 级阴影、48 dp 项；对话框为 extra-large 圆角的不透明 surface-container-high、headline-small 标题、文字按钮靠尾部（危险确认用 error 色）；消息条为 inverse-surface、extra-small 圆角、3 级阴影（不透明）。行页面底部提示条为 large 圆角的不透明 surface-container-high 加 2 级阴影。Android 上对话框、菜单和消息仍优先使用平台原生实现（`src/native_ime.rs` 的 `native_ui`），上述样式用于 Slint 自绘时。
- 页面：仪表盘卡片为填充卡片（surface-container-highest），卡片标题为 title-medium 加 primary 图标，内嵌块为 surface-container-low；启停按钮为卡片内的 M3 大号 FAB（extra-large 圆角，停止时 primary-container、运行时 primary，图标表示状态）；出站模式为分段按钮；上传/下载曲线分别用 tertiary/primary。代理分组为可滚动的 M3 主标签页（3 dp 指示器），节点与配置为描边卡片，选中时填充 secondary-container 并显示勾选徽标（不只靠颜色）。日志/请求记录为连续列表项（2 dp 间隔），不是每行一张卡片。外观页使用 M3 分段按钮，色板单元为 48 dp。
- 不透明表面：浮在滚动内容之上的表面——悬浮导航栏、内容滚入后的顶部应用栏、菜单、对话框和行页面底部提示条——一律经 `ui/components/kit.slint` 的 `Surface`：背景为 `Theme.level` 的 surface-container 色调角色（不透明），需要阴影时只经 `Theme.shadow-*` 令牌（`elevation` 0–3）。不使用 `backdrop-blur` 或半透明“玻璃”：模糊在内容从其下滚过时每帧重读并过滤下方像素，滚动掉帧、GPU 负担大；不透明表面的阴影由 Skia 渲染器按尺寸缓存。文字对比度由 `theme::tests::role_pairs_meet_contrast` 覆盖（on-surface-variant 在 surface-container/-high 上 ≥ 4.5:1）。早先的 `Glass` 组件与玻璃令牌已删除，旧的 `liquidGlass` 偏好仍在读取时丢弃。
- 阴影只用于浮在内容之上的表面（菜单、消息条、FAB、提升卡片/按钮、悬浮导航栏、行页面底部提示条），并只经 `Theme.shadow-blur/offset/color` 令牌设置；常规层级用 surface 色调区分。不使用 `backdrop-blur`、液态玻璃着色器、`RuntimeShader`/AGSL 或其他模糊滤镜（`blur()`）。动效见下文“动效”；无页内拖动返回。
- 图标为 Material Icons 字体（`ui/icons/fonts/`），`ui/icons/icons.slint` 按用途命名映射，Rust 端按名称查表（`src/nav.rs` 的 `glyph`）。
- 界面文字经 `@tr()`（Slint）与 `src/i18n.rs` 的 `tr()`（Rust）翻译，简体中文表为 `crates/client-ui/i18n/zh/LC_MESSAGES/fkbox-client-ui.po`；Rust 源码中不写死中文。
- 外观页保留主题色（含自定义取色）、配色风格、纯黑模式、明暗跟随和字号/字体，并有“页面切换”（滑动/淡入，界面偏好 `tabMotion`，默认滑动）；旧设置与备份中的 `appearanceStyle`、`liquidGlass`、`tabAnimation`、`floatingNavigation`、`sidebarExpanded` 键在读取和恢复时丢弃（[BoxPreferences.kt](../apps/android/app/src/main/java/io/nekohasekai/sfa/bridge/BoxPreferences.kt)）。
- 保留的自绘内容均为功能性：网络速度折线图（`ui/pages/dashboard.slint`）、二维码（`src/pages/profiles.rs`，qrcodegen）、颜色选择器的色板/色谱/滑条，以及订阅额度和进度条。
- [scripts/verify_newui.py](../scripts/verify_newui.py) 在 CI 中检查上述约束的源码部分（界面入口、Compose/Flutter 已删除、禁用的特效与依赖、任何 `.slint` 中不出现 `backdrop-blur` 属性或已删除的 `Glass`/玻璃令牌、`Surface` 为不透明色调表面、顶部应用栏继承 `Surface`、手机导航栏为带阴影的悬浮 `Surface` 且 `home-bottom` 留出栏高、页面中带点按处理的 TouchArea 都驱动水波纹（或为 `Tap`，遮罩/消息条除外）、列表动效组件与三个列表的接入、底部表单拖动使用 `Theme.sheet-*` 令牌、Slint ≥ 1.19 且两个 vendored crate 与之同版本、阴影只经令牌、页面不写十六进制颜色、M3 令牌与组件存在）。

## 动效

参考 FlClash v0.9.3 的动效与交互手感（`lib/pages/home.dart` 的 PageView 与底栏、`lib/ui/list_item.dart` 的 OpenContainer、`lib/views/proxies/list.dart` 的展开、`lib/views/dashboard/edit.dart` 的编辑晃动与让位、`dashboard/widgets/current_outbound.dart` 的 ease-out-back 形变，以及 `context.motionDuration`/`disableAnimations`），在 Slint 中重新实现；不做背景模糊、磨砂或液态玻璃。

- 令牌（`ui/theme/theme.slint`）：时长 `motion-short` 150 ms、`motion-medium` 300 ms、`motion-long` 450 ms、淡出 `motion-exit` 90 ms／淡入 `motion-enter` 210 ms（自 `motion-enter-scale` 0.92 放大）、分页 `motion-tab` 300 ms、页面推入/返回 `motion-route` 200 ms、底部表单 `motion-sheet` 300 ms、对话框 `motion-dialog` 404 ms（自 `motion-dialog-scale` 1.2 倍）、水波纹 `motion-ripple` 450 ms／`motion-ripple-fade` 150 ms、错峰 `motion-stagger` 30 ms（最多 `motion-stagger-items` 8 项）、编辑让位 `motion-yield` 260 ms 与出现 `motion-appear` 200 ms、晃动周期 `motion-sway-period` 3600 ms（0.7°、1.25 px）；曲线 `easing-standard`／`easing-emphasized` cubic-bezier(0.2, 0, 0, 1)、`easing-decelerate`、`easing-accelerate`、`easing-back`（ease-out-back），手动推进的进度用 `Theme.ease-out`/`ease-out-back`/`progress`。组件中不写死时长或曲线：`scripts/verify_newui.py` 检查 `animate` 块的 duration/delay 只用 `Theme.motion-*`、easing 只用 `Theme.easing-*`、`animation-tick()` 的周期只用令牌、主题外不出现 `cubic-bezier(`。
- 减少动画：Android 宿主（`BoxActivity`）读取 `Settings.Global.ANIMATOR_DURATION_SCALE`，随 `locale` 回复（`animatorScale`）及回到前台时的 `motion` 事件传入；Rust 端 `theme_apply::set_motion_scale` 写入 `Theme.motion-scale`，除周期类令牌外所有时长都乘以该系数；为 0（开发者选项关闭动画或无障碍“移除动画”）时所有变化立即完成，`Theme.motion-on` 为假时不画水波纹、编辑晃动和容器变换。不依赖 Slint 自带的减少动画查询。
- 状态过渡：状态层、按钮、图标按钮、开关（滑块位置与尺寸）、单选/复选、筛选纸片、分段按钮、卡片和导航项的颜色与尺寸按 `motion-short`/`motion-medium` 过渡；导航指示器选中时从中心展开，导航图标在描边/填充两图标间交叉淡化并放大（填充图标用 ease-out-back）。
- 水波纹：`kit.slint` 的 `Ripple` 在 TouchArea 的 pointer-event 按下时从按下点扩散（`centered` 时从中心），剪裁到表面圆角，松开后淡出；只在存在时读取 `animation-tick()`。`kit.slint` 的 `Tap` 把状态层、水波纹和 TouchArea 合为一体，供页面中自行处理点按的表面使用。所有可点按的行与按钮都有水波纹：按钮、图标按钮、卡片、仪表盘面板、可点击列表项、筛选纸片、分段按钮（含出站模式）、导航项、菜单项、启停按钮、对话框选项、代理分组标签、节点延迟、记录行及其筛选标签、可折叠分组标题、内网代理行、服务行、出口 IP/内网地址、国家徽标重试、仪表盘编辑的移除徽标和色板（`scripts/verify_newui.py` 检查页面中的点按处理都带水波纹，遮罩、消息条和表单行聚焦除外）。
- 底栏：手机首页的悬浮底栏（不透明）固定在手势区上方，滚动时不再隐藏（用户要求固定）；消息条始终位于底栏上方。
- 手势方向：每次触摸在分发给 Slint 之前先判定方向（`src/gesture.rs`，Android 后端的触摸观察器调用）：手指移动 6 px 后，横向位移不超过纵向 1.5 倍的触摸视为滚动，设置 `Motion.vertical-lock`，首页翻页的 SwipeGestureHandler 在手指抬起前保持禁用。Slint 1.19 的 SwipeGestureHandler 在横向 8 px 且横向大于纵向一半时就接管触摸（约 63° 以内都算横向），稍斜的上下滑动会被翻页抢走而页面不动（“划不动”）。
- 首页切换：手机默认滑动分页（类似 PageView）：点按目的地时旧页滑出、新页滑入（跨多个目的地时只移动这两页），水平拖动带入相邻页，松手超过四分之一宽度即切换，否则回弹，首尾页拖动有阻尼；仪表盘编辑中和代理页的分组标签模式下不响应水平拖动。外观页可改为淡入（fade-through：旧页淡出 90 ms，新页淡入并自 0.92 放大 210 ms）；侧栏窗口总是淡入。核心页面常驻，只有当前页与正在移动的页可见；`Motion.pages-moving` 标记移动中，截图测试检查它总会复位。
- 状态切换：空状态、启动错误、首页、代理/配置/行页面内容出现时淡入并轻微放大（`kit.slint` 的 `Appear`）。Slint 的 `if` 移除是瞬间的，被替换的内容不做淡出（首页分页常驻，两半都有）。
- 页面推入/返回（FlClash `FadePageRoute`，200 ms）：普通页面推入时在 `motion-route` 内淡入；手机上表单的首页作为底部表单自底部升起（`motion-sheet` 300 ms，减速），宽屏的表单卡片与遮罩一起淡入并自 `motion-enter-scale` 放大。返回时路由先留在栈里（`src/nav.rs` 的 `close` 设置 `Motion.leaving-id`），页面本身在 `motion-route` 内淡出露出下层页面（手机底部表单整体下沉、宽屏表单卡片与遮罩一起淡出），动画结束后由 `Nav.route-left` 移除；动画中再按返回会先完成移除再返回下一层。推入的页面先在前 1/3 时长铺上不透明 surface 再淡入内容，两层内容不会互相透出。手机底部表单下方有 M3 的 32% 遮罩（随表单移开而变淡），顶部为 extra-large 圆角容器与 `Theme.sheet-handle`（24 dp）高的拖动条，条中为 32×4 dp 的 M3 拖动手柄。容器变换：展开时 surface 保持不透明，页面在其上淡入；收回时页面先在前 30% 淡出，surface 再缩回卡片。
- 底部表单拖动（FlClash 的模态底部表单，按 Flutter `BottomSheet` 的阈值）：按住拖动条向下拖，表单随手指下移（不会高于原位），遮罩同步变淡；松手时下移超过高度的 `Theme.sheet-close-fraction`（0.5）或向下速度超过 `Theme.sheet-fling-velocity`（700 dp/s，取最后几次移动的平滑速度，抬手前停顿 100 ms 以上视为 0）即关闭表单（经 `Nav.dismiss-form`，有未保存更改时先询问；询问时表单弹回），表单从松手处继续下沉，剩余距离按比例缩短 `motion-sheet` 时长；否则在 `motion-sheet` 内弹回原位。拖动中收到取消、宿主复位手势（`Motion.gesture-resets`）或 `touch-drag-idle` 内无移动时弹回。拖动条的无障碍默认操作为关闭。宽屏的表单卡片不可拖动，点遮罩关闭。
- 对话框（FlClash `showFloatingDialog`）：自 `motion-dialog-scale` 1.2 倍缩小到原尺寸并淡入，遮罩同步淡入；关闭时保持当时尺寸只淡出，期间显示关闭前内容且不响应点按。两向都用临界阻尼曲线（`Theme.ease-spring`，感知时长 275 ms，`motion-dialog` 404 ms 内收敛）。
- 触摸：首页水平拖动须先越过 `Theme.touch-slop`（24 px）且水平位移大于垂直位移的 `touch-axis-ratio`（1.5）倍才进入拖动，并扣除这段距离；松手、取消、失焦、切换目的地、回到前台（`Motion.gesture-resets`）及 `touch-drag-idle`（1200 ms）无新移动时都会结束拖动并复位 `Motion.swiping`、`Motion.pages-moving`、`Motion.home-offset`，首尾页的阻尼拉伸动画回到 0。水波纹与状态层在 `touch-press-max`（3000 ms）内收不到松手时自行淡出。
- 代理组（列表视图）：展开/收起时对显式高度做动画并剪裁，节点卡片错峰淡入放大（只对前 8 项错峰），箭头图标旋转 180°；收起完成后才移除节点网格。
- 列表增删（配置卡片、代理组列表视图、Rust 行页面如分流/路由规则与设置列表）：`kit.slint` 的 `ListMotion` 观察列表长度，`Entrance` 播放单项进场。新加入的项在 `motion-appear` 内淡入并展开高度（行页面、代理组）或自上方 `motion-list-rise`（8 dp）落到位置（配置卡片网格）；删除一项后，后面的项从原位置滑到新位置（`motion-appear` 减速曲线）：模型按位置原地更新时，内容换成后一项的格子淡入（代理组还带 8 dp 上移），配置卡片从后一项原来的格子滑入；模型删除对应实例时（行页面按行键对齐删除/插入），索引变小的项内容从原位置滑上来（Slint 布局中的 y 由布局设置、不能动画，因此移动的是项内内容）。列表显示后的前 `motion-medium` 内、一次增删超过 `motion-list-batch`（3）项（重载、搜索、筛选）或 120 ms 内连续变化累计超过该数时不做动画；懒加载行（应用列表）、记录行（连接、日志）和倒序列表（日志）不做动画；动画关闭时全部立即完成。
- 数值：仪表盘上传/下载速率、内存与连接计数（整数）从旧值补间到新值（按 `format.rs` 的单位规则在 Slint 中还原字节数再格式化，单位切换不跳变）；节点延迟数字补间；确定进度条宽度补间。启停按钮在运行状态变化时以 ease-out-back 改变圆角并旋转图标；出口国家徽标在出口变化时以 ease-out-back 弹出。
- 仪表盘编辑：原地编辑显示的卡片（单列阅读宽度），卡片按各自相位轻微晃动（`animation-tick()` 驱动旋转与上下浮动）且内部控件不响应；长按卡片后拖动，经过的卡片以 `motion-yield` 让位，松手后卡片滑入新位置再保存顺序（`dash-move`）；减号徽标移除卡片（先淡出 `motion-appear`，再收起空间 `motion-yield`，然后 `dash-hide`）；从隐藏列表添加的卡片先让出空间再出现。状态卡不可移除。拖动时页面停止滚动，暂不在边缘自动滚动。
- 容器变换（手机）：仪表盘面板或带箭头的列表项打开页面时（点按后路由数增加），`RouteHost` 从其位置、尺寸、圆角和颜色画一个不透明表面在 `motion-long` 内长到全屏，同时颜色变为 surface，最后 40% 新页面淡入；关闭该页面时表面从全屏缩回原卡片。Slint 关闭路由是瞬间的，返回时表面上不显示页面内容。

## 参考版本与来源清单

布局参考固定为 [chen08209/FlClash v0.9.3](https://github.com/chen08209/FlClash/tree/be84736605527034689c95bf27828ff27d912c5e)（`be84736605527034689c95bf27828ff27d912c5e`，2026-11-04），最初翻译依据为 v0.9.2（`7fd417fb9a9a277c6a9339a8ac87f907df7c25f7`）。服务状态按该版本迁入（规则移植到 `apps/android/core/ui_service_check.go`，界面见下文仪表盘一节）；DNS 记录和应用内更新安装会改变功能，未迁入。84e71e4^ 之前的旧 SFA Compose 页面及其后的 Compose 实现均不再使用。

[flclash-manifest.json](../resources/flclash-manifest.json) 记录参考版本、原生适配（`adaptedNativeFiles`：`apps/android/core/ui_egress.go` 对 FlClash IP 查询的适配，用 sing-box 路由和 libbox HTTP 替换原传输）和补充参考（`supplementalReferences`：MITM dev、地理数据资源页设计）。原 Flutter 导入的逐文件清单可在 `6902a3d` 的 `flclash-manifest.json` 中查看。

请求/MITM 功能另参考 FlClash `dev` 的 [`63aa150e23288558074d877f745a6d3b5f6418f6`](https://github.com/chen08209/FlClash/tree/63aa150e23288558074d877f745a6d3b5f6418f6)（[`f544861`](https://github.com/chen08209/FlClash/commit/f54486102172566e7342d7f5c138663c3c5dec0b) 的 MITM 应用侧设计、[`1a7556d`](https://github.com/chen08209/FlClash/commit/1a7556d30a38707f39abfba4b7d6a6b8f57774ed) 的请求/连接整合）。该 dev 源码的 `FEATURE_MITM` 默认关闭，B-box 自行实现 sing-box 路由上的 TLS 接管。

FlClash 许可保留在 [licenses/LICENSE](../resources/licenses/LICENSE)，字体归属见 [Twemoji-LICENSE.md](../resources/licenses/Twemoji-LICENSE.md)。

## 界面复用与业务适配

页面状态和操作经 Android 桥接接入 libbox；配置列表与界面偏好直接读写 `crates/client-core` 的客户端存储。没有引入 FlClash/Mihomo 原生库、Clash 配置模型、Mihomo 配置覆写脚本、订阅转换器或 Clash 专用服务。请求/响应脚本的范围见 [核心 HTTP 改写](libbox.md#json-配置中的-http-改写)。

| 区域 | 实现 | 当前适配 |
| --- | --- | --- |
| 首页与状态 | `src/pages/dashboard.rs`、`src/pages/egress.rs` | 状态、国家、出口 IP 与质量、网络速率；启停调用原生服务流程 |
| 配置卡与分组概览 | `src/pages/dashboard.rs` | 使用真实配置元数据和内核分组，远端未知数量保持未知 |
| 代理 | `src/pages/proxies.rs` | 卡片与列表都使用 libbox 分组；只有可选择的组显示选择操作 |
| 配置管理 | `src/pages/profiles.rs`、`src/pages/config.rs` | 行高、选中态、相对更新时间、添加菜单、表单及排序；编辑和导入各内核配置 |
| 首页编辑 | `src/pages/dashboard.rs` | 原地编辑卡片：长按拖动排序、移除按钮及可添加列表 |
| 内存与局域网 | `src/pages/dashboard.rs` | 分别使用 Go/进程内存和 sing-box 监听配置，不虚构 Mihomo 内存或全局代理端口 |
| 系统 HTTP 代理 | `src/pages/dashboard.rs` | 图标、启用/禁用副标题及开关，接入内核上报状态及既有 Android 桥接 |
| 外观与关于 | `src/pages/appearance.rs`、`src/pages/settings.rs` | 主题色、配色风格、明暗与字号；版本/许可信息，Release 来源为 [koko123as/fk-box](https://github.com/koko123as/fk-box/releases) |
| 备份 | `src/pages/settings.rs` | 本地/远程分区、WebDAV 表单、连通状态、恢复范围及策略，后端使用 `bbox-1` 格式 |
| 请求调试 | `src/pages/requests.rs`、`src/records.rs` | 请求列表/详情、本机筛选、HTTPS MITM 与证书、诊断、域名选择及 HAR/cURL 导出 |

### 导航与交互约定

- 布局断点按 FlClash v0.9.3 的布局：窗口宽度 ≤600 为手机、≤840 为 laptop、更宽为 desktop（`ui/models/globals.slint` 的 `Nav.mobile` 等，与 M3 的 compact/medium/expanded 宽度级别对应），卡片间距 8/12/16。非手机宽度在左侧显示 M3 导航侧栏；侧栏比手机多出“请求”（仅 sing-box 内核，其他内核在工具页灰显）和“日志”，工具页相应隐藏这两项。每个目的地在非手机宽度有自己的导航栈（`ui/mobile/android/routes.slint` 的路由 `pane`），推入的页面留在内容区，侧栏保持可见；表单、弹窗和菜单在根导航器上。
- 消息（`src/nav.rs` 的 `toast`，Android 上为系统 Toast）：普通消息短时显示，错误长时显示，新消息替换当前消息。
- 只读文字长按打开平台原生的文本查看器以选择复制（`select-text`）；输入框由覆盖在焦点字段上的原生 EditText 编辑（`src/native_ime.rs`），使用系统选择菜单与放大镜。
- 列表基础设施（布局参考 FlClash v0.9.3 的记录筛选）：页面（`src/rows.rs` 的 `Page`）支持 `title_menu`（点按标题弹出菜单切换视图，标题后带 20/16 dp 下拉箭头，供请求页切换 最近/运行中/抓包）和 `keywords`（栏下的关键词条，36 dp 胶囊，显示时正文不再位于栏下）。`src/records.rs` 的筛选器（`Filters`）：点按行内的值（日志级别；连接的网络、规则、应用、代理链各出站）添加筛选条件，同一维度内为“或”、不同维度为“且”；手机宽度只显示已选条件（可移除），非手机宽度固定显示所有可选值作切换；菜单中有“筛选”（网络、规则；日志为级别，按出现次数取前 12 个）和“分组方式”（连接：应用/主机/规则/出口），分组以可折叠标题（名称和数量，默认折叠）显示，所选分组按页面保存在 `recordGroupings` 界面偏好。原有关键词搜索、活动/已关闭、排序、日志级别菜单、暂停、清除、导出及关闭连接保持不变；连接记录没有 MITM 标记，MITM 维度留给抓包列表。
- 请求页（布局参考 FlClash v0.9.3）合并请求与连接：点按标题切换“最近请求”（已关闭连接，菜单可改为全部）、“运行中”（活动连接，带关闭全部）和 fk-box 的“已抓取”（原 HTTP 抓包列表，抓包开关、设置、MITM 证书、抓包日志、导出 HAR、清除及详情不变；Xray/mihomo 下该项置灰并显示原因，远程会话显示仅限本机）。所选视图保存在 `requestFeed` 界面偏好。每个视图有独立的搜索、筛选器和分组：连接视图沿用网络/规则/应用/代理链维度，抓包视图为 MITM（MITM/HTTP/仅域名）与应用维度，可按应用或主机分组。侧栏的“请求”在各内核均显示，工具页只保留一个“请求”入口（手机宽度）。抓包记录没有连接 ID，连接详情仍为原字段，未加入每连接的 HTTP 交换标签页。
- 快速编辑（参考 FlClash v0.9.3 的快速编辑）：`src/forms.rs` 的快速编辑页以等宽文本编辑整个列表，每行一项（行首 `- `、行尾逗号和引号会去掉，逗号也分隔，空行和重复项忽略）；无效项以“第 n 行：原因”列出前三条，并询问是否放弃更改，选择取消时编辑器保持打开。入口：抓包设置的 HTTPS 域名、HTTPS 排除域名和端口（端口仍校验 1–65535）各有“快速编辑”行，结果写回表单草稿后仍经原保存与修订检查；抓包应用页工具栏可按包名逐行编辑；HTTP 改写页新增 MITM 域名/排除域名（`mitm.hosts`、`mitm.excluded_hosts` 字符串数组）；高级配置的各对象列表（HTTP 改写规则、DNS 服务器与规则、路由规则、规则集、入站/出站/端点）工具栏可每行一个 JSON 对象整体编辑（参考版 `parseLineInput` 保持每行完整），保存经高级配置存储（`src/pages/config.rs` 的 `set`）的修订检查。保存的数据格式与原有校验不变。
- 首次启动直接进入仪表盘，没有免责声明接受门槛。默认按顺序显示状态、网络速度、出站模式、VPN、内网 IP、配置、服务状态、内存、连接统计和代理组（系统 HTTP 代理按内核能力出现）；VPN 与服务状态只用于本机服务，远程会话不显示。已保存的布局缺少后来加入的卡片时，新卡片插在默认顺序中它前一张卡片之后，隐藏偏好保持不变。旧的 `Connections` 键仍被接受并忽略。
- 仪表盘宽屏网格按 FlClash 平板仪表盘（`apps/android/native/src/pages/dashboard.rs` 的 `span()`/`pack()`）：内容宽度按每列至少 260 分为 2～4 列，卡片按顺序装入行，下一张放不下时由后面第一张放得下的卡片补位，补不满的行由最后一张卡片占满。状态、网络速度、出站模式、VPN、系统 HTTP 代理、连接统计和代理组占两列，其余占一列，因此 4 列为 [状态 ×2｜上传｜下载] / [出站模式条 ×2｜VPN ×2] / [内网 IP｜配置｜服务状态｜内存] / [连接统计 ×2｜代理组 ×2]。启停按钮在状态卡右侧（各宽度一致，不再有悬浮按钮）；VPN 卡与该按钮调用同一启停流程。网络速度在网格中拆成上传、下载两张卡，显示当前速度、近 30 次采样的峰值、累计和曲线；手机单列仍为一张双磁贴卡。出站模式在网格中为单行条（标题后接图标加文字的分段按钮）。服务状态按 FlClash v0.9.3 的 `ServiceStatusCard`、`ServiceStatusSheet` 与 `ServiceManageView`（`src/pages/services.rs`，规则见 [libbox.md](libbox.md#服务状态检测)）：共 13 项服务，顺序、名称和标识与 FlClash 相同（Google、GitHub、YouTube、ChatGPT、Claude、Gemini、Netflix、Disney+、Prime Video、Spotify、TikTok、Discord、Bilibili；标识为 FlClash 附带的 Simple Icons/自绘矢量，`src/pages/service_icons.rs`）。卡片显示前 4 项已启用服务（两列两行），可用时显示延迟毫秒数，否则显示状态（访问受限、不允许的 ISP、已被封禁、地区不支持、仅限自制内容、即将上线、超时、检测失败），状态点绿/黄/红；标题行显示“N 项异常”或“全部可用”标签，点按卡片进入“服务状态”页。该页列出全部已启用服务：标识、名称、服务报告的地区与检测时间、延迟或状态；点按一行复测该项，右上角“全部检测”复测全部，“管理服务”页可开关各项（最后一项不可关）并用上移/下移调整顺序（FlClash 为拖动），前 4 项标注“显示在仪表盘”；顺序与关闭项保存在界面偏好 `serviceOrder`、`disabledServices`。检测经运行中的内核（停服时直连）走仪表盘出口探测会话（`openProbe` → `serviceCheck` → `closeProbe`），最多同时 4 项。每个进程只自动检测一次：卡片或服务状态页第一次在前台显示时（服务不在启动或停止中）检测全部已启用服务；之后只按点按检测（页中一行、“全部检测”或卡片按钮），不按计时、路由变化、回到前台或开关/排序自动复测。应用进入后台即清空等待队列，不发起新检测。连接统计显示实时连接、最近请求和 DNS 查询：卡片可见时以 `setConnectionStatsVisible` 引用计数订阅连接流，只回传计数（不传连接行）；实时连接为未关闭连接数（订阅未就绪时取内核状态的 `connectionsIn`），最近请求为五分钟窗口内已关闭的连接，DNS 查询为本机内核自上次启动/重载以来应答的 DNS 查询数（`Libbox.readUIDNSQueryCount`，见 [libbox.md](libbox.md#仪表盘-dns-查询计数)），包括 hijack-dns 这类不形成连接的查询；远程会话只能按连接列表计数（DNS 出站、嗅探为 DNS 或目标端口 53）。点按前两项打开请求页对应视图。
- 出站模式只显示内核提供的值。规则、全局、直连使用 Material 图标，名称和选中态忽略大小写，排列和切换参数保留内核原值。系统 HTTP 代理卡片按内核能力显示，支持首页编辑中的排序、移除和重新添加；能力暂不可用时保留顺序和隐藏偏好。卡片为图标、标题、启用/禁用副标题及开关。
- 服务按钮的暂停图形表示停止服务，不新增暂停核心能力。远程会话、启停过渡和不可用操作必须反映真实状态。
- 工具页按 v0.9.3 的工具页布局：顶部分组是导航“更多”项（手机宽度的请求、资源、手机宽度的日志；侧栏已显示的项不重复），随后是 fk-box 自有工具：运行中有数据的端点（Tailscale（含 Taildrop）、OpenVPN、OpenConnect、USB/IP）、终端和远程控制。设置分组依次为外观、访问控制、分流规则（fk-box 自有页面，见 [app.md](app.md#分流规则)）、备份与恢复、常规（参考版的语言项不迁入，沿用系统语言；Clash 高级配置不适用）。其他分组为网络质量、STUN 测试、崩溃/内存/电量报告和关于（参考版在此放开发者页；没有免责声明页）。终端设置在终端内，仪表盘编辑在首页工具栏。配置卡与仪表盘配置卡在副标题前/名称后显示配置实际使用的内核（sing-box、Xray 或 mihomo）标签。资源页按参考版只保留地理数据部分（自动更新及小时间隔、GeoIP/GeoSite/MMDB/ASN 四行的大小、更新时间与来源（APK 未内置且尚未下载的显示“首次使用时下载”）、单独更新、改地址并可恢复默认、复制链接，右上角全部更新），参考版的图标集、规则提供者、脚本和 MITM 模块不迁入；文件机制见 [libbox.md](libbox.md#地理数据) 与 [app.md](app.md#地理数据资源)。高级配置（含 sing-box 规则集）仍从配置的“编辑”页进入，不在资源页重复。
- 抓包列表为合并请求页的“已抓取”视图；列表沿用连接视图的卡片、左右边距和行间距，支持搜索和分批加载。详情使用同一点击复制行及反馈，长 URL、头部与 Cookie 分行显示；右上角菜单切换请求、响应和 Cookies，请求与响应 Cookie 分组展示，保留重复 `Set-Cookie`、空值、值中的等号及属性，点击 Cookie 复制该条原文。抓取开关等待原生确认，停服时保留历史记录；远程会话显示不可用。
- 抓包设置使用共享分组表单，提供 HTTPS/MITM、证书管理、域名包含/排除、应用、原始目标端口、正文限额、可见域名提取及可选 QUIC TCP 回退。保存检查本机修订，失败保留草稿，运行中不能改设置。证书入口也在请求菜单中；生成 CA 是明确操作，重新生成需要确认，打开页面不生成、不安装、不启用抓包。证书页显示有效期、公钥 SHA-256、用户/系统信任及私钥可用性，提供 CRT/系统模块导出和 Android 设置入口；返回前台可刷新信任，远程会话不读本机证书，迟到结果不覆盖新会话。平台边界见 [app.md](app.md#请求抓取与-mitm)。
- 请求菜单提供抓包设置、MITM 证书、“抓包日志”、当前搜索的 HAR 导出和清空请求。日志页集中展示当前后端、实际 TLS 尝试/失败、解密连接/正文字节、QUIC 回退和解析/筛选/保存计数，支持搜索、复制、导出与独立清空。启动或握手失败可在日志中查看，不把开启开关当作收到 HTTPS 请求；日志来源及限额见 [libbox.md](libbox.md#httphttps-请求调试)。
- 请求详情保留重复头部、Cookie、正文状态及 trailers，显示原路由/应用、真实 SNI、双方 TLS 版本、ALPN 和上游证书 SHA-256，字段支持复制。进程 PID、socket FD/inode 和抓包 ID 不再记录或显示。域名观察与握手失败标为“仅域名”，不能导出 cURL；请求正文/头部不完整、截断或含 trailers 也不能导出 cURL。页面复用现有共享组件，没有修改 FlClash 直接导入文件。
- 常规服务页使用即时应用的开关，保存和重启期间显示进度并禁用开关，底部没有“应用服务设置”按钮；启动确认及失败恢复见 [app.md](app.md#配置服务应用与备份)。
- 日志页默认选择“跟随配置”，显示内核上报的日志级别及更严重的记录；菜单同时显示当前级别，仍可手动选择“全部”或单一级别，手动筛选不会被后续状态更新覆盖。搜索、暂停和导出沿用同一筛选结果；默认级别尚未收到时保留已接收记录。日志来源及本地重载同步见 [app.md](app.md#配置服务应用与备份)。
- 应用始终跟随系统语言并响应运行时变化。旧设置及备份中的语言选项丢弃，其他偏好保留。
- 代理列表保留圆角吸顶分组标题、固定高度的懒加载行、折叠及填充/普通/隐藏分组图标。折叠状态按选中配置保存，定位节点通过行偏移实现，不要求节点预先进入视口。
- 访问控制按上游 SFA 的列表/扫描分离方式加载，进入页面直接显示应用与已保存的托管选择；“重新扫描托管应用”为独立操作，扫描期间保留列表。应用图标只为滚动到视口附近（上下各约 600 逻辑像素）的行请求（行的 `image-key`，`rows.slint` 发出 `row-image`），同时最多 3 个请求、最新的请求先处理，等待队列最多 48 个（快速滑过的旧请求丢弃，回到视口附近时重新请求），解码后的图标最多保留 48 个（48 dp，3x 时约 80 KiB/个，按最近请求淘汰；视口加上下预取约 30～36 行），访问控制页和应用选择页关闭或刷新时全部释放，关闭时原生端的包查询快照（`releaseApps`）一并释放。列表行为懒加载行（`SettingRow.lazy`，`rows.slint` 的 `RowView`）：只有视口上下各约 600 逻辑像素内的行实例化文字与图标，其余行是空占位。懒加载行固定同高（上下各 8 px 内边距，48 dp 图标与一行名称加一行包名中较高者，随字体缩放，至少为 M3 双行列表项的 72 dp），名称和包名各一行、超出省略；位置、是否实例化和是否请求图标只取决于行号与滚动偏移，不回读列表布局给出的位置，因此数百个应用只保留几十行的文本排版和图像。此前行高按实测回写共享值、图标请求读行的布局位置，快速拖动且图标回复迟到时构成依赖环，Slint 报 “Recursion detected” 并 panic，android-activity 随之关闭 Activity；`shot::tests::access_control_survives_rapid_scrolling` 以快速拖动、惯性滚动和迟到的图标回复回归此问题。原生端按 48 dp（最大 144 px）绘制图标并在写入 PNG 后立即 `recycle()` 位图。离开访问控制页或应用选择页时立即执行与进入后台相同的释放（`src/memory.rs`）：丢弃行模型与全部图标；下一帧前清空渲染器的图像/路径/文本缓存，对 Skia GPU 上下文调用 `free_gpu_resources()` 并清空 Skia 字体与资源缓存（补丁见 `vendor/i-slint-renderer-skia/FKBOX-PATCH.md`）；Kotlin 端释放包查询快照后执行 GC（标签与图标加载的各应用 Resources/APK 资源表映射和位图像素随之释放）；随后原生堆 `mallopt(M_PURGE_ALL/M_PURGE)` 归还空闲页。原先每次构建列表都为全部已安装应用请求图标：原生端并发绘制、编码数百个图标，Rust 端全部解码为 RGBA 并在进程生命周期内保留，打开页面时内存骤增且离开后不回落。原生查询与托管更新约定见 [app.md](app.md#配置服务应用与备份)。
- 首页编辑保留卡片的全局 key，防止重复出口请求及丢失状态。改动立即保存；保存失败保留草稿，点击完成再次尝试。
- sing-box 专属 Tailscale、Taildrop、终端、认证、USB/IP、诊断和报告使用统一的 Slint 行页面（终端表面、OpenConnect 登录为原生 Activity），复用原生后端；能力边界见 [app.md](app.md)。

`src/app.rs` 把桥接事件写入 `Data`，按应用状态、外观、流量、时钟、配置、代理、记录等标记（`pages::Mark`）合并到下一轮事件循环，只投影受影响的页面；时钟、流量和连接/日志记录这类每秒的标记合并为约每秒一次投影（`pages::mark` 的冷却），其他标记（点按、开关页面、状态变化）立即投影并带上等待中的标记。列表行按位置原地更新（`rows::sync_model`，页面行经 `rows::sync_rows` 按行键沿用内容相同的标签/链路模型，并在第一处差异按键对齐删除或插入，日志环形丢弃最早行或新连接排到顶部时不重设后面的每一行），内容未变的页面、行和筛选标签模型不重新设置；只含每秒标记的投影只重建屏幕上的页面（手机为顶层路由，宽屏为全部路由；请求、日志首页仅在记录到达时），被覆盖的页面由打开、关闭或切换页面时的完整投影刷新。出口查询监听应用状态变化，不因流量或内存采样重新计算路由标识。显式批量通知仍刷新全部依赖。

可见性订阅（`src/pages/mod.rs` 的 `sync_visibility`）按页面可见状态和应用生命周期合并内存/日志订阅；卡片与详情页共享一个原生采样请求，最后一个可见消费者退出时停止。日志增量保留既有记录对象，暂停快照独立于后续追加和淘汰，恢复可见时重新同步原生缓冲。原生采样与消息边界见 [app.md](app.md#配置服务应用与备份)。

抓包轮询（`src/pages/requests.rs` 的 `poll`）仅在本地请求列表/详情或抓包日志页活动且应用前台时每两秒查询；调用不重叠，筛选变化合并刷新，并忽略旧筛选或会话的迟到结果。日志使用独立的 `capture.logs` 查询，请求列表不拉取日志历史。隐藏页面停止轮询，抓取引擎与日志记录继续运行至用户或服务停止。

正文预览（`src/pages/requests.rs`）放在等宽、自动折行、有界滚动的卡片中，展示内容类型、保存/总字节数及 Base64、截断、未完成状态。完整 JSON 可切换格式化与原文；格式化仅调整 JSON 字符串外的空白，保留数值字面量、重复键及转义，无法解析或超过格式化限额时显示原文。长预览分批展开，边界不拆开 UTF-16 代理对；复制始终使用内核返回的原始预览，不受当前格式或已展开长度影响，也不代表未保存的完整正文。

- 后台：Kotlin 侧 `BoxBridge.listen` 跟随 `AppLifecycleObserver.isForeground`，进入后台时向界面发 `foreground: false`，回到前台发 `foreground: true`；libbox 命令流由 `BoxStreams` 按 mobile-ffi `sessions.rs` 的计划连断并原样转发，服务与远程会话状态以 `hostState` 发送。Rust 侧 `pages::set_foreground` 在后台停止投影（数据仍写入 `Data`，标记留到回前台）、停掉 1 秒时钟和请求页 2 秒轮询（`pages::pausable`），把内存、内网、日志、连接、连接统计、流量订阅和服务状态卡全部置为不可见，出口查询的新请求等待前台；回到前台全部投影一次、恢复订阅，轮询立即取一次，服务状态不复测。内核每秒状态（速度、累计流量、曲线、远程内存）经 `setTrafficVisible` 只在仪表盘显示（手机未被路由覆盖）或内存页打开时订阅（`BoxStreams` 的状态客户端，模式客户端仍随前台连接）；重新订阅时内核立即回报当前值，曲线的 30 次采样窗口清空重来，不把间隔前后的采样连成一条线。连接事件随内核批次直接转发，日志增量由界面至少间隔一秒取一次（`app.rs` 的 `LOGS_INTERVAL_MILLIS`）；仪表盘、代理组、连接与日志的状态由 mobile-ffi `runtime.rs` 在 Rust 侧保存，后台不丢失。系统栏边距：Android 宿主（`BoxActivity.pushInsets`）发送窗口自己的系统栏与刘海边距（物理像素），界面按窗口当前缩放换算（`ui/app.slint` 用 `1phx`，`Nav.host-*-px`），缩放变化时自动重算，重复发送只替换不累加；宿主在 `onResume`、获得焦点、配置/多窗口变化和界面重新连接时都会重发。此前边距在收到时按当时的缩放换算一次：后台返回后若窗口（新表面或系统重建的 Activity）尚未取得屏幕密度，缩放仍为 1，边距被放大约 3 倍（顶部大片空白、内容和底栏下移），之后缩放更新也不再重算。核心服务、通知和既有的 Kotlin 前台门控（`pollWhileVisible`、`sessions.rs` 的前台条件）不受影响；界面绘制由 Slint Android 后端随窗口表面销毁停止。

### 配置页与内核差异

配置列表按参考版布局使用两行卡片、固定行高、选中徽标及最多三列的自适应网格。普通配置页首次显示时定位已选配置，切换配置使用弹层。添加按钮和空状态按钮均打开菜单，新建入口标为“新建配置”；排序使用分组背景和拖动手柄。长按菜单依次提供编辑、更新（远程配置）、复制链接和二维码（远程配置）、分享、导出及删除，删除标为危险操作。编辑页显示配置摘要，提供元数据分组表单及原始/高级配置入口。

服务启动中或停止中，配置列表和已打开的切换弹层禁用配置选择，首页切换配置按钮同步禁用；服务进入运行或停止状态后恢复。原生状态复核见 [app.md](app.md#配置服务应用与备份)。

| 参考版能力 | B-box 的适配边界 |
| --- | --- |
| 链接、文件、二维码导入 | 使用 sing-box JSON / 分享链接，保留 `.bpf`、动态 QRS 和剪贴板入口；不转换 Clash YAML |
| 自定义配置、提供者与覆写 | “新建配置”创建本地 sing-box JSON；通过已有原始/高级配置编辑器修改，不引入 Mihomo 配置生成、提供者或覆写脚本 |
| 配置预览与文件编辑 | 从编辑页的“打开配置内容”入口读取保存的 JSON，不将其称为运行时配置 |
| 自动更新 | 保留 Android 后端至少 15 分钟的限制；关闭后隐藏间隔输入，无效草稿不阻止关闭，保留原有效间隔及导入元数据；批量更新按原生互斥写入流程串行执行 |
| 流量用量与到期徽标 | 当前元数据没有订阅额度和到期字段时，卡片显示真实本地/远程类型，不用会话流量代替额度；这是当前元数据能力的差异，不表示 sing-box 无法支持订阅信息展示 |
| 上次更新时间 | 首页和配置列表均显示客户端存储中的时间戳，采用参考版紧凑相对时间并提供完整时间提示；本地配置显示创建/内容保存时间，远程配置显示订阅更新时间。没有历史记录时显示未知，时间记录和失败恢复见 [app.md](app.md#配置服务应用与备份) |
| 分享与导出 | 复制链接得到原始订阅 URL，分享/二维码保留 sing-box 导入链接和名称，导出仍为 JSON |

编辑保留 B-box 已有的本地/远程类型切换；不会因视觉对齐加入参考版的 age 密钥等未接入能力。保存、修订冲突、配置应用及失败恢复继续由既有桥接处理，见 [app.md](app.md#配置服务应用与备份)。

原始 JSON 编辑器（`src/pages/profiles.rs`、`ui/pages/profiles.slint` 的代码编辑页）显示从 1 开始的逻辑行号，空行和末尾换行保留编号；长行折行不增加行号，行号随正文滚动。行号布局与正文使用相同的字形、字号比例和换行宽度，仅创建可见编号；搜索、格式化、撤销及修订校验沿用原编辑流程。

高级配置新增“HTTP 改写”，由 `src/pages/config.rs` 的 HTTP 改写页复用共享分组、规则列表、排序及 JSON 表单，编辑配置启用状态、MITM 范围、Header/URL/Body Rewrite、Map Remote、Map Local/API Mock、JS 和内嵌资源。新建规则提供禁用的示例，用户修改匹配条件与内容后启用；完整 JSON 仍可直接编辑。字段及执行语义统一见 [libbox.md](libbox.md#json-配置中的-http-改写)。

所有修改继续经 `src/pages/config.rs` 的 `set` 写回，保留其他 JSON 字段、内容修订和原生校验/失败恢复；关闭失败表单前保留草稿。页面中的证书入口打开既有本机证书页，不把 CA 写入配置。请求页和诊断页单独显示 `rewriteActive`，说明停记录后规则仍生效；映射请求详情把连接出站/规则标为原连接，避免与映射目标的新路由混淆。证书页在配置处理运行时禁用重新生成。

### 状态卡片与出口查询

查询来源、字段解析及质量判定参考上述固定版本。IP 竞速由 [ui_egress.go](../apps/android/core/ui_egress.go) 负责：Cloudflare、IP.SB、IPinfo、ipwho.is、GeoJS、country.is、IPQuery、ident.me 共八源，B-box 的完整解析与失败回退、限额及传输约束见 [libbox.md](libbox.md#出口-ip-和质量查询传输)。

质量查询在 `apps/android/native/src/pages/egress.rs` 中依次分批使用 ident.me/ip-api.com/IPQuery、IPLocate、ipapi.is、proxycheck.io；等待一秒或当前请求全部失败后进入后续批次，单请求最多八秒。保留参考版组织/ASN 解析、显式类型优先、住宅类型推断及 Tor/abuser 质量规则。

质量缓存将成功结果按 IP 持久缓存三天（`readQualityCache`/`writeQualityCache`），失败仅在内存冷却五分钟，手动重试可跳过失败缓存。先显示 IP，再补充质量；质量失败保留 IP，详情展示来源和错误；质量检查无法开始或全部失败时停止加载状态并显示失败。

刷新与会话约定：

- 停止的本地服务通过设备当前网络查询，即使没有配置也可查询和手动重试；运行中通过 sing-box 路由查询。启停时取消旧探测，等待稳定服务状态再查询。远程会话保留占位，不展示本机出口。
- 返回前台保留未变路由的成功 IP 和质量；IP 查询失败才自动重试，间隔至少五秒，手动刷新可跳过。质量缓存到期只刷新质量。
- 短暂失焦不取消请求；已经发出的请求允许在后台完成，新查询等待前台（`egress::resume`）。UI 在后台断开连接时保留本地分组快照，用于返回时比较。
- 启停、运行中的配置/模式/节点及网络变化取消旧请求、忽略迟到结果，并合并 300 毫秒内的变化后查询新出口，不受旧路由冷却限制。分组排序、延迟更新和停服时选择配置不触发无关的出口失效。

### 内网 IP 与备份展示

内网 IP 卡片提供开关、端口副标题、Wi-Fi 图标及复制按钮（Material 图标按钮）。共享开关修改选中配置的 mixed/http/socks listen；无代理入站时先显示端口表单，明确新增 mixed 入站。运行时复制已成功启动配置的端点，停服/远程会话复制设备 IP，详情保留多入站/多端口。配置变换和保存约束分别见 [libbox.md](libbox.md) 与 [app.md](app.md)。

备份页沿用参考版的本地/远程布局、文件名、范围和兼容/覆盖策略，实际格式是 B-box `bbox-1`。覆盖会在页面确认后移除备份中不存在的配置；取消选择、写入失败或上传未确认时不显示成功。事务、文件恢复和传输限额由 Android/libbox 实现，见 [app.md](app.md)。

## 验证与升级参考版本

当前界面检查为 [verify_newui.py](../scripts/verify_newui.py) 的 Slint 界面源码约束、[build-apk.yml](../.github/workflows/build-apk.yml) 的 Rust 单元测试（`cargo test --workspace --lib`）（由 [newui.yml](../.github/workflows/newui.yml) 和 [build-debug-apk.yml](../.github/workflows/build-debug-apk.yml) 运行），以及 [newui-android.yml](../.github/workflows/newui-android.yml) 的 JVM 测试与模拟器 UI smoke，见 [app.md](app.md#验证与历史记录)。Slint 页面可用 `ui-slint` 的 `shot` 特性在主机上按手机尺寸渲染截图（`examples/shot.rs`，软件渲染器）；`shot-skia` 特性加 `SHOT_RENDERER=skia` 改用 Android 所用的 Skia 渲染器（CPU 光栅化），阴影与抗锯齿与设备一致。CI 不做像素比较；Material Design 3 重写（`ui/m3-rewrite`）只在主机截图中检查过，尚未在实机上做视觉验收；去掉背景模糊后的滚动帧率、水波纹、列表增删动画与底部表单拖动的手感同样未在设备上验证。删除 `flutter/` 前的 Flutter 测试与截图记录可在 Git 历史中查看，不代表当前源码已通过同等测试。

升级布局参考版本时核对页面布局、信息组织与动效时序，不迁入控件、字形或特效；同步审查来源清单和许可，按实际 Actions 运行记录结果。
