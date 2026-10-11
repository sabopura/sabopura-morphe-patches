package io.github.sabopura.morphe.extension.youtube.shared;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The one preference file shared by the settings screens and the player-side features.
 *
 * <p>The file name is fixed. It used to be derived from {@code getPackageName()}, but other
 * patches (package renaming, GmsCore support, package-name spoofing) can make that return
 * different values for the settings activity and for the application, so the screens wrote
 * to one file while the player read another and no change ever took effect.
 */
public final class SettingsStore {
    public static final String NAME = "sabopura_morphe_settings";
    private static final String MIGRATED = "sabopura_settings_migrated";
    private static final String[] PREFIXES = {"danmaku_", "ambient_"};

    private SettingsStore() {
    }

    public static SharedPreferences open(Context context) {
        Context application = context.getApplicationContext();
        if (application == null) application = context;
        SharedPreferences store = application.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        adoptRenamedStore(application, store);
        if (!store.getBoolean(MIGRATED, false)) migrate(context, application, store);
        return store;
    }

    private static volatile boolean adopted;

    /** Earlier builds used another prefix for this file; carry those settings over once. */
    private static synchronized void adoptRenamedStore(Context application,
                                                       SharedPreferences store) {
        if (adopted) return;
        adopted = true;
        LegacyPreferences.adopt(application, store, NAME, "_morphe_settings");
    }

    /** Copies values written by earlier builds into the fixed file, once. */
    private static synchronized void migrate(Context context, Context application,
                                             SharedPreferences store) {
        if (store.getBoolean(MIGRATED, false)) return;
        Set<String> oldNames = new LinkedHashSet<>();
        oldNames.add(application.getPackageName() + "_preferences");
        oldNames.add(context.getPackageName() + "_preferences");
        oldNames.add("com.google.android.youtube_preferences");
        SharedPreferences.Editor editor = store.edit();
        Map<String, ?> current = store.getAll();
        for (String oldName : oldNames) {
            Map<String, ?> old;
            try {
                old = application.getSharedPreferences(oldName, Context.MODE_PRIVATE).getAll();
            } catch (RuntimeException ignored) {
                continue;
            }
            for (Map.Entry<String, ?> entry : old.entrySet()) {
                String key = entry.getKey();
                if (key == null || current.containsKey(key) || !isOurs(key)) continue;
                Object value = entry.getValue();
                if (value instanceof Boolean) {
                    editor.putBoolean(key, (Boolean) value);
                } else if (value instanceof String) {
                    editor.putString(key, (String) value);
                } else if (value instanceof Number) {
                    // List preferences store strings; older builds stored numbers.
                    editor.putString(key, Integer.toString(((Number) value).intValue()));
                }
            }
        }
        editor.putBoolean(MIGRATED, true).commit();
    }

    private static boolean isOurs(String key) {
        for (String prefix : PREFIXES) {
            if (key.startsWith(prefix)) return true;
        }
        return false;
    }
}
