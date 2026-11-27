# Fold7 原版 Pocket XR 双目画面固定

`9ca6805` 增加 **使用 CV10 头部追踪固定 Pocket XR 画面**。开启后，Monado 在 OpenXR `LOCAL` 空间中提交原版左右眼画面的两个 Quad，首次显示时取 CV10 的当前朝向。使用 **将画面移到当前朝向** 可重新放到视野正前方。保持默认设置时，双目画面随视线转动。固定整个双目画面由 Monado 完成，Android 本机 VPN 选项用于把 IMU 交给 Pocket XR 自身，两项择一使用。独立电脑屏幕仍按原路径输出左右并排视频。[原版 Pocket XR 网络发现](https://github.com/pocket-xr/pocket-xr/blob/v1.6.28/app/src/main/kotlin/com/hayeco/flipspaces/spatial/GlassesNetwork.kt)

## 2026-11-27 04:03 至 04:04 香港时间

Galaxy Z Fold7（SM-F9660）、C9.53.6 原厂线和外接 5 V。三星外接显示为 **已镜像**，CV10 为 3200×1600 @ 90 Hz。原版 Pocket XR 1.6.28 的 APK SHA-256 为 `81cf6b61d9f80a20e1809275a72f0dc0008180aa8d8946aa7b830e78e1de4337`，测试中 APK 保持原样。Broker 选用 Monado CV10 Preview。沿用已有 ADB 权限启动原版 `SpaceActivity` 到诊断创建的 3840×1080 @ 60 Hz 虚拟显示屏，并由原版显示眼别测试卡。

眼镜始终固定。诊断驱动读取实时 USB IMU 并按正常路径融合，再在写入 OpenXR 姿态历史前叠加 0.17 rad 的已知偏航或俯仰。分别记录默认随视线和固定模式，两轮均进入 `FOCUSED`。下表是外显转正后，相对于各模式初始位置的像素位移。

| 模拟头动 | 随视线，左眼 / 右眼 | 固定画面，左眼 / 右眼 |
| --- | --- | --- |
| 绕本地 +Y 轴转动 | (0, 0) / (0, 0) | (+164, 0) / (+164, 0) |
| 绕本地 +X 轴转动 | (0, 0) / (0, 0) | (0, +164) / (0, +163) |
| 保持第二个姿态并点按置中 | — | (+1, 0) / (0, 0) |

右镜拍到与外显相同的位移和置中效果。点按 **停止预览** 后，Fold7 释放两轮各自创建的虚拟显示。测试后清除了诊断属性，Broker 恢复稳定 Monado CV10。实物转头、左镜光学实拍、原版应用自己接收 IMU 和 Shizuku 一键打开原版空间仍待验收。[右镜实拍对照](evidence/fold7-pocket-world-anchor-right.jpg) · [右眼外显截图对照](evidence/fold7-pocket-world-anchor-display.jpg) · [双眼像素坐标](evidence/fold7-pocket-world-anchor-pixels.csv) · [设备日志](evidence/fold7-pocket-world-anchor.txt)

## 构建与复现

| ARM64 APK | SHA-256 |
| --- | --- |
| `9ca6805` 普通预览 | `b1342958abb955c9a3a494455ebe99f75f8ec371ec93ec6966acac22a67f4a1e` |
| `9ca6805` 加诊断补丁 | `7831b4978b4a79473683795b3fd5a2399b66c1cd1da9b87cb40452fab59c2d35` |

两包均用 `-Pcv10LauncherPreview=true -Pcv10Abi=arm64-v8a` 构建，v2 签名、16 KiB ZIP 对齐及五个原生库的 15 个 ELF `PT_LOAD` 段通过检查。[诊断补丁](pocket-world-anchor-diagnostic.patch)仅用于复测。诊断中点 **显示左右眼测试卡** 后，用已授权的 ADB 在日志记录的显示 ID 上启动原版 `com.hayeco.flipspaces/.spatial.SpaceActivity`，传入 `--es test_card eyes`。诊断属性 `debug.cv10.anchor_test` 的 0、1、2 分别对应实时姿态、实时姿态加偏航、实时姿态加俯仰。测试结束后清空属性。

04:07 用普通预览确认选中固定模式时本机 VPN 选项保持关闭。首次回归遇到 Windows ADB 服务超时。无线 ADB 恢复后，用同一 APK 从 2D 启动器打开原版 `hello_xr`，会话进入 `FOCUSED`，右镜拍到蓝方块，按返回后进入 `STOPPING`。普通预览安装在 Fold7，APK 摘要一致，Broker 已恢复为稳定版。[普通预览右镜实拍](evidence/fold7-pocket-world-anchor-preview-hello-right.jpg)

## 原版 Pocket XR 恢复旧面板

2026-11-27 13:59–14:10 香港时间。同一 Fold7、C9.53.6 原厂线、5 V 供电和三星外屏镜像。原版 Pocket XR 1.6.28 与 Khronos OpenGLES `hello_xr` 均保留原 APK。沿用既有 Shizuku 授权，未开启本机 VPN 或注入诊断姿态。

在 Broker 选 **Monado CV10 Preview** 并从手机点 **打开 Pocket XR 空间**。旧版预览已跟踪 CV10 的实际 USB 姿态并显示双目画面。原版随后恢复旧的计算器、时钟和 `hello_xr` 普通面板。样例接入 Monado 直出并进入 `FOCUSED` 后，空间会话失去焦点，点按置中没有提交新的画面锚点。

`e8b1ef7` 让 Monado CV10 和 Preview 在建立连接前辨认 Pocket XR 的普通应用虚拟屏。样例收到 `XR_ERROR_RUNTIME_UNAVAILABLE`，空间恢复或保持 `FOCUSED`，两轮点按置中均成功提交 `recenter 1`。同轮外显帧有双目时钟面板，固定右镜相机仍未拍到面板。第一轮关闭空间后直接启动样例时，系统复用之前失败的进程，未进入 `FOCUSED`。第二轮结束该样例进程后，从 Monado 2D 列表点选，新的样例进程进入 `FOCUSED` 并完成 `xrDestroyInstance`。[实机筛选日志](evidence/fold7-pocket-panel-routing.log) · [双目外显和右镜对照](evidence/fold7-pocket-panel-routing.jpg)

保留原样例和同一预览包，Broker 改选 **Pocket XR (Monado CV10)**。从 **在 Pocket XR 中打开 OpenXR 应用** 选择样例，独立应用屏收到画面，样例进入 `FOCUSED`。关闭独立应用屏释放该屏并保留原空间。三星 Freecess 随后冻结样例，约十秒后因 `ActivityDestroyTimeout` 解冻，该轮关闭应用屏时未观察到 `xrDestroyInstance`。结束整个预览后，Broker 恢复为 **Monado CV10 Preview**，外加的虚拟屏全部释放。[实机筛选日志](evidence/fold7-pocket-panel-routing.log)

本轮的 ARM64 预览 APK SHA-256 为 `2bc74f3f0e887e26d1f359d7ec12de33a978f4aa9aadb8db8ce5fb0e9612a281`。APK 签名及 16 KiB ZIP 对齐检查通过，五个 ARM64 库的 15 个 ELF `PT_LOAD` 段均按 16 KiB 对齐。此轮验证了正式入口下的实时 IMU 与置中流程，佩戴转头、左镜实拍及 Fold7 上原版应用接收本机 VPN 姿态仍待验收。
