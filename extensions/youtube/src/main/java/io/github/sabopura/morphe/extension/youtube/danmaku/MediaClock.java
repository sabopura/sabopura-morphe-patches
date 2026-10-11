package io.github.sabopura.morphe.extension.youtube.danmaku;

/**
 * Smooth media clock for the comments, driven by YouTube's sparse time reports (about one
 * per second while the controls are hidden). Pure Java so it can be unit tested.
 *
 * <p>The reports arrive with a few tens of milliseconds of jitter. Following each report -
 * or spreading every disagreement over the next half second - made the comments speed up
 * and slow down once per report. Instead the clock runs at the playback speed measured over
 * several seconds and absorbs disagreements with a small, averaged rate trim (at most a few
 * percent, which the eye does not notice). Only a large disagreement, a seek, jumps.
 */
final class MediaClock {
    /** Disagreements above this are a seek: the clock jumps. */
    static final long SEEK_DISCONTINUITY_MS = 5_000L;
    /** Disagreements above this are corrected at once rather than slewed. */
    static final long RESYNC_MS = 600L;
    /** Largest rate change used to absorb a disagreement. */
    static final float MAX_TRIM = 0.04f;
    /** A disagreement is absorbed over about this long (limited by {@link #MAX_TRIM}). */
    private static final float TRIM_HORIZON_MS = 2_500f;
    /** Playback speed is measured over at least this long. */
    private static final long SPEED_WINDOW_MS = 3_000L;
    private static final long SPEED_WINDOW_MAX_MS = 12_000L;

    /** YouTube's selectable playback speeds; measurements are snapped to the nearest one. */
    private static final float[] KNOWN_SPEEDS = {
            0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f
    };

    private long baseVideoMs;
    private long baseUptimeMs;
    private float speed = 1f;
    private float trim;
    private float errorAverage;
    private boolean advancing;
    private long lastReportVideoMs = -1;
    private long lastReportUptimeMs;
    private long anchorVideoMs = -1;
    private long anchorUptimeMs;

    /** Outcome of one report. */
    static final class Report {
        final boolean seek;
        final boolean resumed;

        Report(boolean seek, boolean resumed) {
            this.seek = seek;
            this.resumed = resumed;
        }
    }

    void reset(long uptimeMs) {
        baseVideoMs = 0;
        baseUptimeMs = uptimeMs;
        speed = 1f;
        trim = 0f;
        errorAverage = 0f;
        advancing = false;
        lastReportVideoMs = -1;
        anchorVideoMs = -1;
    }

    Report onReport(long videoMs, long uptimeMs, long graceMs) {
        long expected = timeAt(uptimeMs, graceMs);
        boolean wasAnimating = isAnimating(uptimeMs, graceMs);
        long difference = videoMs - expected;
        boolean seek = lastReportVideoMs >= 0 && Math.abs(difference) > SEEK_DISCONTINUITY_MS;

        // Moving or not, from the raw reports (not from our own estimate).
        long realDelta = uptimeMs - lastReportUptimeMs;
        long videoDelta = videoMs - lastReportVideoMs;
        if (lastReportVideoMs >= 0 && !seek && realDelta > 250 && videoDelta >= 0) {
            advancing = videoDelta >= Math.min(100, realDelta / 8);
        } else if (lastReportVideoMs < 0 || videoDelta > 0) {
            advancing = lastReportVideoMs >= 0 && videoDelta > 0;
        }

        // Speed over a window of several seconds, where report jitter is a few percent.
        if (!advancing || seek || anchorVideoMs < 0) {
            anchorVideoMs = videoMs;
            anchorUptimeMs = uptimeMs;
        } else if (uptimeMs - anchorUptimeMs >= SPEED_WINDOW_MS) {
            float measured = (videoMs - anchorVideoMs) / (float) (uptimeMs - anchorUptimeMs);
            speed = stableSpeed(speed, measured);
            if (uptimeMs - anchorUptimeMs >= SPEED_WINDOW_MAX_MS) {
                anchorVideoMs = videoMs;
                anchorUptimeMs = uptimeMs;
            }
        }

        if (!seek && wasAnimating && advancing && Math.abs(difference) < RESYNC_MS) {
            // Keep the picture continuous and absorb the averaged disagreement slowly.
            baseVideoMs = expected;
            errorAverage = errorAverage * 0.6f + difference * 0.4f;
            trim = clamp(errorAverage / TRIM_HORIZON_MS, -MAX_TRIM, MAX_TRIM);
        } else {
            baseVideoMs = Math.max(0, videoMs);
            errorAverage = 0f;
            trim = 0f;
        }
        baseUptimeMs = uptimeMs;
        lastReportVideoMs = videoMs;
        lastReportUptimeMs = uptimeMs;
        return new Report(seek, !wasAnimating && advancing);
    }

    long timeAt(long uptimeMs, long graceMs) {
        if (!advancing) return baseVideoMs;
        long elapsed = Math.max(0, Math.min(uptimeMs - baseUptimeMs, graceMs));
        return baseVideoMs + (long) (elapsed * speed * (1f + trim));
    }

    boolean isAnimating(long uptimeMs, long graceMs) {
        return advancing && uptimeMs - baseUptimeMs <= graceMs;
    }

    float speed() {
        return speed;
    }

    /**
     * Measurements close to a selectable speed are snapped to it; anything else is blended
     * in gradually.
     */
    static float stableSpeed(float previous, float measured) {
        float clamped = clamp(measured, 0.1f, 8.0f);
        for (float known : KNOWN_SPEEDS) {
            if (Math.abs(clamped - known) <= known * 0.08f) return known;
        }
        return previous * 0.7f + clamped * 0.3f;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
