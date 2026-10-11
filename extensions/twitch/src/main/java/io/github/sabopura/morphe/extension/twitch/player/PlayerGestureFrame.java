package io.github.sabopura.morphe.extension.twitch.player;

import io.github.sabopura.morphe.extension.twitch.ui.Text;
import android.app.Activity;
import android.content.*;
import android.media.AudioManager;
import android.util.AttributeSet;
import android.view.*;
import android.widget.*;

import io.github.sabopura.morphe.extension.twitch.chat.*;

/**
 * Intercepts a deliberate vertical swipe near either edge; taps and horizontal swipes stay native.
 */
public final class PlayerGestureFrame extends FrameLayout {
    private float x, y, initialBrightness, restoreBrightness = Float.NaN;
    private int volume;
    private boolean eligible, dragging, left;
    private long lastToast;

    public PlayerGestureFrame(Context c, AttributeSet a) {
        super(c, a);
    }

    private Activity activity() {
        Context c = getContext();
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            Context next = ((ContextWrapper) c).getBaseContext();
            if (c == next) break;
            c = next;
        }
        return null;
    }

    @Override
    public boolean onInterceptTouchEvent(android.view.MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            x = e.getX();
            y = e.getY();
            left = x < getWidth() / 2f;
            dragging = false;
            eligible =
                    (x < getWidth() * .22f || x > getWidth() * .78f)
                            && y > getHeight() * .15f
                            && y < getHeight() * .85f
                            && ChatSettings.preferences(getContext())
                                    .getBoolean(
                                            left ? "brightness_gesture" : "volume_gesture", false);
            AudioManager audio =
                    (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            volume = audio == null ? 0 : audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            Activity a = activity();
            initialBrightness = a == null ? .5f : a.getWindow().getAttributes().screenBrightness;
            if (initialBrightness < 0) {
                try {
                    initialBrightness =
                            android.provider.Settings.System.getInt(
                                            getContext().getContentResolver(),
                                            android.provider.Settings.System.SCREEN_BRIGHTNESS)
                                    / 255f;
                } catch (Exception ex) {
                    initialBrightness = .5f;
                }
            }
        } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE
                && eligible
                && e.getPointerCount() == 1) {
            float dy = e.getY() - y, dx = e.getX() - x;
            if (Math.abs(dy) > ViewConfiguration.get(getContext()).getScaledTouchSlop() * 2
                    && Math.abs(dy) > Math.abs(dx) * 1.5f) {
                dragging = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
        }
        if (e.getActionMasked() == MotionEvent.ACTION_UP
                || e.getActionMasked() == MotionEvent.ACTION_CANCEL) eligible = false;
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!dragging) return super.onTouchEvent(e);
        if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
            float delta = (y - e.getY()) / Math.max(1, getHeight());
            int percent;
            if (left) {
                Activity a = activity();
                if (a == null) return true;
                WindowManager.LayoutParams p = a.getWindow().getAttributes();
                if (Float.isNaN(restoreBrightness)) restoreBrightness = p.screenBrightness;
                p.screenBrightness = Math.max(.02f, Math.min(1, initialBrightness + delta));
                a.getWindow().setAttributes(p);
                percent = Math.round(p.screenBrightness * 100);
            } else {
                AudioManager audio =
                        (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
                if (audio == null) return true;
                int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                        value = Math.max(0, Math.min(max, Math.round(volume + delta * max)));
                try {
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0);
                } catch (SecurityException ignored) {
                }
                percent = max == 0 ? 0 : value * 100 / max;
            }
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastToast > 700) {
                lastToast = now;
                Toast.makeText(
                                getContext(),
                                Text.label(
                                                left ? "明るさ " : "音量 ",
                                                left ? "Brightness " : "Volume ")
                                        + percent
                                        + "%",
                                Toast.LENGTH_SHORT)
                        .show();
            }
        } else if (e.getActionMasked() == MotionEvent.ACTION_UP
                || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            dragging = false;
            eligible = false;
            getParent().requestDisallowInterceptTouchEvent(false);
        }
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        Activity a = activity();
        if (a != null && !Float.isNaN(restoreBrightness)) {
            WindowManager.LayoutParams p = a.getWindow().getAttributes();
            p.screenBrightness = restoreBrightness;
            a.getWindow().setAttributes(p);
            restoreBrightness = Float.NaN;
        }
        super.onDetachedFromWindow();
    }
}
