# TvLive · 电视直播

一个轻量的 Android TV 直播播放器。自己添加直播源，遥控器操作，无广告无账号。

## 功能

- 支持 **M3U / M3U8** 和国内常见的 **TXT** 直播源
- 多源管理：添加 / 编辑 / 删除 / 切换，拉取失败自动回退到本地缓存
- 本地文件导入（m3u / txt）
- 频道按分组浏览，台标自动加载
- 遥控器全操作：方向键选台、确定播放、返回退出
- 播放页上下/左右键换台，出错可一键重试

## 支持的源格式

**M3U / M3U8**

```
#EXTM3U
#EXTINF:-1 tvg-logo="http://x/1.png" group-title="央视",CCTV-1
http://example.com/cctv1.m3u8
```

**TXT**

```
央视,#genre#
CCTV-1,http://example.com/cctv1.m3u8
CCTV-2,http://example.com/cctv2.m3u8
湖南卫视,#genre#
湖南卫视|http://example.com/hunan.m3u8
```

也接受每行只有地址、或者 `名称|地址` 的写法。

## 构建

### GitHub Actions（推荐，不占本地环境）

推送到 `main` 或打 tag 都会自动编译：

```bash
git tag v1.0.0
git push origin v1.0.0
```

产物在 Actions 的 Artifacts 里；打 tag 时会自动创建 Release 并附上 APK。

### 本地

需要 JDK 17+ 和 Android SDK（platform 34 / build-tools 34.0.0）。

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

签名用的是仓库内的 `keystore/release.jks`（密码见 `app/build.gradle`），
保证本地和 CI 出的包签名一致，升级不需要卸载重装。

## 安装

```bash
adb connect <电视IP>:5555
adb install -r app-release.apk
```

部分运营商定制固件会拦截侧载，需要带 installer 参数：

```bash
adb install -r -d -i com.android.vending app-release.apk
```

## 音频兼容

国内 IPTV 源大量使用 **MP2（MPEG-1 Layer II）** 和 AC-3 音频，Android 的 Media3/ExoPlayer
原生不带这些解码器 —— 表现就是「有画面、没声音」。

本项目集成了 [NextLib](https://github.com/anilbeesetti/nextlib) 的 FFmpeg 软解扩展
（`nextlib-media3ext`），通过 `NextRenderersFactory` 在平台解码器不支持时自动回落到 FFmpeg：

```java
RenderersFactory rf = new NextRenderersFactory(context)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
player = new ExoPlayer.Builder(context, rf).build();
```

覆盖 MP2 / MP3 / AC-3 / E-AC-3 / DTS / TrueHD / FLAC / Opus 等。
代价是 APK 里多了约 5MB 的 FFmpeg 原生库（只保留 armeabi-v7a + arm64-v8a）。

## 已知问题

- 部分源站的 master playlist 里 `CODECS` 声明错误（标 AAC 实际是 MP2），
  会让平台解码器初始化失败后回落到 FFmpeg，首次起播略慢。
- 极少数频道流内完全没有音频 PID（运营商侧问题），任何播放器都不会有声音。

## 许可

MIT
