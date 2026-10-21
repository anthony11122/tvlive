package com.hermes.tvlive;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 极简台标加载器：内存 LRU + 4 线程池，失败静默（卡片只显示台名）。 */
final class ImageLoader {

    private static final ImageLoader INSTANCE = new ImageLoader();

    static ImageLoader get() { return INSTANCE; }

    private final LruCache<String, Bitmap> cache;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Handler ui = new Handler(Looper.getMainLooper());

    private ImageLoader() {
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
        cache = new LruCache<String, Bitmap>(maxKb) {
            @Override protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    void load(final String url, final ImageView view) {
        if (url == null || url.isEmpty()) { view.setImageDrawable(null); return; }
        view.setTag(url);
        Bitmap hit = cache.get(url);
        if (hit != null) { view.setImageBitmap(hit); return; }
        view.setImageDrawable(null);

        pool.execute(() -> {
            Bitmap bmp = null;
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(8000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12)");
                InputStream in = conn.getInputStream();
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 2;
                bmp = BitmapFactory.decodeStream(in, null, o);
                in.close();
            } catch (Exception ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
            final Bitmap result = bmp;
            if (result == null) return;
            cache.put(url, result);
            ui.post(() -> {
                if (url.equals(view.getTag())) view.setImageBitmap(result);
            });
        });
    }
}
