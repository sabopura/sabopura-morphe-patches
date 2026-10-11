package io.github.sabopura.morphe.extension.twitch.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.Toast;

import io.github.sabopura.morphe.extension.twitch.chat.ViewCustomization;
import io.github.sabopura.morphe.extension.twitch.danmaku.DanmakuSettings;
import io.github.sabopura.morphe.extension.twitch.danmaku.TwitchDanmaku;

import static io.github.sabopura.morphe.extension.twitch.ui.Text.label;

/**
 * The one button the Twitch patches add to the player controls. Tap opens the player menu;
 * long press switches danmaku on or off (or opens the settings when danmaku is not patched).
 * The icon is drawn in code and shows whether danmaku is on.
 */
@SuppressWarnings("unused")
public final class PlayerMenuButton extends View {
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bubble = new RectF();
    private final Path tail = new Path();

    public PlayerMenuButton(Context context) {
        super(context);
        init();
    }

    public PlayerMenuButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public PlayerMenuButton(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2f * density);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(Color.WHITE);
        fill.setColor(Color.WHITE);
        setClickable(true);
        setFocusable(true);
        TypedValue ripple = new TypedValue();
        if (getContext().getTheme().resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, ripple, true)) {
            setBackgroundResource(ripple.resourceId);
        }
        setOnClickListener(v -> PlayerMenu.show(getContext()));
        setOnLongClickListener(v -> {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (Features.danmaku()) toggleDanmaku();
            else SettingsScreen.show(getContext());
            return true;
        });
        describe();
    }

    private boolean danmakuOn() {
        return Features.danmaku() && DanmakuSettings.get(getContext()).enabled;
    }

    private void describe() {
        String base = label("プレイヤーメニュー", "Player menu");
        if (Features.danmaku()) {
            base += label(danmakuOn() ? "（弾幕オン。長押しで切替）" : "（弾幕オフ。長押しで切替）",
                    danmakuOn() ? " (danmaku on; long-press to switch)" : " (danmaku off; long-press to switch)");
        }
        setContentDescription(base);
    }

    private void toggleDanmaku() {
        Context context = getContext();
        boolean enabled = !DanmakuSettings.get(context).enabled;
        DanmakuSettings.setEnabled(context, enabled);
        TwitchDanmaku.settingsChanged();
        describe();
        invalidate();
        Toast.makeText(context, enabled ? label("弾幕: オン", "Danmaku on") : label("弾幕: オフ", "Danmaku off"),
                Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Visibility options need the player's view tree; it exists once this button does.
        if (Features.chat()) ViewCustomization.observe(getRootView());
        describe();
        invalidate();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        // The menu or settings may have switched danmaku while they had focus.
        if (hasWindowFocus) {
            describe();
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float half = Math.min(11f * density, Math.min(getWidth(), getHeight()) * 0.36f);

        // Speech bubble with a tail at the lower left.
        bubble.set(cx - half, cy - half * 0.82f, cx + half, cy + half * 0.58f);
        float radius = half * 0.32f;
        canvas.drawRoundRect(bubble, radius, radius, stroke);
        tail.reset();
        tail.moveTo(cx - half * 0.42f, bubble.bottom);
        tail.lineTo(cx - half * 0.6f, cy + half);
        tail.lineTo(cx - half * 0.08f, bubble.bottom);
        canvas.drawPath(tail, stroke);

        if (!Features.danmaku()) {
            // Chat settings only: three dots.
            float r = 1.1f * density;
            for (int i = -1; i <= 1; i++) canvas.drawCircle(cx + i * half * 0.45f, bubble.centerY(), r, fill);
            return;
        }
        // Two flowing comment lines; dimmed and struck through when danmaku is off.
        boolean on = danmakuOn();
        int alpha = on ? 255 : 120;
        stroke.setAlpha(alpha);
        float y1 = bubble.top + bubble.height() * 0.36f, y2 = bubble.top + bubble.height() * 0.68f;
        canvas.drawLine(cx - half * 0.2f, y1, cx + half * 0.6f, y1, stroke);
        canvas.drawLine(cx - half * 0.6f, y2, cx + half * 0.25f, y2, stroke);
        stroke.setAlpha(255);
        if (!on) canvas.drawLine(cx - half, cy + half * 0.9f, cx + half, cy - half * 0.9f, stroke);
    }
}
