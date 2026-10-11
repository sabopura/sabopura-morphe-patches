package io.github.sabopura.morphe.extension.twitch.danmaku;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class LaneAllocatorTest {
    private static final float SCREEN = 1000f;
    private static final float GAP = 20f;
    private static final long DURATION = 7000L;

    @Test
    public void fillsLanesTopDownThenReportsBusy() {
        LaneAllocator lanes = new LaneAllocator();
        lanes.resize(3);
        assertEquals(0, lanes.acquire(0L, 200f, DURATION, SCREEN, GAP));
        assertEquals(1, lanes.acquire(0L, 200f, DURATION, SCREEN, GAP));
        assertEquals(2, lanes.acquire(0L, 200f, DURATION, SCREEN, GAP));
        assertEquals(-1, lanes.acquire(10L, 200f, DURATION, SCREEN, GAP));
    }

    @Test
    public void laneFreesOnceTheTailHasEnteredAndCannotBeCaught() {
        LaneAllocator lanes = new LaneAllocator();
        lanes.resize(1);
        lanes.acquire(0L, 200f, DURATION, SCREEN, GAP);
        long gap = LaneAllocator.requiredStartGapMs(200f, DURATION, 200f, DURATION, SCREEN, GAP);
        // Same width and duration: only the entry gap matters, (200 + 20) / 1200 of the trip.
        assertEquals((long) Math.ceil(DURATION * 220.0 / 1200.0), gap);
        assertEquals(-1, lanes.acquire(gap - 1, 200f, DURATION, SCREEN, GAP));
        assertEquals(0, lanes.acquire(gap, 200f, DURATION, SCREEN, GAP));
    }

    @Test
    public void longerFollowerWaitsLongerBecauseItMovesFaster() {
        long sameWidth = LaneAllocator.requiredStartGapMs(100f, DURATION, 100f, DURATION,
                SCREEN, GAP);
        long fasterFollower = LaneAllocator.requiredStartGapMs(100f, DURATION, 900f, DURATION,
                SCREEN, GAP);
        assertTrue(fasterFollower > sameWidth);
    }

    @Test
    public void resizeKeepsExistingLanes() {
        LaneAllocator lanes = new LaneAllocator();
        lanes.resize(1);
        lanes.acquire(0L, 200f, DURATION, SCREEN, GAP);
        lanes.resize(2);
        assertEquals(1, lanes.acquire(1L, 200f, DURATION, SCREEN, GAP));
        lanes.clear();
        assertEquals(0, lanes.acquire(2L, 200f, DURATION, SCREEN, GAP));
    }
}
