package io.github.sabopura.morphe.extension.youtube.ambient;

import android.graphics.Canvas;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.view.View;

/**
 * Re-draws YouTube's page on top of the theater light with its background keyed out on the
 * GPU (AGSL, Android 13+): the page fill becomes transparent while text, icons and
 * thumbnails stay, like the desktop extension that makes the page itself see-through. The
 * real page underneath keeps handling every touch and scroll.
 */
final class PageKeyRenderer {
    /**
     * Works on {@code d}: how far a neutral (grey) pixel is from the theme's extreme (black in
     * dark theme, white in light theme) towards the text colour. It does not rely on one exact
     * background colour, because real pages mix pure black, YouTube's dark grey, gradients
     * from YouTube's own ambient mode, card fills and shadows.
     *
     * <ul>
     * <li>Neutral pixels near the extreme ({@code d} below the page band) are page background,
     * cards, borders or shadows: they are converted back to the translucent white/black
     * overlay they were, so the page fill vanishes and cards remain as faint glass.</li>
     * <li>...unless most pixels around them are imagery (inside a thumbnail), or the rows
     * above and below are the opposite brightness (inverse label of a filled button).</li>
     * <li>Brighter neutral pixels touching the page band are anti-aliased text edges and get
     * the same treatment, which gives text clean edges without a rim.</li>
     * <li>Everything else (colour, imagery, solid text) is kept as it is.</li>
     * </ul>
     */
    private static final String KEY_SHADER = ""
            + "uniform shader content;\n"
            + "uniform float dark;\n"
            + "uniform float base;\n"
            + "uniform float unit;\n"
            + "uniform float4 rects[12];\n"
            + "half3 colourAt(float2 p) {\n"
            + "  half4 c = content.eval(p);\n"
            + "  return c.a > 0.0 ? c.rgb / c.a : half3(dark > 0.5 ? 0.0 : 1.0);\n"
            + "}\n"
            + "half satOf(half3 c) {\n"
            + "  return max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));\n"
            + "}\n"
            + "half dist(half3 c) {\n"
            + "  half luma = dot(c, half3(0.299, 0.587, 0.114));\n"
            + "  return dark > 0.5 ? luma : 1.0 - luma;\n"
            + "}\n"
            + "half opposite(float2 p) { return step(0.75, dist(colourAt(p))); }\n"
            + "half pageBand(half3 c) {\n"
            + "  return (1.0 - smoothstep(0.035, 0.07, satOf(c))) * (1.0 - smoothstep(0.20, 0.26, dist(c)));\n"
            + "}\n"
            + "half other(float2 p) { return 1.0 - step(0.5, pageBand(colourAt(p))); }\n"
            + "half4 restore(half4 c, half d) {\n"
            + "  half ink = clamp((d - base) / (1.0 - base), 0.0, 1.0);\n"
            + "  return (dark > 0.5 ? half4(ink, ink, ink, ink) : half4(0.0, 0.0, 0.0, ink)) * c.a;\n"
            + "}\n"
            + "half4 main(float2 p) {\n"
            + "  half4 c = content.eval(p);\n"
            + "  if (c.a <= 0.0) { return half4(0.0); }\n"
            + "  half3 rgb = c.rgb / c.a;\n"
            + "  half grey = 1.0 - smoothstep(0.035, 0.07, satOf(rgb));\n"
            + "  if (grey <= 0.0) { return c; }\n"
            // Thumbnail rectangles found in the view tree are never touched.
            + "  for (int i = 0; i < 12; i++) {\n"
            + "    float4 box = rects[i];\n"
            + "    if (p.x >= box.x && p.y >= box.y && p.x < box.z && p.y < box.w) { return c; }\n"
            + "  }\n"
            + "  half d = dist(rgb);\n"
            + "  half band = 1.0 - smoothstep(0.20, 0.26, d);\n"
            + "  if (band > 0.0) {\n"
            + "    float r = 7.0 * unit;\n"
            + "    half rows = opposite(p + float2(0.0, r)) + opposite(p + float2(0.0, -r))\n"
            + "        + opposite(p + float2(r, r)) + opposite(p + float2(-r, -r));\n"
            // Inside a picture most of the surroundings are not page background: 16 probes
            // (three distances in four directions plus four diagonals). Text never reaches a
            // majority because most of a text block is background. A gap between two pictures
            // is ruled out separately: it is page background along its whole length.
            + "    float a = 14.0 * unit;\n"
            + "    float b = 36.0 * unit;\n"
            + "    float e = 80.0 * unit;\n"
            + "    float g = 34.0 * unit;\n"
            + "    half r1 = other(p + float2(a, 0.0));\n"
            + "    half r2 = other(p + float2(b, 0.0));\n"
            + "    half r3 = other(p + float2(e, 0.0));\n"
            + "    half l1 = other(p + float2(-a, 0.0));\n"
            + "    half l2 = other(p + float2(-b, 0.0));\n"
            + "    half l3 = other(p + float2(-e, 0.0));\n"
            + "    half d1 = other(p + float2(0.0, a));\n"
            + "    half d2 = other(p + float2(0.0, b));\n"
            + "    half d3 = other(p + float2(0.0, e));\n"
            + "    half u1 = other(p + float2(0.0, -a));\n"
            + "    half u2 = other(p + float2(0.0, -b));\n"
            + "    half u3 = other(p + float2(0.0, -e));\n"
            + "    half total = r1 + r2 + r3 + l1 + l2 + l3 + d1 + d2 + d3 + u1 + u2 + u3\n"
            + "        + other(p + float2(g, g)) + other(p + float2(-g, g))\n"
            + "        + other(p + float2(g, -g)) + other(p + float2(-g, -g));\n"
            + "    half columnGap = step(d1 + d2 + d3 + u1 + u2 + u3, 0.5);\n"
            + "    half rowGap = step(r1 + r2 + r3 + l1 + l2 + l3, 0.5);\n"
            + "    half inside = smoothstep(8.5, 9.5, total) * (1.0 - columnGap) * (1.0 - rowGap);\n"
            + "    half keep = max(smoothstep(3.5, 4.0, rows), inside);\n"
            + "    return mix(mix(c, restore(c, d), band * grey), c, keep);\n"
            + "  }\n"
            + "  half touching = max(max(pageBand(colourAt(p + float2(2.5, 0.0))),\n"
            + "      pageBand(colourAt(p + float2(-2.5, 0.0)))),\n"
            + "      max(pageBand(colourAt(p + float2(0.0, 2.5))),\n"
            + "      pageBand(colourAt(p + float2(0.0, -2.5)))));\n"
            + "  half solid = smoothstep(0.80, 0.92, d);\n"
            + "  return mix(c, restore(c, d), touching * grey * (1.0 - solid));\n"
            + "}\n";

    private RenderNode node;
    private RuntimeShader shader;
    private float appliedDark = -1f;
    private float appliedBase = -1f;
    private int recordedWidth = -1;
    private int recordedHeight = -1;
    private final int[] sourceLocation = new int[2];
    private final int[] targetLocation = new int[2];

    private static final int MAX_IMAGE_RECTS = 12;
    private final float[] imageRects = new float[MAX_IMAGE_RECTS * 4];
    private int imageRectCount;
    private int loggedRectCount = -1;
    private final int[] rectLocation = new int[2];
    /** Number of thumbnail rectangles found on the last refresh, for the status row. */
    static volatile int statusImageRects;

    /**
     * Finds video thumbnails from the shape of the view tree instead of from class names
     * (which are obfuscated and change between releases): a list item whose top part is a
     * 16:9 area with a same-width description block directly underneath, or a 16:9 view that
     * draws itself. Coordinates are relative to the page root.
     */
    private void collectImageRects(View view, int depth) {
        if (view == null || depth > 40 || imageRectCount >= MAX_IMAGE_RECTS
                || view.getVisibility() != View.VISIBLE) return;
        int width = view.getWidth();
        int height = view.getHeight();
        if (width >= 240 && height >= 120) {
            int pictureHeight = 0;
            if (view instanceof android.view.ViewGroup) {
                android.view.ViewGroup group = (android.view.ViewGroup) view;
                for (int index = 0; index < group.getChildCount(); index++) {
                    View child = group.getChildAt(index);
                    if (child.getVisibility() == View.VISIBLE
                            && child.getWidth() >= width * 0.9f
                            && child.getTop() > 0
                            && Math.abs(child.getBottom() - height) <= 6) {
                        pictureHeight = child.getTop();
                        break;
                    }
                }
            } else if (view instanceof android.widget.ImageView) {
                pictureHeight = height;
            }
            // A list item that has not been split into child views yet: a 16:9 picture
            // with a description block of plausible height underneath.
            if (pictureHeight == 0 && isListItem(view)) {
                int picture = Math.round(width * 9f / 16f);
                float density = view.getResources().getDisplayMetrics().density;
                int rest = height - picture;
                if (rest >= 36 * density && rest <= 150 * density) pictureHeight = picture;
            }
            if (pictureHeight > 0) {
                float ratio = width / (float) pictureHeight;
                if (ratio > 1.68f && ratio < 1.88f) {
                    view.getLocationInWindow(rectLocation);
                    int offset = imageRectCount * 4;
                    imageRects[offset] = rectLocation[0] - sourceLocation[0];
                    imageRects[offset + 1] = rectLocation[1] - sourceLocation[1];
                    imageRects[offset + 2] = imageRects[offset] + width;
                    imageRects[offset + 3] = imageRects[offset + 1] + pictureHeight;
                    imageRectCount++;
                }
            }
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                collectImageRects(group.getChildAt(index), depth + 1);
            }
        }
    }

    private static boolean isListItem(View view) {
        android.view.ViewParent parent = view.getParent();
        return parent != null && parent.getClass().getName().contains("RecyclerView");
    }

    static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }

    /**
     * Draws {@code page} (YouTube's content root) into {@code canvas} of {@code target}, with
     * the page background keyed out.
     *
     * @param backgroundLuma brightness (0-1) of the theme's page background colour
     * @param refresh        re-run the key now; otherwise the previous keyed result is reused
     *                       and only re-rendered by the system when the page itself redraws
     */
    void draw(Canvas canvas, View target, View page, boolean darkTheme, float backgroundLuma,
              float alpha, boolean refresh) {
        if (node == null) {
            node = new RenderNode("sabopura-page-key");
            shader = new RuntimeShader(KEY_SHADER);
        }
        float dark = darkTheme ? 1f : 0f;
        // Distance of the plain page background from the theme's extreme; subtracted so the
        // normal background becomes fully transparent instead of a faint haze.
        float base = Math.max(0f, Math.min(0.12f,
                darkTheme ? backgroundLuma : 1f - backgroundLuma));
        if (dark != appliedDark || base != appliedBase) {
            shader.setFloatUniform("dark", dark);
            shader.setFloatUniform("base", base);
            shader.setFloatUniform("unit", page.getResources().getDisplayMetrics().density);
            node.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"));
            appliedDark = dark;
            appliedBase = base;
        }
        page.getLocationInWindow(sourceLocation);
        target.getLocationInWindow(targetLocation);
        int width = page.getWidth();
        int height = page.getHeight();
        node.setPosition(0, 0, width, height);
        node.setTranslationX(sourceLocation[0] - targetLocation[0]);
        node.setTranslationY(sourceLocation[1] - targetLocation[1]);
        if (refresh || width != recordedWidth || height != recordedHeight
                || !node.hasDisplayList()) {
            recordedWidth = width;
            recordedHeight = height;
            java.util.Arrays.fill(imageRects, 0f);
            imageRectCount = 0;
            page.getLocationInWindow(sourceLocation);
            collectImageRects(page, 0);
            statusImageRects = imageRectCount;
            if (imageRectCount != loggedRectCount && android.util.Log.isLoggable(
                    AmbientLightController.TAG, android.util.Log.DEBUG)) {
                loggedRectCount = imageRectCount;
                android.util.Log.d(AmbientLightController.TAG, "Thumbnail rects=" + imageRectCount
                        + " " + java.util.Arrays.toString(
                        java.util.Arrays.copyOf(imageRects, imageRectCount * 4)));
            }
            shader.setFloatUniform("rects", imageRects);
            // Uniforms are captured when the effect is created.
            node.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"));
            RecordingCanvas recording = node.beginRecording(width, height);
            try {
                // Only the root's own drawing commands are recorded; every child is
                // referenced through its existing display list, so this is cheap. The
                // expensive part is the shader pass, which runs when this list changes.
                page.draw(recording);
            } finally {
                node.endRecording();
            }
        }
        node.setAlpha(alpha);
        canvas.drawRenderNode(node);
    }
}
