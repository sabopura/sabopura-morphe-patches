package io.github.sabopura.morphe.extension.twitch.points;

/** Each body is replaced by a checked, typed bridge to the input APK. */
public final class PointsAccess {
    private PointsAccess() {}
    public static boolean isEnabled(Object viewState) { return false; }
    public static Object activeClaim(Object viewState) { return null; }
    public static Object status(Object viewState) { return null; }
    public static String id(Object claim) { return null; }
    public static void claimBonus(Object presenter, String id) {
        throw new IllegalStateException("Points access bridge not installed");
    }
}
