package io.github.sabopura.morphe.extension.youtube.danmaku;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.util.LruCache;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Memory-only, host-restricted loader for inline YouTube emoji images. */
final class InlineImageCache {
    private static final String TAG = "DanmakuComments";
    private static final int MAX_DOWNLOAD_BYTES = 1024 * 1024;
    private static final int CACHE_BYTES = 6 * 1024 * 1024;
    private static final InlineImageCache INSTANCE = new InlineImageCache();

    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(CACHE_BYTES) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return value == null ? 0 : value.getAllocationByteCount();
        }
    };
    private final Set<String> loading = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> failed = Collections.synchronizedSet(new HashSet<>());
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "Danmaku-Emoji");
        thread.setDaemon(true);
        return thread;
    });

    private InlineImageCache() {
    }

    static InlineImageCache getInstance() {
        return INSTANCE;
    }

    Bitmap get(String imageUrl, Runnable onReady) {
        if (!isAllowed(imageUrl)) return null;
        Bitmap bitmap = cache.get(imageUrl);
        if (bitmap != null && !bitmap.isRecycled()) return bitmap;
        if (failed.contains(imageUrl) || !loading.add(imageUrl)) return null;
        executor.execute(() -> {
            try {
                Bitmap downloaded = download(imageUrl);
                if (downloaded != null) {
                    cache.put(imageUrl, downloaded);
                    if (onReady != null) onReady.run();
                } else {
                    failed.add(imageUrl);
                }
            } catch (Throwable throwable) {
                failed.add(imageUrl);
                Log.d(TAG, "Inline emoji unavailable: "
                        + throwable.getClass().getSimpleName());
            } finally {
                loading.remove(imageUrl);
            }
        });
        return null;
    }

    private static Bitmap download(String imageUrl) throws Exception {
        return download(imageUrl, 0);
    }

    private static Bitmap download(String imageUrl, int redirects) throws Exception {
        if (redirects > 3) throw new IOException("Too many emoji redirects");
        HttpURLConnection connection = (HttpURLConnection) new URL(imageUrl).openConnection();
        connection.setConnectTimeout(8_000);
        connection.setReadTimeout(12_000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", "com.google.android.youtube/21.04.223");
        try {
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                if (!isAllowed(location)) throw new IOException("Rejected emoji redirect");
                connection.disconnect();
                return download(location, redirects + 1);
            }
            if (status < 200 || status >= 300) throw new IOException("HTTP " + status);
            int declaredLength = connection.getContentLength();
            if (declaredLength > MAX_DOWNLOAD_BYTES) throw new IOException("Image too large");
            byte[] bytes;
            try (InputStream input = new BufferedInputStream(connection.getInputStream())) {
                bytes = readBounded(input);
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                    || bounds.outWidth > 512 || bounds.outHeight > 512) {
                throw new IOException("Invalid emoji dimensions");
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(16 * 1024);
        byte[] buffer = new byte[8 * 1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > MAX_DOWNLOAD_BYTES) throw new IOException("Image too large");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    static boolean isAllowed(String imageUrl) {
        if (imageUrl == null || imageUrl.isEmpty()) return false;
        try {
            URI uri = URI.create(imageUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("ggpht.com") || host.endsWith(".ggpht.com")
                    || host.equals("ytimg.com") || host.endsWith(".ytimg.com")
                    || host.equals("youtube.com") || host.endsWith(".youtube.com");
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
