package io.github.sabopura.morphe.extension.twitch.ui;

import java.util.Locale;

/** Japanese or English text, chosen from the device language. No string resources needed. */
public final class Text {
    private Text() {
    }

    public static boolean japanese() {
        return "ja".equals(Locale.getDefault().getLanguage());
    }

    public static String label(String ja, String en) {
        return japanese() ? ja : en;
    }
}
