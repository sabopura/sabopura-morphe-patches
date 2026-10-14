package io.github.sabopura.morphe.extension.twitch.ui;

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import static io.github.sabopura.morphe.extension.twitch.ui.Text.label;

/**
 * Adds a "patch settings" row to the top of Twitch's own settings screen, so the settings can be
 * found where people look first. It opens the same screen as the player button.
 */
@SuppressWarnings("unused")
public final class SettingsEntry {
    private static final String LEGACY_MAIN = "tv.twitch.android.settings.main.MainSettingsFragment";

    private SettingsEntry() {
    }

    /** Injection point: return value of the main settings screen's onCreateView. */
    public static View wrap(View settings) {
        try {
            Context context = settings.getContext();
            Text.use(context);
            LinearLayout wrapper = new LinearLayout(context);
            wrapper.setOrientation(LinearLayout.VERTICAL);
            wrapper.addView(row(context, wrapper));
            // Added last: if building the row failed, Twitch's view is still free to return.
            wrapper.addView(settings, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            return wrapper;
        } catch (RuntimeException e) {
            Log.w("SabopuraTwitch", "Settings entry not added: " + e);
            return settings;
        }
    }

    /**
     * Injection point: return value of the shared onCreateView of Twitch's list-style settings
     * screens. Only the main screen gets the row.
     */
    public static View wrapIfMain(Object fragment, View settings) {
        return fragment != null && LEGACY_MAIN.equals(fragment.getClass().getName()) ? wrap(settings) : settings;
    }

    private static View row(Context context, ViewGroup parent) {
        String title = label("パッチの設定（広告ブロック・チャット・弾幕）", "Patch settings (ad blocking, chat, danmaku)");
        int layout = id(context, "settings_menu_item", "layout");
        View row = null;
        if (layout != 0) {
            try {
                // Twitch's own settings row, so the entry looks like the rows below it.
                row = LayoutInflater.from(context).inflate(layout, parent, false);
                TextView text = row.findViewById(id(context, "menu_item_title", "id"));
                if (text != null) text.setText(title);
                ImageView icon = row.findViewById(id(context, "icon", "id"));
                int settingsIcon = id(context, "ic_settings", "drawable");
                if (icon != null && settingsIcon != 0) icon.setImageResource(settingsIcon);
            } catch (RuntimeException e) {
                row = null;
            }
        }
        if (row == null) {
            TextView text = new TextView(context);
            int pad = Ui.dp(context, 16);
            text.setPadding(pad, pad, pad, pad);
            text.setTextSize(16);
            text.setText(title);
            row = text;
        }
        row.setContentDescription(label("パッチの設定：広告ブロック・チャット・弾幕・再生",
                "Patch settings: ad blocking, chat, danmaku and playback"));
        row.setOnClickListener(v -> SettingsScreen.show(v.getContext()));
        return row;
    }

    /** Twitch's resources keep the original package even when another patch renames the app. */
    private static int id(Context context, String name, String type) {
        int found = context.getResources().getIdentifier(name, type, "tv.twitch.android.app");
        return found != 0 ? found : context.getResources().getIdentifier(name, type, context.getPackageName());
    }
}
