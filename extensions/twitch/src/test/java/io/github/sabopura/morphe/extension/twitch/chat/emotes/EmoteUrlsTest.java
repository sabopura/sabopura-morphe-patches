package io.github.sabopura.morphe.extension.twitch.chat.emotes;

import org.junit.Test;
import static org.junit.Assert.*;

public class EmoteUrlsTest {
    @Test public void acceptsOnlyProviderCdnHttpsUrls() {
        assertTrue(EmoteUrls.allowed("https://cdn.7tv.app/emote/abc/2x.webp"));
        assertTrue(EmoteUrls.allowed("https://cdn.frankerfacez.com/emote/123/2"));
        assertTrue(EmoteUrls.allowed("https://cdn.betterttv.net/emote/abc/2x.webp"));
        assertFalse(EmoteUrls.allowed("http://cdn.7tv.app/emote/abc/2x.webp"));
        assertFalse(EmoteUrls.allowed("https://cdn.7tv.app.evil.example/a"));
        assertFalse(EmoteUrls.allowed("https://secret@cdn.7tv.app/a"));
        assertFalse(EmoteUrls.allowed("https://127.0.0.1/a"));
        assertFalse(EmoteUrls.allowed("https://cdn.7tv.app:8080/a"));
    }
}
