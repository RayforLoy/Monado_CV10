# Fold7 原版 Pocket XR 电脑屏幕 3D

2026-11-27 03:09 至 03:10（香港时间）。Galaxy Z Fold7 SM-F9660 通过 C9.53.6 原厂线和 5 V 辅助供电连接 CV10。三星外接显示为 **已镜像**，CV10 为 3200×1600、90 Hz。原版 Pocket XR v1.6.28 的 APK SHA-256 为 `81cf6b61d9f80a20e1809275a72f0dc0008180aa8d8946aa7b830e78e1de4337`。

原版空间内的 PC Screen 绘制支路对两眼使用整帧。独立的 PC Screen 活动可直接解码到自己的 Android `SurfaceView`。这次让 Windows Sunshine 在本地网络传输 1920×1080、请求 120 fps 的 HEVC 桌面视频。源桌面的左半为蓝色 **LEFT**，右半为红色 **RIGHT**，两边各有计时和移动的黄点。原版 Pocket XR 负责配对、接收和硬件解码。Monado 从 1920×1080 的虚拟屏取画面，分别将左右半帧送到 CV10 的两个 OpenXR 眼层。[原版空间渲染](https://github.com/pocket-xr/pocket-xr/blob/v1.6.28/app/src/main/kotlin/com/hayeco/flipspaces/spatial/SpaceRenderer.kt#L4659-L4693) · [原版独立解码路径](https://github.com/pocket-xr/pocket-xr/blob/v1.6.28/app/src/main/kotlin/com/hayeco/flipspaces/pcscreen/PcScreenActivity.kt#L711-L734)

- 首轮在 3840×1080 的采集缓冲区接收 1920×1080 的虚拟屏，完整左右并排视频出现在左眼区域，右镜黑屏。把采集缓冲区和虚拟屏统一为 1920×1080 后，CV10 外屏的原始帧只在左眼区域显示 **LEFT**，只在右眼区域显示 **RIGHT**。[双眼外屏原始帧](evidence/fold7-pocket-pc-sbs-display.png)
- 右镜拍到正立的红色 **RIGHT**，两次读数为 00:21.52 和 00:26.12，黄点随视频移动。原版 `PcScreen6Activity` 记录进入流媒体播放，Monado 的 OpenXR 会话保持 `FOCUSED`。左眼已有独立外屏数据，左镜光学拍摄待做。[右镜第一次实拍](evidence/fold7-pocket-pc-sbs-right-first.jpg) · [右镜第二次实拍](evidence/fold7-pocket-pc-sbs-right-second.jpg) · [筛选日志](evidence/fold7-pocket-pc-sbs.log)

诊断包从 `eaf4ea4` 加 [PC Screen 诊断补丁](pocket-pc-sbs-diagnostic.patch) 构建，SHA-256 为 `756d39e25f967c4cd07f3b3661b9ffc1d2a2d4c9bc6ab19c7fff82e307b011eb`。测试沿用已授权的 ADB 在临时公共虚拟屏启动原版第 6 路 PC Screen，给实验使用独立的配对槽。测试结束后移除本次配对、停止临时 Sunshine 和测试卡，Fold7 恢复稳定 Monado Broker，Wi-Fi 保持开启。

在实验分支提交 `4e85e3e6d7e8c22cb2a026badd7a743212101d3d`，预览使用 Shizuku 创建同尺寸显示屏，然后启动原版默认的 PC Screen 活动。使用 `-Pcv10LauncherPreview=true -Pcv10Abi=arm64-v8a` 构建。APK SHA-256 为 `a983600ad7683d0c9cbe53dec8aa8ee7889b495a5e854992cbe3d16b06648e85`，APK v2 签名、16 KiB ZIP 对齐和五个 ARM64 库的 15 个 `PT_LOAD` 段均通过检查。在 Fold7 安装了同一 APK，确认两个中文电脑屏幕入口可用。预览包未新增 Shizuku 授权，正式入口从配对到开流待验收。

后来用 `42248dd` 预览的显示服务，在 Fold7 上创建受信任虚拟屏并从原版默认电脑屏幕接收同规格的 HEVC Half SBS 实时视频。默认槽的虚拟屏接收和释放通过，Monado 界面经 Shizuku 一键启动后的镜片输出仍待实测。[默认槽与受信任屏实测](fold7-pocket-shell-service.md)

本次视频是已左右并排的 Half SBS 画面，兼容路径将两半分别放到头锁定平面。原版空间应用面板里的 3D 视频、Full SBS、其他主机软件以及实时头部姿态是独立的验收项。
