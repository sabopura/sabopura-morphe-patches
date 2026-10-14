package io.github.sabopura.morphe.extension.twitch.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;

import java.lang.ref.WeakReference;
import java.util.Locale;

/**
 * Japanese or English text, chosen from the language the app's screens use. No string
 * resources needed. The screens' configuration is used, not only the device default, so a
 * per-app language (Android 13+ "App languages") is followed too.
 */
public final class Text {
    private static volatile WeakReference<Context> screen = new WeakReference<>(null);

    private Text() {
    }

    /** Remembers a context of the screen being built, for its configured language. */
    public static void use(Context context) {
        if (context != null) screen = new WeakReference<>(context);
    }

    public static boolean japanese() {
        Context context = screen.get();
        if (context != null) {
            Configuration config = context.getResources().getConfiguration();
            Locale locale = Build.VERSION.SDK_INT >= 24 && !config.getLocales().isEmpty()
                    ? config.getLocales().get(0) : config.locale;
            if (locale != null) return "ja".equals(locale.getLanguage());
        }
        return "ja".equals(Locale.getDefault().getLanguage());
    }

    public static String label(String ja, String en) {
        return japanese() ? ja : en;
    }
}
