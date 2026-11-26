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

## 眼镜就绪和手机前台对照

2026-11-27 04:49 至 04:51（香港时间），沿用同一设备、原版 APK 和电池 **优化**，预览诊断 APK 的 SHA-256 为 `25e0f134f17f7127b780e25a2e2474a4acfaa2ec9766cb360c290ddada3d8a1c`。仍由 ADB 放置诊断画面和原版样例，通过现有 **打开手机控制** 入口打开原版的 `TrackpadActivity`。手机保持触控板，Monado 和原版 Pocket XR 各自有前台服务，原版日志确认眼镜就绪服务已启动。[原版眼镜就绪服务](https://github.com/pocket-xr/pocket-xr/blob/v1.6.28/app/src/main/kotlin/com/hayeco/flipspaces/GlassesReadyService.kt)

| 样例的手机端窗口 | 游戏层运行 | 返回和释放 |
| --- | --- | --- |
| 独立诊断虚拟屏 136 | 右镜有蓝块。首个游戏缓冲区出现后 455 毫秒，Freecess 冻结 Pocket XR。 | 按返回后，系统解冻 Pocket XR，样例进入 `STOPPING`。右镜回到 Pocket XR 的应用占位窗口。加载器完成 `xrDestroyInstance` 并卸载原版运行时，7 毫秒后样例线程 `SIGSEGV`。[右镜序列，每秒两帧](evidence/fold7-pocket-stock-foreground-right.jpg) |
| 默认屏的弹出视图 | 原版触控板仍在手机上，首个游戏缓冲区出现后 460 毫秒，Freecess 冻结 Pocket XR。三次读取同一 SurfaceView 的帧编号依次为 23、29、29。 | 按返回后未取得正常退出轨迹。原版服务在 `ServiceANR` 解冻后交还游戏层。 |

`readelf -d` 显示原版 1.6.28 的 `libopenxr_monado.so` 未设 `NODELETE`，CV10 预览包含 `4fe230f` 的 `NODELETE` 修复。卸载后的崩溃时序与此前 CV10 的卸载问题一致。本轮原版崩溃缺少可用的符号化回溯，尚不能确认同一根因。两种窗口放置均无法消除 Freecess。结束后重新恢复稳定 Broker、原电池设置和生产预览，保留原有权限与接线。[前台与退出对照日志](evidence/fold7-pocket-stock-foreground.log)

## 游戏虚拟屏与手机前台对照

2026-11-27 05:09 至 05:22（香港时间）。沿用同一 Fold7、接线、原版 Pocket XR 和 `hello_xr`，Broker 选中 **Pocket XR**，测试期间原版的三星电池选项为 **优化**。外层用自身的 Monado CV10 运行时，原版空间仍在预览创建的 3840×1080 虚拟屏。单独调整的是样例 Activity 所在的 1280×720、60 Hz 游戏虚拟屏。

先在原诊断包加入 `BIND_IMPORTANT`，仅向原版公开的运行时服务发起发现绑定。系统登记 `CR IMP`，原版回应空 Binder，未因此建立游戏图层。样例首个游戏缓冲区出现后 378 毫秒，三星仍冻结原版进程，随后也冻结外层预览。该绑定只用于对照。此诊断包和下表的原诊断包解包后只有 DEX 不同，SHA-256 为 `d6e45613012c0140d700b05141f7f30a4a4e7fb20c5268d8b0af3414b63fcb4d`。[服务绑定和冻结日志](evidence/fold7-pocket-bound-service.log) · [Android 绑定标志](https://developer.android.com/reference/android/content/Context#BIND_IMPORTANT)

下表的 APK 都和 04:49 的原诊断包相同，SHA-256 为 `25e0f134f17f7127b780e25a2e2474a4acfaa2ec9766cb360c290ddada3d8a1c`。通过已授权的 ADB 在新屏上启动同一原版样例。记录 SurfaceFlinger 游戏图层的连续五次帧编号。

| 游戏屏配置 | 手机主屏窗口 | Android 顶层焦点 | 原版游戏图层帧编号 |
| --- | --- | --- | --- |
| Shell 创建，受信任，独立焦点，禁止抢占顶层焦点 | Pocket XR 触控板 | 主屏 0，游戏在屏 146 内获得焦点 | 11、118、223、325、427 |
| Shell 创建，受信任，普通焦点 | Pocket XR 触控板 | 游戏屏 149 | 13、117、216、322、427 |
| Shell 创建，公开，普通焦点 | Pocket XR 触控板 | 主屏 0 | 12、113、220、324、430 |
| Monado 诊断包创建，公开，普通焦点 | Pocket XR 触控板 | 主屏 0 | 15、119、224、321、421 |
| Shell 创建，受信任，独立焦点，禁止抢占顶层焦点 | Monado 预览页 | 主屏 0，游戏在屏 157 内获得焦点 | 17、29、29、29、29 |

五轮样例均进入 `FOCUSED`。手机主屏显示 Pocket XR 触控板的四轮在约九秒的观察窗内持续出帧。使用独立焦点屏的一轮，右镜录像可见蓝色立方体，物理外显录屏只留下空间占位画面。手机主屏显示 Monado 预览页的一轮，首帧后原版进程被 Freecess 冻结。创建方和显示焦点标志都无法单独解释该故障，早前原版触控板已打开时也曾冻结。持续出帧的轮次中，按返回后原版移除游戏图层。独立焦点轮中，加载器完成 `xrDestroyInstance` 并卸载原版运行时，6 毫秒后样例线程 `SIGSEGV`。[右镜画面](evidence/fold7-pocket-own-focus-right.jpg) · [显示与游戏层日志](evidence/fold7-pocket-own-focus.log)

第六轮让 Monado 预览页保留在手机主屏，样例所在的屏 160 保持独立焦点。游戏图层的帧编号由 14 升至 27 后停住。点按 Monado 的 **打开手机控制**，手机主屏切换到原版 Pocket XR 触控板。同一游戏图层继续提交帧 58、125、196、270、344，样例进程和外层 OpenXR 会话均未重启。本轮的恢复依据是图层缓冲区与系统日志，右镜录像在点按前结束。退出后恢复稳定 Broker、原电池设置和生产预览，保留现有 Shizuku、VPN 和 Wi-Fi 状态。[显示与游戏层日志](evidence/fold7-pocket-own-focus.log)

### 复测游戏虚拟屏

在保持现有授权的设备上，将 [Java 探针](Cv10PocketFocusProbe.java) 编译为 DEX。它只运行在 ADB shell 下，定时或收到停止文件后释放虚拟屏。

```powershell
$platform = "$env:ANDROID_HOME/platforms/android-35/android.jar"
$d8 = "$env:ANDROID_HOME/build-tools/36.0.0/d8.bat"
javac --release 11 -cp $platform history/Cv10PocketFocusProbe.java
& $d8 --min-api 34 --lib $platform --output cv10-pocket-focus-probe.jar history/Cv10PocketFocusProbe.class
adb -s $serial push cv10-pocket-focus-probe.jar /data/local/tmp/cv10-pocket-focus-probe.jar
adb -s $serial shell chmod 444 /data/local/tmp/cv10-pocket-focus-probe.jar
adb -s $serial shell 'CLASSPATH=/data/local/tmp/cv10-pocket-focus-probe.jar app_process / Cv10PocketFocusProbe /data/local/tmp/cv10-pocket-focus-probe.stop own-focus'
```

最后一个参数可设为 `trusted` 或 `public`。探针打印屏 ID，在另一终端用 `am start --display <ID>` 选择游戏所在的屏。先关闭游戏，再用 `touch /data/local/tmp/cv10-pocket-focus-probe.stop` 释放显示屏，最后删除临时 JAR 和停止文件。[Android 16 虚拟屏标志](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/hardware/display/DisplayManager.java)
