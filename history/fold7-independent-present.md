# Fold7 与 CV10 独立呈现

2026-11-28 在 SM-F9660（F9660ZCUDBZJ9）、CV10、原厂线盒和 53.6 MIVR 4.70 V 试验固件上采集。折叠状态固定，手机外侧面板是唯一点亮的内建面板。CV10 的活动模式始终为 3200×1600 @ 90 Hz。以下的硬件提交频率来自 Perfetto，实际呈现来自 CV10 图层的 SurfaceFlinger 时间戳，与有效的 Vulkan 实际呈现时间交叉核对。

## 手机亮屏 60 Hz 时的限制

| 原版 Hello XR，同一会话 | SurfaceFlinger 调度 | CV10 HWC 调用/秒 | 手机 HWC 平均调用时间 |
| --- | ---: | ---: | ---: |
| 手机亮屏 60 Hz，系统选择时钟 | 约 60.3/s | 60.32 | 0.23 ms |
| 手机亮屏 60 Hz，指定 CV10 为调度时钟 | 11.1/22.2 ms 间隔混合 | 60.32 | 13.95 ms |
| 手机亮屏 60 Hz，指定 CV10，强制 GPU 客户端合成 | 仍约 60.3/s | 约 60.3 | 13.50 ms |
| 手机面板 OFF，指定 CV10 为调度时钟 | 约 90.0/s | 89.98 | 无调用 |

指定 CV10 后，调度目标周期为 11.111 ms。手机亮屏时，SurfaceFlinger 主线程在手机的 `HwcPresentOrValidateDisplay` 中，每次平均睡眠 13.45 ms。420 次调用与 Qualcomm Composer 3 服务的同步 Binder 事务精确关联，服务端平均在 13.42 ms 后返回。强制 GPU 合成的复测得到 419 次对应事务和 13.46 ms 的主线程睡眠。手机面板熄灭后，这组调用消失，同一会话的 CV10 图层实呈为 90.0/s，p99 间隔 11.1 ms。

追加的 HAL 与调度追踪复现了等待链。手机亮屏、强制 CV10 时钟时，Qualcomm Composer 的 `DRMAtomicReq::Commit` 平均用时 16.27 ms。内建面板的 `crtc_commit:288` 在 `sde_kms_wait_for_commit_done` 平均等待 15.98 ms，系统自动选择手机时钟时该等待为 2.96 ms。344 次慢调用中，Composer 的工作线程在 DRM 提交返回后平均 0.045 ms 唤醒正在等待的 Binder 处理线程。熄灭手机面板后，`crtc_commit:288` 消失，CV10 的 `crtc_commit:301` 仍提交，CV10 HWC 为 90.0 次/秒。该批追踪没有取得可识别确切硬件信号的 fence 或 vblank 事件，因此将阻塞定位到内建面板的 DRM 提交完成等待。

临时的每显示器 60 Hz 模式偏好与撤销系统峰值限制组合会将手机物理模式切到 120 Hz，已排除该组读数并恢复原设置。

用直接渲染的工作区再次采集时，手机一直亮屏 60 Hz，采样前后两张手机屏幕截图的 SHA-256 相同。七秒内仍有 419 次手机屏 HWC 调用，平均 12.51 ms，CV10 HWC 为 60.32 次/秒。将手机控制界面静置也会复现。

系统修复需保证当手机保持 ON/60 时，外屏可以按自身 90 Hz 的节拍提交。应检查当只有 CV10 有新内容时，SurfaceFlinger 是否仍把手机纳入同批 Present/Validate，以及 Qualcomm Composer 能否异步处理不同显示器的提交和栅栏。AOSP 的并行路径需要所有参与的 HWC 输出都声明支持，SurfaceFlinger 还会等待当批输出完成。单独并行化可能仍受超过 11.1 ms 的等待限制，需用同一亮屏对照验证。[AOSP Scheduler](https://android.googlesource.com/platform/frameworks/native/+/9095117f55f3a44a62cb249ab1b952fcb4ca8a42/services/surfaceflinger/Scheduler/Scheduler.cpp#204) · [AOSP CompositionEngine](https://android.googlesource.com/platform/frameworks/native/+/1fc9e7f03d5719e8043a4c0b415278860f94badd/services/surfaceflinger/CompositionEngine/src/CompositionEngine.cpp#97)

## Pocket 工作区性能对照

每眼 2240×2240，GLES 固定中心 1×1、外围 2×2。两条路径保持同一 Bilibili 工作区，使用 34° 峰峰值、3 秒周期的模拟左右转头，连续比较约 15 秒；动态推荐内容随页面变化。熄屏对照的电池温度在 35.1–35.7°C，系统热状态分别为 1→2 和 2→2。右镜固定相机确认内容出画，刷新率由呈现时间戳统计。延迟是模拟头动信号的相位拟合，不是光学测得的运动到光子时延。

| 手机面板 | 工作区输出路径 | 转头期间 CV10 实呈/秒 | 转头期间 CV10 新源帧/秒 | 拟合相位延迟 | Monado GPU p95 |
| --- | --- | ---: | ---: | ---: | ---: |
| ON，60 Hz | 经额外的虚拟显示输出 | 约 60.3 | — | 83.4 ms | — |
| ON，60 Hz | EGL 直接提交到 Monado 消费端 | 60.31 | — | 71.2 ms | — |
| OFF | 经额外的虚拟显示输出 | 89.60 | 84.40 | 67.0 ms | 5.52 ms |
| OFF | EGL 直接提交到 Monado 消费端 | 89.73 | 85.93 | 47.7 ms | 3.03 ms |

直出保留运行 Android 窗口的工作区显示，省去的是整个双目输出的额外虚拟显示合成。熄屏直出时，有效 Vulkan 实际呈现和 SurfaceFlinger 的时间戳 2504/2504 对齐。被消费的 2367 个唯一帧时间戳都找到了对应的 EGL 提交。模拟转头期间，EGL 提交完成到 CV10 实呈的 p50/p95 为 35.70/36.01 ms。省掉最后一段虚拟显示还保住了源帧时间戳，可用作下一步绑定真实入画姿态的键。

## 直出帧的姿态记录

按与 ChatGPT 6 极高复核的方案，Pocket XR 通过每次会话独有的非阻塞管道发送 EGL 时间戳、姿态预测时间、回正序号和实际使用的中央、上边、下边姿态。Monado 仅在 `SurfaceTexture` 锁存的图像具有完全相同的时间戳时配对。应用或通道不可用时继续出画。本轮实现只验证配对，仍沿用现有渲染补偿。

原厂线、手机 ON/60、真实眼镜 IMU、每眼 2240×2240 并开启 GLES FFR。实测两段日志分别配对 380 和 425 个帧，采样窗口内无缺失和时间戳倒退。回正对照覆盖旧序号的 133 帧和新序号的 292 帧，序号单向递增。两段分别观察到 3 和 8 帧上边、下边的姿态不同。右镜固定相机拍到回正后的工作区。两个配对样本统计的是 Monado 接收的源帧，CV10 面板的实际呈现仍以独立的 SurfaceFlinger 数据为准。

通过同一 VPN 链输入可重复的 32° 峰峰值三轴运动，观测到全部 2782 个锁存时间戳精确配对且单调递增。yaw、pitch 和 roll 的实测主轴峰峰值分别为 31.41°、31.40°、31.43°，与模拟输入的相关系数均为 +0.994。测试在 pitch 段观察到 9 帧上边与下边使用不同姿态，yaw 和 roll 的运动采样内两者相同。姿态轴和符号已验证到 Monado 的捕获端，逐行补偿的目标时序和应用重投影后的光学校验待做。测试结束后恢复了使用真实 IMU 的构建。

将最终重投影统一到 Monado 前，仍需用 CV10 实际呈现时钟预测目标姿态，并覆盖重复帧、丢帧、回正以及逐行姿态插值。

## 可选熄屏 90 Hz

实验版在 Pocket 预览首帧到达后提供「熄灭手机屏幕以启用 CV10 90 Hz」。需要已有的 Shizuku 桥接授权和 CV10 时钟，以及用户在该次会话里确认。熄屏期间工作区继续运行，最多维持十分钟。关闭预览或它的进程退出时恢复 Android 对手机面板和合成时钟的控制。

实机用真实 CV10 IMU 和直接输出进入该模式。确认后，手机两个面板均报告 committed OFF，外显维持 ON，CV10 图层在六秒采样中的滚动呈现为 90.0/s，右镜拍到工作区。强行结束预览客户端后，手机面板恢复 ON/60，SurfaceFlinger 的强制外屏时钟随即清除。此轮复测的是客户端退出，十分钟上限和物理拔线恢复另待独立验收。

最终构建重复验证了出画和退出回收。熄屏切换后的前两个滚动样本为 87.9/s，随后三个样本均为 90.0/s，p99 间隔 11.1 ms。

## 本机证据

原始数据保存在主项目的 `.research/monado-rc2-fold7/pocket-performance/ffr-quad/`。同级 `head-motion/` 留有 `*-cadence.txt`。设备标签和实测 APK 摘要用于匹配本机安装，文档中的性能读数来自实验构建。

- `hello-policy-{lit-auto,lit-forced,off-forced}-trace-summary.txt`、`hello-policy-hwc-{thread-state,binder}.txt` 和对应 Perfetto trace，记录 Hello XR 的调度对照。

- `hello-composer-{lit-auto,lit-forced,off-forced}.perfetto-trace`、`composer-waits-summary.txt` 和 `trace-fold7-composer.ps1`，记录 Qualcomm DRM 工作线程及 Composer Binder 等待的对照。
- `pocket-policy-static-phone-summary.txt` 和 `hello-policy-static-phone-hashes.txt`，记录工作区直出时静置的手机界面。
- `compare-lit-vd-yaw-summary.txt`、`compare-lit-direct-retry-yaw-summary.txt` 和 `resume2-off-{vd,direct}-yaw-summary.txt`，记录模拟转头和逐帧匹配。
- `panel-lease-visual-{before,active,client-death,lifecycle}.txt`、`panel-lease-visual-right.jpg`，记录实验开关的画面与回收。

- `panel-current-{before,active,client-death,lifecycle}.txt`、`panel-current-right.jpg`，记录最终构建的回归。
- 直出实机 Monado APK SHA-256 `2f580e5286be0c68bb9af1a7b8b1544cd684f9fa5ca939f9d6ecf5fe26a9c3ef`。
- 熄屏开关实机 Monado APK SHA-256 `8a33b272e885ada19920213d9bd8d46c54b6cba8c85285f9ca6fa6107d0601b0`。

- 熄屏开关最终构建 SHA-256 `96acb95aac01da190cde6297506cd4e66081cafac8ab7a2cb7361855016ffd9a`。
- Pocket XR 直接输出 APK SHA-256 `e8268306621b6ccd1db85757427e2fffcc413fde79c7a37c584657815b93325b`。
- `frame-pose-live-matches.log`、`frame-pose-recenter.log` 和 `frame-pose-recenter-right.jpg`，记录逐帧配对、回正序号切换和右镜实拍。
- `frame-pose-replay-matches.log`、`frame-pose-replay-summary.txt` 和 `analyze-frame-pose-replay.py`，记录三轴合成输入的配对与方向；同目录的 `build-direct-frame-pose-replay.ps1` 和 `check-direct-frame-pose-replay.ps1` 复现采样。
- 姿态记录 APK SHA-256。Monado 为 `10149c55d75a963c061d17905f11ffcd288518d49a2edcffdf3888c97e153f5c`，Pocket XR 为 `421e7a750935fd4decd990af286cf0ccefcbdf175e2b9b9ba8e837c850aa69ce`。
