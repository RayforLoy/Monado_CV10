# Fold7 离线启动器验收

2026-11-27 香港时间。Galaxy Z Fold7（SM-F9660）使用 C9.53.6 原厂线和外接 5 V，三星外接显示为 **已镜像**。CV10 `HUAWEIAV03` 的活动模式为 3200×1600、90 Hz。Broker 选中 `org.cv10.monado.out_of_process.launcher_preview`，预览包未获 Shizuku 授权。测试应用为原版 Khronos OpenGLES `hello_xr` 1.1.65，APK SHA-256 为 `29ae01ef6a4fea41d67816f7051698f2694ab08ced4c16c02bdd978e62975962`。

预览包从 `06d08e4468e79635438d075ff765abae07d35bb0` 构建，ARM64 APK SHA-256 为 `2869820f65faecc1c53b548a42729b5e1b404de3659591bae1b7795ea620b13b`，本机调试证书 SHA-256 为 `68365fa0904432aaa86a8904c691f9e6487414dc959786d72831a083b9f23fca`。五个 ARM64 ELF 的 15 个 `PT_LOAD` 段均为 `0x4000` 对齐。Build Tools 36.0.0 的 `zipalign -c -P 16 4` 与 `apksigner verify` 通过。实测 Fold7 页大小为 4096 字节，测试期间未出现兼容提示。

| 操作 | 设备结果 |
| --- | --- |
| 2D 列表点选 `hello_xr` | 01:18:38 进入 `FOCUSED`，PID 为 17535。外屏截图显示左右眼各有方块，右镜实拍可见蓝方块。01:18:53 按返回键进入 `STOPPING`，应用销毁实例。 |
| 3D 菜单点选同一个 `hello_xr` 进程 | 01:19:02 菜单 `FOCUSED`，右镜可见菜单和激光指针。底部上滑停留置中后点按，菜单销毁 OpenXR 实例。同一个 PID 17535 在 01:19:09 创建新会话、进入 `FOCUSED`，随后右镜蓝方块可见。两轮日志均记录运行时服务创建 CV10 的 Presentation，未复现之前的 `VK_ERROR_SURFACE_LOST_KHR` 或 `VK_ERROR_NATIVE_WINDOW_IN_USE_KHR`。[右镜菜单](evidence/fold7-launcher-menu-right.jpg) · [右镜样例](evidence/fold7-launcher-hello-right.jpg) · [双目外屏截图](evidence/fold7-launcher-hello-display.png) |
| 3D 菜单冷启动样例 | 样例干净退出并结束进程后，重新从 3D 菜单点选。01:19:43，新 PID 24663 进入 `FOCUSED`，外屏截图与右镜实拍均出图。01:20:00 按返回键正常退出。 |
| 左右返回和系统导航 | 从两侧系统手势区内侧各向内滑动一次，两次均回到 2D 启动器。Android 返回键也回到 2D 启动器。沉浸窗口隐藏系统导航条时，从屏幕最底部短滑唤出，再滑一次返回三星桌面。 |

俯仰方向修正已用合成姿态样本复测，左右方向仍符合预期。本轮使用固定右镜摄像头，未手动转动手机或眼镜。左镜光学、手机实物 pitch/yaw、关 Wi-Fi、16 KB 页设备和用户实际拔插需各自复测。无线 ADB 使用同一 Wi-Fi，本轮保留网络。复测后 Broker 恢复手机原有的 Monado CV10 v0.1.2，系统最低刷新率为 `null`，CV10 保持 3200×1600、90 Hz。

原版 Pocket XR 1.6.28 的内置测试卡随后经 ADB 诊断入口与 Monado 原生合成送到 CV10。手机端一键启动需要预览包自己的 Shizuku 授权，创建 Pocket XR 应用面板还需要 Pocket XR 自己的授权。[外显截图和镜片实拍](fold7-pocket-xr-display.md)
