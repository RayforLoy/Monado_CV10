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

## Fold7 实时 TCP 对照

03:38，原接线和固定镜位。诊断 APK 从整合预览 `4e85e3e` 加 [回环诊断补丁](pocket-head-live-loopback-diagnostic.patch) 构建，SHA-256 为 `230ddff11593cc118378cebfc6c2be2c6dd038099022fd3e0c1ff10504fa523a`。v2 签名和 16 KiB ZIP 对齐通过，五个原生库与整合预览逐字节一致。补丁在 CV10 HID 读取器成功打开时启动同一个 `Cv10PocketHeadLink`，监听手机的 `127.0.0.1`，读取器关闭时释放监听。

从 2D 启动器打开原版 Khronos `hello_xr`，会话进入 `FOCUSED`，右镜拍到蓝方块。经临时 ADB 转发，把手机端两个诊断端口接到电脑上的 [验证器](VerifyFold7LiveImu.kt)。验证器与 Pocket XR v1.6.28 发布提交 `af04bc43c6cdf8863b3ac51e6f9b79f817e5abff` 的 `core/src/main` 一起编译，核心源码保持原样。两次连续连接各接收 3000 个当时从 CV10 HID 读出的报文，均未丢弃字节。速率分别为 999.67 Hz 和 999.99 Hz，原版姿态融合器输出有限角度，两次心跳和不支持的立体模式查询回应均通过原版解析器。[实时日志](evidence/fold7-pocket-xr-live-imu.txt) · [右镜实拍](evidence/fold7-pocket-xr-live-imu-right.jpg)

按返回后 `hello_xr` 完成 `xrDestroyInstance`，手机确认两个回环端口均关闭。测试已移除 ADB 转发，恢复完整整合预览 APK 和稳定 Broker。全程沿用已有授权，未增加 VPN 或 Shizuku 授权。

手机上的原版 Pocket XR 尚未连接实时头踪服务。保持既有授权查询路由时，手机未配置 `169.254.2.1/32`，ADB shell 到这一地址的路由指向 Wi-Fi 网关。VPN 授权后仍须验证原版应用能发现本机地址并读取实时样本，再用实物运动确认三轴方向、转角及重置。

## Android 16 原版 Pocket XR 接入

06:26–06:36 香港时间。在 Android 16 Google APIs 模拟器（API 36、镜像修订版 7）上，关闭 Wi-Fi 和移动数据，系统未安装 Shizuku。安装官方 ARM64 Pocket XR 1.6.28（versionCode 214），APK SHA-256 为 `81cf6b61d9f80a20e1809275a72f0dc0008180aa8d8946aa7b830e78e1de4337`。测试小程序复用生产的 `Cv10PocketHeadLinkService`、`Cv10PocketHeadLink` 和 `Cv10PocketImu`，在模拟器中经 Android 授权建立本机 VPN，向采样入口送入模拟陀螺仪和重力数据。用 ADB 将原版 `SpaceActivity` 打开到 3840×1080、60 Hz 测试显示屏。

原版自行发现 `169.254.2.1`，通过 `tun0` 连接 52998 和 52999，报告 IMU 速率为 200 Hz。原版融合器输出以下读数。正 Y 轴旋转的 yaw 符号采用原版 `SpatialMath.yawPitchRoll()` 的约定。[原版角度约定](https://github.com/pocket-xr/pocket-xr/blob/v1.6.28/core/src/main/kotlin/com/hayeco/flipspaces/core/spatial/SpatialMath.kt)

| 输入 | 原版融合器输出 |
| --- | --- |
| 绕 X 轴 +17.189° / −17.189° | pitch +17.19° / −17.19° |
| 绕 Y 轴 +14.324° / −14.324° | yaw −14.3° / +14.3° |

`310f140` 修正空闲控制连接每约 8 秒断开的问题。修复后，原版连接保持超过 70 秒。收到一条控制消息的首字节后仍有读取超时。电脑端回归验证空闲 9 秒后还能在同一套接字回复心跳、半条消息会超时，而且关闭后可重开监听器。[电脑端回归源码](VerifyPocketControlIdle.java)

`627c32b` 修正停止预览时 VPN 接口残留的问题。调用旧停止路径后，Android 系统仍绑定 VPN 服务且 `tun0` 存在。修复后 `tun0` 和服务记录均消失。重新启动后，原版 Pocket XR 在同一个 PID 5402 内再次收到首帧 IMU。[模拟器与电脑端日志](evidence/android16-pocket-head-link.txt)

这轮验证了原版 Android 应用接收、融合姿态和重连。CV10 实物姿态的方向、重置和镜片表现仍需在 Fold7 上确认。Fold7 的 Android VPN 权限保持原值。

## 在原版 Android 应用回放 Fold7 实测样本

07:22 香港时间，Android 16 API 36 模拟器关闭 Wi-Fi 和移动数据，Shizuku 未运行。测试小程序使用 `595683d` 的正式头踪桥与 VPN 服务，以录制的采样间隔将 Fold7 实测的 3000 帧静置 IMU 数据循环送回采样入口。源文件为上文 `4d92709` 采集的 `cv10-pocket-imu.bin`，SHA-256 为 `229090fcd078cb3251463701b11a8fd2ca722aaa65178bd7e029f6aa80cbb0bc`。构建探针时把该文件放入 `assets`，与 [回放 Activity](VerifyPocketAndroidReplay.kt) 及生产的 `Cv10PocketImu`、`Cv10PocketHeadLink` 和 `Cv10PocketHeadLinkService` 一同打包。

未修改的 Pocket XR 1.6.28 在 3840×1080、60 Hz 的测试显示上运行 `SpaceActivity`。原版连接了本机 VPN 的 52998 和 52999 端口，报告 1000 Hz，融合结果为 yaw 约 0.0°、pitch −0.23°、roll −0.14°，与电脑端的静置记录一致。模拟器端收到的是 Fold7 的录制样本。验收 Fold7 上的实时连接及实物转动仍需要可用的本机头踪授权和佩戴转头测试。[原版应用与回放日志](evidence/android16-pocket-fold7-imu-replay.txt)

## 实时 Fold7 采样送入原版 Android 应用

2026-11-27 15:01–15:04 香港时间。Fold7 保持 C9.53.6 原厂线、外接 5 V、三星外显镜像和固定镜位。从 Monado 2D 列表打开原版 Khronos `hello_xr` 并进入 `FOCUSED`，由 `54992ee` 加回环诊断补丁构建的 ARM64 调试包读取 CV10 HID。调试包 SHA-256 为 `5347fa3074079a7096cdbf071e9afb9ddca01b21ba9a49afbddff63416cea71c`，通过 APK 签名与 16 KiB ZIP 对齐检查。[CV10 回环诊断补丁](pocket-head-live-loopback-diagnostic.patch)

用临时 ADB 转发将 Fold7 的 `127.0.0.1:52998` 连到电脑。从这个端口读到的 TCP 数据，经隔离的 ADB 服务器写入 Android 16 API 36 模拟器上的回环端口。模拟器里的探针将报文中的设备时间和六轴采样值送入同提交的 `Cv10PocketImu`，再由原样的 `Cv10PocketHeadLink` 和 `Cv10PocketHeadLinkService` 提供本机 VPN 服务。探针 APK SHA-256 为 `658c213bab9174ace2fbd056133a8e121314f74c6f94e28cd887e42b52a1f528`。模拟器的 Wi-Fi 和移动数据均关闭，Shizuku 未运行。未修改的 ARM64 Pocket XR 1.6.28 在 3840×1080、60 Hz 的虚拟屏上运行。[实时输入探针](VerifyPocketAndroidLive.kt)

26 秒内传送 26,261 个完整采样包。Pocket XR 自行发现 VPN 地址，打开 IMU 和控制端口，两个相隔十秒的统计窗口均报告约 1002 Hz。静置 yaw 约 0.0°，pitch −1.32° 到 −1.33°，roll +1.76° 到 +1.77°，与同轮重力估计的倾斜误差不超过 0.04°。跨设备转发会影响时延，读数用于验证实时数据接入和姿态融合。Fold7 的本机 VPN 未开启，实机同一设备上的连接及佩戴头动仍待验收。

退出后 Fold7 完成 `xrDestroyInstance`，关闭回环监听并移除 ADB 转发。手机恢复正式整合预览和 **Monado CV10 Preview** Broker，既有 Shizuku 进程、授权和最低刷新率保持，回到桌面。模拟器关闭前确认 VPN 地址已移除。[实时传输、原版融合与清理日志](evidence/android16-pocket-fold7-live-imu.log)
