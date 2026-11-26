# Fold7 原版 Pocket XR 外显和启动器

2026-11-27 01:26 香港时间。Fold7 SM-F9660、C9.53.6 原厂线、外接 5 V、三星外屏模式 **已镜像**。CV10 外显 3200×1600、90 Hz。原版 Pocket XR 1.6.28 的 `base.apk` SHA-256 为 `81cf6b61d9f80a20e1809275a72f0dc0008180aa8d8946aa7b830e78e1de4337`。

用于这次光学诊断的 ARM64 APK 从实验分支 `9c5459cbff2cc9ca48961c49e0761f9c7293fe30` 加一处诊断入口构建，SHA-256 为 `b1347c385f61dd17247f5c67639666c2714425535921fb4a97ec9b517b4e77c8`。该入口在 OpenXR 捕获 Surface 上创建 3840×1080、60 Hz 的临时公共虚拟屏，使用 Android `OWN_CONTENT_ONLY` 避免镜像手机屏幕。由已授权的 ADB 在该虚拟屏启动原版 `SpaceActivity` 并请求内置眼别测试卡。诊断和原版应用均未新增 Shizuku 授权。正式实验入口使用 Shizuku UserService 启动原版应用，实验 APK SHA-256 为 `428814856a9e58cd8f4c7da5555a7ec446bba4e11b300e7420ae135a6958f474`。[Android DisplayManager](https://developer.android.com/reference/android/hardware/display/DisplayManager#VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)

虚拟屏 94 的首帧由 Monado 的预览器接收，OpenXR 合成器在 CV10 显示 91 上输出了各自的 `LEFT EYE` 和 `RIGHT EYE`。CV10 两眼面板方向相反，SurfaceFlinger 的物理外屏截图保留面板方向。固定朝向右镜的摄像头拍到正立的 `RIGHT EYE`。[外屏双眼原图](evidence/fold7-pocket-xr-test-card-display.png) · [右镜实拍](evidence/fold7-pocket-xr-test-card-right.jpg)

后续使用标准启动器预览包，在 2D 列表关闭 **仅显示 XR 应用**、搜索 Pocket XR 并点选。系统前台窗口为原版 `com.hayeco.flipspaces.TrackpadActivity`，手机显示触控板。该路径未新增 Shizuku 授权。

## 原版启动器和应用点选

01:49，使用相同诊断 APK 启动原版 `SpaceActivity`，并在 Intent 设置 `test_card=off`。在手机的 **空间窗口** 打开启动器，再将 Monado 预览窗口切回前台以恢复 CV10 输出。Pocket XR 默认把这个 3840×1080 屏当作超宽画布。按原版启动器的 **深度（3D 左右并排）** 后，场景报告双眼各 1920×1080，虚拟眼距为 63 mm。

以该模式重进，物理外屏两眼区都有各自的启动器画面，右镜拍到正立的菜单、工具按钮和应用图标。用虚拟屏的键盘输入搜索并选择 **Hello XR (OpenGL ES)**，原版关闭菜单并为 `org.khronos.openxr.hello_xr.opengles` 显示双目占位窗口。窗口提示 Pocket XR 的 Shizuku 权限未授予，与日志的 `NO_PERMISSION`、实际面板帧复制计数为 0 一致。[双目菜单](evidence/fold7-pocket-xr-launcher-display.png) · [右镜实拍](evidence/fold7-pocket-xr-launcher-right.jpg) · [点选后的双目占位窗口](evidence/fold7-pocket-xr-launcher-selection.png) · [筛选日志](evidence/fold7-pocket-xr-launcher.log)

手机端一键启动外显要使用预览包自己的 Shizuku 授权，Pocket XR 创建空间应用面板还需要它自己的授权。真实应用画面、3D 视频投屏、实时头部跟踪和左镜实拍待测。
