package io.github.sabopura.morphe.extension.youtube.ambient;

import android.content.Context;
import android.content.SharedPreferences;

import io.github.sabopura.morphe.extension.youtube.shared.NativeSettingsActivity;

/** YouTube-themed ambient light screen composed entirely of platform preference widgets. */
public final class AmbientLightSettingsActivity extends NativeSettingsActivity {
    @Override protected String preferencesXml() { return "ambient_light_preferences"; }

    @Override protected String layoutResource() { return "ambient_light_settings_with_toolbar"; }

    @Override protected String titleResource() { return "ambient_light_settings_title"; }

    @Override protected String fallbackTitle() { return "Ambient light"; }

    @Override protected String resetKey() { return "ambient_reset"; }

    @Override protected void prepare(Context context, SharedPreferences preferences) {
        AmbientLightSettings.normalizeForPreferenceScreen(preferences);
        AmbientLightSettings.initialize(context);
    }

    /** Shows which patch build is installed and how the light is actually being drawn. */
    @Override protected void onPreferencesCreated(android.preference.PreferenceFragment fragment) {
        android.preference.Preference status = fragment.findPreference("ambient_status");
        if (status != null) {
            status.setSummary(AmbientLightController.statusText(
                    io.github.sabopura.morphe.extension.youtube.shared.Views.isJapanese()));
        }
    }

    /**
     * Applies a change straight away instead of relying only on the settings store's own
     * listener, then refreshes the status row so it always describes the current state.
     */
    @Override protected void onSettingChanged(android.preference.PreferenceFragment fragment,
                                              String key) {
        if (!key.startsWith("ambient_")) return;
        AmbientLightSettings.reload();
        AmbientLightController.onSettingsChanged();
        onPreferencesCreated(fragment);
    }

    @Override protected boolean onPreferenceClicked(android.preference.Preference preference) {
        if (!"ambient_status".equals(preference.getKey())) return false;
        boolean japanese = io.github.sabopura.morphe.extension.youtube.shared.Views.isJapanese();
        new android.app.AlertDialog.Builder(this)
                .setTitle(preference.getTitle())
                .setMessage(AmbientLightController.statusText(japanese))
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(japanese ? "診断情報をコピー" : "Copy diagnostics",
                        (dialog, which) -> copyDiagnostics(japanese))
                .show();
        return true;
    }

    /** Puts the status and the watch page's view layout on the clipboard for a bug report. */
    private void copyDiagnostics(boolean japanese) {
        String text;
        try {
            text = AmbientLightController.diagnostics();
        } catch (Throwable throwable) {
            text = AmbientLightController.statusText(false) + "\n(diagnostics failed: "
                    + throwable + ")";
        }
        if (android.util.Log.isLoggable(AmbientLightController.TAG, android.util.Log.DEBUG)) {
            for (String line : text.split("\n")) {
                android.util.Log.d(AmbientLightController.TAG, "DIAG " + line);
            }
        }
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                    "Ambient light diagnostics", text));
        }
        android.widget.Toast.makeText(this, japanese ? "診断情報をコピーしました"
                : "Diagnostics copied", android.widget.Toast.LENGTH_SHORT).show();
    }

    @Override protected void resetToDefaults(Context context) {
        AmbientLightSettings.initialize(context);
        AmbientLightSettings.reset();
    }
}
