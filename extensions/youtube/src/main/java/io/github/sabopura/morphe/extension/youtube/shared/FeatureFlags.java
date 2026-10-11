package io.github.sabopura.morphe.extension.youtube.shared;

/**
 * Compile-time feature switches. Each Morphe patch rewrites the constant returned by its
 * own method, so the shared hooks only drive the features that were actually patched in.
 */
@SuppressWarnings("SameReturnValue")
public final class FeatureFlags {
    private FeatureFlags() {
    }

    public static boolean danmaku() { return false; }

    public static boolean ambientLight() { return false; }
}
