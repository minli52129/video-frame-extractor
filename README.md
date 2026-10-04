# 视频截图工具 (Video Frame Extractor)

运行在 Android 16 (API 36) 上的小工具:对一个视频文件批量截图。

## 功能

- **按时间间隔截图**:每隔 N 秒截取一帧(从 0 秒开始)
- **按数量平均分配截图**:指定想要的截图数量,自动把截图时间点均匀分布到整个视频时长上
- 截图以 JPEG(质量 92)保存到相册 `Pictures/VideoFrames/<视频名>/` 目录,可直接在系统相册查看
- 无需申请存储权限(使用系统文件选择器 + MediaStore)
- 单次最多截取 500 张,带进度显示

## 技术栈

- Kotlin + 原生 Views(单 Activity,无第三方框架)
- `MediaMetadataRetriever.getFrameAtTime()` 精确取帧(`OPTION_CLOSEST`)
- compileSdk / targetSdk 36,minSdk 29(Android 10+)
- AGP 8.13.0 / Gradle 8.13 / JDK 17

## 编译(GitHub Actions)

本项目通过 GitHub Actions 在线编译,无需本地 Android 环境:

1. 把本仓库 push 到 GitHub
2. push 后自动触发 **Build APK** 工作流(也可在 Actions 页面手动点 "Run workflow")
3. 构建完成后,在 Actions 运行记录页面底部的 **Artifacts** 下载 `video-frame-extractor-debug-apk`
4. 解压得到 `app-debug.apk`,安装到手机即可(debug 签名,可直接安装)

工作流文件:`.github/workflows/build.yml`

## 本地编译(可选)

如果本地有 Android 环境(SDK Platform 36 + JDK 17 + Gradle 8.13):

```bash
gradle assembleDebug
# 输出: app/build/outputs/apk/debug/app-debug.apk
```
