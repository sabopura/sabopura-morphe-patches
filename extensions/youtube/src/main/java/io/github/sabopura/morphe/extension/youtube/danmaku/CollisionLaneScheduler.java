package io.github.sabopura.morphe.extension.youtube.danmaku;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java collision-free scheduler for right-to-left scrolling comments.
 *
 * <p>Every comment may have its own travel duration, so the same scheduler serves the
 * NicoNico-style constant-duration mode and the bilibili-style constant-speed mode.
 */
final class CollisionLaneScheduler {
    private CollisionLaneScheduler() {
    }

    static Result schedule(List<Candidate> candidates, List<Placement> retained,
                           int laneCount, float videoWidth, long durationMs, float gapPx) {
        return schedule(candidates, retained, laneCount, videoWidth, durationMs, gapPx, false);
    }

    /**
     * @param allowOverlap when no lane is free before a candidate's deadline, place it in the
     *                     least congested lane instead of omitting it (NicoNico behaviour).
     */
    static Result schedule(List<Candidate> candidates, List<Placement> retained,
                           int laneCount, float videoWidth, long durationMs, float gapPx,
                           boolean allowOverlap) {
        int safeLaneCount = Math.max(1, laneCount);
        long defaultDuration = Math.max(1L, durationMs);
        List<List<Placement>> lanes = new ArrayList<>(safeLaneCount);
        for (int lane = 0; lane < safeLaneCount; lane++) lanes.add(new ArrayList<>());
        List<Placement> result = new ArrayList<>();
        if (retained != null) {
            for (Placement placement : retained) {
                if (placement.lane < 0 || placement.lane >= safeLaneCount) continue;
                lanes.get(placement.lane).add(placement);
                result.add(placement);
            }
        }
        for (List<Placement> lane : lanes) {
            lane.sort(Comparator.comparingLong(value -> value.timeMs));
        }

        List<Candidate> ordered = candidates == null
                ? new ArrayList<>() : new ArrayList<>(candidates);
        ordered.sort(Comparator.comparingLong((Candidate value) -> value.desiredTimeMs)
                .thenComparing(Comparator.comparingInt((Candidate value) -> value.priority)
                        .reversed())
                .thenComparing(value -> value.id));

        List<String> omitted = new ArrayList<>();
        for (Candidate candidate : ordered) {
            long candidateDuration = candidate.durationMs > 0
                    ? candidate.durationMs : defaultDuration;
            long deadline = candidate.desiredTimeMs + Math.max(0, candidate.maxDelayMs);
            Placement best = null;
            for (int laneIndex = 0; laneIndex < safeLaneCount; laneIndex++) {
                long time = earliestSafeTime(candidate, candidateDuration, lanes.get(laneIndex),
                        deadline, videoWidth, defaultDuration, gapPx);
                if (time < 0) continue;
                if (best == null || time < best.timeMs
                        || (time == best.timeMs && laneIndex < best.lane)) {
                    best = new Placement(candidate.id, time, laneIndex,
                            candidate.width, candidate.priority, candidateDuration, false);
                }
            }
            if (best == null && allowOverlap) {
                int lane = leastCongestedLane(lanes, candidate.desiredTimeMs);
                best = new Placement(candidate.id, candidate.desiredTimeMs, lane,
                        candidate.width, candidate.priority, candidateDuration, true);
            }
            if (best == null) {
                omitted.add(candidate.id);
            } else {
                insertSorted(lanes.get(best.lane), best);
                result.add(best);
            }
        }
        result.sort(Comparator.comparingLong((Placement value) -> value.timeMs)
                .thenComparingInt(value -> value.lane));
        return new Result(Collections.unmodifiableList(result),
                Collections.unmodifiableList(omitted));
    }

    /** Picks the lane whose most recent comment started longest ago. */
    private static int leastCongestedLane(List<List<Placement>> lanes, long timeMs) {
        int bestLane = 0;
        long bestStart = Long.MAX_VALUE;
        for (int laneIndex = 0; laneIndex < lanes.size(); laneIndex++) {
            long latestStart = Long.MIN_VALUE;
            for (Placement placement : lanes.get(laneIndex)) {
                if (placement.timeMs <= timeMs && placement.timeMs > latestStart) {
                    latestStart = placement.timeMs;
                }
            }
            if (latestStart < bestStart) {
                bestStart = latestStart;
                bestLane = laneIndex;
            }
        }
        return bestLane;
    }

    private static long earliestSafeTime(Candidate candidate, long candidateDuration,
                                         List<Placement> lane, long deadline, float videoWidth,
                                         long defaultDuration, float gapPx) {
        long time = candidate.desiredTimeMs;
        for (int pass = 0; pass <= lane.size() && time <= deadline; pass++) {
            boolean moved = false;
            for (Placement existing : lane) {
                if (existing.overlapping) continue;
                long existingDuration = existing.durationMs > 0
                        ? existing.durationMs : defaultDuration;
                if (time >= existing.timeMs) {
                    long required = requiredStartGapMs(existing.width, existingDuration,
                            candidate.width, candidateDuration, videoWidth, gapPx);
                    if (time < existing.timeMs + required) {
                        time = existing.timeMs + required;
                        moved = true;
                        break;
                    }
                } else {
                    long required = requiredStartGapMs(candidate.width, candidateDuration,
                            existing.width, existingDuration, videoWidth, gapPx);
                    if (existing.timeMs < time + required) {
                        time = existing.timeMs + requiredStartGapMs(existing.width,
                                existingDuration, candidate.width, candidateDuration,
                                videoWidth, gapPx);
                        moved = true;
                        break;
                    }
                }
            }
            if (!moved) return time <= deadline ? time : -1;
        }
        return -1;
    }

    static long requiredStartGapMs(float leadingWidth, float followingWidth,
                                   float videoWidth, long durationMs, float gapPx) {
        return requiredStartGapMs(leadingWidth, durationMs, followingWidth, durationMs,
                videoWidth, gapPx);
    }

    /**
     * Minimum start-time difference so the following comment neither enters before the
     * leading comment's tail has cleared the right edge nor catches it before it exits.
     */
    static long requiredStartGapMs(float leadingWidth, long leadingDurationMs,
                                   float followingWidth, long followingDurationMs,
                                   float videoWidth, float gapPx) {
        float safeVideoWidth = Math.max(1f, videoWidth);
        float lead = Math.max(0f, leadingWidth);
        float follow = Math.max(0f, followingWidth);
        double leadDuration = Math.max(1L, leadingDurationMs);
        double followDuration = Math.max(1L, followingDurationMs);
        double entryGap = leadDuration * (lead + gapPx) / (safeVideoWidth + lead);
        double catchUpGap = leadDuration
                - followDuration * (safeVideoWidth - gapPx) / (safeVideoWidth + follow);
        return Math.max(1L, (long) Math.ceil(Math.max(entryGap, catchUpGap)));
    }

    private static void insertSorted(List<Placement> lane, Placement placement) {
        int index = Collections.binarySearch(lane, placement,
                Comparator.comparingLong(value -> value.timeMs));
        if (index < 0) index = -index - 1;
        lane.add(index, placement);
    }

    static final class Candidate {
        final String id;
        final long desiredTimeMs;
        final long maxDelayMs;
        final float width;
        final int priority;
        /** Travel duration of this comment; zero uses the schedule default. */
        final long durationMs;

        Candidate(String id, long desiredTimeMs, long maxDelayMs,
                  float width, int priority) {
            this(id, desiredTimeMs, maxDelayMs, width, priority, 0L);
        }

        Candidate(String id, long desiredTimeMs, long maxDelayMs,
                  float width, int priority, long durationMs) {
            this.id = id;
            this.desiredTimeMs = desiredTimeMs;
            this.maxDelayMs = maxDelayMs;
            this.width = width;
            this.priority = priority;
            this.durationMs = durationMs;
        }
    }

    static final class Placement {
        final String id;
        final long timeMs;
        final int lane;
        final float width;
        final int priority;
        final long durationMs;
        /** True when crowd mode placed this comment on top of another one. */
        final boolean overlapping;

        Placement(String id, long timeMs, int lane, float width, int priority) {
            this(id, timeMs, lane, width, priority, 0L, false);
        }

        Placement(String id, long timeMs, int lane, float width, int priority,
                  long durationMs, boolean overlapping) {
            this.id = id;
            this.timeMs = timeMs;
            this.lane = lane;
            this.width = width;
            this.priority = priority;
            this.durationMs = durationMs;
            this.overlapping = overlapping;
        }
    }

    static final class Result {
        final List<Placement> placements;
        final List<String> omittedIds;

        Result(List<Placement> placements, List<String> omittedIds) {
            this.placements = placements;
            this.omittedIds = omittedIds;
        }

        int omittedCount() { return omittedIds.size(); }

        Map<String, Placement> byId() {
            Map<String, Placement> values = new HashMap<>();
            for (Placement placement : placements) values.put(placement.id, placement);
            return values;
        }
    }
}
