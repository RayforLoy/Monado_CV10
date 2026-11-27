# Monado CV10

HUAWEI VR Glass CV10 的 Android OpenXR 运行时。包名为 `org.cv10.monado.out_of_process`。安装后在 Khronos OpenXR Runtime Broker 中选择 **Monado CV10**，连接眼镜并允许 USB 访问。

## 安装

从 [Releases](https://github.com/RayforLoy/Monado_CV10/releases) 下载与系统 ABI 匹配的 APK。用 `adb shell getprop ro.product.cpu.abilist` 查看支持的 ABI，用 `adb install -r <APK>` 安装。Android 11 为 API 30，项目最低为 API 26。覆盖更新需要相同签名。实际 VR 功能还取决于 USB 主机、外接显示器、Broker 和 Vulkan 驱动。历史 Fold7 镜片、3DoF 和刷新率复测见 [验收记录](history/README.md)。

| 系统 ABI | v0.1.3 APK |
| --- | --- |
| 64 位 ARM Android，含骁龙、天玑、麒麟的 64 位系统 | [arm64-v8a](https://github.com/RayforLoy/Monado_CV10/releases/download/v0.1.3/Monado-CV10-0.1.3-arm64-v8a.apk) |
| 32 位 ARM Android | [armeabi-v7a](https://github.com/RayforLoy/Monado_CV10/releases/download/v0.1.3/Monado-CV10-0.1.3-armeabi-v7a.apk) |

用 `Get-FileHash <APK> -Algorithm SHA256` 核对下载文件。v0.1.3 沿用 v0.1.2 的签名，Android 版本代码从 102 升至 103。下载页列有每个 APK 的 SHA-256。[v0.1.3 发布页](https://github.com/RayforLoy/Monado_CV10/releases/tag/v0.1.3)

历史 x86 和 x86_64 安装包与对应校验和可在 v0.1.2 发布页获取。[v0.1.2 发布页](https://github.com/RayforLoy/Monado_CV10/releases/tag/v0.1.2)

## 在 Galaxy Z Fold7 上启动 OpenXR 应用

首次配置时安装 ARM64 版 Monado CV10 和 Khronos OpenXR Runtime Broker（`org.khronos.openxr.runtime_broker`），在 Broker 的 **Installed Runtimes** 中勾选 **Monado CV10**。把三星外接显示模式设为 **已镜像**。测试可使用未修改的 Khronos `hello_xr` OpenGLES 样例。[Broker 安装](https://play.google.com/store/apps/details?id=org.khronos.openxr.runtime_broker) · [Broker 源码](https://github.com/KhronosGroup/OpenXR-Android-Broker) · [hello_xr 1.1.65 APK](https://github.com/KhronosGroup/OpenXR-SDK-Source/releases/download/release-1.1.65/hello_xr-OpenGLES-release-1.1.65.apk)

1. 普通 C-C 线可直连 CV10。使用原厂手机线时先接好 CV10 和外接 5 V，再接入已解锁的 Fold7。等镜片出现手机桌面。
2. 在 Fold7 打开 **Monado CV10**，再回到手机桌面。遇到三星后台管理拦截服务绑定时，先打开运行时可恢复启动。
3. 从手机桌面启动 `hello_xr` 或已适配的 OpenXR 应用。系统询问是否允许 **Monado CV10** 访问 **HUAWEI GLASS HID** 时，点 **确定**。需要主动请求时，可用 Monado 菜单中的 **CV10 USB access**。
4. 佩戴眼镜。`hello_xr` 正常运行时左右镜片各有一个蓝色方块，测试结束后按返回键退出。

如果点开样例后停在启动画面，先打开 Broker 确认 **Monado CV10** 仍已勾选，返回后再试第 2、3 步。Loader 查不到活动运行时，或三星的 `MARs` 后台管理拒绝 `bindService` 时，都可能出现 `XR_ERROR_RUNTIME_UNAVAILABLE`。

需要让 Fold7 主屏独立跑 120 Hz 时，在启动 OpenXR 应用前启动 Shizuku，确认已授权 Monado CV10，然后在 Monado 菜单选择 **开启独立 120 Hz**。

## 离线应用启动器

此功能已在预览构建中验证。在 **Monado CV10 Preview → 应用启动器** 中直接点选本机应用，或者连接 CV10 后选 **打开 3D 启动器**。启动器读取本机安装列表，无需账户或 Shizuku 权限。应用从手机主屏启动，OpenXR 画面由运行时输出到眼镜。在 Broker 中点按 **Monado CV10 Preview** 的名称并确认右侧已勾选，保持三星外接显示为 **已镜像**。

从 Pocket XR 外显模式切回内置启动器时，如果 Broker 仍选中 **Pocket XR** 或 **Pocket XR (Monado CV10)**，点选 OpenXR 应用会提示切回 Monado。可以从提示直接打开 Broker，选好后返回启动器。普通 Android 应用仍可直接打开。

- 首次使用或重新插入眼镜后，可从 Monado 菜单选择 **CV10 USB access**，预先允许访问 **HUAWEI GLASS HID**。3D 启动器也会在开启会话前请求权限，允许后会自动打开菜单。若从 2D 列表点选 XR 应用触发了权限提示，允许后会继续启动所选应用。拒绝后会停留在列表，可再次点选重试。
- 应用列表优先使用应用声明的 OpenXR 沉浸式入口。仅有普通启动入口、但在清单请求 OpenXR 权限的应用也出现在 **仅显示 XR 应用** 中，例如需要先选择串流主机的 Moonlight XR。应用仍会在手机上打开自己的设置界面。若应用两个标记都未声明，可取消 **仅显示 XR 应用** 或在 3D 菜单切到 **全部应用** 后打开。[Fold7 从应用启动器打开 Moonlight XR](history/fold7-offline-launcher.md#从应用启动器打开-moonlight-xr)
- 3D 菜单使用手机姿态控制激光指向。激光可以越过菜单边框，指向菜单按钮时点按屏幕确认，从手机两侧的蓝色标记向内滑动返回。从底部蓝色标记向上滑并停留约 350 毫秒，可将菜单和激光指向一起置中。标记位于 Android 的系统手势区内侧，屏幕外沿仍用于系统返回和 Home。手机激光笔在启动器窗口内工作，打开目标应用后按该应用的交互方式操作。[华为 VR Glass 手机操控方式](https://consumer.huawei.com/cn/support/content/zh-cn00776840/)
- 3D 菜单是本项目实现的 OpenXR 客户端。华为 VR 桌面 `com.huawei.vrlauncherx` 11.1.0.337 的开源公告覆盖 APK 列出的第三方依赖。尚未找到华为桌面应用的公开源码或允许 fork 的授权。[样本与许可证核对](history/fold7-offline-launcher.md#华为-vr-桌面的源码与许可)

连接 CV10 时，内置 3D 菜单使用预览包自带的 Monado CV10，以适配镜片的双目方向。未连接 CV10 时，菜单先使用 Broker 选中的运行时，系统 Loader 报告不可用时再用内置运行时重试。打开第三方 OpenXR 应用仍由各应用查询系统 Broker。若菜单也无法打开，可在启动提示中打开 Broker 确认选择后返回，或选择 **重试**。提示未弹出时，点按手机上的错误提示文字可打开启动选项。[Fold7 运行时回退实测](history/fold7-offline-launcher.md#3d-菜单的运行时回退)

Fold7（SM-F9660）使用 C9.53.6 原厂线和外接 5 V，在 **已镜像** 的 3200×1600、90 Hz 模式验证了两条路径。先从 2D 列表启动未修改的 Khronos `hello_xr` OpenGLES，返回后从 3D 菜单重进，同一个应用进程两次达到 `FOCUSED`，外屏截图和右镜均有画面。结束样例进程后，3D 菜单也能启动新的样例进程并出图。运行时在 Android 12 或更新版本直接管理 CV10 的 Presentation，覆盖 3D 菜单关闭时眼镜失去画面的故障。[Fold7 验收记录](history/fold7-offline-launcher.md)

原厂线实验中曾出现外显帧有双目蓝块、镜片仍黑。只重接眼镜端后恢复。保持接线的后续轮次再次出现相同现象，Pocket XR、2D 直启、3D 点选及上一版 Preview 均复现。[镜片与外显对照](history/fold7-offline-launcher.md#整合包的镜片黑屏复现)

如果 Fold7 的外显截图已有双目蓝块，而镜片仍黑，可保持手机端和 5 V 供电，仅拔下眼镜端约 5 秒再接回。系统重新询问时允许访问 **HUAWEI GLASS HID**。较早一轮重接后右镜恢复出图，后续一轮重接后用户仍看不到右镜的 3D 菜单。后一轮的外显画面和头显开屏应答仍正常。[较早的重接对照](history/fold7-offline-launcher.md#在启动-openxr-前确认-cv10-usb-授权) · [重接后右镜仍黑的实测](history/fold7-offline-launcher.md#重接后右镜仍黑)

Fold7 上注入触摸事件验证了左右内侧手势返回、底部上滑停留置中和轻触确认。竖屏控制页复测通过左右内侧返回、系统侧边返回和底边 Home。底部蓝线上快速上滑未置中，停留 460 ms 后置中一次。以 Fold7 实时姿态为基准合成四向转动，验证了俯仰修正和左右指向。早期两路外显截图与右镜实拍一致。后续在 Fold7 竖屏控制页手持转动手机，确认光点离开菜单后仍可见，菜单外点按不触发操作。[激光笔方向、越界和竖屏手势复测](history/fold7-phone-laser-axis.md)

在 Fold7 停止 Shizuku 服务，并同时移除诊断版预览的 `INTERNET` 权限后，原版 `hello_xr` 经 2D 直启和 3D 点选均进入 `FOCUSED` 并正常退出。两次外显截图都有双目蓝块，本轮右镜相机均未拍到画面。此前单独进行应用网络隔离时已拍到右镜蓝块。系统 Wi-Fi 全程保留给无线 ADB，关闭系统 Wi-Fi 的实机复测仍待完成。[两项同时隔离的实机记录](history/fold7-offline-launcher.md#fold7-同时隔离应用网络并停止-shizuku) · [此前的网络隔离与右镜实拍](history/fold7-offline-launcher.md#整合预览的应用网络隔离)

整合预览提供离线启动器和 Pocket XR 外显入口。`71b7fd8` 的 ARM64 包也在关闭 Wi-Fi 和移动数据的 16 KiB Android 16 模拟器上安装成功。从 2D 列表能打开原版 Pocket XR 手机触控板并返回。内置 3D 启动器两次成功加载原生运行时，模拟器因缺少 Vulkan 扩展未能出图。这个版本的原版 `hello_xr` 自带 ARM64 库采用 4 KiB 对齐，16 KiB 系统在进入 OpenXR 前即拒绝加载。[16 KiB 断网对照](history/fold7-offline-launcher.md#android-16-16-kib-系统断网对照)

如果 2D 列表提示 **后台运行受限**，在 **应用程序信息 → 电池 → 后台运行** 中可为 Monado CV10 Preview 选择 **不受限制**。Fold7 的 One UI 8.5 即使在该设置下也曾冻结 Preview，导致 2D 直启或 3D 点选后黑屏、定格或无法返回。当前整合预览在三星 Android 16 设备上检测到外部 OpenXR 应用的 IPC 回复超时后，通过 Android 服务绑定唤醒运行时。Fold7 从 3D 菜单启动和退出原版 `hello_xr` 五轮通过，最初数秒仍可能有短暂停顿。[后台冻结对照](history/fold7-offline-launcher.md#fold7-的-3d-点选与后台冻结对照) · [Android 后台限制说明](https://developer.android.com/topic/performance/background-optimization#user-initiated-restrictions)

出现黑屏时可先核对 `hello_xr` 是否进入 `FOCUSED`，以及外显截图是否已有双目方块。有一次 Fold7 截图有图，但右镜在稳定版、预览版和独立 Android 显示测试中均未拍到图案。三星外显重新切回 3200×1600 @ 90 Hz 后仍未恢复该轮出画。[右镜输出对照](history/fold7-pocket-dual-runtime.md#右镜输出对照)

## Pocket XR 外屏兼容性

安装原版 Pocket XR 后，在 Monado CV10 菜单打开 **Pocket XR 外显兼容性**。该页面显示 CV10 的活动时序，以及 Android 是否允许 Pocket XR 在外屏启动。Fold7 的 **设置 → 已连接的设备 → Samsung DeX → 连接的显示设备** 可切换 **已扩展** 和 **已镜像**。

Pocket XR v1.6.28 自带空间桌面的显示与头部跟踪管线。CV10 的 3200×1600 画幅宽高比为 2:1，低于其 3.2 的双目判定阈值，该版本也没有 CV10 的 IMU 支持。系统允许外屏启动时可打开原版的平面入口。整合预览在同一 APK 提供本地应用启动器和 Pocket XR 外显入口。兼容模式使用 Android 虚拟屏接收 Pocket XR 的左右眼画面，再由 Monado 送到 CV10。[Pocket XR 双目判定](https://github.com/pocket-xr/pocket-xr/blob/v1.6.28/core/src/main/kotlin/com/hayeco/flipspaces/core/spatial/GlassesOptics.kt#L211-L262) · [空间模式前置条件](https://github.com/pocket-xr/pocket-xr/blob/v1.6.28/core/src/main/kotlin/com/hayeco/flipspaces/core/spatial/SpatialMode.kt#L146-L171)

Fold7 接 53 版原厂线和 5 V 外部供电时，**已镜像**为 3200×1600、90 Hz，系统拒绝直接打开 Pocket XR 外屏入口。切到 **已扩展**后，Android 可启动原版的 `GlassesHomeActivity`，外屏活动时序降为 640×480、60 Hz，Samsung DeX 同时报告当前分辨率不受支持。恢复 **已镜像**后回到 3200×1600、90 Hz。

外显诊断在 Fold7 创建 3840×1080、60 Hz 的 Android 虚拟显示屏，并由已授权的 ADB 启动原版 Pocket XR 1.6.28 的 `SpaceActivity`。内置测试卡在 CV10 两个眼区显示各自的标记，右镜拍到正立的 `RIGHT EYE`。在原版空间启动器切换到 **3D** 后，实测菜单左右眼各 1920×1080，右镜拍到完整菜单。点选 `hello_xr` 后出现提示缺少 Shizuku 授权的双目占位窗口。

从 Monado 2D 启动器取消 **仅显示 XR 应用**，可打开原版 Pocket XR 的手机触控板。手机端一键启动外显需要预览包自己的 Shizuku 授权，创建和投射应用面板还需要原版 Pocket XR 的 Shizuku 授权。原版手机端能发现诊断虚拟屏，Android 拒绝它在该屏自行打开平面桌面 Activity，原版随后回退到基础画面。独立电脑屏幕模式已用原版 Pocket XR 把实况左右并排视频投到 CV10，右镜实拍通过。Monado 已验证用 CV10 姿态固定整个双目画面。[Pocket XR 外显记录](history/fold7-pocket-xr-display.md) · [电脑屏幕 3D 实测](history/fold7-pocket-pc-sbs.md) · [固定双目画面的实测](history/fold7-pocket-world-anchor.md)

在 Fold7 为预览包和原版 Pocket XR 分别授权 Shizuku 后，从 **打开 Pocket XR 空间** 可一键启动原版空间。原版自动打开手机触控板时，预览保持外显，触控板上的 Home 按钮可打开眼镜中的原版菜单。原版计算器和秒表已在双目外显更新画面，右镜实拍通过。恢复旧会话后若窗口不可见，可在原版启动器重新选中。实际转头和左镜实拍仍待验收。[Fold7 一键启动](history/fold7-pocket-xr-display.md#fold7-双应用授权后的一键启动) · [原版应用面板和右镜实拍](history/fold7-pocket-xr-display.md#fold7-原版应用面板的实时画面)

在关闭 Wi-Fi 和移动数据的 Android 16 模拟器中，给原版 Pocket XR 授权 Shizuku 后，可从其 3D 启动器打开时钟面板。ANGLE 后端的双目画面显示秒表实时更新，关闭面板后系统释放其虚拟屏。预览使用的 Shizuku 传输代码也通过了原版空间、电脑屏幕入口与显示清理测试。[模拟器面板对照](history/fold7-pocket-xr-display.md#android-16-模拟器原版应用面板) · [显示传输实测](history/fold7-pocket-xr-display.md#android-16-模拟器-shizuku-显示传输)

在 16 KiB 页的 Android 16 模拟器中，原版 Pocket XR 1.6.28 首次启动会显示内存页兼容提示。确认后，从 Monado 预览菜单可启动原版左右眼测试卡，再锁屏、解锁并在同一进程切换到普通空间。两次都收到虚拟显示首帧。该环境的 Monado 合成输出为黑屏。[菜单、首帧和退出记录](history/fold7-pocket-xr-display.md#android-16-16-kib-页正式菜单)

### Pocket XR 外显实验预览

本分支用 3840×1080 虚拟显示屏接收未修改的 Pocket XR，并用 OpenXR 的左右眼 Quad 分别提交两半画面。以 `-Pcv10LauncherPreview=true` 构建。在 Fold7 保持三星外接显示为 **已镜像**，允许运行时访问 CV10 USB，再打开 **Monado CV10 Preview → 更多选项 → Pocket XR 外显预览**。虚拟显示服务需要 Android 14 或更新版本、设备所有者用户，以及以 ADB shell 身份运行的 Shizuku 13 或更新版本。预览 APK 使用单独的包名与 Shizuku 授权。

点选外显模式时，如果系统询问 CV10 USB 访问权限，点 **允许** 后继续刚才选择的模式。如果预览包也缺少 Shizuku 授权，接下来会显示 Shizuku 的授权提示。拒绝 USB 后会留在预览页，可再次点选重试。[Fold7 首次授权记录](history/fold7-pocket-xr-display.md#连接-cv10-后的首次-usb-授权)

Pocket XR 外显预览始终用内置 Monado CV10 驱动镜片。第三方 OpenXR 应用仍从系统 Broker 选择运行时。日常直接启动 CV10 OpenXR 应用时，按前文的流程选择 **Monado CV10** 或 **Monado CV10 Preview**。

- **显示左右眼测试卡** 启动原版 Pocket XR 的内置测试卡。检查日志中虚拟屏 ID 与首帧，再分别确认两只镜片上的标记及其方向。
- **打开 Pocket XR 空间** 并收到画面后，使用 **打开手机控制** 切到原版 Pocket XR，同时保持眼镜会话。可在手机端 **空间窗口** 打开启动器，再在启动器中使用 **3D** 切换到双目布局。Pocket XR 创建应用面板需要它自己的 Shizuku 授权。
- **在 Pocket XR 中打开 OpenXR 应用** 是 Monado 手机端的实验入口。先在 OpenXR Runtime Broker 选择 **Pocket XR (Monado CV10)**，再打开 Pocket XR 空间。收到画面后点选已安装的 OpenXR 应用。预览为应用建立独立虚拟屏，手机会切到原版 Pocket XR 触控板。打开 **Monado CV10 Preview → 更多选项 → Pocket XR 外显预览**，选 **关闭 OpenXR 应用屏** 可结束当前应用并保留 Pocket XR 空间，选 **停止预览** 会关闭整个外显会话。日常直接启动 OpenXR 应用时，在 Broker 改回 **Monado CV10** 或 **Monado CV10 Preview**。[Fold7 双运行时记录](history/fold7-pocket-dual-runtime.md#正式预览通过-shizuku-打开-openxr-应用)
- Fold7 的独立应用屏已验证原版 Khronos `hello_xr` 的 OpenGL ES 和 Vulkan APK。Vulkan 样例在同一进程重进，两次外显均显示双目蓝块。本轮右镜仍待光学验收。需要在手机主屏完成设置的 XR 应用从 Monado 的独立启动器打开。[Vulkan 实测](history/fold7-pocket-dual-runtime.md#vulkan-应用屏与同进程重进)
- **电脑屏幕 3D（左右并排）** 使用原版 Pocket XR 的独立电脑屏幕。结束正在运行的空间预览，再选 **在手机上设置电脑屏幕** 配对 Sunshine 或 Apollo。配对后返回 Monado，选择 **电脑屏幕 3D（左右并排）**。原版在虚拟屏上自动连接已配对的主机。电脑播放铺满 16:9 桌面的左右并排内容时，左半送入左眼，右半送入右眼。当前实测主机为 Sunshine，接收 1920×1080 的 Half SBS 视频。这一路径使用独立的头锁定平面。[正式入口实测](history/fold7-pocket-pc-sbs.md#fold7-正式入口配对后自动开流)
- **使用 CV10 头部追踪固定 Pocket XR 画面** 让 Monado 将原版空间的双目画面固定在最初朝向，转头时画面留在原位。选择 **将画面移到当前朝向** 可重新置中。固定模式无需 VPN 授权，请在 Pocket XR 中关闭自身的头部追踪，以免画面被两次校正。[Fold7 原版测试卡实拍](history/fold7-pocket-world-anchor.md)
- **通过 Android 本机 VPN 接入头部追踪（实验）** 可在打开空间前选用。勾选后由 Android 询问 VPN 授权。打开空间时，预览只为原版 Pocket XR 宣告本机 `169.254.2.1/32`，将 CV10 的 HID 样本和控制心跳送到该地址的两个 TCP 端口。选择 **停止预览** 会释放连接和 VPN 接口。[姿态与重连验证](history/fold7-pocket-xr-imu.md#android-16-原版-pocket-xr-接入)
- 返回预览页选择 **停止预览**，会释放本次虚拟显示和 OpenXR 会话。手机通知也可提供停止入口。

如果 Fold7 提示外显启动超时，先确认 Shizuku 正在运行。如日志出现 `ShizukuServiceStarter: provider is null`，可在 **Shizuku 的应用程序信息 → 电池 → 后台运行** 允许它在后台启动服务，再从预览页重试。[Fold7 后台限制对照](history/fold7-pocket-pc-sbs.md#fold7-正式入口配对后自动开流)

Fold7 上通过已有 ADB 权限调用同一显示服务，验证了受信任虚拟屏的创建和释放。原版空间在该屏输出双目测试卡和应用占位窗口。默认电脑屏幕配对 Sunshine 后在该屏收到了 1920×1080 的 HEVC Half SBS 视频。[服务与默认电脑屏幕实测](history/fold7-pocket-shell-service.md)

临时在 Broker 选用原版 **Pocket XR** 做诊断时，原版运行时接管 `hello_xr`，内置 Monado CV10 同时将游戏图层送到 CV10。将样例放到独立虚拟屏后，四轮在手机主屏打开 Pocket XR 触控板并连续出帧约九秒，CV10 右镜拍到蓝色方块。三星 Freecess 仍可能冻结原版进程。一次冻结后，在 Monado 点按 **打开手机控制**，三星解冻原版进程，同一游戏图层恢复出帧。样例在退出时仍可能于原版运行时卸载后崩溃。长时间运行和正常退出仍待验证。[双运行时实测](history/fold7-pocket-dual-runtime.md)

Pocket XR 的原版空间菜单按普通应用面板启动和恢复应用。声明沉浸式入口的应用可使用 **在 Pocket XR 中打开 OpenXR 应用**，由原版运行时在独立应用屏输出。需要先在手机界面完成连接或设置的 XR 应用，可切回 Monado CV10 Broker 后从内置启动器打开。普通 Android 应用仍可从原版空间菜单点选。Monado CV10 直出运行时会拒绝来自 Pocket XR 普通面板的 OpenXR 连接，以免恢复的应用夺走空间的输出。[同机入口对照](history/fold7-pocket-xr-display.md#原版空间菜单中的-openxr-应用)

如果 Pocket XR 每次进空间都恢复一个旧的 OpenXR 普通面板，可在空间仍开启时到手机上的 **Pocket XR → 空间窗口**，找到该应用，按它右侧的 **关闭**。这样可移除保存的面板并保留同一空间的其他应用。之后用 Monado 的 **在 Pocket XR 中打开 OpenXR 应用** 启动该应用。[Fold7 旧面板移除记录](history/fold7-pocket-dual-runtime.md#移除恢复的-openxr-普通面板)

退出空间后，若从内置列表点选的应用仍停在黑屏，可按 Home 回手机桌面。在 Monado 2D 列表长按该应用，打开系统应用详情，选择 **强制停止** 并确认。返回列表后再点按同一应用。[Fold7 恢复路径](history/fold7-pocket-dual-runtime.md#从-pocket-xr-普通面板切回-monado)

**Pocket XR (Monado CV10)** 是可选的 ARM64 客户端包。它加载带卸载修复的库，渲染服务由未修改的官方 Pocket XR 1.6.28 或 1.6.31 提供。客户端包与 Monado 预览需使用同一签名。Fold7 用原版 1.6.28、正式 Shizuku 入口和此客户端，已复测 `hello_xr` 出画、正常关闭和同进程重进。选择原版 Broker 条目 **Pocket XR** 时，应用加载上游自带的客户端，可能遇到退出崩溃。[客户端构建](src/xrt/targets/pocket_client_android/README.md) · [Fold7 正式入口实测](history/fold7-pocket-dual-runtime.md#正式预览通过-shizuku-打开-openxr-应用)

Fold7 的 CV10 IMU 通过 ADB 回环将实时样本送到隔离的 Android 16 模拟器。模拟器关闭 Wi-Fi 和移动数据，Shizuku 未运行。未修改的 Pocket XR 通过模拟器内的本机 VPN 收到约 1000 Hz 的静置数据并解算姿态。此前的模拟姿态测试还覆盖了转动方向、停止 VPN 和同进程重连。Fold7 上已验证 VPN 授权可取消。Fold7 同机实时连接及佩戴转头仍待验收。[实时 IMU 对照](history/fold7-pocket-xr-imu.md#实时-fold7-采样送入原版-android-应用)

## 原厂手机线

运行时识别到原厂线且当前来源为 PC 时，会请求切换到手机来源。获取 HID 后会请求启用佩戴传感器。拔插后重新获取 HID 时，即使 Android 复用了设备 ID，运行时也会重新发送开屏指令。

用户在 Galaxy Z Fold7（SM-F9660）上两次复测确认出图，使用的是已配置为 2 A 的原厂手机线和外接 5 V 供电。一次记录在重插手机端后等 35 秒，再从普通入口打开 Monado CV10 和 `hello_xr`。右镜显示蓝色立方体，外屏模式为 3200×1600 @ 90 Hz，图层每秒约有 89.96 个不同呈现时间戳。硬件验证使用的 ARM64 候选 APK SHA-256 为 `989c7b43ecd369ed5aa49776542db4511e02ac0d0257395d9c4fd40fdf9f26c5`。

同一候选 APK 也验证了回读字段为 `0C095306` 的 53 版原厂线和外接 5 V。按上述启动顺序复测后，`hello_xr` 进入 `FOCUSED`，用户确认双眼均看到蓝色方块，外屏活动时序为 3200×1600 @ 90 Hz。

## 从完整源码构建

源码基于 [Monado ce2b721](https://gitlab.freedesktop.org/monado/monado/-/tree/ce2b721eb4ffcbd726fb88357625f55871a8f262)。CV10 驱动位于 `src/xrt/drivers/android`，运行时位于 `src/xrt/targets/openxr_android`，源码已合入定制修改。

Windows 使用 JDK 17、Python 3、Android SDK Platform 35、Build Tools 34.0.0、NDK 26.3.11579264、CMake 3.22.1、Eigen 5.0.0 和 `PATH` 中的 `glslangValidator`。设置 `JAVA_HOME` 和 `ANDROID_HOME`，在 `local.properties` 中填写 `sdk.dir`、`eigenCMakeDir`（包含 `Eigen3Config.cmake` 的目录）、`pythonBinary`，路径使用正斜杠。发布 APK 还需要 Build Tools 36.0.0 用于对齐和签名。

默认 Gradle 构建将 ARM64（`arm64-v8a`）和 ARMv7（`armeabi-v7a`）打入同一个 APK。运行下方脚本可为这两个 ABI 分别生成单架构签名包和 SHA-256 清单。

```powershell
.\scripts\build-cv10-apks.ps1 -OutputDir .\build\cv10-apks
```

仅构建一个架构时，通过 `-Pcv10Abi` 指定，例如 `.\gradlew.bat "-Pcv10Abi=arm64-v8a" :src:xrt:targets:openxr_android:packageOutOfProcessRelease`。`x86` 和 `x86_64` 可用同一参数显式构建，例如用于模拟器调试。Gradle 把未签名包输出到 `src/xrt/targets/openxr_android/build/outputs/apk/outOfProcess/release/`。上述脚本使用本机 Android 开发证书签名，并校验对齐和签名。首次构建请允许 Gradle 下载依赖。

需要与正式包并排安装时，构建 **Monado CV10 Preview**，并在 Broker 选择同名运行时。

```powershell
.\gradlew.bat "-Pcv10Abi=arm64-v8a" "-Pcv10LauncherPreview=true" :src:xrt:targets:openxr_android:assembleOutOfProcessDebug
```

预览包位于 `src/xrt/targets/openxr_android/build/outputs/apk/outOfProcess/debug/`。Gradle 同时为 APK 和原生 IPC 客户端设置预览包名 `org.cv10.monado.out_of_process.launcher_preview`。默认静态 C++ 标准库配置的 ARM64 APK 已通过所有 `PT_LOAD` 段的 16 KiB 对齐检查及 `zipalign -c -P 16 -v 4`。使用 4 KiB 内存页的 Fold7 在更新对齐包后未再显示兼容提示。16 KiB Android 16 模拟器已验证安装和运行时加载。[验收记录](history/fold7-offline-launcher.md#16-kib-系统安装与启动失败回归) · [Android 16 KiB 内存页支持](https://developer.android.com/guide/practices/page-sizes)

历史 APK 和对应实验说明见 [apk/archive](apk/archive) 和 [history](history)。上游项目说明与许可证见 [README.upstream.md](README.upstream.md) 和 [LICENSE](LICENSE)。

## DisplayPort 通道诊断

打开 Monado CV10 启动页的菜单，选择 **CV10 DisplayPort 状态**。OpenXR 运行时接到 CV10 外屏时也会在后台读取一次。读到两条通道或 Type-C 双通道线索时，运行时会尝试在手机上显示相应提示，日志标签为 `Cv10DisplayPort`。

- **实际协商值**来自与 CV10 EDID 匹配的活动 DP 接口。运行时用该接口的 DRM AUX 读取 DPCD，结合当前通道数和各通道的链路训练状态确认本次读数。[Linux DPCD 寄存器定义](https://github.com/torvalds/linux/blob/v6.12/include/drm/display/drm_dp.h)
- **间接线索**来自可读的 Type-C DP Alt Mode 引脚分配。D 为 DP 预留两条通道，C 或 E 为 DP 预留四条。此时实际协商值仍显示为不可读取。[Linux Type-C DisplayPort 状态 ABI](https://github.com/torvalds/linux/blob/v6.12/Documentation/ABI/testing/sysfs-driver-typec-displayport)
- **不可读取**可能出现在系统缺少接口、限制访问、训练尚未完成或链路无法与 CV10 对应时。Android 识别到 CV10 时，页面也会显示当前分辨率和刷新率，便于记录闪屏现场。

有群友怀疑双通道配置与闪屏有关，因果关系待验证。诊断使用运行时现有权限进行只读查询。

## v0.1.2 验证

四个独立 APK 均核对了单一 ABI、核心 OpenXR 和 service ELF、API 26 最低版本、签名和 ZIP 对齐。Android 11（API 30）模拟器上，x86_64 与 x86 均通过全新安装并启动 AboutActivity。测试设备支持 x86_64、x86；本次未在 Android 11 ARM 真机或 CV10 镜片上复测。现有实验与旧版 Fold7 硬件记录见 history 目录。
