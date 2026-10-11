package io.github.sabopura.morphe.extension.youtube.ambient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public final class ImmersionProfileTest {
    private static ImmersionProfile level(int level) {
        return ImmersionProfile.forLevel(level, 80, ImmersionProfile.REACH_WHOLE_SCREEN, 0);
    }

    @Test
    public void offKeepsOnlyTheBars() {
        assertFalse(level(ImmersionProfile.LEVEL_OFF).enabled);
    }

    @Test
    public void levelsGrowInReachAndPresence() {
        ImmersionProfile subtle = level(ImmersionProfile.LEVEL_SUBTLE);
        ImmersionProfile balanced = level(ImmersionProfile.LEVEL_BALANCED);
        ImmersionProfile maximum = level(ImmersionProfile.LEVEL_MAXIMUM);
        assertFalse(subtle.wholeScreen);
        assertTrue(balanced.wholeScreen);
        assertTrue(maximum.wholeScreen);
        assertTrue(subtle.floor < balanced.floor && balanced.floor < maximum.floor);
        assertTrue(subtle.strength < balanced.strength);
        assertTrue(balanced.gain > subtle.gain && maximum.gain > subtle.gain);
    }

    @Test
    public void balancedProtectsTextWhileMaximumDoesNot() {
        ImmersionProfile balanced = level(ImmersionProfile.LEVEL_BALANCED);
        ImmersionProfile maximum = level(ImmersionProfile.LEVEL_MAXIMUM);
        assertTrue(balanced.lumaCap < 0.5f);
        assertEquals(0f, balanced.cover, 0f);
        assertTrue(maximum.lumaCap > balanced.lumaCap);
        assertTrue(maximum.cover > 0.4f);
    }

    @Test
    public void theaterShowsThePictureItselfAboveEveryOtherLevel() {
        ImmersionProfile theater = level(ImmersionProfile.LEVEL_THEATER);
        ImmersionProfile maximum = level(ImmersionProfile.LEVEL_MAXIMUM);
        assertTrue(theater.videoBackdrop);
        assertFalse(maximum.videoBackdrop);
        assertTrue(theater.wholeScreen);
        assertTrue(theater.cover > maximum.cover);
        assertEquals(ImmersionProfile.LEVEL_THEATER, ImmersionProfile.HIGHEST_LEVEL);
    }

    @Test
    public void customUsesTheAdvancedValues() {
        ImmersionProfile custom = ImmersionProfile.forLevel(ImmersionProfile.LEVEL_CUSTOM, 40, 150, 60);
        assertEquals(0.4f, custom.strength, 0.001f);
        assertEquals(1.5f, custom.reach, 0.001f);
        assertFalse(custom.wholeScreen);
        assertEquals(0.6f, custom.cover, 0.001f);
        assertTrue(ImmersionProfile.forLevel(ImmersionProfile.LEVEL_CUSTOM, 40,
                ImmersionProfile.REACH_WHOLE_SCREEN, 0).wholeScreen);
    }

    @Test
    public void floorKeepsLightAtTheFarScreenEdge() {
        int width = 4;
        int height = 40;
        int[] pixels = new int[width * height];
        Arrays.fill(pixels, 0xFF203060);
        float[] player = {0f, 0f, 4f, 10f};
        float reach = AmbientMath.farthestDistance(width, height, player);
        AmbientMath.applyPageFalloff(pixels, width, height, player, reach, 1f, 0.5f,
                0xFF000000, 0f);
        int bottomAlpha = pixels[(height - 1) * width] >>> 24;
        assertTrue("bottom alpha " + bottomAlpha, bottomAlpha >= 127);
    }

    @Test
    public void toneLightBrightensAndCapsLuminance() {
        int[] dark = {0x80102040};
        AmbientMath.toneLight(dark, 2f, 1f);
        assertEquals(0x80204080, dark[0]);
        int[] bright = {0xFFFFFFFF};
        AmbientMath.toneLight(bright, 1f, 0.4f);
        assertTrue(AmbientMath.luma(bright[0]) <= 0.4f * 255 + 1);
        assertEquals(0xFF, bright[0] >>> 24);
    }
}
