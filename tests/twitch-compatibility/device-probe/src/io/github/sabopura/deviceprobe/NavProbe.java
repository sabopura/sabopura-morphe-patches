package io.github.sabopura.deviceprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Inflates Twitch's real home screen frame (viewer_landing), applies the window insets the way
 * Twitch does, and checks the navigation bar positions of the Layout settings.
 */
public class NavProbe extends Instrumentation {
    private static final String TAG = "NavProbe";
    private final StringBuilder report = new StringBuilder();
    private int failed;
    private Activity activity;
    private View root, nav, body;
    private int statusBar, navigationBar;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        Context target = getTargetContext();
        SharedPreferences prefs = target.getSharedPreferences("sabopura_chat", Context.MODE_PRIVATE);
        String old = prefs.getString("navigation_position", "default");
        try {
            launch(target);
            build(target);
            Class<?> tools = Class.forName("io.github.sabopura.morphe.extension.twitch.player.LayoutTools", true, target.getClassLoader());
            for (String mode : new String[]{"default", "top", "hidden", "default"}) {
                prefs.edit().putString("navigation_position", mode).commit();
                runOnMainSync(() -> {
                    try {
                        tools.getMethod("applyNavigation", View.class).invoke(null, root);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                waitForIdleSync();
                Thread.sleep(600);
                // A real layout pass runs the option again, as Twitch's screen would.
                runOnMainSync(() -> {
                    root.requestLayout();
                });
                waitForIdleSync();
                Thread.sleep(300);
                runOnMainSync(() -> {
                    try {
                        tools.getMethod("applyNavigation", View.class).invoke(null, root);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                waitForIdleSync();
                Thread.sleep(300);
                int[] n = new int[2], b = new int[2];
                int[] size = new int[3];
                runOnMainSync(() -> {
                    nav.getLocationOnScreen(n);
                    body.getLocationOnScreen(b);
                    size[0] = nav.getHeight();
                    size[1] = nav.getVisibility();
                    size[2] = root.getHeight();
                });
                line("INFO " + mode + " nav y=" + n[1] + " h=" + size[0] + " visibility=" + size[1] + " body y=" + b[1]
                        + " status=" + statusBar + " navigation=" + navigationBar + " screen=" + size[2]);
                shot("nav-" + mode);
                if ("top".equals(mode)) {
                    check(Math.abs(n[1] - statusBar) <= 1 && Math.abs(b[1] - (statusBar + size[0])) <= 1,
                            "top: the bar sits right under the status bar and the page under it");
                } else if ("hidden".equals(mode)) {
                    check(size[1] == View.GONE && Math.abs(b[1] - statusBar) <= 1, "hidden: no bar, the page starts under the status bar");
                } else {
                    check(size[1] == View.VISIBLE && Math.abs(n[1] + size[0] - (size[2] - navigationBar)) <= 1
                                    && Math.abs(b[1] - statusBar) <= 1, "default: the bar is at the bottom as Twitch placed it");
                }
            }
        } catch (Throwable t) {
            fail("crash " + t + " " + Log.getStackTraceString(t));
        } finally {
            prefs.edit().putString("navigation_position", old).commit();
        }
        if (activity != null) runOnMainSync(() -> activity.finish());
        line("RESULT failed=" + failed);
        result.putString(Instrumentation.REPORT_KEY_STREAMRESULT, report.toString());
        finish(failed == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void shot(String name) throws Exception {
        Bitmap shot = getUiAutomation().takeScreenshot();
        File dir = getTargetContext().getExternalFilesDir("nav-probe");
        if (shot != null && dir != null) {
            dir.mkdirs();
            try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) {
                shot.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
        }
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
                activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                // The sign-in screen behind may raise the keyboard and shrink the window.
                activity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                        | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
                if (activity.getCurrentFocus() != null) activity.getCurrentFocus().clearFocus();
                WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
                statusBar = insets.getInsets(WindowInsets.Type.statusBars()).top;
                navigationBar = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
                View landing = LayoutInflater.from(activity).inflate(id(c, "viewer_landing", "layout"), null, false);
                View layout = landing.findViewById(id(c, "viewer_landing_layout", "id"));
                nav = landing.findViewById(id(c, "viewer_landing_bottom_navigation_group", "id"));
                body = landing.findViewById(id(c, "fragment_container", "id"));
                body.setBackgroundColor(0xFF2E7D32);
                // What Twitch's insets listener does on this screen.
                layout.setPadding(0, statusBar, 0, layout.getPaddingBottom());
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) nav.getLayoutParams();
                lp.bottomMargin = navigationBar;
                nav.setLayoutParams(lp);
                nav.setVisibility(View.VISIBLE);
                landing.findViewById(id(c, "splash_overlay", "id")).setVisibility(View.GONE);
                FrameLayout host = new FrameLayout(activity);
                host.setClickable(true);
                host.addView(landing, new FrameLayout.LayoutParams(-1, -1));
                root = host;
                activity.addContentView(host, new ViewGroup.LayoutParams(-1, -1));
            } catch (Throwable t) {
                error[0] = t;
            }
        });
        if (error[0] != null) throw error[0];
        waitForIdleSync();
        Thread.sleep(1000);
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
