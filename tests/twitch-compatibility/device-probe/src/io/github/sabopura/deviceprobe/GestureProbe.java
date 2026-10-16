package io.github.sabopura.deviceprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Builds Twitch's real theatre views (drag container, theatre layout, the patched player root
 * and the player controls on top) over a Twitch activity, then swipes with injected touches
 * the way a finger would, and checks brightness, volume and that taps still reach Twitch.
 */
public class GestureProbe extends Instrumentation {
    private static final String TAG = "GestureProbe";
    private final StringBuilder report = new StringBuilder();
    private int failed;
    private Activity activity;
    private View frame, draggable, root;
    /** The theatre's video_presenter_container, where Twitch puts the landscape chat too. */
    private FrameLayout presenter;
    private int taps;

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
        boolean oldBrightness = prefs.getBoolean("brightness_gesture", false), oldVolume = prefs.getBoolean("volume_gesture", false);
        AudioManager audio = (AudioManager) target.getSystemService(Context.AUDIO_SERVICE);
        int oldLevel = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        try {
            prefs.edit().putBoolean("brightness_gesture", true).putBoolean("volume_gesture", true).commit();
            launch(target);
            build(target);
            int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, max / 2, 0);
            int[] at = new int[2];
            frame.getLocationOnScreen(at);
            float w = frame.getWidth(), h = frame.getHeight();
            line("INFO player at " + at[0] + "," + at[1] + " " + (int) w + "x" + (int) h + " activity " + activity.getClass().getName());

            float before = brightness();
            swipe(at[0] + w * .08f, at[1] + h * .7f, at[0] + w * .08f, at[1] + h * .3f, false);
            float after = brightness();
            check(after > Math.max(before, 0) + .1f, "left edge swipe up raises brightness: " + before + " -> " + after);
            check(taps == 0, "the swipe did not reach the player controls (taps " + taps + ")");

            int level = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            swipe(at[0] + w * .92f, at[1] + h * .3f, at[0] + w * .92f, at[1] + h * .7f, false);
            int lowered = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            check(lowered < level, "right edge swipe down lowers volume: " + level + " -> " + lowered);

            before = brightness();
            level = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            tap(at[0] + w * .5f, at[1] + h * .5f);
            check(taps == 1, "a tap in the middle reaches the controls (taps " + taps + ")");
            tap(at[0] + w * .08f, at[1] + h * .5f);
            check(taps == 2, "a tap at the left edge reaches the controls (taps " + taps + ")");
            swipe(at[0] + w * .05f, at[1] + h * .5f, at[0] + w * .45f, at[1] + h * .55f, false);
            check(brightness() == before, "a sideways swipe at the edge leaves brightness alone");
            swipe(at[0] + w * .5f, at[1] + h * .7f, at[0] + w * .5f, at[1] + h * .3f, false);
            check(brightness() == before && audio.getStreamVolume(AudioManager.STREAM_MUSIC) == level,
                    "a vertical swipe in the middle is left to Twitch");
            swipe(at[0] + w * .08f, at[1] + h * .13f, at[0] + w * .08f, at[1] + h * .6f, false);
            check(brightness() == before, "a swipe from the top band (controls) is left to Twitch");

            // While the finger is down: the level label is drawn over the video.
            swipe(at[0] + w * .92f, at[1] + h * .75f, at[0] + w * .92f, at[1] + h * .35f, true);

            // The screen the theatre opened over (Twitch's feed, a list) stays underneath and can
            // scroll: it must not take the swipe.
            final float fw = w, fh = h;
            android.widget.ScrollView[] below = new android.widget.ScrollView[1];
            runOnMainSync(() -> {
                below[0] = list();
                ((FrameLayout) root).addView(below[0], 0, new FrameLayout.LayoutParams(-1, -1));
            });
            waitForIdleSync();
            Thread.sleep(300);
            level = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            swipe(at[0] + w * .92f, at[1] + h * .3f, at[0] + w * .92f, at[1] + h * .7f, false);
            int underneath = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            check(underneath < level, "a list under the theatre does not stop the swipe: " + level + " -> " + underneath);
            runOnMainSync(() -> ((FrameLayout) root).removeView(below[0]));
            waitForIdleSync();

            // Landscape chat drawn over the video, inside the theatre like Twitch's: a scrolling
            // list under the finger keeps the swipe.
            android.widget.ScrollView[] chat = new android.widget.ScrollView[1];
            runOnMainSync(() -> {
                chat[0] = list();
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) (fw * .3f), (int) fh);
                lp.gravity = android.view.Gravity.END | android.view.Gravity.TOP;
                presenter.addView(chat[0], lp);
            });
            waitForIdleSync();
            Thread.sleep(300);
            level = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            swipe(at[0] + w * .92f, at[1] + h * .75f, at[0] + w * .92f, at[1] + h * .25f, false);
            int[] scrolled = new int[1];
            runOnMainSync(() -> scrolled[0] = chat[0].getScrollY());
            check(audio.getStreamVolume(AudioManager.STREAM_MUSIC) == level && scrolled[0] > 0,
                    "chat over the video scrolls instead of changing volume (scrollY " + scrolled[0] + ")");
            runOnMainSync(() -> presenter.removeView(chat[0]));
            waitForIdleSync();

            prefs.edit().putBoolean("brightness_gesture", false).commit();
            swipe(at[0] + w * .08f, at[1] + h * .3f, at[0] + w * .08f, at[1] + h * .7f, false);
            check(brightness() == before, "switched off: the left edge does nothing");
            prefs.edit().putBoolean("brightness_gesture", true).commit();

            // The mini player: the draggable part is smaller than its container.
            runOnMainSync(() -> {
                ViewGroup.LayoutParams lp = draggable.getLayoutParams();
                lp.width = ((View) draggable.getParent()).getWidth() * 2 / 5;
                lp.height = ((View) draggable.getParent()).getHeight() * 2 / 5;
                draggable.setLayoutParams(lp);
            });
            waitForIdleSync();
            Thread.sleep(500);
            frame.getLocationOnScreen(at);
            w = frame.getWidth();
            h = frame.getHeight();
            swipe(at[0] + w * .08f, at[1] + h * .7f, at[0] + w * .08f, at[1] + h * .3f, false);
            check(brightness() == before, "the mini player gets no swipes (" + (int) w + "x" + (int) h + ")");

            runOnMainSync(() -> ((ViewGroup) root.getParent()).removeView(root));
            Thread.sleep(2500);
            check(brightness() < 0, "closing the player gives the screen its own brightness back: " + brightness());
        } catch (Throwable t) {
            fail("crash " + t + " " + Log.getStackTraceString(t));
        } finally {
            prefs.edit().putBoolean("brightness_gesture", oldBrightness).putBoolean("volume_gesture", oldVolume).commit();
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, oldLevel, 0);
        }
        if (activity != null) runOnMainSync(() -> activity.finish());
        line("RESULT failed=" + failed);
        result.putString(Instrumentation.REPORT_KEY_STREAMRESULT, report.toString());
        finish(failed == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    /** A list of 80 lines that scrolls vertically. */
    private android.widget.ScrollView list() {
        android.widget.ScrollView list = new android.widget.ScrollView(activity);
        android.widget.LinearLayout lines = new android.widget.LinearLayout(activity);
        lines.setOrientation(android.widget.LinearLayout.VERTICAL);
        for (int i = 0; i < 80; i++) {
            android.widget.TextView line = new android.widget.TextView(activity);
            line.setText("chat line " + i);
            line.setTextColor(0xFFFFFFFF);
            lines.addView(line);
        }
        list.addView(lines);
        return list;
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
                draggable = theatre.findViewById(id(c, "draggable_layout", "id"));
                presenter = theatre.findViewById(id(c, "video_presenter_container", "id"));
                View coordinator = inflater.inflate(id(c, "theatre_coordinator", "layout"), presenter, false);
                presenter.addView(coordinator);
                ViewGroup pane = coordinator.findViewById(id(c, "player_pane", "id"));
                frame = inflater.inflate(id(c, "player_view_delegate", "layout"), pane, false);
                pane.addView(frame);
                ViewGroup overlay = coordinator.findViewById(id(c, "player_overlay_container", "id"));
                View controls = inflater.inflate(id(c, "player_control_overlay", "layout"), overlay, false);
                overlay.addView(controls);
                // Twitch shows and hides its controls on a tap anywhere over the video.
                controls.setOnClickListener(v -> taps++);
                FrameLayout host = new FrameLayout(activity);
                host.setBackgroundColor(0xFF18181B);
                host.setClickable(true);
                host.setPadding(0, 300, 0, 0);
                host.addView(theatre, new FrameLayout.LayoutParams(-1, -1));
                root = host;
                activity.addContentView(host, new ViewGroup.LayoutParams(-1, -1));
                line("INFO player root class " + frame.getClass().getName());
            } catch (Throwable t) {
                error[0] = t;
            }
        });
        if (error[0] != null) throw error[0];
        waitForIdleSync();
        Thread.sleep(1500);
    }

    private float brightness() {
        float[] value = new float[1];
        runOnMainSync(() -> value[0] = activity.getWindow().getAttributes().screenBrightness);
        return value[0];
    }

    private void tap(float x, float y) throws Exception {
        long down = SystemClock.uptimeMillis();
        sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0));
        sendPointerSync(MotionEvent.obtain(down, down + 60, MotionEvent.ACTION_UP, x, y, 0));
        waitForIdleSync();
        Thread.sleep(400);
    }

    private void swipe(float x0, float y0, float x1, float y1, boolean shoot) throws Exception {
        long down = SystemClock.uptimeMillis();
        sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x0, y0, 0));
        int steps = 20;
        for (int i = 1; i <= steps; i++) {
            float f = i / (float) steps;
            sendPointerSync(MotionEvent.obtain(down, down + i * 16, MotionEvent.ACTION_MOVE,
                    x0 + (x1 - x0) * f, y0 + (y1 - y0) * f, 0));
        }
        if (shoot) {
            Thread.sleep(300);
            waitForIdleSync();
            Bitmap shot = getUiAutomation().takeScreenshot();
            File dir = getTargetContext().getExternalFilesDir("gesture-probe");
            if (shot != null && dir != null) {
                dir.mkdirs();
                try (FileOutputStream out = new FileOutputStream(new File(dir, "swipe.png"))) {
                    shot.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                line("INFO screenshot " + new File(dir, "swipe.png"));
            }
        }
        sendPointerSync(MotionEvent.obtain(down, down + steps * 16 + 16, MotionEvent.ACTION_UP, x1, y1, 0));
        waitForIdleSync();
        Thread.sleep(400);
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
