# Fold7 CV10 IMU 与 Pocket XR 报文

2026-11-27 香港时间。Galaxy Z Fold7 SM-F9660 使用 C9.53.6 原厂线、外接 5 V，三星外接显示为 **已镜像**。CV10 活动模式为 3200×1600、90 Hz。眼镜固定，测试期间未转动。

实验分支从运行时已打开的 USB HID 读取线程分流陀螺仪、加速度计和设备时间戳，打包成 Pocket XR 1.6.28 可读的 XREAL One IMU 报文。诊断入口在 OpenXR 会话中记录真实样本，由电脑上的原版 `OneImuFramer` 和 `HeadFusion` 读取。编译测试所用的核心源码与原版发布提交 `af04bc43c6cdf8863b3ac51e6f9b79f817e5abff` 内容一致。[原版报文格式](https://github.com/pocket-xr/pocket-xr/blob/v1.6.28/core/src/main/kotlin/com/hayeco/flipspaces/core/spatial/OneImuProtocol.kt) · [原版姿态融合](https://github.com/pocket-xr/pocket-xr/blob/v1.6.28/core/src/main/kotlin/com/hayeco/flipspaces/core/spatial/HeadFusion.kt)

首轮采集基于 `0c69c81`，诊断 APK 的 SHA-256 为 `23e32efac0f6012fa5c4895b200b18d691413e07bf129a60af7ad867875aa3c8`。原版解析器解出 3000 帧，丢弃 0 字节。前两段设备时间间隔分别为 122 毫秒和 563 秒，其后的中位间隔为 0.979 毫秒。加速度模长均值为 9.81 m/s²，静置角速度均方根为 0.0026 rad/s。连续段接近 1 kHz。

`4d92709` 使用与 CV10 驱动相同的 50 毫秒阈值判断时钟断点。等待相邻有效报文后恢复输出，以手机单调时钟建立新的起点，并继续用设备采样间隔推进报文时间。

01:51 使用该修复重新采集。诊断 APK 的 SHA-256 为 `e205e17ee2c35153d3f32a11cd00547e1b9964f4c868dca8895542489e07d59c`。原版解析器读出全部 3000 帧，丢弃 0 字节。首尾间隔为 3.00796 秒，平均 997.02 Hz，最大相邻间隔为 6.82 毫秒。原版融合器在末帧输出 yaw +0.015°、pitch −0.229°、roll −0.140°。402000 字节采集文件的 SHA-256 为 `229090fcd078cb3251463701b11a8fd2ca722aaa65178bd7e029f6aa80cbb0bc`。

## 实验 TCP 服务

`beb01cb` 沿用同一个 CV10 HID 读取线程，向原版 Pocket XR 的协议端口 52998 提供 IMU 样本。52999 响应心跳，按协议返回不支持的硬件控制请求。预览可选的 Android VPN 只为 Pocket XR 配置 `169.254.2.1/32` 本机地址，转发路由和 DNS 均为空。监听器验证对端地址为本机地址。原版协议没有调用方认证，手机上能访问该地址的其他进程也可能连接。退出预览时两个监听器随服务关闭。[原版网络发现及 TCP 连接](https://github.com/pocket-xr/pocket-xr/blob/v1.6.28/app/src/main/kotlin/com/hayeco/flipspaces/spatial/GlassesNetwork.kt)

电脑端把上面的 Fold7 实测采集送回 `Cv10PocketImu`，再由预览的 TCP 服务传给原版 `OneImuFramer`、`ControlFramer` 和 `HeadFusion`。两个连续连接各解析 300 帧，丢弃 0 字节，时间戳递增，融合姿态为有限数值。原版解析器验证了分片发送的心跳以及不支持的硬件控制返回。关闭服务后两个监听端口可立即重新绑定。[回归输出](evidence/fold7-pocket-xr-head-tcp-host.txt)

Fold7 安装了同一提交构建的 ARM64 调试预览，包名为 `org.cv10.monado.out_of_process.launcher_preview`，SHA-256 为 `c86616a57ec180851299993436141eff02a2f60b86b32e3c376fb90e6b2b536c`。签名、`zipalign -c -P 16 4` 和五个原生库的 15 个 ELF LOAD 段均通过检查。点击实验选项出现 Android VPN 确认框。按返回取消后，头踪关闭，两个显示预览按钮仍可用，系统没有新增 `169.254.2.1` 地址，也没有启动头踪服务。

02:04 选中该预览包作为运行时后，未经修改的 Khronos `hello_xr` 进入 FOCUSED，右镜实拍可见蓝色方块。按返回后会话进入 STOPPING、IDLE，日志记录 `xrDestroyInstance` 完成。测试结束后返回三星桌面，Broker 恢复正式 Monado CV10，`min_refresh_rate` 为 `null`。[右镜实拍](evidence/fold7-pocket-xr-head-hello-right.jpg)

Fold7 上的原版 Pocket XR 尚未连接实时头踪服务。保持既有授权查询路由时，手机未配置 `169.254.2.1/32`，ADB shell 到这一地址的路由指向 Wi-Fi 网关。Android VPN 授权后仍须验证原版能发现本机地址并收到实时样本，再用实物运动确认三轴方向、转角及重置。
