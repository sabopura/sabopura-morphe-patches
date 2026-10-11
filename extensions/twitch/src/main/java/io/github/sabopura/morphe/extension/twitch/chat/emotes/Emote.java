// Adapted from Hooman's patches, GPL-3.0; see NOTICE for source and revision.
package io.github.sabopura.morphe.extension.twitch.chat.emotes;

final class Emote {
    final String name;
    final String url;
    final boolean animated;
    final boolean zeroWidth;

    Emote(String name, String url, boolean animated) {
        this(name, url, animated, false);
    }

    Emote(String name, String url, boolean animated, boolean zeroWidth) {
        this.name = name;
        this.url = url;
        this.animated = animated;
        this.zeroWidth = zeroWidth;
    }
}
