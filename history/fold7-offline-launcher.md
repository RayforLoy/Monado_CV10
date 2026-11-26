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

## 3D 菜单的运行时回退

预览包从 `f17ea5f` 的运行时代码构建，ARM64 APK SHA-256 为 `e2c7269e342bcef451e2472e683381c74fbb3d15f325fe8c7e857342fefd763e`。签名和 ZIP 对齐检查通过，五个 ARM64 ELF 的 15 个 `PT_LOAD` 段为 `0x4000` 对齐。该包保留标准 `INTERNET` 声明，本轮沿用上文接线，保持系统 Wi-Fi 开启。

- 02:32:50 系统 Broker 正常返回 Monado CV10，菜单于 02:32:51.036 进入 `FOCUSED`。按返回键结束菜单后重新打开。
- 02:33:05.375，系统 Loader 查不到 Broker Provider。内置菜单在独立的 `:spatialLauncher` 进程使用 `XR_EXT_loader_init_properties` 指定由本包生成的 `XR_RUNTIME_JSON`，让官方 Loader 加载本包的 Monado。02:33:05.998 菜单进入 `FOCUSED`，右镜实拍有原版 `hello_xr` 卡片和激光指针。[启动日志摘录](evidence/fold7-launcher-bundled-runtime.txt) · [右镜实拍](evidence/fold7-launcher-bundled-runtime-right.jpg)

这个回退作用于内置菜单的 Loader。第三方应用仍通过系统 Broker 选择运行时，三星 Freecess 冻结运行时导致的 ANR 也需要单独处理。[OpenXR Loader 1.1.65 实现](https://github.com/KhronosGroup/OpenXR-SDK-Source/blob/release-1.1.65/src/loader/loader_init_data.cpp)

## 整合预览在网络隔离下启动原版样例

2026-11-27 03:19 至 03:30（香港时间）。Fold7 SM-F9660 沿用 C9.53.6、5 V、三星 **已镜像**，CV10 活动模式为 3200×1600、90 Hz。普通和隔离 APK 均来自 `4e85e3e6d7e8c22cb2a026badd7a743212101d3d`。在主清单的 `INTERNET` 声明加上 `tools:node="remove"` 生成隔离版，同时移除来自依赖的同名声明。解包后两份 APK 只有 `AndroidManifest.xml` 不同。

- 普通预览 SHA-256 为 `a983600ad7683d0c9cbe53dec8aa8ee7889b495a5e854992cbe3d16b06648e85`，隔离预览为 `5defe84ccd6e834b86ec61a8602d17a384ad2b65b554713a95e1dd306bcecade`。隔离 APK 的 v2 签名和 16 KiB ZIP 对齐均通过，五个已验证 ELF 与普通包字节相同。普通预览 UID 能连接本地 IPv4 和 IPv6 对照端口，隔离预览创建两种套接字时均得到 `Operation not permitted`。原版 `hello_xr` APK 的哈希同前，清单未申请 `INTERNET`。启动器未获 Shizuku 授权。
- 03:21:14 内置 3D 菜单进入 `FOCUSED`。打开已选中 Monado CV10 Preview 的 Broker 再回到 3D 菜单，按底部上滑停留置中后点按。原版 `hello_xr` 于 03:21:21 进入 `FOCUSED`，CV10 外屏两眼各有方块，右镜先后两次拍到蓝方块。03:21:36 按返回后会话进入 `STOPPING` 和 `IDLE`，随后完成 `xrDestroyInstance`。[双目外屏原始帧](evidence/fold7-launcher-isolated-3d-hello-display.png) · [右镜实拍](evidence/fold7-launcher-isolated-3d-hello-right.jpg)
- 03:19 的普通预览对照中，内置菜单出图后点选原版样例，样例 Loader 查询不到两个 Broker Provider，报告 `XR_ERROR_RUNTIME_UNAVAILABLE`。诊断包尝试在打开样例前持有 Broker Provider 连接，03:23 在点选时未能取得连接。03:25 在菜单进入时取得连接，三星仍在约 0.5 秒后重新冻结 Broker，样例再次报告运行时不可用。两个诊断改动均未纳入预览。
- 03:26 临时给 Khronos Broker 添加电池优化豁免后，内置菜单点选样例成功。03:29 移除豁免后复测，也成功进入 `FOCUSED` 并正常退出。这两次均未在点选前重新打开 Broker，三星先冻结 Broker，样例查询时又解除冻结。手动打开 Broker 可用于恢复，电池豁免的效果未确认。[套接字、冻结与会话日志](evidence/fold7-launcher-isolated-3d-handoff.log)
- 03:30 从同一个隔离包的 2D 列表直接启动原版 `hello_xr`，未配置电池豁免，也未在点选前重新打开 Broker。样例进入 `FOCUSED`，外屏双眼区均有蓝方块，右镜实拍可见蓝方块。按返回键后进入 `STOPPING`、`IDLE`，并完成 `xrDestroyInstance`。[双目外屏原始帧](evidence/fold7-launcher-integrated-isolated-direct-display.png) · [右镜实拍](evidence/fold7-launcher-integrated-isolated-direct-right.jpg)

无线 ADB 和电量监测期间保留 Wi-Fi。采证后移除了临时端口映射和电池豁免，恢复稳定 Monado CV10 Broker 和完整整合预览 APK，系统最低刷新率为 `null`。

## Pocket XR 整合分支的启动器回归

2026-11-27 06:03 至 06:04（香港时间）。ARM64 预览从 `93133c7` 用 `-Pcv10LauncherPreview=true -Pcv10Abi=arm64-v8a` 构建，APK SHA-256 为 `fdfd59a043dbdce2aaec35bd88d596c9196ef05eba1b928c8ccc68876c778032`。校验了 APK 签名、16 KiB ZIP 对齐和五个 arm64 库的 15 个 `PT_LOAD` 段。Fold7 实际系统页大小为 4096，这轮实机运行覆盖 4 KiB 系统。

同一台 Fold7、C9.53.6 原厂线和 5 V 供电，CV10 为 3200×1600、90 Hz。Broker 选 **Monado CV10 Preview**，预览没有 Shizuku 授权。从 2D 列表直开原版 `hello_xr`，以及底部上滑停留置中后从 3D 菜单点选样例，两次均进入 `FOCUSED`，右镜拍到蓝色方块。两次系统返回均进入 `STOPPING` 并完成 `xrDestroyInstance`。3D 菜单也拍到激光指针。完成后恢复稳定 **Monado CV10** Broker、原预览 APK，最低刷新率为 `null`。[回归日志](evidence/fold7-launcher-93133c7.log) · [3D 菜单右镜](evidence/fold7-launcher-93133c7-menu-right.jpg)
