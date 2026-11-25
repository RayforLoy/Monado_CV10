# Monado CV10

HUAWEI VR Glass CV10 的 Android OpenXR 运行时。包名为 `org.cv10.monado.out_of_process`。安装后在 Khronos OpenXR Runtime Broker 中选择 **Monado CV10**，连接眼镜并允许 USB 访问。

## 安装

从 [Releases](https://github.com/RayforLoy/Monado_CV10/releases) 下载与系统 ABI 匹配的 APK。用 `adb shell getprop ro.product.cpu.abilist` 查看支持的 ABI，用 `adb install -r <APK>` 安装。Android 11 为 API 30，项目最低为 API 26。覆盖更新需要相同签名。实际 VR 功能还取决于 USB 主机、外接显示器、Broker 和 Vulkan 驱动。历史 Fold7 镜片、3DoF 和刷新率复测见 [验收记录](history/README.md)。

| 系统 ABI | v0.1.2 APK |
| --- | --- |
| 64 位 ARM Android，含骁龙、天玑、麒麟的 64 位系统 | [arm64-v8a](https://github.com/RayforLoy/Monado_CV10/releases/download/v0.1.2/Monado-CV10-0.1.2-arm64-v8a.apk) |
| 32 位 ARM Android | [armeabi-v7a](https://github.com/RayforLoy/Monado_CV10/releases/download/v0.1.2/Monado-CV10-0.1.2-armeabi-v7a.apk) |
| 64 位 x86 Android | [x86_64](https://github.com/RayforLoy/Monado_CV10/releases/download/v0.1.2/Monado-CV10-0.1.2-x86_64.apk) |
| 32 位 x86 Android | [x86](https://github.com/RayforLoy/Monado_CV10/releases/download/v0.1.2/Monado-CV10-0.1.2-x86.apk) |

下载后可用 `Get-FileHash <APK> -Algorithm SHA256` 与[发行文件校验清单](apk/v0.1.2/SHA256SUMS.txt)对照。v0.1.2 与之前的 Shizuku 重连版使用相同签名，版本号从 1 升为 102。

## 从完整源码构建

源码基于 [Monado ce2b721](https://gitlab.freedesktop.org/monado/monado/-/tree/ce2b721eb4ffcbd726fb88357625f55871a8f262)。CV10 驱动位于 `src/xrt/drivers/android`，运行时位于 `src/xrt/targets/openxr_android`，源码已合入定制修改。

Windows 使用 JDK 17、Python 3、Android SDK Platform 35、Build Tools 34.0.0、NDK 26.3.11579264、CMake 3.22.1、Eigen 5.0.0 和 PATH 中的 glslangValidator。设置 JAVA_HOME，在 local.properties 中填写 sdk.dir、eigenCMakeDir（包含 Eigen3Config.cmake 的目录）、pythonBinary，路径使用正斜杠。发布 APK 还需要 Build Tools 36.0.0 用于对齐和签名。构建全部架构：

```powershell
.\scripts\build-cv10-apks.ps1 -OutputDir .\apk\v0.1.2
```

仅构建一个架构时，可执行 `.\gradlew.bat "-Pcv10Abi=arm64-v8a" :src:xrt:targets:openxr_android:packageOutOfProcessRelease`。Gradle 输出未签名包到 `src/xrt/targets/openxr_android/build/outputs/apk/outOfProcess/release/`；上述脚本完成对齐、使用本机 Android 开发证书签名，并检查 APK 签名。首次构建请允许 Gradle 下载依赖。

历史 APK 和对应实验说明见 [apk/archive](apk/archive) 和 [history](history)。上游项目说明与许可证见 [README.upstream.md](README.upstream.md) 和 [LICENSE](LICENSE)。

## v0.1.2 验证

四个独立 APK 均核对了单一 ABI、核心 OpenXR 和 service ELF、API 26 最低版本、签名和 ZIP 对齐。Android 11（API 30）模拟器上，x86_64 与 x86 均通过全新安装并启动 AboutActivity。测试设备支持 x86_64、x86；本次未在 Android 11 ARM 真机或 CV10 镜片上复测。现有实验与旧版 Fold7 硬件记录见 history 目录。
