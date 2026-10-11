package io.github.sabopura.morphe.extension.youtube.danmaku;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Random;

import org.junit.Test;

public class MediaClockTest {
    private static final long GRACE = 2_500L;

    /** Reports once a second with up to +-80 ms of delivery jitter at 1x speed. */
    @Test
    public void jitteryReportsKeepAnEvenPace() {
        MediaClock clock = new MediaClock();
        clock.reset(0);
        Random random = new Random(7);
        long lastTime = -1;
        float slowest = Float.MAX_VALUE;
        float fastest = 0f;
        for (long uptime = 0; uptime <= 30_000; uptime += 8) {
            if (uptime % 1_000 == 0) {
                long jitter = random.nextInt(161) - 80;
                clock.onReport(uptime + jitter, uptime, GRACE);
            }
            long time = clock.timeAt(uptime, GRACE);
            if (uptime >= 5_000 && lastTime >= 0) {
                // Pace over the next 100 ms window.
                long later = clock.timeAt(uptime + 100, GRACE);
                float pace = (later - time) / 100f;
                slowest = Math.min(slowest, pace);
                fastest = Math.max(fastest, pace);
            }
            lastTime = time;
        }
        assertTrue("slowest " + slowest, slowest >= 0.95f);
        assertTrue("fastest " + fastest, fastest <= 1.05f);
    }

    @Test
    public void followsTheVideoOverTime() {
        MediaClock clock = new MediaClock();
        clock.reset(0);
        for (long uptime = 0; uptime <= 20_000; uptime += 1_000) {
            clock.onReport(uptime + 300, uptime, GRACE); // 300 ms ahead of us
        }
        assertEquals(20_300, clock.timeAt(20_000, GRACE), 60);
    }

    @Test
    public void measuresPlaybackSpeedOverSeveralSeconds() {
        MediaClock clock = new MediaClock();
        clock.reset(0);
        for (long uptime = 0; uptime <= 10_000; uptime += 1_000) {
            clock.onReport(uptime * 3 / 2, uptime, GRACE);
        }
        assertEquals(1.5f, clock.speed(), 0.001f);
    }

    @Test
    public void seeksJumpAndPausesStop() {
        MediaClock clock = new MediaClock();
        clock.reset(0);
        for (long uptime = 0; uptime <= 5_000; uptime += 1_000) {
            clock.onReport(uptime, uptime, GRACE);
        }
        MediaClock.Report seek = clock.onReport(60_000, 6_000, GRACE);
        assertTrue(seek.seek);
        assertEquals(60_000, clock.timeAt(6_000, GRACE));
        clock.onReport(61_000, 7_000, GRACE);
        clock.onReport(61_000, 8_000, GRACE); // paused
        assertFalse(clock.isAnimating(8_100, GRACE));
        assertEquals(61_000, clock.timeAt(9_000, GRACE));
    }

    @Test
    public void snapsToSelectableSpeeds() {
        assertEquals(1f, MediaClock.stableSpeed(1f, 1.06f), 0f);
        assertEquals(2f, MediaClock.stableSpeed(1f, 1.95f), 0f);
    }
}
