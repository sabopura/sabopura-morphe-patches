package io.github.sabopura.playbackprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Version-independent check of the shared player button, quick menu and settings screen inside
 * the patched Twitch process. Uses only extension classes and resource names added by the
 * patches, so it runs on every supported Twitch version.
 *
 * Arguments: {@code -e expect all|danmaku|chat} selects which feature flags must be on.
 * Screenshots are written to the app's external files directory.
 */
public final class MenuProbe extends Instrumentation {
    private static final String UI = "io.github.sabopura.morphe.extension.twitch.ui.";
    private final StringBuilder report = new StringBuilder();
    private int failures;
    private String expect = "all";
    private File out;

    interface Check {
        void run() throws Throwable;
    }

    private void check(String name, Check action) {
        try {
            action.run();
            report.append("PASS ").append(name).append('\n');
        } catch (Throwable t) {
            failures++;
            report.append("FAIL ").append(name).append('\n');
            java.io.StringWriter w = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(w));
            report.append(w).append('\n');
        }
    }

    @Override
    public void onCreate(Bundle args) {
        super.onCreate(args);
        if (args != null && args.getString("expect") != null) expect = args.getString("expect");
        start();
    }

    private Class<?> type(Context context, String name) throws Exception {
        return Class.forName(name, true, context.getClassLoader());
    }

    private boolean flag(Context context, String name) throws Exception {
        return (Boolean) type(context, UI + "Features").getMethod(name).invoke(null);
    }

    private void save(Bitmap bitmap, String name) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(new File(out, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        }
    }

    private void screenshot(String name) throws Exception {
        Thread.sleep(1500);
        waitForIdleSync();
        Bitmap shot = getUiAutomation().takeScreenshot();
        if (shot == null) throw new AssertionError("No screenshot for " + name);
        save(shot, name);
        shot.recycle();
    }

    @Override
    public void onStart() {
        Context context = getTargetContext();
        out = context.getExternalFilesDir("menu-probe");
        if (out != null) out.mkdirs();

        check("feature flags (" + expect + ")", () -> {
            boolean danmaku = flag(context, "danmaku"), chat = flag(context, "chat"),
                    player = flag(context, "player");
            boolean ok = "all".equals(expect) ? danmaku && chat && player
                    : "danmaku".equals(expect) ? danmaku && !chat && !player
                    : chat && !danmaku;
            if (!ok) throw new AssertionError("danmaku=" + danmaku + " chat=" + chat + " player=" + player);
        });

        runOnMainSync(() -> check("player controls contain exactly one menu button", () -> {
            Context themed = new ContextThemeWrapper(context, context.getApplicationInfo().theme);
            int layout = context.getResources().getIdentifier("player_control_overlay", "layout",
                    context.getPackageName());
            View overlay = LayoutInflater.from(themed).inflate(layout, null, false);
            int id = context.getResources().getIdentifier("sabopura_player_menu", "id", context.getPackageName());
            View button = overlay.findViewById(id);
            if (button == null || !button.getClass().getName().equals(UI + "PlayerMenuButton"))
                throw new AssertionError("menu button missing: " + button);
            for (String old : new String[]{"sabopura_chat_settings", "sabopura_danmaku_toggle"}) {
                int oldId = context.getResources().getIdentifier(old, "id", context.getPackageName());
                if (oldId != 0 && overlay.findViewById(oldId) != null)
                    throw new AssertionError("old button still present: " + old);
            }
            overlay.setBackgroundColor(0xFF000000);
            int width = 1080, height = 608;
            overlay.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            overlay.layout(0, 0, width, height);
            if (button.getWidth() <= 0 || button.getHeight() <= 0)
                throw new AssertionError("menu button has no size");
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            overlay.draw(new Canvas(bitmap));
            save(bitmap, "controls");
            bitmap.recycle();
            report.append("INFO button at ").append(button.getLeft()).append(',').append(button.getTop())
                    .append(' ').append(button.getWidth()).append('x').append(button.getHeight()).append('\n');
        }));

        Activity[] activity = new Activity[1];
        check("launch Twitch", () -> {
            // The launcher activity is a trampoline; track whichever activity ends up resumed.
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
            activity[0] = resumed[0];
            if (activity[0] == null) throw new AssertionError("no resumed Twitch activity");
            report.append("INFO activity ").append(activity[0].getClass().getName()).append((char) 10);
        });

        if (activity[0] != null) {
            String[][] screens = {
                    {"player menu", "PlayerMenu", "show", null, "menu"},
                    {"settings screen", "SettingsScreen", "show", null, "settings"},
                    {"danmaku settings", "SettingsScreen", "show", "danmaku", "settings-danmaku"},
                    {"settings search", "SettingsScreen", "showSearch", "NG", "settings-search"},
            };
            for (String[] screen : screens) {
                check("open " + screen[0], () -> {
                    Throwable[] error = new Throwable[1];
                    runOnMainSync(() -> {
                        try {
                            Class<?> owner = type(context, UI + screen[1]);
                            if (screen[3] == null) owner.getMethod(screen[2], Context.class).invoke(null, activity[0]);
                            else owner.getMethod(screen[2], Context.class, String.class)
                                    .invoke(null, activity[0], screen[3]);
                        } catch (Throwable t) {
                            error[0] = t;
                        }
                    });
                    if (error[0] != null) throw error[0];
                    screenshot(screen[4]);
                    dismissDialogs(activity[0]);
                });
            }
            runOnMainSync(() -> activity[0].finish());
        }

        Bundle result = new Bundle();
        result.putString(Instrumentation.REPORT_KEY_STREAMRESULT, report + "RESULT failed=" + failures + "\n");
        finish(failures == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    /** Presses back until our dialogs are gone. */
    private void dismissDialogs(Activity activity) throws Exception {
        for (int i = 0; i < 3 && !activity.hasWindowFocus(); i++) {
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            Thread.sleep(800);
        }
        if (!activity.hasWindowFocus()) throw new AssertionError("dialog did not close with back");
    }
}
