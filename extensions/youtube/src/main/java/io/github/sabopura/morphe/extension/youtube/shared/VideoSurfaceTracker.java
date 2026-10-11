package io.github.sabopura.morphe.extension.youtube.shared;

import android.os.SystemClock;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.lang.ref.WeakReference;

/**
 * Finds YouTube's video surface without walking the whole (very large) view tree on every
 * frame. The previous surface is reused while it stays attached under the same root and a
 * full rescan happens at most once per {@link #RESCAN_INTERVAL_MS}.
 */
public final class VideoSurfaceTracker {
    private static final long RESCAN_INTERVAL_MS = 2_000L;
    private static final long MISSING_RESCAN_INTERVAL_MS = 300L;

    private WeakReference<View> rootRef = new WeakReference<>(null);
    private WeakReference<View> surfaceRef = new WeakReference<>(null);
    private long lastScanMs;

    public View find(View root) {
        if (root == null) return null;
        long now = SystemClock.elapsedRealtime();
        View cached = surfaceRef.get();
        boolean sameRoot = rootRef.get() == root;
        if (sameRoot && cached != null && isUsable(cached)
                && now - lastScanMs < RESCAN_INTERVAL_MS && isDescendant(cached, root)) {
            return cached;
        }
        if (sameRoot && cached == null && now - lastScanMs < MISSING_RESCAN_INTERVAL_MS) {
            return null;
        }
        lastScanMs = now;
        rootRef = new WeakReference<>(root);
        View found = findLargestVideoSurface(root, null);
        surfaceRef = new WeakReference<>(found);
        return found;
    }

    public void invalidate() {
        lastScanMs = 0;
        surfaceRef = new WeakReference<>(null);
    }

    private static boolean isUsable(View view) {
        return view.isAttachedToWindow() && view.isShown()
                && view.getWidth() > 0 && view.getHeight() > 0;
    }

    private static boolean isDescendant(View view, View root) {
        View current = view;
        while (current != null) {
            if (current == root) return true;
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    public static View findLargestVideoSurface(View view, View best) {
        if (view == null || view.getVisibility() != View.VISIBLE) return best;
        if ((view instanceof SurfaceView || view instanceof TextureView) && view.isShown()
                && !Views.isOwnSurface(view)) {
            long area = (long) view.getWidth() * view.getHeight();
            long bestArea = best == null ? -1L : (long) best.getWidth() * best.getHeight();
            if (area > bestArea) best = view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                best = findLargestVideoSurface(group.getChildAt(index), best);
            }
        }
        return best;
    }
}
