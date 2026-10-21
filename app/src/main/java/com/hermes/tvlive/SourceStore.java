package com.hermes.tvlive;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** 直播源的持久化与拉取。源列表存 SharedPreferences，拉到的内容做本地缓存以便断网时仍能看列表。 */
public class SourceStore {

    public static class Source {
        public String name;
        public String url;
        public Source(String name, String url) { this.name = name; this.url = url; }
    }

    private static final String PREF = "tvlive";
    private static final String K_SOURCES = "sources";
    private static final String K_CURRENT = "current";
    private static final String K_CACHE = "cache_";

    private final SharedPreferences sp;

    public SourceStore(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public List<Source> getSources() {
        List<Source> list = new ArrayList<>();
        String raw = sp.getString(K_SOURCES, null);
        if (raw != null) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    list.add(new Source(o.optString("name"), o.optString("url")));
                }
            } catch (Exception ignored) {}
        }
        if (list.isEmpty()) {
            // 首次启动给一条可直接用的内网源，用户可在源管理里改
            list.add(new Source("内网·四川联通单播", "http://10.23.5.4:8080/local/sc-unicom-iptv-epg.m3u"));
        }
        return list;
    }

    public void saveSources(List<Source> list) {
        JSONArray arr = new JSONArray();
        try {
            for (Source s : list) {
                JSONObject o = new JSONObject();
                o.put("name", s.name);
                o.put("url", s.url);
                arr.put(o);
            }
        } catch (Exception ignored) {}
        sp.edit().putString(K_SOURCES, arr.toString()).apply();
    }

    public int getCurrent() {
        return sp.getInt(K_CURRENT, 0);
    }

    public void setCurrent(int idx) {
        sp.edit().putInt(K_CURRENT, idx).apply();
    }

    public void addSource(String name, String url) {
        List<Source> list = getSources();
        list.add(new Source(name, url));
        saveSources(list);
    }

    public void updateSource(int idx, String name, String url) {
        List<Source> list = getSources();
        if (idx >= 0 && idx < list.size()) {
            list.set(idx, new Source(name, url));
            saveSources(list);
        }
    }

    public void removeSource(int idx) {
        List<Source> list = getSources();
        if (idx >= 0 && idx < list.size()) {
            list.remove(idx);
            saveSources(list);
            int cur = getCurrent();
            if (cur >= list.size()) setCurrent(Math.max(0, list.size() - 1));
        }
    }

    /** 拉取源内容；失败时回退到上次缓存。返回 null 表示两者都没有。 */
    public String fetch(int idx) {
        List<Source> list = getSources();
        if (idx < 0 || idx >= list.size()) return null;
        String url = list.get(idx).url;
        String body = null;
        try {
            body = httpGet(url);
        } catch (Exception e) {
            body = null;
        }
        if (body != null && !body.trim().isEmpty()) {
            sp.edit().putString(K_CACHE + idx, body).apply();
            return body;
        }
        return sp.getString(K_CACHE + idx, null);
    }

    public boolean hasCache(int idx) {
        return sp.getString(K_CACHE + idx, null) != null;
    }

    private String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(urlStr);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120.0 Safari/537.36");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
            InputStream in = conn.getInputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = br.read(buf)) > 0) {
                sb.append(buf, 0, n);
                if (sb.length() > 8 * 1024 * 1024) break; // 8MB 上限，防止拉到大文件
            }
            br.close();
            return sb.toString();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
