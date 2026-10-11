package io.github.sabopura.morphe.extension.youtube.ambient;

/**
 * Translates the single "immersion" choice into the parameters of the light that spills out
 * of the player. Pure Java so the presets can be unit tested.
 */
final class ImmersionProfile {
    static final int LEVEL_OFF = 0;
    static final int LEVEL_SUBTLE = 1;
    static final int LEVEL_BALANCED = 2;
    static final int LEVEL_MAXIMUM = 3;
    static final int LEVEL_CUSTOM = 4;
    /** Strongest level: the moving picture itself, blurred, surrounds the player. */
    static final int LEVEL_THEATER = 5;
    static final int HIGHEST_LEVEL = LEVEL_THEATER;

    /** Custom reach value meaning "down to the far edge of the screen". */
    static final int REACH_WHOLE_SCREEN = 1000;

    static final ImmersionProfile OFF =
            new ImmersionProfile(false, 0f, 0f, false, 0f, 1f, 1f, 0f, 0f, false);

    final boolean enabled;
    /** Peak opacity of the light next to the player (0-1). */
    final float strength;
    /** Reach as a multiple of the player height; ignored when {@link #wholeScreen}. */
    final float reach;
    /** Light the whole page down to the far screen edge. */
    final boolean wholeScreen;
    /** Fraction of the strength kept at the far edge (0-1). */
    final float floor;
    /** Brightness gain so dark scenes still glow. */
    final float gain;
    /** Maximum luminance of the light (0-1); 1 = uncapped. Keeps white text readable. */
    final float lumaCap;
    /**
     * Opacity of the colour veil laid over the page content (0-1). In theater mode this is
     * the opacity of the blurred video backdrop.
     */
    final float cover;
    /** How far distant light drifts towards the frame's average colour (0-1). */
    final float averageMix;
    /** Theater: opaque light under a see-through page instead of light over the page. */
    final boolean videoBackdrop;

    private ImmersionProfile(boolean enabled, float strength, float reach, boolean wholeScreen,
                             float floor, float gain, float lumaCap, float cover,
                             float averageMix, boolean videoBackdrop) {
        this.enabled = enabled;
        this.strength = strength;
        this.reach = reach;
        this.wholeScreen = wholeScreen;
        this.floor = floor;
        this.gain = gain;
        this.lumaCap = lumaCap;
        this.cover = cover;
        this.averageMix = averageMix;
        this.videoBackdrop = videoBackdrop;
    }

    /**
     * @param level          one of the {@code LEVEL_*} constants
     * @param customStrength custom peak strength in percent (10-100)
     * @param customReach    custom reach in percent of the player height, or
     *                       {@link #REACH_WHOLE_SCREEN}
     * @param customCover    custom page veil in percent (0-80)
     */
    static ImmersionProfile forLevel(int level, int customStrength, int customReach,
                                     int customCover) {
        switch (level) {
            case LEVEL_OFF:
                return OFF;
            case LEVEL_SUBTLE:
                // A gentle halo just around the player.
                return new ImmersionProfile(true, 0.6f, 1.2f, false, 0f, 1.25f, 1f, 0f, 0f, false);
            case LEVEL_MAXIMUM:
                // Whole screen bathed in the video's colour; page content fades behind it.
                return new ImmersionProfile(true, 1f, 0f, true, 0.8f, 1.6f, 0.36f, 0.75f, 0.55f,
                        false);
            case LEVEL_THEATER:
                // The picture's light fills the whole screen at full strength and the page
                // itself becomes see-through on top of it.
                return new ImmersionProfile(true, 1f, 0f, true, 1f, 1.15f, 1f, 1f, 0f, true);
            case LEVEL_CUSTOM: {
                boolean whole = customReach >= REACH_WHOLE_SCREEN;
                return new ImmersionProfile(true, clamp(customStrength / 100f, 0.1f, 1f),
                        Math.max(0.25f, customReach / 100f), whole, whole ? 0.45f : 0f, 1.5f, 1f,
                        clamp(customCover / 100f, 0f, 0.8f), whole ? 0.45f : 0.2f, false);
            }
            case LEVEL_BALANCED:
            default:
                // Whole screen tinted, but the light is capped so text stays readable.
                return new ImmersionProfile(true, 0.95f, 0f, true, 0.4f, 1.7f, 0.34f, 0f, 0.5f,
                        false);
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
