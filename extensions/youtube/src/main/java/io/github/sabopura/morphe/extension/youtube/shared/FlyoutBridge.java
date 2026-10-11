package io.github.sabopura.morphe.extension.youtube.shared;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.github.sabopura.morphe.extension.youtube.ambient.AmbientLightSettings;
import io.github.sabopura.morphe.extension.youtube.danmaku.DanmakuSettings;

/**
 * Adds native YouTube rows to the player's settings flyout: one quick toggle per patched
 * feature plus a single settings row (a chooser when several features are patched in).
 * Long-pressing a toggle opens that feature's detailed settings directly.
 */
@SuppressWarnings("unused")
public final class FlyoutBridge {
    private static final String TAG = "SabopuraPlayer";
    private static final String ROW_TAG_PREFIX = "sabopura_flyout_";
    private static final String SETTINGS_TAG = ROW_TAG_PREFIX + "settings";
    static final String DANMAKU_SETTINGS_ACTION = "io.github.sabopura.morphe.action.OPEN_DANMAKU_SETTINGS_V2";
    static final String AMBIENT_SETTINGS_ACTION = "io.github.sabopura.morphe.action.OPEN_AMBIENT_LIGHT_SETTINGS";

    private static WeakReference<Dialog> dialogRef = new WeakReference<>(null);
    private static WeakReference<PopupWindow> popupRef = new WeakReference<>(null);

    private FlyoutBridge() {
    }

    public static void setBottomSheetFlyout(Dialog dialog) {
        if (dialog == null || dialog.getWindow() == null || features().isEmpty()) return;
        dialogRef = new WeakReference<>(dialog);
        try {
            View decor = dialog.getWindow().getDecorView();
            ViewTreeObserver observer = decor.getViewTreeObserver();
            observer.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    try {
                        Dialog current = dialogRef.get();
                        if (current == null || !current.isShowing()) {
                            if (observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
                            return;
                        }
                        if (inject(current) && observer.isAlive()) {
                            observer.removeOnGlobalLayoutListener(this);
                        }
                    } catch (Throwable throwable) {
                        Log.w(TAG, "Unable to add rows to bottom sheet", throwable);
                    }
                }
            });
        } catch (Throwable throwable) {
            Log.w(TAG, "Unable to observe bottom sheet", throwable);
        }
    }

    public static void setPopupWindowFlyout(PopupWindow popup) {
        if (popup == null || popup.getContentView() == null || features().isEmpty()) return;
        popupRef = new WeakReference<>(popup);
        popup.getContentView().post(() -> {
            try {
                inject(popup);
            } catch (Throwable throwable) {
                Log.w(TAG, "Unable to add rows to popup settings", throwable);
            }
        });
    }

    /** @return true when this flyout is finished (rows added or not a player-settings menu). */
    private static boolean inject(Object flyout) {
        LinearLayout container = findMenuContainer(flyout);
        if (container == null) return false;
        if (findByTag(container, SETTINGS_TAG) != null) return true;
        if (!looksLikePlayerSettings(container)) return false;

        Context context = container.getContext();
        List<Feature> features = features();
        if (features.isEmpty()) return true;
        int index = flyout instanceof Dialog ? Math.min(1, container.getChildCount()) : 0;
        for (Feature feature : features) {
            feature.initialize(context);
            View toggle = createRow(context, container, feature.toggleTitle(), feature.icon);
            if (toggle == null) return true;
            toggle.setTag(ROW_TAG_PREFIX + feature.id);
            TextView toggleText = findText(toggle);
            toggle.setOnClickListener(view -> {
                feature.toggle(view.getContext());
                if (toggleText != null) toggleText.setText(feature.toggleTitle());
            });
            toggle.setOnLongClickListener(view -> {
                dismissFlyouts();
                openSettings(view.getContext(), feature.settingsAction);
                return true;
            });
            container.addView(toggle, Math.min(index++, container.getChildCount()));
        }

        String settingsTitle;
        if (features.size() == 1) {
            settingsTitle = features.get(0).settingsTitle();
        } else {
            settingsTitle = Views.isJapanese() ? "弾幕・アンビエントライトの設定"
                    : "Danmaku & ambient light settings";
        }
        View settings = createRow(context, container, settingsTitle,
                "yt_outline_gear_vd_theme_24|yt_outline_settings_vd_theme_24");
        if (settings == null) return true;
        settings.setTag(SETTINGS_TAG);
        settings.setOnClickListener(view -> {
            Context viewContext = view.getContext();
            dismissFlyouts();
            if (features.size() == 1) {
                openSettings(viewContext, features.get(0).settingsAction);
            } else {
                chooseSettings(viewContext, features);
            }
        });
        container.addView(settings, Math.min(index, container.getChildCount()));
        return true;
    }

    private static void chooseSettings(Context context, List<Feature> features) {
        CharSequence[] titles = new CharSequence[features.size()];
        for (int i = 0; i < titles.length; i++) titles[i] = features.get(i).label();
        try {
            new AlertDialog.Builder(context)
                    .setItems(titles, (dialog, which) ->
                            openSettings(context, features.get(which).settingsAction))
                    .show();
        } catch (Throwable throwable) {
            // A non-Activity context cannot host a dialog; open the first screen instead.
            openSettings(context, features.get(0).settingsAction);
        }
    }

    private static void openSettings(Context context, String action) {
        Intent intent = new Intent(action);
        intent.setPackage(context.getPackageName());
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (Throwable throwable) {
            Log.w(TAG, "Settings screen unavailable: " + action, throwable);
        }
    }

    private static List<Feature> features() {
        List<Feature> features = new ArrayList<>(2);
        if (FeatureFlags.danmaku()) features.add(Feature.DANMAKU);
        if (FeatureFlags.ambientLight()) features.add(Feature.AMBIENT);
        return features;
    }

    private static LinearLayout findMenuContainer(Object flyout) {
        if (flyout instanceof PopupWindow) {
            View content = ((PopupWindow) flyout).getContentView();
            if (content instanceof FrameLayout) {
                View first = childAt((ViewGroup) content, 0);
                if (first instanceof ViewGroup) {
                    View menu = childAt((ViewGroup) first, 0);
                    if (menu instanceof LinearLayout) return (LinearLayout) menu;
                }
            }
            return findLargestLinearLayout(content, null);
        }
        if (!(flyout instanceof Dialog)) return null;
        Window window = ((Dialog) flyout).getWindow();
        if (window == null) return null;
        View decor = window.getDecorView();
        int containerId = Views.resource(decor.getContext(), "id", "container");
        View base = containerId == 0 ? decor : decor.findViewById(containerId);
        return findLargestLinearLayout(base == null ? decor : base, null);
    }

    private static LinearLayout findLargestLinearLayout(View root, LinearLayout current) {
        if (root == null) return current;
        if (root instanceof LinearLayout && ((LinearLayout) root).getChildCount() >= 1) {
            long area = (long) root.getWidth() * root.getHeight();
            long oldArea = current == null ? -1L : (long) current.getWidth() * current.getHeight();
            if (area > oldArea) current = (LinearLayout) root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                current = findLargestLinearLayout(group.getChildAt(i), current);
            }
        }
        return current;
    }

    private static boolean looksLikePlayerSettings(View root) {
        Set<String> labels = new HashSet<>();
        collectText(root, labels);
        int matches = 0;
        String[] expected = {
                "quality", "playback speed", "captions", "additional settings", "loop video",
                "stable volume", "sleep timer", "lock screen",
                "画質", "再生速度", "字幕", "その他の設定", "ループ再生", "音量の安定化",
                "スリープタイマー", "画面のロック"
        };
        for (String item : expected) {
            for (String label : labels) {
                if (label.equals(item) || label.startsWith(item + " ")) {
                    matches++;
                    break;
                }
            }
        }
        return matches >= 2;
    }

    private static void collectText(View view, Set<String> result) {
        CharSequence description = view.getContentDescription();
        if (description != null) result.add(clean(description));
        if (view instanceof TextView) {
            CharSequence value = ((TextView) view).getText();
            if (value != null) result.add(clean(value));
        }
        collectRenderedText(view, result);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectText(group.getChildAt(i), result);
        }
    }

    private static String clean(CharSequence value) {
        return value.toString().replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    /** Newer flyouts render labels through Litho ComponentHost instead of TextView. */
    private static void collectRenderedText(View view, Set<String> result) {
        try {
            Object textContent = view.getClass().getMethod("getTextContent").invoke(view);
            if (textContent == null) return;
            Object items = textContent.getClass().getMethod("getTextItems").invoke(textContent);
            if (!(items instanceof Iterable<?>)) return;
            for (Object item : (Iterable<?>) items) {
                if (item instanceof CharSequence) result.add(clean((CharSequence) item));
            }
        } catch (Throwable ignored) {
        }
    }

    private static View createRow(Context context, ViewGroup parent, String title, String iconName) {
        int layout = Views.resource(context, "layout", "modern_bottom_sheet_enableable_list_item");
        if (layout == 0) layout = Views.resource(context, "layout", "bottom_sheet_enableable_list_item");
        if (layout == 0) return null;
        View row = LayoutInflater.from(context).inflate(layout, parent, false);
        TextView text = findText(row);
        if (text != null) text.setText(title);

        int iconId = Views.resource(context, "id", "list_item_icon_primary");
        ImageView icon = iconId == 0 ? null : row.findViewById(iconId);
        int drawableId = 0;
        for (String candidate : (iconName + "|yt_outline_message_bubble_left_vd_theme_24")
                .split("\\|")) {
            drawableId = Views.resource(context, "drawable", candidate);
            if (drawableId != 0) break;
        }
        if (icon != null && drawableId != 0) {
            Drawable drawable = context.getDrawable(drawableId);
            icon.setImageDrawable(drawable);
            if (text != null) icon.setImageTintList(ColorStateList.valueOf(text.getCurrentTextColor()));
        }
        int secondaryId = Views.resource(context, "id", "list_item_secondary_container");
        View secondary = secondaryId == 0 ? null : row.findViewById(secondaryId);
        if (secondary != null) secondary.setVisibility(View.GONE);
        TypedArray attributes = context.obtainStyledAttributes(
                new int[]{android.R.attr.selectableItemBackground});
        try {
            row.setForeground(attributes.getDrawable(0));
        } finally {
            attributes.recycle();
        }
        return row;
    }

    private static TextView findText(View row) {
        int textId = Views.resource(row.getContext(), "id", "list_item_text");
        View text = textId == 0 ? null : row.findViewById(textId);
        return text instanceof TextView ? (TextView) text : null;
    }

    private static View findByTag(View root, String tag) {
        if (tag.equals(root.getTag())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View result = findByTag(group.getChildAt(i), tag);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static View childAt(ViewGroup group, int index) {
        return index >= 0 && index < group.getChildCount() ? group.getChildAt(index) : null;
    }

    private static void dismissFlyouts() {
        Dialog dialog = dialogRef.get();
        if (dialog != null) dialog.dismiss();
        PopupWindow popup = popupRef.get();
        if (popup != null) popup.dismiss();
    }

    private enum Feature {
        DANMAKU("danmaku", DANMAKU_SETTINGS_ACTION, "yt_outline_message_bubble_left_vd_theme_24"),
        AMBIENT("ambient", AMBIENT_SETTINGS_ACTION,
                "yt_outline_experimental_ambient_mode_vd_theme_24|yt_outline_brightness_vd_theme_24");

        final String id;
        final String settingsAction;
        final String icon;

        Feature(String id, String settingsAction, String icon) {
            this.id = id;
            this.settingsAction = settingsAction;
            this.icon = icon;
        }

        void initialize(Context context) {
            if (this == DANMAKU) DanmakuSettings.initialize(context);
            else AmbientLightSettings.initialize(context);
        }

        boolean enabled() {
            return this == DANMAKU ? DanmakuSettings.isMasterEnabled()
                    : AmbientLightSettings.isEnabled();
        }

        void toggle(Context context) {
            if (this == DANMAKU) DanmakuSettings.toggleMaster(context);
            else AmbientLightSettings.toggleEnabled(context);
        }

        String label() {
            boolean japanese = Views.isJapanese();
            if (this == DANMAKU) return japanese ? "弾幕" : "Danmaku";
            return japanese ? "アンビエントライト" : "Ambient light";
        }

        String toggleTitle() {
            boolean japanese = Views.isJapanese();
            boolean on = enabled();
            String state = japanese ? (on ? "オン" : "オフ") : (on ? "On" : "Off");
            return label() + (japanese ? "：" : ": ") + state;
        }

        String settingsTitle() {
            return Views.isJapanese() ? label() + "の設定" : label() + " settings";
        }
    }
}
