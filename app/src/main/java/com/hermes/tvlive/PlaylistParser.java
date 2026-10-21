package com.hermes.tvlive;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析直播源。同时支持 M3U/M3U8 和国内常见的 TXT 格式。
 *
 * M3U:
 *   #EXTM3U
 *   #EXTINF:-1 tvg-logo="..." group-title="央视",CCTV-1
 *   http://example.com/cctv1.m3u8
 *
 * TXT（两种写法都吃）:
 *   央视,#genre#
 *   CCTV-1,http://example.com/cctv1.m3u8
 *   湖南卫视|http://example.com/hunan.m3u8
 *   http://example.com/noname.m3u8
 */
public final class PlaylistParser {

    private static final Pattern ATTR = Pattern.compile("([\\w-]+)=\"([^\"]*)\"");

    private PlaylistParser() {}

    public static List<Channel> parse(String text) {
        List<Channel> out = new ArrayList<>();
        if (text == null) return out;
        // 去 BOM、统一换行
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");

        boolean isM3u = text.contains("#EXTM3U") || text.contains("#EXTINF");
        if (isM3u) parseM3u(lines, out);
        else parseTxt(lines, out);
        return out;
    }

    private static void parseM3u(String[] lines, List<Channel> out) {
        String pendingName = null, pendingGroup = null, pendingLogo = null;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXTINF")) {
                int comma = line.lastIndexOf(',');
                pendingName = comma >= 0 ? cleanName(line.substring(comma + 1).trim()) : "未命名";
                pendingGroup = null;
                pendingLogo = null;
                Matcher m = ATTR.matcher(line);
                while (m.find()) {
                    String k = m.group(1).toLowerCase();
                    String v = m.group(2);
                    if (k.equals("group-title")) pendingGroup = v;
                    else if (k.equals("tvg-logo")) pendingLogo = v;
                }
            } else if (line.startsWith("#")) {
                continue;
            } else if (isUrl(line)) {
                String name = pendingName != null ? pendingName : guessName(line);
                out.add(new Channel(name, line, pendingGroup, pendingLogo));
                pendingName = null;
                pendingGroup = null;
                pendingLogo = null;
            }
        }
    }

    private static void parseTxt(String[] lines, List<Channel> out) {
        String group = "未分组";
        int auto = 1;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") && !line.endsWith("#genre#")) continue;

            // 分组标记：xxx,#genre#
            if (line.endsWith("#genre#")) {
                String g = line.substring(0, line.length() - 7).trim();
                if (g.endsWith(",")) g = g.substring(0, g.length() - 1).trim();
                if (!g.isEmpty()) group = g;
                continue;
            }

            String name = null, url = null;
            int idx = line.indexOf(",http");
            if (idx > 0) {
                name = line.substring(0, idx).trim();
                url = line.substring(idx + 1).trim();
            } else if (line.contains("|http")) {
                int p = line.indexOf("|http");
                name = line.substring(0, p).trim();
                url = line.substring(p + 1).trim();
            } else if (isUrl(line)) {
                url = line;
                name = "频道 " + auto;
            } else if (line.contains(",")) {
                // 兜底：名称,地址（地址可能不是 http 开头，如 rtmp）
                int p = line.indexOf(',');
                name = line.substring(0, p).trim();
                url = line.substring(p + 1).trim();
                if (!isUrl(url)) continue;
            } else {
                continue;
            }
            if (url == null || !isUrl(url)) continue;
            if (name == null || name.isEmpty()) name = guessName(url);
            out.add(new Channel(cleanName(name), url, group, null));
            auto++;
        }
    }

    /** 去掉国内源常见的「 - 单播」「- 组播」尾巴，让卡片上的台名干净些。 */
    static String cleanName(String n) {
        if (n == null) return "";
        String s = n.replaceAll("\\s*[-–—]\\s*(单播|组播|备用|测试|IPV6|ipv6)\\s*$", "");
        s = s.replaceAll("\\s{2,}", " ").trim();
        return s.isEmpty() ? n.trim() : s;
    }

    /**
     * EPG 匹配用的频道 key：CCTV-1高清 - 单播 -> CCTV1，SCTV-2高清 -> SCTV2，
     * 湖南卫视高清 -> 湖南卫视。和 Python 版构建脚本保持同一套规则。
     */
    public static String normName(String raw) {
        if (raw == null) return "";
        String n = raw.trim();
        n = n.replaceAll("^[★☆◆●■▲·\\s]+", "");
        n = n.replaceAll("(?i)\\s*[-–—]\\s*(单播|组播|备用|测试|移动|电信|联通|ipv6)\\s*$", "");
        n = n.replaceAll("(?i)[（(](50P|25P|未标注|备用|高清|超清|标清|流畅|\\d{3,4}[pi]|[pi])[）)]", "");
        n = n.replaceAll("\\s+", "");

        // 独立的 CCTV-4K 超高清频道
        if (n.matches("(?i)^cctv[-_ ]?4k$")) return "CCTV4K";

        n = n.replaceAll("(?i)4k", "·4K");
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("(?i)^cctv[-_ ]?(\\d+)(\\+|plus)?(·4K)?(.*)$").matcher(n);
        if (m.find()) {
            String num = m.group(1);
            String plus = m.group(2);
            String fourK = m.group(3);
            if (fourK != null) return "CCTV" + num;
            return "CCTV" + num + (plus != null ? "+" : "");
        }
        n = n.replace("·", "");
        n = n.replaceAll("(?i)^cgtn[-_ ]?", "CGTN");
        n = n.replaceAll("(?i)(高清|超清|蓝光|标清|流畅|HD|SD|FHD)$", "");
        n = n.replaceAll("^([A-Za-z]+)-(\\d+)$", "$1$2");
        return n;
    }

    static boolean isUrl(String s) {
        if (s == null) return false;
        String l = s.toLowerCase();
        return l.startsWith("http://") || l.startsWith("https://")
                || l.startsWith("rtmp://") || l.startsWith("rtsp://")
                || l.startsWith("udp://") || l.startsWith("rtp://");
    }

    private static String guessName(String url) {
        try {
            String path = url.substring(url.lastIndexOf('/') + 1);
            int dot = path.indexOf('.');
            if (dot > 0) path = path.substring(0, dot);
            return path.isEmpty() ? url : path;
        } catch (Exception e) {
            return url;
        }
    }
}
