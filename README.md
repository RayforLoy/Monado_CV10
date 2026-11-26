# Monado CV10

HUAWEI VR Glass CV10 的 Android OpenXR 运行时。包名为 `org.cv10.monado.out_of_process`。安装后在 Khronos OpenXR Runtime Broker 中选择 **Monado CV10**，连接眼镜并允许 USB 访问。

## 安装

从 [Releases](https://github.com/RayforLoy/Monado_CV10/releases) 下载与系统 ABI 匹配的 APK。用 `adb shell getprop ro.product.cpu.abilist` 查看支持的 ABI，用 `adb install -r <APK>` 安装。Android 11 为 API 30，项目最低为 API 26。覆盖更新需要相同签名。实际 VR 功能还取决于 USB 主机、外接显示器、Broker 和 Vulkan 驱动。历史 Fold7 镜片、3DoF 和刷新率复测见 [验收记录](history/README.md)。

| 系统 ABI | v0.1.3 APK |
| --- | --- |
| 64 位 ARM Android，含骁龙、天玑、麒麟的 64 位系统 | [arm64-v8a](https://github.com/RayforLoy/Monado_CV10/releases/download/v0.1.3/Monado-CV10-0.1.3-arm64-v8a.apk) |
| 32 位 ARM Android | [armeabi-v7a](https://github.com/RayforLoy/Monado_CV10/releases/download/v0.1.3/Monado-CV10-0.1.3-armeabi-v7a.apk) |

用 `Get-FileHash <APK> -Algorithm SHA256` 核对下载文件。v0.1.3 沿用 v0.1.2 的签名，Android 版本代码从 102 升至 103。下载页列有每个 APK 的 SHA-256。[v0.1.3 发布页](https://github.com/RayforLoy/Monado_CV10/releases/tag/v0.1.3)

历史 x86 和 x86_64 安装包与对应校验和可在 v0.1.2 发布页获取。[v0.1.2 发布页](https://github.com/RayforLoy/Monado_CV10/releases/tag/v0.1.2)

## 在 Galaxy Z Fold7 上启动 OpenXR 应用

首次配置时安装 ARM64 版 Monado CV10 和 Khronos OpenXR Runtime Broker（`org.khronos.openxr.runtime_broker`），在 Broker 的 **Installed Runtimes** 中勾选 **Monado CV10**。把三星外接显示模式设为 **已镜像**。测试可使用未修改的 Khronos `hello_xr` OpenGLES 样例。[Broker 安装](https://play.google.com/store/apps/details?id=org.khronos.openxr.runtime_broker) · [Broker 源码](https://github.com/KhronosGroup/OpenXR-Android-Broker) · [hello_xr 1.1.65 APK](https://github.com/KhronosGroup/OpenXR-SDK-Source/releases/download/release-1.1.65/hello_xr-OpenGLES-release-1.1.65.apk)

1. 普通 C-C 线可直连 CV10。使用原厂手机线时先接好 CV10 和外接 5 V，再接入已解锁的 Fold7。等镜片出现手机桌面。
2. 在 Fold7 打开 **Monado CV10**，再回到手机桌面。遇到三星后台管理拦截服务绑定时，先打开运行时可恢复启动。
3. 从手机桌面启动 `hello_xr` 或已适配的 OpenXR 应用。系统询问是否允许 **Monado CV10** 访问 **HUAWEI GLASS HID** 时，点 **确定**。需要主动请求时，可用 Monado 菜单中的 **CV10 USB access**。
4. 佩戴眼镜。`hello_xr` 正常运行时左右镜片各有一个蓝色方块，测试结束后按返回键退出。

如果点开样例后停在启动画面，先确认 Broker 中 **Monado CV10** 仍已勾选，再重复第 2、3 步。Loader 查不到活动运行时，或三星的 `MARs` 后台管理拒绝 `bindService` 时，都可能出现 `XR_ERROR_RUNTIME_UNAVAILABLE`。

需要让 Fold7 主屏独立跑 120 Hz 时，在启动 OpenXR 应用前启动 Shizuku，确认已授权 Monado CV10，然后在 Monado 菜单选择 **开启独立 120 Hz**。

## 离线应用启动器

此功能已在预览构建中验证。在 **Monado CV10 Preview → 应用启动器** 中直接点选本机应用，或者连接 CV10 后选 **打开 3D 启动器**。启动器读取本机安装列表，无需账户或 Shizuku 权限。应用从手机主屏启动，OpenXR 画面由运行时输出到眼镜。在 Broker 中选择 **Monado CV10 Preview**，保持三星外接显示为 **已镜像**。

- 首次使用或重新插入眼镜后，可先从 Monado 菜单选择 **CV10 USB access**，允许访问 **HUAWEI GLASS HID**，再进入应用启动器。
- 应用列表默认筛选声明了 OpenXR 沉浸式入口的应用，并直接启动该入口。另有一些应用从普通界面进入 XR，例如 PiliPlus，可关闭 **仅显示 XR 应用** 或在 3D 菜单切到 **全部应用** 后打开。
- 3D 菜单使用手机姿态控制激光指向。点按屏幕确认，从手机左右边缘的系统手势区内侧向中间滑动可返回。从系统手势条上方开始向上滑，停留约 350 毫秒，可将菜单和激光指向一起置中。系统返回与 Home 手势仍可用。手机激光笔在启动器窗口内工作，打开目标应用后按该应用的交互方式操作。[华为 VR Glass 手机操控方式](https://consumer.huawei.com/cn/support/content/zh-cn00776840/)
- 3D 菜单是本项目实现的 OpenXR 客户端。项目持有的华为 VR 桌面材料为已打包 APK，未找到可复用的公开源码。

若冷启动找不到运行时，可在启动提示中打开 Broker，确认运行时选择后返回，或选择 **重试**。提示未弹出时，点按手机上的错误提示文字可打开启动选项。

Fold7（SM-F9660）使用 C9.53.6 原厂线和外接 5 V，在 **已镜像** 的 3200×1600、90 Hz 模式验证了两条路径。先从 2D 列表启动未修改的 Khronos `hello_xr` OpenGLES，返回后从 3D 菜单重进，同一个应用进程两次达到 `FOCUSED`，外屏截图和右镜均有画面。结束样例进程后，3D 菜单也能启动新的样例进程并出图。运行时在 Android 12 或更新版本直接管理 CV10 的 Presentation，覆盖 3D 菜单关闭时眼镜失去画面的故障。[Fold7 验收记录](history/fold7-offline-launcher.md)

Fold7 上注入触摸事件验证了左右内侧手势返回、底部上滑停留置中和轻触确认，也通过了 Android 返回键。使用系统导航条的上滑手势回到了三星桌面。激光笔俯仰修正和横向方向通过合成传感器样本复测，手持转动手机的复测待做。两个应用入口均在预览包未获得 Shizuku 权限时通过。无线调试使用同一 Wi-Fi，因此这轮保留了 Wi-Fi，关闭 Wi-Fi 的独立复测待做。

## Pocket XR 外屏兼容性

安装原版 Pocket XR 后，在 Monado CV10 菜单打开 **Pocket XR 外显兼容性**。该页面显示 CV10 的活动时序，以及 Android 是否允许 Pocket XR 在外屏启动。Fold7 的 **设置 → 已连接的设备 → Samsung DeX → 连接的显示设备** 可切换 **已扩展** 和 **已镜像**。

Pocket XR v1.6.28 自带空间桌面的显示与头部跟踪管线。CV10 的 3200×1600 画幅宽高比为 2:1，低于其 3.2 的双目判定阈值，该版本也没有 CV10 的 IMU 支持。系统允许外屏启动时可打开原版的平面入口。实验分支 `codex/cv10-pocket-renderer` 使用 Android 虚拟屏接收 Pocket XR 的左右眼画面，再由 Monado 送到 CV10。[Pocket XR 双目判定](https://github.com/pocket-xr/pocket-xr/blob/v1.6.28/core/src/main/kotlin/com/hayeco/flipspaces/core/spatial/GlassesOptics.kt#L211-L262) · [空间模式前置条件](https://github.com/pocket-xr/pocket-xr/blob/v1.6.28/core/src/main/kotlin/com/hayeco/flipspaces/core/spatial/SpatialMode.kt#L146-L171)

Fold7 接 53 版原厂线和 5 V 外部供电时，**已镜像**为 3200×1600、90 Hz，系统拒绝直接打开 Pocket XR 外屏入口。切到 **已扩展**后，Android 可启动原版的 `GlassesHomeActivity`，外屏活动时序降为 640×480、60 Hz，Samsung DeX 同时报告当前分辨率不受支持。恢复 **已镜像**后回到 3200×1600、90 Hz。

另一次实验在 Fold7 创建 3840×1080、60 Hz 的 Android 虚拟显示屏，并由 ADB 启动原版 Pocket XR 1.6.28 的 `SpaceActivity`。内置测试卡录到了独立的 `LEFT EYE` 与 `RIGHT EYE` 帧。打开 Pocket XR **空间窗口 → 3D：窗口有真实的深度** 后，日志确认实际场景按左右眼各 1920×1080 渲染。没有应用面板、未打开启动器时，正常场景仍为黑底。该轮 Pocket XR 未获得 Shizuku 授权，也没有实际头部姿态输入。镜片双目、空间应用窗口和 3D 投屏仍待验证。

后续用已有 ADB 授权启动同一原版测试卡，通过 Monado 的 OpenXR 合成送到 CV10。外屏截图有各自的左右眼标记，右镜拍到了正立的 `RIGHT EYE`。手机端一键启动需完成独立 Shizuku 授权后复测。左镜光学、实际头部姿态、空间应用窗口和 3D 投屏分别待验收。[Pocket XR 外显记录](history/fold7-pocket-xr-display.md)

## 原厂手机线

运行时识别到原厂线且当前来源为 PC 时，会请求切换到手机来源。获取 HID 后会请求启用佩戴传感器。拔插后重新获取 HID 时，即使 Android 复用了设备 ID，运行时也会重新发送开屏指令。

用户在 Galaxy Z Fold7（SM-F9660）上两次复测确认出图，使用的是已配置为 2 A 的原厂手机线和外接 5 V 供电。一次记录在重插手机端后等 35 秒，再从普通入口打开 Monado CV10 和 `hello_xr`。右镜显示蓝色立方体，外屏模式为 3200×1600 @ 90 Hz，图层每秒约有 89.96 个不同呈现时间戳。硬件验证使用的 ARM64 候选 APK SHA-256 为 `989c7b43ecd369ed5aa49776542db4511e02ac0d0257395d9c4fd40fdf9f26c5`。

同一候选 APK 也验证了回读字段为 `0C095306` 的 53 版原厂线和外接 5 V。按上述启动顺序复测后，`hello_xr` 进入 `FOCUSED`，用户确认双眼均看到蓝色方块，外屏活动时序为 3200×1600 @ 90 Hz。

## 从完整源码构建

源码基于 [Monado ce2b721](https://gitlab.freedesktop.org/monado/monado/-/tree/ce2b721eb4ffcbd726fb88357625f55871a8f262)。CV10 驱动位于 `src/xrt/drivers/android`，运行时位于 `src/xrt/targets/openxr_android`，源码已合入定制修改。

Windows 使用 JDK 17、Python 3、Android SDK Platform 35、Build Tools 34.0.0、NDK 26.3.11579264、CMake 3.22.1、Eigen 5.0.0 和 `PATH` 中的 `glslangValidator`。设置 `JAVA_HOME` 和 `ANDROID_HOME`，在 `local.properties` 中填写 `sdk.dir`、`eigenCMakeDir`（包含 `Eigen3Config.cmake` 的目录）、`pythonBinary`，路径使用正斜杠。发布 APK 还需要 Build Tools 36.0.0 用于对齐和签名。

默认 Gradle 构建将 ARM64（`arm64-v8a`）和 ARMv7（`armeabi-v7a`）打入同一个 APK。运行下方脚本可为这两个 ABI 分别生成单架构签名包和 SHA-256 清单。

```powershell
.\scripts\build-cv10-apks.ps1 -OutputDir .\build\cv10-apks
```

仅构建一个架构时，通过 `-Pcv10Abi` 指定，例如 `.\gradlew.bat "-Pcv10Abi=arm64-v8a" :src:xrt:targets:openxr_android:packageOutOfProcessRelease`。`x86` 和 `x86_64` 可用同一参数显式构建，例如用于模拟器调试。Gradle 把未签名包输出到 `src/xrt/targets/openxr_android/build/outputs/apk/outOfProcess/release/`。上述脚本使用本机 Android 开发证书签名，并校验对齐和签名。首次构建请允许 Gradle 下载依赖。

需要与正式包并排安装时，构建 **Monado CV10 Preview**，并在 Broker 选择同名运行时。

```powershell
.\gradlew.bat "-Pcv10Abi=arm64-v8a" "-Pcv10LauncherPreview=true" :src:xrt:targets:openxr_android:assembleOutOfProcessDebug
```

预览包位于 `src/xrt/targets/openxr_android/build/outputs/apk/outOfProcess/debug/`。Gradle 同时为 APK 和原生 IPC 客户端设置预览包名 `org.cv10.monado.out_of_process.launcher_preview`。默认静态 C++ 标准库配置的 ARM64 APK 已通过所有 `PT_LOAD` 段的 16 KB 对齐检查及 `zipalign -c -P 16 -v 4`。使用 4 KB 内存页的 Fold7 在更新对齐包后未再显示兼容提示。16 KB 内存页设备仍待验证。[Fold7 验收记录](history/fold7-offline-launcher.md) · [Android 16 KB 内存页支持](https://developer.android.com/guide/practices/page-sizes)

历史 APK 和对应实验说明见 [apk/archive](apk/archive) 和 [history](history)。上游项目说明与许可证见 [README.upstream.md](README.upstream.md) 和 [LICENSE](LICENSE)。

## DisplayPort 通道诊断

打开 Monado CV10 启动页的菜单，选择 **CV10 DisplayPort 状态**。OpenXR 运行时接到 CV10 外屏时也会在后台读取一次。读到两条通道或 Type-C 双通道线索时，运行时会尝试在手机上显示相应提示，日志标签为 `Cv10DisplayPort`。

- **实际协商值**来自与 CV10 EDID 匹配的活动 DP 接口。运行时用该接口的 DRM AUX 读取 DPCD，结合当前通道数和各通道的链路训练状态确认本次读数。[Linux DPCD 寄存器定义](https://github.com/torvalds/linux/blob/v6.12/include/drm/display/drm_dp.h)
- **间接线索**来自可读的 Type-C DP Alt Mode 引脚分配。D 为 DP 预留两条通道，C 或 E 为 DP 预留四条。此时实际协商值仍显示为不可读取。[Linux Type-C DisplayPort 状态 ABI](https://github.com/torvalds/linux/blob/v6.12/Documentation/ABI/testing/sysfs-driver-typec-displayport)
- **不可读取**可能出现在系统缺少接口、限制访问、训练尚未完成或链路无法与 CV10 对应时。Android 识别到 CV10 时，页面也会显示当前分辨率和刷新率，便于记录闪屏现场。

有群友怀疑双通道配置与闪屏有关，因果关系待验证。诊断使用运行时现有权限进行只读查询。

## v0.1.2 验证

四个独立 APK 均核对了单一 ABI、核心 OpenXR 和 service ELF、API 26 最低版本、签名和 ZIP 对齐。Android 11（API 30）模拟器上，x86_64 与 x86 均通过全新安装并启动 AboutActivity。测试设备支持 x86_64、x86；本次未在 Android 11 ARM 真机或 CV10 镜片上复测。现有实验与旧版 Fold7 硬件记录见 history 目录。
