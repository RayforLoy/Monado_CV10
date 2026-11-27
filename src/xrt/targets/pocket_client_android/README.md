# Pocket XR (Monado CV10)

此 ARM64 包由 Khronos OpenXR Runtime Broker 加载到要运行的 OpenXR 应用。它把游戏的 OpenXR 会话交给官方 Pocket XR 1.6.28 或 1.6.31 的 Android 服务，Pocket XR 保持原版安装。Monado CV10 预览继续单独驱动眼镜。选择客户端前须同时安装上述官方 Pocket XR 和同一签名的 Monado 预览。[外显启动步骤](../../../../README.md#pocket-xr-外显实验预览)

## 构建

准备 Monado 上游基线 `ee03722885e777462708841223b60affe6873ac2` 和 Pocket XR 源码 `a15941723f72ac3308291de711bc6071e4278293`（`v1.6.31`）。在干净的 Monado 基线上依次应用 Pocket XR 的 `app/src/main/cpp/monado/monado-pocketxr.patch` 和项目的 [`pocketxr-monado-android-nodelete.patch`](../../../../history/evidence/pocketxr-monado-android-nodelete.patch)。按 Fold7 构建记录的 CMake 参数构建 `openxr_monado` 目标，保留原版服务的协议版本检查。[源码与工具链记录](../../../../history/fold7-pocket-dual-runtime.md#源码版卸载修复对照)

在本仓库根目录，使用 JDK 17、Android 35 平台、Build Tools 36 和 Kotlin stdlib 2.0.0，将出自上一步的库打包。`$pocketSource` 指向包含上述提交的 Pocket XR Git 库。`$cv10Keystore` 和 `$cv10KeyAlias` 对应同一份 Monado 预览使用的签名。脚本默认由 `apksigner` 读取口令，也接受 `CV10_POCKET_KEYSTORE_PASSWORD` 和 `CV10_POCKET_KEY_PASSWORD` 环境变量。

```powershell
python src/xrt/targets/pocket_client_android/build.py `
  --pocket-source $pocketSource --runtime $pocketClientLibrary --kotlin-stdlib $kotlinStdlib `
  --platform "$env:ANDROID_HOME/platforms/android-35/android.jar" `
  --build-tools "$env:ANDROID_HOME/build-tools/36.0.0" `
  --keystore $cv10Keystore --key-alias $cv10KeyAlias --output Monado-CV10-Pocket-Client-arm64-v8a.apk
```

安装后在 Broker 选择 **Pocket XR (Monado CV10)**，打开 **Monado CV10 Preview → 更多选项 → Pocket XR 外显预览**。开启 Pocket XR 空间，再用 **在 Pocket XR 中打开 OpenXR 应用** 选择应用。手机端建立虚拟屏需要预览自己的 Shizuku 授权。回到预览页，可用 **关闭 OpenXR 应用屏** 保留空间、结束当前应用，或用 **停止预览** 结束整个外显会话。日常从内置 2D、3D 启动器启动应用时，在 Broker 选回 **Monado CV10 Preview** 或稳定的 **Monado CV10**。
