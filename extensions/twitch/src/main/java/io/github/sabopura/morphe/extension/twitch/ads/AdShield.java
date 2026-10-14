package io.github.sabopura.morphe.extension.twitch.ads;

import io.github.sabopura.morphe.extension.twitch.ui.Text;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.sabopura.morphe.extension.twitch.ui.Text.label;

/**
 * The last resort: covers the video and mutes the player while a stitched ad plays that no
 * backup session could replace. Added to the player layout right above the video, below the
 * player controls.
 */
public final class AdShield extends FrameLayout {
    private static final Pattern DURATION = Pattern.compile("[(,] ?duration=([0-9.]+)");
    private static final Pattern POD_DURATION = Pattern.compile("podDuration=([0-9.]+)");
    private static final Pattern POD_POSITION = Pattern.compile("podPosition=(\\d+)");
    /** Ends the cover if Twitch never reports the return to live. */
    private static final long GRACE_MS = 5000;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final List<WeakReference<AdShield>> shields = new ArrayList<>();
    private static long endsAt;
    private static boolean active;
    private static WeakReference<Object> mutedPlayer = new WeakReference<>(null);
    private static boolean wasMuted;

    private final TextView text;

    public AdShield(Context context, AttributeSet attrs) {
        super(context, attrs);
        Text.use(context);
        setBackgroundColor(Color.BLACK);
        setClickable(false);
        text = new TextView(context);
        text.setTextColor(0xFFDDDDDD);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        text.setGravity(Gravity.CENTER);
        addView(text, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setVisibility(GONE);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        synchronized (shields) {
            shields.add(new WeakReference<>(this));
        }
        render();
    }

    @Override
    protected void onDetachedFromWindow() {
        synchronized (shields) {
            for (Iterator<WeakReference<AdShield>> it = shields.iterator(); it.hasNext(); ) {
                AdShield s = it.next().get();
                if (s == null || s == this) it.remove();
            }
        }
        super.onDetachedFromWindow();
    }

    private void render() {
        if (!active) {
            setVisibility(GONE);
            return;
        }
        long left = Math.max(0, (endsAt - GRACE_MS - System.currentTimeMillis() + 999) / 1000);
        text.setText(left > 0
                ? label("広告をブロック中・残り " + left + " 秒", "Ad blocked · " + left + " s left")
                : label("広告をブロック中", "Ad blocked"));
        setVisibility(VISIBLE);
    }

    /** A stitched ad started. Called from a player thread. */
    static void start(Object player, Object event) {
        String description = String.valueOf(event);
        float seconds = number(DURATION, description);
        float pod = number(POD_DURATION, description);
        boolean first = number(POD_POSITION, description) <= 0;
        float span = first && pod > seconds ? pod : seconds;
        final float shown = span > 0 ? span : 30;
        long until = System.currentTimeMillis() + (long) (shown * 1000) + GRACE_MS;
        main.post(() -> {
            endsAt = until;
            if (!active) {
                active = true;
                mute(player, true);
                tick();
            }
            AdBlock.log("Covering an ad that could not be replaced: " + shown + " s");
            renderAll();
        });
    }

    /** The stream is live again. Called from a player thread. */
    static void end(Object player) {
        main.post(AdShield::stop);
    }

    private static void stop() {
        if (!active) return;
        active = false;
        Object player = mutedPlayer.get();
        if (player != null) mute(player, false);
        renderAll();
    }

    private static void tick() {
        if (!active) return;
        if (System.currentTimeMillis() >= endsAt) {
            stop();
            return;
        }
        renderAll();
        main.postDelayed(AdShield::tick, 1000);
    }

    private static void renderAll() {
        synchronized (shields) {
            for (Iterator<WeakReference<AdShield>> it = shields.iterator(); it.hasNext(); ) {
                AdShield s = it.next().get();
                if (s == null) it.remove();
                else s.render();
            }
        }
    }

    private static float number(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        try {
            return m.find() ? Float.parseFloat(m.group(1)) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Mutes the media player inside Twitch's player wrapper: Amazon IVS (setMuted) or ExoPlayer
     * (setVolume). Restores the earlier state afterwards.
     */
    private static void mute(Object wrapper, boolean mute) {
        try {
            for (Class<?> c = wrapper.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType().isPrimitive()) continue;
                    f.setAccessible(true);
                    Object target = f.get(wrapper);
                    if (target == null) continue;
                    Method setMuted = find(target, "setMuted", boolean.class);
                    if (setMuted != null && target.getClass().getName().startsWith("com.amazonaws.ivs.")) {
                        if (mute) {
                            Method isMuted = find(target, "isMuted");
                            wasMuted = isMuted != null && Boolean.TRUE.equals(isMuted.invoke(target));
                            mutedPlayer = new WeakReference<>(wrapper);
                        }
                        setMuted.invoke(target, mute || wasMuted);
                        return;
                    }
                    Method setVolume = find(target, "setVolume", float.class);
                    if (setVolume != null && target.getClass().getName().startsWith("androidx.media3.")) {
                        if (mute) mutedPlayer = new WeakReference<>(wrapper);
                        setVolume.invoke(target, mute ? 0f : 1f);
                        return;
                    }
                }
            }
        } catch (Exception e) {
            AdBlock.log("Could not mute the player: " + e);
        }
    }

    private static Method find(Object target, String name, Class<?>... types) {
        try {
            Method m = target.getClass().getMethod(name, types);
            m.setAccessible(true);
            return m;
        } catch (Exception e) {
            return null;
        }
    }
}
