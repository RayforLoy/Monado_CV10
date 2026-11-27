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

06:37 以包含 Pocket XR VPN 停止修复的 `627c32bf60e1f736eeecd1bc3677f591cf63d6bf` 再测。ARM64 APK 的 SHA-256 为 `4447087444bee5dcea4fffd8c4db2873798a86b8759fc1add1f97c3cc33b9a55`。v2 签名、16 KiB ZIP 对齐及五个 ARM64 ELF 的 15 个 `PT_LOAD` 段通过检查。沿用同一台 Fold7 和接线，两种入口都启动原版 `hello_xr` 至 `FOCUSED`。右镜拍到 3D 菜单、激光指针和两轮蓝色方块。两轮返回都进入 `IDLE` 并完成 `xrDestroyInstance`。结束后恢复稳定 Broker 及测试前的预览 APK，电量 69%、最低刷新率 `null`。Wi-Fi 与 VPN、Shizuku 授权保持原值。[回归日志](evidence/fold7-launcher-627c32b.log) · [2D 直开样例右镜](evidence/fold7-launcher-627c32b-direct-right.jpg) · [3D 菜单右镜](evidence/fold7-launcher-627c32b-menu-right.jpg) · [3D 点选样例右镜](evidence/fold7-launcher-627c32b-select-right.jpg)

## 16 KiB 系统安装与启动失败回归

06:43–06:47 香港时间。Android 16 Google APIs 16 KiB 系统镜像（API 36、x86_64、修订版 7）报告 `getconf PAGE_SIZE` 为 `16384`，并通过 ARM64 翻译层安装同一个 `627c32b` APK。2D 启动器打开，3D 入口成功加载 ARM64 的 OpenXR 运行时与 `libmonado-service.so`。测试配置缺少运行时所需的 Vulkan 实例扩展。初始化失败时，服务清理尚未初始化的 Vulkan 队列互斥锁，触发 `vk_deinit_mutex → os_mutex_destroy` 的断言并退出。

`6561af9` 仅释放已初始化的队列互斥锁，并回收部分初始化失败时已经创建的锁。修复包在同一个 16 KiB 系统成功安装，原生运行时能够加载、创建实例。以相同 Vulkan 条件重新启动及选择 **重试**，两次都在 `xrGetSystem` 返回错误并显示可重试提示。启动器和运行时服务进程均存活。这轮模拟器验证覆盖安装、原生库加载和失败恢复，CV10 显示仍由 Fold7 实机回归覆盖。[16 KiB 加载与重试日志](evidence/android16-16k-launcher.txt)

`6561af9` ARM64 APK SHA-256 为 `c2b9f6017662de9766e06e027a32cf847a5050a73b941465385231c682160d9d`。v2 签名、16 KiB ZIP 对齐和五个原生库的 15 个 ELF `PT_LOAD` 段通过检查。

06:46–06:47 在 Fold7 安装同一 APK，系统页大小为 4096。沿用 C9.53.6 原厂线和 5 V 供电，从 2D 列表和 3D 菜单依次启动未修改的 `hello_xr`。两次均 `FOCUSED`，右镜对应蓝方块可见，按返回后进入 `IDLE` 并完成 `xrDestroyInstance`。恢复稳定 Broker 和原预览 APK，最低刷新率为 `null`，电量 70%。[Fold7 日志](evidence/fold7-launcher-6561af9.log) · [2D 直开样例右镜](evidence/fold7-launcher-6561af9-direct-right.jpg) · [3D 菜单右镜](evidence/fold7-launcher-6561af9-menu-right.jpg) · [3D 点选样例右镜](evidence/fold7-launcher-6561af9-select-right.jpg)

## 独立启动器的 16 KiB 与 Fold7 回归

2026-11-27 07:16–07:18 香港时间。独立启动器分支从 `76ad245b5113a6a347593d6e6ba78d630f0c026d` 构建，使用 `-Pcv10LauncherPreview=true -Pcv10Abi=arm64-v8a`。APK SHA-256 为 `31777c5b42b83d3e5d8eea727802d0981b079710717b8192d38199bbcb42859b`，v2 签名、`zipalign -c -P 16 4` 及五个 ARM64 ELF 的 15 个 `PT_LOAD` 段通过检查。调试证书与本页首轮使用的证书相同。

- Android 16 Google APIs 16 KiB 镜像（API 36、x86_64、修订版 7）实测 `PAGE_SIZE=16384`。APK 经 ARM64 转译安装并加载内置 OpenXR 运行时。模拟器缺少所需的 Vulkan 实例扩展，首次启动和点击 **重试** 均返回 `xrGetSystem` 错误并出现恢复提示，两个进程继续运行。取消提示后按返回可回到 2D 列表。[16 KiB 安装和恢复日志](evidence/android16-16k-launcher-76ad245.txt)
- Fold7（SM-F9660）实测 `PAGE_SIZE=4096`，沿用 C9.53.6 原厂线和 5 V 供电。CV10 保持 3200×1600、90 Hz。由 2D 列表直开原版 `hello_xr`，返回后进入 3D 菜单，底部上滑停留置中并点选同一应用。样例在同一 PID 30745 两次进入 `FOCUSED`、两次正常销毁实例。外屏截图显示双目方块，固定摄像头三次拍到右镜输出。结束后恢复稳定 Broker 和原预览 APK。Wi-Fi 保持开启，最低刷新率为 `null`，未新增 Shizuku 或 VPN 授权。[日志](evidence/fold7-launcher-76ad245.log) · [2D 直启右镜](evidence/fold7-launcher-76ad245-direct-right.jpg) · [3D 菜单右镜](evidence/fold7-launcher-76ad245-menu-right.jpg) · [3D 点选右镜](evidence/fold7-launcher-76ad245-select-right.jpg) · [双目外屏](evidence/fold7-launcher-76ad245-select-display.png)

## Fold7 的 3D 点选与后台冻结对照

10:41，`00d383b` 从 2D 列表直启原版 `hello_xr` 约 0.52 秒进入 `FOCUSED`。按返回后马上由 3D 菜单点选同一个仍存活的样例，三星 Freecess 在启动请求约 0.24 秒后冻结 Preview 约 9.8 秒，随后解冻。样例这轮在点选后约 10.43 秒进入 `FOCUSED`，CV10 两眼区最终正常出画。Preview 的后台运行已允许。2D 直启后迅速切到 3D 的延迟待修。[筛选日志](evidence/fold7-launcher-warm-2d-to-3d.log)

10:48，仍用 `00d383b` 快速重进。3D 点选后 121 毫秒进入 `FOCUSED`，约 378 毫秒后 Freecess 冻结预览的运行时与菜单进程。返回键因输入超时触发 ANR。One UI 8.5 中国版 **电池 → 后台控制** 显示 Preview 已为 **不受限制**，ADB 也读到该包位于 Android 电池优化豁免名单。

10:50 至 11:00 用三份诊断包复测。将 3D Activity 的 `finish()` 推迟到 `onStop()`，仍在旧样例的 `onDestroy` 时触发约 9.6 秒冻结。将预览的目标 SDK 改为 34，Android 确认 Monado 前台服务的类型为 `specialUse`，样例 178 毫秒进入 `FOCUSED` 后仍然冻结，返回触发 ANR。让 3D 启动器在交接时使用带 `BIND_IMPORTANT | BIND_ABOVE_CLIENT` 的绑定，三次点选仍在进入 `FOCUSED` 后冻结约十秒。三份诊断包均已撤回。

11:01，恢复 `00d383b` 后只从 2D 列表打开样例，未启动 3D 菜单进程。样例约 405 毫秒进入 `FOCUSED`，约 648 毫秒后 Freecess 冻结 Preview 主进程。两次双目截图的文件摘要相同，系统返回触发 ANR。当时 `dumpsys activity oom` 仍显示运行时为前台绑定服务 `BTOP`，该 PID 的 `cgroup.freeze` 已实际为 `1`。AOSP 的 `cmd activity unfreeze` 报告成功后，三星记录与该 cgroup 仍显示冻结。[进程状态和筛选日志](evidence/fold7-launcher-3d-freecess-20261127.log)

11:04–11:05，单独把 Preview 的后台 AppOp 设为 `default`，以及单独把 `hello_xr` 的后台 AppOp 设为 `allow`，均复现 3D 点选后约十秒的冻结。两项设置随即恢复为实测前的 `allow` 和 `ignore`。

11:07–11:08，用诊断提交 `9c4a497` 构建的 APK，SHA-256 为 `328ac97b74f0b7465ad03d8fdafb7237a1acd25fad675d8e1670546970f75d2`。原版样例进程加载运行时后，每 300 毫秒新建并释放一次额外的服务绑定。三次 2D 后立即切到 3D 的轮次中，从点选到 `FOCUSED` 分别为 114、650、147 毫秒。三次均在同一个样例 PID 中重新创建会话。长一点的第三轮前几秒仍出现 Freecess 冻结，系统日志确认新的 `bindService` 解冻，摘录中的间隔为 53–100 毫秒。外屏早期测得约 50.88 次不同的呈现时间戳每秒，最大呈现间隔 116.05 毫秒，稳定后约 60.32 次每秒，最大间隔 16.58 毫秒。第三轮打开约 17 秒后，返回键立即触发 `onPause`。循环绑定仅用于诊断，生产预览仍待解决最初数秒的冻结和掉帧。[筛选日志和呈现数据摘要](evidence/fold7-launcher-3d-freecess-20261127.log)

11:12，持久绑定改用外部样例的 Activity 上下文，并设置 `BIND_ADJUST_WITH_ACTIVITY | BIND_IMPORTANT`。3D 点选使样例在 119 毫秒进入 `FOCUSED`，Freecess 在其后 85 毫秒冻结运行时 10.59 秒。该 APK 已撤回。[持久绑定对照](evidence/fold7-launcher-3d-freecess-20261127.log)

11:16–11:19，测试 `71b7fd8` 的按需唤醒预览。外部 XR 应用等待套接字回复超过 45 毫秒时，才向 Android 请求一次额外服务绑定。无延迟的回复不发起新绑定。APK SHA-256 为 `71f1f6d788df303d9049a96feba5f4ad9fccf2bcf2070985cb41339214cd49d8`。APK v2 签名、16 KiB ZIP 对齐及五个 ARM64 库的 15 个 ELF `PT_LOAD` 段检查通过。

五轮都先从 2D 列表直启，再从 3D 菜单点选原版 `hello_xr`。3D 点选至 `FOCUSED` 为 134–658 毫秒，五次均在同一 PID 进入 `FOCUSED`，均在系统返回后完成 `xrDestroyInstance`，其中两次在启动约一秒后返回，未出现 ANR。三轮延长测试采样到最初数秒外屏约 54.75–55.47 次呈现每秒，最大间隔 66.33 毫秒，稍后稳定至约 60.32 次每秒，最大间隔 16.58 毫秒。CV10 右镜实拍有蓝块。三星仍在最初数秒触发 Freecess，预览通过绑定缩短冻结；这次测量未覆盖佩戴时的流畅度。[筛选日志和呈现数据摘要](evidence/fold7-launcher-3d-freecess-20261127.log) · [右镜实拍](evidence/fold7-launcher-71b7fd8-right.jpg)

11:29，仍用同一 `71b7fd8` APK，通过 Shizuku 的停止操作关闭服务，测试前后均查不到 `shizuku_server` 进程。2D 直启和 3D 点选原版 `hello_xr` 分别在 419 毫秒和 145 毫秒进入 `FOCUSED`，随后均正常完成 `xrDestroyInstance`。3D 点选的右镜实拍拍到蓝块。Fold7 保持 Wi-Fi 用于唯一的 ADB 连接。完成后重新启动 Shizuku，手机回到 Monado 的 2D 列表，系统最低刷新率为 `null`。[无 Shizuku 时的筛选日志](evidence/fold7-71b7fd8-pocket-and-no-shizuku.log) · [右镜实拍](evidence/fold7-launcher-71b7fd8-shizuku-off-right.jpg)

2026-11-27 香港时间。沿用同一台 Fold7、原厂线、供电及原版 `hello_xr`。经手机菜单打开 2D 列表后等待 40 秒，避免 ADB 启动产生的临时豁免覆盖 2D 直启。先在 2D 列表直启并退出样例，再从 3D 菜单点选同一个样例。记录 `FOCUSED`、SurfaceFlinger 呈现计数、右镜画面及返回后的 `xrDestroyInstance`。前三轮预览包的 `RUN_ANY_IN_BACKGROUND` 为 `ignore`。

- 基线 `76ad245` 经 2D 直启后持续出帧至第 78 秒，外屏图层约 60 次不同的呈现时间戳每秒，CV10 面板模式为 90 Hz。3D 点选于 07:28:46.252 进入 `FOCUSED`，Freecess 随后以 `Bg-buff` 冻结预览包的两个进程。第 2 秒右镜为黑色，`ActivityDestroyTimeout` 约十秒后解冻并恢复出图，样例可退出。[第 2 秒右镜](evidence/fold7-launcher-76ad245-handoff-black-right.jpg) · [第 38 秒右镜](evidence/fold7-launcher-76ad245-handoff-recovered-right.jpg)
- 对照 `2f52e97` 让 3D 菜单返回选中的应用，由重新获得焦点的 2D 列表发起启动。2D 直启仍可持续出帧并退出。3D 点选后右镜第 2 秒已出蓝块，Freecess 随后再次冻结。第 2、7、14、38、78 秒的外屏截图摘要完全相同，图层无呈现时间戳，按返回键后未进入 `IDLE`。该 APK SHA-256 为 `96677e959c8d1ad8a38ec2afcec479fe1f9504d24131da05f9badfc16521877b`。实验改动保留在诊断分支。[日志摘录](evidence/fold7-launcher-handoff-freecess.txt) · [同包的 Android 16 16 KiB 安装和 Vulkan 失败恢复](evidence/android16-16k-launcher-2f52e97.txt)
- 对照 `3cfbf5b` 在 2D 列表获得焦点后延时 2.6 秒，运行时确实执行 `onShutdown`、`onDestroy`，下一个应用触发新的 `onBind`。本轮 3D 点选仍触发 `Bg-buff`，右镜为黑，返回未进入 `IDLE`。本轮 2D 直启能退出，但在创建外显目标约两秒后，日志记录原生端释放外显视图，所采镜片为黑。APK SHA-256 为 `98a076a4fe9f29e7fb12c7b28e4c4de687fbbddbbca5cf807f6ae3d58f6e3ec9`，实验改动保留在诊断分支。[日志摘录](evidence/fold7-launcher-handoff-freecess.txt)
- 07:42–07:43，使用同一个基线 `76ad245` APK，将预览包的 `RUN_ANY_IN_BACKGROUND` 临时由 `ignore` 改为 `default`。2D 直启及 3D 点选均在 PID 15493 进入 `FOCUSED` 并正常退出。3D 点选第 2、7、14、24 秒的外屏有新呈现时间戳，期间未观察到该包被 Freecess 冻结。[日志摘录](evidence/fold7-launcher-handoff-freecess.txt)
- 07:46–07:49，预览包 `dbc8141` 复测。受限提示可以打开本包应用详情。临时恢复默认 AppOp 后，Fold7 的 Android API 仍报告后台受限，提示仍显示。2D 直启及 3D 点选两轮都在 PID 20996 进入 `FOCUSED` 并正常销毁实例。第二轮第 2、7、14、38、78 秒的图层持续呈现，第 78 秒测得每秒 60.32 次不同的呈现时间戳，CV10 面板仍为 90 Hz。右镜第 2 秒和第 78 秒都拍到蓝块。该包 SHA-256 为 `5d5b639bb81be1fdb3935d83ba87d61366af9e2ba56945cefbab1127708c918d`，v2 签名、16 KiB ZIP 对齐及 15 个 ARM64 `PT_LOAD` 段检查通过。同包在 16 KiB Android 16 模拟器完成安装、运行时加载和 Vulkan 失败后的重试及退出。[日志摘录](evidence/fold7-launcher-handoff-freecess.txt) · [第 2 秒右镜](evidence/fold7-launcher-dbc8141-handoff-2s-right.jpg) · [第 78 秒右镜](evidence/fold7-launcher-dbc8141-handoff-78s-right.jpg) · [16 KiB 验证](evidence/android16-16k-launcher-dbc8141.txt)

复测使用的临时后台 AppOp 调整仅涉及预览包。复测完毕后恢复预览包原先的 `ignore` 设置、稳定版 Broker 和原预览 APK。系统最低刷新率为 `null`，无线 ADB 在线。

## 启动器与 Pocket XR 整合预览

2026-11-27 香港时间。源代码 `39250d1` 合并独立启动器 `a8fe12a` 与 Pocket XR 实验预览 `496be1c`。使用 `-Pcv10LauncherPreview=true -Pcv10Abi=arm64-v8a` 构建，包名为 `org.cv10.monado.out_of_process.launcher_preview`，APK SHA-256 为 `1c914a4c15fd91ec4e75cc489bd7355487acffe3dbc11161675eb219d29efdef`。APK v2 签名、16 KiB ZIP 对齐及五个 ARM64 原生库的 15 个 ELF `PT_LOAD` 段通过校验。

Android 16 Google APIs 模拟器报告 `PAGE_SIZE=16384`，经 ARM64 转译安装预览。3D 入口的首次启动和重试都加载了原生运行时。模拟器缺少所需的 Vulkan 实例扩展，两次 `xrGetSystem` 均返回 `-2`，可取消提示并返回 2D 应用列表。Pocket XR 预览菜单可打开，VPN 头踪和空间固定复选框初始均未勾选。[安装与恢复日志](evidence/android16-16k-launcher-39250d1.txt)

Fold7（SM-F9660，4 KiB）保持 C9.53.6 原厂线、5 V 外接供电和三星 **已镜像**，CV10 面板活动时序为 3200×1600、90 Hz。08:04，在 2D 列表关闭 **仅显示 XR 应用** 后，点选原版 Pocket XR 1.6.28 可打开其手机触控板。返回时，处于后台受限状态的预览被三星 Freecess 冻结并出现输入超时。08:06，将预览的 `RUN_ANY_IN_BACKGROUND` 临时设为 `default` 后，原版触控板可正常返回 2D 列表。两包均未获 Shizuku 授权，未启动 VPN。

08:07–08:08，先从 2D 列表直启原版 `hello_xr`，再从 3D 菜单点选同一应用。样例两次以 PID 8655 达到 `FOCUSED`，退出均进入 `IDLE` 并完成 `xrDestroyInstance`。3D 点选后第 24 秒仍有约 60.32 次/秒的不同呈现时间戳。两路均拍到右镜蓝块。测试后恢复原预览 APK、稳定 Broker、后台 `ignore` 和最低刷新率 `null`。[Fold7 日志](evidence/fold7-launcher-39250d1.txt) · [2D 直启右镜](evidence/fold7-launcher-39250d1-direct-right.jpg) · [3D 点选第 24 秒右镜](evidence/fold7-launcher-39250d1-spatial-24s-right.jpg)

### 整合预览的应用网络隔离

08:09–08:11，使用 `39250d1` 派生的诊断提交 `a05f274`，仅从合并后的 Android 清单移除 `INTERNET`。APK SHA-256 为 `305cfe8ef6c47ea0feb1fcbbb1702b36f7eb6602ebfdac49e4d885e3ffb60b4e`。v2 签名、16 KiB ZIP 对齐及五个 ARM64 库的 15 个 ELF `PT_LOAD` 段通过校验。预览和未修改的 Khronos `hello_xr` 均无 `INTERNET` 权限。预览未获 Shizuku 授权。运行于预览 UID 的探针创建 IPv4、IPv6 套接字时均得到 `Operation not permitted`。

移除临时回环映射后，从 2D 列表直启和 3D 菜单点选 `hello_xr`。同一 PID 11235 两次进入 `FOCUSED` 并正常退出。3D 点选第 24 秒仍有约 60.32 次/秒的不同呈现时间戳，右镜拍到蓝块。手机 Wi-Fi 保持打开以维持无线 ADB。结束后确认稳定 Broker、后台 `ignore`、最低刷新率 `null` 和原预览 APK 已恢复。[隔离测试日志](evidence/fold7-launcher-a05f274-isolated.txt) · [第 24 秒右镜](evidence/fold7-launcher-a05f274-isolated-24s-right.jpg)

### Fold7 同时隔离应用网络并停止 Shizuku

2026-11-27 14:24–14:25 香港时间，Fold7（SM-F9660）保持 C9.53.6 原厂线、5 V 外接供电和三星 **已镜像**，CV10 活动时序为 3200×1600、90 Hz。用 `e8b1ef7` 构建 ARM64 诊断 APK，只移除清单中的 `INTERNET` 权限。除清单及签名文件外，APK 内容与常规预览逐字节一致。v2 签名、16 KiB ZIP 对齐及五个原生库的 15 个 ELF `PT_LOAD` 段均已校验。诊断 APK SHA-256 为 `94449002cd776489c6aade49b18d459bded1a003868ad967ec8a09bd20c96413`。

已安装的预览和未修改的 Khronos `hello_xr` 均无 `INTERNET` 权限。从 Shizuku 手机端停止服务后，测试前后均确认没有 `shizuku_server` 进程。从 2D 列表直启样例，再打开 3D 菜单，底部上滑停留置中后点选样例。同一 PID 14436 两次进入 `FOCUSED`，两次按返回均完成 `xrDestroyInstance`。两次 CV10 外显截图均有双目蓝块，固定右镜相机均未拍到蓝块。系统 Wi-Fi 保留给无线 ADB。测完恢复常规预览、其 Broker 选择和 Shizuku 服务，最低刷新率为 `null`。[筛选日志](evidence/fold7-integrated-offline-combined.log) · [外显截图与右镜对照（上为 2D 直启，下为 3D 点选）](evidence/fold7-integrated-offline-combined.jpg)

### Android 16 16 KiB 系统断网对照

2026-11-27 11:33–11:37 香港时间，Android 16（API 36）模拟器报告 `PAGE_SIZE=16384`。使用与 Fold7 相同的 `71b7fd8` ARM64 APK，Broker 选择 **Monado CV10 Preview**。测试期间系统 Wi-Fi 与移动数据均关闭，`shizuku_server` 未运行。

从 2D 列表点选原版 `hello_xr`，Android 收到启动请求。强制使用 `arm64-v8a` 的样例因 `libhello_xr.so` 的 4 KiB 对齐在加载时退出。从同一列表打开 3D 启动器，首次和重试均成功加载运行时，创建并销毁实例。模拟器缺少 `VK_KHR_external_memory_capabilities`，两次 `xrGetSystem` 都返回 `XR_ERROR_RUNTIME_FAILURE`。通过 **取消** 和 Android 返回键回到 2D 列表。关闭 **仅显示 XR 应用** 后，同一列表可打开未修改的 Pocket XR 1.6.28 手机触控板，按返回回到列表。这项测试只覆盖手机入口和运行时加载，CV10 出图另见 Fold7 实机记录。[安装、系统状态与筛选日志](evidence/android16-16k-launcher-71b7fd8-offline.txt)

### Pocket XR 测试卡修订版的 Fold7 安装回归

08:30–08:39，使用同一 Fold7、C9.53.6 原厂线和 5 V 供电。源码 `a3d86e5` 的 ARM64 预览 APK SHA-256 为 `7b4c258ada835cc6f07882231aa9d9ae99504676e52c5a38bb1413c633b1e216`。v2 签名、16 KiB ZIP 对齐及 15 个 ELF `PT_LOAD` 段检查均通过。Fold7 覆盖安装后能打开 **应用启动器** 和启动原版 `hello_xr` 的 Activity。尝试在 Broker 选择 **Monado CV10 Preview** 时，自动化读回仍为稳定版 v0.1.2。

使用稳定版作对照时，2D 启动路径两次都已加载稳定运行时。IPC 服务绑定失败，`xrCreateInstance` 返回 `XR_ERROR_RUNTIME_UNAVAILABLE`。第二次在卸载后还出现 `__cxa_thread_finalize` 崩溃。在两次对照之间，先打开稳定 Monado 再启动同一原版样例，可从手机进入 `FOCUSED`。[修订版安装与稳定运行时对照](evidence/fold7-launcher-a3d86e5-install.txt)

08:42–08:43，点按 Broker 列表中的 **Monado CV10 Preview** 名称一次。Broker 内容提供器确认活动包为 `org.cv10.monado.out_of_process.launcher_preview`，样例的 OpenXR Loader 日志也确认加载该包。2D 直启和底部上滑停留置中后 3D 点选，均由同一原版 `hello_xr` 进程（PID 31344）进入 `FOCUSED`。两次系统返回后都到达 `IDLE` 并完成 `xrDestroyInstance`。CV10 右镜拍到 3D 菜单与指针、两次样例的蓝方块。未新增 Shizuku 或 VPN 授权。原预览 APK、稳定 Broker、后台 `ignore` 和最低刷新率 `null` 已恢复。[筛选日志](evidence/fold7-launcher-a3d86e5-pass.txt) · [2D 直启右镜](evidence/fold7-launcher-a3d86e5-direct-right.jpg) · [3D 菜单右镜](evidence/fold7-launcher-a3d86e5-menu-right.jpg) · [3D 点选右镜](evidence/fold7-launcher-a3d86e5-select-right.jpg)

08:47，使用同一预览包，在 2D 列表关闭 **仅显示 XR 应用** 后打开原版 Pocket XR 1.6.28。系统记录 `com.hayeco.flipspaces/.TrackpadActivity` 已显示并处于前台。系统返回后重新进入 Monado 的 2D 列表。这条手机入口未新增 Shizuku 或 VPN 授权。测试时临时固定手机竖屏，结束后恢复原横屏方向、原预览 APK 和后台限制。Broker 一直选中稳定运行时。[手机入口与返回记录](evidence/fold7-launcher-a3d86e5-pass.txt)

## 从 Pocket XR 切回内置启动器

2026-11-27 12:26 香港时间，ARM64 预览 APK 的 SHA-256 为 `053494a4e9a3c8ec9efb3d5726b7053b7a1fab6e544846ca8f224b6d26231eb6`。APK v2 签名、16 KiB ZIP 对齐及五个 ARM64 库的 15 个 ELF `PT_LOAD` 段均通过检查。内置 2D 和 3D 启动器在点选 OpenXR 应用时查询 Broker。确认选中原版 Pocket XR 或兼容客户端时提示切换。查询在后台进行，超过一秒会继续正常启动，避免 Broker 卡住手机界面。

同一 APK 在 Android 16 的 16 KiB 模拟器上运行，系统 Wi-Fi 和移动数据关闭，Shizuku 未运行。2D 列表在两个 Pocket XR 选项下都显示提示。取消后仍在列表，样例进程未启动。提示能打开已安装的 Khronos Broker。选择 **仍然启动** 时 Android 收到原版 `hello_xr` Activity 启动请求。切回 **Monado CV10 Preview** 后再次点选，同一 Activity 直接启动。模拟器用于验收手机入口和 Broker 选择，镜片输出由 Fold7 实测。

12:29 在 Fold7 以同一 APK 复测。沿用 C9.53.6 原厂线、5 V 外接供电及三星镜像。从 3D 菜单置中后点选未修改的 `hello_xr`，样例加载 Preview，在点选后一秒内进入 `FOCUSED`，按返回后进入 `IDLE` 并完成 `xrDestroyInstance`。CV10 外显采集帧有两个蓝色方块。固定相机在同一轮右镜实拍中仍未捕捉到图案。开屏命令收到 `OK`。之前的稳定版和独立 Android 图案对照也有相同现象，眼镜重新插接后的恢复情况仍待验。[筛选日志](evidence/fold7-launcher-broker-handoff.log) · [双目外显帧](evidence/fold7-launcher-broker-handoff-external.png) · [右镜实拍](evidence/fold7-launcher-broker-handoff-right.jpg)

12:30 将 Broker 切到原版 Pocket XR 后，在同一 3D 菜单点选 `hello_xr` 显示切换提示，样例进程未启动。从提示打开 Khronos Broker 并切回 **Monado CV10 Preview**，按返回回到 3D 启动器，菜单会话重新到达 `FOCUSED`。测试后恢复 `f439234` APK 和原先的 Preview Broker 选择，最低刷新率为 `null`。[筛选日志](evidence/fold7-launcher-broker-handoff.log)

## 在启动 OpenXR 前确认 CV10 USB 授权

Fold7（SM-F9660）保持 C9.53.6 原厂线、外接 5 V 与三星外屏镜像。一次 USB 数据重新枚举后，`hello_xr` 成功创建 OpenXR 实例，系统 USB 对话框仍在前台，会话在等待窗口内未进入 `FOCUSED`。[USB 重枚举与 OpenXR 日志](evidence/fold7-usb-data-reenumeration.log)

内置 3D 启动器现在先检查 CV10 HID 的 USB 权限。缺少权限时，启动器等待系统授权，重新获得焦点并确认已授权后才开始渲染。取消授权后可点手机上的提示重新请求。2D 列表在 Broker 选中本包时也先检查权限，允许后再次点选应用。已授权或未检测到 CV10 HID 时保留原启动路径。

ARM64 预览 APK 的 SHA-256 为 `18220c62c54343a7f7212979e03717aeea2c63d0e3ed27e57531eca16860ad0e`。包通过 v2 签名和 ZIP 16 KiB 对齐校验，五个原生库全部通过 16 KiB ELF 对齐检查，与上一预览中的原生库逐字节一致。用模拟 USB 服务覆盖了设备缺失、其他设备、请求、拒绝、允许及更换设备。Android 16、16 KiB 模拟器在关闭 Wi-Fi 与移动数据、未运行 Shizuku 时安装成功，从 2D 列表点选 `hello_xr` 确认发出启动请求。原版样例的 4 KiB 原生库仍无法在该系统加载。未接 CV10 的 3D 菜单可加载运行时。模拟器在 `xrGetSystem` 失败后显示重试选项并能返回 2D 列表。

12:47 香港时间，同包在 Fold7 实测。Android 的 USB 授权对话框停留约 15 秒。允许后，3D 启动器自动创建 OpenXR 实例并进入 `FOCUSED`，再从菜单点选原版 `hello_xr`。随后对照 2D 直启，两个入口的会话均进入 `FOCUSED`，外显截图均有灰底双目蓝块，右镜相机均未拍到图案。

12:48，用户只重接眼镜端，保留手机端和 5 V 供电。CV10 HID 从 USB 地址 004 重新枚举为 009，Android 的外显逻辑编号由 246 变为 247。保持同一个 APK，3D 启动器再次在 USB 授权后自行打开。用手机置中手势瞄准并点选 `hello_xr` 后，右镜拍到蓝块。该轮按返回后样例会话进入 `IDLE`，随后 Android 记录 Activity 销毁超时。12:50 的 2D 直启对照也在右镜拍到蓝块，返回后完成 `xrDestroyInstance`。测试结束后恢复原预览 APK，Broker 保持 **Monado CV10 Preview**，系统最低刷新率为 `null`。

12:54–12:55 用同一 APK 核对退出与重进。先后执行 3D 冷启动、2D 直启、3D 点选及延长停留后的 3D 点选。四轮都由样例 PID 22979 进入 `FOCUSED`，按返回后均完成 `xrDestroyInstance`。复核先前超时的日志，三星 Freecess 在会话进入 `IDLE` 约一秒后冻结了样例 PID 17609，并在 Activity 销毁超时后解冻。该轮冻结与退出超时的先后顺序已确认，后续四轮未复现超时。

[授权与会话日志](evidence/fold7-launcher-usb-preflight.log) · [重接前的双目外显帧](evidence/fold7-launcher-usb-before-reconnect-stereo.png) · [重接前的右镜实拍](evidence/fold7-launcher-usb-before-reconnect-right.jpg) · [重接后 3D 入口的右镜实拍](evidence/fold7-launcher-usb-after-reconnect-right.jpg) · [重接后 2D 入口的右镜实拍](evidence/fold7-launcher-usb-after-reconnect-direct-right.jpg)

### 整合包的镜片黑屏复现

2026-11-27 13:11–13:14 香港时间，使用同一台 Fold7、C9.53.6 原厂线、5 V 供电和 `ef8a0ae` ARM64 预览包，保持接线及右镜相机位置。预览包沿用两项已有的 Shizuku 授权。Monado 的 **在 Pocket XR 中打开 OpenXR 应用** 经 **Pocket XR (Monado CV10)** 客户端，在原版 Pocket XR 1.6.28 空间两次启动未修改的 `hello_xr`。同一 PID 两次进入 `FOCUSED`，先后释放应用屏 274 和 275，并保留空间屏 270。第一轮正常完成 `xrDestroyInstance`。第二轮应用屏释放后，三星 Freecess 再次冻结样例，约十秒后因 `ActivityDestroyTimeout` 解冻。结束整个空间时样例完成 `xrDestroyInstance`，之后所有实验虚拟屏均已释放。本机 VPN 全程关闭。

切回 **Monado CV10 Preview** 后，同一个 `ef8a0ae` APK 的 2D 直启和 3D 点选都成功进入 `FOCUSED`，也正常销毁实例。再换回试验前安装的 `f439234`，2D 直启对照同样成功。五次启动的外显帧均有双目蓝块，相机在右镜均未拍到画面。直接启动两组预览 APK 时，佩戴传感器均报 `state=1`，开屏命令也收到 `OK`。结束时恢复 `f439234` 及 Preview Broker，最低刷新率为 `null`。

对照图每行左侧为外显帧，右侧为同期右镜实拍。自上而下对应两轮 Pocket XR、同包 2D 直启、同包 3D 点选、旧预览 2D 直启。[同机对照图](evidence/fold7-integrated-preview-optical-recurrence.jpg) · [应用、冻结及开屏日志](evidence/fold7-integrated-preview-optical-recurrence.log)

13:20–13:21 在相同接线下安装只增加 PCUI 状态读取的诊断包。五个 ARM64 原生库与 `ef8a0ae` 预览 APK 逐字节一致。诊断包通过同证书签名及 ZIP 16 KiB 对齐校验，SHA-256 为 `fb27f5e62169916e719ad3df7685043c9d2ef188dd1bd97b34315cda157f1efa`。通过 2D 列表启动样例后，外显有双目蓝块，右镜继续黑屏。样例正常销毁，手机随后恢复原预览包。

头显读取的来源为手机 `0`，`AT^TYPECSTATE?` 返回 `2`，佩戴状态为 `1`，开屏收到 `OK`。延长读取窗口后，逐眼命令实际都收到头显字符串 `READ LCD timeout!`。此前 Fold7 普通 C-C 直连且镜片可见图案时也收到相同回显，因此 LCD 查询不能区分这次故障。现有对照将复现范围缩小到 Android 已提交外显画面之后，仍需同机、同版本的原厂线与直连对照才能判断链路和头显各自的影响。[诊断补丁](fold7-panel-status-probe.patch) · [读数日志](evidence/fold7-panel-status-probe.log) · [该轮外显和右镜](evidence/fold7-panel-status-probe.jpg) · [主项目直连对照](https://github.com/RayforLoy/HUAWEI_CV10_EVO/blob/d41cad9/docs/cv10-original-cable-overnight-20261126.md)

13:35 从 Fold7 系统诊断中提取了三星 DisplayPort 驱动和 USB 端口记录。12:48 眼镜端重接使 HPD 先低后高，四通道链路两阶段训练均成功，随后驱动开启 3200×1600、90 Hz 视频流。12:48:31 的 DPCD `0x202–0x204` 为 `77 77 81`，四条通道均报告时钟恢复、均衡及符号锁定，通道间对齐位也已置位。[筛选后的驱动与 USB 日志](evidence/fold7-original-cable-dp-optical-timeline.txt) · [Linux DRM 对 DP 状态位的定义](https://kernel.googlesource.com/pub/scm/linux/kernel/git/harry/linux/%2B/c38b8f5f791ecce13ab77e2257f8fd2444ba80f6/include/drm/display/drm_dp.h)

重接后，12:49–13:01 的出图实拍与 13:11–13:14 的黑屏实拍之间，保存下来的驱动记录没有新的 HPD、训练或分辨率切换。USB 历史也没有新增的断开或枚举。HDCP 2.2 认证失败在出图和黑屏时段均已存在。这组证据只能确认重接时链路训练成功，尚未取得黑屏当时的实时 DPCD 或面板读数。[13:01 原版 Pocket XR 右镜](evidence/fold7-pocket-original-xr-compat-right.jpg) · [随后黑屏同机对照](evidence/fold7-integrated-preview-optical-recurrence.jpg)

### 镜片黑屏时的 Fold7 显示硬件读数

2026-11-27 13:54 香港时间。保留 C9.53.6 原厂线、5 V 供电、三星镜像及右镜机位，使用 `9e96499` 构建的预览 APK，SHA-256 为 `5a92592e79d0d6e1fe6b4545ebc5a32171bf3ee3c45bb6a05cf5e08bef912770`。从 2D 列表打开原版 `hello_xr`，样例进入 `FOCUSED`，CV10 的开屏命令收到 `OK`。同期外显截图可见两个蓝块，右镜照片里没有蓝块。

`dumpsys SurfaceFlinger --comp-displays` 里，`HUAWEIAV03` 的 3200×1600 `FramebufferSurface` 计数在约 2.6 秒内从 33737 增至 33883。两次 `dumpsys android.hardware.graphics.composer3.IComposer/default` 采样中，HWC 显示 1 均使用 3200×1600、90 Hz 的全幅 `GPU_TARGET`。同一 SurfaceView 对应的缓冲和客户端合成目标缓冲均发生变化。可以确认黑屏期间 Fold7 仍在合成和更新 CV10 外显缓冲。逐图层 `--latency` 只返回主屏周期，没有提供 CV10 的呈现记录。物理链路即时状态及镜片出画仍需独立证据。[提取后的计数和 HWC 读数](evidence/fold7-hwc-black-optical.log) · [同轮外显与右镜对照](evidence/fold7-hwc-black-optical.png) · [AOSP 的逐图层统计实现](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android16-qpr2-release/services/surfaceflinger/SurfaceFlinger.cpp#6403)

14:10，用户重接眼镜端后，用 `e8b1ef7` 预览回归。退出 Pocket XR 空间并恢复 **Monado CV10 Preview** Broker。内置 3D 启动器进入 `FOCUSED`，手机底部上滑并停留触发置中，点按后原版 `hello_xr` 进入 `FOCUSED` 并在返回时完成 `xrDestroyInstance`。CV10 开屏命令收到 `OK`，外显截图两眼都有蓝块，同期右镜相机仍未拍到蓝块。该轮只检验了固定相机对准的右镜。[外显与右镜对照](evidence/fold7-launcher-hello-after-eye-reconnect.jpg) · [筛选日志](evidence/fold7-pocket-panel-routing.log)

14:38，保持相同接线和右镜取景，用临时 Android `Presentation` 直接向 CV10 外显绘制 3200×1600 的纯色全屏画面，未运行 OpenXR。每隔约 2.2 秒更换颜色，序列为黑、白、黑、红、绿、黑、白、黑。八次外显截图的左右采样点均与预期颜色一致。右镜连续拍摄 23 秒，两段白色窗口和红、绿窗口都没有可辨认的对应画面，相机中央 RGB 的变化未重复出现在两次白屏时。本轮确认现象也能在 Android 原生外显路径中观察到。固定相机的取景和镜片实际出光仍需分别验证。测试包已卸载，手机回到桌面，原 Broker 和 Shizuku 保持运行。[外显阶段与相机读数](evidence/fold7-right-optical-pulse.log) · [白屏外显与右镜画面对照](evidence/fold7-right-optical-pulse-white.jpg)

### 重接后右镜仍黑

2026-11-27 15:15 至 15:21 香港时间，Fold7 使用 C9.53.6 原厂线、外接 5 V 和三星镜像，用户重接眼镜端。沿用源码 `5b650d6` 的 Preview APK，SHA-256 为 `e093936289f603c90d2e7e907caaea36d7380403a48a401e543296e5aebb6648`。内置 2D 列表打开原版 `hello_xr` 后进入 `FOCUSED`，手机外显截图有左右两个蓝块，返回后完成 `xrDestroyInstance`。单独打开 3D 菜单，菜单也进入 `FOCUSED`。外显截图有双目菜单和激光，固定相机未拍到菜单，用户直接看右镜也看不到菜单。[外显与同轮右镜](evidence/fold7-eye-reseat-optics.jpg)

HWC 对 CV10 使用 3200×1600、90 Hz 全幅输出。在 2.07 秒内读到同一外显图层的 126 个不同呈现时间戳。诊断 APK 与既有预览的五个 ARM64 原生库逐字节相同，通过同证书签名及 16 KiB ZIP 对齐校验，SHA-256 为 `b4988e8a39bd26afb359d9887964e976746591672058b7ccb964d486c4979ec5`。亮度读回 4，佩戴状态为 1，手机来源为 0，Type-C 状态为 2，开屏收到 `OK`。异常查询在两秒内未返回。ADB shell 无法读取即时 DP 错误计数或连接器状态。[只读诊断补丁](fold7-eye-reseat-reads.patch) · [实机筛选日志](evidence/fold7-eye-reseat-optics.log)

15:21 生成的 Fold7 驱动记录保留了 12:48 的 HPD 变化和链路训练，以及持续到约 15:18 的同一组 HDCP 重试。15:15 黑屏复测附近未记录新的 HPD、链路训练或 CV10 USB 枚举，无法从该报告独立确认这次重接完成了链路重训或头显复位。[筛选后的驱动日志](evidence/fold7-eye-reseat-driver.log)

### USB-C 直连恢复右镜画面

2026-11-27 15:54 至 15:59 香港时间，用户换为 Fold7 与 CV10 的 USB-C 直连，绕过 C9.53.6 原厂线盒及其辅助供电。系统记录原 HID 设备移除、新设备枚举，CV10 逻辑显示从 247 变为 390，活动时序继续为 3200×1600 @ 90 Hz。沿用 `6d2fb4c` Preview APK，SHA-256 为 `37da3025899c52362150fa851a25e069fdd2adefca37d1b5d1024ab3f2a47293`。

允许 CV10 HID 访问后，3D 菜单进入 `FOCUSED`，固定相机拍到右镜中正向的中文菜单和激光指针。随后从 2D 列表打开同一个原版 `hello_xr`，样例进入 `FOCUSED`，外显有左右两个蓝块，相机在右镜拍到蓝块。此前原厂线接法的同一台设备和相机能采到外显帧，右镜看不到应用。本轮证实直连能恢复右镜显示，原厂线盒、供电及重新建立链路的影响仍有待逐项对照。[直连日志](evidence/fold7-cv10-direct-cable.log) · [3D 菜单右镜实拍](evidence/fold7-cv10-direct-menu-right.jpg) · [Hello XR 右镜实拍](evidence/fold7-cv10-direct-hello-right.jpg)

佩戴复测确认双眼均显示蓝块，左右转头及抬头低头时画面移动正常。重新打开 3D 菜单后，用户持手机确认指针上下同向，再瞄准 `hello_xr` 点按。日志记录手动置中一次，点选后同一应用进程重新进入 `FOCUSED`，退出时完成实例销毁。此前 ADB 只在置中后直接轻点，光点位于应用项之间，菜单保持打开。[直连日志](evidence/fold7-cv10-direct-cable.log) · [3D 菜单外显帧](evidence/fold7-cv10-direct-menu-external.jpg) · [Hello XR 外显帧](evidence/fold7-cv10-direct-hello-external.jpg)

临时为 CV10 请求 3200×1600、70 Hz 后，Android 读回该偏好。采样时活动模式仍为 90 Hz。驱动仅记录显示渲染频率短暂变为 70 Hz，随即回到 90 Hz，本轮未取得稳定 70 Hz 的光学对照。实验结束后恢复原 APK、外显偏好和 90 Hz 活动模式。手机已回桌面，预览 Broker、Shizuku 和无线 ADB 均可用。现象已在用户直接观察右镜时复现，故障范围仍在 Fold7 生成外显画面之后。原厂线视频通路和头显实际出光需用同机直连对照进一步区分。

### 2D USB 授权后继续启动

2026-11-27 13:28–13:30 香港时间，在同一台 Fold7 上保留 C9.53.6 原厂线、外接 5 V、三星镜像及右镜相机位置。源码提交 `463702b` 在 USB 权限对话框关闭、启动器重新获得焦点后，检查仍连接的 CV10 和它的授权状态。允许后继续启动刚才点选的应用。拒绝或眼镜已断开时留在列表。此流程依赖 Android 在关闭系统对话框前授予 USB 设备访问权。[AOSP USB 授权对话框](https://android.googlesource.com/platform/frameworks/base/%2B/c231302191e6/packages/SystemUI/src/com/android/systemui/usb/UsbPermissionActivity.java)

用临时包名取得全新的 USB 授权状态。该 APK 的 SHA-256 为 `023e8c1f135eae3dcdb1d08915e0fc6ed663680c859bc25803d9210fc3e2900e`。首次拒绝后停留在 2D 列表。再次点选原版 `hello_xr` 后，只按系统的 **允许**，约 0.75 秒后样例进入 `FOCUSED`。按返回后完成 `xrDestroyInstance` 并回到列表。再次点选时未弹出授权，样例在同一 PID 26288 进入 `FOCUSED`，随后正常退出。与授权成功同期的外显帧有双目蓝块，右镜相机仍未拍到图案。测试结束后卸载临时包。[首次授权日志](evidence/fold7-direct-usb-continuation-fresh.log) · [同期外显和右镜](evidence/fold7-direct-usb-continuation-photo.jpg)

常用包名 `org.cv10.monado.out_of_process.launcher_preview` 的 APK SHA-256 为 `c09cbfae6db51145e78638e60f32306dbd38d00b77bfc92ca057fe4b8894e079`。五个 ARM64 原生库使用安装时解包方式，15 个 ELF `PT_LOAD` 均通过 16 KiB 对齐检查。保留已有授权后依次从 2D 列表及竖屏 3D 菜单启动同一原版样例，两次均在 PID 26288 进入 `FOCUSED`，返回后均完成 `xrDestroyInstance`。Fold7 保留这个预览包和对应 Broker 选项，系统最低刷新率为 `null`。[常用预览包实机日志](evidence/fold7-direct-usb-continuation-preview.log)

## 从应用启动器打开 Moonlight XR

2026-11-27 14:51–14:55 香港时间，使用同一台 Fold7、C9.53.6 原厂线、外接 5 V 和三星外显镜像。源码 `5b650d6` 的 ARM64 预览 APK SHA-256 为 `e093936289f603c90d2e7e907caaea36d7380403a48a401e543296e5aebb6648`。v2、v3 签名、16 KiB ZIP 对齐和五个 ARM64 库的 15 个 ELF `PT_LOAD` 段均通过检查。

旧预览的 **仅显示 XR 应用** 隐藏 Moonlight XR CV10。覆盖安装后，同一筛选包含 Moonlight XR CV10 和原版 `hello_xr`，原版 Pocket XR 触控板及 Monado CV10 Controller 设置入口仍归在 **全部应用**。3D 菜单进入 `FOCUSED`，双目外显截图显示同样的 XR 条目。本轮从 2D 列表点选 Moonlight XR CV10，手机主屏先显示主机列表，随后选择测试 Sunshine 主机和桌面。两次串流均使用 Monado CV10 Preview 进入 `FOCUSED`，采用 Qualcomm HEVC 硬件解码。两次返回都完成 `xrDestroyInstance`，随后从同一列表直启原版 `hello_xr` 并正常退出。该应用的设置入口需要手机主屏，因此 Pocket XR 嵌套的 OpenXR 应用列表仍只提供声明沉浸式入口的应用。[筛选与退出日志](evidence/fold7-launcher-moonlight.log) · [3D 菜单双目外显](evidence/fold7-launcher-moonlight-menu.jpg)

Moonlight XR CV10 v0.4.3 的 APK SHA-256 为 `581e973086ef5a2a378a542c2d87d32a8d768dc078e9723868f1131948fbaf39`。第二轮串流中，相隔约 4.5 秒的双目外显截图均显示测试卡，计时从 `00:17.45` 走到 `00:21.95`。同期固定右镜相机均未拍到测试图案，镜片端现象仍待独立确认。两轮均沿用现有配对与授权，结束后退出本机测试主机、保留新预览包和 Preview Broker，并恢复到手机桌面。[串流外显与右镜实拍](evidence/fold7-launcher-moonlight-optics.jpg)

## 华为 VR 桌面的源码与许可

用 `aapt2 dump badging` 核对原版样本。包名为 `com.huawei.vrlauncherx`，版本为 `11.1.0.337`（versionCode `110100337`），APK SHA-256 为 `1e91f00d596c636c3f52dddc494ee6b745cdf90bb3a0838ff203f9ae54b92dd3`。包内 `assets/opensource/HwVrLauncherX.html` 的 SHA-256 为 `87543f776255dccc7fe1236ac6d4dfb9244f3b3b462ec246ef87b944901ebbed`。公告列出 AndroidX 的六个组件、Okio 2.8.0、OkHttp 4.9.3 和 Gson 2.9.0，许可证均为 Apache 2.0。`okhttp3/internal/publicsuffix/NOTICE` 另列出 OkHttp 内公众后缀数据的 MPL 2.0 许可。

在华为开源发布中心、VR Engine 开发者资料以及按包名检索的公开结果中，未找到可 fork 的桌面应用源码或整包授权。项目的离线 3D 菜单按 Monado 的 Boost Software License 1.0 发布。它读取 Android 本地应用列表，并通过 OpenXR 渲染卡片和激光指针。交互参考华为公开操作指南。[华为开源发布中心](https://consumer.huawei.com/en/opensource/) · [VR Engine](https://developer.huawei.com/consumer/cn/vr) · [手机操控模式](https://consumer.huawei.com/cn/support/content/zh-cn00776840/)
