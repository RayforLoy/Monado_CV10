# Fold7 CV10 IMU 与 Pocket XR 报文

2026-11-27 香港时间。Galaxy Z Fold7 SM-F9660 使用 C9.53.6 原厂线、外接 5 V，三星外接显示为 **已镜像**。CV10 活动模式为 3200×1600、90 Hz。眼镜固定，测试期间未转动。

实验分支从运行时已打开的 USB HID 读取线程分流陀螺仪、加速度计和设备时间戳，打包成 Pocket XR 1.6.28 可读的 XREAL One IMU 报文。诊断入口在 OpenXR 会话中记录真实样本，由电脑上的原版 `OneImuFramer` 和 `HeadFusion` 读取。编译测试所用的核心源码与原版发布提交 `af04bc43c6cdf8863b3ac51e6f9b79f817e5abff` 内容一致。[原版报文格式](https://github.com/pocket-xr/pocket-xr/blob/v1.6.28/core/src/main/kotlin/com/hayeco/flipspaces/core/spatial/OneImuProtocol.kt) · [原版姿态融合](https://github.com/pocket-xr/pocket-xr/blob/v1.6.28/core/src/main/kotlin/com/hayeco/flipspaces/core/spatial/HeadFusion.kt)

首轮采集基于 `0c69c81`，诊断 APK 的 SHA-256 为 `23e32efac0f6012fa5c4895b200b18d691413e07bf129a60af7ad867875aa3c8`。原版解析器解出 3000 帧，丢弃 0 字节。前两段设备时间间隔分别为 122 毫秒和 563 秒，其后的中位间隔为 0.979 毫秒。加速度模长均值为 9.81 m/s²，静置角速度均方根为 0.0026 rad/s。连续段接近 1 kHz。

`4d92709` 使用与 CV10 驱动相同的 50 毫秒阈值判断时钟断点。等待相邻有效报文后恢复输出，以手机单调时钟建立新的起点，并继续用设备采样间隔推进报文时间。

01:51 使用该修复重新采集。诊断 APK 的 SHA-256 为 `e205e17ee2c35153d3f32a11cd00547e1b9964f4c868dca8895542489e07d59c`。原版解析器读出全部 3000 帧，丢弃 0 字节。首尾间隔为 3.00796 秒，平均 997.02 Hz，最大相邻间隔为 6.82 毫秒。原版融合器在末帧输出 yaw +0.015°、pitch −0.229°、roll −0.140°。402000 字节采集文件的 SHA-256 为 `229090fcd078cb3251463701b11a8fd2ca722aaa65178bd7e029f6aa80cbb0bc`。

这一诊断验证了真实 CV10 数据的编码、时间戳和原版解析。Fold7 上的原版 Pocket XR 仍需接入实时头踪传输，实际三轴方向、转角及重置须用实物运动验收。
