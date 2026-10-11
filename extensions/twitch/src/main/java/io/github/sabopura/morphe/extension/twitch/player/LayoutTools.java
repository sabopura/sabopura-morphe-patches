package io.github.sabopura.morphe.extension.twitch.player;

import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.Size;
import android.view.View;
import android.view.ViewParent;

import io.github.sabopura.morphe.extension.twitch.chat.ChatSettings;

import java.util.Map;
import java.util.WeakHashMap;

/** Size hooks run at the native layout calculation, before drag boundaries are calculated. */
public final class LayoutTools {
    private static final Map<View, Sized> MINI = new WeakHashMap<>();
    private static final Map<View, Background> BACKGROUNDS = new WeakHashMap<>();
    private static final Map<View, Navigation> NAVIGATION = new WeakHashMap<>();

    private LayoutTools() {}

    public static int chatWidth(View view, int original) {
        if (!hasId(view, "landscape_chat_container")) return original;
        SharedPreferences p = ChatSettings.preferences(view.getContext());
        if (!p.getBoolean("landscape_width_enabled", false)) return original;
        // Native 50% is the side-by-side video/chat layout; phone/tablet defaults are 30/20%.
        return clamp(
                p.getInt(
                        original == 50 ? "landscape_split_width" : "landscape_width",
                        original == 50 ? 50 : 30),
                10,
                70);
    }

    public static Size miniSize(View view, Size original) {
        if (original == null) return null;
        SharedPreferences p = ChatSettings.preferences(view.getContext());
        Sized previous = MINI.get(view);
        Size base = previous != null && original.equals(previous.scaled) ? previous.base : original;
        int percent = clamp(p.getInt("mini_player_scale", 100), 50, 200);
        if (!p.getBoolean("mini_player_scale_enabled", false) || percent == 100) {
            MINI.remove(view);
            return base;
        }
        int availableWidth =
                view.getWidth() > 0
                        ? view.getWidth()
                        : view.getResources().getDisplayMetrics().widthPixels;
        int availableHeight =
                view.getHeight() > 0
                        ? view.getHeight()
                        : view.getResources().getDisplayMetrics().heightPixels;
        float ratio =
                Math.min(
                        percent / 100f,
                        Math.min(
                                Math.max(1, availableWidth - 32)
                                        / (float) Math.max(1, base.getWidth()),
                                Math.max(1, availableHeight - 64)
                                        / (float) Math.max(1, base.getHeight())));
        Size scaled =
                new Size(
                        Math.max(1, Math.round(base.getWidth() * ratio)),
                        Math.max(1, Math.round(base.getHeight() * ratio)));
        MINI.put(view, new Sized(base, scaled));
        return scaled;
    }

    public static Object miniNativeSize(View view, Object original) {
        if (original == null) return null;
        try {
            Class<?> type = original.getClass();
            int width = ((Number) type.getMethod("getWidth").invoke(original)).intValue(),
                    height = ((Number) type.getMethod("getHeight").invoke(original)).intValue();
            Size size = miniSize(view, new Size(width, height));
            return size.getWidth() == width && size.getHeight() == height
                    ? original
                    : type.getConstructor(int.class, int.class)
                            .newInstance(size.getWidth(), size.getHeight());
        } catch (Exception ignored) {
            return original;
        }
    }

    public static void applyBackgrounds(View root) {
        applyNavigation(root);
        SharedPreferences p = ChatSettings.preferences(root.getContext());
        boolean enabled =
                p.getBoolean("landscape_opacity_enabled", false)
                        && root.getResources().getConfiguration().orientation
                                == Configuration.ORIENTATION_LANDSCAPE;
        if (!enabled && BACKGROUNDS.isEmpty()) return;
        int color =
                Color.argb(
                        Math.round(clamp(p.getInt("landscape_opacity", 30), 0, 100) * 2.55f),
                        0,
                        0,
                        0);
        for (String name :
                new String[] {
                    "landscape_chat_container",
                    "chat_view_delegate_root",
                    "main_chomments_container"
                }) {
            int id =
                    root.getResources()
                            .getIdentifier(name, "id", root.getContext().getPackageName());
            if (id == 0) continue;
            View view = root.findViewById(id);
            if (view == null) continue;
            boolean landscape = false;
            for (View at = view; at != null; ) {
                if (hasId(at, "landscape_chat_container")) {
                    landscape = true;
                    break;
                }
                ViewParent parent = at.getParent();
                at = parent instanceof View ? (View) parent : null;
            }
            if (enabled && landscape) {
                Background saved = BACKGROUNDS.get(view);
                if (saved == null) {
                    saved = new Background(view.getBackground());
                    BACKGROUNDS.put(view, saved);
                }
                Drawable current = view.getBackground();
                if (!(current instanceof ColorDrawable)
                        || ((ColorDrawable) current).getColor() != color)
                    view.setBackground(new ColorDrawable(color));
            } else {
                Background saved = BACKGROUNDS.remove(view);
                if (saved != null) view.setBackground(saved.original);
            }
        }
    }

    public static void applyNavigation(View root) {
        String mode =
                ChatSettings.preferences(root.getContext())
                        .getString("navigation_position", "default");
        if ("default".equals(mode) && NAVIGATION.isEmpty()) return;
        View nav = find(root, "viewer_landing_bottom_navigation_group");
        if (nav == null || !(nav.getParent() instanceof View)) return;
        View parent = (View) nav.getParent(),
                body = find(parent, "fragment_container"),
                rating = find(parent, "rating_banner_container");
        if (body == null) return;
        Navigation saved = NAVIGATION.get(nav);
        if ("default".equals(mode)) {
            if (saved != null) {
                saved.restore(nav, body, rating);
                NAVIGATION.remove(nav);
            }
            return;
        }
        if (saved == null) {
            saved = new Navigation(nav, body, rating);
            NAVIGATION.put(nav, saved);
        }
        if ("hidden".equals(mode)) {
            if (nav.getVisibility() != View.GONE) saved.visibility = nav.getVisibility();
            if (nav.getVisibility() != View.GONE) nav.setVisibility(View.GONE);
            nav.setTranslationY(saved.navY);
            body.setTranslationY(saved.bodyY);
            if (rating != null) rating.setTranslationY(saved.ratingY);
            saved.hidden = true;
        } else {
            if (saved.hidden) {
                nav.setVisibility(saved.visibility);
                saved.hidden = false;
            }
            boolean visible = nav.getVisibility() == View.VISIBLE;
            nav.setTranslationY(visible ? saved.navY - nav.getTop() : saved.navY);
            body.setTranslationY(saved.bodyY + (visible ? nav.getHeight() : 0));
            if (rating != null)
                rating.setTranslationY(saved.ratingY + (visible ? nav.getHeight() : 0));
        }
    }

    private static View find(View root, String name) {
        int id = root.getResources().getIdentifier(name, "id", root.getContext().getPackageName());
        return id == 0 ? null : root.findViewById(id);
    }

    private static boolean hasId(View view, String name) {
        int id = view.getResources().getIdentifier(name, "id", view.getContext().getPackageName());
        return id != 0 && view.getId() == id;
    }

    private static int clamp(int n, int min, int max) {
        return Math.max(min, Math.min(max, n));
    }

    private static final class Sized {
        final Size base, scaled;

        Sized(Size b, Size s) {
            base = b;
            scaled = s;
        }
    }

    private static final class Background {
        final Drawable original;

        Background(Drawable d) {
            original = d;
        }
    }

    private static final class Navigation {
        final float navY, bodyY, ratingY;
        int visibility;
        boolean hidden;

        Navigation(View nav, View body, View rating) {
            navY = nav.getTranslationY();
            bodyY = body.getTranslationY();
            ratingY = rating == null ? 0 : rating.getTranslationY();
            visibility = nav.getVisibility();
        }

        void restore(View nav, View body, View rating) {
            nav.setTranslationY(navY);
            body.setTranslationY(bodyY);
            if (rating != null) rating.setTranslationY(ratingY);
            if (hidden) nav.setVisibility(visibility);
        }
    }
}
