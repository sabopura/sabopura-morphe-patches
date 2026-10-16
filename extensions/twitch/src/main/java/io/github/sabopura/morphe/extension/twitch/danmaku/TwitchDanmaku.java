package io.github.sabopura.morphe.extension.twitch.danmaku;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Entry point called from Twitch's live chat parser.
 *
 * <p>Every parsed live chat message is handed to {@link #onChatMessage(Object)}. History that
 * Twitch replays when joining a channel, re-parsed messages and duplicates are dropped; the
 * rest is forwarded to the largest visible player's {@link DanmakuOverlayView}.
 */
@SuppressWarnings("unused")
public final class TwitchDanmaku {
    static final String TAG = "TwitchDanmaku";

    /** Messages sent longer ago than this are chat history, not live chat. */
    private static final long MAX_AGE_MS = 15_000L;
    private static final int RECENT_IDS = 512;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<DanmakuOverlayView> VIEWS = new ArrayList<>();
    private static final Map<String, Boolean> RECENT = new LinkedHashMap<String, Boolean>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > RECENT_IDS;
        }
    };
    private static volatile boolean loggedFailure;
    private static volatile int loggedMessages;

    private TwitchDanmaku() {
    }

    // Patch-time defaults; the patch replaces the constants with the chosen options.
    public static int configFontSizeSp() { return 22; }
    public static int configOpacityPercent() { return 85; }
    public static int configDurationSeconds() { return 7; }
    public static int configCoveragePercent() { return 75; }
    public static int configMaxLength() { return 60; }

    /** Called by the patched chat parser with each parsed live chat message. */
    public static void onChatMessage(Object message) {
        try {
            DanmakuSettings settings = DanmakuSettings.peek();
            if (message == null || settings == null || !settings.enabled) return;

            ChatMessageReader.Comment comment = ChatMessageReader.read(message);
            if (comment == null || comment.plainText.isEmpty() && !hasEmote(comment)) return;
            if (loggedMessages < 3) {
                loggedMessages++;
                Log.d(TAG, "Chat message: segments=" + comment.segments.size()
                        + " ageMs=" + (comment.sentAtMs > 0L
                        ? System.currentTimeMillis() - comment.sentAtMs : -1L)
                        + " hasId=" + (comment.id != null));
            }
            if (comment.sentAtMs > 0L
                    && System.currentTimeMillis() - comment.sentAtMs > MAX_AGE_MS) {
                return;
            }
            String key = comment.id != null ? comment.id
                    : comment.author + '\u0000' + comment.plainText + '\u0000' + comment.sentAtMs;
            synchronized (RECENT) {
                if (RECENT.put(key, Boolean.TRUE) != null) return;
            }
            if (comment.plainText.codePointCount(0, comment.plainText.length())
                    > settings.maxLength) {
                return;
            }
            if (settings.filter.blocks(comment.plainText)) return;

            MAIN.post(() -> {
                DanmakuOverlayView target = activeView();
                if (target != null) target.add(comment);
            });
        } catch (Throwable throwable) {
            if (!loggedFailure) {
                loggedFailure = true;
                Log.w(TAG, "Could not read a chat message", throwable);
            }
        }
    }

    private static boolean hasEmote(ChatMessageReader.Comment comment) {
        for (ChatTokens.Segment segment : comment.segments) {
            if (segment.isEmote()) return true;
        }
        return false;
    }

    static void register(DanmakuOverlayView view) {
        if (!VIEWS.contains(view)) VIEWS.add(view);
    }

    static void unregister(DanmakuOverlayView view) {
        VIEWS.remove(view);
    }

    /** Applies changed settings to every player; the views are only touched on the main thread. */
    public static void settingsChanged() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(TwitchDanmaku::settingsChanged);
            return;
        }
        for (DanmakuOverlayView view : new ArrayList<>(VIEWS)) view.settingsChanged();
    }

    /** The largest visible player; feed previews and minimised players are smaller. */
    private static DanmakuOverlayView activeView() {
        DanmakuOverlayView best = null;
        long bestArea = 0L;
        for (DanmakuOverlayView view : VIEWS) {
            if (!view.isAttachedToWindow() || !view.isShown()) continue;
            long area = (long) view.getWidth() * view.getHeight();
            if (area > bestArea) {
                bestArea = area;
                best = view;
            }
        }
        return best;
    }
}
