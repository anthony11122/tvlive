package com.hermes.tvlive;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.GZIPInputStream;

/**
 * EPG（XMLTV）加载与查询。
 *
 * - 首次启动异步下载，解析后缓存到私有目录，默认 6 小时刷新
 * - 只保留 [now-3h, now+24h] 的节目，避免几万条节目把内存撑爆
 * - 频道匹配用 PlaylistParser 的归一化 key（CCTV-1高清 -> CCTV1）
 */
public class EpgManager {

    public static final String DEFAULT_EPG = "https://epg.gotonas.com/t.xml.gz";
    /** 备用源：第一个失败时自动尝试 */
    public static final String[] FALLBACK_EPG = {
            "https://epg.zsdc.eu.org/t.xml.gz"
    };

    private static final long CACHE_TTL_MS = 6 * 3600 * 1000L;

    public static class Programme {
        public long start;
        public long stop;
        public String title;
        public String desc;
    }

    private static EpgManager instance;

    public static synchronized EpgManager get(Context ctx) {
        if (instance == null) instance = new EpgManager(ctx.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final SharedPreferences sp;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** key -> 按开始时间排序的节目表 */
    private final Map<String, List<Programme>> byChannel = new HashMap<>();
    private volatile boolean loading = false;
    private volatile boolean ready = false;
    private long lastLoadMs = 0;

    private EpgManager(Context ctx) {
        this.ctx = ctx;
        this.sp = ctx.getSharedPreferences("tvlive_epg", Context.MODE_PRIVATE);
    }

    public boolean isReady() { return ready; }

    public String getUrl() {
        return sp.getString("url", DEFAULT_EPG);
    }

    public void setUrl(String url) {
        sp.edit().putString("url", url).apply();
        byChannel.clear();
        ready = false;
    }

    public interface Callback { void onLoaded(boolean ok); }

    /** 启动时调用：缓存新鲜就直接用，否则后台刷新。 */
    public void loadIfNeeded(final Callback cb) {
        if (ready) { if (cb != null) cb.onLoaded(true); return; }
        if (loading) return;
        loading = true;

        new Thread(() -> {
            boolean ok = false;
            File cache = new File(ctx.getFilesDir(), "epg.xml.gz");
            long age = System.currentTimeMillis() - sp.getLong("fetched", 0);

            if (cache.exists() && age < CACHE_TTL_MS) {
                ok = parseFile(cache);
            }
            if (!ok) {
                String[] urls = new String[]{getUrl(), FALLBACK_EPG[0]};
                for (String u : urls) {
                    if (u == null || u.isEmpty()) continue;
                    if (download(u, cache)) {
                        ok = parseFile(cache);
                        if (ok) { sp.edit().putLong("fetched", System.currentTimeMillis()).apply(); break; }
                    }
                }
            }
            loading = false;
            ready = ok;
            lastLoadMs = System.currentTimeMillis();
            if (cb != null) {
                final boolean r = ok;
                ui.post(() -> cb.onLoaded(r));
            }
        }).start();
    }

    public void forceRefresh(Callback cb) {
        sp.edit().putLong("fetched", 0).apply();
        ready = false;
        loadIfNeeded(cb);
    }

    // ---------- 查询 ----------

    public Programme current(String channelKey) {
        List<Programme> list = byChannel.get(PlaylistParser.normName(channelKey));
        if (list == null) return null;
        long now = System.currentTimeMillis();
        for (Programme p : list) {
            if (p.start <= now && now < p.stop) return p;
        }
        return null;
    }

    public Programme next(String channelKey) {
        List<Programme> list = byChannel.get(PlaylistParser.normName(channelKey));
        if (list == null) return null;
        long now = System.currentTimeMillis();
        for (Programme p : list) {
            if (p.start > now) return p;
        }
        return null;
    }

    /** 面板里显示用：当前节目名，没有就返回空串 */
    public String currentTitle(String channelKey) {
        Programme p = current(channelKey);
        return p == null || p.title == null ? "" : p.title;
    }

    // ---------- 下载 / 解析 ----------

    private boolean download(String url, File dest) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12)");
            if (conn.getResponseCode() != 200) return false;
            InputStream in = conn.getInputStream();
            File tmp = new File(dest.getAbsolutePath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            byte[] buf = new byte[16384];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
                if (total > 40L * 1024 * 1024) break;
            }
            out.close();
            in.close();
            if (total < 1024) { tmp.delete(); return false; }
            return tmp.renameTo(dest);
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private boolean parseFile(File f) {
        Map<String, List<Programme>> map = new HashMap<>();
        long now = System.currentTimeMillis();
        long from = now - 3 * 3600 * 1000L;
        long to = now + 24 * 3600 * 1000L;
        try {
            InputStream raw = new FileInputStream(f);
            InputStream in = new BufferedInputStream(
                    f.getName().endsWith(".gz") ? new GZIPInputStream(raw, 16384) : raw, 32768);
            XmlPullParser p = Xml.newPullParser();
            p.setInput(in, null);

            String curChannel = null;
            Programme cur = null;
            StringBuilder text = null;
            int event = p.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String tag = p.getName();
                    if ("programme".equals(tag)) {
                        String ch = p.getAttributeValue(null, "channel");
                        long s = parseTime(p.getAttributeValue(null, "start"));
                        long e = parseTime(p.getAttributeValue(null, "stop"));
                        if (ch != null && s > 0 && e > s && e > from && s < to) {
                            curChannel = ch;
                            cur = new Programme();
                            cur.start = s;
                            cur.stop = e;
                        } else {
                            curChannel = null;
                            cur = null;
                        }
                    } else if (cur != null && ("title".equals(tag) || "desc".equals(tag))) {
                        text = new StringBuilder();
                    }
                } else if (event == XmlPullParser.TEXT) {
                    if (text != null) text.append(p.getText());
                } else if (event == XmlPullParser.END_TAG) {
                    String tag = p.getName();
                    if (cur != null && "title".equals(tag) && text != null) {
                        cur.title = text.toString().trim();
                        text = null;
                    } else if (cur != null && "desc".equals(tag) && text != null) {
                        cur.desc = text.toString().trim();
                        text = null;
                    } else if ("programme".equals(tag) && cur != null) {
                        String key = PlaylistParser.normName(curChannel);
                        List<Programme> list = map.get(key);
                        if (list == null) { list = new ArrayList<>(); map.put(key, list); }
                        list.add(cur);
                        cur = null;
                        curChannel = null;
                    }
                }
                event = p.next();
            }
            in.close();
        } catch (Exception e) {
            return false;
        }
        if (map.isEmpty()) return false;
        for (List<Programme> l : map.values()) {
            Collections.sort(l, Comparator.comparingLong(a -> a.start));
        }
        synchronized (byChannel) {
            byChannel.clear();
            byChannel.putAll(map);
        }
        return true;
    }

    /** XMLTV 时间格式：20261022060000 +0800 */
    private long parseTime(String s) {
        if (s == null || s.length() < 14) return 0;
        try {
            String v = s.trim();
            String tz = "+0800";
            int sp2 = v.indexOf(' ');
            if (sp2 > 0) { tz = v.substring(sp2 + 1).replace(":", ""); v = v.substring(0, sp2); }
            SimpleDateFormat f = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("GMT" + tz));
            return f.parse(v).getTime();
        } catch (Exception e) {
            return 0;
        }
    }
}
