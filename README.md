# TvLive · 电视直播

一个轻量的 Android TV 直播播放器。交互照酷9 那套来：**打开就是画面，频道列表按 OK 键浮出来**。

## 操作

| 想干什么 | 遥控器 |
|---|---|
| 换台 | 面板关着时直接 **↑ / ↓** |
| 调出频道列表 | **OK / Enter** |
| 面板里选台 | **↑↓** 移动，**OK** 切台并收起 |
| 换分组 | 面板里 **← / →** |
| 直接跳台 | **数字键**输入频道号（如 05） |
| 设置 / 换源 | **菜单键** |
| 关闭面板 | **返回** |

面板是左侧半透明浮层，右边还透出正在播的画面，换台不中断观看。

## 功能

- 支持 **M3U / M3U8** 和国内常见的 **TXT** 直播源
- 多源管理：添加 / 编辑 / 删除 / 切换，拉取失败自动回退本地缓存
- **内置 EPG**：默认 `epg.gotonas.com`，列表里直接显示每个频道「正在播什么」
- 记住上次看的频道，下次打开直接续播
- 音频兼容：平台硬解优先，MP2 / AC-3 等自动回落 FFmpeg 软解

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

## 音频兼容

国内 IPTV 源大量使用 **MP2（MPEG-1 Layer II）** 和 AC-3 音频，Media3/ExoPlayer 原生不带这些解码器
—— 表现就是「有画面、没声音」。实测某运营商源 111 个频道里 MP2 占 49.5%。

项目集成 [NextLib](https://github.com/anilbeesetti/nextlib) 的 FFmpeg 软解扩展：

```java
RenderersFactory rf = new NextRenderersFactory(context)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
player = new ExoPlayer.Builder(context, rf).build();
```

覆盖 MP2 / MP3 / AC-3 / E-AC-3 / DTS / TrueHD / FLAC / Opus。只保留 `armeabi-v7a` + `arm64-v8a`，
APK 约 10MB。

## EPG

默认地址 `https://epg.gotonas.com/t.xml.gz`（XMLTV，130+ 频道，含 CCTV / 卫视 / 四川地方 / CDTV / SCTV）。
设置里可以改地址或立即刷新，缓存 6 小时，失败自动换备用源。

频道匹配用归一化 key：`CCTV-1高清 - 单播` → `CCTV1`，`SCTV-2高清` → `SCTV2`。

## 构建

### GitHub Actions（推荐）

推到 `main` 或打 tag 自动编译：

```bash
git tag v1.2
git push origin v1.2
```

产物在 Actions 的 Artifacts；打 tag 时会自动创建 Release 并附上 APK。

### 本地

需要 JDK 17+ 和 Android SDK（platform 35 / build-tools 35）。

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleRelease
```

签名用仓库内的 `keystore/release.jks`（密码见 `app/build.gradle`），本地和 CI 出包签名一致，
升级不用卸载重装。

## 安装

```bash
adb connect <电视IP>:5555
adb install -r app-release.apk
```

运营商定制固件可能拦截侧载，需要带 installer 参数：

```bash
adb install -r -d -i com.android.vending app-release.apk
```

## 已知问题

- 部分源站的 master playlist 里 `CODECS` 声明错误（标 AAC 实际是 MP2），首次起播会多等一次解码器回落。
- 极少数频道流内完全没有音频 PID（运营商侧问题），任何播放器都不会有声音。

## 许可

MIT
