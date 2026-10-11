package io.github.sabopura.morphe.extension.twitch.danmaku;

/**
 * Real-time lane picker for right-to-left comments with a constant crossing time
 * (NicoNico style: long comments move faster, every comment is on screen equally long).
 *
 * <p>A lane accepts a new comment when the previous one has fully entered the screen plus
 * a gap, and when the new, possibly faster, comment cannot catch up with it before the
 * previous one leaves. Pure Java so it can be unit tested.
 */
final class LaneAllocator {
    private long[] startMs = new long[0];
    private float[] widths = new float[0];
    private long[] durations = new long[0];

    /** Resizes to {@code laneCount} lanes, keeping the state of lanes that remain. */
    void resize(int laneCount) {
        int count = Math.max(0, laneCount);
        if (count == startMs.length) return;
        long[] newStart = new long[count];
        float[] newWidths = new float[count];
        long[] newDurations = new long[count];
        int keep = Math.min(count, startMs.length);
        System.arraycopy(startMs, 0, newStart, 0, keep);
        System.arraycopy(widths, 0, newWidths, 0, keep);
        System.arraycopy(durations, 0, newDurations, 0, keep);
        for (int lane = keep; lane < count; lane++) newStart[lane] = Long.MIN_VALUE;
        startMs = newStart;
        widths = newWidths;
        durations = newDurations;
    }

    int laneCount() {
        return startMs.length;
    }

    void clear() {
        for (int lane = 0; lane < startMs.length; lane++) startMs[lane] = Long.MIN_VALUE;
    }

    /**
     * Returns the first (top-most) lane that can take a comment now, or -1 when every lane
     * is busy. The lane is reserved when one is returned.
     */
    int acquire(long nowMs, float width, long durationMs, float screenWidth, float gapPx) {
        for (int lane = 0; lane < startMs.length; lane++) {
            if (startMs[lane] == Long.MIN_VALUE
                    || nowMs - startMs[lane] >= requiredStartGapMs(widths[lane], durations[lane],
                    width, durationMs, screenWidth, gapPx)) {
                startMs[lane] = nowMs;
                widths[lane] = width;
                durations[lane] = durationMs;
                return lane;
            }
        }
        return -1;
    }

    /**
     * Minimum start-time difference so the following comment neither enters before the
     * leading comment's tail has cleared the right edge nor catches it before it exits.
     */
    static long requiredStartGapMs(float leadingWidth, long leadingDurationMs,
                                   float followingWidth, long followingDurationMs,
                                   float screenWidth, float gapPx) {
        float safeWidth = Math.max(1f, screenWidth);
        float lead = Math.max(0f, leadingWidth);
        float follow = Math.max(0f, followingWidth);
        double leadDuration = Math.max(1L, leadingDurationMs);
        double followDuration = Math.max(1L, followingDurationMs);
        double entryGap = leadDuration * (lead + gapPx) / (safeWidth + lead);
        double catchUpGap = leadDuration
                - followDuration * (safeWidth - gapPx) / (safeWidth + follow);
        return Math.max(1L, (long) Math.ceil(Math.max(entryGap, catchUpGap)));
    }
}
