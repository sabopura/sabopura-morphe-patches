package io.github.sabopura.morphe.extension.reddit;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Runtime bridge kept independent of Reddit and Kotlin classes. */
public final class AutoTranslate {
    private static final long MIN_REQUEST_SPACING_MS = 75L;
    private static final long RETRY_WINDOW_MS = 15_000L;
    private static final long PRUNE_AGE_MS = 300_000L;
    private static final int MAX_TRANSLATED_KEYS = 4096;
    private static final String POST_ID_LABEL = "linkIdWithKind=";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, Long> RECENT = new ConcurrentHashMap<>();
    private static final AtomicInteger CALL_COUNT = new AtomicInteger();
    private static long nextDispatchAtMs;

    /**
     * Items that Reddit has already displayed as translated. When such an item becomes
     * translatable again, the user (or Reddit) restored the original, so it is left alone.
     */
    private static final Map<String, Boolean> SHOWN_TRANSLATED =
            new LinkedHashMap<String, Boolean>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_TRANSLATED_KEYS;
                }
            };

    // Post-detail metadata seen just before its translation indicator is composed (main thread).
    private static Object pendingPostDetail;
    private static Object lastPostDetail;
    private static String lastPostDetailId;

    private AutoTranslate() {
    }

    public static void onPost(Object callback, String id, Object state) {
        onStatefulCallback("post:", callback, id, state);
    }

    public static void onComment(Object callback, String id, Object state) {
        onStatefulCallback("comment:", callback, id, state);
    }

    public static void onAccessibilityComment(Object callback, String id) {
        enqueue("comment:", callback, id);
    }

    /** Called with the post-detail metadata model right after its indicator state is read. */
    public static void notePostDetail(Object metadata) {
        pendingPostDetail = metadata;
    }

    /** Called right after the post-detail translation indicator has been composed. */
    public static void onPostDetail(Object callback, Object state) {
        Object metadata = pendingPostDetail;
        pendingPostDetail = null;
        onStatefulCallback("detail:", callback, postDetailId(metadata), state);
    }

    private static String postDetailId(Object metadata) {
        if (metadata == null) {
            return null;
        }
        if (metadata == lastPostDetail) {
            return lastPostDetailId;
        }

        String id = null;
        try {
            // PostUnitMetadata(..., linkIdWithKind=t3_xxx, postId=...) is a stable data-class
            // anchor; its field names are obfuscated.
            String text = metadata.toString();
            int start = text.indexOf(POST_ID_LABEL);
            if (start >= 0) {
                start += POST_ID_LABEL.length();
                int end = text.indexOf(", ", start);
                id = end < 0 ? text.substring(start) : text.substring(start, end);
                if (id.isEmpty() || "null".equals(id)) {
                    id = null;
                }
            }
        } catch (RuntimeException ignored) {
            // Leave the post untouched rather than crashing Reddit.
        }
        lastPostDetail = metadata;
        lastPostDetailId = id;
        return id;
    }

    private static void onStatefulCallback(
            String kind, Object callback, String id, Object state) {
        if (id == null || id.isEmpty()) {
            return;
        }
        String key = kind + id;
        String name = state instanceof Enum ? ((Enum<?>) state).name() : null;
        if ("ClickableRevert".equals(name) || "Translated".equals(name)) {
            synchronized (SHOWN_TRANSLATED) {
                SHOWN_TRANSLATED.put(key, Boolean.TRUE);
            }
            RECENT.remove(key);
            return;
        }
        if (!"ClickableTranslate".equals(name)) {
            RECENT.remove(key);
            return;
        }
        enqueue(kind, callback, id);
    }

    private static void enqueue(String kind, Object callback, String id) {
        if (callback == null || id == null || id.isEmpty()) {
            return;
        }

        long now = SystemClock.uptimeMillis();
        String key = kind + id;
        synchronized (SHOWN_TRANSLATED) {
            if (SHOWN_TRANSLATED.containsKey(key)) {
                // The original was restored after a translation; respect "show original".
                return;
            }
        }
        synchronized (RECENT) {
            Long previous = RECENT.get(key);
            if (previous != null && now - previous < RETRY_WINDOW_MS) {
                return;
            }
            RECENT.put(key, now);
        }

        if ((CALL_COUNT.incrementAndGet() & 127) == 0) {
            synchronized (RECENT) {
                for (Map.Entry<String, Long> entry : RECENT.entrySet()) {
                    if (now - entry.getValue() > PRUNE_AGE_MS) {
                        RECENT.remove(entry.getKey());
                    }
                }
            }
        }

        long dispatchAt = reserveDispatchTime(now);
        MAIN.postAtTime(() -> invoke(callback, key), dispatchAt);
    }

    private static synchronized long reserveDispatchTime(long now) {
        long dispatchAt = Math.max(now, nextDispatchAtMs);
        nextDispatchAtMs = dispatchAt + MIN_REQUEST_SPACING_MS;
        return dispatchAt;
    }

    private static void invoke(Object callback, String key) {
        synchronized (SHOWN_TRANSLATED) {
            if (SHOWN_TRANSLATED.containsKey(key)) {
                // Already translated (for example by another surface) before this ran.
                return;
            }
        }
        try {
            Method invoke = callback.getClass().getMethod("invoke");
            invoke.invoke(callback);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Allow a later recomposition to retry instead of crashing Reddit.
            RECENT.remove(key);
        }
    }
}
