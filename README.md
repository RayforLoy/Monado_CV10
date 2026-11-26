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

历史 APK 和对应实验说明见 [apk/archive](apk/archive) 和 [history](history)。上游项目说明与许可证见 [README.upstream.md](README.upstream.md) 和 [LICENSE](LICENSE)。

## DisplayPort 通道诊断

打开 Monado CV10 启动页的菜单，选择 **CV10 DisplayPort 状态**。OpenXR 运行时接到 CV10 外屏时也会在后台读取一次。读到两条通道或 Type-C 双通道线索时，运行时会尝试在手机上显示相应提示，日志标签为 `Cv10DisplayPort`。

- **实际协商值**来自与 CV10 EDID 匹配的活动 DP 接口。运行时用该接口的 DRM AUX 读取 DPCD，结合当前通道数和各通道的链路训练状态确认本次读数。[Linux DPCD 寄存器定义](https://github.com/torvalds/linux/blob/v6.12/include/drm/display/drm_dp.h)
- **间接线索**来自可读的 Type-C DP Alt Mode 引脚分配。D 为 DP 预留两条通道，C 或 E 为 DP 预留四条。此时实际协商值仍显示为不可读取。[Linux Type-C DisplayPort 状态 ABI](https://github.com/torvalds/linux/blob/v6.12/Documentation/ABI/testing/sysfs-driver-typec-displayport)
- **不可读取**可能出现在系统缺少接口、限制访问、训练尚未完成或链路无法与 CV10 对应时。Android 识别到 CV10 时，页面也会显示当前分辨率和刷新率，便于记录闪屏现场。

有群友怀疑双通道配置与闪屏有关，因果关系待验证。诊断使用运行时现有权限进行只读查询。

## v0.1.2 验证

四个独立 APK 均核对了单一 ABI、核心 OpenXR 和 service ELF、API 26 最低版本、签名和 ZIP 对齐。Android 11（API 30）模拟器上，x86_64 与 x86 均通过全新安装并启动 AboutActivity。测试设备支持 x86_64、x86；本次未在 Android 11 ARM 真机或 CV10 镜片上复测。现有实验与旧版 Fold7 硬件记录见 history 目录。
