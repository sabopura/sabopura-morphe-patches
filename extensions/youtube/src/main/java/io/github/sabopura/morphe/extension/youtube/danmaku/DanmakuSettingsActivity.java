package io.github.sabopura.morphe.extension.youtube.danmaku;

import android.content.Context;
import android.content.SharedPreferences;

import io.github.sabopura.morphe.extension.youtube.shared.NativeSettingsActivity;

/** YouTube-themed danmaku screen composed entirely of platform preference widgets. */
public final class DanmakuSettingsActivity extends NativeSettingsActivity {
    @Override protected String preferencesXml() { return "danmaku_preferences"; }

    @Override protected String layoutResource() { return "danmaku_settings_with_toolbar"; }

    @Override protected String titleResource() { return "danmaku_settings_title"; }

    @Override protected String fallbackTitle() { return "Danmaku"; }

    @Override protected String resetKey() { return "danmaku_reset"; }

    @Override protected void prepare(Context context, SharedPreferences preferences) {
        DanmakuSettings.normalizeForPreferenceScreen(preferences);
        DanmakuSettings.initialize(context);
    }

    @Override protected void resetToDefaults(Context context) {
        DanmakuSettings.initialize(context);
        DanmakuSettings.reset();
    }
}
