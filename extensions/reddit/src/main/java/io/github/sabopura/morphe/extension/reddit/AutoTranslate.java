package io.github.sabopura.morphe.extension.reddit;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Runtime bridge kept independent of Reddit and Kotlin classes. */
public final class AutoTranslate {
    private static final long MIN_REQUEST_SPACING_MS = 75L;
    private static final long RETRY_WINDOW_MS = 15_000L;
    private static final long PRUNE_AGE_MS = 300_000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, Long> RECENT = new ConcurrentHashMap<>();
    private static final AtomicInteger CALL_COUNT = new AtomicInteger();
    private static long nextDispatchAtMs;

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

    private static void onStatefulCallback(
            String kind, Object callback, String id, Object state) {
        if (!(state instanceof Enum)
                || !"ClickableTranslate".equals(((Enum<?>) state).name())) {
            if (id != null) {
                RECENT.remove(kind + id);
            }
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
        try {
            Method invoke = callback.getClass().getMethod("invoke");
            invoke.invoke(callback);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Allow a later recomposition to retry instead of crashing Reddit.
            RECENT.remove(key);
        }
    }
}
