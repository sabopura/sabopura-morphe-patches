package io.github.sabopura.morphe.extension.twitch.danmaku;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Transparent layer above the video that scrolls chat messages from right to left.
 *
 * <p>The patch inserts it into Twitch's player layout directly above the video surface, so
 * the player controls, ads and captions stay on top. It never takes touches.
 */
@SuppressWarnings("unused")
public final class DanmakuOverlayView extends View {
    /** Players shorter than this (minimised player, small previews) show nothing. */
    private static final float MIN_HEIGHT_DP = 100f;
    /** Text size reaches 100 % of the setting at this player height. */
    private static final float FULL_SIZE_HEIGHT_DP = 360f;
    private static final float MIN_SCALE = 0.55f;
    /** Comments waiting for a free lane are dropped after this long. */
    private static final long MAX_WAIT_MS = 4_000L;
    private static final int MAX_PENDING = 60;

    private static final class Piece {
        final String text;
        final String emoteId;
        final boolean name;
        float width;

        Piece(String text, String emoteId, boolean name) {
            this.text = text;
            this.emoteId = emoteId;
            this.name = name;
        }
    }

    private static final class Item {
        final List<Piece> pieces;
        final int color;
        final float width;
        int lane;
        long startMs;

        Item(List<Piece> pieces, int color, float width) {
            this.pieces = pieces;
            this.color = color;
            this.width = width;
        }
    }

    private static final class Pending {
        final ChatMessageReader.Comment comment;
        final long queuedAtMs;

        Pending(ChatMessageReader.Comment comment, long queuedAtMs) {
            this.comment = comment;
            this.queuedAtMs = queuedAtMs;
        }
    }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF emoteRect = new RectF();
    private final List<Item> active = new ArrayList<>();
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private final LaneAllocator lanes = new LaneAllocator();
    private final Runnable redraw = this::postInvalidateOnAnimation;

    private DanmakuSettings settings;
    private float density;
    private float textSize;
    private float lineHeight;
    private float emoteSize;
    private float topPadding;
    private float gapPx;
    private long durationMs;

    public DanmakuOverlayView(Context context) {
        super(context);
        init();
    }

    public DanmakuOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public DanmakuOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        density = getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setLongClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(Color.BLACK);
        fill.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        settings = DanmakuSettings.get(getContext());
        TwitchDanmaku.register(this);
        applyMetrics();
    }

    @Override
    protected void onDetachedFromWindow() {
        TwitchDanmaku.unregister(this);
        clear();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        applyMetrics();
        if (height != oldHeight) clear();
    }

    void settingsChanged() {
        settings = DanmakuSettings.get(getContext());
        clear();
        applyMetrics();
    }

    private void clear() {
        active.clear();
        pending.clear();
        lanes.clear();
        invalidate();
    }

    private void applyMetrics() {
        if (settings == null) return;
        float heightDp = getHeight() / density;
        float scale = Math.max(MIN_SCALE, Math.min(1f, heightDp / FULL_SIZE_HEIGHT_DP));
        textSize = settings.fontSizeSp * getResources().getDisplayMetrics().scaledDensity * scale;
        lineHeight = textSize * 1.3f;
        emoteSize = textSize * 1.25f;
        topPadding = lineHeight * 0.2f;
        gapPx = textSize * 1.5f;
        durationMs = settings.durationSeconds * 1000L;
        fill.setTextSize(textSize);
        stroke.setTextSize(textSize);
        stroke.setStrokeWidth(Math.max(1.5f, textSize * 0.12f));
        Typeface typeface = settings.bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT;
        fill.setTypeface(typeface);
        stroke.setTypeface(typeface);
        int alpha = Math.round(255 * settings.opacityPercent / 100f);
        bitmapPaint.setAlpha(alpha);
        float usable = getHeight() * settings.coveragePercent / 100f - topPadding;
        lanes.resize(lineHeight <= 0f ? 0 : Math.max(0, (int) (usable / lineHeight)));
    }

    /** Adds a chat message; called on the main thread. */
    void add(ChatMessageReader.Comment comment) {
        if (settings == null || !settings.enabled || getWidth() <= 0
                || getHeight() < MIN_HEIGHT_DP * density || lanes.laneCount() == 0) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (pending.isEmpty() && active.size() < settings.maxOnScreen && place(comment, now)) {
            postInvalidateOnAnimation();
            return;
        }
        if (pending.size() >= MAX_PENDING) pending.pollFirst();
        pending.addLast(new Pending(comment, now));
        postInvalidateOnAnimation();
    }

    private boolean place(ChatMessageReader.Comment comment, long now) {
        Item item = layout(comment);
        if (item == null) return true;
        int lane = lanes.acquire(now, item.width, durationMs, getWidth(), gapPx);
        if (lane < 0) return false;
        item.lane = lane;
        item.startMs = now;
        active.add(item);
        return true;
    }

    private Item layout(ChatMessageReader.Comment comment) {
        List<Piece> pieces = new ArrayList<>(comment.segments.size() + 1);
        int color = Color.WHITE;
        if (settings.chatColors && comment.color != null) color = readable(comment.color);
        if (settings.showNames && !comment.author.isEmpty()) {
            pieces.add(new Piece(comment.author + ": ", null, true));
        }
        for (ChatTokens.Segment segment : comment.segments) {
            if (segment.isEmote() && settings.showEmotes
                    && EmoteCache.isValidId(segment.emoteId)) {
                pieces.add(new Piece(segment.text, segment.emoteId, false));
            } else if (!segment.text.isEmpty()) {
                pieces.add(new Piece(segment.text.replace('\n', ' '), null, false));
            }
        }
        if (pieces.isEmpty()) return null;
        float width = 0f;
        for (Piece piece : pieces) {
            if (piece.emoteId != null) {
                Bitmap bitmap = EmoteCache.getInstance().get(piece.emoteId, this::requestRedraw);
                piece.width = bitmap == null ? emoteSize
                        : emoteSize * bitmap.getWidth() / Math.max(1, bitmap.getHeight());
                piece.width += textSize * 0.1f;
            } else {
                piece.width = fill.measureText(piece.text);
            }
            width += piece.width;
        }
        return new Item(pieces, color, width);
    }

    private void requestRedraw() {
        removeCallbacks(redraw);
        post(redraw);
    }

    /** Lightens very dark name colours so they stay legible over video. */
    private static int readable(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color | 0xFF000000, hsv);
        // Bright and slightly desaturated, like Twitch's own readability adjustment.
        hsv[1] = Math.min(hsv[1], 0.7f);
        hsv[2] = Math.max(hsv[2], 0.92f);
        return Color.HSVToColor(hsv);
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        return false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (settings == null) return;
        long now = SystemClock.uptimeMillis();
        while (!pending.isEmpty()) {
            Pending next = pending.peekFirst();
            if (now - next.queuedAtMs > MAX_WAIT_MS) {
                pending.pollFirst();
                continue;
            }
            if (active.size() >= settings.maxOnScreen || !place(next.comment, now)) break;
            pending.pollFirst();
        }

        int width = getWidth();
        int alpha = Math.round(255 * settings.opacityPercent / 100f);
        Paint.FontMetrics metrics = fill.getFontMetrics();
        float baselineOffset = (lineHeight - (metrics.descent - metrics.ascent)) / 2f
                - metrics.ascent;
        Iterator<Item> iterator = active.iterator();
        while (iterator.hasNext()) {
            Item item = iterator.next();
            float progress = (now - item.startMs) / (float) durationMs;
            if (progress >= 1f || item.lane >= lanes.laneCount()) {
                iterator.remove();
                continue;
            }
            float x = width - progress * (width + item.width);
            float top = topPadding + item.lane * lineHeight;
            float baseline = top + baselineOffset;
            float centerY = top + lineHeight / 2f;
            for (Piece piece : item.pieces) {
                if (piece.emoteId != null) {
                    Bitmap bitmap = EmoteCache.getInstance().get(piece.emoteId,
                            this::requestRedraw);
                    if (bitmap != null) {
                        float emoteWidth = piece.width - textSize * 0.1f;
                        emoteRect.set(x, centerY - emoteSize / 2f, x + emoteWidth,
                                centerY + emoteSize / 2f);
                        canvas.drawBitmap(bitmap, null, emoteRect, bitmapPaint);
                    } else {
                        drawText(canvas, piece.text, x, baseline, item.color, alpha);
                    }
                } else {
                    int color = piece.name ? Color.argb(255, 200, 200, 200) : item.color;
                    if (piece.name && settings.chatColors) color = item.color;
                    drawText(canvas, piece.text, x, baseline, color, alpha);
                }
                x += piece.width;
            }
        }
        if (!active.isEmpty() || !pending.isEmpty()) postInvalidateOnAnimation();
    }

    private void drawText(Canvas canvas, String text, float x, float baseline,
                          int color, int alpha) {
        stroke.setAlpha(Math.round(alpha * 0.85f));
        canvas.drawText(text, x, baseline, stroke);
        fill.setColor(color);
        fill.setAlpha(alpha);
        canvas.drawText(text, x, baseline, fill);
    }
}
