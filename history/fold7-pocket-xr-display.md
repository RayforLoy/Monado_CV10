# Fold7 原版 Pocket XR 外显和启动器

2026-11-27 01:26 香港时间。Fold7 SM-F9660、C9.53.6 原厂线、外接 5 V、三星外屏模式 **已镜像**。CV10 外显 3200×1600、90 Hz。原版 Pocket XR 1.6.28 的 `base.apk` SHA-256 为 `81cf6b61d9f80a20e1809275a72f0dc0008180aa8d8946aa7b830e78e1de4337`。

用于这次光学诊断的 ARM64 APK 从实验分支 `9c5459cbff2cc9ca48961c49e0761f9c7293fe30` 加一处诊断入口构建，SHA-256 为 `b1347c385f61dd17247f5c67639666c2714425535921fb4a97ec9b517b4e77c8`。该入口在 OpenXR 捕获 Surface 上创建 3840×1080、60 Hz 的临时公共虚拟屏，使用 Android `OWN_CONTENT_ONLY` 避免镜像手机屏幕。由已授权的 ADB 在该虚拟屏启动原版 `SpaceActivity` 并请求内置眼别测试卡。诊断和原版应用均未新增 Shizuku 授权。正式实验入口使用 Shizuku UserService 启动原版应用，实验 APK SHA-256 为 `428814856a9e58cd8f4c7da5555a7ec446bba4e11b300e7420ae135a6958f474`。[Android DisplayManager](https://developer.android.com/reference/android/hardware/display/DisplayManager#VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)

虚拟屏 94 的首帧由 Monado 的预览器接收，OpenXR 合成器在 CV10 显示 91 上输出了各自的 `LEFT EYE` 和 `RIGHT EYE`。CV10 两眼面板方向相反，SurfaceFlinger 的物理外屏截图保留面板方向。固定朝向右镜的摄像头拍到正立的 `RIGHT EYE`。[外屏双眼原图](evidence/fold7-pocket-xr-test-card-display.png) · [右镜实拍](evidence/fold7-pocket-xr-test-card-right.jpg)

后续使用标准启动器预览包，在 2D 列表关闭 **仅显示 XR 应用**、搜索 Pocket XR 并点选。系统前台窗口为原版 `com.hayeco.flipspaces.TrackpadActivity`，手机显示触控板。该路径未新增 Shizuku 授权。

## 原版启动器和应用点选

01:49，使用相同诊断 APK 启动原版 `SpaceActivity`，并在 Intent 设置 `test_card=off`。在手机的 **空间窗口** 打开启动器，再将 Monado 预览窗口切回前台以恢复 CV10 输出。Pocket XR 默认把这个 3840×1080 屏当作超宽画布。按原版启动器的 **深度（3D 左右并排）** 后，场景报告双眼各 1920×1080，虚拟眼距为 63 mm。

以该模式重进，物理外屏两眼区都有各自的启动器画面，右镜拍到正立的菜单、工具按钮和应用图标。用虚拟屏的键盘输入搜索并选择 **Hello XR (OpenGL ES)**，原版关闭菜单并为 `org.khronos.openxr.hello_xr.opengles` 显示双目占位窗口。窗口提示 Pocket XR 的 Shizuku 权限未授予，与日志的 `NO_PERMISSION`、实际面板帧复制计数为 0 一致。[双目菜单](evidence/fold7-pocket-xr-launcher-display.png) · [右镜实拍](evidence/fold7-pocket-xr-launcher-right.jpg) · [点选后的双目占位窗口](evidence/fold7-pocket-xr-launcher-selection.png) · [筛选日志](evidence/fold7-pocket-xr-launcher.log)

手机端一键启动外显要使用预览包自己的 Shizuku 授权，Pocket XR 创建空间应用面板还需要它自己的授权。真实应用面板、实时头部跟踪和左镜实拍待测。独立的电脑屏幕 3D 投屏见 [Half SBS 串流实测](fold7-pocket-pc-sbs.md)。

## 整合预览复测

2026-11-27 02:40 香港时间，同一台 Fold7 和接线。ARM64 整合预览来自 `6e536a4`，包含内置 2D、3D 启动器及 Pocket XR 外显和头踪实验。正式预览包 SHA-256 为 `94fc866acb33f0def30ec4d3f03ffd11a09e918e44b30604635ea850c110f21c`。签名与 16 KiB ZIP 对齐检查通过，五个 ARM64 库的 15 个 `PT_LOAD` 段均对齐到 16 KiB。本机页大小为 4 KiB。内置 3D 菜单进入 `XR_SESSION_STATE_FOCUSED`，右镜拍到应用卡片和手机指针。[内置 3D 菜单右镜实拍](evidence/fold7-pocket-integrated-menu-right.jpg)

光学诊断包来自同一提交加 [诊断补丁](pocket-display-entry-diagnostic.patch)，SHA-256 为 `faa80bd4e7e59e4c7bff85f39c02f23d1d675d047015a23a73a8b0d62794c169`。在同一源码上应用补丁，使用与正式预览相同的 Gradle 构建选项，选择 **显示左右眼测试卡**。这次先记录到系统 Broker 查询失败，随后使用预览包附带的 Monado 运行时进入 `FOCUSED`。诊断创建公共自有内容虚拟屏 103，由已授权的 ADB 在该屏启动原版 `com.hayeco.flipspaces/.spatial.SpaceActivity`，设置 `test_card=eyes`，随后收到首帧。CV10 的两个眼区显示各自的眼别标记，右镜拍到正立的 `RIGHT EYE`。左镜仍待拍摄。[双眼外屏截图](evidence/fold7-pocket-integrated-card-display.png) · [右镜实拍](evidence/fold7-pocket-integrated-card-right.jpg) · [筛选日志](evidence/fold7-pocket-integrated-entry-policy.log)

预览包调用 Android 的 `isActivityStartAllowedOnDisplay` 检查了对该虚拟屏的启动权限。三项入口均返回 `false`。

| 原版入口 | 清单与 Fold7 实测 |
| --- | --- |
| `.spatial.SpaceActivity` | 已导出，要求 `android.permission.INTERACT_ACROSS_USERS_FULL`，普通预览包没有这项权限 |
| `.MainActivity` | 已导出，无组件权限要求。可从手机端启动，系统拒绝在诊断虚拟屏启动 |
| `.TrackpadActivity` | 未导出，由 Pocket XR 在手机端自行打开 |

02:44，在同一诊断包重开原版空间和双目菜单。原版报告左右各 1920×1080，右镜拍到菜单。用原版菜单选择 `hello_xr` 后，双眼显示提示需要授权的占位窗口。Shizuku 服务在运行，Pocket XR 报告 `NO_PERMISSION`，实际应用面板帧复制计数为 0。[原版菜单右镜实拍](evidence/fold7-pocket-integrated-stock-menu-right.jpg) · [点选结果](evidence/fold7-pocket-integrated-selection.png) · [筛选日志](evidence/fold7-pocket-integrated-selection.log)

手机端一键打开原版外显走预览包自己的 Shizuku 服务。在原版空间中投射实际应用面板还需要 Pocket XR 自己的 Shizuku 授权。上述复测沿用已有 ADB 授权。[原版清单](https://github.com/pocket-xr/pocket-xr/blob/v1.6.28/app/src/main/AndroidManifest.xml) · [Android 显示启动权限检查](https://developer.android.com/reference/android/app/ActivityManager#isActivityStartAllowedOnDisplay(android.content.Context,%20int,%20android.content.Intent))

原版 `SpaceRenderer` 的普通应用面板可以按眼裁分左右并排纹理，内置 PC Screen 的实时解码支路则把整帧送给每只眼。由此可知，兼容层搬运双目画布本身不会把 PC Screen 的普通输入变成左右眼不同的画面。普通面板的 3D 视频和 PC Screen 串流需分别验收。[Pocket XR 1.6.28 面板绘制](https://github.com/pocket-xr/pocket-xr/blob/v1.6.28/app/src/main/kotlin/com/hayeco/flipspaces/spatial/SpaceRenderer.kt#L4659-L4693)

## 切到原版手机控制时保留眼镜画面

02:50，用 `6e536a4` 光学诊断包复现。Fold7 主屏切到原版 Pocket XR，OpenXR 会话从 `FOCUSED` 经 `VISIBLE`、`SYNCHRONIZED`、`STOPPING` 退到 `IDLE`。此时 CV10 回到三星镜像画面。切回 Monado 后会话重新进入 `FOCUSED`。

`eaf4ea4` 为 Pocket XR 外显增加 **打开手机控制**。该入口启动原版手机界面，并在这次切换期间使用前台服务保留 CV10 会话。回到预览应用后退出这一模式。停止预览会释放本次虚拟显示、OpenXR 会话和前台服务。机制仅用于预览包内的 Pocket XR 外显活动。正式 ARM64 预览 APK 的 SHA-256 为 `899b45200c4312a38f7c7400f83b616f1efd8ecaa63d19e48ff6180903981e77`。APK v2 签名、16 KiB ZIP 对齐和五个 ARM64 库的 15 个 `PT_LOAD` 段检查通过。

02:55 至 02:57，在同一 Fold7、镜像设置、53 版原厂线和 5 V 供电下，用 `eaf4ea4` 加 [控制切换诊断补丁](pocket-phone-controls-diagnostic.patch) 构建的 APK 复测，SHA-256 为 `cfaa0ef7d3fce9542879390a4b868885c9bc029b8e32041f74a07710d4593e11`。补丁仅为测试改用由已授权 ADB 启动的公共虚拟屏，并允许在测试卡模式点击同一个手机控制按钮。

- 点击 **打开手机控制** 后，原版 `TrackpadActivity` 成为手机前台，原版 `SpaceActivity` 保持虚拟屏前台。十多秒后右镜仍显示正立的 `RIGHT EYE`。这期间 OpenXR 会话保持 `FOCUSED`。[触控板前台时的右镜实拍](evidence/fold7-pocket-phone-controls-card-right.jpg)
- 第二轮打开原版 **空间窗口 → 打开启动器**。手机仍停在原版 `SpatialActivity` 时，眼镜两眼区同步显示新打开的菜单，右镜拍到正立菜单。返回预览并选择 **停止预览** 后，ADB 查不到本次虚拟屏和前台服务。通知停止入口和锁屏行为待验收。[外屏双眼截图](evidence/fold7-pocket-phone-controls-menu-display.png) · [右镜实拍](evidence/fold7-pocket-phone-controls-menu-right.jpg) · [筛选日志](evidence/fold7-pocket-phone-controls.log)

本轮结束后 APK 恢复为 `eaf4ea4` 正式预览，Broker 恢复选择稳定 Monado CV10。正式预览的 Shizuku 一键启动仍待授权后实测。

## 原版应用从手机侧发现虚拟屏

2026-11-27 03:43 至 03:45（香港时间），Fold7 SM-F9660 沿用 C9.53.6 线材、外接 5 V 和三星 **已镜像**。原版 Pocket XR 仍为本文开头记录的 APK。整合预览 `4e85e3e` 加 [识别对照补丁](pocket-stock-display-discovery-diagnostic.patch) 构建的诊断 APK，SHA-256 为 `b2bb2a7a230b895a33419703deba5f880aae1d1477c2fa1ec84467c59ce05097`。APK v2 签名和 16 KiB ZIP 对齐通过。两轮都使用 3840×1080、60 Hz 的公共自有内容 Presentation 虚拟屏，未用 ADB 将原版 Activity 直接放到虚拟屏。

- 普通诊断名下，原版的自动检测选中真实 `HUAWEIAV03`（显示 91），并报告 Samsung 当前只能镜像。加入 `XREAL` 关键词以触发原版显示排序后，原版选中诊断虚拟屏 115，手机上显示该屏名称并标为虚拟显示屏。两轮均报告 Shizuku `NO_PERMISSION`。[原版显示选择](https://github.com/pocket-xr/pocket-xr/blob/v1.6.28/core/src/main/kotlin/com/hayeco/flipspaces/core/DisplaySelection.kt)
- 从原版手机设置点按 **在眼镜上显示**，原版选择 `FLAT` 并尝试自行打开 `.GlassesHomeActivity`。以全屏边界启动和只指定显示 ID 两种尝试均被 Android 拒绝。原版回退到自身的基础 Presentation，Monado 收到首帧，CV10 外屏两眼区显示了同一平面提示的不同片段。此对照验证了发现、选屏和基础画面输出，原版桌面和空间应用面板仍需单独验收。[系统拒绝与原版回退日志](evidence/fold7-pocket-xr-stock-discovery.txt) · [CV10 外屏原图](evidence/fold7-pocket-xr-stock-discovery-display.png) · [Android 显示启动策略](https://source.android.com/docs/core/display/multi_display/activity-launch)

诊断名称和按钮改动仅用于此对照。测试后恢复完整整合预览 APK 和稳定 Broker，没有增加原版或预览的 Shizuku 授权。

## Android 16 模拟器原版应用面板

2026-11-27 06:50 至 07:01（香港时间），在隔离的 Android 16 Google APIs x86_64 模拟器（ARM 转译、4 KiB 页）中，安装本文开头对应的原版 Pocket XR 1.6.28，以及官方 Shizuku 13.6.0。Shizuku 以 `shell` 身份运行，并仅在该模拟器向原版 Pocket XR 授权。关闭模拟器的 Wi-Fi 和移动数据后，用 ADB 把原版 `SpaceActivity` 打开在 Android 的 3840×1080、60 Hz 模拟外屏，原版切到 **3D** 后每眼为 1920×1080，虚拟眼距 63 mm。[Shizuku 13.6.0](https://github.com/RikkaApps/Shizuku/releases/tag/v13.6.0)

两轮都通过 Pocket XR 的原版菜单搜索并打开系统应用。用 SwiftShader 时，**Clock** 和 **Settings** 各自进入由 Pocket XR 建立的 1786×976 虚拟屏，直接抓取这些屏幕可见应用已正常更新，双目输出却仍显示各自的启动图标。换成 ANGLE（`-gpu swangle`）后，从原版菜单重新打开 **Clock**，在时钟所在虚拟屏触发秒表，双目输出先后显示 6.78 秒和 10.20 秒，日志也记录到连续复制新帧。在原版手机界面关闭面板后，`Pocket XR: Clock` 从系统显示列表消失。[06.78 秒双目原图](evidence/android16-pocket-app-panel-sbs-1.png) · [10.20 秒双目原图](evidence/android16-pocket-app-panel-sbs-2.png) · [两种图形后端日志与截图摘要](evidence/android16-pocket-app-panel.txt)

该轮验证了原版空间内从菜单启动应用、实时渲染双目面板和关闭清理。秒表按键用 ADB 触发，Fold7、Monado 外显转接及 CV10 镜片未参与本轮。
