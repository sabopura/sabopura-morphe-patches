package io.github.sabopura.morphe.extension.twitch.player;

import io.github.sabopura.morphe.extension.twitch.ui.Text;
import android.app.AlertDialog;
import android.content.*;
import android.net.Uri;
import android.os.SystemClock;
import android.widget.*;

import io.github.sabopura.morphe.extension.twitch.chat.*;

import java.lang.ref.WeakReference;
import java.util.*;

/** Uses only the stable, public IVS Player methods; no private account state is inspected. */
public final class PlayerTools {
    private static WeakReference<Object> player = new WeakReference<>(null);
    private static volatile Context app;
    private static volatile String vodId = "";
    private static long sleepAt;
    private static final Runnable SLEEP =
            () -> {
                sleepAt = 0;
                try {
                    invoke("pause", new Class<?>[0]);
                } catch (Exception ignored) {
                }
            };

    private PlayerTools() {}

    public static void initialize(Context context) {
        Text.use(context);
        if (context != null) {
            app = context.getApplicationContext();
            ViewCustomization.initialize(context);
        }
    }

    public static void onPlay(Object value) {
        player = new WeakReference<>(value);
    }

    public static void onRelease(Object value) {
        if (player.get() == value) player.clear();
    }

    public static void onVod(Object model) {
        try {
            Object id = model.getClass().getMethod("getId").invoke(model);
            vodId = id == null ? "" : id.toString().replaceFirst("^v", "");
        } catch (Exception ignored) {
            vodId = "";
        }
    }

    public static void onVod(Object model, Object channel) {
        onVod(model);
        try {
            ChatEnhancements.onChannelChanged(
                    String.valueOf(channel.getClass().getMethod("getId").invoke(channel)),
                    String.valueOf(channel.getClass().getMethod("getName").invoke(channel)));
        } catch (Exception ignored) {
        }
    }

    public static void onLive() {
        vodId = "";
    }

    public static String vodId() {
        return vodId;
    }

    public static int commentTime(int seconds) {
        int offset = app == null ? 0 : ChatSettings.preferences(app).getInt("vod_sync_" + vodId, 0);
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, (long) seconds + offset));
    }

    public static SharedPreferences preferences() {
        return app == null ? null : ChatSettings.preferences(app);
    }

    private static Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Object value = player.get();
        if (value == null) throw new IllegalStateException("No active player");
        return Class.forName("com.amazonaws.ivs.player.Player")
                .getMethod(name, types)
                .invoke(value, args);
    }

    private static long number(String name) throws Exception {
        return ((Number) invoke(name, new Class<?>[0])).longValue();
    }

    public static void seek(long position) throws Exception {
        long duration = number("getDuration");
        invoke(
                "seekTo",
                new Class<?>[] {long.class},
                Math.max(0, duration > 0 ? Math.min(duration, position) : position));
    }

    public static boolean hasPlayer() {
        return player.get() != null;
    }

    /** Current playback rate, or NaN when no player is active. */
    public static float playbackRate() {
        try {
            return ((Number) invoke("getPlaybackRate", new Class<?>[0])).floatValue();
        } catch (Exception e) {
            return Float.NaN;
        }
    }

    public static void playbackRate(float rate) throws Exception {
        if (!Float.isFinite(rate)) throw new IllegalArgumentException();
        invoke("setPlaybackRate", new Class<?>[] {float.class}, Math.max(.25f, Math.min(2f, rate)));
    }

    public static void relativeSeek(Context c, boolean forward) {
        run(
                c,
                () -> {
                    int seconds =
                            ChatSettings.preferences(c)
                                    .getInt(
                                            forward ? "forward_seek" : "rewind_seek",
                                            forward ? 30 : 10);
                    seek(number("getPosition") + (forward ? 1 : -1) * seconds * 1000L);
                });
    }

    public static void setSleepMinutes(int minutes) {
        setSleepDuration(Math.max(0, Math.min(1440, minutes)) * 60000L);
    }

    public static void setSleepDuration(long duration) {
        duration = Math.max(0, Math.min(86400000L, duration));
        PublicApi.UI.removeCallbacks(SLEEP);
        sleepAt = duration == 0 ? 0 : SystemClock.elapsedRealtime() + duration;
        if (duration > 0) PublicApi.UI.postDelayed(SLEEP, duration);
    }

    public static long sleepRemaining() {
        return sleepAt == 0 ? 0 : Math.max(0, sleepAt - SystemClock.elapsedRealtime());
    }

    public static void reload(Context c) {
        run(
                c,
                () -> {
                    String path = (String) invoke("getPath", new Class<?>[0]);
                    if (path == null || !path.startsWith("https://"))
                        throw new IllegalStateException();
                    long position = number("getPosition"), duration = number("getDuration");
                    invoke("load", new Class<?>[] {Uri.class}, LiveProxy.forReload(path));
                    if (duration > 0) seek(position);
                    invoke("play", new Class<?>[0]);
                });
    }

    public static void stats(Context c) {
        TextView view = new TextView(c);
        view.setPadding(30, 24, 30, 24);
        AlertDialog dialog =
                new AlertDialog.Builder(c)
                        .setTitle(label("再生統計", "Playback statistics"))
                        .setView(view)
                        .setPositiveButton(android.R.string.ok, null)
                        .create();
        Runnable tick =
                new Runnable() {
                    public void run() {
                        if (!dialog.isShowing()) return;
                        try {
                            view.setText(
                                    "Position: "
                                            + number("getPosition") / 1000
                                            + " s\nDuration: "
                                            + number("getDuration") / 1000
                                            + " s\nBuffer: "
                                            + Math.max(
                                                    0,
                                                    number("getBufferedPosition")
                                                            - number("getPosition"))
                                            + " ms\nLive latency: "
                                            + number("getLiveLatency")
                                            + " ms\nBitrate: "
                                            + number("getAverageBitrate") / 1000
                                            + " kbps\nRate: "
                                            + invoke("getPlaybackRate", new Class<?>[0])
                                            + "×");
                        } catch (Exception e) {
                            view.setText(label("再生中の動画がありません", "No active player"));
                        }
                        PublicApi.UI.postDelayed(this, 1000);
                    }
                };
        dialog.setOnDismissListener(d -> PublicApi.UI.removeCallbacks(tick));
        dialog.show();
        tick.run();
    }

    public static void sync(Context c) {
        if (vodId.isEmpty()) {
            Toast.makeText(c, label("VODを開いてください", "Open a VOD first"), Toast.LENGTH_SHORT).show();
            return;
        }
        final String key = "vod_sync_" + vodId;
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(1);
        TextView label = new TextView(c);
        box.addView(label);
        SeekBar seek = new SeekBar(c);
        seek.setMax(240);
        int saved = ChatSettings.preferences(c).getInt(key, 0);
        seek.setProgress(Math.max(0, Math.min(240, saved + 120)));
        label.setText(saved + " s");
        box.addView(seek);
        seek.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    public void onStartTrackingTouch(SeekBar s) {}

                    public void onStopTrackingTouch(SeekBar s) {}

                    public void onProgressChanged(SeekBar s, int value, boolean user) {
                        label.setText((value - 120) + " s");
                    }
                });
        new AlertDialog.Builder(c)
                .setTitle(label("VODチャット同期（正:先のコメント）", "VOD chat offset (+ shows later comments)"))
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(
                        android.R.string.ok,
                        (d, i) ->
                                ChatSettings.preferences(c)
                                        .edit()
                                        .putInt(key, seek.getProgress() - 120)
                                        .apply())
                .show();
    }

    public interface Operation {
        void run() throws Exception;
    }

    public static void run(Context c, Operation action) {
        try {
            action.run();
        } catch (Exception e) {
            Toast.makeText(
                            c,
                            label(
                                    "再生中の動画ではこの操作を実行できません",
                                    "This action is unavailable for the current player"),
                            Toast.LENGTH_SHORT)
                    .show();
        }
    }

    private static String label(String ja, String en) {
        return Text.label(ja, en);
    }
}
