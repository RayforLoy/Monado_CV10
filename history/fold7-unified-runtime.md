# Fold7 整合预发布验收

2026-11-27 香港时间。Galaxy Z Fold7（SM-F9660，Android 16）通过 C9.53.6 原厂手机线连接 CV10，使用外部 5 V 供电。三星选择 **已镜像**，HUAWEIAV03 活动模式为 3200×1600 @ 90 Hz。

- 构建源码 `89a6cc4434091559a5216589ee6ee1799e163cee`。发布标签在该源码上补充本文与 README。`Monado-CV10-0.1.4-rc.1.apk` 的 SHA-256 为 `99ace97c02f34845e2c9b4f70c6364c1d1d269f33c3195f0e0f23c9fc8f528d5`，证书 SHA-256 为 `68365fa0904432aaa86a8904c691f9e6487414dc959786d72831a083b9f23fca`。
- APK 同时包含 `arm64-v8a` 和 `armeabi-v7a`，验证通过 APK v2 签名、ZIP 对齐和六个 ARM64 原生库的 16 KiB `PT_LOAD` 对齐。Fold7 覆盖旧预览后保留已有 Shizuku 权限。32 位设备与实际 16 KiB 系统的本包运行仍待验证。
- 从 2D 列表直接启动原版 Khronos `hello_xr` OpenGLES，右镜拍到蓝块。打开 3D 菜单时右镜拍到方向正确的界面，底部上滑停留完成一次置中。随后用户用手机激光笔点选 `hello_xr`，佩戴确认双眼有蓝块。通过 ADB 按返回，样例退出并回到 2D 列表。
- 原版 Pocket XR 1.6.28 通过 Shizuku 接收 3840×1080 虚拟显示，右镜拍到正向 `RIGHT EYE`。停止预览后确认释放了 Monado 的虚拟显示。整合 APK 这轮覆盖测试卡，完整 Half SBS 串流的双眼记录来自此前的直连预览。[Half SBS 双眼实测](fold7-pocket-pc-sbs.md#fold7-usb-c-直连双眼佩戴验收)
- CF20、6DoF 套件和软件瞳距入口均在 Fold7 打开。CF20 和套件协议向量测试通过。实际配对、有效 6DoF 位姿和瞳距光学校准仍待验收。

| 时间 | 观察 |
| --- | --- |
| 17:09:34.832 | 2D 启动的 `hello_xr` 进入 `FOCUSED`，PID 16827。 |
| 17:09:39.177 | 样例 `xrDestroyInstance` 完成。 |
| 17:09:52.435 | 3D 菜单进入 `FOCUSED`。 |
| 17:09:57.032 | `Recentered 3D launcher (1)`。 |
| 17:12:34.451 | Android 记录 `Cv10KitActivity` 已显示。 |
| 17:12:56.902 | 原版 Pocket XR 虚拟显示收到首帧。 |
| 17:14:11.331 | 3D 菜单点选的 `hello_xr` 进入 `FOCUSED`，仍是 PID 16827。 |
| 17:14:41.928 | 样例 `xrDestroyInstance` 完成，回到 `Cv10LauncherActivity`。 |

[2D 启动右镜蓝块](fold7-unified-runtime/2d-hello-right.jpg) · [3D 菜单右镜](fold7-unified-runtime/3d-menu-right.jpg) · [原版 Pocket XR 右镜测试卡](fold7-unified-runtime/pocket-card-right.jpg)
