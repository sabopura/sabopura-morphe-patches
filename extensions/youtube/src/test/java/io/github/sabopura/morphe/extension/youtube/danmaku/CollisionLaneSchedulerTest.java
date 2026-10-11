package io.github.sabopura.morphe.extension.youtube.danmaku;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

public final class CollisionLaneSchedulerTest {
    @Test
    public void delaysFollowingCommentUntilItCannotCatchLeadingComment() {
        CollisionLaneScheduler.Result result = CollisionLaneScheduler.schedule(Arrays.asList(
                new CollisionLaneScheduler.Candidate("first", 1_000, 2_000, 300, 3),
                new CollisionLaneScheduler.Candidate("second", 1_000, 8_000, 500, 1)
        ), Collections.emptyList(), 1, 1_000, 8_000, 12);

        assertEquals(2, result.placements.size());
        CollisionLaneScheduler.Placement first = result.byId().get("first");
        CollisionLaneScheduler.Placement second = result.byId().get("second");
        long required = CollisionLaneScheduler.requiredStartGapMs(
                first.width, second.width, 1_000, 8_000, 12);
        assertTrue(second.timeMs >= first.timeMs + required);
    }

    @Test
    public void prioritizesStreamAtTheSameTimestamp() {
        CollisionLaneScheduler.Result result = CollisionLaneScheduler.schedule(Arrays.asList(
                new CollisionLaneScheduler.Candidate("untimed", 5_000, 0, 200, 1),
                new CollisionLaneScheduler.Candidate("stream", 5_000, 0, 200, 3)
        ), Collections.emptyList(), 1, 1_000, 8_000, 12);

        assertEquals(5_000L, result.byId().get("stream").timeMs);
        assertEquals(1, result.omittedCount());
        assertEquals("untimed", result.omittedIds.get(0));
    }

    @Test
    public void retainsExistingPlacementsWhenNewPageArrives() {
        CollisionLaneScheduler.Placement retained =
                new CollisionLaneScheduler.Placement("old", 10_000, 0, 250, 2);
        CollisionLaneScheduler.Result result = CollisionLaneScheduler.schedule(
                Collections.singletonList(new CollisionLaneScheduler.Candidate(
                        "new", 10_000, 8_000, 250, 1)),
                Collections.singletonList(retained), 2, 1_000, 8_000, 12);

        assertEquals(10_000L, result.byId().get("old").timeMs);
        assertEquals(0, result.byId().get("old").lane);
        assertEquals(10_000L, result.byId().get("new").timeMs);
        assertEquals(1, result.byId().get("new").lane);
    }

    @Test
    public void fasterFollowingCommentNeverCatchesSlowerLeadingComment() {
        // Constant-speed mode: the long comment travels longer than the short one.
        CollisionLaneScheduler.Result result = CollisionLaneScheduler.schedule(Arrays.asList(
                new CollisionLaneScheduler.Candidate("short", 0, 10_000, 100, 2, 4_000),
                new CollisionLaneScheduler.Candidate("long", 0, 10_000, 600, 2, 8_000)
        ), Collections.emptyList(), 1, 1_000, 6_000, 10);

        assertEquals(2, result.placements.size());
        CollisionLaneScheduler.Placement first = result.placements.get(0);
        CollisionLaneScheduler.Placement second = result.placements.get(1);
        assertEquals("long", first.id);
        // Sample the whole overlap: the follower's head must stay behind the leader's tail.
        for (long t = second.timeMs; t <= first.timeMs + first.durationMs; t += 50) {
            double leaderX = 1_000 - (t - first.timeMs) / (double) first.durationMs
                    * (1_000 + first.width);
            double followerX = 1_000 - (t - second.timeMs) / (double) second.durationMs
                    * (1_000 + second.width);
            assertTrue("collision at " + t, followerX >= leaderX + first.width + 10 - 1);
        }
    }

    @Test
    public void crowdModeOverlapsInsteadOfOmitting() {
        ArrayList<CollisionLaneScheduler.Placement> retained = new ArrayList<>();
        retained.add(new CollisionLaneScheduler.Placement("busy", 10_000, 0, 900, 3));
        CollisionLaneScheduler.Result result = CollisionLaneScheduler.schedule(
                Collections.singletonList(new CollisionLaneScheduler.Candidate(
                        "crowded", 10_000, 2_000, 900, 2)),
                retained, 1, 1_000, 8_000, 12, true);

        assertEquals(0, result.omittedCount());
        assertTrue(result.byId().get("crowded").overlapping);
        assertEquals(10_000L, result.byId().get("crowded").timeMs);
    }

    @Test
    public void omitsSyncedCommentWhenTwoSecondDeadlineHasNoLane() {
        ArrayList<CollisionLaneScheduler.Placement> retained = new ArrayList<>();
        retained.add(new CollisionLaneScheduler.Placement("busy", 10_000, 0, 900, 3));
        CollisionLaneScheduler.Result result = CollisionLaneScheduler.schedule(
                Collections.singletonList(new CollisionLaneScheduler.Candidate(
                        "synced", 10_000, 2_000, 900, 2)),
                retained, 1, 1_000, 8_000, 12);

        assertEquals(1, result.omittedCount());
        assertEquals("synced", result.omittedIds.get(0));
    }
}
