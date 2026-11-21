# 随处发现

Android 锁屏卡片原型。打开应用即可浏览内置的观鸟、植物和音乐卡片。可以选择资料链接或试听合成音，然后把当前卡片设为锁屏壁纸。

在「主题与资料」里可开关、新建主题，记录本地背景，登记书籍或视频等资料。添加卡片时可关联主题内的多份资料，并选填小任务、图片和音频。壁纸用上方图片与下方渐变文字排版。无图时显示内置背景，声音在应用内点播。

卡片和配置存储在设备上。可以手动添加卡片，也可以把 Agent 根据你提供的资料生成的卡片批量导入。

## 批量导入卡片

在「主题与资料 → 资料与备注」顶部点「添加资料」，可添加单份资料，也可在系统文件选择器里勾选多份 PDF 一次导入。同一主题内已登记的文件和内容相同的 PDF 会跳过，导入结果会显示新增、重复和失败的数量。资料名默认填为文件名，可在编辑时修改。

「删除资料」支持多选，单份资料的编辑页也有删除入口。删除资料会解除卡片上的资料关联。可在「资料与备注」底部删除整个主题及其中的卡片和资料记录。设备上的原文件会保留。

打开主题的「添加卡片」，选择「手动添加卡片」或「批量导入卡片」。批量导入页的「备注说明」可勾选附上主题备注，也可另写本次说明。选择资料后点「发给 Agent 生成卡片」，可选择已安装的常见 AI App，或通过「其他应用」打开 Android 分享面板。提示包含选中的资料和备注，本地 PDF 会随分享附送。可在列表里点「复制提示」，或长按「发给 Agent 生成卡片」单独复制提示。

用「导入 ZIP / JSON 文件」或「粘贴卡片 JSON」导入已有内容。审阅后取消勾选不想保存的卡片，再点「导入选中卡片」。入库卡片关联本批选中的资料，主题开启时会加入锁屏轮换。

```json
{
  "cards": [
    {
      "title": "逆光里看叶脉",
      "body": "叶片透光时，叶脉和薄的叶肉形成明显对比，分叉更容易观察。",
      "task": "举起一片落叶，对着明亮的天空看它的叶脉。"
    }
  ]
}
```

每批最多 30 张。标题、正文为必填，小任务可省略。标题、正文和小任务的长度上限分别为 42、155 和 70 个字符。同一主题内标题和正文都相同的卡片只会入库一次。

### 导入带图片和音频的卡片

在「批量导入卡片」里点「导入 ZIP / JSON 文件」。把 UTF-8 编码的 `cards.json` 放在 ZIP 根目录。每张卡片可以通过 `image` 和 `audio` 指向包里的文件，路径区分大小写。两个字段都可以省略。

```json
{
  "cards": [
    {
      "title": "听两种音色",
      "body": "两个音的音高相同，听起来仍然可能不同。",
      "task": "听一遍录音，试着描述第二个音的质感。",
      "image": "media/timbre.png",
      "audio": "media/timbre.wav"
    }
  ]
}
```

从包含 `cards.json` 和 `media/` 的目录打包，避免多一层文件夹。

```sh
zip discovery-cards.zip cards.json media/timbre.png media/timbre.wav
```

图片支持 JPEG、PNG、WebP，单张最大 12 MiB。音频支持 MP3、M4A、WAV，单条最大 24 MiB。ZIP 以及解包后的媒体总量各自最大 64 MiB。审阅时可看图、试听和取消勾选。确认后，App 会保存选中卡片引用的媒体，原 ZIP 移动或删除后仍可使用。图片用于锁屏，音频在 App 中点播。

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
