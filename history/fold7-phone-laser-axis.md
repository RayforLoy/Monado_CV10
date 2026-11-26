# Fold7 手机激光笔方向验证

2026-11-27 香港时间，使用 Galaxy Z Fold7（SM-F9660）、C9.53.6 原厂线、外接 5 V 和三星镜像模式。CV10 定位为 `HUAWEIAV03`，活动输出为 3200×1600 @ 90 Hz。两组测试都由 Monado CV10 Preview 从 2D 应用启动器进入内置 3D 菜单，等待 OpenXR 会话进入 `FOCUSED`。摄像头固定对准右镜。

## 方向对照

在 Fold7 的 `Game Rotation Vector` 回调中锁存当时的真实姿态矩阵。数字键 0 至 4 分别选中置中、向上、向下、向右及向左。方向样本以锁存姿态为基准生成，俯仰绕校准后的右轴旋转 ±0.20 rad，左右绕重力竖轴旋转 ±0.20 rad。保持真实传感器回调、平滑滤波、JNI、立体渲染和外显输出。合成方向样本用于在固定接线下验收映射，手机保持静止。Android 旋转向量的世界竖轴指向上方。[Android 传感器文档](https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion#sensors-motion-rotate)

在左右眼画面分别转正后，以激光环颜色提取坐标。下表是相对各自置中位置的像素位移，横向向右为正，纵向向下为正。

| 指向变化 | 原映射，左眼 / 右眼 | 修正后，左眼 / 右眼 |
| --- | --- | --- |
| 向上 | Y +149 / +149 | Y −149.5 / −149 |
| 向下 | Y −151 / −150 | Y +150.5 / +151 |
| 向右 | X +204 / +204 | X +205 / +204 |
| 向左 | X −203 / −204 | X −203 / −204 |

原映射把传感器的正仰角直接传给纵向向下为正的菜单坐标。修正后只取反仰角，横向结果与原映射一致。将诊断指向再次抬高，在手机上从导航条内侧向上划并停留 450 ms，左右画面中的光标回到各自的置中坐标。右镜实拍与两路外显采样的方向一致。

- [原映射右镜实拍](evidence/fold7-laser-control-right.jpg) · [修正后右镜实拍](evidence/fold7-laser-fixed-right.jpg)
- [外显光标像素](evidence/fold7-laser-screen-pixels.csv) · [Fold7 会话与激光日志](evidence/fold7-laser-axis.txt)
- 复现对照时，在 `4e85e3e` 应用[诊断补丁](phone-laser-axis-diagnostic.patch)。原映射包 SHA-256 为 `54a97fc55d3c97ad2e6cc5f045d402abf20f54b615f9927fbbb7e535f8ad41d9`。第二组在其基础上应用 `5d58700` 的俯仰表达式，诊断包 SHA-256 为 `94a7fd59278a9f9567ea8753ea245f5a33b8a7c1557b01e6b45978b581d3f7b5`。

## 普通预览包回归

`5d58700` 的普通 arm64 预览包 SHA-256 为 `e08a76447ec20644de1340f1494ba0f18f6a01cd4eda7b32b2f6be7fdf9becf3`。Fold7 安装文件的摘要一致。预览包通过 v2 签名和 16 KiB ZIP 对齐验证，五个原生库与此前的 3D 投屏包逐字节一致，DEX 中无诊断输入。

从 Monado 2D 启动器进入 3D 菜单，底部上滑停留后点按，未经修改的 `hello_xr` 进入 `FOCUSED` 且右镜实拍蓝色立方体。原样例 APK SHA-256 为 `29ae01ef6a4fea41d67816f7051698f2694ab08ced4c16c02bdd978e62975962`。Android 返回键使样例进入 `STOPPING` 并完成 `xrDestroyInstance`。结束后保留修正版预览包，Broker 选回稳定版 Monado CV10，最低刷新率为 `null`。手持手机转动和左镜光学实拍仍待复测。[普通预览包右镜实拍](evidence/fold7-laser-preview-hello-right.jpg)

## 与 Android 手势的边界

在相同接线下使用 Fold7 外屏横屏（2520×1080），开启 Android 全面屏手势。手机控制页的两侧和底部以蓝线提示起点，蓝线随 Android 返回、Home 和安全边距调整。底部两角优先按左右返回识别。台架通过 ADB 注入触控，手机和摄像头位置固定。

| 预览包 | 构建源码 | SHA-256 |
| --- | --- | --- |
| Pocket XR 兼容与离线启动器整合包 | `9fdb45e` | `837f8421bb204957e21244b9136fcaec98846af0b1e50c918bf850263231cda8` |
| 标准 OpenXR 离线启动器包 | `722183a` | `e85132fd9df7d66050ba6a8830a959c30526ac16e90e4cb5badadd6937beea18` |

两只 APK 都以同一调试证书签名，五只 ARM64 原生库的 ZIP 位置及 15 个 ELF LOAD 段均满足 16 KiB 对齐。

- 整合包从左蓝线向内滑、从右下角沿右蓝线向内滑及从屏幕最左沿使用系统返回，三次均由 3D 菜单返回 2D 应用列表。
- 标准包从底部蓝线上划 120 ms 随即松手，没有产生置中事件。上划后停留 460 ms 产生一次置中，随后点按选中原版 `hello_xr`。样例进入 `FOCUSED`，CV10 右镜拍到蓝色立方体。样例返回后进入 `STOPPING` 并完成 `xrDestroyInstance`。
- 标准包再次打开 3D 菜单。从屏幕最下沿上滑，第一次显示系统导航条，第二次回到三星桌面。这两次滑动没有产生置中事件。结束后 Broker 恢复稳定版 Monado CV10，最低刷新率为 `null`。

[手机手势标记截图](evidence/fold7-laser-gesture-guides.png) · [选中样例右镜实拍](evidence/fold7-laser-gesture-hello-right.jpg) · [置中、样例退出与系统 Home 日志](evidence/fold7-laser-gesture-boundaries.log)
