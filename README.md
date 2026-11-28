# Monado CV10

HUAWEI VR Glass CV10 的 Android OpenXR 运行时。包名为 `org.cv10.monado.out_of_process`。安装后在 Khronos OpenXR Runtime Broker 中选择 **Monado CV10**，连接眼镜并允许 USB 访问。

## 安装

**v0.1.4-rc.4** 在第三方 OpenXR 会话中自动显示竖屏手机手柄。中央点阵触摸板可滑动，轻点后抬手确认，滑动和长时间按住都只传递触点。手柄显示时手机窗口亮度为 10%，隐藏后恢复应用自己的亮度。CF20 和手机可以分别占用左右手，也可以在启动器交换。CF20 支持华为按键、触控板、手柄电量和旋转角速度，头显可向应用发送佩戴事件。Fold7 版 APK 为 arm64-v8a，沿用 `org.cv10.monado.out_of_process.launcher_preview` 和此前的签名，可覆盖 rc.3。[下载与 SHA-256](https://github.com/RayforLoy/Monado_CV10/releases/tag/v0.1.4-rc.4) · [Fold7 实测与应用接入](https://github.com/RayforLoy/HUAWEI_CV10_EVO/blob/codex/cv10-company-handoff/docs/fold7-dual-controller.md)

**整合预发布 v0.1.4-rc.1** 把 OpenXR 运行时、离线 2D/3D 启动器、Pocket XR 外显、CF20 手柄和实验性 6DoF 套件放进同一个 ARM 安装包。它在手机和 Broker 中显示为 **Monado CV10 Preview**，包名为 `org.cv10.monado.out_of_process.launcher_preview`。此包可与稳定版并存，覆盖旧预览版时须匹配签名。[整合预发布和 SHA-256](https://github.com/RayforLoy/Monado_CV10/releases/tag/v0.1.4-rc.1) · [Fold7 整合包实测](history/fold7-unified-runtime.md)

当前源码统一显示为 **Monado CV10**。从源码默认构建使用 `org.cv10.monado.out_of_process`，指定 `-Pcv10LauncherPreview=true` 可继续覆盖同签名的旁路预览包。当前源码包含华为原厂投影与 RGB 畸变、Pocket XR 光学视场、CF20 交互和可选超分辨率。下载链接所指的 v0.1.4-rc.1 保留其发布时的 Preview 名称和功能。[原厂光学与 Fold7 验证](history/fold7-oem-optics.md)

从 [Releases](https://github.com/RayforLoy/Monado_CV10/releases) 下载与系统 ABI 匹配的 APK。用 `adb shell getprop ro.product.cpu.abilist` 查看支持的 ABI，用 `adb install -r <APK>` 安装。Android 11 为 API 30，项目最低为 API 26。覆盖更新需要相同签名。实际 VR 功能还取决于 USB 主机、外接显示器、Broker 和 Vulkan 驱动。历史 Fold7 镜片、3DoF 和刷新率复测见 [验收记录](history/README.md)。

当前源码可根据 CV10 外屏的实际刷新率设定初始帧周期。Android 12 及以上可用系统报告的产品名识别外屏，其他设备也可按显示名或唯一的 3200×1600 候选识别。Android 10/11 的运行时服务可在授予「显示在其他应用上层」后使用外显窗口，这条路径仍待实机验证。[通用 Android 外显 PR](https://github.com/RayforLoy/Monado_CV10/pull/1)

| 系统 ABI | v0.1.3 APK |
| --- | --- |
| 64 位 ARM Android，含骁龙、天玑、麒麟的 64 位系统 | [arm64-v8a](https://github.com/RayforLoy/Monado_CV10/releases/download/v0.1.3/Monado-CV10-0.1.3-arm64-v8a.apk) |
| 32 位 ARM Android | [armeabi-v7a](https://github.com/RayforLoy/Monado_CV10/releases/download/v0.1.3/Monado-CV10-0.1.3-armeabi-v7a.apk) |

用 `Get-FileHash <APK> -Algorithm SHA256` 核对下载文件。v0.1.3 沿用 v0.1.2 的签名，Android 版本代码从 102 升至 103。下载页列有每个 APK 的 SHA-256。[v0.1.3 发布页](https://github.com/RayforLoy/Monado_CV10/releases/tag/v0.1.3)

历史 x86 和 x86_64 安装包与对应校验和可在 v0.1.2 发布页获取。[v0.1.2 发布页](https://github.com/RayforLoy/Monado_CV10/releases/tag/v0.1.2)

## 在 Galaxy Z Fold7 上启动 OpenXR 应用

首次配置时安装支持 ARM64 的 Monado CV10 和 Khronos OpenXR Runtime Broker（`org.khronos.openxr.runtime_broker`），在 Broker 的 **Installed Runtimes** 中勾选 **Monado CV10**。使用整合预发布时勾选 **Monado CV10 Preview**，后续步骤也打开该应用。把三星外接显示模式设为 **已镜像**。测试可使用未修改的 Khronos `hello_xr` OpenGLES 样例。[Broker 安装](https://play.google.com/store/apps/details?id=org.khronos.openxr.runtime_broker) · [Broker 源码](https://github.com/KhronosGroup/OpenXR-Android-Broker) · [hello_xr 1.1.65 APK](https://github.com/KhronosGroup/OpenXR-SDK-Source/releases/download/release-1.1.65/hello_xr-OpenGLES-release-1.1.65.apk)

1. 普通 C-C 线可直连 CV10。使用原厂手机线时先接好 CV10 和外接 5 V，再接入已解锁的 Fold7。等镜片出现手机桌面。
2. 在 Fold7 打开 **Monado CV10**，再回到手机桌面。遇到三星后台管理拦截服务绑定时，先打开运行时可恢复启动。
3. 从手机桌面启动 `hello_xr` 或已适配的 OpenXR 应用。系统询问是否允许 **Monado CV10** 访问 **HUAWEI GLASS HID** 时，点 **确定**。需要主动请求时，可用 Monado 菜单中的 **CV10 USB access**。
4. 佩戴眼镜。`hello_xr` 正常运行时左右镜片各有一个蓝色方块，测试结束后按返回键退出。

如果点开样例后停在启动画面，先打开 Broker 确认 **Monado CV10** 仍已勾选，返回后再试第 2、3 步。Loader 查不到活动运行时，或三星的 `MARs` 后台管理拒绝 `bindService` 时，都可能出现 `XR_ERROR_RUNTIME_UNAVAILABLE`。

需要让 Fold7 主屏独立跑 120 Hz 时，在启动 OpenXR 应用前启动 Shizuku，确认已授权 Monado CV10，然后在 Monado 菜单选择 **开启独立 120 Hz**。

如需关闭 Wi-Fi 和 Shizuku 后继续使用，在同一菜单选择 **离线保持 120 Hz**。菜单变为 **已保持 120 Hz · 恢复原刷新率** 后可以停止 Shizuku，拔插眼镜或重启手机仍会保留系统最低值。这项设置也影响日常亮屏并可能增加耗电。离线开关会关闭按会话自动提频。结束使用时启动已授权的 Shizuku，回到菜单选择恢复；若先点了恢复，菜单会显示 **等待恢复原刷新率 · 需要 Shizuku**，启动 Shizuku 后再打开 Monado 即可继续。原来没有设最低值的手机会恢复为自适应，用户在其他地方更改的刷新率会保留。

## 离线应用启动器

在 **Monado CV10 → 应用启动器** 中直接点选本机应用，或者连接 CV10 后选 **打开 3D 启动器**。启动器读取本机安装列表，无需账户或 Shizuku 权限。应用从手机主屏启动，OpenXR 画面由运行时输出到眼镜。在 Broker 选中当前安装的运行时，保持三星外接显示为 **已镜像**。使用已发布的 v0.1.4-rc.1 时，相应的应用和 Broker 条目显示为 **Monado CV10 Preview**。

从 Pocket XR 外显模式切回内置启动器时，如果 Broker 仍选中 **Pocket XR** 或 **Pocket XR (Monado CV10)**，点选 OpenXR 应用会提示切回 Monado。可以从提示直接打开 Broker，选好后返回启动器。普通 Android 应用仍可直接打开。

- 首次使用或重新插入眼镜后，可从 Monado 菜单选择 **CV10 USB access**，预先允许访问 **HUAWEI GLASS HID**。3D 启动器也会在开启会话前请求权限，允许后会自动打开菜单。若从 2D 列表点选 XR 应用触发了权限提示，允许后会继续启动所选应用。拒绝后会停留在列表，可再次点选重试。
- 应用列表优先使用应用声明的 OpenXR 沉浸式入口。仅有普通启动入口、但在清单请求 OpenXR 权限的应用也出现在 **仅显示 XR 应用** 中，例如需要先选择串流主机的 Moonlight XR。应用仍会在手机上打开自己的设置界面。若应用两个标记都未声明，可取消 **仅显示 XR 应用** 或在 3D 菜单切到 **全部应用** 后打开。[Fold7 从应用启动器打开 Moonlight XR](history/fold7-offline-launcher.md#从应用启动器打开-moonlight-xr)
- **手机控制时**，转动手机瞄准，轻点确认，中央区域横滑翻页。从两侧标记向内滑动返回，从底部标记向上滑后松手回菜单首页，保持约一秒会将菜单和光标一起回正。标记位于 Android 系统手势区内侧，屏幕最外沿由系统处理。折叠屏上使用前台启动器触摸区，电源键沿用 Android 行为。[华为 VR Glass 手机操控方式](https://consumer.huawei.com/cn/support/content/zh-cn00776840/)
- **CF20 控制时**，已选用的 CF20 连上后自动接管指针，按确认键选择，返回键关闭 3D 菜单，横滑手柄触控板翻页。短按手柄主页键回菜单首页，长按约一秒使菜单和指针回正。断开后等待原手柄重连，也可轻触手机上的提示恢复手机控制。[原厂手机与 CF20 的切换](https://github.com/RayforLoy/HUAWEI_CV10_EVO/blob/codex/cv10-phase1/controller/oem-controller-switching.md)
- **3D 菜单光标**采用原厂的世界空间规则。白色空心环落在命中的控件上，射线在约 80% 的命中距离处收尖。指向空处时，光环移到默认远端并缩为四分之一。菜单、环和射线逐眼绘制，共用深度缓冲。
- **第三方应用中的手机手柄**　在 Monado 的应用启动器中打开「手机操控 OpenXR 应用」，可启用竖屏手柄并选择角色。默认双手模式下，单用手机时手机为右手，连接 CF20 后 CF20 为右手、手机为左手。「仅手机」固定手机为右手，「左右互换」在双手使用时将 CF20 分到左手。应用需绑定相应的 OpenXR 手部路径才能使用该手。
- 从桌面或启动器打开第三方 OpenXR 应用，当前会话开始后，手机显示竖屏控制面板。移动手机瞄准，手指在触摸板上滑动传递触点，轻点后松手发送一次确认，「菜单」发送菜单按键，「回正指向」将手机瞄准对齐头显。点「隐藏手柄」可恢复该应用的手机界面，下一次 OpenXR 会话会再次按设置显示。离开应用、结束会话或打开系统 USB 授权窗口时，面板释放手机输入。手机面板使用当前应用的窗口。
- 3D 菜单是本项目实现的 OpenXR 客户端。华为 VR 桌面 `com.huawei.vrlauncherx` 11.1.0.337 的开源公告覆盖 APK 列出的第三方依赖。尚未找到华为桌面应用的公开源码或允许 fork 的授权。[样本与许可证核对](history/fold7-offline-launcher.md#华为-vr-桌面的源码与许可)

连接 CV10 时，内置 3D 菜单使用预览包自带的 Monado CV10，以适配镜片的双目方向。未连接 CV10 时，菜单先使用 Broker 选中的运行时，系统 Loader 报告不可用时再用内置运行时重试。打开第三方 OpenXR 应用仍由各应用查询系统 Broker。若菜单也无法打开，可在启动提示中打开 Broker 确认选择后返回，或选择 **重试**。提示未弹出时，点按手机上的错误提示文字可打开启动选项。[Fold7 运行时回退实测](history/fold7-offline-launcher.md#3d-菜单的运行时回退)

Fold7（SM-F9660）使用 C9.53.6 原厂线和外接 5 V，在 **已镜像** 的 3200×1600、90 Hz 模式验证了两条路径。先从 2D 列表启动未修改的 Khronos `hello_xr` OpenGLES，返回后从 3D 菜单重进，同一个应用进程两次达到 `FOCUSED`，外屏截图和右镜均有画面。结束样例进程后，3D 菜单也能启动新的样例进程并出图。运行时在 Android 12 或更新版本直接管理 CV10 的 Presentation，覆盖 3D 菜单关闭时眼镜失去画面的故障。[Fold7 验收记录](history/fold7-offline-launcher.md)

原厂线实验中曾出现外显帧有双目蓝块、镜片仍黑。只重接眼镜端后恢复。保持接线的后续轮次再次出现相同现象，Pocket XR、2D 直启、3D 点选及上一版 Preview 均复现。[镜片与外显对照](history/fold7-offline-launcher.md#整合包的镜片黑屏复现)

如果 Fold7 的外显截图已有双目蓝块，而使用原厂线时镜片仍黑，可先保持手机端和 5 V 供电，仅重接眼镜端。重接后仍黑时，改用支持视频输出的 USB-C 线直连手机和 CV10，系统询问时允许访问 **HUAWEI GLASS HID**。Fold7 直连实拍恢复了正向的 3D 菜单和 `hello_xr` 蓝块，佩戴复测双眼显示及转头正常。[原厂线重接](history/fold7-offline-launcher.md#重接后右镜仍黑) · [USB-C 直连实测](history/fold7-offline-launcher.md#usb-c-直连恢复右镜画面)

Fold7 上注入触摸事件验证了左右内侧手势返回、底部上滑停留置中和轻触确认。竖屏控制页复测通过左右内侧返回、系统侧边返回和底边 Home。底部蓝线上快速上滑未置中，停留 460 ms 后置中一次。以 Fold7 实时姿态为基准合成四向转动，验证了俯仰修正和左右指向。早期两路外显截图与右镜实拍一致。后续在 Fold7 竖屏控制页手持转动手机，确认光点离开菜单后仍可见，菜单外点按不触发操作。[激光笔方向、越界和竖屏手势复测](history/fold7-phone-laser-axis.md)

Fold7 用 USB-C 直连 CV10，关闭系统 Wi-Fi 和移动数据并停止 Shizuku 后，从 2D 列表直启及从 3D 菜单点选原版 `hello_xr` 均显示双目蓝块。两次会话进入 `FOCUSED`，按返回后都完成 `xrDestroyInstance`。测试在断网前打开了 2D 列表，3D 菜单与两次样例会话均在断网后打开。[整机断网实测](history/fold7-offline-launcher.md#fold7-整机断网并停止-shizuku) · [应用网络隔离对照](history/fold7-offline-launcher.md#fold7-同时隔离应用网络并停止-shizuku)

整合预览提供离线启动器和 Pocket XR 外显入口。`71b7fd8` 的 ARM64 包也在关闭 Wi-Fi 和移动数据的 16 KiB Android 16 模拟器上安装成功。从 2D 列表能打开原版 Pocket XR 手机触控板并返回。内置 3D 启动器两次成功加载原生运行时，模拟器因缺少 Vulkan 扩展未能出图。这个版本的原版 `hello_xr` 自带 ARM64 库采用 4 KiB 对齐，16 KiB 系统在进入 OpenXR 前即拒绝加载。[16 KiB 断网对照](history/fold7-offline-launcher.md#android-16-16-kib-系统断网对照)

如果 2D 列表提示 **后台运行受限**，在 **应用程序信息 → 电池 → 后台运行** 中可为 Monado CV10 Preview 选择 **不受限制**。Fold7 的 One UI 8.5 即使在该设置下也曾冻结 Preview，导致 2D 直启或 3D 点选后黑屏、定格或无法返回。当前整合预览在三星 Android 16 设备上检测到外部 OpenXR 应用的 IPC 回复超时后，通过 Android 服务绑定唤醒运行时。Fold7 从 3D 菜单启动和退出原版 `hello_xr` 五轮通过，最初数秒仍可能有短暂停顿。[后台冻结对照](history/fold7-offline-launcher.md#fold7-的-3d-点选与后台冻结对照) · [Android 后台限制说明](https://developer.android.com/topic/performance/background-optimization#user-initiated-restrictions)

出现黑屏时可先核对 `hello_xr` 是否进入 `FOCUSED`，以及外显截图是否已有双目方块。有一次 Fold7 截图有图，但右镜在稳定版、预览版和独立 Android 显示测试中均未拍到图案。三星外显重新切回 3200×1600 @ 90 Hz 后仍未恢复该轮出画。[右镜输出对照](history/fold7-pocket-dual-runtime.md#右镜输出对照)

## Pocket XR 外屏兼容性

安装原版 Pocket XR 后，在 Monado CV10 菜单打开 **Pocket XR 外显兼容性**。该页面显示 CV10 的活动时序，以及 Android 是否允许 Pocket XR 在外屏启动。Fold7 的 **设置 → 已连接的设备 → Samsung DeX → 连接的显示设备** 可切换 **已扩展** 和 **已镜像**。

Pocket XR v1.6.28 自带空间桌面的显示与头部跟踪管线。CV10 的 3200×1600 画幅宽高比为 2:1，低于其 3.2 的双目判定阈值，该版本也没有 CV10 的 IMU 支持。系统允许外屏启动时可打开原版的平面入口。整合预览在同一 APK 提供本地应用启动器和 Pocket XR 外显入口。兼容模式使用 Android 虚拟屏接收 Pocket XR 的左右眼画面，再由 Monado 送到 CV10。[Pocket XR 双目判定](https://github.com/pocket-xr/pocket-xr/blob/v1.6.28/core/src/main/kotlin/com/hayeco/flipspaces/core/spatial/GlassesOptics.kt#L211-L262) · [空间模式前置条件](https://github.com/pocket-xr/pocket-xr/blob/v1.6.28/core/src/main/kotlin/com/hayeco/flipspaces/core/spatial/SpatialMode.kt#L146-L171)

Fold7 接 53 版原厂线和 5 V 外部供电时，**已镜像**为 3200×1600、90 Hz，系统拒绝直接打开 Pocket XR 外屏入口。切到 **已扩展**后，Android 可启动原版的 `GlassesHomeActivity`，外屏活动时序降为 640×480、60 Hz，Samsung DeX 同时报告当前分辨率不受支持。恢复 **已镜像**后回到 3200×1600、90 Hz。

外显诊断在 Fold7 创建 3840×1080、60 Hz 的 Android 虚拟显示屏，并由已授权的 ADB 启动原版 Pocket XR 1.6.28 的 `SpaceActivity`。内置测试卡在 CV10 两个眼区显示各自的标记，右镜拍到正立的 `RIGHT EYE`。在原版空间启动器切换到 **3D** 后，实测菜单左右眼各 1920×1080，右镜拍到完整菜单。点选 `hello_xr` 后出现提示缺少 Shizuku 授权的双目占位窗口。

从 Monado 2D 启动器取消 **仅显示 XR 应用**，可打开原版 Pocket XR 的手机触控板。Fold7 停止 Shizuku 后，这个手机入口仍能打开。兼容模式的一键外显需要预览包自己的 Shizuku 授权，创建和投射应用面板还需要原版 Pocket XR 的 Shizuku 授权。原版手机端能发现诊断虚拟屏，Android 拒绝它在该屏自行打开平面桌面 Activity，原版随后回退到基础画面。独立电脑屏幕模式已用原版 Pocket XR 把实况左右并排视频投到 CV10，并通过双眼佩戴验收。Monado 已验证用 CV10 姿态固定整个双目画面。[Fold7 停用 Shizuku 的手机入口](history/fold7-pocket-xr-display.md#fold7-停止-shizuku-后的手机入口) · [电脑屏幕 3D 佩戴实测](history/fold7-pocket-pc-sbs.md#fold7-usb-c-直连双眼佩戴验收) · [固定双目画面的实测](history/fold7-pocket-world-anchor.md)

在 Fold7 为预览包和原版 Pocket XR 分别授权 Shizuku 后，从 **打开 Pocket XR 空间** 可一键启动原版空间。原版自动打开手机触控板时，预览保持外显，触控板上的 Home 按钮可打开眼镜中的原版菜单。原版计算器和秒表已在双目外显更新画面，右镜实拍通过。USB-C 直连时，佩戴复测确认双眼都能看到时钟，启用 CV10 画面固定后转头时窗口留在原方向。恢复旧会话后若窗口不可见，可在原版启动器重新选中。[Fold7 一键启动](history/fold7-pocket-xr-display.md#fold7-双应用授权后的一键启动) · [原版应用面板和右镜实拍](history/fold7-pocket-xr-display.md#fold7-原版应用面板的实时画面) · [直连佩戴复测](history/fold7-pocket-world-anchor.md#usb-c-直连佩戴复测)

在关闭 Wi-Fi 和移动数据的 Android 16 模拟器中，给原版 Pocket XR 授权 Shizuku 后，可从其 3D 启动器打开时钟面板。ANGLE 后端的双目画面显示秒表实时更新，关闭面板后系统释放其虚拟屏。预览使用的 Shizuku 传输代码也通过了原版空间、电脑屏幕入口与显示清理测试。[模拟器面板对照](history/fold7-pocket-xr-display.md#android-16-模拟器原版应用面板) · [显示传输实测](history/fold7-pocket-xr-display.md#android-16-模拟器-shizuku-显示传输)

在 16 KiB 页的 Android 16 模拟器中，原版 Pocket XR 1.6.28 首次启动会显示内存页兼容提示。确认后，从 Monado 预览菜单可启动原版左右眼测试卡，再锁屏、解锁并在同一进程切换到普通空间。两次都收到虚拟显示首帧。该环境的 Monado 合成输出为黑屏。[菜单、首帧和退出记录](history/fold7-pocket-xr-display.md#android-16-16-kib-页正式菜单)

### Pocket XR 外显实验预览

此模式用 3840×1080 虚拟显示屏接收未修改的 Pocket XR，并用 OpenXR 的左右眼 Quad 分别提交两半画面。整合预发布和源码构建的独立进程版均提供此入口。在 Fold7 保持三星外接显示为 **已镜像**，允许运行时访问 CV10 USB，再打开 **Monado CV10 Preview → 更多选项 → Pocket XR 外显预览**。虚拟显示服务需要 Android 14 或更新版本、设备所有者用户，以及以 ADB shell 身份运行的 Shizuku 13 或更新版本。对应 Monado 包和原版 Pocket XR 的 Shizuku 授权各自独立。

点选外显模式时，如果系统询问 CV10 USB 访问权限，点 **允许** 后继续刚才选择的模式。如果预览包也缺少 Shizuku 授权，接下来会显示 Shizuku 的授权提示。拒绝 USB 后会留在预览页，可再次点选重试。[Fold7 首次授权记录](history/fold7-pocket-xr-display.md#连接-cv10-后的首次-usb-授权)

Pocket XR 外显预览始终用内置 Monado CV10 驱动镜片。第三方 OpenXR 应用仍从系统 Broker 选择运行时。日常直接启动 CV10 OpenXR 应用时，按前文的流程选择 **Monado CV10** 或 **Monado CV10 Preview**。

- **显示左右眼测试卡** 启动原版 Pocket XR 的内置测试卡。检查日志中虚拟屏 ID 与首帧，再分别确认两只镜片上的标记及其方向。
- **打开 Pocket XR 空间** 并收到画面后，使用 **打开手机控制** 切到原版 Pocket XR，同时保持眼镜会话。可在手机端 **空间窗口** 打开启动器，再在启动器中使用 **3D** 切换到双目布局。Pocket XR 创建应用面板需要它自己的 Shizuku 授权。
- **在 Pocket XR 中打开 OpenXR 应用** 是 Monado 手机端的实验入口。先在 OpenXR Runtime Broker 选择 **Pocket XR**，或者另外安装了带退出修复的可选客户端时选择 **Pocket XR (Monado CV10)**，再打开 Pocket XR 空间。收到画面后点选已安装的 OpenXR 应用。预览为应用建立独立虚拟屏，手机会切到原版 Pocket XR 触控板。打开 **Monado CV10 Preview → 更多选项 → Pocket XR 外显预览**，选 **关闭 OpenXR 应用屏** 可结束当前应用并保留 Pocket XR 空间，选 **停止预览** 会关闭整个外显会话。日常直接启动 OpenXR 应用时，在 Broker 改回 **Monado CV10** 或 **Monado CV10 Preview**。[Fold7 双运行时记录](history/fold7-pocket-dual-runtime.md#正式预览通过-shizuku-打开-openxr-应用)
- Fold7 的独立应用屏已验证原版 Khronos `hello_xr` 的 OpenGL ES 和 Vulkan APK。Vulkan 样例在同一进程重进，两次外显均显示双目蓝块。本轮右镜仍待光学验收。需要在手机主屏完成设置的 XR 应用从 Monado 的独立启动器打开。[Vulkan 实测](history/fold7-pocket-dual-runtime.md#vulkan-应用屏与同进程重进)
- **电脑屏幕 3D（左右并排）** 使用原版 Pocket XR 的独立电脑屏幕。结束正在运行的空间预览，再选 **在手机上设置电脑屏幕** 配对 Sunshine 或 Apollo。配对后返回 Monado，选择 **电脑屏幕 3D（左右并排）**。原版在虚拟屏上自动连接已配对的主机。电脑播放铺满 16:9 桌面的左右并排内容时，左半送入左眼，右半送入右眼。当前实测主机为 Sunshine，接收 1920×1080 的 Half SBS 视频。这一路径使用独立的头锁定平面。[正式入口实测](history/fold7-pocket-pc-sbs.md#fold7-正式入口配对后自动开流) · [USB-C 直连双眼佩戴验收](history/fold7-pocket-pc-sbs.md#fold7-usb-c-直连双眼佩戴验收)
- **使用 CV10 头部追踪固定 Pocket XR 画面** 让 Monado 将原版空间的双目画面固定在最初朝向，转头时画面留在原位。选择 **将画面移到当前朝向** 可重新置中。固定模式无需 VPN 授权，请在 Pocket XR 中关闭自身的头部追踪，以免画面被两次校正。[Fold7 原版测试卡实拍](history/fold7-pocket-world-anchor.md)
- **宽视场** 用于原版 Pocket XR 空间。先在 Pocket XR 手机触控板的 **菜单 → 设置 → 空间窗口 → 空间设置** 把 **在其 3D 模式下** 的屏幕大小调到 **130%**，再在 Monado 的 **Pocket XR 外显预览** 勾选 **宽视场** 后打开空间。Fold7 上原版每眼视场从约 44.2° × 25.8° 扩大到 55.7° × 33.1°，Monado 同步扩大双目画布，可与 CV10 画面固定一起使用。超出扩大后的原版视场的内容仍需移动窗口或用触控板转动空间。
- **打开 Pocket XR（CV10 光学视场）** 需要另装 **Pocket XR CV10**（`org.cv10.pocketxr`）。为适配版单独授权 Shizuku，并在 Monado 启用本机 VPN 头部追踪。该入口以 95° × 95° 的 CV10 每眼视锥提交 OpenXR 投影层，默认接收 3200×1600 的双目画面。超分辨率选项会改变光学模式虚拟屏的尺寸，由适配版 Pocket XR 在源端重绘。原版入口仍可用于 3840×1080 兼容模式和电脑 3D 投屏。[Pocket XR CV10 build and display integration](https://github.com/RayforLoy/pocket-xr-cv10/blob/codex/cv10-optics/docs/CV10.md)
- **通过 Android 本机 VPN 接入头部追踪（实验）** 可在打开空间前选用。勾选后由 Android 询问 VPN 授权。打开空间时，预览只为当前选中的 Pocket XR 包宣告本机 `169.254.2.1/32`，将 CV10 的 HID 样本和控制心跳送到该地址的两个 TCP 端口。选择 **停止预览** 会释放连接和 VPN 接口。[姿态与重连验证](history/fold7-pocket-xr-imu.md#android-16-原版-pocket-xr-接入)
- 返回预览页选择 **停止预览**，会释放本次虚拟显示和 OpenXR 会话。手机通知也可提供停止入口。

如果 Fold7 提示外显启动超时，先确认 Shizuku 正在运行。如日志出现 `ShizukuServiceStarter: provider is null`，可在 **Shizuku 的应用程序信息 → 电池 → 后台运行** 允许它在后台启动服务，再从预览页重试。[Fold7 后台限制对照](history/fold7-pocket-pc-sbs.md#fold7-正式入口配对后自动开流)

Fold7 上通过已有 ADB 权限调用同一显示服务，验证了受信任虚拟屏的创建和释放。原版空间在该屏输出双目测试卡和应用占位窗口。默认电脑屏幕配对 Sunshine 后在该屏收到了 1920×1080 的 HEVC Half SBS 视频。[服务与默认电脑屏幕实测](history/fold7-pocket-shell-service.md)

临时在 Broker 选用原版 **Pocket XR** 做诊断时，原版运行时接管 `hello_xr`，内置 Monado CV10 同时将游戏图层送到 CV10。将样例放到独立虚拟屏后，四轮在手机主屏打开 Pocket XR 触控板并连续出帧约九秒，CV10 右镜拍到蓝色方块。三星 Freecess 仍可能冻结原版进程。一次冻结后，在 Monado 点按 **打开手机控制**，三星解冻原版进程，同一游戏图层恢复出帧。样例在退出时仍可能于原版运行时卸载后崩溃。长时间运行和正常退出仍待验证。[双运行时实测](history/fold7-pocket-dual-runtime.md)

Pocket XR 的原版空间菜单按普通应用面板启动和恢复应用。声明沉浸式入口的应用可使用 **在 Pocket XR 中打开 OpenXR 应用**，由原版运行时在独立应用屏输出。需要先在手机界面完成连接或设置的 XR 应用，可切回 Monado CV10 Broker 后从内置启动器打开。普通 Android 应用仍可从原版空间菜单点选。Monado CV10 直出运行时会拒绝来自 Pocket XR 普通面板的 OpenXR 连接，以免恢复的应用夺走空间的输出。[同机入口对照](history/fold7-pocket-xr-display.md#原版空间菜单中的-openxr-应用)

如果 Pocket XR 每次进空间都恢复一个旧的 OpenXR 普通面板，可在空间仍开启时到手机上的 **Pocket XR → 空间窗口**，找到该应用，按它右侧的 **关闭**。这样可移除保存的面板并保留同一空间的其他应用。之后用 Monado 的 **在 Pocket XR 中打开 OpenXR 应用** 启动该应用。[Fold7 旧面板移除记录](history/fold7-pocket-dual-runtime.md#移除恢复的-openxr-普通面板)

退出空间后，若从内置列表点选的应用仍停在黑屏，可按 Home 回手机桌面。在 Monado 2D 列表长按该应用，打开系统应用详情，选择 **强制停止** 并确认。返回列表后再点按同一应用。[Fold7 恢复路径](history/fold7-pocket-dual-runtime.md#从-pocket-xr-普通面板切回-monado)

**Pocket XR (Monado CV10)** 是可选的 ARM64 客户端包。它加载带卸载修复的库，渲染服务由未修改的官方 Pocket XR 1.6.28 或 1.6.31 提供。客户端包与 Monado 预览需使用同一签名。Fold7 用原版 1.6.28、正式 Shizuku 入口和此客户端，已复测 `hello_xr` 出画、正常关闭和同进程重进。选择原版 Broker 条目 **Pocket XR** 时，应用加载上游自带的客户端，可能遇到退出崩溃。[客户端构建](src/xrt/targets/pocket_client_android/README.md) · [Fold7 正式入口实测](history/fold7-pocket-dual-runtime.md#正式预览通过-shizuku-打开-openxr-应用)

Fold7 在关闭 Pocket XR 的独立 Vulkan 应用屏时，三星后台管理可能冻结样例。应用屏可以释放，空间仍保留。应用自身退出可能等到系统约十秒后的解冻，单次观测也有解冻后再次冻结的情况。[Vulkan 退出对照](history/fold7-pocket-dual-runtime.md#usb-c-直连下的-vulkan-退出冻结对照)

Fold7 的 CV10 IMU 通过 ADB 回环将实时样本送到隔离的 Android 16 模拟器。模拟器关闭 Wi-Fi 和移动数据，Shizuku 未运行。未修改的 Pocket XR 通过模拟器内的本机 VPN 收到约 1000 Hz 的静置数据并解算姿态。此前的模拟姿态测试还覆盖了转动方向、停止 VPN 和同进程重连。Fold7 上已验证 VPN 授权可取消。Fold7 同机实时连接及佩戴转头仍待验收。[实时 IMU 对照](history/fold7-pocket-xr-imu.md#实时-fold7-采样送入原版-android-应用)

## 原厂光学与软件瞳距

CV10 驱动使用 P40 Pro+ 原厂运行时实测的双眼投影参数。每眼水平和垂直投影视场均为 95°。左右眼的 RGB 径向畸变分别应用，并保留实测的逐眼光心偏移。用 P40 Pro+ 上原厂运行时提交给 GPU 的 2,178 个网格顶点核对，Monado 的归一化 UV 单轴最大误差约为 0.0000092。95° 是运行时投影参数，镜片实际可见范围仍需单独测量。[采集、对照和 Fold7 验证](history/fold7-oem-optics.md)

在 **更多选项 → CV10 软件瞳距** 可将 OpenXR 的左右眼位间距设为 55–71 毫米，默认 63 毫米。Fold7 上在同一个 OpenXR 会话内调节并恢复默认后，`xrLocateViews` 实测为 63.000 → 68.000 → 63.000 毫米。[眼位实测](history/fold7-oem-optics.md#fold7-软件瞳距)

## 渲染质量

打开 **Monado CV10 → 更多选项 → CV10 渲染质量**。更改超分辨率档位后，重新打开 OpenXR 应用或 Pocket XR 光学会话。CV10 的物理双目输出保持 3200×1600。

| 选项 | 行为 |
| --- | --- |
| **关闭超分辨率** | 初始设置。保留普通 OpenXR 的推荐尺寸和 Pocket XR 光学入口原有的每眼 1600×1600。 |
| **华为默认中心密度** | 超分辨率预设。华为桌面和 VR 视频 High 档用 1280×1280 的独立中心纹理覆盖全视场投影范围的 0.4 倍。Monado 兼容普通双目提交，按等效密度请求每眼全视场 3200×3200。 |
| **自定义超分辨率** | 设定每眼正方形画面的边长，范围 1600–3200 像素。标准 OpenXR 应用根据各自的规则采用推荐尺寸。Pocket XR CV10 光学模式会请求对应尺寸的源画面。 |
| **启用增强渲染模式（中心锐化）** | 对应 `enableEnhancedRenderMode`，默认关闭，可与任一尺寸档位搭配。 |

Pocket XR 光学入口从每眼 1600 提到 3200 时，源画面的像素数增至四倍。普通 OpenXR 多图层合成还受现有中间纹理尺寸限制。原版 Pocket XR 兼容入口和 Half SBS 来源沿用各自的捕获规格。当前新增档位和增强开关尚待构建及设备验证。[原厂档位和增强处理证据](https://github.com/RayforLoy/HUAWEI_CV10_EVO/blob/codex/cv10-phase1/docs/cv10-oem-rendering.md) · [Fold7 已知问题](https://github.com/RayforLoy/HUAWEI_CV10_EVO/blob/codex/cv10-phase1/docs/fold7-openxr-known-issues.md)

### Pocket XR 四视图实验

光学视场桥接会重复使用已提交的交换链图像，直到 Android 虚拟屏交付新帧。写入新图像时可丢弃旧颜色内容，省去图块 GPU 的旧内容载入。

将 `debug.xrt.XRT_COMPOSITOR_COMPUTE` 设为 `true`，将 `debug.cv10.pocket_quad` 设为 `1`，结束 Monado 进程并重新进入 Pocket XR 光学视场预览。运行时枚举出四视图后，桥接为每眼提交一张上限 1600 × 1600 的全视场图和一张原采样密度的中心图。中心覆盖全视场投影平面宽高的 40%。3200 档对应每眼 1280 × 1280，2240 档对应 896 × 896。四张图共用一个紧凑的交换链。当前中心图取自 Pocket XR 已渲染的双目源，保留对应区域原有采样。具备四视图渲染能力的 OpenXR 应用也可独立绘制中心和全视场图。

检查 `Cv10SpatialLauncher` 的 `CV10 four-view atlas` 日志确认启用了四视图。如果运行时未枚举四视图，桥接继续使用双目投影。比较两条合成路径时请保持中心锐化设置一致。复测双目路径可将上面两项属性分别设为 `false` 和 `0`，再重启 Monado 进程。

Fold7 上的 CV10 Compute 合成使用同时支持图形和计算的 Vulkan 队列。当前四视图仍先将中心与全视场混合到中间纹理，再做镜片畸变校正。它可用于验证四视图提交，实际呈现速度受这两道合成影响。

## 手柄与定位套件

- **更多选项 → CV10 蓝牙手柄** 提供 CF20 的扫描、配对和输入诊断。系统提示时允许附近设备访问。
- **第三方 OpenXR 应用中的 CF20 回正**　首次连接时，把手柄前端沿视线水平指向前方。没有保存过贴合校准的 CF20 会用头显的水平方向对齐右手的握持与瞄准姿态。游戏或影院内想调整正前方时，面向目标、手柄前指，长按手柄 Home 约一秒。运行时重设当前应用的 `LOCAL` 和 `LOCAL_FLOOR` 水平朝向，并对齐手柄；该次手柄偏移只在本次连接有效。使用 `STAGE` 的固定场地和应用自己锁定的内容有各自的坐标原点。3D 启动器内的相同长按由启动器处理，继续同时回正菜单与指针。
- **CV10 蓝牙手柄 → Huawei 6DoF Kit · 定位交互套件** 提供校准读取、Y8 双目、头部定位和左右手柄的实验入口。运行原厂头部 SLAM 需要用户自行导入对应的 ARM64 `libarvr_slam.so`。套件的头手完整 6DoF 尚待真机验收。[连接与校准流程](https://github.com/RayforLoy/HUAWEI_CV10_EVO/blob/codex/cv10-phase1/controller/README.md)

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

用 `-UnifiedPreview` 可生成与稳定版并存的单个双 ABI 安装包。

```powershell
.\scripts\build-cv10-apks.ps1 -OutputDir .\build\cv10-unified -UnifiedPreview
```

仅构建一个架构时，通过 `-Pcv10Abi` 指定，例如 `.\gradlew.bat "-Pcv10Abi=arm64-v8a" :src:xrt:targets:openxr_android:packageOutOfProcessRelease`。`x86` 和 `x86_64` 可用同一参数显式构建，例如用于模拟器调试。Gradle 把未签名包输出到 `src/xrt/targets/openxr_android/build/outputs/apk/outOfProcess/release/`。上述脚本使用本机 Android 开发证书签名，并校验对齐和签名。首次构建请允许 Gradle 下载依赖。

需要与正式包并排安装时，构建 **Monado CV10 Preview**，并在 Broker 选择同名运行时。

```powershell
.\gradlew.bat "-Pcv10Abi=arm64-v8a" "-Pcv10LauncherPreview=true" :src:xrt:targets:openxr_android:assembleOutOfProcessDebug
```

预览包位于 `src/xrt/targets/openxr_android/build/outputs/apk/outOfProcess/debug/`。Gradle 同时为 APK 和原生 IPC 客户端设置预览包名 `org.cv10.monado.out_of_process.launcher_preview`。默认静态 C++ 标准库配置的 ARM64 APK 已通过所有 `PT_LOAD` 段的 16 KiB 对齐检查及 `zipalign -c -P 16 -v 4`。使用 4 KiB 内存页的 Fold7 在更新对齐包后未再显示兼容提示。16 KiB Android 16 模拟器已验证安装和运行时加载。[验收记录](history/fold7-offline-launcher.md#16-kib-系统安装与启动失败回归) · [Android 16 KiB 内存页支持](https://developer.android.com/guide/practices/page-sizes)

历史 APK 和对应实验说明见 [apk/archive](apk/archive) 和 [history](history)。上游项目说明与许可证见 [README.upstream.md](README.upstream.md) 和 [LICENSE](LICENSE)。

### 手机和 CF20 同时作为 OpenXR 输入设备

自动模式下，手机单用时提供右手。收到 CF20 输入后，CF20 占右手，手机占左手。交换模式将 CF20 放到左手，手机维持右手。仅手机模式关闭 CF20 的输入和系统 Home 手势。两路设备独立运行，报告过期 250 ms 后会释放对应角色。运行中连接、断开或交换均通过 OpenXR 的交互配置变化通知客户端。6DoF 套件使用其原有左右手设备。

支持 `XR_HUAWEI_controller_interaction` 的应用可绑定 Home、返回、音量、二值扳机、触控板按压与触摸、触控板 XY 以及握持和瞄准姿态。CF20 的简单手柄绑定继续将扳机或触控板确认合并为 `select/click`。CF20 固件的已核实协议只提供输入，此实现未声明可用的振动输出。手柄姿态的角速度由连续有效四元数转换到基准空间，以 rad/s 返回。首次采样、回正及跨越超过 100 ms 的间隔后，需要新的有效样本对。原始陀螺仪的轴向和刻度可使用 `Cv10Cf20Gyro` 的 DEBUG 日志对照。该日志按报告到达时间配对四元数和原厂 SDK 三轴刻度。

手机触控板支持 OpenXR 核心 Google Daydream 交互配置 `/interaction_profiles/google/daydream_controller`。在手机所在手的 `/input/trackpad` 上读取 XY（右、上为正），`/touch` 表示接触，`/click` 表示轻点确认。`/input/select/click` 也接收轻点确认。只提供 Khronos simple 配置的应用继续使用原有 select、menu、grip 和 aim。手机和 CF20 各自报告实际输入，手机不提供 CF20 电量。

### 第三方应用读取 CF20 和手机手柄模型

公开 APK 支持在 Monado CV10 首页的「更多选项 → 导入已校准的 CF20 手柄模型」选择本地 GLB。运行时校验文件摘要，保存到应用私有目录。重新打开 OpenXR 应用后，使用真实 CF20 的那只手可以提供模型。未导入时，CF20 的按键、瞄准和握持位姿仍可用。手机触控板启用并产生姿态后，运行时可独立提供随包分发的手机模型。提取步骤和已校准文件的摘要见[模型说明](https://github.com/RayforLoy/HUAWEI_CV10_EVO/blob/codex/cv10-company-handoff/controller/cf20-openxr-model.md)。

自行构建也可指定 `-Pcv10ControllerModel=D:/path/to/cf20.glb` 嵌入本地模型。坐标约定为握持原点、按键朝上、前端朝 OpenXR 局部 −Z。公开 APK 内置原创手机模型，机身为 73 × 149 mm，网格局部 +Z 指向屏幕正面，+Y 指向手机顶边。手机模型保留实际俯仰与滚转，回正校准水平朝向。手机和 CF20 共用肩腕位置估算；手机模型和射线共用同一个手位。手机射线沿用平放时沿顶边、竖持时朝背面的瞄准校准。手机和 CF20 的 GLB 使用各自的缓存 UUID。

应用同时启用 `XR_EXT_render_model` 与 `XR_EXT_interaction_render_model`，为左手和右手分别绑定动作，并调用 `xrSyncActions`。收到 `XrEventDataInteractionRenderModelsChangedEXT` 后重新枚举模型。用 `xrGetRenderModelPropertiesEXT` 取得当前会话的缓存 ID，再通过 `xrCreateRenderModelAssetEXT` 和 `xrGetRenderModelAssetDataEXT` 两次调用取得 GLB。两种模型都是静态网格，动画节点数为 0。请对每个模型调用 `xrGetRenderModelPoseTopLevelUserPathEXT` 识别左右手，并为它单独创建模型空间。角色交换或设备断开后，重新枚举的 ID 反映当前来源，旧模型空间不再定位。

模型位置由 `xrCreateRenderModelSpaceEXT` 创建的空间给出。当预测显示时刻同时具备位置和方向的 `TRACKED` 标志时绘制。通过 `xrGetRenderModelPoseTopLevelUserPathEXT` 确定模型当前位于哪只手。使用该手 `/input/aim/pose` 的局部 −Z 发射射线。驱动已计入射线原点相对握持处向前 69 mm、向上 20 mm 的位移。模型只对应物理 CF20，手机虚拟手柄和 6DoF 套件均使用自己的姿态来源。

## DisplayPort 通道诊断

打开 Monado CV10 启动页的菜单，选择 **CV10 DisplayPort 状态**。OpenXR 运行时接到 CV10 外屏时也会在后台读取一次。读到两条通道或 Type-C 双通道线索时，运行时会尝试在手机上显示相应提示，日志标签为 `Cv10DisplayPort`。

- **实际协商值**来自与 CV10 EDID 匹配的活动 DP 接口。运行时用该接口的 DRM AUX 读取 DPCD，结合当前通道数和各通道的链路训练状态确认本次读数。[Linux DPCD 寄存器定义](https://github.com/torvalds/linux/blob/v6.12/include/drm/display/drm_dp.h)
- **间接线索**来自可读的 Type-C DP Alt Mode 引脚分配。D 为 DP 预留两条通道，C 或 E 为 DP 预留四条。此时实际协商值仍显示为不可读取。[Linux Type-C DisplayPort 状态 ABI](https://github.com/torvalds/linux/blob/v6.12/Documentation/ABI/testing/sysfs-driver-typec-displayport)
- **不可读取**可能出现在系统缺少接口、限制访问、训练尚未完成或链路无法与 CV10 对应时。Android 识别到 CV10 时，页面也会显示当前分辨率和刷新率，便于记录闪屏现场。

有群友怀疑双通道配置与闪屏有关，因果关系待验证。诊断使用运行时现有权限进行只读查询。

## v0.1.2 验证

四个独立 APK 均核对了单一 ABI、核心 OpenXR 和 service ELF、API 26 最低版本、签名和 ZIP 对齐。Android 11（API 30）模拟器上，x86_64 与 x86 均通过全新安装并启动 AboutActivity。测试设备支持 x86_64、x86；本次未在 Android 11 ARM 真机或 CV10 镜片上复测。现有实验与旧版 Fold7 硬件记录见 history 目录。
