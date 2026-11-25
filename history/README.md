# Monado CV10 Android 运行时

本目录的 `cv10.patch` 适用于 Monado 提交 `ce2b721eb4ffcbd726fb88357625f55871a8f262`。定制运行时包名为 `org.cv10.monado.out_of_process`，可与官方 `org.freedesktop.monado.openxr_runtime.out_of_process` 共存。在 Khronos OpenXR Runtime Broker 中选择 **Monado CV10** 使用定制版，选择 **Monado XR** 恢复官方版。[Monado 固定源码](https://gitlab.freedesktop.org/monado/monado/-/tree/ce2b721eb4ffcbd726fb88357625f55871a8f262)

v0.1.0 发布的是 MAILBOX 版，启动器与 Broker 均使用 **Monado CV10**。从固定上游提交依次应用 `cv10.patch`、`framevote90.patch`、`primary-mailbox.patch`、`launcher-name.patch` 后构建。最后一个补丁让启动 Activity 继承应用名。本地已安装产物为 `resources/Monado-CV10-primary-mailbox-named-arm64.apk`，SHA-256 为 `ee7a8f1d7e4124a381d32a4cef13aac36c19e5b8f3a98961b81c104b392da41c`。与上一版 MAILBOX 安装包对照，只有 AndroidManifest.xml 的内容发生变化。

后续 [Shizuku 120 Hz 测试版](shizuku-120.md) 已在 SM-F9660 上验证。手机端启动 Shizuku 并授权后，直接打开 OpenXR 应用即可提高手机主屏刷新率。

定制 HMD 驱动读取 `12d1:107e` 的头显 IMU，向 OpenXR 提供旋转姿态。头显未连接、USB 授权缺失或报告中断时，应用应检查返回的姿态有效标志。外屏路径按名称识别 `HUAWEIAV03`，使用 3200×1600 左右两个视区及相反的逐眼旋转。当前对称视场和镜间距为开发用参数，光学畸变与个体标定仍待测量。

## 从固定源码构建

在 Windows 工作站上准备 Git、JDK 17、Python 3.10 或更新版本、可在 PATH 中调用的 `glslangValidator`，以及 Android SDK Platform 35、Build Tools 34.0.0、NDK 26.3.11579264 和 CMake 3.22.1。本次使用 Eigen 5.0.0 的 CMake 安装目录及 glslang 16.6.0。设置 `JAVA_HOME` 指向 JDK，`ANDROID_HOME` 指向 SDK，`CV10_EIGEN_CMAKE_DIR` 指向包含 `Eigen3Config.cmake` 的目录。[Eigen](https://gitlab.com/libeigen/eigen/-/tree/5.0.0) · [glslang](https://github.com/KhronosGroup/glslang)

从本项目根目录的 PowerShell 执行：

```powershell
$patch = (Resolve-Path ports/monado-cv10/cv10.patch).Path
$checkout = '.research/monado-cv10-rebuild'
$revision = 'ce2b721eb4ffcbd726fb88357625f55871a8f262'
git init $checkout
git -C $checkout fetch --depth 1 https://gitlab.freedesktop.org/monado/monado.git $revision
git -C $checkout checkout --detach FETCH_HEAD
git -C $checkout apply --check $patch
if ($LASTEXITCODE) { throw 'Patch does not match the checkout' }
git -C $checkout apply $patch
$sdk = $env:ANDROID_HOME.Replace('\','/')
$eigen = $env:CV10_EIGEN_CMAKE_DIR.Replace('\','/')
$python = (Get-Command python).Source.Replace('\','/')
@("sdk.dir=$sdk", "eigenCMakeDir=$eigen", "pythonBinary=$python") |
    Set-Content -Encoding ascii "$checkout/local.properties"
Push-Location $checkout
try {
    .\gradlew.bat --max-workers=6 -Pandroid.injected.build.abi=arm64-v8a :src:xrt:targets:openxr_android:assembleOutOfProcessDebug
    if ($LASTEXITCODE) { throw 'Gradle build failed' }
    Get-ChildItem src/xrt/targets/openxr_android/build/intermediates/apk/outOfProcess/debug/*.apk
} finally {
    Pop-Location
}
```

Gradle 使用本机 Android 调试签名。检查产物时，使用 SDK Build Tools 的 `apksigner.bat verify --verbose --print-certs` 和 `aapt.exe dump badging`，并确认 APK 内有 `lib/arm64-v8a/libopenxr_monado.so` 与 `lib/arm64-v8a/libmonado-service.so`。首次安装选择新的 Broker 条目；后续更新同一包名时应使用同一签名。

2026-11-25 构建通过 Gradle 组合任务及 APK v2 签名验证，独立文件保存在本机 `resources/Monado-CV10-arm64.apk`。逐眼旋转对照版为 34,760,200 字节，SHA-256 为 `b697c76fc273e9716269918a0680ed4e68a6da28fbadcff30e72ff8742d7fc6b`。测试签名证书 SHA-256 为 `3f110dfc8ba9bbe99f454e60679216cf12f66388b27c08cbc5d7cf51d542e684`。

Java IPC 的 CONNECT action 与合并后的 Manifest 一致。原生客户端通过 CMake 生成的 XRT_ANDROID_PACKAGE 定位服务和加载运行时类。首轮 APK 的这个值仍指向官方包，实机在绑定服务时失败。修复版已从打包的两份原生库的 .rodata 区逐一核实定制包名，并核实不含旧包名。

逐眼旋转对照版使用 `u_device_rotation_left` 处理视图 0，使用 `u_device_rotation_right` 处理视图 1。上一版的两项设置相反，SHA-256 为 `4faa15e81c7585158deb8adf4928050be4404c9eacff6a785cdc26c87bec029c`。两个 APK 的 ZIP 条目经逐一比较，变化限于 `libmonado-service.so`。视差方向及佩戴效果以同一标记的新旧实机对照为准。

### 90 Hz Surface 帧率请求候选

在前述 `cv10.patch` 之后附加 `framevote90.patch` 再构建，可生成独立保存的候选 `resources/Monado-CV10-framevote90-arm64.apk`，本次 SHA-256 为 `2a1476d90e7e317dc32105b3851ec2285334205d25c45c49c48cb7e5647ef63b`。该 APK 与基线同包同签名，覆盖安装后可用原 APK 切回。API 30 起在 `HUAWEIAV03` 创建 Surface 时向系统请求固定来源的 90 Hz；API 31 起传入 `CHANGE_FRAME_RATE_ALWAYS`。服务窗口和应用的 Presentation 均经过该回调。日志中的 `CV10 Surface.setFrameRate requested 90 Hz` 只确认请求已提交，实际刷新率和出帧间隔须在设备上测量。

### 手机主屏与 MAILBOX 候选

按 `cv10.patch`、`framevote90.patch`、`primary-mailbox.patch` 顺序叠加构建。产物为 `resources/Monado-CV10-primary-mailbox-arm64.apk`，本次 SHA-256 为 `49cf9fa8020132b0503186a15c78ba45d0bde0471bd4c7beaefff3898d65df2c`。与上一候选相比，只有 `classes5.dex` 和 `libmonado-service.so` 的内容变化。

CV10 的应用 Presentation 成功显示时，运行时针对 OpenXR Activity 的默认手机显示器请求同尺寸最高刷新率，同时设置窗口的 `preferredDisplayModeId`、`preferredRefreshRate`。在 API 35 及以上，Activity 内容视图也请求 HIGH。最后一个该窗口的 Presentation 关闭时，运行时释放请求并恢复此前的属性。服务自行创建外屏浮层时不会请求手机窗口。两种外屏路径均使用适合交互式 XR 的 `FRAME_RATE_COMPATIBILITY_DEFAULT` 提交 90 Hz 请求。

当定制 CV10 驱动运行时，Vulkan 合成器在 `XRT_COMPOSITOR_PRESENT_MODE` 未设置或为空字符串时优先 MAILBOX。显式设置沿用 Monado 的既有解析，设备不支持所选模式时尝试 FIFO。可检查 `CV10 primary window requested`、`CV10 primary window released` 与 `VkSwapchainCreateInfoKHR` 的 `presentMode` 区分窗口请求、释放和实际呈现模式。

### 手机窗口持续绘制对照

在上述三个补丁之后附加 `primary-pulse.patch`。候选 `resources/Monado-CV10-primary-pulse-arm64.apk` 的 SHA-256 为 `8bd48c5b4e81c384c1741f3a0de827dcec3817ca47428b577b7a604ee057f973`。该候选在 CV10 Presentation 所属的手机窗口内容区添加 1×1 像素的视图，通过 `postInvalidateOnAnimation` 在可见时更新该像素。最后一个 Presentation 关闭会删除该视图。可用 `CV10 primary 1px pulse attached` 和 `CV10 primary 1px pulse removed` 观察其生命周期；显示时序需实测。

## Fold7 与 CV10 双眼实机验收（2026-11-25）

使用普通 C-C 线、Fold7 的三星「已镜像」显示模式及两台固定摄像头。Khronos Broker 选中 Monado CV10。样例为 Khronos OpenGLES `hello_xr` 1.1.65。开始和结束时分别从手机拉取已安装的 base.apk，SHA-256 均为 `29ae01ef6a4fea41d67816f7051698f2694ab08ced4c16c02bdd978e62975962`。最终测试的运行时 APK SHA-256 为 `b697c76fc273e9716269918a0680ed4e68a6da28fbadcff30e72ff8742d7fc6b`。此调试 APK 在 Fold7 上使用 `adb install -t -r` 安装。

| 项目 | 实测结果 |
| --- | --- |
| 开屏和输出 | 定制运行时获得 USB 访问许可，PCUI 返回开屏 OK。外屏 Presentation 选中 HUAWEIAV03，活动模式 3200×1600 @ 90 Hz。该值是面板模式，未测量应用持续帧率。 |
| OpenXR 会话 | 原版样例枚举到 Huawei VR Glass CV10 (USB IMU)，旋转跟踪为真，位置跟踪为假，双眼交换链创建成功，会话进入 FOCUSED。[日志](https://github.com/RayforLoy/HUAWEI_CV10_EVO/blob/c77ab9f/research/evidence/fold7-monado-cv10-session.log.txt) |
| 镜片眼别和方向 | 探针 Canvas 左 270°、右 90°。两台摄像头分别拍到蓝色 L↑ 和橙色 R↑，字母正读。[左镜片](https://github.com/RayforLoy/HUAWEI_CV10_EVO/blob/c77ab9f/research/evidence/fold7-cv10-upright-left.jpg) · [右镜片](https://github.com/RayforLoy/HUAWEI_CV10_EVO/blob/c77ab9f/research/evidence/fold7-cv10-upright-right.jpg) |
| 样例实际出图 | 在同一摄像头位置换为官方样例，两个镜片均拍到中央蓝色方块。左摄像头广角镜头的背景反光较强。[左眼实拍](https://github.com/RayforLoy/HUAWEI_CV10_EVO/blob/c77ab9f/research/evidence/fold7-monado-cv10-left.jpg) · [右眼实拍](https://github.com/RayforLoy/HUAWEI_CV10_EVO/blob/c77ab9f/research/evidence/fold7-monado-cv10-right.jpg) |
| 立体视差 | 将外屏截图按中央分为两个 1600×1600 视图，左图顺时针 90°、右图逆时针 90°还原佩戴方向。用 B>220、G<30、R<30 求中央方块像素重心。旧版左 x=784、右 x=815。最终版左 x=815、右 x=784，两者 y=799.5，视区中心为 (799.5,799.5)。前方居中物体呈正确的水平视差。[最终外屏原图](https://github.com/RayforLoy/HUAWEI_CV10_EVO/blob/c77ab9f/research/evidence/fold7-monado-cv10-surface.png) |
| 眼镜姿态 | USB 端点 0x84 连续送入眼镜设备时钟与 IMU 样本，最终版本日志包含采集与融合四元数。转动对照使用上一个仅视图旋转不同的运行时，约 15:13:11 至 15:13:21，四元数夹角约 21.5°，角速度峰值 0.60 rad/s，外屏边缘场景随之改变。中央方块在样例中锁定于头部前方 2 米，不能用它判断是否发生头动。实验没有独立记录手机转动时间或手机陀螺仪。[姿态日志](https://github.com/RayforLoy/HUAWEI_CV10_EVO/blob/c77ab9f/research/evidence/fold7-monado-cv10-motion.log.txt) · [Khronos 样例源码](https://github.com/KhronosGroup/OpenXR-SDK-Source/blob/release-1.1.65/src/tests/hello_xr/openxr_program.cpp) |

此验收覆盖该样例的 3DoF 头显链路。镜片畸变、个体瞳距和三轴角度精度仍待光学校准。

## Fold7 转头拖影与帧时序复测

本次保留的运行时为 `Monado-CV10-primary-mailbox-arm64.apk`。在基线补丁之后依次应用 `framevote90.patch` 和 `primary-mailbox.patch`。CV10 使用 Vulkan MAILBOX；Fold7 的两台摄像头、官方 hello_xr、IMU、每眼 2240×2240 交换链及外屏 3200×1600 @ 90 Hz 与验收配置一致。

| 实测配置 | 外屏图层不同呈现时间戳/秒 | 图层就绪到呈现中位数 | 第 95 百分位 |
| --- | ---: | ---: | ---: |
| 原验收版 FIFO，手机 60 Hz | 60.32 | 27.5 ms | 31.2 ms |
| 修复版 MAILBOX，系统自行选择手机刷新率 | 60.31 | 11.0 ms | 14.7 ms |
| 修复版 MAILBOX，会话期间手机最低 120 Hz | 89.95 | 10.3 ms | 12.6 ms |

数据来自 `dumpsys SurfaceFlinger --latency` 对应的外屏 BLAST SurfaceView。呈现频次按不同实际呈现时间戳计算，等待时间只采用就绪时间戳有效的帧。复测包含两次会话启动。手机屏幕是系统合成的主时钟；外屏窗口独立请求 90 Hz、手机窗口请求 120 Hz、主屏逐帧绘制一个像素的对照，均未单独解除 60 Hz 限制。120 Hz 主时钟下，图层呈现间隔仍在约 8.3 / 16.6 ms 之间变化。此测量没有覆盖面板像素发光时间及完整运动到成像延迟。[各轮采样](https://github.com/RayforLoy/HUAWEI_CV10_EVO/blob/c77ab9f/research/evidence/fold7-cv10-frame-pacing.json)

在官方 hello_xr 已经显示、眼镜已连接时，从 Windows PowerShell 启动本次会话的辅助脚本。将设备序列号换成 `adb devices` 中 Fold7 的序列号。

```powershell
$serial = '192.168.50.38:42505'
adb -s $serial push ports/monado-cv10/cv10-hello-refresh.sh /data/local/tmp/cv10-hello-refresh.sh
adb -s $serial shell "nohup sh /data/local/tmp/cv10-hello-refresh.sh >/data/local/tmp/cv10-refresh.log 2>&1 </dev/null &"
```

脚本仅在当前 hello_xr 会话临时将手机系统 `min_refresh_rate` 设为 120 Hz。退出样例、拔掉眼镜或结束运行时后，脚本会在下一次轮询恢复原值；轮询间隔为三秒。已实测退出样例后恢复。手机突然断电会中断清理，再运行脚本会先恢复原值；也可以执行 `adb -s $serial shell sh /data/local/tmp/cv10-hello-refresh.sh restore` 清理上次异常退出的状态。再次打开样例时需重新启动此辅助脚本。单独安装 APK 即可使用 MAILBOX。

佩戴者在当前修复配置下复测，反馈「转头拖影明显减少」。本轮没有用高速摄像测量光学残影长度。
