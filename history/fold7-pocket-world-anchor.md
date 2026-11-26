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
