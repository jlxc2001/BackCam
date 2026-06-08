# RearCameraProbeDemo

这个 Demo 用于验证第三方车机的 AHD/倒车影像输入是否被 Android 标准 Camera2 API 暴露。

## 使用方法

1. 用 Android Studio 打开本项目。
2. 等 Gradle Sync 完成。
3. 连接车机 ADB，点击 Run 安装运行。
4. 点击「扫描」，查看 Camera2 ID 和 /dev/video* 节点。
5. 依次点击不同 Camera ID 的「打开」或「下一个」。
6. 如果车机的视频输入被系统暴露为 Camera2 设备，就可以在左侧看到画面。

## ADB 辅助命令

```bash
adb logcat -s RearCameraProbe
adb shell dumpsys media.camera
adb shell ls -l /dev/video*
adb shell dumpsys window | grep -E "mCurrentFocus|mFocusedApp"
adb shell dumpsys activity top | grep ACTIVITY
```

## 重要判断

- 能扫到多个 Camera ID，且其中一个打开后显示 AHD 画面：说明可以从普通 App 获取预览。
- 扫不到对应 Camera ID，或者只有普通前后摄：说明 AHD/倒车输入大概率是厂商私有 AVIN/MCU 通道。
- 能看到 /dev/video* 但普通 App 无法读取：可能需要 native V4L2 + root/system 权限/SELinux 放行。
