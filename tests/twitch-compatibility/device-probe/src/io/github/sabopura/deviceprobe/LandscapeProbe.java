package io.github.sabopura.deviceprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.Map;

/**
 * The live theatre's landscape layout (theatre_coordinator_land) with Twitch's own
 * ConstraintSet (fixture names for 31.5.2: s9a, e = clone, s = guideline percent, a = apply).
 * Checks the chat width and background options of the Layout settings.
 */
public class LandscapeProbe extends Instrumentation {
    private static final String TAG = "LandscapeProbe";
    private final StringBuilder report = new StringBuilder();
    private int failed;
    private Activity activity;
    private ClassLoader loader;
    private ViewGroup layout, host;
    private View guideline, wrapper, inner;
    /** ConstraintSet and its guideline-percent setter: s9a/s on 31.5.2, el9/r on 30.7.2. */
    private String setClass = "s9a", percentMethod = "s";

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        if (arguments != null && arguments.getString("set") != null) setClass = arguments.getString("set");
        if (arguments != null && arguments.getString("percent") != null) percentMethod = arguments.getString("percent");
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        Context target = getTargetContext();
        loader = target.getClassLoader();
        SharedPreferences prefs = target.getSharedPreferences("sabopura_chat", Context.MODE_PRIVATE);
        Map<String, ?> saved = prefs.getAll();
        try {
            launch(target);
            build(target);
            prefs.edit().putBoolean("landscape_width_enabled", true).putInt("landscape_width", 40)
                    .putInt("landscape_split_width", 60).commit();
            check(near(guide(0.7f), .60f), "chat beside the video: 40% wide (" + guide(0.7f) + ")");
            check(near(guide(0.8f), .60f), "tablet default also follows the setting (" + guide(0.8f) + ")");
            check(near(guide(0.5f), .40f), "split layout: 60% chat (" + guide(0.5f) + ")");
            check(near(guide(1.0f), 1f), "no chat stays without chat (" + guide(1.0f) + ")");
            prefs.edit().putBoolean("landscape_width_enabled", false).commit();
            check(near(guide(0.7f), .70f), "switched off: Twitch's own width (" + guide(0.7f) + ")");

            Drawable before = background(wrapper);
            int original = before instanceof ColorDrawable ? ((ColorDrawable) before).getColor() : 0;
            prefs.edit().putBoolean("landscape_opacity_enabled", true).putInt("landscape_opacity", 20).commit();
            backgrounds();
            Drawable after = background(wrapper), innerAfter = background(inner);
            int color = after instanceof ColorDrawable ? ((ColorDrawable) after).getColor() : 0;
            line("INFO chat column " + Integer.toHexString(original) + " -> " + Integer.toHexString(color));
            check((color >>> 24) == 51 && (color & 0xFFFFFF) == (original & 0xFFFFFF),
                    "chat background 20% in Twitch's own shade");
            check(innerAfter instanceof ColorDrawable && ((ColorDrawable) innerAfter).getColor() == 0,
                    "the chat inside is clear, so the shades do not stack");
            prefs.edit().putBoolean("landscape_opacity_enabled", false).commit();
            backgrounds();
            check(background(wrapper) == before && background(inner) instanceof ColorDrawable
                    && ((ColorDrawable) background(inner)).getColor() == 0xFF112233, "switched off: backgrounds restored");
        } catch (Throwable t) {
            fail("crash " + t + " " + Log.getStackTraceString(t));
        } finally {
            SharedPreferences.Editor e = prefs.edit().clear();
            for (Map.Entry<String, ?> entry : saved.entrySet()) {
                Object v = entry.getValue();
                if (v instanceof Boolean) e.putBoolean(entry.getKey(), (Boolean) v);
                else if (v instanceof Integer) e.putInt(entry.getKey(), (Integer) v);
                else if (v instanceof Long) e.putLong(entry.getKey(), (Long) v);
                else if (v instanceof String) e.putString(entry.getKey(), (String) v);
            }
            e.commit();
        }
        if (activity != null) runOnMainSync(() -> activity.finish());
        line("RESULT failed=" + failed);
        result.putString(Instrumentation.REPORT_KEY_STREAMRESULT, report.toString());
        finish(failed == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) < .02f;
    }

    private Drawable background(View v) {
        Drawable[] d = new Drawable[1];
        runOnMainSync(() -> d[0] = v.getBackground());
        return d[0];
    }

    private void backgrounds() throws Exception {
        Class<?> tools = Class.forName("io.github.sabopura.morphe.extension.twitch.player.LayoutTools", true, loader);
        Exception[] error = new Exception[1];
        runOnMainSync(() -> {
            try {
                tools.getMethod("applyBackgrounds", View.class).invoke(null, host);
            } catch (Exception e) {
                error[0] = e;
            }
        });
        if (error[0] != null) throw error[0];
    }

    /** Applies Twitch's percent through its ConstraintSet and returns where the guideline ends up. */
    private float guide(float percent) throws Exception {
        Class<?> set = Class.forName(setClass, true, loader);
        Class<?> constraint = Class.forName("androidx.constraintlayout.widget.ConstraintLayout", true, loader);
        Exception[] error = new Exception[1];
        runOnMainSync(() -> {
            try {
                Object cs = set.getConstructor().newInstance();
                set.getMethod("e", constraint).invoke(cs, layout);
                set.getMethod(percentMethod, float.class, int.class).invoke(cs, percent, guideline.getId());
                set.getMethod("a", constraint).invoke(cs, layout);
            } catch (Exception e) {
                error[0] = e;
            }
        });
        if (error[0] != null) throw error[0];
        waitForIdleSync();
        Thread.sleep(300);
        float[] at = new float[1];
        runOnMainSync(() -> at[0] = guideline.getLeft() / (float) (layout.getWidth() - layout.getPaddingLeft() - layout.getPaddingRight()));
        return Math.round(at[0] * 1000) / 1000f;
    }

    private void launch(Context context) throws Exception {
        Activity[] resumed = new Activity[1];
        ((android.app.Application) context.getApplicationContext()).registerActivityLifecycleCallbacks(
                new android.app.Application.ActivityLifecycleCallbacks() {
                    public void onActivityResumed(Activity a) { resumed[0] = a; }
                    public void onActivityPaused(Activity a) { }
                    public void onActivityCreated(Activity a, Bundle b) { }
                    public void onActivityStarted(Activity a) { }
                    public void onActivityStopped(Activity a) { }
                    public void onActivitySaveInstanceState(Activity a, Bundle b) { }
                    public void onActivityDestroyed(Activity a) { if (resumed[0] == a) resumed[0] = null; }
                });
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivitySync(launch);
        for (int i = 0; i < 30 && (resumed[0] == null || resumed[0].isFinishing()); i++) Thread.sleep(300);
        Thread.sleep(3000);
        activity = resumed[0];
        if (activity == null) throw new AssertionError("no resumed Twitch activity");
    }

    private int id(Context c, String name, String type) {
        int id = c.getResources().getIdentifier(name, type, c.getPackageName());
        if (id == 0) throw new AssertionError("missing " + type + " " + name);
        return id;
    }

    private void build(Context c) throws Throwable {
        Throwable[] error = new Throwable[1];
        runOnMainSync(() -> {
            try {
                // The theatre as it is laid out in landscape, whatever the test screen's rotation.
                Configuration config = new Configuration(activity.getResources().getConfiguration());
                config.orientation = Configuration.ORIENTATION_LANDSCAPE;
                Context land = new ContextThemeWrapper(activity.createConfigurationContext(config),
                        activity.getApplicationInfo().theme);
                layout = (ViewGroup) LayoutInflater.from(land).inflate(id(c, "theatre_coordinator_land", "layout"), null, false);
                guideline = layout.findViewById(id(c, "chat_guideline", "id"));
                wrapper = layout.findViewById(id(c, "chat_wrapper", "id"));
                ViewGroup chat = layout.findViewById(id(c, "chat_view_container", "id"));
                inner = new FrameLayout(land);
                inner.setId(id(c, "chat_view_delegate_root", "id"));
                inner.setBackgroundColor(0xFF112233);
                chat.addView(inner, new FrameLayout.LayoutParams(-1, -1));
                host = new FrameLayout(land);
                host.addView(layout, new FrameLayout.LayoutParams(-1, -1));
                activity.addContentView(host, new ViewGroup.LayoutParams(-1, -1));
            } catch (Throwable t) {
                error[0] = t;
            }
        });
        if (error[0] != null) throw error[0];
        waitForIdleSync();
        Thread.sleep(800);
    }

    private void check(boolean ok, String what) {
        if (ok) line("PASS " + what);
        else fail(what);
    }

    private void fail(String what) {
        failed++;
        line("FAIL " + what);
    }

    private void line(String text) {
        Log.i(TAG, text);
        report.append(text).append('\n');
    }
}
