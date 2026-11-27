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

用直接渲染的工作区再次采集时，手机一直亮屏 60 Hz，采样前后两张手机屏幕截图的 SHA-256 相同。七秒内仍有 419 次手机屏 HWC 调用，平均 12.51 ms，CV10 HWC 为 60.32 次/秒。

### 图层快照对采样的影响

另一次直出采集在完全静置手机界面时仍复现约 60.3 次实呈/秒。图层追踪的 395 个有效快照中，手机前台始终为缓冲帧 73，CV10 有 395 个不同的帧。两路 `HwcPresentOrValidateDisplay` 平均分别为 0.33 ms（手机）和 0.35 ms（CV10）。CV10 合成调用中有 398 次 `drawLayersInternal for HUAWEIAV03`。Composer dump 显示外屏的全屏 `SurfaceView` 被分配为 `CLIENT`，DPU 扫描的是 SurfaceFlinger 的 `GPU_TARGET`。

此时应用 VSync 节拍接近 90 Hz，SurfaceFlinger 的间隔混合了约 11.1 ms 和 22.2 ms。CV10 的 `PrevGpuFrameMissed` 为 171/397，`PrevHwcFrameMissed` 为 0/397，手机两项均为零。该批次开启了逐帧图层快照。下文的轻量追踪复现了快照怎样转移主线程的耗时，因此这批快照保留作图层内容和 HWC 合成类型的证据。

常规时间戳的 Hello XR 七秒内调用了 421 次 `commit`、421 次 `composite` 和 421 次 `present`。直接工作区有 397 次完整的 `commit`。尽早呈现的路径把 CV10 的 `PrevGpuFrameMissed` 从 133/421 增至 391/421，两边进入 `commit` 的次数相同。

同一原版 Hello XR 在手机 ON/60、CV10 作为调度时钟时，分别用 Vulkan MAILBOX、FIFO 和 IMMEDIATE 复测，外屏均约 60.3 次实呈/秒。还在同一实验 APK、默认 MAILBOX 和电池温度 34.6°C 下取消 `desiredPresentTime`，保留 present ID 和实际时间反馈。对照两次均为约 60.3 次实呈/秒。记录验证了实验组有效反馈的 `desired` 为零。未将该选项纳入正常构建。[Vulkan present time](https://docs.vulkan.org/refpages/latest/refpages/source/VkPresentTimeGOOGLE.html)

### 关闭逐帧快照后的调度对照

原版 Hello XR，手机 ON/60，指定 CV10 为调度时钟。`sfdo schedule-composite` 在指定组短时间循环调用，并把 Monado 实验合成预算从默认 3 ms 提到 8 ms 以提前交帧。轻量追踪保留 gfx、hal、线程调度和 FrameTimeline。无快照两轮的电池温度为 33.8–33.9°C，热状态为 0。带快照的提前交帧参考为 34.3°C，热状态为 1。

| 七秒追踪 | SurfaceFlinger 定时回调/秒 | 主线程 `commit`/秒 | CV10 实呈/秒 | 手机 HWC 平均耗时 |
| --- | ---: | ---: | ---: | ---: |
| 有逐帧快照，定时请求合成并提前交帧 | 76.20 | 60.34 | 60.34 | 0.21 ms |
| 无逐帧快照，常规交帧 | 61.78 | 60.31 | 60.39 | 13.46 ms |
| 无逐帧快照，定时请求合成并提前交帧 | 76.34 | 60.29 | 60.34 | 14.31 ms |

有快照时，主线程每次 `takeLayersSnapshotProto` 的中位耗时 13.68 ms，`postComposition` 中位耗时 14.43 ms。关闭快照后，`postComposition` 中位耗时降到 0.26 ms，而同步的 `present` 中位耗时增至 15.64 ms。提前交帧组的 CV10 HWC 调用平均 0.22 ms，手机 HWC 调用平均 14.31 ms；532 次定时回调中 438 次落在主线程执行上一轮 `present` 期间。结果复核了前面的手机面板 DRM 等待链，也解释了为什么开启图层快照后，HWC 会较快返回但实际频率相同。

关闭快照的提前交帧组里，Monado 每秒成功提交 90 帧。206 个空出的 CV10 扫描时隙全部已有更新帧在合成器名义唤醒前完成 Vulkan 提交及 GPU 渲染，名义唤醒取实际扫描节拍减去已追踪的 8.333 ms 合成预算。该判断基于生产者的时间戳，SurfaceFlinger 实际锁存和导入栅栏的时点另计。采集额外三秒时 SurfaceFlinger 的 237 个实际呈现时间戳与有效 Vulkan 反馈逐一相同。

同一电池温度和热状态下，Pocket XR 使用 2240×2240 每眼、GLES FFR 和直出，再做一轮关闭快照的静止采样。Monado 每秒提交 89.71 帧，CV10 约 60.3 次实呈/秒，手机和 CV10 HWC 平均分别为 12.80 ms 和 0.32 ms。额外三秒的 241 个呈现时间戳逐一吻合。采集结束恢复了手机 60 Hz 下的系统自动合成时钟和 Monado 参数。

系统修复需保证当手机保持 ON/60 时，外屏可以按自身 90 Hz 的节拍提交。应检查当只有 CV10 有新内容时，SurfaceFlinger 是否仍把手机纳入同批 Present/Validate，以及 Qualcomm Composer 能否异步处理不同显示器的提交和栅栏。AOSP 的并行路径需要所有参与的 HWC 输出都声明支持，SurfaceFlinger 还会等待当批输出完成。单独并行化可能仍受超过 11.1 ms 的等待限制，需用同一亮屏对照验证。[AOSP Scheduler](https://android.googlesource.com/platform/frameworks/native/+/9095117f55f3a44a62cb249ab1b952fcb4ca8a42/services/surfaceflinger/Scheduler/Scheduler.cpp#204) · [AOSP CompositionEngine](https://android.googlesource.com/platform/frameworks/native/+/1fc9e7f03d5719e8043a4c0b415278860f94badd/services/surfaceflinger/CompositionEngine/src/CompositionEngine.cpp#97)

Android 16 QPR2 上游还可把部分 GPU 虚拟显示从主批次移出，这条路径仍将物理显示放在同一组。可在系统树做两个可独立回退的对照。先按输出的损伤和待处理事务跳过静置内屏的不必要提交，再将需要更新的内屏和 CV10 提交从共同的同步等待中拆开，分别处理本输出的 present fence。对快速返回的路径，还需检查 CV10 图层转为 HWC 设备合成的条件，以及保持客户端合成时栅栏触发相对 90 Hz 截止时间的位置。验收应覆盖静置和持续操作内屏两种情况，同时看内屏物理模式、两路 HWC 调用和 CV10 实际呈现。上述系统改动尚未部署到 Fold7。[AOSP SurfaceFlinger](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android16-qpr2-release/services/surfaceflinger/SurfaceFlinger.cpp#3050)

本机 `sfdo` 可指定时钟和强制 GPU 合成。已列出的命令没有针对单个物理输出拆分 Present 的选项。量产系统要求 root 才能读取 `aflags list`，无线 ADB 也无法读取 Composer 服务和 `libsdmcore.so`、`libsdedrm.so` 的文件内容。现有 Perfetto 证据能定位同步等待链。此固件是否在 HAL 对各物理显示声明 `MULTI_THREADED_PRESENT`、以及内部调用使用的 DRM flags，仍需用可读的同版固件或系统构建核对。[Composer AIDL 能力定义](https://android.googlesource.com/platform/hardware/interfaces/+/48fc7fb9ac330141c352b293f36c7c23fa00ae37/graphics/composer/aidl/android/hardware/graphics/composer3/DisplayCapability.aidl)

## Pocket 工作区性能对照

每眼 2240×2240，GLES 固定中心 1×1、外围 2×2。两条路径保持同一 Bilibili 工作区，使用 34° 峰峰值、3 秒周期的模拟左右转头，连续比较约 15 秒；动态推荐内容随页面变化。熄屏对照的电池温度在 35.1–35.7°C，系统热状态分别为 1→2 和 2→2。右镜固定相机确认内容出画，刷新率由呈现时间戳统计。延迟是模拟头动信号的相位拟合，不是光学测得的运动到光子时延。

| 手机面板 | 工作区输出路径 | 转头期间 CV10 实呈/秒 | 转头期间 CV10 新源帧/秒 | 拟合相位延迟 | Monado GPU p95 |
| --- | --- | ---: | ---: | ---: | ---: |
| ON，60 Hz | 经额外的虚拟显示输出 | 约 60.3 | — | 83.4 ms | — |
| ON，60 Hz | EGL 直接提交到 Monado 消费端 | 60.31 | — | 71.2 ms | — |
| OFF | 经额外的虚拟显示输出 | 89.60 | 84.40 | 67.0 ms | 5.52 ms |
| OFF | EGL 直接提交到 Monado 消费端 | 89.73 | 85.93 | 47.7 ms | 3.03 ms |

直出保留运行 Android 窗口的工作区显示，省去的是整个双目输出的额外虚拟显示合成。熄屏直出时，有效 Vulkan 实际呈现和 SurfaceFlinger 的时间戳 2504/2504 对齐。被消费的 2367 个唯一帧时间戳都找到了对应的 EGL 提交。模拟转头期间，EGL 提交完成到 CV10 实呈的 p50/p95 为 35.70/36.01 ms。省掉最后一段虚拟显示还保住了源帧时间戳，可用作下一步绑定真实入画姿态的键。

手机 ON/60 的追加对照保持工作区、每眼 2240×2240、GLES FFR、后期投影和模拟头动，尝试省掉 Pocket XR 的首个全幅清屏，让最后的双眼重投影完全覆盖输出。转头期间两边实际呈现均为 60.36 次/秒。有清屏与省清屏时，EGL 提交至 CV10 实呈的 p50 分别为 46.98 和 47.34 ms，p95 分别为 58.10 和 59.14 ms。两轮热状态分别为 0→1 和 1→2，Bilibili 动态内容也有变动，因此不能从本轮推断省清屏的纯 GPU 成本。正常版保留原有清屏顺序。Pocket XR 另外修正了 32 位 GPU 计时读数饱和时污染统计的问题，单独报告饱和计数。

### Direct Vulkan sampling of Pocket XR output

The direct bridge still draws each new `SurfaceTexture` frame into the launcher’s OpenXR swapchain. A standalone Fold7 probe replaced that step with a GLES 3.2 window backed by an `AImageReader`. On the Adreno 830, Vulkan imported each RGBA8 `AHardwareBuffer` by buffer ID and sampled it through an ordinary image view. It imported the EGL producer fence and exported a release fence. The previous `AImage` stayed retained until the next image arrived, and the producer alternated between two buffers at every size. [Android NDK media APIs](https://developer.android.com/ndk/reference/group/media) · [Vulkan Android hardware-buffer import](https://docs.vulkan.org/refpages/latest/refpages/source/VK_ANDROID_external_memory_android_hardware_buffer.html)

| Stereo dimensions | Imported bytes per buffer | Verified frames | Matching EGL timestamps | Acquire / release sync FDs |
| --- | ---: | ---: | ---: | ---: |
| 3200×1600 | 20,582,400 | 180 | 180/180 | 180 / 180 |
| 4480×2240 | 40,321,024 | 180 | 180/180 | 180 / 180 |
| 6400×3200 | 82,280,448 | 180 | 180/180 | 180 / 180 |

The probe validated the full crop and sampled four distinguishable quadrant texels per frame. Raw Vulkan sampling was top-first at all sizes. Each frame waited for its four-pixel verification readback. The results establish format, timestamp, and fence compatibility for the integrated bridge.

### Pocket XR 缓冲直采实验

调试属性 `debug.cv10.pocket_zero_copy=1` 启用双视图直采。Pocket XR 直接绘制到 Monado 提供的 Android Surface。Monado 等缓冲的 acquire fence 就绪后，把同一个 `AHardwareBuffer` 导入 Vulkan，在畸变合成中采样。缓冲在合成器及 GPU 完成使用后交还 Android。实验保留现有的 `SurfaceTexture` 和 GLES 拷贝路径作对照，四视图也沿用拷贝路径。此开关默认关闭。

首轮长跑在 Android 异步丢帧时触发了 fdsan。现在逐个取得图像及各自的栅栏，保留已经完成的最新帧，并将还没完成的下一帧留到后续检查。日志可按 `CV10_STREAM` 核对导入缓冲及提交、复用和拷贝数量。[Android NDK ImageReader](https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/media/ndk/NdkImageReader.cpp#650)

配对测量使用同一 Bilibili 工作区、每眼 2240×2240、中心 1×1 和外围 2×2 的 GLES FFR，以及 34° 峰峰值、周期 3 秒的模拟左右转头。两组采用 Compute 合成、0 ms 的 Pocket 姿态偏移和基于 CV10 实际呈现的后期重投影。手机外侧面板保持亮屏 60 Hz，CV10 活动模式为 90 Hz。两组热状态均为 3，主表面温度传感器读数为 45.0°C。

| 指标 | GLES 整帧复制 | 缓冲直采 |
| --- | ---: | ---: |
| Monado Vulkan 提交 | 89.97/s | 90.00/s |
| 模拟转头时 CV10 图层实呈 | 59.88/s | 59.95/s |
| 模拟转头时实呈间隔 p99 | 33.33 ms | 33.33 ms |
| Monado 合成 GPU 时间 p50 / p95 | 2.15 / 3.64 ms | 1.71 / 3.69 ms |
| 模拟转头时 Pocket 提交到 CV10 实呈 p50 / p95 | 55.81 / 66.74 ms | 57.34 / 58.32 ms |
| 重投影后的模拟转头相位差 | 提前 0.7 ms | 提前 1.1 ms |

直采在该会话提交了 2654 帧新缓冲、复用 104 次、整帧复制 0 次，全部新缓冲都有匹配的姿态元数据。两组纳入相位拟合的帧均使用了后期重投影。相位拟合衡量日志中姿态和实际呈现的对齐，运动到光子延迟仍需光学测量。Graphics 直采对照的合成中位数由 2.41 ms 升至 3.19 ms。

先前对照遗留了 +45 ms 的 Pocket 姿态偏移。直采让帧更早进入 OpenXR，带偏移的源姿态在 833 个拟合样本中超出 48 ms 配对范围。该轮数据用于诊断，表中采用 0 ms 及已校准配置。清理测试后已移除旧偏移并恢复常规预览 APK。

Compute 四视图也完成模拟转头采样。每眼全视场从 2240×2240 缩至 1600×1600，896×896 的中心由同一双目源画面裁出。外显截图显示了 Bilibili 页面。这轮带旧偏移的四视图测试合成时间 p95 为 11.34 ms，整段外显实呈 58.77/s。独立高密度的中心源渲染待做。

测试中的外显截图记录到工作区，固定右镜相机的中心仍为黑色。恢复常规预览后，原版 Hello XR 进入 FOCUSED，外显截图有双眼蓝块，连续两次右镜实拍仍黑。这一轮的光学显示和主观拖影验收仍待复测。

证据位于 `.research/monado-rc2-fold7/pocket-performance/ffr-quad/`，保留 `zero-motion-default-calibrated-true-{0-0,1-1}-summary.txt`、`zero-motion-calibrated-projection.txt`、`quad-motion-true-0-0-summary.txt`、`zero-hello-control-external.png` 和对应实拍。实测直采 APK 摘要为 `d6dd8c65ba64314360d9c2c660329f722510a13e5257da209e815aadb5756cf3`，模拟转头 APK 摘要为 `35891f604e7bd60ae3e857dffef77a346d7d23f964bb5e25cac5261b64ee2af7`。

The generic Monado swapchain import currently transitions images from `UNDEFINED` during creation. Producer-filled buffers require a path that preserves their contents and acquires ownership from `VK_QUEUE_FAMILY_FOREIGN_EXT` after the producer fence. Keep the image and associated pose together until the compositor has finished its final use, including repeated reprojections. Return its slot with the compositor’s release fence. Keep imported views in a bounded cache per reader generation and use an explicit mapping for the top-first stereo crop. [Vulkan external memory ownership](https://docs.vulkan.org/spec/latest/chapters/resources.html#resources-external-sharing)

### Producer-rendered four-view atlas

Pocket XR can now draw the context and inset projections independently and put all four images in one Android hardware buffer. The OpenXR configuration is `PRIMARY_STEREO_WITH_FOVEATED_INSET`, with 1600×1600 full-field and 1280×1280 inset views per eye. The inset half-angle is `atan(0.4 × tan(47.5°))`. The 3200×2880 buffer uses top-origin rectangles on import. The prior path takes a single 2240×2240 view per eye and resamples it to 1600×1600 context and 896×896 inset rectangles. The producer uses one pose for its four passes, reuses the same panel textures, and lets Monado handle late reprojection. The Monado four-view Compute path still blends the views into intermediate images before distortion.

The experiment requires the compatible Pocket XR CV10 build, Android hardware-buffer import, and Compute composition. In the direct preview, set `debug.cv10.pocket_quad=1`, `debug.cv10.pocket_quad_atlas=1`, and `debug.cv10.pocket_zero_copy=1` to use it. The ordinary stereo and four-view resampling paths remain available.

Fold7 test on the original powered cable, with the phone panel ON at 60 Hz and CV10 physically in its 90 Hz mode. Both runs used the same Bilibili workspace, Compute, scanout feedback, and the 34° peak-to-peak yaw replay. Metrics use 15 seconds of continuous yaw. Bilibili recommendations changed between runs. The first AB pass started at different thermal states (0 and 2). The reversed BA pass recorded Android thermal status 2 during both runs and peak skin readings of 42.1→44.0°C for the producer atlas and 42.6→44.8°C for resampling.

| Run | Four-view source | Monado submits/s | CV10 actual/s | CV10 new source frames/s | Compositor GPU p50/p95 | Pocket swap to actual p50/p95 |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| AB | Resampled SBS | 56.59 | 51.76 | 41.71 | 10.26 / 11.53 ms | 47.90 / 92.43 ms |
| AB | Producer atlas | 79.79 | 59.29 | 51.84 | 8.62 / 10.78 ms | 57.68 / 73.37 ms |
| BA | Producer atlas | 83.67 | 58.77 | 51.30 | 7.59 / 10.51 ms | 57.31 / 73.46 ms |
| BA | Resampled SBS | 55.55 | 47.10 | 42.29 | 10.29 / 20.48 ms | 51.46 / 104.68 ms |

Both producer sessions reported zero bridge copies, clean Back, and four correctly dimensioned imported buffers. Vulkan actual timestamps matched all 3172 unique SurfaceFlinger timestamps across the four motion samples. The replay fit for the producer atlas was 1.0–1.1 ms after late reprojection, with 0.21–0.25° RMS error. This is a pose-phase estimate. Fold7 external screenshots showed both eyes with the same window layout and orientation as the resampling path. The fixed right-eye camera still captured a dark image, including when the ordinary Hello XR control reached FOCUSED with both blue blocks in the external screenshot. Optical clarity, motion-to-photon latency, and smearing await optical verification.

Reproduction evidence is in `.research/monado-rc2-fold7/pocket-performance/ffr-quad/` under `producer-quad-ab-*` and `producer-quad-ba-*`. The replay APK hashes are `bf0ffbb782fd4b99d4c04c64f9c4ea822aa630282b692f797ec9325a56bc3dbd` (Monado) and `f1dafd19c9570d13cceed017826140ba06badef4afab7b74b0227443af09f259` (Pocket XR). The steady-state CV10 limitation remains the physical-display Composer wait described above. The producer atlas improves rendering headroom before that system limit.

## 直出帧的姿态记录

按与 ChatGPT 6 极高复核的方案，Pocket XR 通过每次会话独有的非阻塞管道发送 EGL 时间戳、姿态预测时间、回正序号和实际使用的中央、上边、下边姿态。Monado 仅在 `SurfaceTexture` 锁存的图像具有完全相同的时间戳时配对。应用或通道不可用时继续出画。本轮实现只验证配对，仍沿用现有渲染补偿。

原厂线、手机 ON/60、真实眼镜 IMU、每眼 2240×2240 并开启 GLES FFR。实测两段日志分别配对 380 和 425 个帧，采样窗口内无缺失和时间戳倒退。回正对照覆盖旧序号的 133 帧和新序号的 292 帧，序号单向递增。两段分别观察到 3 和 8 帧上边、下边的姿态不同。右镜固定相机拍到回正后的工作区。两个配对样本统计的是 Monado 接收的源帧，CV10 面板的实际呈现仍以独立的 SurfaceFlinger 数据为准。

通过同一 VPN 链输入可重复的 32° 峰峰值三轴运动，观测到全部 2782 个锁存时间戳精确配对且单调递增。yaw、pitch 和 roll 的实测主轴峰峰值分别为 31.41°、31.40°、31.43°，与模拟输入的相关系数均为 +0.994。测试在 pitch 段观察到 9 帧上边与下边使用不同姿态，yaw 和 roll 的运动采样内两者相同。姿态轴和符号已验证到 Monado 的捕获端，逐行补偿的目标时序和应用重投影后的光学校验待做。测试结束后恢复了使用真实 IMU 的构建。

## Late projection calibrated against CV10 presentation

`debug.cv10.pocket_atw=1` allows the direct bridge to express the source orientation in OpenXR `LOCAL` space. It combines the unsmoothed `CVP2` reference with a Monado pose sampled at that same time. Repeated submissions keep the orientation of the captured source frame. Missing or stale metadata, the first capture after recenter, materially different row orientations and excessive corrections retain the existing `VIEW` path. Pocket XR retains its own row correction. The experiment defaults to off.

`debug.xrt.CV10_SCANOUT_FEEDBACK=true` joins `VK_GOOGLE_display_timing` feedback by present ID and estimates the prediction error from the median of the last 21 valid samples. The adjustment applies to the poses sampled for CV10 scanout. Submission cadence and desired present times are unchanged. The estimate expires after 250 ms without feedback and resets when the swapchain is rebuilt. This property also defaults to off.

The same Bilibili workspace used direct rendering at 2240×2240 per eye, GLES FFR and the graphics compositor. The phone panel stayed ON at 60 Hz. The replay swept yaw by 34 degrees peak to peak on a 3-second cycle. Each arm had 905 exact present-to-pose joins over five complete cycles. Battery temperature rose from 34.5 to 35.6°C during the sequence. The baseline and combined runs stayed at thermal status 1. The late-projection-only run ended at status 2. A fixed camera verified right-lens output. The table reports software fitted pose phase, not a camera measurement of motion-to-photon latency.

| During replay | Neither experiment | Late projection only | Late projection and feedback |
| --- | ---: | ---: | ---: |
| Actual CV10 presents/s | 60.36 | 60.36 | 60.31 |
| Frames with a matched late projection | 0/905 | 431/905 | 875/905 |
| Actual present minus scanout pose target, median | 32.54 ms | 33.31 ms | 0.00 ms |
| Absolute scanout pose target error p95 | 43.65 ms | 44.43 ms | 11.11 ms |
| Source yaw phase delay | 24.6 ms | 28.3 ms | 32.9 ms |
| Effective yaw phase delay after composition | 24.6 ms | 30.2 ms | 1.6 ms |
| Effective angular error RMS | 0.674° | 0.817° | 0.194° |

All three arms submitted about 90 frames per second and exactly matched the valid Vulkan and SurfaceFlinger presentation timestamps. The combined run had a Monado GPU p95 of 4.48 ms. A causal replay checked a per-phase median for the 90-to-60 cadence. It did not improve the absolute p95 across the lit-panel traces, so the implementation uses one median.

The first comparison used a producer pose offset of +45 ms. With the standard zero offset, Pocket XR used materially different row poses on 830 of the 905 lit-panel frames. A rigid projection could handle the other 75. The direct build now leaves row timing to Monado when both experiments are enabled and the producer has a usable `CVP2` reference. With no producer offset, the lit-panel run used late projection for all 905 frames and held 60.31 actual presents/s. Fitted yaw error went from 66.8 ms in the uncorrected source pose to an estimated 1.1 ms lead in the composed pose. Angular RMS went from 1.692° to 0.239°. Monado GPU p95 was 4.07 ms and 895 of the 905 displayed samples carried a new source buffer. System thermal status stayed at 1.

The panel-off control used the same uniform source pose with zero offset. During the yaw sweep CV10 sustained 89.73 actual presents/s, p99 interval 11.11 ms, with 85.80 new source frames/s and Monado GPU p95 of 2.99 ms. All 1346 presented samples in the fit were corrected. The uncorrected source lag was 47.0 ms and the composed pose led the replay by an estimated 0.9 ms. Thermal status changed from 1 to 2. This reference temporarily powered off the phone panel and forced SurfaceFlinger to use the CV10 clock. Both settings reverted after capture.

The final build also displayed the workspace through the original cable with the real CV10 IMU. With the phone at 60 Hz, all 884 unique uniform-pose frames with a `CVP2` reference applied late projection. The fixed right-lens camera captured the visible workspace. The automated check reached the phone trackpad, but its UI hierarchy did not expose the recenter button in this run. The handoff after a new recenter remains to be checked with the final build.

## 可选熄屏 90 Hz

实验版在 Pocket 预览首帧到达后提供「熄灭手机屏幕以启用 CV10 90 Hz」。需要已有的 Shizuku 桥接授权和 CV10 时钟，以及用户在该次会话里确认。熄屏期间工作区继续运行，最多维持十分钟。关闭预览或它的进程退出时恢复 Android 对手机面板和合成时钟的控制。

实机用真实 CV10 IMU 和直接输出进入该模式。确认后，手机两个面板均报告 committed OFF，外显维持 ON，CV10 图层在六秒采样中的滚动呈现为 90.0/s，右镜拍到工作区。强行结束预览客户端后，手机面板恢复 ON/60，SurfaceFlinger 的强制外屏时钟随即清除。此轮复测的是客户端退出，十分钟上限和物理拔线恢复另待独立验收。

最终构建重复验证了出画和退出回收。熄屏切换后的前两个滚动样本为 87.9/s，随后三个样本均为 90.0/s，p99 间隔 11.1 ms。

## 本机证据

原始数据保存在主项目的 `.research/monado-rc2-fold7/pocket-performance/ffr-quad/`。同级 `head-motion/` 留有 `*-cadence.txt`。设备标签和实测 APK 摘要用于匹配本机安装，文档中的性能读数来自实验构建。

- `hello-policy-{lit-auto,lit-forced,off-forced}-trace-summary.txt`、`hello-policy-hwc-{thread-state,binder}.txt` 和对应 Perfetto trace，记录 Hello XR 的调度对照。

- `hello-composer-{lit-auto,lit-forced,off-forced}.perfetto-trace`、`composer-waits-summary.txt` 和 `trace-fold7-composer.ps1`，记录 Qualcomm DRM 工作线程及 Composer Binder 等待的对照。
- `pocket-policy-static-phone-summary.txt` 和 `hello-policy-static-phone-hashes.txt`，记录工作区直出时静置的手机界面。
- `pocket-lit-layer-state.perfetto-trace`、`lit-layer-present-mode-comparison.txt`、`hello-present-asap-comparison.txt`、`fold7-backpressure-stages.txt` 和对应 `*-monado.log`，记录快返回的 HWC 调用、图层与 Vulkan 调度对照。尽早呈现实验 APK SHA-256 为 `d377c303c99109361a5d5f3f6145253abca9ccc2714f322a25c0c973092a410d`。

- `hello-lit-layer-state-*-sched*.perfetto-trace`、`pocket-lit-layer-state-control-sched-no-snapshots.perfetto-trace`、`hello-lit-wakeup-control-summary.txt`、`hello-lit-wakeup-no-snapshots-summary.txt` 和 `fold7-wakeup-dispatch.txt` 记录关闭快照前后的等待位置。用 `trace-static-phone-layers.ps1 -CadenceProbe schedule-composite -RendererLeadMs 8 -ScheduleTrace -WithoutLayerSnapshots` 重新采集，用 `analyze-present-wakeups.py` 和 `analyze-sf-main-thread.py` 对齐时间戳和主线程片段。
- `window-clear-ab-lit-{0,1}-2-summary.txt`、`*-submit.txt` 和 `*-thermal-*.txt`，记录清屏对照。用于两组的 Pocket XR APK SHA-256 为 `e3c5139228b39ecc9e28c7a66ce16c3ad38eaea8aaa83d8f464936170699c06c`。
- `gpu-timer-device.log` 和 `gpu-timer-device-right.jpg`，记录 GPU 计时修正版的 GLES FFR 与右眼画面。一个 5 秒日志窗口的有效 GPU 查询总时间为 p50 1.10 ms、p99 2.32 ms，当时累计排除了 105 个饱和样本。APK SHA-256 为 `818e885e76fcc5fb62041d1e2806ffe429579e916808678c1bc65136c061a260`。

- `ahb-probe/fold7-size-{1600,2240,3200}.txt` records the Vulkan sampler verification. `ahb-probe/run.ps1 -Serial <Fold7 ADB serial>` builds, validates the shader, runs the three sizes and removes the temporary device files.
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
- `late-projection-host-clock-{0,1,2}-summary.txt`, `late-projection-host-clock-result.txt`, `scanout-cadence-result.txt` and `direct-atw-live-right.jpg` cover the timing comparison, the causal cadence check and the right-lens capture using the real IMU. The regular Monado APK SHA-256 was `672b281b556e8097d0e9faf6513a1c2e4a11ccf789aca90d8dd5c5dac583f601`, the replay APK was `d1842d5122c7f61f523a5272afb4112836b29f4b6f1d2bbd3697e68303ebc39d` and the Pocket XR APK was `d5a6cf89117105f03ccb7de3d687360a749d10608ef70d0df4051d9250eb7f47`.
- `late-projection-clock-zero-{lit,off}-2-summary.txt`, `late-projection-uniform-{lit,off}-2-summary.txt` and the associated right-lens captures cover the follow-up at the standard producer offset. The uniform-pose Pocket XR APK SHA-256 was `9cc83db25254f449114a9b84e809276abb132b40bf96eeea3033dbf500287c06`.
- `late-projection-uniform-live.log` and `late-projection-uniform-live-before-right.jpg` record the final real-IMU smoke check.
