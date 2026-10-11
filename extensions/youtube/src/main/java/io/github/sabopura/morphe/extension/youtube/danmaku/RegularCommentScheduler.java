package io.github.sabopura.morphe.extension.youtube.danmaku;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Assigns one stable video-time slot to every untimestamped regular comment. */
final class RegularCommentScheduler {
    private static final long FIRST_COMMENT_DELAY_MS = 1_000L;
    private static final long END_PADDING_MS = 5_000L;

    private RegularCommentScheduler() {
    }

    static long assignTime(String videoId, String commentId, long currentTimeMs,
                           long videoLengthMs, List<Long> occupiedTimes) {
        long length = Math.max(1, videoLengthMs);
        long latest = Math.max(0, length - Math.min(END_PADDING_MS, length / 10));
        long earliest = Math.min(latest, Math.max(0, currentTimeMs + FIRST_COMMENT_DELAY_MS));
        if (occupiedTimes == null || occupiedTimes.isEmpty() || latest <= earliest) {
            return earliest;
        }

        List<Long> sorted = new ArrayList<>(occupiedTimes.size());
        for (Long value : occupiedTimes) {
            if (value != null && value >= earliest && value <= latest) sorted.add(value);
        }
        if (sorted.isEmpty()) return earliest;
        Collections.sort(sorted);

        long bestLeft = earliest;
        long bestRight = sorted.get(0);
        long bestGap = Math.max(0, bestRight - bestLeft);
        for (int index = 1; index < sorted.size(); index++) {
            long left = sorted.get(index - 1);
            long right = sorted.get(index);
            long gap = Math.max(0, right - left);
            if (gap > bestGap) {
                bestGap = gap;
                bestLeft = left;
                bestRight = right;
            }
        }
        long tailGap = Math.max(0, latest - sorted.get(sorted.size() - 1));
        if (tailGap > bestGap) {
            bestGap = tailGap;
            bestLeft = sorted.get(sorted.size() - 1);
            bestRight = latest;
        }
        if (bestGap <= 1) return Math.min(latest, earliest + sorted.size());

        long hash = Integer.toUnsignedLong((videoId + ':' + commentId).hashCode());
        double fraction = 0.35d + (hash % 301L) / 1000d;
        return Math.max(earliest, Math.min(latest,
                bestLeft + Math.round((bestRight - bestLeft) * fraction)));
    }
}
