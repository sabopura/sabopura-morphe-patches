package io.github.sabopura.morphe.extension.youtube.ambient;

import android.content.Context;
import android.content.SharedPreferences;

import io.github.sabopura.morphe.extension.youtube.shared.NativeSettingsActivity;

/** Fine-grained ambient light options, reached from the main ambient light screen. */
public final class AmbientLightAdvancedSettingsActivity extends NativeSettingsActivity {
    @Override protected String preferencesXml() { return "ambient_light_advanced_preferences"; }

    @Override protected String layoutResource() { return "ambient_light_advanced_settings_with_toolbar"; }

    @Override protected String titleResource() { return "ambient_advanced_title"; }

    @Override protected String fallbackTitle() { return "Advanced settings"; }

    /** The reset row lives on the main ambient light screen. */
    @Override protected String resetKey() { return ""; }

    @Override protected void prepare(Context context, SharedPreferences preferences) {
        AmbientLightSettings.normalizeForPreferenceScreen(preferences);
        AmbientLightSettings.initialize(context);
    }

    @Override protected void resetToDefaults(Context context) {
        AmbientLightSettings.initialize(context);
        AmbientLightSettings.reset();
    }
}
