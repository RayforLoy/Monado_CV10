# Fold7 原版 Pocket XR 测试卡外显

2026-11-27 01:26 香港时间。Fold7 SM-F9660、C9.53.6 原厂线、外接 5 V、三星外屏模式 **已镜像**。CV10 外显 3200×1600、90 Hz。原版 Pocket XR 1.6.28 的 `base.apk` SHA-256 为 `81cf6b61d9f80a20e1809275a72f0dc0008180aa8d8946aa7b830e78e1de4337`。

用于这次光学诊断的 ARM64 APK 从实验分支 `9c5459cbff2cc9ca48961c49e0761f9c7293fe30` 加一处诊断入口构建，SHA-256 为 `b1347c385f61dd17247f5c67639666c2714425535921fb4a97ec9b517b4e77c8`。该入口在 OpenXR 捕获 Surface 上创建 3840×1080、60 Hz 的临时公共虚拟屏，使用 Android `OWN_CONTENT_ONLY` 避免镜像手机屏幕。由已授权的 ADB 在该虚拟屏启动原版 `SpaceActivity` 并请求内置眼别测试卡。诊断和原版应用均未新增 Shizuku 授权。正式实验入口使用 Shizuku UserService 启动原版应用，实验 APK SHA-256 为 `428814856a9e58cd8f4c7da5555a7ec446bba4e11b300e7420ae135a6958f474`。[Android DisplayManager](https://developer.android.com/reference/android/hardware/display/DisplayManager#VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)

虚拟屏 94 的首帧由 Monado 的预览器接收，OpenXR 合成器在 CV10 显示 91 上输出了各自的 `LEFT EYE` 和 `RIGHT EYE`。CV10 两眼面板方向相反，SurfaceFlinger 的物理外屏截图保留面板方向。固定朝向右镜的摄像头拍到正立的 `RIGHT EYE`。[外屏双眼原图](evidence/fold7-pocket-xr-test-card-display.png) · [右镜实拍](evidence/fold7-pocket-xr-test-card-right.jpg)

手机端一键启动需要预览包自己的 Shizuku 授权，Pocket XR 创建空间应用面板另需它自己的授权。左镜实拍、真实头部运动、应用入口、窗口与 3D 投屏待测。诊断退出后已移除临时虚拟屏，Broker 恢复稳定 Monado CV10，最低刷新率仍为 `null`。
