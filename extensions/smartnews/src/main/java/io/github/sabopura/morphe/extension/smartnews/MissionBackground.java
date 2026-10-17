package io.github.sabopura.morphe.extension.smartnews;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;

/**
 * Runs the "My missions" page invisibly while the main screen is open, so completed rewards are
 * claimed without opening the Points tab. The page is SmartNews' own MissionsLandingPageFragment
 * (so the app's bridge and sign-in work unchanged), added to a 1 x 1 transparent container and
 * removed again as soon as the claim script reports that it is done.
 */
public final class MissionBackground {
    private static final String TAG = "SabopuraMissions";
    private static final String FRAGMENT_TAG = "sabopura-missions";
    private static final String FRAGMENT_CLASS = "jp.gocro.smartnews.android.stamprally.ui.MissionsLandingPageFragment";
    private static final String URL = "https://tour.smartnews.com/campaign/";
    private static final String MODULES = "stampRally,ad";
    private static final long START_DELAY_MS = 5_000L;
    private static final long MIN_INTERVAL_MS = 5 * 60_000L;
    private static final long TIMEOUT_MS = 90_000L;
    /** While the main screen stays open, check again periodically (articles read meanwhile). */
    private static final long REPEAT_MS = 10 * 60_000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static long lastStart = -MIN_INTERVAL_MS;
    private static WeakReference<Activity> host;
    private static WeakReference<Activity> resumed;
    private static Object fragmentManager;
    private static Object fragment;
    private static FrameLayout container;
    private static final Runnable TIMEOUT = () -> finish("timeout");

    private MissionBackground() {
    }

    /** Called from the main activity's onResume. */
    public static void onMainResumed(Activity activity) {
        resumed = new WeakReference<>(activity);
        if (activity == null || container != null
                || SystemClock.elapsedRealtime() - lastStart < MIN_INTERVAL_MS) {
            return;
        }
        WeakReference<Activity> target = new WeakReference<>(activity);
        MAIN.postDelayed(() -> start(target.get()), START_DELAY_MS);
    }

    /** Called from onStop of the main activity's base class; never leaves the page behind. */
    public static void onActivityStopped(Activity activity) {
        if (resumed != null && resumed.get() == activity) {
            resumed = null;
        }
        if (host != null && host.get() == activity) {
            finish("stopped");
        }
    }

    static boolean isHidden(WebView webView) {
        for (View view = webView; view != null; ) {
            if (view == container) {
                return true;
            }
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        }
        return false;
    }

    static String describeParents(View view) {
        StringBuilder out = new StringBuilder();
        for (Object v = view; v instanceof View && out.length() < 400; v = ((View) v).getParent()) {
            out.append(v.getClass().getSimpleName()).append('#').append(((View) v).getId()).append(v == container ? "*" : "").append(' ');
        }
        return out.append("container=").append(container == null ? "null" : container.getId()).toString();
    }

    static void onScriptDone() {
        MAIN.post(() -> finish("done"));
    }

    private static void start(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || container != null
                || resumed == null || resumed.get() != activity
                || SystemClock.elapsedRealtime() - lastStart < MIN_INTERVAL_MS) {
            return;
        }
        try {
            Object manager = fragmentManager(activity);
            Object page = newFragment();
            ViewGroup content = activity.findViewById(android.R.id.content);
            if (manager == null || page == null || content == null) {
                return;
            }
            FrameLayout box = new FrameLayout(activity);
            box.setId(unusedId(activity));
            box.setAlpha(0f);
            box.setClickable(false);
            box.setFocusable(false);
            box.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            box.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            content.addView(box, new FrameLayout.LayoutParams(1, 1));
            container = box;
            if (!addFragment(manager, box.getId(), page, FRAGMENT_TAG)) {
                content.removeView(box);
                container = null;
                return;
            }
            lastStart = SystemClock.elapsedRealtime();
            host = new WeakReference<>(activity);
            fragmentManager = manager;
            fragment = page;
            MAIN.postDelayed(TIMEOUT, TIMEOUT_MS);
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Background page started");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Background page failed to start", t);
            finish("error");
        }
    }

    private static void finish(String why) {
        MAIN.removeCallbacks(TIMEOUT);
        Object manager = fragmentManager;
        Object page = fragment;
        FrameLayout box = container;
        fragmentManager = null;
        fragment = null;
        host = null;
        if (manager != null && page != null) {
            try {
                removeFragment(manager, page);
            } catch (Throwable t) {
                Log.w(TAG, "Background page failed to stop", t);
            }
        }
        if (box != null) {
            // Let the fragment transaction detach the WebView before the container goes away.
            MAIN.postDelayed(() -> {
                if (box.getParent() instanceof ViewGroup) {
                    ((ViewGroup) box.getParent()).removeView(box);
                }
                if (container == box) {
                    container = null;
                }
            }, 1_000L);
        }
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Background page finished: " + why);
        }
        Activity current = resumed == null ? null : resumed.get();
        if (current != null) {
            WeakReference<Activity> target = new WeakReference<>(current);
            MAIN.postDelayed(() -> start(target.get()), REPEAT_MS);
        }
    }

    /**
     * The fragment manager finds its container with findViewById, so the id must not match any
     * view in the activity (generated ids are small numbers that the app also uses).
     */
    private static int unusedId(Activity activity) {
        int id = 0x00534d00;
        while (activity.findViewById(id) != null) {
            id++;
        }
        return id;
    }

    private static Object newFragment() throws ReflectiveOperationException {
        Class<?> type = Class.forName(FRAGMENT_CLASS);
        Field companion = type.getField("Companion");
        Object factory = companion.get(null);
        return factory.getClass().getMethod("newInstance", String.class, String.class)
                .invoke(factory, MODULES, URL);
    }

    // The three methods below are replaced by the patch with calls into the app's (obfuscated)
    // AndroidX fragment classes, resolved from SmartNews' own Points tab at patch time.

    /** Returns the activity's FragmentManager, or null. */
    public static Object fragmentManager(Activity activity) {
        return null;
    }

    /** Adds the fragment to the container and commits, allowing state loss. */
    public static boolean addFragment(Object manager, int containerId, Object fragment, String tag) {
        return false;
    }

    /** Removes the fragment and commits, allowing state loss. */
    public static boolean removeFragment(Object manager, Object fragment) {
        return false;
    }
}
