package io.github.sabopura.morphe.extension.twitch.player;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.KeyboardShortcutGroup;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SearchEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import io.github.sabopura.morphe.extension.twitch.chat.ChatSettings;
import io.github.sabopura.morphe.extension.twitch.chat.PublicApi;
import io.github.sabopura.morphe.extension.twitch.ui.Text;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Brightness (left edge) and volume (right edge) swipes over the theatre player.
 *
 * <p>The player's controls lie above the video, and Twitch's drag-to-minimise wraps it, so a
 * view inside the player never sees these touches. The swipe is read where the activity's
 * window receives each touch, before any view: a vertical swipe that starts at an edge of the
 * video is taken over and the views get a cancel. Taps and horizontal swipes pass through.
 */
public final class PlayerGestures {
    private static final List<WeakReference<View>> FRAMES = new ArrayList<>();
    /** Share of the width at each side, and the top and bottom bands left to the controls. */
    private static final float EDGE = .22f, BAND = .15f;

    private PlayerGestures() {}

    static void attach(View frame) {
        prune(null);
        FRAMES.add(new WeakReference<>(frame));
        Activity activity = activity(frame.getContext());
        if (activity == null) return;
        Window window = activity.getWindow();
        Window.Callback callback = window == null ? null : window.getCallback();
        if (callback != null && !(callback instanceof Callback))
            window.setCallback(new Callback(window, callback));
    }

    static void detach(View frame) {
        prune(frame);
        Activity activity = activity(frame.getContext());
        Window window = activity == null ? null : activity.getWindow();
        if (window != null && window.getCallback() instanceof Callback) {
            Callback callback = (Callback) window.getCallback();
            // A feed preview leaving the screen must not cut off a swipe on the theatre.
            if (callback.frame == frame) callback.end();
            // Rotating or re-laying out the theatre moves the player; only leaving it restores.
            PublicApi.UI.postDelayed(callback::restoreIfClosed, 1500);
        }
    }

    private static void prune(View removed) {
        for (int i = FRAMES.size() - 1; i >= 0; i--) {
            View view = FRAMES.get(i).get();
            if (view == null || view == removed) FRAMES.remove(i);
        }
    }

    private static Activity activity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) break;
            context = next;
        }
        return null;
    }

    private static int id(View view, String name) {
        return view.getResources().getIdentifier(name, "id", view.getContext().getPackageName());
    }

    private static View ancestor(View view, int id) {
        if (id == 0) return null;
        for (ViewParent at = view.getParent(); at instanceof View; at = at.getParent())
            if (((View) at).getId() == id) return (View) at;
        return null;
    }

    /** The full-size theatre player: not a feed preview, and not the mini player. */
    static boolean inExpandedTheatre(View frame) {
        if (ancestor(frame, id(frame, "video_presenter_container")) == null) return false;
        View draggable = ancestor(frame, id(frame, "draggable_layout"));
        if (draggable == null || !(draggable.getParent() instanceof View)) return true;
        Rect moving = new Rect(), whole = new Rect();
        if (!draggable.getGlobalVisibleRect(moving) || !((View) draggable.getParent()).getGlobalVisibleRect(whole))
            return false;
        return moving.width() >= whole.width() * .95f && moving.height() >= whole.height() * .95f;
    }

    /** Which swipe a touch at window coordinates (x, y) would start over this frame, if any. */
    static int zone(View frame, float x, float y) {
        if (!frame.isShown() || frame.getWidth() <= 0 || frame.getHeight() <= 0) return 0;
        Rect visible = new Rect();
        if (!frame.getGlobalVisibleRect(visible) || !visible.contains((int) x, (int) y)) return 0;
        int[] at = new int[2];
        frame.getLocationInWindow(at);
        float rx = (x - at[0]) / frame.getWidth(), ry = (y - at[1]) / frame.getHeight();
        if (ry <= BAND || ry >= 1 - BAND) return 0;
        if (rx < EDGE) return Swipe.BRIGHTNESS;
        if (rx > 1 - EDGE) return Swipe.VOLUME;
        return 0;
    }

    /** Whether a visible view under (x, y) can scroll vertically, such as a chat list. */
    static boolean scrollsAt(View view, int x, int y) {
        if (view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0) return false;
        Rect area = new Rect();
        if (!view.getGlobalVisibleRect(area) || !area.contains(x, y)) return false;
        if (view.canScrollVertically(1) || view.canScrollVertically(-1)) return true;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--)
                if (scrollsAt(group.getChildAt(i), x, y)) return true;
        }
        return false;
    }

    private static final class Swipe {
        static final int BRIGHTNESS = 1, VOLUME = 2;
    }

    /** Sits in front of the activity, so it sees each touch before any view does. */
    private static final class Callback implements Window.Callback {
        private final Window window;
        private final Window.Callback base;
        private View frame;
        private int kind;
        private boolean tracking, claimed;
        private float startX, startY, baseY, startValue;
        private int maxVolume, lastVolume = -1;
        private float originalBrightness = Float.NaN;
        private Indicator indicator;

        Callback(Window window, Window.Callback base) {
            this.window = window;
            this.base = base;
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            boolean ours;
            try {
                ours = onTouch(event);
            } catch (RuntimeException e) {
                end();
                ours = false;
            }
            return ours || base.dispatchTouchEvent(event);
        }

        private boolean onTouch(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    end();
                    begin(e.getX(), e.getY());
                    return false;
                case MotionEvent.ACTION_POINTER_DOWN:
                    tracking = false;
                    return claimed;
                case MotionEvent.ACTION_MOVE:
                    if (claimed) {
                        update(e.getY());
                        return true;
                    }
                    if (!tracking) return false;
                    float dx = e.getX() - startX, dy = e.getY() - startY;
                    // Decide a little before the views' own slop, so Twitch never starts dragging first.
                    float slop = ViewConfiguration.get(frame.getContext()).getScaledTouchSlop() * .8f;
                    if (dx * dx + dy * dy <= slop * slop) return false;
                    tracking = false;
                    if (Math.abs(dy) <= Math.abs(dx) * 1.5f) return false;
                    claim(e);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    tracking = false;
                    if (!claimed) return false;
                    end();
                    return true;
                default:
                    return claimed;
            }
        }

        private void begin(float x, float y) {
            // Every touch of the screen passes here: settle the common case (both off) first.
            SharedPreferences prefs = ChatSettings.preferences(window.getContext());
            boolean brightness = prefs.getBoolean("brightness_gesture", false),
                    volume = prefs.getBoolean("volume_gesture", false);
            if (!brightness && !volume) return;
            for (WeakReference<View> ref : FRAMES) {
                View view = ref.get();
                if (view == null || view.getWindowToken() == null
                        || view.getWindowToken() != window.getDecorView().getWindowToken()) continue;
                int found = zone(view, x, y);
                if (found == 0 || !(found == Swipe.BRIGHTNESS ? brightness : volume)) continue;
                if (!inExpandedTheatre(view)) continue;
                // Chat drawn over the video in landscape scrolls with the same swipe; it wins. Only
                // the theatre's own views count: the screen it opened over (a feed, a list) stays
                // underneath and can always scroll.
                View theatre = ancestor(view, id(view, "video_presenter_container"));
                if (scrollsAt(theatre != null ? theatre : view, (int) x, (int) y)) continue;
                if (found == Swipe.VOLUME) {
                    AudioManager audio = audio(view);
                    if (audio == null || audio.isVolumeFixed()) continue;
                }
                frame = view;
                kind = found;
                startX = x;
                startY = y;
                tracking = true;
                return;
            }
        }

        private void claim(MotionEvent e) {
            claimed = true;
            MotionEvent cancel = MotionEvent.obtain(e);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            base.dispatchTouchEvent(cancel);
            cancel.recycle();
            baseY = e.getY();
            if (kind == Swipe.BRIGHTNESS) {
                float current = window.getAttributes().screenBrightness;
                if (current < 0) {
                    try {
                        current = Settings.System.getInt(frame.getContext().getContentResolver(),
                                Settings.System.SCREEN_BRIGHTNESS) / 255f;
                    } catch (Settings.SettingNotFoundException ex) {
                        current = .5f;
                    }
                }
                startValue = clamp(current, .01f, 1);
            } else {
                AudioManager audio = audio(frame);
                maxVolume = audio == null ? 0 : audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                lastVolume = audio == null ? 0 : audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                startValue = lastVolume;
            }
            Text.use(frame.getContext());
            indicator = new Indicator(frame.getContext(),
                    kind == Swipe.BRIGHTNESS ? Text.label("明るさ", "Brightness") : Text.label("音量", "Volume"));
            Rect visible = new Rect();
            frame.getGlobalVisibleRect(visible);
            indicator.place(visible);
            window.getDecorView().getOverlay().add(indicator);
            update(e.getY());
        }

        private void update(float y) {
            float delta = (baseY - y) / Math.max(1, frame.getHeight());
            if (kind == Swipe.BRIGHTNESS) {
                WindowManager.LayoutParams params = window.getAttributes();
                if (Float.isNaN(originalBrightness)) originalBrightness = params.screenBrightness;
                params.screenBrightness = clamp(startValue + delta, .01f, 1);
                window.setAttributes(params);
                indicator.show(params.screenBrightness);
            } else {
                AudioManager audio = audio(frame);
                if (audio == null || maxVolume <= 0) return;
                int value = Math.round(clamp(startValue + delta * maxVolume, 0, maxVolume));
                if (value != lastVolume) {
                    try {
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0);
                    } catch (SecurityException ignored) {
                    }
                    lastVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                }
                indicator.show(lastVolume / (float) maxVolume);
            }
        }

        void end() {
            tracking = false;
            claimed = false;
            frame = null;
            Indicator shown = indicator;
            indicator = null;
            if (shown != null) {
                View decor = window.getDecorView();
                decor.postDelayed(() -> decor.getOverlay().remove(shown), 600);
            }
        }

        /** Gives the screen its own brightness back once no theatre player is left open. */
        void restoreIfClosed() {
            if (Float.isNaN(originalBrightness)) return;
            for (WeakReference<View> ref : FRAMES) {
                View view = ref.get();
                if (view != null && view.getWindowToken() != null
                        && view.getWindowToken() == window.getDecorView().getWindowToken()
                        && ancestor(view, id(view, "video_presenter_container")) != null) return;
            }
            WindowManager.LayoutParams params = window.getAttributes();
            params.screenBrightness = originalBrightness;
            window.setAttributes(params);
            originalBrightness = Float.NaN;
        }

        private static AudioManager audio(View view) {
            return (AudioManager) view.getContext().getSystemService(Context.AUDIO_SERVICE);
        }

        // ---- everything else goes to the activity unchanged ----

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            return base.dispatchKeyEvent(event);
        }

        @Override
        public boolean dispatchKeyShortcutEvent(KeyEvent event) {
            return base.dispatchKeyShortcutEvent(event);
        }

        @Override
        public boolean dispatchTrackballEvent(MotionEvent event) {
            return base.dispatchTrackballEvent(event);
        }

        @Override
        public boolean dispatchGenericMotionEvent(MotionEvent event) {
            return base.dispatchGenericMotionEvent(event);
        }

        @Override
        public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
            return base.dispatchPopulateAccessibilityEvent(event);
        }

        @Override
        public View onCreatePanelView(int featureId) {
            return base.onCreatePanelView(featureId);
        }

        @Override
        public boolean onCreatePanelMenu(int featureId, Menu menu) {
            return base.onCreatePanelMenu(featureId, menu);
        }

        @Override
        public boolean onPreparePanel(int featureId, View view, Menu menu) {
            return base.onPreparePanel(featureId, view, menu);
        }

        @Override
        public boolean onMenuOpened(int featureId, Menu menu) {
            return base.onMenuOpened(featureId, menu);
        }

        @Override
        public boolean onMenuItemSelected(int featureId, MenuItem item) {
            return base.onMenuItemSelected(featureId, item);
        }

        @Override
        public void onWindowAttributesChanged(WindowManager.LayoutParams attrs) {
            base.onWindowAttributesChanged(attrs);
        }

        @Override
        public void onContentChanged() {
            base.onContentChanged();
        }

        @Override
        public void onWindowFocusChanged(boolean hasFocus) {
            base.onWindowFocusChanged(hasFocus);
        }

        @Override
        public void onAttachedToWindow() {
            base.onAttachedToWindow();
        }

        @Override
        public void onDetachedFromWindow() {
            base.onDetachedFromWindow();
        }

        @Override
        public void onPanelClosed(int featureId, Menu menu) {
            base.onPanelClosed(featureId, menu);
        }

        @Override
        public boolean onSearchRequested() {
            return base.onSearchRequested();
        }

        @Override
        public boolean onSearchRequested(SearchEvent searchEvent) {
            return base.onSearchRequested(searchEvent);
        }

        @Override
        public ActionMode onWindowStartingActionMode(ActionMode.Callback callback) {
            return base.onWindowStartingActionMode(callback);
        }

        @Override
        public ActionMode onWindowStartingActionMode(ActionMode.Callback callback, int type) {
            return base.onWindowStartingActionMode(callback, type);
        }

        @Override
        public void onActionModeStarted(ActionMode mode) {
            base.onActionModeStarted(mode);
        }

        @Override
        public void onActionModeFinished(ActionMode mode) {
            base.onActionModeFinished(mode);
        }

        @Override
        public void onProvideKeyboardShortcuts(List<KeyboardShortcutGroup> data, Menu menu, int deviceId) {
            base.onProvideKeyboardShortcuts(data, menu, deviceId);
        }

        @Override
        public void onPointerCaptureChanged(boolean hasCapture) {
            base.onPointerCaptureChanged(hasCapture);
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /** A small label with a level bar, drawn over the video while swiping. */
    private static final class Indicator extends Drawable {
        private final Paint box = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG),
                track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String title;
        private final float pad, gap, bar, radius, minWidth;
        private final RectF shape = new RectF();
        private float level;

        Indicator(Context context, String title) {
            this.title = title;
            pad = px(context, TypedValue.COMPLEX_UNIT_DIP, 16);
            gap = px(context, TypedValue.COMPLEX_UNIT_DIP, 8);
            bar = px(context, TypedValue.COMPLEX_UNIT_DIP, 4);
            radius = px(context, TypedValue.COMPLEX_UNIT_DIP, 14);
            minWidth = px(context, TypedValue.COMPLEX_UNIT_DIP, 132);
            box.setColor(0xC0000000);
            text.setColor(0xFFFFFFFF);
            text.setTextSize(px(context, TypedValue.COMPLEX_UNIT_SP, 17));
            text.setTypeface(Typeface.DEFAULT_BOLD);
            text.setTextAlign(Paint.Align.CENTER);
            track.setColor(0x4DFFFFFF);
            fill.setColor(0xFFFFFFFF);
        }

        private static float px(Context context, int unit, float value) {
            return TypedValue.applyDimension(unit, value, context.getResources().getDisplayMetrics());
        }

        /** Centres the label over the visible part of the video. */
        void place(Rect video) {
            Paint.FontMetrics metrics = text.getFontMetrics();
            float width = Math.max(minWidth, text.measureText(title + " 100%")) + pad * 2;
            float height = (metrics.descent - metrics.ascent) + gap + bar + pad * 1.5f;
            int left = Math.round(video.exactCenterX() - width / 2), top = Math.round(video.exactCenterY() - height / 2);
            setBounds(left, top, left + Math.round(width), top + Math.round(height));
        }

        void show(float value) {
            level = clamp(value, 0, 1);
            invalidateSelf();
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            shape.set(b);
            canvas.drawRoundRect(shape, radius, radius, box);
            Paint.FontMetrics metrics = text.getFontMetrics();
            float baseline = b.top + pad * .75f - metrics.ascent;
            canvas.drawText(title + " " + Math.round(level * 100) + "%", b.exactCenterX(), baseline, text);
            float top = baseline + metrics.descent + gap, left = b.left + pad, right = b.right - pad;
            shape.set(left, top, right, top + bar);
            canvas.drawRoundRect(shape, bar / 2, bar / 2, track);
            shape.set(left, top, left + (right - left) * level, top + bar);
            canvas.drawRoundRect(shape, bar / 2, bar / 2, fill);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
