package io.github.sabopura.morphe.extension.youtube.shared;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.Map;
import java.util.Set;

/**
 * Moves preference files that earlier builds wrote under a different name prefix into the
 * current file. Matching is by suffix so a renamed prefix keeps the user's settings.
 */
public final class LegacyPreferences {
    private LegacyPreferences() {
    }

    /** Copies every key the target lacks from {@code *suffix.xml} files, then deletes them. */
    public static void adopt(Context context, SharedPreferences target, String currentName,
                             String suffix) {
        String[] files;
        try {
            files = new File(context.getApplicationInfo().dataDir, "shared_prefs").list();
        } catch (RuntimeException ignored) {
            return;
        }
        if (files == null) return;
        for (String file : files) {
            if (!file.endsWith(suffix + ".xml")) continue;
            String name = file.substring(0, file.length() - ".xml".length());
            if (name.equals(currentName)) continue;
            Map<String, ?> current = target.getAll();
            SharedPreferences.Editor editor = target.edit();
            for (Map.Entry<String, ?> entry : context.getSharedPreferences(name,
                    Context.MODE_PRIVATE).getAll().entrySet()) {
                String key = entry.getKey();
                if (key == null || current.containsKey(key) || key.endsWith("_migrated")) continue;
                put(editor, key, entry.getValue());
            }
            if (editor.commit()) context.deleteSharedPreferences(name);
        }
    }

    @SuppressWarnings("unchecked")
    private static void put(SharedPreferences.Editor editor, String key, Object value) {
        if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) editor.putInt(key, (Integer) value);
        else if (value instanceof Long) editor.putLong(key, (Long) value);
        else if (value instanceof Float) editor.putFloat(key, (Float) value);
        else if (value instanceof String) editor.putString(key, (String) value);
        else if (value instanceof Set) editor.putStringSet(key, (Set<String>) value);
    }
}
