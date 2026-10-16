package io.github.sabopura.morphe.extension.twitch.player;

import io.github.sabopura.morphe.extension.twitch.ui.Text;
import android.app.AlertDialog;
import android.content.*;
import android.net.Uri;
import android.os.SystemClock;
import android.widget.*;

import io.github.sabopura.morphe.extension.twitch.chat.*;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Uses only the stable, public IVS Player methods; no private account state is inspected. */
public final class PlayerTools {
    /** A live stream played faster than 1x has caught up once less than this is loaded ahead. */
    private static final long CAUGHT_UP_MS = 2500;
    public static final int SPEED_SET = 0, SPEED_AT_LIVE_EDGE = 1, SPEED_CATCHING_UP = 2;
    /** Every IVS player that has started and is not released. */
    private static final List<Entry> PLAYERS = new ArrayList<>();
    private static WeakReference<Object> catchingUp = new WeakReference<>(null);
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

    private static final class Entry {
        final WeakReference<Object> player;
        long playedAt;
        String content = "";
        float rate = 1f;

        Entry(Object player) {
            this.player = new WeakReference<>(player);
        }
    }

    /** Finds (or adds) a player's entry and drops collected ones. Must hold PLAYERS. */
    private static Entry entry(Object value, boolean create) {
        Entry found = null;
        for (Iterator<Entry> it = PLAYERS.iterator(); it.hasNext(); ) {
            Entry e = it.next();
            Object p = e.player.get();
            if (p == null) it.remove();
            else if (p == value) found = e;
        }
        if (found != null || !create || value == null) return found;
        found = new Entry(value);
        PLAYERS.add(found);
        return found;
    }

    public static void onPlay(Object value) {
        synchronized (PLAYERS) {
            entry(value, true).playedAt = SystemClock.uptimeMillis();
        }
    }

    public static void onRelease(Object value) {
        synchronized (PLAYERS) {
            Entry e = entry(value, false);
            if (e != null) PLAYERS.remove(e);
        }
        if (catchingUp.get() == value) catchingUp.clear();
    }

    /**
     * Before the player loads something. IVS keeps the playback rate across loads, so a speed
     * picked for one VOD would otherwise carry over into the next stream.
     */
    public static void onLoad(Object value, Object source) {
        String content = contentOf(source);
        boolean reset;
        synchronized (PLAYERS) {
            Entry e = entry(value, true);
            reset = e.rate != 1f && !content.equals(e.content);
            if (reset) e.rate = 1f;
            e.content = content;
        }
        if (!reset) return;
        if (catchingUp.get() == value) catchingUp.clear();
        try {
            call(value, "setPlaybackRate", new Class<?>[] {float.class}, 1f);
        } catch (Exception ignored) {
        }
    }

    /** The stream or VOD a load is for, without the per-request token. */
    static String contentOf(Object source) {
        String text;
        try {
            text = source instanceof String ? (String) source
                    : String.valueOf(source.getClass().getMethod("getUri").invoke(source));
        } catch (Exception e) {
            return "";
        }
        String path = Uri.parse(text).getPath();
        // A proxy keeps the channel in its path too, so the path identifies the content.
        return path == null ? text : path.replaceAll("/+$", "");
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

    private static final java.util.regex.Pattern VOD_PATH =
            java.util.regex.Pattern.compile("^/vod/(?:v\\d+/)?(\\d{1,20})\\.m3u8$");

    /**
     * The VOD on screen, or "". What the player on screen has loaded decides, so a live preview
     * started elsewhere in the app changes nothing. Twitch's VOD chat setup is the fallback when
     * the player's content is unknown.
     */
    public static String vodId() {
        // Player state is read on the main thread only, where Twitch itself drives the player.
        Object p = android.os.Looper.myLooper() == android.os.Looper.getMainLooper() ? current() : null;
        if (p != null) {
            String content;
            synchronized (PLAYERS) {
                Entry e = entry(p, false);
                content = e == null ? "" : e.content;
            }
            java.util.regex.Matcher vod = VOD_PATH.matcher(content);
            if (vod.find()) return vod.group(1);
            if (!content.isEmpty()) return "";
        }
        return vodId;
    }

    /** Twitch asks for the chat replay at this many seconds into the VOD it set up. */
    public static int commentTime(int seconds) {
        int offset = app == null ? 0 : ChatSettings.preferences(app).getInt("vod_sync_" + vodId, 0);
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, (long) seconds + offset));
    }

    public static SharedPreferences preferences() {
        return app == null ? null : ChatSettings.preferences(app);
    }

    /** The application, once Twitch has started it. */
    static Context app() {
        return app;
    }

    private static Object call(Object player, String name, Class<?>[] types, Object... args) throws Exception {
        return Class.forName("com.amazonaws.ivs.player.Player")
                .getMethod(name, types)
                .invoke(player, args);
    }

    /**
     * The player on screen. Twitch also runs players for previews (its React Native feed has
     * its own), so the most recently started one is not always the theatre's: prefer one that
     * can be heard, then one that is playing, then the most recently started.
     */
    static Object current() {
        List<Entry> entries;
        synchronized (PLAYERS) {
            entry(null, false);
            entries = new ArrayList<>(PLAYERS);
        }
        Object best = null;
        int bestScore = -1;
        long bestAt = Long.MIN_VALUE;
        for (Entry e : entries) {
            Object p = e.player.get();
            if (p == null) continue;
            int score = score(p);
            if (score > bestScore || score == bestScore && e.playedAt > bestAt) {
                best = p;
                bestScore = score;
                bestAt = e.playedAt;
            }
        }
        return best;
    }

    private static int score(Object p) {
        int state;
        try {
            // A paused IVS player reports IDLE, so only an ended one counts as finished.
            String name = String.valueOf(call(p, "getState", new Class<?>[0]));
            state = "PLAYING".equals(name) ? 3 : "BUFFERING".equals(name) ? 2 : "ENDED".equals(name) ? 0 : 1;
        } catch (Exception e) {
            return 0;
        }
        if (state == 0) return 0;
        boolean audible;
        try {
            audible = !(Boolean) call(p, "isMuted", new Class<?>[0])
                    && ((Number) call(p, "getVolume", new Class<?>[0])).floatValue() > 0;
        } catch (Exception e) {
            audible = false;
        }
        return (audible ? 4 : 0) + state;
    }

    private static Object require() {
        Object value = current();
        if (value == null) throw new IllegalStateException("No active player");
        return value;
    }

    private static Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        return call(require(), name, types, args);
    }

    private static long number(String name) throws Exception {
        return ((Number) invoke(name, new Class<?>[0])).longValue();
    }

    private static long number(Object player, String name) throws Exception {
        return ((Number) call(player, name, new Class<?>[0])).longValue();
    }

    /** True for a live stream, false for a VOD or clip, null when nothing is playing. */
    public static Boolean live() {
        Object p = current();
        if (p == null) return null;
        try {
            return number(p, "getDuration") < 0;
        } catch (Exception e) {
            return null;
        }
    }

    /** How far a live stream is behind the broadcast, in milliseconds; 0 or -1 when unknown. */
    public static long liveDelay() {
        Object p = current();
        try {
            return p == null ? -1 : number(p, "getLiveLatency");
        } catch (Exception e) {
            return -1;
        }
    }

    public static void seek(long position) throws Exception {
        long duration = number("getDuration");
        invoke(
                "seekTo",
                new Class<?>[] {long.class},
                Math.max(0, duration > 0 ? Math.min(duration, position) : position));
    }

    public static boolean hasPlayer() {
        return current() != null;
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
        speed(rate);
    }

    /**
     * Sets the speed of the player on screen. A live stream cannot run ahead of the broadcast:
     * faster than 1x only makes up a delay and then returns to 1x by itself. Left alone, the
     * player would stall at the live edge over and over.
     */
    public static int speed(float rate) throws Exception {
        if (!Float.isFinite(rate)) throw new IllegalArgumentException();
        rate = Math.max(.25f, Math.min(2f, rate));
        Object p = require();
        boolean live = number(p, "getDuration") < 0;
        if (live && rate > 1f && caughtUp(p)) return SPEED_AT_LIVE_EDGE;
        call(p, "setPlaybackRate", new Class<?>[] {float.class}, rate);
        synchronized (PLAYERS) {
            entry(p, true).rate = rate;
        }
        catchingUp.clear();
        PublicApi.UI.removeCallbacks(CATCH_UP);
        if (!live || rate <= 1f) return SPEED_SET;
        catchingUp = new WeakReference<>(p);
        PublicApi.UI.postDelayed(CATCH_UP, 500);
        return SPEED_CATCHING_UP;
    }

    /**
     * Faster than 1x is only possible while video is loaded ahead of the playing position. Near
     * the live edge little is, whatever the stream's own delay, so playing on fast would stall.
     */
    private static boolean caughtUp(Object p) throws Exception {
        long ahead = number(p, "getBufferedPosition") - number(p, "getPosition");
        return ahead < CAUGHT_UP_MS;
    }

    private static final Runnable CATCH_UP =
            new Runnable() {
                @Override
                public void run() {
                    Object p = catchingUp.get();
                    if (p == null) return;
                    try {
                        float rate = ((Number) call(p, "getPlaybackRate", new Class<?>[0])).floatValue();
                        if (rate <= 1f) {
                            catchingUp.clear();
                            return;
                        }
                        if (caughtUp(p)) {
                            call(p, "setPlaybackRate", new Class<?>[] {float.class}, 1f);
                            synchronized (PLAYERS) {
                                Entry e = entry(p, false);
                                if (e != null) e.rate = 1f;
                            }
                            catchingUp.clear();
                            Context c = app;
                            if (c != null)
                                Toast.makeText(c, label("ライブに追いついたので 1× に戻しました",
                                        "Caught up with the stream, back to 1×"), Toast.LENGTH_SHORT).show();
                            return;
                        }
                    } catch (Exception e) {
                        catchingUp.clear();
                        return;
                    }
                    PublicApi.UI.postDelayed(this, 500);
                }
            };

    public static void relativeSeek(Context c, boolean forward) {
        if (Boolean.TRUE.equals(live())) {
            Toast.makeText(c, label("ライブ配信では戻る・進むは使えません（VOD で使えます）",
                    "Live streams cannot be skipped; this works on VODs"), Toast.LENGTH_SHORT).show();
            return;
        }
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
        String vod = vodId();
        if (vod.isEmpty()) {
            Toast.makeText(c, label("VODを開いてください", "Open a VOD first"), Toast.LENGTH_SHORT).show();
            return;
        }
        final String key = "vod_sync_" + vod;
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
