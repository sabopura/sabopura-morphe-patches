package io.github.sabopura.morphe.extension.twitch.chat;

import android.content.Context;
import android.content.SharedPreferences;

public final class ChatSettings {
    public final boolean enabled, sevenTv, bttv, ffz, repeats, timestamps;
    public final int fontPercent;
    public final ChatRules rules;
    private static volatile ChatSettings cached;
    private static volatile Context app;

    private ChatSettings(SharedPreferences p) {
        enabled = p.getBoolean("enabled", true);
        sevenTv = p.getBoolean("7tv", true);
        bttv = p.getBoolean("bttv", true);
        ffz = p.getBoolean("ffz", true);
        repeats = p.getBoolean("repeats", false);
        timestamps = p.getBoolean("timestamps", false);
        fontPercent = Math.max(70, Math.min(160, p.getInt("font_percent", 100)));
        rules =
                new ChatRules(
                        p.getString("words", ""),
                        p.getString("users", ""),
                        p.getString("highlights", ""));
    }

    public static SharedPreferences preferences(Context context) {
        app = context.getApplicationContext();
        return app.getSharedPreferences("sabopura_chat", Context.MODE_PRIVATE);
    }

    /** For callers without a Context; null until any screen has read the settings. */
    public static SharedPreferences preferences() {
        Context context = app;
        return context == null ? null : preferences(context);
    }

    public static ChatSettings get(Context context) {
        ChatSettings result = cached;
        if (result == null) cached = result = new ChatSettings(preferences(context));
        return result;
    }

    public static void reload(Context context) {
        cached = new ChatSettings(preferences(context));
        ChatEnhancements.refresh();
        ViewCustomization.refresh();
    }
}
