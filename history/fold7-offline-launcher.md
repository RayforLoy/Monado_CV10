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

## Fold7 应用网络隔离对照

同一台 Fold7、线材与显示模式。为保留供电监测和 ADB 采集，手机 Wi-Fi 保持开启。本轮测试通过从预览应用的最终清单移除 `android.permission.INTERNET` 来隔离应用的 IP 套接字。正常对照包和隔离包均从 `ba431c9` 构建，解包后唯一不同的 ZIP 成员是 `AndroidManifest.xml`。原版 `hello_xr` APK 的 SHA-256 与上文相同，清单也未申请 `INTERNET`。

| 构建 | SHA-256 |
| --- | --- |
| 普通对照 | `280aa6e72624d7c7dc371ec264814c62949f1abd2ec139c9101cffea72409ba9` |
| 移除 `INTERNET` 的诊断包 | `2820dcb39252208ad179e93583bc9d43d77901534d5157e0c65966b375f91aa9` |

使用临时 ADB 反向端口把手机本机回环地址接到 Windows 回环监听。普通预览 UID 可连接 IPv4 和 IPv6，隔离预览 UID 对两种地址均在创建套接字时返回 `Operation not permitted`。启动器测试前移除了反向端口。隔离包签名校验通过，五个 ELF 的 15 个 `PT_LOAD` 段均为 `0x4000` 对齐。

- 隔离包的 3D 菜单进入 `FOCUSED`，外显截图有两只眼的菜单，右镜拍到本地应用卡片和激光指针。[双目外屏截图](evidence/fold7-launcher-isolated-menu-display.png) · [右镜实拍](evidence/fold7-launcher-isolated-menu-right.jpg)
- 02:09 和 02:14 两次从隔离包的 2D 菜单点选原版 `hello_xr`，应用进入 `FOCUSED`，物理外屏截图与右镜均为黑色。按返回后发生输入 ANR，首次 ANR 把样例渲染线程定位到 `xrWaitFrame → ipc_call_compositor_wait_woke → recvmsg`。此时客户端等待的是运行时的本地 IPC 响应。
- 冷启动配对期间，普通对照也曾无法从 2D 或 3D 菜单引导样例进入 OpenXR。该失败发生在加载器查找 Broker Provider 时，返回 `XR_ERROR_RUNTIME_UNAVAILABLE`。隔离包的 3D 菜单能出图，点选原版样例后也观察到相同的 Broker 查找失败。
- 先打开 Broker 再返回 2D 列表并点击 `hello_xr`。02:19:18 普通对照、02:19:42 隔离包先后进入 `FOCUSED`，均留下双目外屏截图和右镜蓝方块。普通对照按返回键后进入 `STOPPING` 并完成 `xrDestroyInstance`。[隔离包双目外屏截图](evidence/fold7-launcher-isolated-hello-display.png) · [隔离包右镜实拍](evidence/fold7-launcher-isolated-hello-right.jpg)
- 02:14:24.357 和 02:19:43.002，三星 `FreecessController` 均记录以 `Bg-buff` 原因冻结预览运行时。第二次冻结时外屏已出图。02:19 的样例按返回后再现 `xrWaitFrame` 等待本地 IPC 的调用栈。02:20:23.937 运行时解除冻结，随即恢复 USB 读数和帧处理。[冻结与 ANR 日志摘录](evidence/fold7-launcher-isolated-freecess.txt)

本轮保留 Wi-Fi 以维持无线 ADB。隔离包仅用于应用层对照。恢复时 Broker 选回稳定运行时，系统最低刷新率为 `null`，临时日志级别和 ADB 反向端口均已清理。

### 独立预览的网络隔离复测

2026-11-27 07:53 香港时间开始。使用 `dbc8141` 的启动器代码，在诊断分支 `ecab336` 上从合并清单移除 `INTERNET`。ARM64 APK SHA-256 为 `b8c7f725abe8da04535f7858c0d1c2997b9244f6e18f3ed29c064c292a8679df`，通过 v2 签名校验、16 KiB ZIP 对齐及 ARM64 ELF 校验。原版 `hello_xr` 在 Fold7 上的已安装 APK 摘要为 `29ae01ef6a4fea41d67816f7051698f2694ab08ced4c16c02bdd978e62975962`，同样无 `INTERNET`。预览包未获得 Shizuku 授权。预览进程尝试创建 IPv4、IPv6 套接字，均返回 `Operation not permitted`。已在启动测试前撤销临时回环端口。

预览包的 `RUN_ANY_IN_BACKGROUND` 临时设为 `default`。2D 列表直启和 3D 菜单点选的原版 `hello_xr` 在同一 PID 25558 两次进入 `FOCUSED`，分别按返回并成功销毁实例。3D 点选第 2、7、14、24 秒均有外屏呈现记录，第 24 秒图层每秒有 60.32 次不同的呈现时间戳，右镜拍到蓝块。CV10 保持 3200×1600、90 Hz。Fold7 系统 Wi-Fi 供 ADB 使用，系统内的 Shizuku 服务仍在运行。复测后恢复预览包原先的后台策略、原 APK 和稳定版 Broker。[日志摘录](evidence/fold7-launcher-ecab336-no-internet.txt) · [3D 点选第 24 秒右镜](evidence/fold7-launcher-ecab336-3d-24s-right.jpg)

## 3D 菜单的运行时回退

预览包从 `f17ea5f` 的运行时代码构建，ARM64 APK SHA-256 为 `e2c7269e342bcef451e2472e683381c74fbb3d15f325fe8c7e857342fefd763e`。签名和 ZIP 对齐检查通过，五个 ARM64 ELF 的 15 个 `PT_LOAD` 段为 `0x4000` 对齐。该包保留标准 `INTERNET` 声明，本轮沿用上文接线，保持系统 Wi-Fi 开启。

- 02:32:50 系统 Broker 正常返回 Monado CV10，菜单于 02:32:51.036 进入 `FOCUSED`。按返回键结束菜单后重新打开。
- 02:33:05.375，系统 Loader 查不到 Broker Provider。内置菜单在独立的 `:spatialLauncher` 进程使用 `XR_EXT_loader_init_properties` 指定由本包生成的 `XR_RUNTIME_JSON`，让官方 Loader 加载本包的 Monado。02:33:05.998 菜单进入 `FOCUSED`，右镜实拍有原版 `hello_xr` 卡片和激光指针。[启动日志摘录](evidence/fold7-launcher-bundled-runtime.txt) · [右镜实拍](evidence/fold7-launcher-bundled-runtime-right.jpg)

这个回退作用于内置菜单的 Loader。第三方应用仍通过系统 Broker 选择运行时，三星 Freecess 冻结运行时导致的 ANR 也需要单独处理。[OpenXR Loader 1.1.65 实现](https://github.com/KhronosGroup/OpenXR-SDK-Source/blob/release-1.1.65/src/loader/loader_init_data.cpp)

## 独立启动器的 16 KiB 与 Fold7 回归

2026-11-27 07:16–07:18 香港时间。独立启动器分支从 `76ad245b5113a6a347593d6e6ba78d630f0c026d` 构建，使用 `-Pcv10LauncherPreview=true -Pcv10Abi=arm64-v8a`。APK SHA-256 为 `31777c5b42b83d3e5d8eea727802d0981b079710717b8192d38199bbcb42859b`，v2 签名、`zipalign -c -P 16 4` 及五个 ARM64 ELF 的 15 个 `PT_LOAD` 段通过检查。调试证书与本页首轮使用的证书相同。

- Android 16 Google APIs 16 KiB 镜像（API 36、x86_64、修订版 7）实测 `PAGE_SIZE=16384`。APK 经 ARM64 转译安装并加载内置 OpenXR 运行时。模拟器缺少所需的 Vulkan 实例扩展，首次启动和点击 **重试** 均返回 `xrGetSystem` 错误并出现恢复提示，两个进程继续运行。取消提示后按返回可回到 2D 列表。[16 KiB 安装和恢复日志](evidence/android16-16k-launcher-76ad245.txt)
- Fold7（SM-F9660）实测 `PAGE_SIZE=4096`，沿用 C9.53.6 原厂线和 5 V 供电。CV10 保持 3200×1600、90 Hz。由 2D 列表直开原版 `hello_xr`，返回后进入 3D 菜单，底部上滑停留置中并点选同一应用。样例在同一 PID 30745 两次进入 `FOCUSED`、两次正常销毁实例。外屏截图显示双目方块，固定摄像头三次拍到右镜输出。结束后恢复稳定 Broker 和原预览 APK。Wi-Fi 保持开启，最低刷新率为 `null`，未新增 Shizuku 或 VPN 授权。[日志](evidence/fold7-launcher-76ad245.log) · [2D 直启右镜](evidence/fold7-launcher-76ad245-direct-right.jpg) · [3D 菜单右镜](evidence/fold7-launcher-76ad245-menu-right.jpg) · [3D 点选右镜](evidence/fold7-launcher-76ad245-select-right.jpg) · [双目外屏](evidence/fold7-launcher-76ad245-select-display.png)

## Fold7 的 3D 点选与后台冻结对照

2026-11-27 香港时间。沿用同一台 Fold7、原厂线、供电及原版 `hello_xr`。经手机菜单打开 2D 列表后等待 40 秒，避免 ADB 启动产生的临时豁免覆盖 2D 直启。先在 2D 列表直启并退出样例，再从 3D 菜单点选同一个样例。记录 `FOCUSED`、SurfaceFlinger 呈现计数、右镜画面及返回后的 `xrDestroyInstance`。前三轮预览包的 `RUN_ANY_IN_BACKGROUND` 为 `ignore`。

- 基线 `76ad245` 经 2D 直启后持续出帧至第 78 秒，外屏图层约 60 次不同的呈现时间戳每秒，CV10 面板模式为 90 Hz。3D 点选于 07:28:46.252 进入 `FOCUSED`，Freecess 随后以 `Bg-buff` 冻结预览包的两个进程。第 2 秒右镜为黑色，`ActivityDestroyTimeout` 约十秒后解冻并恢复出图，样例可退出。[第 2 秒右镜](evidence/fold7-launcher-76ad245-handoff-black-right.jpg) · [第 38 秒右镜](evidence/fold7-launcher-76ad245-handoff-recovered-right.jpg)
- 对照 `2f52e97` 让 3D 菜单返回选中的应用，由重新获得焦点的 2D 列表发起启动。2D 直启仍可持续出帧并退出。3D 点选后右镜第 2 秒已出蓝块，Freecess 随后再次冻结。第 2、7、14、38、78 秒的外屏截图摘要完全相同，图层无呈现时间戳，按返回键后未进入 `IDLE`。该 APK SHA-256 为 `96677e959c8d1ad8a38ec2afcec479fe1f9504d24131da05f9badfc16521877b`。实验改动保留在诊断分支。[日志摘录](evidence/fold7-launcher-handoff-freecess.txt) · [同包的 Android 16 16 KiB 安装和 Vulkan 失败恢复](evidence/android16-16k-launcher-2f52e97.txt)
- 对照 `3cfbf5b` 在 2D 列表获得焦点后延时 2.6 秒，运行时确实执行 `onShutdown`、`onDestroy`，下一个应用触发新的 `onBind`。本轮 3D 点选仍触发 `Bg-buff`，右镜为黑，返回未进入 `IDLE`。本轮 2D 直启能退出，但在创建外显目标约两秒后，日志记录原生端释放外显视图，所采镜片为黑。APK SHA-256 为 `98a076a4fe9f29e7fb12c7b28e4c4de687fbbddbbca5cf807f6ae3d58f6e3ec9`，实验改动保留在诊断分支。[日志摘录](evidence/fold7-launcher-handoff-freecess.txt)
- 07:42–07:43，使用同一个基线 `76ad245` APK，将预览包的 `RUN_ANY_IN_BACKGROUND` 临时由 `ignore` 改为 `default`。2D 直启及 3D 点选均在 PID 15493 进入 `FOCUSED` 并正常退出。3D 点选第 2、7、14、24 秒的外屏有新呈现时间戳，期间未观察到该包被 Freecess 冻结。[日志摘录](evidence/fold7-launcher-handoff-freecess.txt)
- 07:46–07:49，预览包 `dbc8141` 复测。受限提示可以打开本包应用详情。临时恢复默认 AppOp 后，Fold7 的 Android API 仍报告后台受限，提示仍显示。2D 直启及 3D 点选两轮都在 PID 20996 进入 `FOCUSED` 并正常销毁实例。第二轮第 2、7、14、38、78 秒的图层持续呈现，第 78 秒测得每秒 60.32 次不同的呈现时间戳，CV10 面板仍为 90 Hz。右镜第 2 秒和第 78 秒都拍到蓝块。该包 SHA-256 为 `5d5b639bb81be1fdb3935d83ba87d61366af9e2ba56945cefbab1127708c918d`，v2 签名、16 KiB ZIP 对齐及 15 个 ARM64 `PT_LOAD` 段检查通过。同包在 16 KiB Android 16 模拟器完成安装、运行时加载和 Vulkan 失败后的重试及退出。[日志摘录](evidence/fold7-launcher-handoff-freecess.txt) · [第 2 秒右镜](evidence/fold7-launcher-dbc8141-handoff-2s-right.jpg) · [第 78 秒右镜](evidence/fold7-launcher-dbc8141-handoff-78s-right.jpg) · [16 KiB 验证](evidence/android16-16k-launcher-dbc8141.txt)

复测使用的临时后台 AppOp 调整仅涉及预览包。复测完毕后恢复预览包原先的 `ignore` 设置、稳定版 Broker 和原预览 APK。系统最低刷新率为 `null`，无线 ADB 在线。

## 华为 VR 桌面的源码与许可

用 `aapt2 dump badging` 核对原版样本。包名为 `com.huawei.vrlauncherx`，版本为 `11.1.0.337`（versionCode `110100337`），APK SHA-256 为 `1e91f00d596c636c3f52dddc494ee6b745cdf90bb3a0838ff203f9ae54b92dd3`。包内 `assets/opensource/HwVrLauncherX.html` 的 SHA-256 为 `87543f776255dccc7fe1236ac6d4dfb9244f3b3b462ec246ef87b944901ebbed`。公告列出 AndroidX 的六个组件、Okio 2.8.0、OkHttp 4.9.3 和 Gson 2.9.0，许可证均为 Apache 2.0。`okhttp3/internal/publicsuffix/NOTICE` 另列出 OkHttp 内公众后缀数据的 MPL 2.0 许可。

在华为开源发布中心、VR Engine 开发者资料以及按包名检索的公开结果中，未找到可 fork 的桌面应用源码或整包授权。项目的离线 3D 菜单按 Monado 的 Boost Software License 1.0 发布。它读取 Android 本地应用列表，并通过 OpenXR 渲染卡片和激光指针。交互参考华为公开操作指南。[华为开源发布中心](https://consumer.huawei.com/en/opensource/) · [VR Engine](https://developer.huawei.com/consumer/cn/vr) · [手机操控模式](https://consumer.huawei.com/cn/support/content/zh-cn00776840/)
