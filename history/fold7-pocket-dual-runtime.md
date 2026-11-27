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

## 从 Monado 手机端打开原版 OpenXR 样例

2026-11-27 05:30 至 05:32（香港时间），沿用同一台 Fold7、CV10 和 53 版原厂供电线。Monado 源码 `4faeeaf` 新增 **在 Pocket XR 中打开 OpenXR 应用**。预览 APK SHA-256 为 `bf477ed3cc992f4d6e49c8c76376c7f72b78fb77e6a8dd0fd6578e348db327cb`，签名、APK 的 16 KiB 对齐及五个 arm64 ELF 库的加载段对齐均已核对。

诊断 APK SHA-256 为 `1719bb1b5ad6deccbca6bec9d6c6a601aa8e3353e09050e753e2382443928cee`。沿用 ADB 授权放置原版 Pocket XR 空间，并用上一节的 shell 探针提供游戏虚拟屏。应用列表、Broker 检查、应用启动和切换手机控制均走预览 APK 的实现。正式入口通过预览自己的 Shizuku UserService 管理两块屏，这一段仍需在预览包获得授权后串联验收。[诊断 APK 的显示传输补丁](evidence/fold7-pocket-client-entry-transport.patch)

- Broker 选择 **Monado CV10** 时，选中样例显示选择 **Pocket XR** 的提示。改选 **Pocket XR** 后，从 Monado 手机列表点选 **Hello XR (OpenGL ES)**。系统确认是预览包在屏 164 启动样例，主屏同时显示原版触控板。外层继续使用内置 Monado，样例由 Broker 加载原版 Pocket XR 运行时。
- 样例进入 `FOCUSED`，游戏图层采样序号为 37、109、180、252，右镜拍到蓝色方块。对游戏屏发送返回后，原版空间收回游戏层，随后原版样例线程再次收到 `SIGSEGV`，与前几轮原版卸载时的崩溃一致。[筛选日志](evidence/fold7-pocket-client-entry.log) · [右镜实拍](evidence/fold7-pocket-client-entry-right.jpg)
- 也在 ADB shell 中独立运行本次构建的原样显示服务代码。Fold7 成功创建原版空间与应用屏，验证令牌不匹配时屏幕继续存在、重开替换旧应用屏、单独关闭应用屏时保留空间，以及停止外层后释放两块屏。复测时将探针与 `Cv10PocketFocusProbe.java` 一起编译，并将本次 Gradle 构建的 `Cv10PocketXrDisplayService*.class` 与 `ICv10PocketXrDisplay*.class` 一起打成 DEX。这个探针通过 shell 直接调用服务，本轮未经过 Shizuku 的应用授权和跨进程绑定。[探针源码](Cv10PocketClientDisplaySmoke.java) · [服务实测日志](evidence/fold7-pocket-client-display-service.log)
- 测试结束后，恢复稳定 **Monado CV10** Broker、原版 Pocket XR 的 **受限制** 电池模式与原预览 APK。最低刷新率为 `null`，两块诊断虚拟屏均已释放。

## 返回预览并重新打开原版样例

2026-11-27 05:39（香港时间），同一 Fold7 和原版 Pocket XR 1.6.28，沿用上节的 ADB 显示传输。源码 `2d79440` 使 Monado 菜单和通知都回到已有的预览 Activity。预览 APK SHA-256 为 `54a107e78d6e236670e197a5eb43363016b05cb3d6ae64f47fa637e347cb8d0b`，诊断 APK SHA-256 为 `46a6a9944d9ea3aefca870f6d4fa807c1b6bf8d22c23b177478350ed068ab454`。两包均完成签名与 16 KiB APK 对齐校验。

第一次从 Monado 点选原版 `hello_xr` 后，样例进入 `FOCUSED`，右镜拍到蓝色方块。对应用屏发送返回后，原版空间收回游戏层，样例线程在 Android 的 `__cxa_thread_finalize` 中崩溃。随后从手机重新打开 Monado，按返回键关闭系统通知授权提示，从 **更多选项 → Pocket XR 外显预览** 回到同一个预览，PID 仍为 802，空间仍在显示屏 171。重新点选样例得到新 PID 2892，再次进入 `FOCUSED`，右镜再次出画。第二次退出仍复现相同的堆栈。实验结束后恢复稳定 Broker、原预览 APK、原电池选项和最低刷新率。[两轮日志](evidence/fold7-pocket-reentry.log) · [第二次启动的右镜实拍](evidence/fold7-pocket-reentry-right.jpg)

比较官方 1.6.28 与 1.6.31 APK，两版的 arm64 `libopenxr_monado.so` 都是 2,048,592 字节，SHA-256 均为 `d514e935c5f9123660b4c775dc13846ef979c54e3b71e5003bc28257f99e37ca`，ELF `DT_FLAGS_1` 均只有 `NOW`。两个 APK 的发布签名相同。Fold7 仍保留 1.6.28，1.6.31 的结论来自发布包静态比较。[Pocket XR 1.6.31 发布页](https://github.com/pocket-xr/pocket-xr/releases/tag/v1.6.31)

## 源码版卸载修复对照

在 Monado `ee03722885e777462708841223b60affe6873ac2` 依次应用 Pocket XR 1.6.31 源码中的 `app/src/main/cpp/monado/monado-pocketxr.patch` 和 [Android 链接修补](evidence/pocketxr-monado-android-nodelete.patch)。修补使用 Monado CV10 `4fe230f` 已实测的 `-z,nodelete`，在线程清理期间保留运行时的映射。以 Android NDK 26.3.11579264、CMake 3.22.1 和 Ninja 构建 `openxr_monado`，使用以下配置，另设 Eigen 路径并让 CMake 找到 `glslangValidator`。[Pocket XR 原生构建文档](https://github.com/pocket-xr/pocket-xr/blob/v1.6.31/docs/OPENXR.md#building-monado)

```text
-DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=26 -DANDROID_STL=c++_static
-DBUILD_TESTING=OFF -DCMAKE_BUILD_TYPE=Release -DXRT_FEATURE_SERVICE=ON
-DXRT_ANDROID_PACKAGE=com.hayeco.flipspaces
-DCMAKE_MODULE_LINKER_FLAGS=-Wl,-z,max-page-size=16384
-DGIT_DESC=v25.1.0-1070-gc865e3f57
```

初次构建使用默认 `ee03722`，成功绑定原版 Pocket XR 服务并取得 3840×1080 游戏面，但服务的版本比对拒绝了客户端。上游导出的补丁列出了发布库对应的 `c865e3f57`，补丁未修改 `ipc/shared`、`ipc/client` 或 `ipc/server` 的协议实现。上述 `GIT_DESC` 使用原版服务报出的客户端协议版本，Monado 的运行时版本检查保持启用。`llvm-readelf` 确认重建客户端的 `FLAGS_1` 为 `NOW NODELETE`，三个 ELF 加载段均为 `0x4000`。经 `llvm-strip --strip-unneeded` 的库 SHA-256 为 `bc344d1eace98fadcaff38e665c55ec11161f9c3b67fb5b65e528b0c68ddd153`。

[探针清单](pocket-client-probe/AndroidManifest.xml)与[发现服务源码](pocket-client-probe/RuntimeDiscoveryService.java)向 Broker 公告诊断客户端。用 `javac --release 11` 和 Android 35 的 `android.jar` 编译服务，再用 `d8 --min-api 26` 将服务和 Pocket XR 1.6.31 `app/libs/monado/` 下两个 AAR 的 `classes.jar` 以及 `kotlin-stdlib-2.0.0.jar` 编入同一 `classes.dex`。用 `aapt2 link` 生成基础 APK，装入此 DEX 和 `lib/arm64-v8a/libopenxr_monado.so`，随后 `zipalign` 和 `apksigner`。探针没有自己的渲染服务，继续绑定手机上已安装且未修改的 Pocket XR 1.6.28 服务。正式 Pocket XR 入口仍经 Broker 选择原版运行时。

2026-11-27 05:53（香港时间），同一台 Fold7 和 53 版原厂线。源码探针 APK SHA-256 为 `8fc0520c6113fe6c0f2ae43b1b8a6c7542f465fe00ef01f70c73b5b5ff4660e8`。外层 Monado 诊断 APK SHA-256 为 `8b28655c39a59a0fcfa89841a571519823640bdb9a136d76a9c16a2fc9549910`，沿用上一节的 ADB 显示传输，菜单临时识别此诊断包。两次从 Monado 点选未修改的 `hello_xr` 均进入 `FOCUSED`，右镜均拍到蓝色方块。游戏层帧编号分别为 14、85、152 和 20、86、153。两次返回都进入 `STOPPING` 并完成 `xrDestroyInstance`，两段崩溃日志为空。样例两次使用 PID 20809，外层 Monado 维持 PID 19201。结束后恢复稳定 Monado CV10 Broker、原预览 APK 和原电池设置，卸载探针，最低刷新率为 `null`。[卸载实测日志](evidence/fold7-pocket-nodelete.log) · [重进后的右镜实拍](evidence/fold7-pocket-nodelete-right.jpg)

05:54 用同一菜单再做单变量对照。把官方 arm64 客户端复制到单独的诊断 APK，只把 ELF `DT_FLAGS_1` 在 `0x19ccc0` 的值由 `0x01` 改为 `0x09`，加入 `NODELETE`。该诊断 APK SHA-256 为 `c3e4bffb0b0eddb27c2e02720b72516685504637aa342c2c67210d1b4b71e9da`。两次右镜出画、两次正常销毁，重进继续使用 PID 23878。把应用直接放到诊断虚拟屏的早期轮次曾在 `FOCUSED` 后触发三星 Freecess，游戏层停在第 30 帧。菜单路径复测期间持续出帧。卸载修复覆盖线程清理，三星的冻结问题仍可能在其他启动路径出现。[对照日志](evidence/fold7-pocket-nodelete.log)

05:57 至 06:02 用源码版探针运行原版 `hello_xr` 约五分钟，结束前右镜仍有蓝色方块，样例始终使用 PID 27725。运行约 49 秒时，Android 主屏与另一逻辑屏发生旋转。样例销毁旧 OpenXR 实例，约 1.43 秒后自动重建并重新进入 `FOCUSED`。此后间隔约 30 秒读取的游戏图层编号持续增加。结束时，发往原游戏虚拟屏 186 的返回键因没有焦点而被 Android 丢弃。清理日志显示样例当时的焦点窗口在手机主屏 0，脚本随后强制停止了样例。这轮证实旋转后恢复和长时出画，旋转后仍需按实际焦点验证最终退出。[五分钟日志](evidence/fold7-pocket-nodelete-soak.log) · [结束前右镜实拍](evidence/fold7-pocket-nodelete-soak-right.jpg)

## 从 Monado 菜单使用修复客户端

源码 `5b5efa7` 新增 Broker 条目 **Pocket XR (Monado CV10)**，包名为 `org.cv10.monado.pocket_client`。它只提供 OpenXR 客户端，连接仍由原版 Pocket XR APK 提供的渲染服务。Monado 预览验证两份包的签名相同，并识别已核对的官方 Pocket XR 1.6.28 与 1.6.31。两版官方 APK 的 arm64 服务库 SHA-256 均为 `5e188a0b5bab412da2ec59000fce98ef85ca6f0cd884120192279b53040f2a41`。Fold7 本轮使用原版 1.6.28。[客户端构建步骤](../src/xrt/targets/pocket_client_android/README.md)

2026-11-27 06:12（香港时间），同一台 Fold7、CV10 和 C9.53.6 原厂供电线。使用 ADB 诊断显示传输，预览按正式的 Broker 条目、签名和版本判断放行。在 Monado 菜单点选原版 `hello_xr` 两次，均进入 `FOCUSED`，渲染服务两次提供 3840×1080 游戏面，右镜也两次出画。两次返回均完成 `xrDestroyInstance`，同一 PID 9303 重进且无崩溃。预览的 Shizuku 一键开启仍需获得它自己的授权后串联验收。[APK 摘要和会话日志](evidence/fold7-pocket-client-companion.log) · [第二次右镜实拍](evidence/fold7-pocket-client-companion-right.jpg)

06:14 用 ADB 将样例任务从虚拟屏 192 移至主屏 0，复现此前长测观察到的显示旋转、会话重建和焦点迁移。同一 PID 12975 约 0.8 秒内恢复 `FOCUSED`，右镜重新出画。按 `dumpsys window displays` 确认焦点后把返回键发给主屏 0，样例正常进入 `STOPPING` 并销毁第二个实例。此轮验证了焦点移到主屏后的退出，自发迁移的触发条件仍需查明。[焦点和销毁日志](evidence/fold7-pocket-client-companion.log#L38)

06:14 至 06:15 切回普通预览 APK `5b5efa7`，SHA-256 为 `73ff89d035379109d8dd8ba956ca385bd4d893f8b36d74319be5d165c393201d`。在 Broker 选择 **Monado CV10 Preview**，未使用 Shizuku。2D 菜单直接启动原版 `hello_xr`，3D 启动器经底部上滑停留和点按也启动了同一样例。右镜拍到 3D 菜单和两次样例出画，样例两次进入 `FOCUSED`，返回后均完成 `xrDestroyInstance`。复测后恢复稳定版 Broker 和试验前安装的预览包。[会话日志](evidence/fold7-pocket-client-companion.log#L51)

## 正式预览通过 Shizuku 打开 OpenXR 应用

2026-11-27 11:40 至 11:43 香港时间。沿用 Fold7（SM-F9660）、C9.53.6 原厂线、外接 5 V 和 3200×1600 @ 90 Hz 的 CV10 镜像输出。使用 `71b7fd8` 正式预览，APK SHA-256 为 `71f1f6d788df303d9049a96feba5f4ad9fccf2bcf2070985cb41339214cd49d8`。保留 Fold7 上已有的 Shizuku 授权及官方 Pocket XR 1.6.28。Broker 选用同一签名的 **Pocket XR (Monado CV10)** 客户端，APK SHA-256 为 `043691ba64619d31f77f118c79d371b9361cfc785ada8a2b67a2d2db0418746a`。

通过手机上的 **打开 Pocket XR 空间** 创建显示 206 并收到首帧。随后从 Monado 的 **在 Pocket XR 中打开 OpenXR 应用** 两次选择未修改的 `hello_xr`。正式 Shizuku 服务为样例创建显示 209 和 210，Loader 确认使用客户端包。两次分别在 606 和 430 毫秒内进入 `FOCUSED`，右镜都拍到蓝方块。第一轮开始约 35 秒后，原版 Pocket XR 游戏图层的帧编号继续从 2121 升至 2262。第一轮向样例所在屏发送返回，正常完成 `xrDestroyInstance`。第二轮重用样例 PID 22779。手机主屏的返回键由原版触控板转发给空间，样例继续运行。从 Monado 点按 **停止预览** 正常销毁第二个样例实例，空间及应用显示均已释放。[正式流程日志](evidence/fold7-pocket-client-production.log) · [右镜实拍](evidence/fold7-pocket-client-production-right.jpg)

### 关闭应用屏并保留 Pocket XR 空间

11:46 至 11:55，覆盖安装 `f439234` ARM64 预览，APK SHA-256 为 `aec65f666705bc278a589dead6dd7dc3ea31a157ad91f8fc09de17f1a4a8ed7e`。APK 通过 v2 签名与 16 KiB ZIP 对齐检查。五个原生库和 `71b7fd8` 逐字节相同，15 个 ELF `PT_LOAD` 段仍采用 16 KiB 对齐。

通过 **关闭 OpenXR 应用屏** 三次退出样例，Android DisplayManager 依次移除显示 238、239、240，每一轮都保留空间显示 235。原版运行时为样例提供 3840×1080 游戏图层，同一 PID 22779 三次进入 `FOCUSED`、三次完成 `xrDestroyInstance`。最后点按 **停止预览**，空间显示也已释放。另有对照在 DisplayManager 移除应用屏后触发三星 Freecess，样例的销毁回调在解除冻结后才完成，最迟一次约 32 秒。关闭按钮释放应用屏，无需等 Android 回收应用进程。[独立关闭筛选日志](evidence/fold7-pocket-client-close-display.log)

### 右镜输出对照

11:55 至 12:00 用同一接线复测黑屏。预览版的 Pocket XR、内置 2D 直开和 3D 菜单，以及稳定版 Monado CV10 v0.1.2 均让样例进入 `FOCUSED`。Fold7 采集到的 CV10 外显帧有左右两个蓝方块。固定相机在这四次复测中均未从右镜捕捉到测试画面，与 11:40 至 11:43 的明亮实拍结果不同。重置外显电源状态及从 Android 禁用后重新启用外显，均未恢复右镜实拍。接线和相机位置未做物理调整，原因待确认。[Pocket XR 外显帧](evidence/fold7-pocket-client-close-display-stereo.png) · [3D 菜单启动后的外显帧](evidence/fold7-pocket-menu-optical-control-stereo.png) · [稳定版外显帧](evidence/fold7-pocket-stable-optical-control-stereo.png) · [稳定版右镜实拍](evidence/fold7-pocket-stable-optical-control-right.jpg)

12:05 至 12:09 用独立的 **CV10 显示测试** 应用以 Android `Presentation` 绘制 L／R 测试图，右镜相机仍未拍到图案。应用请求 3200×1600 @ 70 Hz 或 3200×1800 @ 90 Hz 时，外显活动模式仍是 3200×1600 @ 90 Hz。随后在三星 DeX 的 **连接的显示设备** 中从 **已镜像** 切到 **已扩展** 并切回，活动时序实际切至 640×480 @ 60 Hz 再回到 3200×1600 @ 90 Hz。逻辑显示从 91 变为 245 和 246，SurfaceFlinger 的物理 ID 始终为 `4621527156978103573`。在恢复镜像后重新绘制测试图、再从 Monado 的 2D 列表打开 `hello_xr`。测试图和蓝方块分别出现在外显采集画面中，Monado 收到开屏 `OK`，样例正常进入 `FOCUSED` 和退出。右镜相机仍无可辨识的测试图案。当前对照覆盖了独立 Android 绘制和重新选择活动时序，头显重新插接后的恢复情况仍待确认。[筛选日志](evidence/fold7-native-optical-control.log) · [测试图外显帧](evidence/fold7-native-optical-control-frame.png) · [同轮右镜实拍](evidence/fold7-native-optical-control-right.jpg)

12:09:01 的 Monado 日志还记录佩戴传感器已启用并读回 `state=1`，随后收到开屏 `OK`。同轮右镜仍未拍到样例图案。[筛选日志](evidence/fold7-native-optical-control.log)

复测后 Broker 保持 **Monado CV10 Preview**，手机停在 2D 列表，Pocket XR 诊断虚拟屏已释放。已恢复三星外显镜像，保留现有 Shizuku 授权及无线 ADB，系统最低刷新率为 `null`。原版 Pocket XR 的佩戴头动和左镜光学输出，以及 Fold7 整机关 Wi-Fi 后的实测仍待完成。

12:34 至 12:35，保持同一接线、5 V 供电与无线 ADB，在 Fold7 的物理 USB 端口关闭再恢复数据传输。头显 HID、音频、集线器和键盘均重新枚举。头显 HID 复用了原 USB 地址，Monado 在下次启动 `hello_xr` 时仍重新请求 USB 权限。Android 在切换期间持续列出 CV10，前后均为显示 246、3200×1600、90 Hz。手机通过 `monitor_info` 读到显示器身份，当前 shell 无权读取链路训练相关节点。首次样例在系统授权提示中等候，已允许此次访问，手机随后恢复个人使用。没有取得 USB 重枚举后的镜片结果，仍需物理重接眼镜端来完成对照。[设备与运行时日志](evidence/fold7-usb-data-reenumeration.log)
