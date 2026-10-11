package io.github.sabopura.morphe.extension.twitch.danmaku;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Memory-only loader for Twitch emote images from Twitch's own emote CDN. */
final class EmoteCache {
    private static final String TAG = "TwitchDanmaku";
    private static final int MAX_DOWNLOAD_BYTES = 512 * 1024;
    private static final int CACHE_BYTES = 8 * 1024 * 1024;
    private static final EmoteCache INSTANCE = new EmoteCache();

    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(CACHE_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value == null ? 0 : value.getAllocationByteCount();
        }
    };
    private final Set<String> loading = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> failed = Collections.synchronizedSet(new HashSet<>());
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "TwitchDanmaku-Emote");
        thread.setDaemon(true);
        return thread;
    });

    private EmoteCache() {
    }

    static EmoteCache getInstance() {
        return INSTANCE;
    }

    /** Emote ids are digits or "emotesv2_" hex; anything else is never requested. */
    static boolean isValidId(String id) {
        if (id == null || id.isEmpty() || id.length() > 64) return false;
        for (int index = 0; index < id.length(); index++) {
            char value = id.charAt(index);
            if (!(value >= '0' && value <= '9') && !(value >= 'a' && value <= 'z')
                    && !(value >= 'A' && value <= 'Z') && value != '_') {
                return false;
            }
        }
        return true;
    }

    static String urlFor(String id) {
        return "https://static-cdn.jtvnw.net/emoticons/v2/" + id + "/default/dark/2.0";
    }

    /** Returns the cached bitmap, or null and starts loading it ({@code onReady} on success). */
    Bitmap get(String id, Runnable onReady) {
        if (!isValidId(id)) return null;
        Bitmap bitmap = cache.get(id);
        if (bitmap != null && !bitmap.isRecycled()) return bitmap;
        if (failed.contains(id) || !loading.add(id)) return null;
        executor.execute(() -> {
            try {
                Bitmap downloaded = download(urlFor(id));
                if (downloaded != null) {
                    cache.put(id, downloaded);
                    if (onReady != null) onReady.run();
                } else {
                    failed.add(id);
                }
            } catch (Throwable throwable) {
                failed.add(id);
                Log.d(TAG, "Emote unavailable: " + throwable.getClass().getSimpleName());
            } finally {
                loading.remove(id);
            }
        });
        return null;
    }

    private static Bitmap download(String imageUrl) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(imageUrl).openConnection();
        connection.setConnectTimeout(8_000);
        connection.setReadTimeout(12_000);
        connection.setInstanceFollowRedirects(false);
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("HTTP " + status);
            if (connection.getContentLength() > MAX_DOWNLOAD_BYTES) {
                throw new IOException("Emote too large");
            }
            byte[] bytes;
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream(16 * 1024);
                byte[] buffer = new byte[8 * 1024];
                int total = 0;
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    total += read;
                    if (total > MAX_DOWNLOAD_BYTES) throw new IOException("Emote too large");
                    output.write(buffer, 0, read);
                }
                bytes = output.toByteArray();
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                    || bounds.outWidth > 512 || bounds.outHeight > 512) {
                throw new IOException("Invalid emote dimensions");
            }
            // Animated emotes are GIFs; the first frame is used.
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } finally {
            connection.disconnect();
        }
    }
}
