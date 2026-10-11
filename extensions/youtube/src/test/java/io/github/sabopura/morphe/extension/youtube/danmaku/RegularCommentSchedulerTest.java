package io.github.sabopura.morphe.extension.youtube.danmaku;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public final class RegularCommentSchedulerTest {
    @Test
    public void firstUntimedCommentAppearsOneSecondAfterCapture() {
        long assigned = RegularCommentScheduler.assignTime(
                "jNQXAC9IVRw", "c1", 5_000L, 300_000L, new ArrayList<>());

        assertEquals(6_000L, assigned);
    }

    @Test
    public void laterCommentsFillGapsWithoutMovingExistingTimes() {
        List<Long> occupied = new ArrayList<>();
        occupied.add(6_000L);
        long second = RegularCommentScheduler.assignTime(
                "jNQXAC9IVRw", "c2", 5_000L, 300_000L, occupied);
        occupied.add(second);
        long third = RegularCommentScheduler.assignTime(
                "jNQXAC9IVRw", "c3", 5_000L, 300_000L, occupied);

        assertEquals(6_000L, occupied.get(0).longValue());
        assertNotEquals(second, third);
        assertTrue(second >= 6_000L && second <= 295_000L);
        assertTrue(third >= 6_000L && third <= 295_000L);
    }

    @Test
    public void assignmentIsDeterministicForSameInputs() {
        List<Long> occupied = new ArrayList<>();
        occupied.add(2_000L);

        assertEquals(
                RegularCommentScheduler.assignTime("video-id01", "same", 1_000L,
                        60_000L, occupied),
                RegularCommentScheduler.assignTime("video-id01", "same", 1_000L,
                        60_000L, occupied));
    }
}
