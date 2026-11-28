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

同一接线下用系统快捷设置的「有线 DeX」做了两次对照。手机均保持 ON/60，CV10 的逻辑显示重建后只提供 640×480 和 640×533 的 60 Hz 模式，实测活动模式为 640×480、60 Hz。第二次等待 4.5 秒后结果相同，因此没有在这个模式下启动 OpenXR。关掉 DeX 后恢复 3200×1600、90 Hz 输出时序。随后原版 Hello XR 进入 FOCUSED，127 个完成呈现时间戳给出 60.31/s，外显截图有双目蓝块，右镜相机亮度第 90 百分位仍为 0。[三星有线 DeX 切换说明](https://www.samsung.com/us/apps/dex/)

用直接渲染的工作区再次采集时，手机一直亮屏 60 Hz，采样前后两张手机屏幕截图的 SHA-256 相同。七秒内仍有 419 次手机屏 HWC 调用，平均 12.51 ms，CV10 HWC 为 60.32 次/秒。

### 绕过 Monado 的外屏缓冲对照

临时测试应用在 CV10 的 `Presentation` 上通过 NDK `ASurfaceTransaction_setBuffer` 轮换四张预先生成的 3200×1600 不透明缓冲。测试线程按 90 Hz 交付 810 帧，缓冲带 `COMPOSER_OVERLAY` 用途，循环期间不重新渲染或写入缓冲。RGB 组按事务回调的非空呈现栅栏去重，在第 180 到 749 次提交间统计。视频组在同一个外屏 `SurfaceView` 上播放 3200×1600、90 fps 的 H.264 测试片，按该图层的 `PresentFenceSignaled` 统计。亮屏组手机的物理模式为 60 Hz，CV10 始终为 3200×1600、90 Hz。各组的起始电池温度为 32.6–32.9°C，Android 热状态为 0。[Android NDK SurfaceControl](https://developer.android.com/ndk/reference/group/native-activity) · [Android 硬件缓冲](https://developer.android.com/ndk/reference/group/a-hardware-buffer)

| 外屏内容 | 手机面板 | 调度时钟 | CV10 实呈/秒 | CV10 HWC 调用/秒 | 手机 HWC 平均用时 |
| --- | --- | --- | ---: | ---: | ---: |
| RGBA8888 缓冲 | ON/60 | 系统自动 | 60.32 | 60.33 | 0.23 ms |
| RGBA8888 缓冲 | ON/60 | CV10 | 58.63 | 60.33 | 13.78 ms |
| RGBX8888 缓冲 | ON/60 | CV10 | 60.26 | 60.48 | 14.68 ms |
| 解码 YUV 4:2:0 | ON/60 | 系统自动 | 60.31 | 60.33 | 0.27 ms |
| 解码 YUV 4:2:0 | ON/60 | CV10 | 60.25 | 60.33 | 14.46 ms |
| RGBX8888 缓冲 | OFF | CV10 | 88.42 | 88.32 | 无调用 |

Composer 在这些外显样本中均将应用图层标为 `CLIENT`，DPU 使用 `GPU_TARGET`。硬解得到的外屏缓冲格式是 `Y_CBCR_420_VENUS_UBWC`，同批 dump 的手机壁纸使用相同格式且被分到 `DEVICE`。RGBX 熄屏对照仍由 GPU 合成，CV10 的呈现间隔 p50 和 p95 均为 11.111 ms。两组 RGBX 的差异复现了手机显示参与调度时的限帧，也确认换不透明像素格式或使用视频解码缓冲没有触发这台设备的外屏硬件直出。

本机 `ffr-quad/fold7-dpu-probe/` 保存缓冲探针、分析脚本、Composer dump、模式读数及 `summary.txt`。RGBX 亮屏和熄屏追踪 SHA-256 分别为 `64beb8cf6bcddb47a25894d02ba3d4c9114a83a8a4bb4c2db655100856be123c`、`9da9e6dc3350fa897754b9fca53198e6f865682400a4d5b0388fa282f3c5554b`。测试结束后已卸载探针，恢复手机 ON/60 和系统自动时钟。这组读数来自呈现栅栏，尚无镜片实拍。

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

AOSP 的 `Output::beginFrame` 会根据损伤区决定是否重画。物理输出仍可在后续的 `prepareFrame` 中进入 `presentOrValidate`，即使应用画面保持静止。实现跳过静置手机提交的实验，应在 `prepareFrame` 前检查该输出是否还有内容、配置、颜色、光标或缓冲释放事务，并仅对真实的新硬件 present 更新该输出的栅栏和帧统计。[AOSP Output](https://android.googlesource.com/platform/frameworks/native/+/58bca588b79f339a2ae26f4348b88ac93084e038/services/surfaceflinger/CompositionEngine/src/Output.cpp#1135)

上游 `follower_display_backpressure` 在跟随输出已有待完成栅栏时，可以把它排除出当轮目标集合。同步 HAL 仍可在把新栅栏返回给 SurfaceFlinger 之前阻塞。三组已采集的 CV10 时钟追踪里均未找到上游的 `Avoiding backpressure for follower display` 事件，且两路 `HwcPresentOrValidateDisplay` 次数分别为 360/360、420/420、421/420。量产 Fold7 的 `dumpsys SurfaceFlinger` 确认该标志为 `false`。[AOSP Scheduler](https://android.googlesource.com/platform/frameworks/native/+/fcbde2bcff56ca1d7bec9cabf8bdca5e288bf103/services/surfaceflinger/Scheduler/Scheduler.cpp#432)

### 每块物理屏的帧周期提示

原厂线连接不变，用原版 Hello XR 重新采集两次约 4.5 秒的轻量追踪。两次手机面板均为 ON/60，CV10 的物理时序均为 90 Hz。用 HWC 调用的直接父追踪区间匹配 `NextFrameInterval`，同一次调用中的提示和耗时如下。[Perfetto 查询](fold7-hwc-frame-interval.sql)

| 合成时钟 | CV10（HWC 1） | 手机屏（HWC 3） | 调用数（每屏） |
| --- | ---: | ---: | ---: |
| 系统自动选手机 | 60 Hz 提示，平均 0.150 ms | 60 Hz 提示，平均 0.203 ms | 269 |
| 临时指定 CV10 | 90 Hz 提示，平均 1.165 ms | 90 Hz 提示，平均 13.930 ms | 268 |

Android 16 QPR2 的 SurfaceFlinger 在 `CompositionRefreshArgs` 中共用主屏时钟的下一帧周期。各输出已有独立的预计呈现时间，写入 HWC 的帧周期仍共用同一个值。高通同平台公开实现把这两个值作为每屏单独的参数交给 HAL。当前追踪切换了时钟和周期提示，因而无法单独归因于提示。[AOSP SurfaceFlinger](https://android.googlesource.com/platform/frameworks/native/+/fcbde2bcff56ca1d7bec9cabf8bdca5e288bf103/services/surfaceflinger/SurfaceFlinger.cpp#3157) · [AOSP Output](https://android.googlesource.com/platform/frameworks/native/+/fcbde2bcff56ca1d7bec9cabf8bdca5e288bf103/services/surfaceflinger/CompositionEngine/src/Output.cpp#909) · [高通 Composer AIDL](https://github.com/LineageOS/android_vendor_qcom_opensource_display-hal/blob/28edbb21cf722c6ce5acdd7711af9cc692167a12/composer/AidlComposerClient.cpp#L1276)

复查六秒强制 CV10 时钟的 HAL 追踪，手机和眼镜的提交已运行在两个线程。手机的 `DRMAtomicReq::Commit` 平均耗时 16.3 ms，其中 `atomic_commit` 平均 16.0 ms。CV10 对应两段平均为 15.2 ms 和 0.32 ms。CV10 线程在较长的提交区间内有 358 个由手机提交线程触发的唤醒。因此即使拆开 SurfaceFlinger 层的线程，下面两路提交仍有等待关系。新追踪未采得 syscall 或内核阻塞符号，无法确定 Samsung 量产 Composer 的 DRM flags 或具体锁。[Perfetto syscall 数据源](https://perfetto.dev/docs/data-sources/syscalls) · [同平台公开的 DRM 提交代码](https://github.com/LineageOS/android_vendor_qcom_opensource_display-core/blob/89aa1914da50aae0fb1b2ebf3db38d34fba5f748/sde-drm/drm_atomic_req.cpp#L260)

[系统实验补丁](aosp-android16-per-display-present-interval.patch) 基于 `platform/frameworks/native@fcbde2bcff56ca1d7bec9cabf8bdca5e288bf103`，默认关闭。在含补丁的系统构建中，启动 SurfaceFlinger 前设置 `debug.sf.cv10_per_display_frame_interval=true`，把各物理跟随输出的帧周期取自各自的 VSync 跟踪器和预计呈现时间。虚拟输出及缺少有效单屏周期的输出沿用主时钟的值。补丁已在指定上游文件上通过正反向应用检查。Fold7 使用量产系统，这轮未编译或部署修改后的 SurfaceFlinger。

有系统构建时先固定 CV10 为调度时钟，仅切换此属性，确认 HWC 1 收到约 11.11 ms 周期、HWC 3 收到约 16.67 ms 周期。先测原版 Hello XR，再在同一 Pocket XR 工作区测静止和持续转头。同时记录 CV10 图层实呈、手机 DRM 等待与温控。若周期已分开但仍低于 90 次实呈/秒，应再比较跟随屏的提交调度和 Qualcomm DRM 完成等待。

### 手机提交在上一帧完成前进入 DRM

把内建屏的 `atomic_commit`、子区间 `sde_kms_prepare_fence` 和 CRTC 288 的 `complete_commit` 对齐。强制 CV10 节拍时，359 次采样完整的手机 DRM 调用全部在上一帧的 CRTC 工作尚未完成时进入。本帧的栅栏平均在上一帧工作结束后 0.037 ms 才创建，且有 356 次本帧的 CRTC 工作在 DRM 调用返回后的 0.3 ms 内才开始。系统自动选手机时钟时，361 次调用进入时上一帧均已完成。[Perfetto 查询](fold7-drm-pending-commit.sql)

| 合成时钟 | 手机调用进入时上一帧未完成 | 进入 DRM 到本帧准备栅栏 | 准备栅栏到返回 |
| --- | ---: | ---: | ---: |
| 自动选手机 | 0/361 | 0.001 ms | 0.008 ms |
| 强制 CV10 | 359/359 | 15.965 ms | 0.011 ms |

同一批内核事件中，强制 CV10 时钟的 359 次重叠调用均匹配到 CRTC 288 的上一帧 `signal_retire_fence`。每次新调用都先进入 DRM，上一帧的 retire fence 平均再过 15.856 ms 才发出信号。信号平均比 CRTC 工作结束早 0.072 ms，最多早 0.448 ms。手机正常领跑时没有重叠，熄屏对照没有手机 DRM 调用。这些读数锁定了等待的那一帧，也量出了 retire fence 与 CRTC 工作结束之间的差距。[Perfetto 查询](fold7-drm-pending-commit.sql)

从两组亮屏追踪中按物理显示 ID 读取 SurfaceFlinger 的 `PrevFramePending`。强制 CV10 时钟时，手机显示 `4630946872173396372` 的 629 次检查全为待完成，627 次检查落在 CRTC 288 的 `complete_commit` 区间。系统自动选手机时钟时为 0/630。自动模式下，根据 `LastSignaledFrameTime` 打印的相对时间重建信号，629/630 个与内核 retire 区间结束时间相差不超过 0.5 ms。追踪的相对时间只保留两位小数，含日志调用误差，结果建立了时间对应关系。熄屏对照的 537 次检查均无待完成的手机呈现。现有 SurfaceFlinger 追踪可据此校验实验补丁是否实际延后了仍在等待的跟随屏。[SurfaceFlinger 与 DRM Perfetto 查询](fold7-surfaceflinger-pending-fence.sql)

以同一个 VSync ID 对齐两个目标器，用 CV10 的 `beginFrame … vsyncIn` 重建预计呈现时刻。强制 CV10 时钟的两段追踪中，629 次手机栅栏待完成检查有 627 次配到下一个内建屏 retire 信号。627 个信号全部迟于同批 CV10 预计呈现时刻超过 0.5 ms，两段各自的平均差为 10.347 和 10.342 ms。其中 243 次已向后调整过 CV10 的预计时刻。原调度时刻在这些样本里更早。AOSP 的跟随屏背压可在预计跟随屏 VSync 先到时容忍一个待完成栅栏。新查询使用的是实测 retire，说明这个硬件信号没有赶上该批 CV10 时限，启用背压后的实际跳过次数还需在系统构建上测量。[同批次时限 Perfetto 查询](fold7-slower-follower-deadline.sql)

长等待发生在提交本帧栅栏以前。公开的同平台 DRM 内核代码在等同 CRTC 或同一平面的待完成提交前取得共享 `connection_mutex`，然后才准备栅栏和派发新工作。这与手机等待上一帧、CV10 同时被拖住的时间顺序相符。后续[量产内核的阻塞调用栈](#量产内核的阻塞调用栈) 验证了外屏的锁路径。[同平台 DRM 源码](https://github.com/LineageOS/android_kernel_qcom_sm8750-modules/blob/5d27ace07b893b611cf3ce1c301b760f32d59892/qcom/opensource/display-drivers/msm/msm_atomic.c#L871)

现有系统的 `debug.sf.follower_display_backpressure` 属性可临时写入，但写成 `true` 后，正在运行的 SurfaceFlinger 仍报告对应标志为 `false`。上游实现首次读入时缓存属性。从手机读取的分区 aconfig 名单也没有该标志，因此没有证据支持用运行中的 DeviceConfig 开启。实测已还原属性。[AOSP FlagManager](https://android.googlesource.com/platform/frameworks/native/+/fcbde2bcff56ca1d7bec9cabf8bdca5e288bf103/services/surfaceflinger/common/FlagManager.cpp#227)

[跟随屏调度实验补丁](aosp-android16-defer-pending-slower-follower.patch) 基于同一 AOSP 提交，可独立应用。启动 SurfaceFlinger 前设置 `debug.sf.cv10_defer_pending_slower_followers=true`，使较慢的跟随屏在仍有未完成的呈现栅栏时跳过当前批次，下批检查就绪状态。原有背压策略继续按各自开关运行。实验标记同时记录较慢跟随屏的待完成栅栏数和最近的栅栏时间戳，可直接与内核 retire 事件对照。补丁通过独立反向和组合正向应用检查。验收应分别对照上游背压开关、较慢跟随屏延后和逐屏周期提示。若跟随屏的 present fence 早于 CRTC 释放排队资源，延后策略仍可能错过真正的忙碌区间。需核对新追踪里的延后标记、两路 CRTC 及真实呈现。此补丁尚未在 Fold7 系统上运行。

验收须同时统计手机图层的实际呈现。若手机栅栏每次在提交后 16.67 ms 才就绪，而调度只在 11.11 ms 的 CV10 节拍检查，手机最早在第二个节拍提交，每秒可更新约 45 次。这个简化模型只用于评估跟随屏延后的取舍。若实机出现这种节奏，跟随屏还需按自己的信号和节拍调度，并验证手机提交不会跨越 CV10 的下一次关键合成时刻。

[离线窗口回放](fold7-follower-window.py) 用两段亮屏强制 CV10 时钟的追踪重建 90 Hz 调度网格，并用手机 retire 后 0.5 ms 估算新提交的最早时刻。若手机提交需要预留下一次 retire 前 7 ms，在 CV10 每批次开始时检查就绪只能保留 261/357 和 195/265 次手机提交机会，约 44.1 和 44.4 次/秒。把检查推迟到同批 CV10 HWC 调用开始时，分别约 48.0 和 48.9 次/秒。让手机在自己的节拍附近排队，同时避开 CV10 的 HWC 时段，在两段记录里都可排入约 60.3 次/秒。CV10 前后各留 0.5 ms 的保护窗时，手机提交相位位移 p95 小于 0.75 ms。保护窗加宽到 2 ms 时小于 3.83 ms。结果取决于提交所需提前量和保护窗，未计入新增系统调度开销。系统补丁应同时用持续动画验证手机实呈节奏，并用 CV10 栅栏验证实呈帧率和延迟。

同一脚本按 VSync ID 和线程配对最早的 `beginFrame`、目标屏的 HWC 调用和 `composite` 结束。CV10 独跑的 537 个样本在 HWC 开始前、后分别需要 0.997 和 1.635 ms（各段 p95）。手机领跑的 361 个样本分别需要 0.725 和 1.406 ms。把这些区间作为主线程互斥预算，回放仍可在 0.5 ms 保护窗和 7 ms 手机呈现提前量下排入 357/357、265/265 次手机提交。该计算使用了事后已知的 retire 相位。

因果回放只用调度当时已收到的各屏 retire 和各自显示周期。CV10 的相位取最近五次已收到信号的周期对齐中位数。收到手机 retire 后留出 0.5 ms，再启动手机侧的准备工作。记录中的后续栅栏仅用于事后检查手机时限和重建的 CV10 时隙，预测另留 0.25 ms 误差预算。两段追踪的结果如下。

| CV10 前后保护窗 | 手机呈现提前量 | 第一段有效手机时隙 | 第二段有效手机时隙 | 手机 HWC 相位位移 p95 |
| --- | --- | --- | --- | --- |
| 0.5 ms | 7 ms | 356/357（60.15/s） | 265/265（60.32/s） | 5.58 / 5.56 ms |
| 2 ms | 7 ms | 308/357（52.04/s） | 232/265（52.81/s） | 7.14 / 7.12 ms |

较窄的保护窗下，第一段有一次手机提交时限错判，两段均未预测到占用 CV10 保护窗的提交。加宽保护窗会明显减少可用机会。独立排程原型需要在真正进入 HWC 前复查栅栏和 CV10 的剩余预算，并记录跳过原因。回放使用基线 p95 主线程预算、现有追踪的重建时钟和固定提前量。它没有包含独立提交对 Qualcomm Composer 等待链、调度抖动或新 retire 相位的反馈，不能用来报告设备已达到 60/90 Hz。现有 AOSP 实验仍需系统构建验证。

### Pocket XR 长样本的调度回放

把同一回放扩到四段约 30 秒的 Bilibili 工作区转头追踪，涵盖 1280×1280、1600×1600 全视场和同一 1280×1280 中心。以 7 ms 手机呈现提前量和 CV10 前后各 0.5 ms 保护窗估算。第二组 1600 档有一次 23.001 ms 的 CV10 retire 间隔，不符合 90 Hz 网格。回放在此拆成两段，不跨段预测，跳过 3 个边界窗口，仍按整段采样时长计算机会数。

| 工作区追踪 | 可重建手机时隙 | 仅在 CV10 帧开始检查 | 独立排程 | 独立排程超时 / 与 CV10 冲突 |
| --- | ---: | ---: | ---: | ---: |
| 第一组 1280 | 1802 / 1802 | 43.95/s | 60.25/s | 2 / 0 |
| 第一组 1600 | 1799 / 1799 | 44.36/s | 60.28/s | 1 / 0 |
| 第二组 1280 | 1802 / 1802 | 44.38/s | 60.02/s | 6 / 2 |
| 第二组 1600 | 1800 / 1803 | 43.86/s | 59.88/s | 5 / 3 |

将保护窗加至 0.75 ms 时，独立排程估计为 59.92–60.28/s，仍有 8 次冲突。手机准备工作开始前，每次新收到 CV10 retire 就重新选择窗口，也未消除冲突，两档保护窗下分别剩 5 和 8 次。这个回放支持按屏调度，同时表明仅靠过去的 retire 相位无法保证隔离。实验系统应在实际进入 HWC 前核对上一帧的栅栏和下一次 CV10 截止时间，并在时钟异常或准备超预算时放弃该手机时隙。回放没有改变 Fold7 的实际呈现。

### 独立提交应覆盖验证阶段

用 [HWC 调用外层切片查询](fold7-hwc-present-ancestors.sql) 复查上述两段追踪。在第一段，359/359 个手机 `HwcPresentOrValidateDisplay 3` 都位于 SurfaceFlinger 主线程的 `Output::present → prepareFrame → chooseCompositionStrategy → getDeviceCompositionChanges` 内，平均耗时 14.138 ms。第二段有 268/268 个，平均 13.930 ms。这个调用发生在 `Output::present` 返回 future 之前。现有并行 present 从 `prepareFrame` 之后才开始，单独调整 `future.get()` 仍会保留实测的长等待。[AOSP Output](https://android.googlesource.com/platform/frameworks/native/+/fcbde2bcff56ca1d7bec9cabf8bdca5e288bf103/services/surfaceflinger/CompositionEngine/src/Output.cpp#470)

[显示管线插桩补丁](aosp-android16-trace-present-pipeline.patch) 默认关闭。在实验系统构建启动 SurfaceFlinger 前设 `debug.sf.cv10_trace_present_pipeline=true`，采集 graphics trace。调度器记录已选中跟随屏的预计呈现与参考栅栏，输出对照原有背压跳过事件。CompositionEngine 按批次和显示 ID 标识 `Output::present` 及每个 future 的等待，SurfaceFlinger 用同一批次标识记录各 FrameTargeter 注册时的预计呈现与栅栏状态。注册事件可以显示栅栏仍处于 pending，实呈仍用内核 retire 或合格的实际呈现栅栏统计。补丁没有更改选择、提交或栅栏完成顺序，已按指定 AOSP 基线做正反向及三补丁联合应用检查，尚未编译或在系统上运行。

[主线程等待查询](fold7-hwc-present-stage-times.sql) 在两段强制外屏时钟追踪中均读到 `Waiting on HWC` 平均 0.002 ms，分别有 360 和 269 次。另一次强制 GPU 合成的追踪仍出现 419 次手机 `HwcPresentOrValidateDisplay 3`，平均 13.504 ms。AOSP 的 `getDeviceCompositionChanges` 允许 HWC 在验证阶段直接呈现，成功时随即记录已返回的 present 和 release fences。带有相同芯片平台标记的公开 Composer 源码会把显式 `validate` 和合并命令分别以 `validate_only=true` 和 `false` 传给 `CommitOrPrepare`。三星私有实现的具体路径仍需实测。[AOSP HWComposer](https://android.googlesource.com/platform/frameworks/native/+/fcbde2bcff56ca1d7bec9cabf8bdca5e288bf103/services/surfaceflinger/DisplayHardware/HWComposer.cpp#535) · [公开 Composer 实现](https://github.com/LineageOS/android_hardware_qcom_display/blob/28edbb21cf722c6ce5acdd7711af9cc692167a12/composer/AidlComposerClient.cpp#L1276)

[逐屏拆分验证与呈现的补丁](aosp-android16-validate-only-display.patch) 默认关闭。实验系统启动 SurfaceFlinger 前，把 `debug.sf.cv10_validate_only_display` 设为内屏的 64 位物理显示 ID，以 `dumpsys SurfaceFlinger --display-id` 为准。当前采样里的 Composer 显示 ID `3` 是另一套编号。补丁让指定屏走 AOSP 已有的分开验证和呈现路径，沿用其栅栏、最早可呈现时间和释放流程。插桩中的 `skipEligible=1` 表示这帧原本会走合并路径。用同一批图层、模式和时钟做开关对照，记录内屏验证、内屏呈现及 CV10 实际 retire 间隔。等待若移到内屏实际提交，可把逐屏准入点放在提交前。若独立验证仍然同步等待，就需继续检查 HAL 验证链。此补丁已做四补丁联合正反向应用检查，尚未编译或部署。

[提交窗口插桩补丁](aosp-android16-trace-follower-present-window.patch) 在实验系统上通过 `debug.sf.cv10_probe_pacesetter_display` 和 `debug.sf.cv10_probe_follower_display` 分别指定 CV10 和手机的 64 位物理显示 ID。合成前读取已排定的下一次 CV10 唤醒时间，手机输出在进入验证前再读上一帧栅栏，记录 `CV10 follower window`。按回放保留 retire 后 0.5 ms、CV10 下一次唤醒前 2 ms，以及手机预计呈现前 7.3 ms。缺少真实的下一次计划唤醒时单独标记。trace 的状态 0–5 分别对应窗口满足预算、上一栅栏无有效信号、retire 后保护期、无有效唤醒、CV10 时限不足、手机自身时限不足。该探针只记录判定，实际仍由原流程提交和处理缓冲释放。状态 0 还需结合 pacesetter 的实际完成事件，才能判断是否可以调度独立手机任务。八个条件边界已通过 Android arm64 编译检查，五补丁在独立 AOSP 基线正向应用和反向还原通过。量产 Fold7 仍运行原系统。

从 Fold7 的 F9660ZCUDBZJ9 构建只读取得 `/vendor/lib64/libdrm.so`。SHA-256 为 `311a8f5fff41f49d7ae186a94aaf053f465d946b4e716deb1c4ac29fa1d21743`，Build ID 为 `5e3f95c5eb67ff212df8d1288c77a9f7`。ARM64 的 `drmModeAtomicCommit` 在 `0x13b48` 保存调用参数 `w2`，在 `0x13d48` 读回并在 `0x13e54` 写入 ioctl 结构的 flags。随后 `0x13e68` 把请求 `0xc03864bc` 传给 `drmIoctl`。量产系统拒绝 ADB 读取 Composer 服务、`libsdedrm.so`、`libsdmcore.so` 和 `msm_drm.ko` 的内容。后续阻塞栈确认手机的提交进入了 DRM 的 nonblocking 分支。完整 flags 数值未采集。[三星构建记录](https://doc.samsungmobile.com/SM-F9660/034393250731/eng.html)

### CV10 进入内核提交前的跨屏等待

原厂线和原版 Hello XR 不变，手机物理模式始终为 60 Hz。连续采样系统自动时钟、强制 CV10 时钟、恢复自动时钟三种状态。将 CV10 的 `DRMAtomicReq::Commit` 开始、其内层 `atomic_commit` 开始、线程休眠区间和唤醒者配对。表中的计数来自 CV10 的内核 retire 事件。[Perfetto 查询](fold7-drm-cross-display-admission.sql)

| 第二次采样 | CV10 调用开始至 `atomic_commit` 平均 | 期间平均休眠 | 内层 `atomic_commit` 平均 | CV10 retire 次数/秒 |
| --- | ---: | ---: | ---: | ---: |
| 初始自动 | 0.029 ms | 0.000 ms | 0.011 ms | 60.38 |
| 强制 CV10 | 14.894 ms | 14.781 ms | 0.375 ms | 60.38 |
| 刚恢复自动 | 7.422 ms | 7.324 ms | 0.020 ms | 60.39 |
| 恢复自动约 9 秒后 | 7.414 ms | 7.315 ms | 0.020 ms | 60.39 |

在新旧四次强制外屏时钟采样中，等待至少 1 ms 的调用分别为 201/209、199/208、255/267 和 349/359。这些调用全部与手机的 `atomic_commit` 重叠，CV10 在手机调用结束后 0.5 ms 内进入自己的 `atomic_commit`。每次都由手机提交线程在该时段唤醒 CV10 工作线程。恢复自动时钟的三段采样包括约 9 秒后的追踪，每段的全部 209 次调用都有相同关系。四段初始自动时钟对照均没有达到 1 ms 的前置等待，CV10 retire 仍约 60 次/秒。既有手机熄屏组的 537 次 CV10 调用没有手机提交等待，CV10 retire 为 90.0 次/秒。

同平台公开的 MSM 驱动在等待旧 CRTC 和 plane 腾出之前先取 DRM 的 `connection_mutex`，且 `nonblock` 分支也走该等待。该批追踪没有采到 `lock/contention` 事件，后续在量产机的调度切换点用内核调用栈验证了这条等待链。[同平台公开内核源码](https://github.com/OnePlusOSS/android_kernel_modules_and_devicetree_oneplus_sm8750/blob/d50b305f7da9e14715a25120a4ac7b1a4b8b97c3/vendor/qcom/opensource/display-drivers/msm/msm_atomic.c#L874) · [Perfetto 线程唤醒定义](https://perfetto.dev/docs/data-sources/cpu-scheduling)

#### 量产内核的阻塞调用栈

在同一 Hello XR 会话、同一接线和手机 ON/60 模式下，分别采集 4 秒的系统自动、强制 CV10 和恢复自动时钟。Perfetto 的 `linux.perf` 在 Composer 线程的 `sched_switch` 采样，通过 `ftrace` 对齐休眠开始、唤醒者和两路 DRM 调用。仅采样内核帧。三星量产系统无需变更即可采集这些符号。[Perfetto 阻塞采样说明](https://perfetto.dev/docs/case-studies/scheduling-blockages) · [采集配置](fold7-drm-kernel-blocked-stacks.pbtxt) · [配对查询](fold7-drm-kernel-blocked-stacks.sql)

| 系统时钟 | CV10 在连接器锁上睡眠 ≥ 1 ms | 平均 / 最大 | 被手机提交线程唤醒 | CV10 退役栅栏/秒 |
| --- | ---: | ---: | ---: | ---: |
| 初始自动 | 0 | — | 0 | 60.34 |
| 强制 CV10 | 239 | 14.832 / 16.437 ms | 239 | 60.17 |
| 恢复自动 | 239 | 7.367 / 7.857 ms | 239 | 60.34 |

手机提交栈是 `drm_mode_atomic_ioctl → drm_atomic_nonblocking_commit → msm_atomic_commit → do_wait_intr`。在强制和恢复自动两段，分别有 236 和 239 次大于等于 1 ms 的睡眠由旧手机帧的 `crtc_commit:288` 工作线程唤醒。外屏的栈是 `drm_atomic_set_property → drm_atomic_get_connector_state → drm_modeset_lock → ww_mutex_lock_interruptible`。Linux 6.6 的这条连接器状态路径取得的是设备共享的 `connection_mutex`。表中所有长锁等待均由手机提交线程在其 `atomic_commit` 结束前后 0.5 ms 内唤醒。这样可以区分手机线程正在内核等上一帧，和 CV10 线程在自己进入 `atomic_commit` 前等同一个 DRM 设备的锁。[Linux 6.6 连接器状态](https://github.com/torvalds/linux/blob/v6.6/drivers/gpu/drm/drm_atomic.c#L997-L1008) · [Linux 6.6 原子请求分支](https://github.com/torvalds/linux/blob/v6.6/drivers/gpu/drm/drm_atomic_uapi.c)

默认时钟首次启动时即使没有长锁等待，调度仍沿手机的节拍。仅切换到 CV10 时钟会提前发出手机的新提交，导致内核的跨屏锁等待。逐屏调度实验应在手机进入同步 HWC 验证前检查上一帧是否就绪，并同时保护 CV10 的提交窗口。独立线程仍会经过相同的 DRM 锁。

本机 `ffr-quad/trace-fold7-drm-kstack.ps1` 保存采集配置，三份 `hello-drm-kstack-lit-*.perfetto-trace` 依次为初始自动、强制 CV10、恢复自动。SHA-256 分别为 `f6f0b5cccd3934d9be3cff94a74c7631eedabf1d43f86b01c38892b3d8c666c3`、`544d410d6975a34c8ecaec81a6eff380e2a89db11ba190407523c5d5abd694f5`、`74a1e184bb886ff5905f1e32eafaebec5dcfeb98b5350f3019e9f8ea127e734d`。

在 Pocket XR 的同一 Bilibili 单窗工作区再次采集。启用缓冲直采、单次 Compute 四视图合成、每眼 1600×1600 全视场和 1280×1280 独立中心，GLES 中心 1×1、外围 2×2。手机始终亮屏 60 Hz。每段约 30 秒的追踪统计锁等待，连续 15 秒的 34° 峰峰值模拟转头用于统计实际呈现和延迟。强制组电池温度 32.4°C、热状态 0→1，恢复自动组 33.9°C、热状态 1→2。场景几何一致，Bilibili 动态内容和温控有差异。

| 调度时钟 | Monado 提交/秒 | CV10 实呈/秒 | CV10 锁等待 ≥ 1 ms，匹配手机提交线程 | Pocket 提交至实呈 p50 / p95 |
| --- | ---: | ---: | ---: | ---: |
| 强制 CV10 | 89.80 | 60.31 | 1794 / 1794 | 35.58 / 46.82 ms |
| 恢复自动 | 90.00 | 60.31 | 1788 / 1789 | 32.94 / 43.59 ms |

两组各有 905 个 CV10 图层实际呈现时间戳与 Vulkan 反馈一致。长锁等待平均 12.381 / 13.707 ms，CV10 CRTC 的退役频率分别为 60.31 / 60.32 次/秒。手机栅栏信号相对该帧的 CRTC 工作结束最多提前 0.379 / 0.475 ms，仍落在实验准入的 0.5 ms 保护期内。两段的 Perfetto 统计均出现切片嵌套深度上限，锁等待来自独立的调度采样，图层实际呈现用外部的 SurfaceFlinger 和 Vulkan 时间戳核对。自动组在强制组之后采集，不能代表从未切换时钟的初始状态。

按实际呈现时刻拟合模拟头动，源图像相位延迟分别为 51.9 / 49.3 ms。后期重投影后的姿态分别接近零差和提前约 1.7 ms。固定相机画面中心没有检测到右镜内容，光学拖影和运动到光子延迟仍待验证。

本机 `ffr-quad` 保留 `pocket-inset-interleaved-kstack-*` 的追踪、日志、场景就绪记录和双目外显截图。强制、自动追踪的 SHA-256 分别为 `a462900cd9db1e8b2cfec24e2ee7521cbf167d41a10c76ef26b56e53e64ba3e1`、`66b7fde1796b71107ad87f83f1080dceb962fffca491ae455693889f1fbf30ed`。Monado 测试 APK 为 `611cbd69fd732e437180991f85c139cb009b6cf8fe4b1f5a8d02b0ce99f08ff6`，Pocket XR 为 `3abd1df3c67e6b0459a745ad353670cda1b7b91944e6c8d0ee588351cf601c5c`。完成后按 SHA-256 验证恢复原先安装包，手机回到桌面、60 Hz 和系统自动时钟。

对已测量的阻塞路径，逐屏异步原型需覆盖 `Output::present` 中的同步验证，或在该调用前按准备就绪和时限把手机输出顺延到自己的机会。跨显示共享缓冲区仍需等待所有实际消费者，不能直接跳过 `postComposition` 的释放栅栏处理。原型验收要同时跟踪按屏在途帧、热插拔或切换模式时的旧任务、全局事务 VSync ID、虚拟输出及两块物理屏的真实呈现节奏。[与 ChatGPT 6 极高的源码审阅](https://chatgpt.com/c/6b09b179-eac8-83ea-8cae-87222af8fa41)

量产系统也启用了以时长配置合成相位的属性，但 ADB 将当前三个 8,333,333 ns 时长原样传给 SurfaceFlinger 调试事务 1047 时，返回 `Operation not permitted`，相位读回无变化。[AOSP SurfaceFlinger 调试事务](https://android.googlesource.com/platform/frameworks/native/+/fcbde2bcff56ca1d7bec9cabf8bdca5e288bf103/services/surfaceflinger/SurfaceFlinger.cpp#7609)

### 进入合成前延后手机帧

[手机帧准入实验补丁](aosp-android16-late-follower-admission.patch) 默认关闭，基于与前述实验相同的 AOSP 提交。在实验系统启动 SurfaceFlinger 前，将 `debug.sf.cv10_gate_pacesetter_display` 和 `debug.sf.cv10_gate_follower_display` 分别设为 CV10 和手机的 64 位物理显示 ID。全局 `commit` 完成后，调度器在生成当轮合成目标时读取手机的在途栅栏。栅栏未完成或距离最近一次 retire 未满 0.5 ms 时，本轮先提交 CV10，下轮重新检查手机。手机这一轮不进入 `prepareFrame` 或 HWC 验证，也不登记新的呈现栅栏或完成统计。trace `CV10 late gate` 的 `decision` 0、1、2 分别代表继续、等待栅栏和等待 retire 保护期。

补丁依赖上游 `force_slower_follower_gpu_composition` 为跳过的手机输出保留先前的 GPU 读栅栏，仅在手机屏已开启、刷新率较低、独享图层栈且上一轮完全由 GPU 合成时使用。几何、颜色、缓冲移除和可信呈现状态有变更时继续走原流程。保护期根据追踪中 retire 信号到手机工作线程释放的最大观测间隔 0.448 ms 取整。这一阶段不分配 CV10 两次节拍之间的手机任务，也不执行单独的时限检查。

用手机熄屏对照估算，`commit` 结束到 CV10 进入 HWC 的中位间隔为 0.130 ms，帧开始到进入 HWC 则为 0.652 ms。在四段相同 Bilibili 场景的回放里，以 7 ms 手机呈现提前量计算，手机在 CV10 帧开始时有 43.79–44.26 次/秒的可用机会，推迟到 `commit` 后有 46.80–47.21 次/秒，推迟到 HWC 开始时有 47.54–47.75 次/秒。按手机自身时限独立排程的模型给出 59.85–60.28 次/秒，四段合计仍有六次与 CV10 的预计冲突。数据来自 [回放脚本](fold7-follower-window.py)，输出为本机 `ffr-quad/fold7-follower-late-gate-replay.txt`，计算的是手机可交帧机会。

栅栏判定的八个边界用 Android NDK 编译，并在 Fold7 的 arm64 用户空间通过。六份 AOSP 补丁按文档顺序正向应用、反向还原通过。本轮没有构建完整 SurfaceFlinger，Fold7 仍使用量产系统。实验系统应先用 Hello XR 对照，确认判定 1、2 时没有当轮手机 HWC 调用，再在相同 Pocket XR 工作区记录手机和 CV10 的实际呈现时间、栅栏释放及热状态，判断延后策略是否消除了跨屏等待。

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

### Single-pass distortion for the four-view atlas

For a compatible four-view hardware-buffer atlas, one Compute dispatch now performs late reprojection, the center/periphery blend, chromatic correction, optional center enhancement, and lens distortion. It samples the independent 1280×1280 center views directly and avoids writing and reading an intermediate full-field image for each eye. Samples are clamped inside their atlas rectangles. The path is experimental and requires `debug.xrt.CV10_FUSE_INSET=1`, Compute, timewarp, and the three atlas settings above. Other layer combinations follow the existing compositor paths.

Both test orders used the same Pocket XR Bilibili workspace, independent center views, enabled center enhancement, scanout feedback, and a 15-second analysis window within continuous simulated yaw. Fold7 stayed ON at 60 Hz and the CV10 active mode stayed at 90 Hz. Bilibili recommendations varied. The AB runs both recorded thermal status 2, with maximum skin sensor readings of 42.2→44.7°C for the two-pass run and 42.8→44.3°C for the single-pass run. The BA single-pass run started cooler at thermal status 1.

| Run | Compute path | Monado submits/s | CV10 actual/s | CV10 new source frames/s | Compositor GPU p50/p95 | Pocket swap to actual p50/p95 |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| AB | Two pass | 82.53 | 59.79 | 53.96 | 7.57 / 10.85 ms | 57.91 / 69.06 ms |
| AB | Single pass | 90.00 | 60.31 | 60.02 | 2.41 / 4.24 ms | 56.94 / 57.94 ms |
| BA | Single pass | 90.00 | 60.36 | 59.41 | 3.03 / 5.81 ms | 46.81 / 66.61 ms |
| BA | Two pass | 75.57 | 57.01 | 54.72 | 7.58 / 10.70 ms | 55.44 / 67.91 ms |

All 3419 sampled unique CV10 presentation timestamps matched the Vulkan feedback exactly. The calibrated single-pass yaw phase fits were −2.1 ms and +0.2 ms, with 0.16° and 0.24° RMS errors. The Android external screenshots show the two workspace panels in both eye images. The fixed right-eye camera was dark in both Pocket XR and the original Hello XR control, leaving in-lens center clarity, motion-to-photon delay, and smear unmeasured. Monado sustained 90 submissions per second while the Android display path still limited presentation.

The local evidence uses `fused-sharpness-ab-*`, `fused-sharpness-ba-*`, and `fused-sharpness-hello-control-*` under the same evidence directory. The replay Monado APK SHA-256 is `409428eaf782a45856631eb3ea6bca41555d91a95915bfb640eb7f22d54004e5`. The APK without synthetic IMU uses SHA-256 `62b3c37d4a19514b95faefd25fcd34ba282b953c7fd9559057d4d816bfd2f803`. Both retain the preview package name. The ordinary preview and Pocket XR builds were restored after sampling.

### Direct-source stalls on Fold7

The 90 Hz submission counter can stay steady while the program displaying the workspace has stopped sending images. In a working Bilibili cold start with the folded phone screen ON at 60 Hz, Monado submitted 90.00 frames/s, CV10 presented 60.31 times/s, and 60.09 of those presentations per second used a different Pocket XR buffer. The median time from the producer's pre-swap timestamp to Monado draw was 11.97 ms. Monado drew in a further 2.05 ms, after which 40.64 ms elapsed before the actual presentation timestamp. SurfaceFlinger matched all 854 Vulkan feedback timestamps in the motion sample.

Other cold starts of the same saved workspace repeated one source timestamp for over 20 seconds. Both tested OpenXR application wake margins (0.5 and 2.0 ms) reproduced the stall. With the diagnostic launcher, 1871–2045 consecutive capture checks reused the same image with no pending producer fence. In the onset trace, the last `eglSwapBuffers` completed in 0.17 ms. Samsung Freecess then recorded `FZ` with reason `Bg-buff` for the Pocket XR process. `UFZ` appeared as the session exited and the source resumed. During the stall, all of the sampled 60 Hz presentations reused the same workspace image. A temporary per-app Android Doze exemption, Samsung's Unrestricted app battery setting, and an experimental connected-device foreground service all reproduced the freeze. The original battery settings and APK were restored.

Opening Pocket XR's phone controls recovered the same stalled session. Freecess logged `UFZ` with reason `activity` and the existing Pocket XR process resumed rendering when TrackpadActivity came to the front. After recovery, Monado submitted 90.00 frames/s and CV10 presented 60.31 times/s with 60.11 new source images/s. All 905 timestamps matched and all images in the yaw sample used late projection. The fitted yaw phase led the replay by 2.1 ms with 0.225° RMS error. This control ran at thermal status 2 and 42.0°C skin temperature before sampling. Keeping the controls in front restores the workspace refresh while the physical displays retain their current modes.

#### Application wake margin with the phone controls in front

The same direct Bilibili workspace used a 1600² context and 1280² center per eye, the fused compositor and the same synthetic yaw. Each group used 15 seconds of matched display feedback. The shorter margin moved image selection closer to the Monado draw, but the reduction in source-to-display latency varied by run.

| Wake margin | Android thermal status | Skin sensor at start | Capture end to draw p50 | New source images/s | Pre-swap to CV10 p50 / p95 |
| --- | --- | ---: | ---: | ---: | ---: |
| 2.0 ms | 1 → 2 | 39.9°C | 2.78 ms | 56.78 | 54.83 / 66.57 ms |
| 0.5 ms | 1 → 2 | 39.9°C | 1.28 ms | 59.24 | 44.54 / 66.69 ms |
| 2.0 ms | 2 → 2 | 42.0°C | 2.81 ms | 60.31 | 54.46 / 55.58 ms |
| 0.5 ms | 2 → 2 | 42.1°C | 1.29 ms | 56.98 | 54.61 / 66.40 ms |

Monado submitted 90.00 frames/s and CV10 presented 60.31 times/s throughout. All 3620 sampled SurfaceFlinger timestamps matched Vulkan feedback. The warmer pair lost fresh source images and gained 10.82 ms at p95 with the shorter margin, so the release default remains 2.0 ms. The shorter margin remains an experimental diagnostic option. The capture timestamp is taken after acquisition and pose metadata handling. The fixtures use `pocket-margin-recovered-ab*` and `pocket-margin-recovered-ba{3,4}*`. A back-to-back startup timed out waiting for the display transport before the alternate group's first frame. Successful shorter-margin sessions were measured after separate cold starts.

#### Four-view draw-rate foveation

Pocket XR commit `9b8b3f2` adds `debug.pocketxr.cv10_quad_ffr=1` as an opt-in to the direct atlas path. The driver accepted a 2×2 fragment shading rate on the atlas EGL surface. Pocket XR applies it to the 1600×1600 full-FOV pass for each eye, then restores 1×1 for the separate 1280×1280 inset pass. Monado consumes the same hardware buffer and fuses four-view blending and distortion in one Compute pass. The atlas layout and projection dimensions are identical in both variants. [GLES fragment shading rate](https://registry.khronos.org/OpenGL/extensions/EXT/EXT_fragment_shading_rate.txt)

The comparison used two pairs in reverse order, the same saved Bilibili workspace, and 15 seconds of the same simulated yaw in each run. The Fold7 panel stayed on at 60 Hz and CV10 stayed in its 90 Hz physical mode. Thermal status started at 1 in the first pair (main skin sensor 39.9–40.0°C) and at 2 in the second pair (42.0°C). All four cold starts froze the Pocket XR process with Freecess reason `Bg-buff`. Bringing its phone controls to the front unfroze the same process before data collection. The layout and motion inputs were held fixed; the live Bilibili content could change.

| Run | Shading of the full-FOV pass | CV10 actual / fresh source frames per second | Monado GPU p50 / p95 | Pocket EGL submission to CV10 present p50 / p95 |
| --- | --- | ---: | ---: | ---: |
| A1 | 2×2 | 60.33 / 58.71 | 2.21 / 6.26 ms | 55.03 / 67.41 ms |
| B1 | 1×1 | 60.36 / 60.22 | 2.07 / 6.10 ms | 56.04 / 68.06 ms |
| B2 | 1×1 | 60.36 / 59.09 | 2.76 / 4.47 ms | 44.25 / 55.64 ms |
| A2 | 2×2 | 60.33 / 60.20 | 2.70 / 4.35 ms | 55.20 / 66.25 ms |

Across the steady 300-frame Pocket XR timer windows, query sums had p50 values of 0.03–0.07 ms with 2×2 and 0.05–0.12 ms with 1×1. These readings cover the bracketed GLES commands. Pose-to-fence and end-to-end times varied between pairs, so the experiment remains off by default. Monado submitted 89.93–90.00 times/s, and all 3618 SurfaceFlinger presentation timestamps matched Vulkan feedback. All sampled frames used late projection. The simulated yaw fit was within 0.8 ms of the requested phase (0.15–0.23° RMS); the fitted phase is derived from tracing. External screenshots captured workspace content. The fixed right lens camera did not capture a visible window, so optical clarity and smear are unverified.

The local fixtures use `pocket-quad-ffr-{a1-1,b1-0,b2-0,a2-1}-default-calibrated-true-0-2.0`. Pocket XR APK SHA-256 is `6ecd33f1dcab5393d71872b448db06080d961b439b9fe3d68bc660198ccb5ebb` and the paired Monado replay APK SHA-256 is `99d4fa267e8c0c1a113bbab17101d17be44eafa67d5cb0d0cbeccd3f750ccfc2c`. The previous Monado preview and Pocket XR APKs were restored after the experiment and both installed hashes were verified. The phone returned to its launcher at 60 Hz with 33% battery.

#### Direct producer draw lead

The launcher read the producer buffer about 2.8 ms before Monado drew in the preceding four-view samples. Depending on the run, 94–1313 of about 1350 draw records had a newer producer pre-swap timestamp by the start of the compositor draw. A pre-swap stamp only establishes that Pocket XR reached the swap call. BufferQueue publication, acquire fence completion and matching pose metadata can follow later.

An isolated Pocket XR build requested a 3 ms earlier GLES wake without shifting the intended display time, pose target, or EGL presentation timestamp. The same APK provided the zero-lead controls. All arms used the Bilibili workspace, simulated yaw, four-view direct import and 2×2 full-field shading. Thermal status started at 1 for A1, B1 and B2, and 2 for A2. The experiment moved the median pre-swap time earlier relative to its EGL presentation timestamp by 1.0–1.5 ms. The gap from the launcher's read to Monado draw held at about 2.8 ms.

| Run | Requested lead | CV10 actual / new source frames per second | Pocket pre-swap to CV10 present p50 / p95 |
| --- | ---: | ---: | ---: |
| A1 | 0 ms | 60.29 / 60.22 | 55.91 / 68.89 ms |
| B1 | 3 ms | 60.36 / 59.67 | 57.77 / 59.53 ms |
| B2 | 3 ms | 60.31 / 59.31 | 57.43 / 68.76 ms |
| A2 | 0 ms | 60.31 / 60.18 | 56.42 / 57.79 ms |

Monado submitted 90.00 times/s in all four runs, and all 3619 SurfaceFlinger presentation timestamps matched Vulkan feedback. Both lead runs had fewer new source frames per second and higher median delays than both controls. The p95 changes also differed across pairs. The Pocket XR branch retains its original draw schedule. The experimental APK, local patch and timing logs are archived under `pocket-bridge-lead-*` and `pocket-bridge-draw-lead-experiment.patch`. The APK SHA-256 is `0d7b37785a0406145077cc2d0766162bd718f86282e444c98f6ac316e97eca60`. The right lens camera captured no visible window in the B2 run. The previous APK hashes and the phone's 60 Hz mode were verified after restoring them.

The traces are under `pocket-margin-*`, `pocket-pick-*`, `pocket-freecess-*`, `pocket-bg-unrestricted-*`, `pocket-direct-service-*`, and `pocket-controls-front-*` in the local evidence directory. `analyze-pocket-pipeline.py` joins source, draw, GPU and presentation timestamps. `analyze-pocket-pick-perfetto.py` finds the producer thread in the onset trace. The Monado diagnostic replay APK SHA-256 is `99d4fa267e8c0c1a113bbab17101d17be44eafa67d5cb0d0cbecd3f750ccfc2c`. The right-lens camera captured a dark image in this run, so the result describes the software rendering and presentation chain.

### Pocket XR producer-buffer readiness

The direct Android stream can log readiness with `debug.cv10.pocket_fence_trace=1`. `CV10_ACQUIRE` records the image's EGL timestamp, consumer acquisition time, successful fence-poll time and the kernel signal time of the last child of the acquire fence. An unavailable signal is recorded as zero. This mode reads fence information after the normal nonblocking poll succeeds. The regular path keeps the same polling and buffer ownership. [Readiness analysis](fold7-pocket-acquire-fence.py)

Two more runs used the same four-view workspace, 2×2 full-field GLES mode, 1280×1280 center per eye, 2.0 ms OpenXR application margin and simulated yaw. Both started at thermal status 1 with a skin reading of 39.9°C. Monado submitted 90.00 frames/s and CV10 presented 60.31 times/s. All 1810 sampled SurfaceFlinger presentation timestamps matched Vulkan feedback. The median interval from the source read to Monado draw was 2.69–2.70 ms.

The larger Perfetto capture paired 2811 producer `queueBuffer` calls with acquire fences. In the 15-second motion interval, 260 of 905 draws with actual-presentation feedback had a newer producer pre-swap record available by draw time. The trace bounded the queue and fence readiness of every newer candidate for 186 of these draws. A newer image was provably ready in 40 draws by the end of the launcher read, 173 draws one millisecond after the read, and 184 draws one millisecond before compositor draw. These bounds measure potential availability in the matched subset. The larger trace still reported discarded chunks. The smaller trace filled early and only bounded one such presented draw. The separate late-read comparison measures whether the launcher can use that opportunity while meeting the compositor deadline.

The `pocket-acquire-fence-a*` fixtures include the Perfetto traces, matching logcats, and the CV10 layer's actual presentation times. The replay APK SHA-256 is `56c28ee5f51d5ceeca68801f57ff3b54064b3fc06a441df4310396c4d9b0a68b`.

An A/B/B/A comparison used the same replay APK for all four arms. B requested a 1.1 ms delay only before the direct four-view stream read. The global application wake and compositor budgets were identical across arms. Battery temperature at the start of sampling was 35.2–36.2°C. A1 moved from thermal status 1 to 2. The other three arms started and ended at 2.

| Arm | Read to Monado draw, p50 | Producer pre-swap to CV10 present, p50 / p95 | Fresh source images/s |
| --- | ---: | ---: | ---: |
| A1 | 2.74 ms | 55.05 / 68.69 ms | 60.11 |
| B1 | 2.37 ms | 56.29 / 67.16 ms | 60.11 |
| B2 | 2.34 ms | 44.33 / 55.35 ms | 60.24 |
| A2 | 2.76 ms | 57.71 / 68.64 ms | 60.22 |

The delay shortened the measured read-to-draw interval by 0.37–0.42 ms. The lower p95 ranged from 1.53 to 13.29 ms across the pairs, and the median rose in the first pair. Fresh-source rate was nearly unchanged. Monado submitted 90.00 frames/s. CV10 presented 60.31–60.36 times/s and all 3620 sampled actual timestamps matched. The simulated yaw fit remained within 1.3 ms of the requested phase in all arms with 0.19–0.23° RMS error. This fit uses tracked poses and CV10 timestamp feedback. The fixed right camera did not resolve the workspace, leaving optical clarity, smear and motion-to-photon delay unverified.

The normal build keeps its existing latch timing. The archived `pocket-late-read-experiment.patch`, `pocket-late-read-*` traces and replay APK support repeating the test. The APK SHA-256 is `75b972a3a41e14e017791f4702d1a2ded0d08060716ff64518655809f5ed5bb8`.

#### GLES submission with a ready Android stream

The Android stream importer waits for the producer's acquire fence before submitting Pocket XR's buffer, then retains the image until the compositor releases it. The launcher does no GLES rendering to these imported swapchains. The OpenGLES client still takes its context lock, creates an EGL fence for its own command stream and flushes on each `xrEndFrame`. With a valid fence descriptor, the multi-compositor schedules the prepared frame via its fence waiter. [EGL native fence extension](https://registry.khronos.org/EGL/extensions/ANDROID/EGL_ANDROID_native_fence_sync.txt)

`debug.xrt.CV10_SKIP_STREAM_GL_FENCE=true` makes frames composed exclusively of imported Android stream projection layers use the existing ready-frame submission route. It bypasses the launcher's GLES fence, including for both projection passes of the four-view atlas. Producer acquire polling, Vulkan foreign-ownership barriers and compositor image release still apply. A regular GL layer anywhere in the frame, mixed stream/GL views, RenderDoc, empty frames and the disabled option use the normal synchronization. The option defaults to false and is read once in each runtime process.

Use the same APK and restart the runtime after each setting change. Compare ON/60 and OFF/90 in separate pairs with the same workspace and turn replay. With `debug.xrt.CV10_PRESENT_TRACE=true`, `CV10_SOURCE` adds `end_frame_ns` around `xrEndFrame`. Compare its p50/p95 along with the interval from the stream read to compositor draw, fresh source frames, matched actual present timestamps and right-lens playback. In the ready-frame route, waiting for a free scheduled slot can occur on the submission thread instead of the fence waiter. Check those submit tails as part of any latency result. Test standard Hello XR and the non-direct launcher as controls.

The Android ARM64 launcher preview built successfully, passed signature and 16 KiB ZIP alignment checks, and contains both the option and the timing field. A Windows x64 check compiled the GL layer/commit functions with mocked compositor callbacks and verified stream-only, ordinary, mixed, depth, invalid mixed views, empty, RenderDoc and default-off decisions. Monado test APK SHA-256 `5b8af0790972aeee216f3166a99f41e0de5bd8605b915ac862198d8b5a554d7e`. The Fold7 was offline during this change. Hardware fence timing, actual present cadence and optical behavior await the A/B.

#### Producer wake phase feedback

The experimental direct bridge returned the time of the last Monado image read and the predicted frame interval to Pocket XR over a nonblocking pipe. Pocket XR used those samples to schedule the next GLES draw roughly 2 ms before the predicted read. Source pose prediction and EGL timestamps retained their original time bases. The experiment kept GLES 2×2 shading for the full-field passes, 1×1 shading for the 1280×1280 inset, zero-copy import and fused Compute composition. Each sample replayed 15 seconds of yaw in the same Bilibili workspace with the phone panel lit at 60 Hz.

| Arm | Thermal status | Battery temperature | Phase-aligned draws | Producer pre-swap to CV10 present, p50 / p95 | Fresh source images/s |
| --- | --- | ---: | ---: | ---: | ---: |
| A1, feedback off | 1 → 2 | 34.6°C | — | 54.68 / 67.10 ms | 57.18 |
| B1, feedback on | 2 → 2 | 35.3°C | 569/1348 | 51.73 / 58.08 ms | 59.60 |
| B2, feedback on | 2 → 2 | 35.6°C | 1349/1349 | 44.90 / 56.03 ms | 59.76 |
| A3, feedback off after cold restart | 2 → 2 | 35.8°C | — | 44.34 / 55.82 ms | 59.55 |
| B3, new Pocket XR with previous Monado | 2 → 2 | 36.2°C | — | 54.61 / 66.44 ms | 59.09 |

A2 timed out before the first source buffer arrived. A3 repeated that control after a cold restart. In B2, pose sampling was within 1–4 ms before the next Monado read for every draw. A3 reached the same window for 1334/1347 draws with normal scheduling and produced essentially the same present latency. B3 opened through the existing pose protocol and rendered the four-view atlas, verifying fallback when Monado lacks the timing transaction. Keep normal wake scheduling until a repeatable latency gain is measured.

Monado submitted 90.00 frames/s in all completed samples. CV10 presented 60.31–60.36 times/s. All 4524 sampled SurfaceFlinger presentation timestamps matched Vulkan feedback. Even in A3 and B2, the p95 interval from GPU completion to presentation was 48.50 and 49.37 ms, respectively. The fixed right camera did not resolve the workspace, so these samples do not establish optical smear, clarity or motion-to-photon latency.

Local fixtures in `ffr-quad` use the `pocket-latch-phase-*` prefix. They include both source patches, the source timing analysis and the replay logs. The experimental Monado replay APK SHA-256 is `6eb60aa9b8cf9c777c20bee5add599ea7bfa92b977cac505cf6eb6c7e96bc674`. The Pocket XR APK SHA-256 is `fcf84ac15180d3219490c10262df5f9aee43d3b524d5e2ac3c7c07361c7c5c76`.

#### Android present fences by buffer number

Enable `android.surfaceflinger.frame` and `gfx` in Perfetto to separate time before SurfaceFlinger latches a Monado buffer from time spent downstream of the latch. The frame data source records queue, acquire, latch, RenderEngine composition and present fence events by layer and frame number. The replay log supplies Monado GPU timestamps and Vulkan present IDs. In the default mode, all 905 Android fence timestamps in the 15-second yaw window matched Vulkan's actual timestamps by frame number to within 1 μs after the clock conversion. A separate BLAST trace check matched 481 of those buffers to an acquire call inside the corresponding `vkQueuePresentKHR` interval. [Android frame event schema](https://perfetto.dev/docs/reference/trace-packet-proto#graphicsframeevent)

Both runs used the same saved workspace. The frame traces identify GPSTest and Clock. Both runs enabled the zero-copy four-view atlas, 2×2 GLES shading on the context views and full-rate 1280×1280 inset views. The phone remained lit at 60 Hz. Battery temperatures were 34.5°C and 34.8°C at the start of the captures. Thermal status moved from 1 to 2 in each run.

| Interval or rate | Default MAILBOX | Single-buffer shared demand |
| --- | ---: | ---: |
| Monado successful submits/s | 90.00 | 90.00 |
| CV10 layer present fences/s | 60.36 | 60.31 |
| Buffer queued and acquired to latch, p50 / p95 | 3.14 / 9.23 ms | 3.34 / 8.75 ms |
| Latch to present fence, p50 / p95 | 19.91 / 26.14 ms | 20.93 / 28.84 ms |
| RenderEngine composite to present fence, p50 / p95 | 18.30 / 25.01 ms | 19.57 / 25.24 ms |
| Estimated missed 90 Hz slots during phone HWC `PresentOrValidate` | 421/444 | 403/445 |
| Estimated missed slots during CV10 HWC `PresentOrValidate` | 10/444 | 29/445 |

Candidate missed slots were reconstructed within gaps aligned to the 11.111 ms scanout period within 0.5 ms. Overlap shows that the phone HWC call often spans the absent CV10 update. It does not independently establish which deadline was missed within each batch. The `crtc_commit:288` retire-fence joins above provide the corresponding wait-chain evidence.

The shared-demand run used one Android buffer instead of five in the default trace. Its Vulkan feedback reported zero actual timestamps. The CV10 layer still produced 905 presents by Android's frame-numbered fence events. Keep MAILBOX for calibrated reprojection. The default run had a 32.48 / 43.56 ms p50/p95 from Pocket producer pre-swap to present. Its software yaw fit after calibrated reprojection was −0.5 ms, with 0.09° RMS error. The fixed camera did not resolve the right-lens image. Actual optical latency and smear remain open.

The local `pocket-buffer-present-g1-*` and `pocket-buffer-present-shared-*` fixtures contain both captures and matching logs. Trace SHA-256 values are `57518e9b6d9bd4943629d5c9002748b8c860559ab05b2f56e6986f885b6febbb` and `206c2443bb6c5c5f9d9f955ab7e290d47af828f365ee32ee860d9c2ef5f7f78d`. The [analyzer](fold7-pocket-present-fences.py) accepts the trace, the Monado log, the exact `--layer` name and one `--hwc-id` for each physical output. In these traces, HWC 1 is CV10 and HWC 3 is the active phone panel.

#### Full-field resolution with a fixed center

The direct four-view test holds the center at 1280×1280 per eye and changes only the full-field views from 1600×1600 to 1280×1280. It keeps the same full and inset projection angles, GLES 2×2 full-field and 1×1 inset shading, zero-copy buffer import, the fused Compute pass, CV10's 3200×1600 output and the 34° peak-to-peak yaw replay. The atlas changes from 3200×2880 to 2560×2560, reducing its logical pixel count by 28.9%. Both arms use the same Pocket XR APK and the same 15-second motion sample. The Fold7 outer panel stays lit at 60 Hz.

The first two pairs reopened the saved GPSTest and Clock workspace. The frame trace identifies both app windows. AB warmed from thermal status 0 to 2. The BA runs both stayed at status 2 with the primary SKIN reading at 42.0°C.

| Run | Full-field pixels per eye | Thermal status | CV10 presents/s | Monado GPU p50 / p95 | Pocket pre-swap to present p50 / p95 |
| --- | ---: | --- | ---: | ---: | ---: |
| A1 | 1600×1600 | 0 → 1 | 60.36 | 1.66 / 2.40 ms | 33.46 / 44.65 ms |
| B1 | 1280×1280 | 1 → 2 | 60.36 | 3.01 / 4.36 ms | 34.41 / 45.47 ms |
| B2 | 1280×1280 | 2 → 2 | 60.31 | 2.36 / 3.30 ms | 32.74 / 44.26 ms |
| A2 | 1600×1600 | 2 → 2 | 60.24 | 2.72 / 4.19 ms | 33.04 / 44.15 ms |

Monado submitted 90.00 frames/s in all four runs. The first pair matched all 1810 Android present fences to Vulkan actual timestamps by frame number. The phone's HWC call overlapped 433/444 missing CV10 presentation slots in A1 and 430/444 in B1. The CV10 HWC call overlapped zero in both. Median latch-to-present was 20.72 and 20.83 ms respectively. The warm pair reduced compositor GPU time with the smaller atlas, while the measured pre-swap-to-present tail differed by 0.11 ms.

The initial AB and BA artifacts use `pocket-buffer-present-ctx{1,2}-{1600,1280}-*` in the local `ffr-quad` directory. The same directory includes `build-pocket-context-1280.ps1`, `run-pocket-context-1280.ps1` and both source patches. The baseline Monado SHA-256 is `56c28ee5f51d5ceeca68801f57ff3b54064b3fc06a441df4310396c4d9b0a68b`, the 1280 prototype is `c94cb2cb5b50589db571336df3826d26efb31868ff92f179d437eee9004f75f6`, and the Pocket XR fixture is `82519b10e0308590f01a4bb37cf979eec49cf6c2341d85393076f966c2858d09`.

A second local fixture used a single Bilibili panel in an in-memory layout. The first pair captured Bilibili's Android splash, so those timings only describe the splash workload. Launching the same Bilibili activity on the phone panel also timed out with only the splash layer. After a process restart, the phone launch logged `Displayed tv.danmaku.bili/.MainActivityV2` in 522 ms and SurfaceFlinger showed the activity's own buffer layer.

The next BA and AB pairs started Bilibili in a fresh process for each run and used the same portrait panel geometry. The harness required both Android's `Displayed` event and the Bilibili activity's own SurfaceFlinger layer before sampling. Both eyes showed the home feed in the external-display capture. Recommendations and UI animation varied between runs. The active panel temperatures for the second pair were comparable, with thermal status 2 and a primary SKIN reading of 42.1°C throughout. Battery temperatures at sample start were 36.3°C and 36.5°C.

| Run | Full-field pixels per eye | Thermal status | CV10 presents/s | CV10 fresh Pocket buffers/s | Monado GPU p95 | Pocket pre-swap to present p95 |
| --- | ---: | --- | ---: | ---: | ---: | ---: |
| BA | 1280×1280 | 1 → 2 | 60.36 | 57.15 | 4.99 ms | 46.21 ms |
| BA | 1600×1600 | 2 → 2 | 60.31 | 56.71 | 6.59 ms | 46.70 ms |
| AB | 1600×1600 | 2 → 2 | 60.31 | 59.71 | 6.51 ms | 46.78 ms |
| AB | 1280×1280 | 2 → 2 | 60.31 | 55.04 | 6.58 ms | 45.77 ms |

Monado submitted 89.86–90.00 frames/s. The GPU time reduction in the first pair did not repeat at matched thermal status. CV10 presentation rates and the pre-swap-to-present tails were close, and the compact buffer had fewer fresh source frames in the matched pair. Keep 1600×1600 for each full-field view, with the 1280×1280 center passes, and retain the compact buffer as a local fixture. The fixed right-eye camera did not resolve the window during these samples. Center clarity and motion smear still need optical verification.

All 3620 CV10 Android present fences matched the Vulkan timestamps by frame number. The phone HWC call overlapped 407–430 out of 444–445 estimated missing 90 Hz slots per run. The CV10 HWC call overlapped 5–25. The results put the main cadence constraint downstream of Monado's draw and submit in both resolution variants.

The local Bilibili artifacts use `pocket-buffer-present-bili{2,3}-{1600,1280}-*`. Ready logs and external screenshots confirm content, and `bilibili-main-panel-relaunch.txt` records the ordinary phone control. The portrait Pocket XR fixture SHA-256 is `3abd1df3c67e6b0459a745ad353670cda1b7b91944e6c8d0ee588351cf601c5c`.

After sampling, installed SHA-256 values matched the prior Monado preview `672b281b556e8097d0e9faf6513a1c2e4a11ccf789aca90d8dd5c5dac583f601` and Pocket XR `818e885e76fcc5fb62041d1e2806ffe429579e916808678c1bc65136c061a260`. Fold7 returned to the launcher at 1080×2520 and 60 Hz with 33% battery. CV10 remained attached in its 3200×1600 90 Hz mode.

#### Four-view center enhancement and frame readiness

With enhanced rendering enabled, the fused Compute shader can use the inset texture directly for the four enhancement neighbours when every chromatic tap is inside the fully weighted inset. Bounds are computed after reprojection for red, green and blue. Taps that reach the blend ramp or have invalid center coordinates use the existing per-tap blend. This keeps the physical output at 3200×1600, the independent center at 1280×1280 per eye and the full-field source at 1600×1600 per eye.

The Fold7 comparison used the same centered Bilibili workspace, the same Pocket XR build, a 34° peak-to-peak yaw replay and the lit phone panel at 60 Hz. Separate full-session AB / BA / AB runs reported changes in Monado's GPU p95 of −1.28, −1.10 and +1.46 ms. The last pair stayed at thermal status 2 and its battery temperatures were 35.1°C and 35.6°C. The feed can refresh between launches, and the results justify a same-session comparison.

The same-session fixture switches the two routes in an ABBA sequence on adjacent compositor frames. It pairs only adjacent frame IDs and reports GPU timestamps. The first two captures use one pipeline with a uniform to select the route. The next captures use independently specialized Vulkan pipelines. The comparison measures the draw cost, including frames that did not reach a scanout.

| Comparison | Thermal status | Original GPU p95 | Candidate GPU p95 | Median paired GPU time saved | Candidate faster |
| --- | --- | ---: | ---: | ---: | ---: |
| One pipeline, first run | 1 → 2 | 4.80 ms | 4.50 ms | 0.329 ms | 587/674 |
| One pipeline, second run | 2 → 2 | 4.97 ms | 4.70 ms | 0.334 ms | 572/674 |
| Specialized pipelines, first run | 2 → 2 | 3.93 ms | 3.78 ms | 0.170 ms | 601/675 |
| Specialized pipelines, second run | 2 → 2 | 3.50 ms | 3.38 ms | 0.156 ms | 603/674 |

The specialized captures began at 36.0°C and 35.9°C battery temperature. The optimization was faster in 1204 of the 1349 specialized frame pairs. The separate full-session results are still inconclusive about a reduction in end-to-end latency.

The GPU differential fixture evaluates both neighbour sums from the same source buffer and reprojected coordinates. Across three 3200×1600 external captures it reported 2,272,194 accepted outputs with exact float equality and zero differences. One capture included 559,903 accepted pixels whose reference samples contained visible content. A separate ARM64 boundary check evaluated one million finite synthetic cases on Fold7, including signed transforms and coordinates near the blend ramp. These tests establish sampling equivalence where the fast path applies. The right-lens camera did not resolve the workspace during the comparison, so optical center clarity and smear remain unverified.

The [frame fence analyzer](fold7-pocket-present-fences.py) also reports whether milestones for a newer buffer precede each missing 90 Hz slot and its estimated SurfaceFlinger wake. The four interleaved captures have 1777 missing slots. A newer Monado frame had been submitted and completed GPU work before the nominal wake for all 1777 slots. Android had observed the queue and acquire events for 1776 of them and already latched a newer buffer in 1649. The phone HWC call overlapped 1708 of those slots, compared with 29 for CV10 HWC. These are event relationships around reconstructed slots, using the traced 8.333 ms SurfaceFlinger work duration. They cannot show that every newer buffer remained available at the actual wake. All 3618 present fences matched the Vulkan timestamps by frame number. CV10 still presented 60.31–60.36 times/s with 89.93–90.00 Monado submits/s.

The final shader has no benchmark route selector. Its replay build completed the same Bilibili yaw sequence at 90.00 Monado submits/s and 60.31 CV10 presents/s. All 905 timestamps matched by frame number. Monado's GPU p50/p95 was 2.85/4.72 ms and Pocket pre-swap to present was 33.30/44.43 ms. The physical phone panel stayed at 60 Hz and thermal status changed from 1 to 2. The replay APK SHA-256 is `611cbd69fd732e437180991f85c139cb009b6cf8fe4b1f5a8d02b0ce99f08ff6`. The final trace SHA-256 is `eb66c4bce03ac8c04b49e4fb8d124fe559f0fd86fbae4cb0aed9934b49297b4c`. The installed Monado and Pocket XR APKs were restored after capture.

The local `ffr-quad` directory contains `pocket-inset-taps-pair{1,2,3}-*`, `pocket-inset-interleaved-*`, `pocket-inset-audit-*`, `inset-taps-gpu-differential.txt`, and the matching build, run and analysis scripts. The single-pipeline fixture SHA-256 is `8dedc0213d113d50b56a81c93c6c0719df700d6de359d09591b5f92283ce5992`, the specialized fixture is `5b59435892b8929cd72b8eeef6a0f6905d34329aa3762daa0e63af3b0e325b43`, and the differential fixture is `e6d77b741d4c3f9a607f18aea80153feec4803038e6b9b990a0b45e843705c17`. The Pocket XR fixture is the same Bilibili portrait build recorded above.

#### Precomputed rolling-scanout projection coefficients

The fused four-view pass performs a separate scanout warp for each chromatic sample. `debug.xrt.CV10_QUAD_WARP_POLYNOMIAL=true` moves the combination of the tangent mapping and the two scanout matrices to the CPU once per eye per frame. The shader keeps the original row weighting and evaluates clip `x`, `y` and `w` from coefficients for `1`, `u`, `v`, `u*v` and `v*v`. It retains the matrix routine near the projection plane and preserves the atlas taps, inset blend and 1280×1280 center source. This experiment is off by default and applies only to the fused four-view pass. [Vulkan uniform layout](https://docs.vulkan.org/guide/latest/shader_memory_layout.html)

The Windows differential fixture runs the original and experimental GLSL routines on the same coordinates, using coefficients generated by the production C function. Eight pairs of asymmetric stereo poses cover a static view, both directions of yaw, mixed pitch and roll, and the edge of the projection plane. Each of the RTX 3090 and AMD Radeon runs tested 10.24 million coordinates. The largest difference on a coordinate visible in either routine was `2.45e-7` in normalized source UV, or under `0.0004` texel at a 1600-pixel context width. The near-plane fallback matched in all samples. One RTX sample crossed the boundary of the visible region. The comparison covers projection coordinates and clipping. Phone GPU time, optical clarity and physical presentation remain unmeasured for this change. Local fixture and generated shaders are under `ffr-quad/quad-warp-host/`.

The ARM64 `Monado-CV10-quad-warp-arm64-v8a.apk` built and passed signature and 16 KiB alignment checks. Its SHA-256 is `7481518d61dc466f2689b32d5d14172dadc87d6ef7c0ca05011f86453acaf8d1`. For the Fold7 comparison, leave the stream GLES fence setting the same in both arms and switch only the polynomial option. Restart Monado between arms. Compare GPU p95, fresh source buffers, exact CV10 present fences and the right lens with the same four-view workspace, temperature range and yaw replay.

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

#### Direct four-view GLES FFR on and off

The powered original cable, lit 60 Hz phone panel and automatic SurfaceFlinger clock were held across two test orders. The Pocket XR fixture opened one centered Bilibili window and replayed the same 34° peak-to-peak yaw. The direct atlas carried a 1600×1600 context and an independent 1280×1280 inset per eye. GLES FFR changed the context from 1×1 to 2×2 sampling and kept the inset at 1×1. Both groups used the zero-copy hardware-buffer import, fused Compute compositor and CV10 scanout feedback. The app feed could refresh between runs. Battery temperature at capture start ranged from 32.2°C to 34.8°C, and Android thermal status ranged from 0 to 2 across the four runs.

| Order | GLES FFR | Monado submits/s | CV10 presents/s | Fresh Pocket source frames shown/s | Pocket pre-swap to present p95 |
| --- | --- | ---: | ---: | ---: | ---: |
| AB | On | 90.00 | 60.36 | 60.02 | 46.84 ms |
| AB | Off | 90.00 | 60.33 | 60.07 | 46.84 ms |
| BA | Off | 90.00 | 60.40 | 55.99 | 54.98 ms |
| BA | On | 90.00 | 60.31 | 60.04 | 46.71 ms |

Across those windows all 3619 Android present fences matched Vulkan feedback by frame number. Observed present intervals reached 22.22 ms at p95. Of the 1776 inferred missing 90 Hz slots, 1735 overlapped the phone's HWC PresentOrValidate and 1758 already had a newer Monado GPU result by the estimated SurfaceFlinger wake.

In the BA order, sampling Adreno's system-wide `gpu_busy_percentage` and `devfreq/cur_freq` at approximately 5 Hz gave 46% median and 50% p95 GPU busy with FFR off, and 38% median and 41% p95 with FFR on. Median frequency rose from 607 to 660 MHz. These are device-wide samples at different clocks and thermal states, so the percentages cannot isolate the producer's GPU time. They show available GPU time during the cadence limit. The fitted software yaw phase after late warp ranged from −2.1 to +2.7 ms. Optical blur and clarity still need an optical measurement. The external-display screenshots contained the workspace and the fixed right-lens images had a black center.

Evidence is in `ffr-quad/fold7-slot-ffr-{comparison,pose}.txt`, `fold7-slot-ffr-ba-gpu-{summary,series}.txt` and `pocket-inset-interleaved-slot-ffr-*`. The Monado replay APK was `611cbd69fd732e437180991f85c139cb009b6cf8fe4b1f5a8d02b0ce99f08ff6` and the Pocket fixture was `3abd1df3c67e6b0459a745ad353670cda1b7b91944e6c8d0ee588351cf601c5c`. The pre-test APKs and desktop were restored.

## 独立手机时隙探针

[AOSP 调度探针](aosp-android16-probe-independent-follower-slot.patch) 基于同一 `frameworks/native` 提交。设置 `debug.sf.cv10_slot_pacesetter_display` 和 `debug.sf.cv10_slot_follower_display` 为两块屏的物理 ID 后生效。每次 CV10 批次结束后，用手机自身 VSync 跟踪器挑一段可用时间，并通过原有 SurfaceFlinger 主线程 Looper 安排探针。初始预算留出 2.75 ms 给手机合成、0.5 ms 给下次 CV10 唤醒，在手机预计呈现前至少 7.8 ms 开始。最近已知的内屏 retire 至少完成 0.5 ms。规划时另外给 retire 的预测误差留 0.5 ms、给内屏截止时间留 0.25 ms。回调重读两个时钟、手机最后实际 retire 和待完成栅栏，遇到新批次、热插拔、模式不一致、超期或手机时钟相位变化时放弃。若 CV10 下一次唤醒还未排定，可以用 CV10 跟踪器确定何时记录回调。回调判定窗口可用仍要求实际排定的唤醒。探针只记录 `CV10 slot plan`、`CV10 slot check`、`CV10 slot canceled`，维持原有输出、合成和栅栏行为。

[回放](fold7-follower-slot-replay.py) 与系统探针共用 [C++ 预算函数及检查程序](fold7-follower-slot-check.cpp)。Windows x64 与 Android arm64 均通过编译和边界检查，Fold7 上执行的决策结果一致。系统补丁在固定 AOSP 基线上独立及与前六项补丁组合通过正反向应用，完整 SurfaceFlinger 尚未构建或部署到 Fold7。

四段约 30 秒的 Pocket XR 直出追踪回放中，用实际 retire 重建 CV10 的 90 Hz 时隙，假设该时隙的计划唤醒可及时读取，手机 HWC 提交全部改由独立任务处理。内屏可用窗口在准时唤醒时为 60.26–60.31 次/秒，晚 0.5 ms 时为 60.26–60.33 次/秒，晚 1 ms 时降至 58.37–58.45 次/秒。四段晚 0.5 ms 的检查均给手机留足 7.8 ms，晚 1 ms 的 1600 档有一次低于预算。保留量产机现有手机 HWC 调用、用该调用开始至 retire 估计在途区间时，晚 0.5 ms 的可用窗口只有 36.46–49.52 次/秒。回放未执行新的显示提交，也无法验证两个输出改变相位之后的栅栏顺序。

[唤醒事件查询](fold7-follower-schedule-availability.sql) 把 `scheduleFrame`、`vsyncCallback` 与每轮 SurfaceFlinger `composite` 结束对齐。在手机 OFF 的 90 Hz 对照中，536 个完整批次在本次唤醒和合成结束之间均未记录下一次 `scheduleFrame`。手机 ON/60 的 Pocket XR 强制 CV10 时钟与自动时钟两轮中，分别只有 11 和 13 个批次记录到下一次请求且距离回调尚有至少 3.25 ms。该查询没有读取 `getScheduledFrameResult()` 的返回值。这解释了为什么还需协调独立任务与 CV10 唤醒的预约时机。原型若成功进入实际提交路径，应在任何可触发手机 `presentOrValidate` 的操作前重新判断时限，并按各输出单独维护栅栏及缓冲释放。原有实验中的 `Output::present` 已经可能在返回 future 之前提交手机，因此延后 future 等待无法实现这个时序。

本机证据位于 `ffr-quad/fold7-follower-slot-replay.txt`、`ffr-quad/fold7-follower-slot-device.txt`、`ffr-quad/fold7-follower-schedule-availability.txt`。这是调度实验，量产机 ON/60 的 CV10 实际呈现仍约 60.31 次/秒。熄内屏的 90 Hz 对照仍可复现，未作为亮屏目标的验收结果。

### 主合成队列的主动唤醒实验

[主动唤醒补丁](aosp-android16-request-next-cv10-wake.patch) 接在[独立时隙探针](aosp-android16-probe-independent-follower-slot.patch)之后，基线仍为 `platform/frameworks/native@fcbde2bcff56ca1d7bec9cabf8bdca5e288bf103`。在系统实验构建启动 SurfaceFlinger 前，除探针的两块物理屏 ID 外，可设置 `debug.sf.cv10_slot_wake_requests` 为 1–900，默认 0。上限按 SurfaceFlinger 进程计数，包括队列争用等失败请求。

当 Fold7 内屏和 CV10 都亮着、CV10 领跑且预测留得出内屏的提交窗口时，探针检查主合成队列。如果队列还空着，就通过 CV10 当前 VSync 注册补发一次普通帧请求。已经排好的唤醒和已入主线程队列的帧保持原样。若新的 VSync 已经到达，旧批次不能再替它发请求。每次实验请求记录 `CV10 slot wake request=N target=T wake=W vsync=V`，未取得调度结果时 W 和 V 为 0。

探针仍在同一个 Looper 重查。它只接受紧邻上一批的 CV10 VSync，允许预测相位有四分之一周期偏差。如果调度器已经跳到更晚的一拍、旧栅栏仍忙或唤醒太近，则该时隙无效。系统构建实测时对齐 `CV10 slot wake`、`VSYNC-sf`、`CV10 slot check` 和外显的 HWC 调用及实际呈现时间戳，分别核对请求、唤醒和扫描输出。该补丁会改变实验模式下的帧唤醒安排，手机图层提交及栅栏仍由现有合成流程处理。

八项系统补丁按「每屏周期、呈现追踪、慢屏栅栏门、validate-only、跟随屏追踪、延后准入、独立时隙探针、主动唤醒」顺序在基线上正反向应用通过。尾部两项都应用后，Windows x64 和 Fold7 上的 Android arm64 C++ 检查通过。检查覆盖命中下一拍、超出相位容差、误认后一拍和无效周期。两端七条边界决策输出一致，结果见 `ffr-quad/fold7-follower-wake-check-{host,device}.txt`。补丁同时在上游 `MessageQueueTest` 加入保持已有唤醒和拒绝陈旧批次的用例。完整 SurfaceFlinger 与其测试套件尚未构建或部署。量产 Fold7 此时仍为内屏 1080×2520 @ 60 Hz，CV10 使用原厂线。

### 批次内调用顺序与上一轮手机提交

[批次顺序查询](fold7-hwc-batch-order.sql) 将两路 HWC 调用归到同一次 SurfaceFlinger 合成，再配对 CV10 的 DRM 请求与重叠的手机 atomic 提交。配对要求重叠至少 1 ms，并且 CV10 在手机 atomic 结束后 0.5 ms 内进入自己的 atomic。这是基于追踪切片的时序配对，锁和唤醒关系另见上面的内核栈采样。

| 量产机对照 | 两路均调用 HWC 的批次 | CV10 先调用 | 配对到手机等待 | 手机在本批合成前已启动 |
| --- | ---: | ---: | ---: | ---: |
| Pocket XR 直出四轮，内屏 ON/60、CV10 时钟 | 7214 | 7214 | 7084 | 7070 |
| Hello XR，内屏 ON/60、CV10 时钟 | 208 | 208 | 199 | 190 |
| Hello XR，内屏 ON/60、自动时钟 | 209 | 0 | 209 | 0 |
| Hello XR，内屏 OFF、CV10 时钟 | 0 | 0 | 0 | 0 |

Pocket XR 的 7070 次较早启动中，7066 次在上一次 SurfaceFlinger 合成结束前就已启动。其余 4 次处于采样边界，缺少可对照的上一批结束时间。单独把 CV10 排到手机前面无法消除已经在途的 phone atomic 等待。手机动态更新的实验应从产生等待的那次提交着手，核对上一帧 retire、在途 phone atomic、CV10 唤醒和同一物理屏的新呈现栅栏。若把等待移到 HAL，则还要验证 HAL 等待期间未持有跨屏锁，且 CV10 的提交线程可继续运行。查询输出保存在 `ffr-quad/fold7-hwc-batch-order.txt`。

四轮 Pocket XR 中有 7,059 次手机 atomic 跨过下一批合成的开始时刻。这些调用在下一批开始时剩余耗时的 p95 为 14.21–14.93 ms。有 6,686 次能把手机请求的开始放进上一批手机 HWC 调用内，它们的内核 atomic 全部在该 HWC 返回之后才结束，返回后剩余时间的 p95 为 16.24–16.34 ms。HWC 调用返回不足以证明内核已释放跨屏锁。

用[旧帧 retire 和工作线程退出查询](fold7-drm-pending-commit.sql)处理同四段追踪。7,212 次能把等待配到手机上一帧 retire，retire 到 `complete_commit` 退出的 p95 为 0.198–0.235 ms，最大 0.671 ms。原 0.5 ms 保护时间在其中四次短于这个间隔。为 HAL 实验选择保护时间时，可从 0.75 ms 起测，并保留时限检查。这只是追踪观测到的范围，不能保证更高系统负载下仍能覆盖。若把等待移到 HAL 的实际 DRM 请求前，应验证该线程等待期间 CV10 的 DRM 请求已经能进入内核，并继续按真实呈现栅栏验收。附加输出为 `fold7-hwc-batch-order-timing.json` 和 `fold7-phone-retire-worker-pocket.json`。

### 内屏在进入 DRM 前等待上一帧

[Qualcomm HAL 实验补丁](sm8750-cv10-guard-phone-retire.patch) 基于公开的 SM8750 display-core `89aa1914da50aae0fb1b2ebf3db38d34fba5f748`。默认关闭。实验系统初始化 Composer 前，可将 `vendor.display.cv10_retire_guard_crtc` 设为要观察的内屏 DRM CRTC 编号。量产追踪所见的编号为 288。实验只在该内屏已亮、60 Hz 且正常异步提交时生效，保留同步提交、刷新率切换、受保护会话和电源转换的原路径。

实验在准备本次 DRM 请求前，至多等待旧帧栅栏 17 ms。收到信号后，以内核时间戳为准预留 0.75 ms，整个准入窗口为 18 ms。公开驱动的 `RETIRE_FENCE_OFFSET=1` 会让栅栏在下一次提交结束后触发。连续使用该模式时，等待更早一笔提交返回的栅栏，它对应当前在途帧。遇到失败、模式过渡或空提交时清理缓存，超时或栅栏无效时走原有提交并记录原因。[Qualcomm 连接器栅栏偏移](https://github.com/OnePlusOSS/android_kernel_modules_and_devicetree_oneplus_sm8750/blob/d50b305f7da9e14715a25120a4ac7b1a4b8b97c3/vendor/qcom/opensource/display-drivers/msm/sde/sde_connector.c#L2319-L2338) · [栅栏信号时序](https://github.com/OnePlusOSS/android_kernel_modules_and_devicetree_oneplus_sm8750/blob/d50b305f7da9e14715a25120a4ac7b1a4b8b97c3/vendor/qcom/opensource/display-drivers/msm/sde/sde_fence.c#L1129-L1139)

四段 Pocket XR 追踪的 7,212 条配对记录中，从 `HWDeviceDRM::AtomicCommit` 入口到旧帧 retire 的 p99 为 16.28–16.43 ms，最大 16.70 ms，均落在 17 ms 的等待窗内。Fold7 上运行从补丁源码提取的 arm64 探针，覆盖 10 个合成等待用例和 6 个栅栏历史状态检查。未触发的测试描述符在 17.059 ms 返回。探针也用 Fold7 驱动生成的 EGL 原生栅栏成功走过真实的 `poll` 和 `SYNC_IOC_FILE_INFO`。这次栅栏由离屏绘制生成。文档中的时序回放依据量产系统原有调度。证据位于 `ffr-quad/cv10-retire-guard-poll-replay.json`、`ffr-quad/cv10-retire-guard-check-device.txt` 和 `ffr-quad/build-cv10-retire-guard-check.py`。

完整 Composer 尚未安装。可替换 HAL 的实验系统中，用同一 ON/60 场景核对 `CV10 wait before DRM` 区间与 `CV10 retire guard` 结果。成功时应看到外屏的 `atomic_commit` 能进入内核，内屏随后才提交；也需要确认内屏持续有新帧时，两个显示的实呈节奏。如果 SurfaceFlinger 仍在同步等待手机 HWC，此处移出的等待仍会出现在上层，需要配合逐屏调度。

### 手机画面未变时复用上一帧

量产机的一段 Pocket XR 静置手机画面追踪录到 CV10 420 次、手机 419 次 HWC 验证或提交，频率均约 60.32 次/秒，手机调用平均耗时 12.51 ms。另一组 Hello XR 的两次手机截图 SHA-256 一致，仍记录到持续的手机 HWC 调用。截图不能证明两次采样之间每一帧的图层状态，固件也不会因为两个画面看起来一样就自动跳过手机提交。原始证据为 `pocket-policy-static-phone.perfetto-trace` 和 `hello-policy-static-phone-hashes.txt`。

[复用未变化输出的补丁](aosp-android16-reuse-unchanged-follower.patch) 接在前八项补丁之后。启用已有的两块物理屏 `debug.sf.cv10_gate_*` ID 及 `debug.sf.cv10_gate_reuse_unchanged=true`，切换为按内容变化判断的试验模式。CV10 领跑且两块屏都亮着时，独享图层栈、已有完成呈现、无输出损坏区域、无手机层状态变化或新锁存缓冲的手机可沿用 HWC 上一帧。空损坏区域的新缓冲仍应提交并产生正常释放栅栏。HWC 请求刷新、新屏幕截图、屏幕拓扑或合成状态发生变化时照常提交。

复用发生在生成合成目标时，抢在 `prepareFrame` 及 `presentOrValidate` 之前。它保留手机的上一次呈现和已持有的缓冲，不给手机记一条新的呈现栅栏。接下来收到手机内容更新时，由正常流程触发新提交。实验使用 `CV10 reuse follower` 标记与同轮 HWC 调用交叉核对，并统计手机亮屏期间 CV10 的实际呈现。静态复用之后还需单独验证手机持续有内容更新的场景。

在独立 Git 工作树上应用九项补丁，逐个反向应用后与固定基线逐字节核对，再重新应用并核对最终内容。完整系统合成器尚未构建或部署，对量产 Fold7 的呈现频率没有实测提升。

## 可选熄屏 90 Hz

实验版在 Pocket 预览首帧到达后提供「熄灭手机屏幕以启用 CV10 90 Hz」。需要已有的 Shizuku 桥接授权和 CV10 时钟，以及用户在该次会话里确认。熄屏期间工作区继续运行，最多维持十分钟。关闭预览或它的进程退出时恢复 Android 对手机面板和合成时钟的控制。

实机用真实 CV10 IMU 和直接输出进入该模式。确认后，手机两个面板均报告 committed OFF，外显维持 ON，CV10 图层在六秒采样中的滚动呈现为 90.0/s，右镜拍到工作区。强行结束预览客户端后，手机面板恢复 ON/60，SurfaceFlinger 的强制外屏时钟随即清除。此轮复测的是客户端退出，十分钟上限和物理拔线恢复另待独立验收。

最终构建重复验证了出画和退出回收。熄屏切换后的前两个滚动样本为 87.9/s，随后三个样本均为 90.0/s，p99 间隔 11.1 ms。

### 使用无线 ADB 熄屏

普通 Android 应用调用逐屏电源接口需要 `MANAGE_DISPLAYS`。SM‑F9660 报告该权限的保护级别为 `signature`。Monado 预览的内置按钮通过 Shizuku 以 shell 身份执行显示命令。已获授权的电脑也可以经无线 ADB 直接执行。 [Android 16 显示服务](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/display/DisplayManagerService.java) · [逐屏熄屏和复位命令](https://android.googlesource.com/platform/frameworks/base/+/a8458cd441e838c29087077dd6510543f394ee63%5E%21/)

先启动 XR 应用并确认 CV10 已出画。用第一条命令读取当前显示 ID，将 `$cv10` 设为该列表中 `HUAWEIAV03` 的物理 ID。若现有会话已管理外屏时钟和手机电源，退出该模式后再进行手动对照。这里只将默认内建显示 `0` 熄灭。

```powershell
adb -s $serial shell dumpsys SurfaceFlinger --display-id
adb -s $serial shell sfdo force-pacesetter $cv10
adb -s $serial shell cmd display power-off 0
```

结束时恢复面板状态和自动时钟。

```powershell
adb -s $serial shell cmd display power-reset 0
adb -s $serial shell sfdo reset-forced-pacesetter
```

这条路径已在实机对照中使用。原版 Hello XR 约 90.0 次实呈/秒，关闭手机面板并指定 CV10 时钟的独立 RGBX 探针测得 88.42 次实呈/秒，p95 仍为 11.111 ms。另一次熄屏对照保留手机作为调度时钟，CV10 只有 59.08 次 HWC 调用/秒。手动操作结束应执行复位。证据见 `trace-fold7-composer.ps1`、`mivr470-hello-phone-off*-scheduler.txt`、对应 Perfetto 文件，以及 `fold7-dpu-probe/run.ps1` 和 `summary.txt`。

### 一次 ADB 启动，手机自行恢复

[Fold7 熄屏脚本](fold7-cv10-screen-off.sh) 可由已配对的电脑启动一次。它在手机的 shell 进程中运行，识别当前的 CV10 物理显示 ID，按指定秒数熄灭手机面板。到期、收到终止信号、查不到同一个 CV10 或电量低于 20% 时恢复面板和系统时钟。以下用 60 秒作为示例。应先让 XR 应用出画，退出其他正在管理外显时钟的模式。

```powershell
adb -s $serial push history/fold7-cv10-screen-off.sh /data/local/tmp/fold7-cv10-screen-off.sh
$panelJob = adb -s $serial shell 'setsid sh /data/local/tmp/fold7-cv10-screen-off.sh 60 </dev/null >/data/local/tmp/fold7-cv10-screen-off.log 2>&1 & echo $!'
adb -s $serial shell cat /data/local/tmp/fold7-cv10-screen-off.log
```

若要在到期前恢复，向仍在运行的脚本发 `TERM`。日志应显示 `restored forced clock: nullopt`。

```powershell
adb -s $serial shell kill -TERM $panelJob
```

实测仍用带外接 5 V 的原厂线。先关闭 Shizuku 服务，再在同一原版 Hello XR 会话内以已授权的无线 ADB 启动 18 秒脚本。启动命令退出后，脚本由 Android 的 PID 1 托管，UID 为 2000。两块手机面板报告 OFF，外屏报告 ON，SurfaceFlinger 的六段滚动样本均为 90.0 次实际呈现/秒，p99 间隔为 11.1–11.2 ms。熄屏前及自动恢复后，手机在亮屏 60 Hz 模式，CV10 实呈稳态均为 60.3 次/秒。自动恢复清除了时钟覆盖，测试结束后重启了原有的 Shizuku 服务。

外显系统截图里有双眼蓝块，固定相机未拍到右镜内容。这一轮确认了实际呈现栅栏与自动恢复，光学画面及物理拔线分支待验。此脚本只负责显示电源和时钟，Pocket XR 创建受信任虚拟屏或启动其他应用窗口时所需的授权另行处理。证据位于 `ffr-quad/hello-standalone-adb-panel-b-session.txt`、同前缀外显截图和右镜照片，以及 `head-motion/hello-standalone-adb-panel-b-*-cadence.txt`。

## 本机证据

原始数据保存在主项目的 `.research/monado-rc2-fold7/pocket-performance/ffr-quad/`。同级 `head-motion/` 留有 `*-cadence.txt`。设备标签和实测 APK 摘要用于匹配本机安装，文档中的性能读数来自实验构建。

- `hello-policy-{lit-auto,lit-forced,off-forced}-trace-summary.txt`、`hello-policy-hwc-{thread-state,binder}.txt` 和对应 Perfetto trace，记录 Hello XR 的调度对照。

- `hello-composer-{lit-auto,lit-forced,off-forced}.perfetto-trace`、`composer-waits-summary.txt` 和 `trace-fold7-composer.ps1`，记录 Qualcomm DRM 工作线程及 Composer Binder 等待的对照。

- `hello-drm-lock-lit-{auto,forced}.perfetto-trace`、`hello-drm-lock-lit-{auto,forced}-dump.txt` 和 `trace-fold7-drm-lock.ps1`，记录两块屏的帧周期提示、HWC 调用和采样前后的物理模式。
- `hello-client-lit-gpu.perfetto-trace` 和 `hello-hwc-present-stage-times.txt` 补充比较了强制 GPU 合成和两轮强制外屏时钟的主线程等待。
- `fold7-follower-workload-guard-{050,075}.txt` 保存长样本回放。 `fold7-vendor-present/CHCDBZJ9/` 保存只读采集的 `libdrm.so` 和相关反汇编。
- `hello-lit-optical-on60-*` 记录 35% 电量、内屏 60 Hz 时的短测。Hello XR 进入 FOCUSED，外显截图有双目蓝块。桌面和样例的右镜相机画面都接近全黑，亮度第 90 百分位均为 0。固定相机当时未取得可核对的光学画面，样例已退出并回到手机桌面。
- `hello-wired-dex-settled-*` 是有线 DeX 的活动模式和可选模式，`hello-after-wired-dex-*` 是恢复镜像后的外显实呈、右镜实拍和 OpenXR 会话记录。
- `hello-connection-lock-*`、`hello-connection-recovery-*` 保存新增的跨屏提交采样。第二轮强制 CV10 和恢复系统自动时钟后约 9 秒的 trace SHA-256 分别为 `d6807793e024a10dbc0f8f7001d58f8e774eb2772b7978d9366120f42349bf99` 和 `c582ce5e3f4de3d08d9264cdd89156358c6b550e4480215b522902d6251af819`。
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
