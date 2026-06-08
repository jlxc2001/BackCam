# BackCam AHD Probe

给第三方安卓车机测试 AHD / 倒车 / 前视视频输入的探针 Demo。

适配目标：Android 10、32 位处理器车机。源码不依赖 Kotlin，不依赖 NDK，GitHub Actions 可在线编译 APK。

## 这个 Demo 能做什么

- 安全扫描 Camera2 设备 ID
- 安全扫描 Camera1 设备数量
- 扫描 `/dev/video*` / V4L2 节点
- 尝试用 Camera2 打开 1920x1080 预览
- 尝试用 Camera1 打开 1920x1080 预览
- 查找车机自带的前视、后视、倒车、AVIN、AVM、Camera 类 App
- 逐个尝试启动候选原厂影像 App
- 复制日志，方便发给 ChatGPT 继续分析

## 重要说明

AHD_1080P_25 是模拟高清视频制式，普通 Android App 不能直接读取“模拟电信号 raw 画面”并用软件转换。必须先经过车机内置 AHD 解码芯片。

所以本 Demo 的目标是：尝试读取“车机硬件已经解码并暴露给 Android 的画面”。如果系统没有通过 Camera2、Camera1、/dev/video 暴露出来，就需要继续分析原厂影像 App 的私有接口或直接调用原厂 App。

## GitHub 在线编译

把本项目所有文件上传到 GitHub 仓库根目录，然后进入：

Actions → Android CI → Run workflow

编译成功后，在 Artifacts 下载：

BackCamAHDProbe-debug-apk

里面就是 APK。

## 车机上如何测试

1. 安装 APK。
2. 第一次打开允许相机权限。
3. 点“一键安全扫描”。
4. 点“Camera2打开”，看是否能出图。
5. 点“下一个ID”，再点“Camera2打开”，逐个测试。
6. 点“Camera1打开”，测试旧接口。
7. 点“查找原厂影像App”，然后点“打开候选App”，看能否打开车机自带前视/倒车影像。
8. 点“复制日志”，把日志发给 ChatGPT 分析。
