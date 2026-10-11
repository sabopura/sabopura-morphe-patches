package io.github.sabopura.morphe.extension.youtube.shared;

import android.os.SystemClock;
import android.util.Log;
import android.view.ViewGroup;

import io.github.sabopura.morphe.extension.youtube.ambient.AmbientLightController;
import io.github.sabopura.morphe.extension.youtube.danmaku.DanmakuController;

/** Single entry point for the player hooks shared by every YouTube feature patch. */
@SuppressWarnings("unused")
public final class PlayerHooks {
    private static final String TAG = "SabopuraPlayer";
    private static final long MIN_GRACE_MS = 1_600L;
    private static final long MAX_GRACE_MS = 6_000L;

    private static volatile long lastCallbackRealtimeMs;
    private static long lastIntervalLogMs;
    /** Smoothed interval between media-time callbacks. */
    private static volatile float callbackIntervalMs = 500f;

    private static volatile long lastVideoTimeMs = -1;
    private static volatile long lastAdvanceRealtimeMs;

    private PlayerHooks() {
    }

    /** Called whenever YouTube builds the regular player overlay. */
    public static void initializeOverlay(ViewGroup controlsParent) {
        if (controlsParent == null) return;
        if (FeatureFlags.danmaku()) {
            try {
                DanmakuController.initializeOverlay(controlsParent);
            } catch (Throwable throwable) {
                Log.w(TAG, "Danmaku overlay hook failed", throwable);
            }
        }
        if (FeatureFlags.ambientLight()) {
            try {
                AmbientLightController.attach(controlsParent);
            } catch (Throwable throwable) {
                Log.w(TAG, "Ambient light hook failed", throwable);
            }
        }
    }

    /** YouTube's media-time callback, delivered several times per second while playing. */
    public static void setVideoTime(long videoTimeMs) {
        long previous = lastVideoTimeMs;
        lastVideoTimeMs = videoTimeMs;
        long nowRealtime = SystemClock.elapsedRealtime();
        long sinceLast = nowRealtime - lastCallbackRealtimeMs;
        lastCallbackRealtimeMs = nowRealtime;
        if (sinceLast > 0 && sinceLast < MAX_GRACE_MS) {
            callbackIntervalMs = callbackIntervalMs * 0.7f + sinceLast * 0.3f;
        }
        if (Log.isLoggable(TAG, Log.DEBUG) && nowRealtime - lastIntervalLogMs >= 2_000) {
            lastIntervalLogMs = nowRealtime;
            Log.d(TAG, "Clock interval=" + sinceLast + "ms smoothed=" + (int) callbackIntervalMs
                    + "ms grace=" + clockGraceMs() + "ms");
        }
        if (previous >= 0 && videoTimeMs != previous) {
            lastAdvanceRealtimeMs = SystemClock.elapsedRealtime();
        }
        if (FeatureFlags.danmaku()) DanmakuController.setVideoTime(videoTimeMs);
        if (FeatureFlags.ambientLight()) AmbientLightController.onVideoTime();
    }

    /** Smoothed interval between YouTube's media-time reports, for diagnostics. */
    public static int clockIntervalMs() {
        return (int) callbackIntervalMs;
    }

    /**
     * Milliseconds since YouTube last reported the media time. YouTube pauses its reports
     * at times while the video keeps playing (for example while parts of the watch page are
     * scrolled out of view), so a silent clock does not mean the video stopped.
     */
    public static long millisSinceLastReport() {
        long last = lastCallbackRealtimeMs;
        return last == 0 ? Long.MAX_VALUE : SystemClock.elapsedRealtime() - last;
    }

    /** True while the media clock moved recently, i.e. not paused, buffering, or ended. */
    public static boolean isPlaybackAdvancing() {
        return SystemClock.elapsedRealtime() - lastAdvanceRealtimeMs <= clockGraceMs();
    }

    /**
     * How long the last reported time may be extrapolated. YouTube reports the media time
     * several times a second while the player controls are visible but much less often once
     * they are hidden; a fixed short grace made everything freeze between sparse reports.
     */
    public static long clockGraceMs() {
        long adaptive = (long) (callbackIntervalMs * 2.5f);
        return Math.max(MIN_GRACE_MS, Math.min(MAX_GRACE_MS, adaptive));
    }
}
