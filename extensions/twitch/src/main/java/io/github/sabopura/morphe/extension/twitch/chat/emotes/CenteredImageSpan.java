// Adapted from Hooman's patches, GPL-3.0; see NOTICE for source and revision.
package io.github.sabopura.morphe.extension.twitch.chat.emotes;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.text.style.DynamicDrawableSpan;
import android.widget.TextView;

import java.lang.ref.WeakReference;

final class CenteredImageSpan extends DynamicDrawableSpan {
    private final Drawable drawable;
    private final Paint.FontMetricsInt paintMetrics = new Paint.FontMetricsInt();

    CenteredImageSpan(TextView textView, Drawable drawable) {
        super(ALIGN_BOTTOM);
        this.drawable = drawable;

        int height = Math.max(1, Math.round(textView.getTextSize() * 1.25f));
        int intrinsicHeight = drawable.getIntrinsicHeight();
        int intrinsicWidth = drawable.getIntrinsicWidth();
        int width =
                intrinsicHeight > 0 && intrinsicWidth > 0
                        ? Math.max(1, Math.round((float) intrinsicWidth * height / intrinsicHeight))
                        : height;
        drawable.setBounds(0, 0, width, height);
        // A normal TextView does not verify arbitrary inline drawables, and a strong callback
        // would keep recycled chat rows alive. Schedule frames through a weak UI callback.
        WeakReference<TextView> owner = new WeakReference<>(textView);
        drawable.setCallback(
                new Drawable.Callback() {
                    public void invalidateDrawable(Drawable who) {
                        TextView view = owner.get();
                        if (view != null && view.isAttachedToWindow()) view.invalidate();
                    }

                    public void scheduleDrawable(Drawable who, Runnable what, long when) {
                        TextView view = owner.get();
                        if (view != null && view.isAttachedToWindow())
                            view.postDelayed(
                                    what,
                                    Math.max(0, when - android.os.SystemClock.uptimeMillis()));
                    }

                    public void unscheduleDrawable(Drawable who, Runnable what) {
                        TextView view = owner.get();
                        if (view != null) view.removeCallbacks(what);
                    }
                });
        if (drawable instanceof Animatable) {
            ((Animatable) drawable).start();
        }
    }

    @Override
    public Drawable getDrawable() {
        return drawable;
    }

    @Override
    public int getSize(
            Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt metrics) {
        Rect bounds = drawable.getBounds();
        if (metrics != null) {
            paint.getFontMetricsInt(paintMetrics);
            int center = (paintMetrics.ascent + paintMetrics.descent) / 2;
            int halfHeight = bounds.height() / 2;
            metrics.ascent = center - halfHeight;
            metrics.top = metrics.ascent;
            metrics.descent = center + halfHeight;
            metrics.bottom = metrics.descent;
        }
        return bounds.width();
    }

    @Override
    public void draw(
            Canvas canvas,
            CharSequence text,
            int start,
            int end,
            float x,
            int top,
            int baseline,
            int bottom,
            Paint paint) {
        paint.getFontMetricsInt(paintMetrics);
        int textCenter = baseline + (paintMetrics.ascent + paintMetrics.descent) / 2;
        int drawableTop = textCenter - drawable.getBounds().height() / 2;
        canvas.save();
        canvas.translate(x, drawableTop);
        drawable.draw(canvas);
        canvas.restore();
    }

    void stop() {
        if (drawable instanceof Animatable) {
            ((Animatable) drawable).stop();
        }
        drawable.setCallback(null);
    }
}
