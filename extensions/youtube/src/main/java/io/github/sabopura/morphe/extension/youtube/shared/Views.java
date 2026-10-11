package io.github.sabopura.morphe.extension.youtube.shared;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.util.Locale;

/** Small view helpers shared by the extension features. */
public final class Views {
    private Views() {
    }

    /** Tag of the surface the comments are drawn on; it is never the video. */
    public static final String OWN_SURFACE_TAG = "sabopura_comment_surface";

    public static boolean isOwnSurface(View view) {
        return OWN_SURFACE_TAG.equals(view.getTag());
    }

    /**
     * True for a muted inline preview: a player embedded in a scrolling list (home feed,
     * related videos). The watch page's player - expanded, fullscreen, miniplayer or
     * picture-in-picture - never sits inside a list, while YouTube moves it out of
     * watch_player for the miniplayer.
     */
    public static boolean isInlinePreview(View view) {
        if (view == null) return false;
        // Called every frame from the main thread: each view's answer is reused for a second.
        long now = android.os.SystemClock.uptimeMillis();
        long[] cached = previewChecks.get(view);
        if (cached != null && now - cached[0] < 1_000L) return cached[1] != 0;
        boolean preview = findListAncestor(view);
        previewChecks.put(view, new long[]{now, preview ? 1 : 0});
        return preview;
    }

    private static final java.util.WeakHashMap<View, long[]> previewChecks =
            new java.util.WeakHashMap<>();

    private static boolean findListAncestor(View view) {
        for (ViewParent parent = view.getParent(); parent instanceof View;
             parent = parent.getParent()) {
            for (Class<?> type = parent.getClass(); type != null && type != View.class;
                 type = type.getSuperclass()) {
                if (type.getName().endsWith("RecyclerView")) return true;
            }
        }
        return false;
    }

    /**
     * Whether a newly built player overlay should replace the current one. An inline
     * preview never replaces a player that is still on screen.
     */
    public static boolean shouldAdoptPlayer(View current, View candidate) {
        if (current == null || current == candidate || !current.isAttachedToWindow()) {
            return true;
        }
        return !isInlinePreview(candidate) || isInlinePreview(current);
    }

    public static Activity findActivity(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) return (Activity) current;
            Context base = ((ContextWrapper) current).getBaseContext();
            if (base == current) break;
            current = base;
        }
        return current instanceof Activity ? (Activity) current : null;
    }

    /** Lowest common ancestor of two views, or null when they are in different windows. */
    public static ViewGroup commonAncestor(View first, View second) {
        if (first == null || second == null) return null;
        for (ViewParent a = first.getParent(); a instanceof ViewGroup; a = a.getParent()) {
            for (ViewParent b = second.getParent(); b instanceof ViewGroup; b = b.getParent()) {
                if (a == b) return (ViewGroup) a;
            }
        }
        return null;
    }

    /** Direct child of {@code ancestor} that contains {@code descendant}. */
    public static View childContaining(ViewGroup ancestor, View descendant) {
        View current = descendant;
        while (current != null) {
            ViewParent parent = current.getParent();
            if (parent == ancestor) return current;
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    public static int resource(Context context, String type, String name) {
        return context.getResources().getIdentifier(name, type, context.getPackageName());
    }

    public static String string(Context context, String name, String fallback) {
        int id = resource(context, "string", name);
        if (id == 0) return fallback;
        try {
            return context.getString(id);
        } catch (Resources.NotFoundException ignored) {
            return fallback;
        }
    }

    public static boolean isJapanese() {
        return Locale.getDefault().getLanguage().equals(Locale.JAPANESE.getLanguage());
    }
}
