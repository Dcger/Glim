# 随处发现

Android 锁屏卡片原型。打开应用即可浏览内置的观鸟、植物和音乐卡片。可以选择资料链接或试听合成音，然后把当前卡片设为锁屏壁纸。

在「主题与资料」里可开关、新建主题，记录本地背景，登记书籍或视频等资料。添加卡片时可关联主题内的一份资料，并选填小任务、图片和音频。壁纸用上方图片与下方渐变文字排版。无图时显示内置背景，声音在应用内点播。

卡片和配置存储在设备上。此版通过手动录入资料与卡片验证手机端流程，Agent 寻找资料、读取原文生成卡片和跨设备同步留待后续接入。

## 构建

用 Android Studio 打开本目录并使用 JDK 17 或兼容版本、Android SDK Platform 35 和 Build Tools 35.0.0。使用 Gradle 8.11.1 或兼容版本同步。可用本地 Gradle 执行：

```sh
gradle :app:assembleDebug
```

调试包生成在 `app/build/outputs/apk/debug/app-debug.apk`。应用最低支持 Android 7.0（API 24）。

## 验证

运行 `bash tools/check-core.sh` 可用 JDK 检查主题过滤、预览遍历、轮换与时间记录。此检查独立于 Android SDK。

在 Android Studio 模拟器中启动应用，滚动到「下一张」并连续点击，检查页面是否留在当前滚动区域。操作主题开关，添加一张带本机图片的卡片并试听内置音频。关闭并重开应用确认新内容仍在。选择「设为锁屏」后，用模拟器锁屏确认图片和底部文字的位置，并检查桌面壁纸。

### 锁屏换图测试

在「发现卡片」下方打开「每 10 秒换锁屏（测试）」，锁屏并等 20–30 秒，再点亮屏幕查看。测试使用前台服务，会在两分钟后自动结束。也可以关闭开关，或从通知选择「结束测试」。如允许应用通知，系统会在通知栏显示测试状态。测试期间短暂保持 CPU 可运行，屏幕可以熄灭。结束后按原设置恢复常规轮换。应用内「上次应用」精确到秒。

```sh
adb logcat -v time -s DiscoveryWallpaper
```

日志中的 `TEST applied`、`SCHEDULED applied` 和 `MANUAL applied` 分别对应测试、常规自动轮换和手动换图。若要检查 Android 记录的近期进程退出原因（Android 11 及以上），可用 `adb shell dumpsys activity exit-info dev.discovery.wallpaper`。

关闭测试后，开启「自动更换锁屏」，可以用 ADB 独立触发已排程的任务：

```sh
adb shell cmd jobscheduler run -f dev.discovery.wallpaper 2301
```

在应用中查看「上次应用」的时间和标题。调试命令用于触发轮换路径，实际定时行为以系统调度为准。

真机上可以单独安装调试 APK。先手动设置一张，再开启自动更换并将间隔设为 15 或 30 分钟，锁屏观察实际更新和文字位置。首次真机试用无需 USB，ADB 可辅助诊断。自动更换默认关闭，开启后默认间隔为 30 分钟。系统省电时可能推迟换图。

Android 接口参考见下方链接。

- [WallpaperManager](https://developer.android.com/reference/android/app/WallpaperManager)
- [JobInfo.Builder](https://developer.android.com/reference/android/app/job/JobInfo.Builder)
- [Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)
