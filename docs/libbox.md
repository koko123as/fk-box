# libbox（已退役）

阶段 6（2026-11-24）起，应用不再使用三内核 libbox.aar（sing-box + Xray + mihomo 各自完整运行，外加 `apps/android/core/` 的 Go 扩展、`setup-core`/`build-libbox` 两个 Action 和 `scripts/patch_*.py` 补丁脚本）。内核改为单一 sing-box + Rust 大脑的 `fkhost.aar`，打包、数据位置与取舍见 [architecture/brain-android.md](architecture/brain-android.md)，宿主见 [architecture/brain-host.md](architecture/brain-host.md)。

本文旧内容（扩展列表、多内核适配、HTTP 抓取/改写、出口探测、DNS 计数、升级内核流程）随代码一起删除，需要时查看阶段 5 的分支 `arch/stage5-groups`（1d72da6）中的 `docs/libbox.md` 与 `apps/android/core/`。

## 仍保留的补丁

| 补丁 | 位置 | 原因 |
| --- | --- | --- |
| Android 自动重定向兼容 | [sing-tun-android-ingress.patch](../apps/android/core/sing-tun-android-ingress.patch)、[测试](../apps/android/core/sing-tun/redirect_ingress_discard_linux_test.go)、[patch_sing_tun.py](../scripts/patch_sing_tun.py) | sing-tun `redirect_ingress_discard_android.go:73` 校验 netd ingress discard BPF map 的 value 大小，只接受两个接口槽（8 字节）；单接口的 4 字节布局会让 auto-redirect 失败。补丁兼容 4/8 字节 value，保留接口归属检查和原值恢复。auto-redirect 需要 root，单内核路径暂未接入，所以 `fkhost.aar` 不打这个补丁；Core and source validation 在模块的临时副本上应用并运行测试，保证它对 fkhost 锁定的 sing-tun 版本仍然可用 |
