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

长等待发生在提交本帧栅栏以前。公开的同平台 DRM 内核代码在等同 CRTC 或同一平面的待完成提交前取得共享 `connection_mutex`，然后才准备栅栏和派发新工作。这与手机等待上一帧、CV10 同时被拖住的时间顺序相符。量产内核的具体锁和提交标志仍缺直接读数。[同平台 DRM 源码](https://github.com/LineageOS/android_kernel_qcom_sm8750-modules/blob/5d27ace07b893b611cf3ce1c301b760f32d59892/qcom/opensource/display-drivers/msm/msm_atomic.c#L871)

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

### 独立提交应覆盖验证阶段

用 [HWC 调用外层切片查询](fold7-hwc-present-ancestors.sql) 复查上述两段追踪。在第一段，359/359 个手机 `HwcPresentOrValidateDisplay 3` 都位于 SurfaceFlinger 主线程的 `Output::present → prepareFrame → chooseCompositionStrategy → getDeviceCompositionChanges` 内，平均耗时 14.138 ms。第二段有 268/268 个，平均 13.930 ms。这个调用发生在 `Output::present` 返回 future 之前。现有并行 present 从 `prepareFrame` 之后才开始，单独调整 `future.get()` 仍会保留实测的长等待。[AOSP Output](https://android.googlesource.com/platform/frameworks/native/+/fcbde2bcff56ca1d7bec9cabf8bdca5e288bf103/services/surfaceflinger/CompositionEngine/src/Output.cpp#470)

[显示管线插桩补丁](aosp-android16-trace-present-pipeline.patch) 默认关闭。在实验系统构建启动 SurfaceFlinger 前设 `debug.sf.cv10_trace_present_pipeline=true`，采集 graphics trace。调度器记录已选中跟随屏的预计呈现与参考栅栏，输出对照原有背压跳过事件。CompositionEngine 按批次和显示 ID 标识 `Output::present` 及每个 future 的等待，SurfaceFlinger 用同一批次标识记录各 FrameTargeter 注册时的预计呈现与栅栏状态。注册事件可以显示栅栏仍处于 pending，实呈仍用内核 retire 或合格的实际呈现栅栏统计。补丁没有更改选择、提交或栅栏完成顺序，已按指定 AOSP 基线做正反向及三补丁联合应用检查，尚未编译或在系统上运行。

[主线程等待查询](fold7-hwc-present-stage-times.sql) 在两段强制外屏时钟追踪中均读到 `Waiting on HWC` 平均 0.002 ms，分别有 360 和 269 次。另一次强制 GPU 合成的追踪仍出现 419 次手机 `HwcPresentOrValidateDisplay 3`，平均 13.504 ms。AOSP 的 `getDeviceCompositionChanges` 允许 HWC 在验证阶段直接呈现，成功时随即记录已返回的 present 和 release fences。带有相同芯片平台标记的公开 Composer 源码会把显式 `validate` 和合并命令分别以 `validate_only=true` 和 `false` 传给 `CommitOrPrepare`。三星私有实现的具体路径仍需实测。[AOSP HWComposer](https://android.googlesource.com/platform/frameworks/native/+/fcbde2bcff56ca1d7bec9cabf8bdca5e288bf103/services/surfaceflinger/DisplayHardware/HWComposer.cpp#535) · [公开 Composer 实现](https://github.com/LineageOS/android_hardware_qcom_display/blob/28edbb21cf722c6ce5acdd7711af9cc692167a12/composer/AidlComposerClient.cpp#L1276)

[逐屏拆分验证与呈现的补丁](aosp-android16-validate-only-display.patch) 默认关闭。实验系统启动 SurfaceFlinger 前，把 `debug.sf.cv10_validate_only_display` 设为内屏的 64 位物理显示 ID，以 `dumpsys SurfaceFlinger --display-id` 为准。当前采样里的 Composer 显示 ID `3` 是另一套编号。补丁让指定屏走 AOSP 已有的分开验证和呈现路径，沿用其栅栏、最早可呈现时间和释放流程。插桩中的 `skipEligible=1` 表示这帧原本会走合并路径。用同一批图层、模式和时钟做开关对照，记录内屏验证、内屏呈现及 CV10 实际 retire 间隔。等待若移到内屏实际提交，可把逐屏准入点放在提交前。若独立验证仍然同步等待，就需继续检查 HAL 验证链。此补丁已做四补丁联合正反向应用检查，尚未编译或部署。

对已测量的阻塞路径，逐屏异步原型需覆盖 `Output::present` 中的同步验证，或在该调用前按准备就绪和时限把手机输出顺延到自己的机会。跨显示共享缓冲区仍需等待所有实际消费者，不能直接跳过 `postComposition` 的释放栅栏处理。原型验收要同时跟踪按屏在途帧、热插拔或切换模式时的旧任务、全局事务 VSync ID、虚拟输出及两块物理屏的真实呈现节奏。[与 ChatGPT 6 极高的源码审阅](https://chatgpt.com/c/6b09b179-eac8-83ea-8cae-87222af8fa41)

量产系统也启用了以时长配置合成相位的属性，但 ADB 将当前三个 8,333,333 ns 时长原样传给 SurfaceFlinger 调试事务 1047 时，返回 `Operation not permitted`，相位读回无变化。[AOSP SurfaceFlinger 调试事务](https://android.googlesource.com/platform/frameworks/native/+/fcbde2bcff56ca1d7bec9cabf8bdca5e288bf103/services/surfaceflinger/SurfaceFlinger.cpp#7609)

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

- `hello-drm-lock-lit-{auto,forced}.perfetto-trace`、`hello-drm-lock-lit-{auto,forced}-dump.txt` 和 `trace-fold7-drm-lock.ps1`，记录两块屏的帧周期提示、HWC 调用和采样前后的物理模式。
- `hello-client-lit-gpu.perfetto-trace` 和 `hello-hwc-present-stage-times.txt` 补充比较了强制 GPU 合成和两轮强制外屏时钟的主线程等待。
- `hello-lit-optical-on60-*` 记录 35% 电量、内屏 60 Hz 时的短测。Hello XR 进入 FOCUSED，外显截图有双目蓝块。桌面和样例的右镜相机画面都接近全黑，亮度第 90 百分位均为 0。固定相机当时未取得可核对的光学画面，样例已退出并回到手机桌面。
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
