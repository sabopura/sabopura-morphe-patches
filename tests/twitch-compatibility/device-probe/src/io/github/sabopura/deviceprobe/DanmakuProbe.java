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
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Feeds Twitch 31.5.2's own parsed live chat message objects (fixture-specific obfuscated
 * names) to the danmaku entry point while Twitch's real player layout is on screen, and checks
 * what reaches the comment layer.
 */
public class DanmakuProbe extends Instrumentation {
    private static final String TAG = "DanmakuProbe";
    private final StringBuilder report = new StringBuilder();
    private int failed, serial;
    private Activity activity;
    private ClassLoader loader;
    private View overlay;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    private Class<?> type(String name) throws Exception {
        return Class.forName(name, true, loader);
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        Context target = getTargetContext();
        loader = target.getClassLoader();
        SharedPreferences prefs = target.getSharedPreferences("sabopura_twitch_danmaku", Context.MODE_PRIVATE);
        Map<String, ?> saved = prefs.getAll();
        try {
            prefs.edit().clear().putBoolean("enabled", true).putBoolean("show_names", true)
                    .putString("ng_words", "forbidden").putInt("max_length", 40).commit();
            type("io.github.sabopura.morphe.extension.twitch.danmaku.DanmakuSettings").getMethod("reload", Context.class).invoke(null, target);
            launch(target);
            build(target);
            Class<?> danmaku = type("io.github.sabopura.morphe.extension.twitch.danmaku.TwitchDanmaku");
            java.lang.reflect.Method on = danmaku.getMethod("onChatMessage", Object.class);

            long now = System.currentTimeMillis();
            on.invoke(null, message("Viewer", now, text("hello danmaku "), emote("25", "Kappa"), text(" from Twitch")));
            Thread.sleep(600);
            check(active() == 1, "a live message reaches the comment layer (" + active() + ")");
            on.invoke(null, message("Old", now - 60000, text("an old message from history")));
            on.invoke(null, message("Rude", now, text("this is forbidden here")));
            on.invoke(null, message("Long", now, text("a very long message that goes on and on and on past the limit")));
            Thread.sleep(400);
            check(active() == 1, "history, muted words and long messages are left out (" + active() + ")");
            Object twice = message("Again", now, text("only once please"));
            on.invoke(null, twice);
            on.invoke(null, twice);
            Thread.sleep(400);
            check(active() == 2, "the same message is shown once (" + active() + ")");
            line("INFO before: " + state());
            for (int i = 0; i < 6; i++) on.invoke(null, message("Fan" + i, System.currentTimeMillis(), text("comment number " + i)));
            Thread.sleep(500);
            int flowing = active();
            line("INFO after: " + state());
            Thread.sleep(700);
            waitForIdleSync();
            Bitmap shot = getUiAutomation().takeScreenshot();
            File dir = target.getExternalFilesDir("danmaku-probe");
            if (shot != null && dir != null) {
                dir.mkdirs();
                try (FileOutputStream out = new FileOutputStream(new File(dir, "danmaku.png"))) {
                    shot.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                line("INFO screenshot " + new File(dir, "danmaku.png"));
            }
            check(flowing >= 6, "several comments flow at once (" + flowing + ")");

            prefs.edit().putBoolean("enabled", false).commit();
            type("io.github.sabopura.morphe.extension.twitch.danmaku.DanmakuSettings").getMethod("reload", Context.class).invoke(null, target);
            danmaku.getMethod("settingsChanged").invoke(null);
            on.invoke(null, message("Late", System.currentTimeMillis(), text("should not show")));
            Thread.sleep(400);
            check(active() == 0, "switched off: nothing is shown");
        } catch (Throwable t) {
            fail("crash " + t + " " + Log.getStackTraceString(t));
        } finally {
            SharedPreferences.Editor e = prefs.edit().clear();
            for (Map.Entry<String, ?> entry : saved.entrySet()) {
                Object v = entry.getValue();
                if (v instanceof Boolean) e.putBoolean(entry.getKey(), (Boolean) v);
                else if (v instanceof Integer) e.putInt(entry.getKey(), (Integer) v);
                else if (v instanceof String) e.putString(entry.getKey(), (String) v);
            }
            e.commit();
            try {
                type("io.github.sabopura.morphe.extension.twitch.danmaku.DanmakuSettings").getMethod("reload", Context.class).invoke(null, target);
            } catch (Exception ignored) {
            }
        }
        if (activity != null) runOnMainSync(() -> activity.finish());
        line("RESULT failed=" + failed);
        result.putString(Instrumentation.REPORT_KEY_STREAMRESULT, report.toString());
        finish(failed == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private Object text(String value) throws Exception {
        Object flags = type("vw1").getConstructor(int.class, int.class, int.class, int.class).newInstance(0, 0, 0, 0);
        return type("ttp").getConstructor(String.class, type("vw1")).newInstance(value, flags);
    }

    private Object emote(String id, String code) throws Exception {
        return type("ytp").getConstructor(String.class, String.class).newInstance(id, code);
    }

    private Object message(String name, long sentAt, Object... tokens) throws Exception {
        Map<String, String> tags = new HashMap<>();
        tags.put("id", "probe-" + (serial++) + "-" + sentAt);
        tags.put("display-name", name);
        tags.put("tmi-sent-ts", String.valueOf(sentAt));
        List<Object> list = new ArrayList<>();
        for (Object t : tokens) list.add(t);
        Object tuid = type("tv.twitch.android.models.Tuid").getConstructor(String.class).newInstance("123");
        Object sender = type("zb6").getConstructor(type("tv.twitch.android.models.Tuid"), String.class, String.class)
                .newInstance(tuid, name.toLowerCase(), name);
        Class<?> message = type("l56");
        java.lang.reflect.Constructor<?> c = message.getConstructors()[0];
        return c.newInstance(tags.get("id"), type("tb6").getField("a").get(null), tags, new ArrayList<>(), 0xFF9146FF,
                sender, list, null, sentAt / 1000, false, false, false, false, null, null, null);
    }

    private String state() {
        String[] out = new String[1];
        runOnMainSync(() -> {
            try {
                Field pending = overlay.getClass().getDeclaredField("pending");
                pending.setAccessible(true);
                Field lanes = overlay.getClass().getDeclaredField("lanes");
                lanes.setAccessible(true);
                Object allocator = lanes.get(overlay);
                java.lang.reflect.Method laneCount = allocator.getClass().getDeclaredMethod("laneCount");
                laneCount.setAccessible(true);
                Object count = laneCount.invoke(allocator);
                out[0] = overlay.getWidth() + "x" + overlay.getHeight() + " shown=" + overlay.isShown()
                        + " attached=" + overlay.isAttachedToWindow() + " lanes=" + count
                        + " pending=" + ((java.util.Collection<?>) pending.get(overlay)).size();
            } catch (Exception e) {
                out[0] = String.valueOf(e);
            }
        });
        return out[0];
    }

    private int active() throws Exception {
        int[] size = new int[1];
        Exception[] error = new Exception[1];
        runOnMainSync(() -> {
            try {
                Field f = overlay.getClass().getDeclaredField("active");
                f.setAccessible(true);
                size[0] = ((List<?>) f.get(overlay)).size();
            } catch (Exception e) {
                error[0] = e;
            }
        });
        if (error[0] != null) throw error[0];
        return size[0];
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
                LayoutInflater inflater = LayoutInflater.from(activity);
                View theatre = inflater.inflate(id(c, "theatre_container_layout", "layout"), null, false);
                ViewGroup presenter = theatre.findViewById(id(c, "video_presenter_container", "id"));
                View coordinator = inflater.inflate(id(c, "theatre_coordinator", "layout"), presenter, false);
                presenter.addView(coordinator);
                ViewGroup pane = coordinator.findViewById(id(c, "player_pane", "id"));
                View frame = inflater.inflate(id(c, "player_view_delegate", "layout"), pane, false);
                frame.setBackgroundColor(0xFF2B2B35);
                pane.addView(frame);
                overlay = frame.findViewById(id(c, "sabopura_danmaku_overlay", "id"));
                FrameLayout host = new FrameLayout(activity);
                host.setBackgroundColor(0xFF18181B);
                host.setPadding(0, 300, 0, 0);
                host.addView(theatre, new FrameLayout.LayoutParams(-1, -1));
                activity.addContentView(host, new ViewGroup.LayoutParams(-1, -1));
                // The minimised-player overlay is shown until Twitch's presenter hides it.
                View minimized = theatre.findViewById(id(c, "minimized_overlay", "id"));
                if (minimized != null) minimized.setVisibility(View.GONE);
            } catch (Throwable t) {
                error[0] = t;
            }
        });
        if (error[0] != null) throw error[0];
        waitForIdleSync();
        Thread.sleep(1000);
        if (overlay == null) throw new AssertionError("no danmaku layer in the player");
        line("INFO danmaku layer " + overlay.getClass().getName() + " " + overlay.getWidth() + "x" + overlay.getHeight());
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
