# Fold7 的 Pocket XR 双运行时

2026-11-27 04:40 至 04:44（香港时间）。Galaxy Z Fold7 SM-F9660，Android 16，HUAWEI VR Glass CV10，经 53 版原厂线和 5 V 外接供电相连。三星外接模式为 **已镜像**，CV10 外显为 3200×1600、90 Hz。用已有无线 ADB 和固定在右镜前的电脑摄像头取证。

| 构建或样例 | SHA-256 |
| --- | --- |
| Monado CV10 生产代码预览 `ce07cb0` | `8d1bbb5bc75e24e8d225872e0def4ecbc5768ad0cd849c5d21e00af50c4713db` |
| 单虚拟屏测试卡诊断 | `457511642cacafa0168f86aa2a7e826a3b7f9b320c5a630dfed43102f8bf9832` |
| 双虚拟屏 OpenXR 诊断 | `77c39d416fe6cf73a79fb87d0d7e8cb7a34b443d6ef65e8cbac5c64198e10691` |
| 原版 Pocket XR v1.6.28 | `81cf6b61d9f80a20e1809275a72f0dc0008180aa8d8946aa7b830e78e1de4337` |
| 原版 Khronos `hello_xr` OpenGLES 1.1.65 | `29ae01ef6a4fea41d67816f7051698f2694ab08ced4c16c02bdd978e62975962` |

预览包 `org.cv10.monado.out_of_process.launcher_preview` 通过 16 KiB ZIP 对齐检查。五个 ARM64 库共 15 个 `PT_LOAD` 段均对齐。三个 APK 的 `libcv10-spatial-launcher.so` 字节相同。诊断用普通公开虚拟屏加已有 ADB 启动应用，生产预览仍经 Shizuku 创建受信任显示。当前未实测生产预览通过新授予的 Shizuku 权限一键启动。

## 原版测试卡

Broker 选中 **Pocket XR**。外层预览的 Loader 从自身缓存读取 `cv10-pocket-runtime.json`，系统记录预览包绑定自己的 `MonadoService`。外层会话进入 `FOCUSED` 并收到原版测试卡。CV10 截图中的两个渲染区分别显示 `LEFT EYE` 和 `RIGHT EYE`，右镜实拍为正立的 `RIGHT EYE`。点击 **停止预览** 后，外层会话销毁，诊断虚拟屏已释放。[双眼缓冲](evidence/fold7-pocket-testcard-pocket-broker-sbs.png) · [右镜实拍](evidence/fold7-pocket-testcard-pocket-broker-right.jpg)

## 原版 OpenXR 游戏图层

保留 Broker 的 **Pocket XR** 选择，临时把原版 Pocket XR 的三星电池模式从 **受限制** 改为 **优化**。双虚拟屏诊断从 ADB 把原版 `SpaceActivity` 打开到显示 133，再把原版 `hello_xr` 打开到独立的显示 134。这个摆法让外层预览持续运行，并保留 Pocket XR 的空间窗口作为游戏层宿主。[双虚拟屏诊断补丁](evidence/fold7-pocket-nested-openxr.patch)

- 外层在 04:43:28.133 进入 `FOCUSED`。内层样例的 Loader 从系统 Broker 选中 `com.hayeco.flipspaces`；原版运行时创建 3840×1080、60 Hz 游戏图层，样例在 04:43:31.456 进入 `FOCUSED`。
- 04:43:31.606，SurfaceFlinger 记录游戏 SurfaceView 的首缓冲区。CV10 外显录屏的两眼区域出现蓝块，右镜摄像头由原版空间的应用占位画面转为灰底蓝块。[双眼录屏帧](evidence/fold7-pocket-nested-openxr-sbs.png) · [右镜实拍](evidence/fold7-pocket-nested-openxr-right.jpg)
- 04:43:31.993，三星 Freecess 冻结 Pocket XR。测试按返回后未取得原版样例正常退出的轨迹，随后终止了测试进程。04:44:04.630，系统因 `ServiceANR` 解冻原版，才出现游戏图层离开的日志。留存的蓝块经镜片确认，但本轮未测得冻结前的持续渲染、游戏头动、正常退出或从原版菜单点选样例。[两轮筛选日志](evidence/fold7-pocket-nested-openxr.log)

换回同一提交的生产预览，暂时在 Broker 选择 **Monado CV10 Preview**，回归内置启动入口。2D 直开和 3D 菜单点选都让原版 `hello_xr` 进入 `FOCUSED`；两次按返回均进入 `STOPPING` 并完成 `xrDestroyInstance`，没有新的崩溃记录。内置 3D 菜单由系统 Broker 找到预览运行时。[启动器回归日志](evidence/fold7-pocket-dual-runtime-launchers.log)

测试后保留生产预览，恢复原版 Pocket XR 的 **受限制** 和稳定 **Monado CV10** Broker。系统最低刷新率为原值 `null`，诊断虚拟屏均已清理。未增授 Shizuku、VPN 或不限电池权限。[原版受限与优化模式对照](fold7-pocket-shell-service.md#原版-pocket-xr-运行时对照)
