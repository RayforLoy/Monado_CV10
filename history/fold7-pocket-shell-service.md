# Fold7 上的 Pocket XR 虚拟显示服务

2026-11-27 04:15（香港时间），在 Galaxy Z Fold7 SM-F9660（Android 16）上测试。CV10 经 C9.53.6 原厂线和 5 V 辅助供电连接，三星外接显示为 **已镜像**，活动时序为 3200×1600、90 Hz。

- Monado 预览源码 `9ca6805`，预览 APK SHA-256 `b1342958abb955c9a3a494455ebe99f75f8ec371ec93ec6966acac22a67f4a1e`。
- 未修改的 Pocket XR v1.6.28，APK SHA-256 `81cf6b61d9f80a20e1809275a72f0dc0008180aa8d8946aa7b830e78e1de4337`。

[探针](PocketShellProbe.java) 通过既有无线 ADB，在 shell 进程从已安装的预览 APK 加载实际的 `Cv10PocketXrDisplayService`。先用预览包的 Context 创建一个服务实例，确认它拒绝 shell 作为调用方，再用 Android 自带的 shell Context 在探针内新建服务实例，以探针自己的 Surface 和 Binder 令牌检验该服务。没有改动预览 APK 的 UID 校验或手机授权。

| 模式 | 虚拟显示 | Fold7 结果 |
| --- | --- | --- |
| Pocket XR 空间 | 123，3840×1080，60 Hz，`FLAG_TRUSTED` | 原版 `SpaceActivity` 启动，左右眼都有 Hello XR 占位面板，提示 Pocket XR 尚未获得 Shizuku 应用授权。[源帧](evidence/fold7-pocket-shell-space.png) |
| 左右眼测试卡 | 124，3840×1080，60 Hz，`FLAG_TRUSTED` | 原版测试卡左右标记完整。[源帧](evidence/fold7-pocket-shell-card.png) |
| 电脑屏幕 3D | 125，1920×1080，60 Hz，`FLAG_TRUSTED` | 系统成功启动原版默认 `PcScreenActivity`。手机没有配对这次测试的主机，原版开始搜索，观察 5.5 秒未收到视频帧。 |

三个模式都忽略错误的停止令牌，并在正确令牌传入后释放显示屏。最后查询系统没有残留的虚拟显示屏。[探针与系统日志](evidence/fold7-pocket-shell-service.log)

源帧从虚拟屏直接采样，光学输出见已有的 [双目固定对照](fold7-pocket-world-anchor.md) 和 [Sunshine Half SBS 对照](fold7-pocket-pc-sbs.md)。仍需实测从 Monado 界面经 Shizuku 绑定服务后的一键开流，以及原版 Pocket XR 获得自己的授权后向空间面板投射应用。此次保留已有 Wi-Fi 和权限。

提交 `42248dd` 在电脑屏幕模式已建屏、尚未收到画面时，显示等待视频与去手机端配对的提示。该预览包 SHA-256 为 `ff752f8c919201f42402e3220ef77f256194a59aba1e59ba3cbfadf17471a887`，与上一个预览使用同一签名，16 KiB ZIP 对齐及五个 ARM64 库的 15 个 `PT_LOAD` 段通过检查。第一次启动复用了旧的 Hello XR 进程，未进入 `FOCUSED`。停止旧进程后从内置 2D 启动器冷启动，CV10 右镜拍到蓝色方块。系统返回键使会话进入 `STOPPING`，`xrDestroyInstance` 完成。测试保留新预览，Broker 恢复为稳定的 Monado CV10。[启动和退出日志](evidence/fold7-pocket-pc-wait-hello.log) · [右镜实拍](evidence/fold7-pocket-pc-wait-hello-right.jpg)

## 复测

用 JDK 17 和 Android SDK 构建探针。将 `$serial` 指向本机 Fold7，保持 Android 调试已经授权。探针需要安装 Monado 预览和原版 Pocket XR。

```powershell
$platform = "$env:ANDROID_HOME/platforms/android-35/android.jar"
$d8 = "$env:ANDROID_HOME/build-tools/36.0.0/d8.bat"
javac --release 11 -cp $platform history/PocketShellProbe.java
& $d8 --min-api 34 --lib $platform --output pocket-shell-probe.jar history/PocketShellProbe.class
adb -s $serial push pocket-shell-probe.jar /data/local/tmp/pocket-shell-probe.jar
adb -s $serial shell chmod 444 /data/local/tmp/pocket-shell-probe.jar
adb -s $serial shell 'CLASSPATH=/data/local/tmp/pocket-shell-probe.jar app_process / PocketShellProbe 1 /data/local/tmp/pocket-shell-card.png'
adb -s $serial pull /data/local/tmp/pocket-shell-card.png
adb -s $serial shell rm -f /data/local/tmp/pocket-shell-probe.jar /data/local/tmp/pocket-shell-card.png
```

把模式 `1` 改为 `0` 可测普通空间，改为 `2` 可测默认电脑屏幕。电脑屏幕收到视频后才会保存图像。
