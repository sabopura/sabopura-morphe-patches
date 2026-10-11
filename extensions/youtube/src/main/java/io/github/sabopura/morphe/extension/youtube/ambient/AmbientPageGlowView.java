package io.github.sabopura.morphe.extension.youtube.ambient;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.drawable.ColorDrawable;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.AnimationUtils;

import java.lang.ref.WeakReference;

import io.github.sabopura.morphe.extension.youtube.shared.PlayerHooks;
import io.github.sabopura.morphe.extension.youtube.shared.Views;

/**
 * Window-level layer that lets the video's light spill out of the player onto the page,
 * the status bar area and the panels around it, like the desktop Ambient light extension.
 * It sits above the page content and below the danmaku overlay and never draws over the
 * player.
 *
 * <p>Colour levels add the light with a SCREEN blend in dark theme (the "maximum" level also
 * lays a translucent colour veil over the page). The theater level instead makes the page
 * background itself transparent ({@link SeeThroughPage}) and paints the light underneath
 * the page, so YouTube's own text, icons and thumbnails sit directly on the light in either
 * theme. Where the background hook is missing it falls back to drawing the page again on top
 * of the light with its background keyed out (Android 13+) or a LIGHTEN/DARKEN composite.
 *
 * <p>While the user scrolls or touches the page the light is toned down, and it returns
 * gradually once they have been idle for a while.
 */
final class AmbientPageGlowView extends View {
    private static final long FADE_DURATION_MS = 450L;
    /** How quickly the light gets out of the way when the user starts interacting. */
    private static final long DIM_DURATION_MS = 180L;
    /** How slowly the light returns after the idle delay. */
    private static final long RESTORE_DURATION_MS = 1_600L;
    /** Remaining light while the user is interacting. */
    private static final float INTERACTION_LEVEL = 0.25f;
    /** Light-theme pages are white; a plain tint must stay subtle there. */
    private static final float LIGHT_THEME_ALPHA = 0.45f;
    /** While paused the page copy still follows page changes at this interval. */
    private static final long IDLE_REFRESH_MS = 250L;

    private final Paint lightPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint coverPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint blendPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final RectF destination = new RectF();
    private final Rect hole = new Rect();
    private final Object frameLock = new Object();
    private final Runnable restoreCheck = this::postInvalidateOnAnimation;
    private final ViewTreeObserver.OnScrollChangedListener scrollListener =
            this::onScrollChanged;
    /** Scrolls count as the user's only this long after they touched the page. */
    private static final long TOUCH_SCROLL_WINDOW_MS = 3_000L;
    private long lastPageTouchUptime;
    /** Values of the last drawn frame, for the status row. */
    private static volatile float shownAlpha;
    private static volatile float shownFactor = 1f;
    private static volatile long shownInteractionUptime;
    /** Compact state for the on-screen diagnostics line. */
    static volatile String debugState = "-";
    static volatile long lastDrawUptime;

    /** Current strength and dim state for the status row. */
    static String statusLight() {
        long since = shownInteractionUptime == 0 ? -1
                : (SystemClock.uptimeMillis() - shownInteractionUptime) / 1000;
        return Math.round(shownAlpha * 100) + "% (dim " + Math.round(shownFactor * 100)
                + "%, idle " + since + "s)";
    }

    private void updateDebugState() {
        boolean hasFrame;
        synchronized (frameLock) {
            hasFrame = frontBitmap != null && frameWidth == getWidth()
                    && frameHeight == getHeight();
        }
        View watchPage = hostRef.get();
        android.graphics.drawable.Drawable hostBackground =
                watchPage == null ? null : watchPage.getBackground();
        debugState = "L" + Math.round(alpha * 100) + " d" + Math.round(interactionFactor * 100)
                + " t" + Math.round(targetAlpha * 100) + (hasFrame ? " frm" : " nofrm")
                + (behindPage ? " behind" : " over") + " host:"
                + (watchPage == null ? "none" : !watchPage.isAttachedToWindow() ? "detached"
                        : SeeThroughPage.isLightDrawable(hostBackground) ? "light"
                        : hostBackground == null ? "null"
                        : hostBackground.getClass().getSimpleName());
    }
    private boolean loggedInteracting;
    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = () -> {
        pageDirty = true;
        invalidate();
    };
    /** The keyed page copy is re-run at least this often even without scrolling. */
    private static final long PAGE_REFRESH_MS = 300L;
    private boolean pageDirty = true;
    private long lastPageRefreshUptime;
    private long lastThemeCheckUptime;
    private final PageKeyRenderer pageKey = new PageKeyRenderer();
    private WeakReference<View> pageRootRef = new WeakReference<>(null);
    /** The watch page (light is painted as its background) and the player inside it. */
    private WeakReference<View> hostRef = new WeakReference<>(null);
    private WeakReference<View> playerRef = new WeakReference<>(null);
    /** True while the light is drawn underneath a see-through page. */
    private boolean behindPage;
    private int backgroundColour = 0xFF0F0F0F;
    /** Brightest / darkest luma (0-1) of the current light frame. */
    private volatile float framePeak;
    private volatile float frameFloor = 1f;
    /** Strength of the page-coloured veil that keeps text readable over the light. */
    private float guard;
    /** White text needs the light below this luma; black text needs it above the other. */
    private static final float DARK_PAGE_MAX_LUMA = 0.5f;
    private static final float LIGHT_PAGE_MIN_LUMA = 0.6f;
    private final int[] hostLocation = new int[2];
    private final Rect hostRect = new Rect();

    private Bitmap frontBitmap;
    private Bitmap backBitmap;
    private final Rect frameHole = new Rect();
    private int frameWidth;
    private int frameHeight;

    private float alpha;
    private float targetAlpha;
    private long lastAlphaUptime;
    private float interactionFactor = 1f;
    private long lastInteractionUptime;
    private long lastFactorUptime;
    private int colorFilterGeneration = -1;
    private Boolean darkTheme;
    /** Brightness (0-1) of YouTube's page background colour. */
    private float backgroundLuma = 0.059f;

    AmbientPageGlowView(Context context) {
        super(context);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setWillNotDraw(false);
        lightPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
    }

    /** Worker thread: reusable buffer for the next frame. */
    Bitmap obtainBackBuffer(int width, int height) {
        synchronized (frameLock) {
            Bitmap back = backBitmap;
            if (back != null && back.getWidth() == width && back.getHeight() == height) {
                backBitmap = null;
                return back;
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    }

    /** Worker thread: publishes a frame computed for a page of {@code width x height}. */
    void publish(Bitmap bitmap, Rect playerHole, int width, int height) {
        // Brightest and darkest areas of the light, for the readability guard of the
        // see-through page. Sampled here on the worker thread.
        // Percentiles rather than extremes: one bright spot next to the player must not
        // darken the whole page.
        int stepX = Math.max(1, bitmap.getWidth() / 12);
        int stepY = Math.max(1, bitmap.getHeight() / 20);
        int[] lumas = new int[(bitmap.getWidth() / stepX + 1) * (bitmap.getHeight() / stepY + 1)];
        int count = 0;
        for (int y = stepY / 2; y < bitmap.getHeight(); y += stepY) {
            for (int x = stepX / 2; x < bitmap.getWidth(); x += stepX) {
                lumas[count++] = AmbientMath.luma(bitmap.getPixel(x, y));
            }
        }
        if (count > 0) {
            java.util.Arrays.sort(lumas, 0, count);
            framePeak = lumas[Math.min(count - 1, count * 85 / 100)] / 255f;
            frameFloor = lumas[count * 15 / 100] / 255f;
        }
        synchronized (frameLock) {
            backBitmap = frontBitmap;
            frontBitmap = bitmap;
            frameHole.set(playerHole);
            frameWidth = width;
            frameHeight = height;
        }
        postInvalidateOnAnimation();
    }

    /**
     * Called by the capture loop for every published frame and about once a second: the
     * calm-down after scrolling must end even when nothing else redraws this view.
     */
    void onTick() {
        if (interactionFactor < 1f || alpha != targetAlpha) postInvalidateOnAnimation();
    }

    void setActive(boolean active) {
        float value = active ? 1f : 0f;
        if (targetAlpha != value) {
            targetAlpha = value;
            lastAlphaUptime = AnimationUtils.currentAnimationTimeMillis();
            postInvalidateOnAnimation();
            updateSeeThrough();
        }
    }

    void onSettingsChanged() {
        colorFilterGeneration = -1;
        darkTheme = null;
        postInvalidateOnAnimation();
        updateSeeThrough();
    }

    void setWatchPage(View host, View player) {
        hostRef = new WeakReference<>(host);
        playerRef = new WeakReference<>(player);
        updateSeeThrough();
    }

    /**
     * Main thread; also called about once a second so backgrounds created later are covered.
     * Switches the watch page between its normal and its see-through state.
     */
    void updateSeeThrough() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            post(this::updateSeeThrough);
            return;
        }
        View host = hostRef.get();
        boolean wanted = host != null && host.isAttachedToWindow() && targetAlpha > 0f
                && AmbientLightSettings.getProfile().videoBackdrop
                && SeeThroughPage.isAvailable();
        // The page colour must be read before the backgrounds are cleared.
        if (wanted && !behindPage) {
            darkTheme = null;
            isDarkTheme();
        }
        SeeThroughPage.update(this, host, playerRef.get(), wanted, backgroundColour);
        if (behindPage != wanted) {
            behindPage = wanted;
            pageDirty = true;
            invalidate();
        }
    }

    /**
     * Called by the watch page's background drawable: the page colour, then the light on
     * top of it at the current strength. The canvas is in the host's coordinates.
     */
    void drawBehindPage(Canvas canvas, View host) {
        canvas.drawColor(backgroundColour | 0xFF000000);
        Bitmap bitmap;
        synchronized (frameLock) {
            bitmap = frontBitmap;
            if (bitmap == null || frameWidth != getWidth() || frameHeight != getHeight()) return;
        }
        float visibility = alpha * interactionFactor;
        if (visibility <= 0f) return;
        updatePaints();
        host.getLocationInWindow(hostLocation);
        int save = canvas.save();
        canvas.translate(-hostLocation[0], -hostLocation[1]);
        destination.set(0, 0, getWidth(), getHeight());
        coverPaint.setAlpha(Math.round(255 * visibility));
        canvas.drawBitmap(bitmap, null, destination, coverPaint);
        canvas.restoreToCount(save);
        // Readability guard: YouTube's text is white on a dark page and black on a light
        // one, so a light that is too bright (or too dark) is pulled towards black (or
        // white) just enough. It eases in and out so scene cuts do not make it pump.
        boolean dark = backgroundLuma < 0.5f;
        float wanted = dark
                ? (framePeak <= DARK_PAGE_MAX_LUMA ? 0f : 1f - DARK_PAGE_MAX_LUMA / framePeak)
                : (frameFloor >= LIGHT_PAGE_MIN_LUMA ? 0f
                        : 1f - (1f - LIGHT_PAGE_MIN_LUMA) / (1f - frameFloor));
        guard += (wanted - guard) * 0.12f;
        int guardAlpha = Math.round(255 * guard * visibility);
        if (guardAlpha > 0) {
            canvas.drawColor((guardAlpha << 24) | (dark ? 0x000000 : 0xFFFFFF));
        }
        // The status bar icons above the player are white in either theme; on a light page
        // YouTube keeps that strip black, and so does this.
        int stripBottom;
        synchronized (frameLock) {
            stripBottom = frameHole.top - hostLocation[1];
        }
        if (!dark && stripBottom > 0) {
            int strip = canvas.save();
            canvas.clipRect(0, 0, host.getWidth(), stripBottom);
            canvas.drawColor(0xFF000000);
            canvas.restoreToCount(strip);
        }
        AmbientLightController.statusRenderer = Views.isJapanese()
                ? "シアター：背景そのものを透明化" : "theater: transparent page background";
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnScrollChangedListener(scrollListener);
        getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        ViewTreeObserver observer = getViewTreeObserver();
        if (observer.isAlive()) {
            observer.removeOnScrollChangedListener(scrollListener);
            observer.removeOnGlobalLayoutListener(layoutListener);
        }
        removeCallbacks(restoreCheck);
    }

    /**
     * Observes touches without consuming them: returning false lets every event continue to
     * the views underneath. Touches on the player itself are about the video, not the page.
     */
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            boolean onPlayer;
            synchronized (frameLock) {
                onPlayer = frameHole.contains((int) event.getX(), (int) event.getY());
            }
            if (!onPlayer) {
                lastPageTouchUptime = SystemClock.uptimeMillis();
                onUserInteraction();
            }
        }
        return false;
    }

    /**
     * Scroll notifications come from every scrolling view in the window, including ones
     * that move by themselves - inline previews, carousels and tickers among the related
     * videos. Counting those as the user kept the light dimmed for good once they were on
     * screen. Only scrolls shortly after a touch on the page (a drag and its fling) count.
     */
    private void onScrollChanged() {
        if (SystemClock.uptimeMillis() - lastPageTouchUptime <= TOUCH_SCROLL_WINDOW_MS) {
            onUserInteraction();
        } else {
            // The theater fallback still has to follow the page.
            pageDirty = true;
            if (alpha > 0f) invalidate();
        }
    }

    private void onUserInteraction() {
        // The theater page copy must follow scrolling immediately.
        pageDirty = true;
        if (alpha > 0f) invalidate();
        if (!AmbientLightSettings.isInteractionDimEnabled() || alpha <= 0f && targetAlpha <= 0f) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        // Scroll callbacks arrive every frame; only reschedule occasionally.
        if (now - lastInteractionUptime < 100L) return;
        lastInteractionUptime = now;
        removeCallbacks(restoreCheck);
        postDelayed(restoreCheck, AmbientLightSettings.getIdleDelayMs());
    }

    @Override protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        darkTheme = null;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = AnimationUtils.currentAnimationTimeMillis();
        if (alpha != targetAlpha) {
            float step = (now - lastAlphaUptime) / (float) FADE_DURATION_MS;
            alpha = targetAlpha > alpha ? Math.min(targetAlpha, alpha + step)
                    : Math.max(targetAlpha, alpha - step);
            lastAlphaUptime = now;
            if (alpha != targetAlpha) postInvalidateOnAnimation();
        }
        updateInteractionFactor(now);
        View host = behindPage ? hostRef.get() : null;
        if (behindPage) SeeThroughPage.keep();
        lastDrawUptime = SystemClock.uptimeMillis();
        shownAlpha = alpha;
        shownFactor = interactionFactor;
        shownInteractionUptime = lastInteractionUptime;
        // Diagnostics text is only built while someone looks at it.
        if (AmbientLightController.diagnosticsShown()) updateDebugState();
        // The light itself is painted by the watch page's background; this view keeps the
        // timing and covers what lies outside the watch page (the navigation bar strip).
        if (host != null) host.invalidate();
        if (alpha <= 0f) return;
        Bitmap bitmap;
        synchronized (frameLock) {
            bitmap = frontBitmap;
            if (bitmap == null || frameWidth != getWidth() || frameHeight != getHeight()) return;
            hole.set(frameHole);
        }
        updatePaints();
        boolean dark = isDarkTheme();
        ImmersionProfile profile = AmbientLightSettings.getProfile();
        float visibility = alpha * interactionFactor;

        int save = canvas.save();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            canvas.clipOutRect(hole);
        } else {
            canvas.clipRect(hole, android.graphics.Region.Op.DIFFERENCE);
        }
        destination.set(0, 0, getWidth(), getHeight());
        if (host != null) {
            host.getLocationInWindow(hostLocation);
            hostRect.set(hostLocation[0], hostLocation[1], hostLocation[0] + host.getWidth(),
                    hostLocation[1] + host.getHeight());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                canvas.clipOutRect(hostRect);
            } else {
                canvas.clipRect(hostRect, android.graphics.Region.Op.DIFFERENCE);
            }
            coverPaint.setAlpha(Math.round(255 * visibility));
            canvas.drawBitmap(bitmap, null, destination, coverPaint);
        } else if (profile.videoBackdrop) {
            drawTheater(canvas, bitmap, visibility, dark);
        } else if (dark) {
            float cover = profile.cover;
            // Veil first (hides page content for maximum immersion), then additive light.
            if (cover > 0f) {
                coverPaint.setAlpha(Math.round(255 * cover * visibility));
                canvas.drawBitmap(bitmap, null, destination, coverPaint);
            }
            // Under a veil the additive pass only adds depth; at full strength it would
            // wash the page out to a milky pastel.
            lightPaint.setAlpha(Math.round(255 * visibility * (1f - 0.8f * cover)));
            canvas.drawBitmap(bitmap, null, destination, lightPaint);
            AmbientLightController.statusRenderer = Views.isJapanese()
                    ? "色の光をページに重ねる" : "colour light over the page";
        } else {
            float tint = Math.max(LIGHT_THEME_ALPHA, profile.cover);
            coverPaint.setAlpha(Math.round(255 * tint * visibility));
            canvas.drawBitmap(bitmap, null, destination, coverPaint);
            AmbientLightController.statusRenderer = Views.isJapanese()
                    ? "色の光をページに重ねる（ライトテーマ）" : "colour tint (light theme)";
        }
        canvas.restoreToCount(save);
    }

    /**
     * Theater: the light covers the whole screen opaquely, then the page is drawn again on
     * top with its background keyed out so only text, icons and thumbnails remain.
     */
    private void drawTheater(Canvas canvas, Bitmap light, float visibility, boolean dark) {
        View pageRoot = PageKeyRenderer.isSupported() && canvas.isHardwareAccelerated()
                ? pageRoot() : null;
        boolean japanese = Views.isJapanese();
        if (pageRoot != null && pageRoot.getWidth() > 0 && pageRoot.getHeight() > 0) {
            coverPaint.setAlpha(Math.round(255 * visibility));
            canvas.drawBitmap(light, null, destination, coverPaint);
            // Above the player there is only YouTube's status bar scrim (the system draws
            // the clock and icons itself), so the light is left uncovered there.
            int strip = canvas.save();
            if (hole.top > 0) canvas.clipRect(0, hole.top, getWidth(), getHeight());
            long uptime = SystemClock.uptimeMillis();
            boolean refresh = pageDirty || uptime - lastPageRefreshUptime >= PAGE_REFRESH_MS;
            if (uptime - lastThemeCheckUptime >= 3_000L) {
                lastThemeCheckUptime = uptime;
                darkTheme = null;
                dark = isDarkTheme();
            }
            if (refresh) {
                pageDirty = false;
                lastPageRefreshUptime = uptime;
            }
            pageKey.draw(canvas, this, pageRoot, dark, backgroundLuma, visibility, refresh);
            canvas.restoreToCount(strip);
            AmbientLightController.statusRenderer = japanese
                    ? "シアター：ページ透過（GPU）" : "theater: see-through page (GPU)";
        } else {
            // Without AGSL: the light replaces the page background wherever it is brighter
            // (dark theme) or darker (light theme) than the page.
            blendPaint.setXfermode(new PorterDuffXfermode(
                    dark ? PorterDuff.Mode.LIGHTEN : PorterDuff.Mode.DARKEN));
            blendPaint.setAlpha(Math.round(255 * visibility));
            canvas.drawBitmap(light, null, destination, blendPaint);
            AmbientLightController.statusRenderer = japanese
                    ? "シアター：簡易合成（Android 12以前）" : "theater: blend fallback (Android 12-)";
        }
        // New light frames and scrolling invalidate this view; while paused, still follow
        // page changes such as loading images at a low rate.
        if (!PlayerHooks.isPlaybackAdvancing()) postInvalidateDelayed(IDLE_REFRESH_MS);
    }

    /** YouTube's full-screen content root (the watch page and everything under it). */
    private View pageRoot() {
        View cached = pageRootRef.get();
        if (cached != null && cached.isAttachedToWindow()) return cached;
        View root = getRootView();
        int id = Views.resource(getContext(), "id", "slim_status_bar_player_container");
        View found = id == 0 ? null : root.findViewById(id);
        if (found == null) found = root.findViewById(android.R.id.content);
        pageRootRef = new WeakReference<>(found);
        return found;
    }

    /** Dims quickly while the user interacts, then restores slowly after the idle delay. */
    private void updateInteractionFactor(long now) {
        long uptime = SystemClock.uptimeMillis();
        boolean interacting = AmbientLightSettings.isInteractionDimEnabled()
                && lastInteractionUptime != 0
                && uptime - lastInteractionUptime < AmbientLightSettings.getIdleDelayMs();
        float target = interacting ? INTERACTION_LEVEL : 1f;
        if (interacting != loggedInteracting && android.util.Log.isLoggable(
                AmbientLightController.TAG, android.util.Log.DEBUG)) {
            loggedInteracting = interacting;
            android.util.Log.d(AmbientLightController.TAG, "Interacting=" + interacting
                    + " alpha=" + alpha + " target=" + targetAlpha);
        }
        long elapsed = lastFactorUptime == 0 ? 0 : Math.max(0, now - lastFactorUptime);
        lastFactorUptime = now;
        if (interactionFactor == target) return;
        float range = 1f - INTERACTION_LEVEL;
        if (target < interactionFactor) {
            interactionFactor = Math.max(target,
                    interactionFactor - range * elapsed / (float) DIM_DURATION_MS);
        } else {
            interactionFactor = Math.min(target,
                    interactionFactor + range * elapsed / (float) RESTORE_DURATION_MS);
        }
        if (interactionFactor != target) postInvalidateOnAnimation();
    }

    private boolean isDarkTheme() {
        Boolean cached = darkTheme;
        if (cached != null) return cached;
        boolean dark = detectDarkTheme();
        darkTheme = dark;
        return dark;
    }

    /**
     * YouTube can use its own theme regardless of the system setting, and the window's
     * decor context does not always carry the activity's night mode. The page background
     * colour of YouTube's theme is the reliable signal.
     */
    private boolean detectDarkTheme() {
        // While the page is see-through its views no longer carry the colour.
        if (behindPage) return backgroundLuma < 0.5f;
        android.app.Activity activity = Views.findActivity(getContext());
        Context context = activity != null ? activity : getContext();
        // 1. The colour the watch page really paints. Other patches can restyle YouTube
        //    (pure-black themes, custom colours) without touching the theme attributes.
        View root = getRootView();
        for (String name : BACKGROUND_VIEW_IDS) {
            int id = Views.resource(context, "id", name);
            View view = id == 0 || root == null ? null : root.findViewById(id);
            if (view != null && view.getBackground() instanceof ColorDrawable) {
                int color = ((ColorDrawable) view.getBackground()).getColor();
                if ((color >>> 24) >= 0xF0) return useBackground(color, "view:" + name);
            }
        }
        // 2. YouTube's theme attribute.
        int attr = Views.resource(context, "attr", "ytBaseBackground");
        if (attr != 0) {
            TypedValue value = new TypedValue();
            if (context.getTheme().resolveAttribute(attr, value, true)
                    && value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return useBackground(value.data, "theme");
            }
        }
        // 3. The system setting.
        boolean night = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        return useBackground(night ? 0xFF0F0F0F : 0xFFFFFFFF, "system");
    }

    private static final String[] BACKGROUND_VIEW_IDS = {
            "video_info_loading_layout", "pane_fragment_container",
            "watch_while_layout_coordinator_layout"
    };

    private boolean useBackground(int color, String source) {
        int luma = (((color >> 16) & 0xFF) * 299 + ((color >> 8) & 0xFF) * 587
                + (color & 0xFF) * 114) / 1000;
        backgroundLuma = luma / 255f;
        backgroundColour = color | 0xFF000000;
        boolean dark = luma < 128;
        AmbientLightController.statusBackground = String.format("#%06X (%s, %s)",
                color & 0xFFFFFF, dark ? "dark" : "light", source);
        return dark;
    }

    private void updatePaints() {
        int generation = AmbientLightSettings.getGeneration();
        if (generation == colorFilterGeneration) return;
        colorFilterGeneration = generation;
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(AmbientLightSettings.getSaturation() / 100f);
        float brightness = AmbientLightSettings.getBrightness() / 100f;
        ColorMatrix scale = new ColorMatrix();
        scale.setScale(brightness, brightness, brightness, 1f);
        matrix.postConcat(scale);
        ColorMatrixColorFilter filter = new ColorMatrixColorFilter(matrix);
        lightPaint.setColorFilter(filter);
        coverPaint.setColorFilter(filter);
        blendPaint.setColorFilter(filter);
    }
}
