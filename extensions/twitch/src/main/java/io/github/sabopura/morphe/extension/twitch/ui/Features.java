package io.github.sabopura.morphe.extension.twitch.ui;

/**
 * Which Twitch patches were applied. Each patch replaces the constant of its flag method, so
 * the shared player button and settings screen only show the features that exist.
 */
@SuppressWarnings("unused")
public final class Features {
    private static volatile boolean preview;

    private Features() {
    }

    // Patch-time flags; 0 means the patch was not selected.
    public static int danmakuPatch() { return 0; }
    public static int chatPatch() { return 0; }
    public static int playerPatch() { return 0; }
    public static int adsPatch() { return 0; }

    public static boolean danmaku() {
        return preview || danmakuPatch() != 0;
    }

    public static boolean chat() {
        return preview || chatPatch() != 0;
    }

    public static boolean player() {
        return preview || playerPatch() != 0;
    }

    public static boolean ads() {
        return preview || adsPatch() != 0;
    }

    /** Shows every feature; used by the on-device verification harness only. */
    public static void enableAllForPreview() {
        preview = true;
    }
}
