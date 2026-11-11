# KanPu（看谱）

安卓本地 PDF 琴谱阅读器，支持铺满屏幕的单页／双页看谱、带动画的点击和滑动翻页、逐页定时自动翻页、手动指法标注、批量或递归导入 PDF、琴谱单网格／列表分类、自定义琴谱与琴谱单封面，以及界面明暗主题和谱面反色。Android 8.0（API 26）及以上可安装。

使用 Android SDK 36、JDK 17+ 构建：

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

产物在 `app/build/outputs/apk/debug/app-debug.apk`。本地 SDK 路径可写入未纳入版本控制的 `local.properties`，例如 `sdk.dir=/path/to/Android/Sdk`；也可设置 `ANDROID_HOME`。应用不需要账号或网络权限，首次打开后从“导入”选择单个 PDF、多个 PDF 或文件夹。

