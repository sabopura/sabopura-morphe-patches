package io.github.sabopura.morphe.extension.twitch.ads;

import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Real Twitch playlists captured on 2026-10-13, with addresses and identifiers replaced. */
public class PlaylistsTest {
    private static String fixture(String name) {
        InputStream in = PlaylistsTest.class.getResourceAsStream("/playlists/" + name);
        assertNotNull(name, in);
        try (Scanner s = new Scanner(in, StandardCharsets.UTF_8.name()).useDelimiter("\\A")) {
            return s.hasNext() ? s.next() : "";
        }
    }

    @Test
    public void prerollPlaylistsAreAds() {
        for (String name : Arrays.asList("preroll-start.m3u8", "preroll-later.m3u8")) {
            String text = fixture(name);
            assertTrue(name, Playlists.hasStitchedAd(text));
            assertFalse(name, Playlists.isCleanLive(text));
            assertEquals(name, -1, Playlists.liveSequence(text));
        }
    }

    @Test
    public void backupPlaylistIsCleanLive() {
        String text = fixture("backup-live.m3u8");
        assertFalse(Playlists.hasStitchedAd(text));
        assertTrue(Playlists.isCleanLive(text));
        assertEquals(12456, Playlists.liveSequence(text));
    }

    @Test
    public void prepareKeepsStitchedCuesWhileAnAdIsPresent() {
        String prepared = Playlists.prepare(fixture("preroll-start.m3u8"));
        assertTrue(prepared.contains("CLASS=\"twitch-stitched-ad\""));
        assertTrue(Playlists.hasStitchedAd(prepared));
    }

    @Test
    public void prepareNumbersByLiveSequenceAndDropsClientAdCues() {
        String text = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:15\n"
                + "#EXT-X-TWITCH-LIVE-SEQUENCE:15170\n"
                + "#EXT-X-DATERANGE:ID=\"maf-1\",CLASS=\"twitch-maf-ad\",START-DATE=\"2026-10-13T14:08:23.736Z\"\n"
                + "#EXT-X-DATERANGE:ID=\"p-1\",CLASS=\"live-video-net-metadata\",X-NET-LIVE-VIDEO-METADATA-TYPE=\"pbyp-preflight\"\n"
                + "#EXT-X-DATERANGE:ID=\"q-1\",CLASS=\"twitch-ad-quartile\",START-DATE=\"2026-10-13T14:08:23.736Z\"\n"
                + "#EXT-X-PROGRAM-DATE-TIME:2026-10-13T14:08:23.736Z\n#EXTINF:2.000,live\nhttps://a.ttvnw.net/1.ts\n";
        String prepared = Playlists.prepare(text);
        assertTrue(prepared.contains("#EXT-X-MEDIA-SEQUENCE:15170\n"));
        assertFalse(prepared.contains("twitch-maf-ad"));
        assertFalse(prepared.contains("pbyp-preflight"));
        assertFalse(prepared.contains("twitch-ad-quartile"));
        assertTrue(prepared.contains("#EXTINF:2.000,live\nhttps://a.ttvnw.net/1.ts"));
    }

    @Test
    public void prepareLeavesNormalLivePlaylistsAlone() {
        String text = fixture("backup-live.m3u8");
        assertEquals(text, Playlists.prepare(text));
    }

    @Test
    public void readsVariantsOfTheMasterPlaylist() {
        List<Playlists.Variant> variants = Playlists.variants(fixture("master.m3u8"),
                "https://usher.ttvnw.net/api/channel/hls/test.m3u8?token=x");
        assertEquals(6, variants.size());
        Playlists.Variant source = null;
        for (Playlists.Variant v : variants) if ("chunked".equals(v.group)) source = v;
        assertNotNull(source);
        assertTrue(source.uri.startsWith("https://"));
        assertTrue(variants.stream().anyMatch(Playlists.Variant::audioOnly));
    }

    @Test
    public void matchesTheSameRenditionAcrossSessions() {
        List<Playlists.Variant> primary = Playlists.variants(fixture("master.m3u8"), "https://usher.ttvnw.net/a.m3u8");
        for (Playlists.Variant wanted : primary) {
            assertSame(wanted, Playlists.match(primary, wanted));
        }
    }

    @Test
    public void fallsBackToTheClosestLowerBitrate() {
        List<Playlists.Variant> backup = Playlists.variants(
                "#EXTM3U\n"
                        + "#EXT-X-STREAM-INF:BANDWIDTH=630000,RESOLUTION=640x360,VIDEO=\"360p30\",FRAME-RATE=30.000\nhttps://b/360.m3u8\n"
                        + "#EXT-X-STREAM-INF:BANDWIDTH=230000,RESOLUTION=284x160,VIDEO=\"160p30\",FRAME-RATE=30.000\nhttps://b/160.m3u8\n",
                "https://usher.ttvnw.net/a.m3u8");
        Playlists.Variant wanted = Playlists.variants(
                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=8000000,RESOLUTION=1920x1080,VIDEO=\"chunked\",FRAME-RATE=60.000\nhttps://p/src.m3u8\n",
                "https://usher.ttvnw.net/a.m3u8").get(0);
        assertEquals("https://b/360.m3u8", Playlists.match(backup, wanted).uri);
    }

    @Test
    public void readsTheChannelOnlyFromLiveUsherUrls() {
        assertEquals("kato_junichi0817", Playlists.channel("https://usher.ttvnw.net/api/channel/hls/kato_junichi0817.m3u8?token=x"));
        assertEquals("abc", Playlists.channel("https://usher.ttvnw.net/api/v2/channel/hls/ABC.m3u8"));
        assertNull(Playlists.channel("https://usher.ttvnw.net/vod/123.m3u8"));
        assertNull(Playlists.channel("https://example.com/api/channel/hls/abc.m3u8"));
    }

    @Test
    public void recognisesTwitchPlaylistsOnly() {
        assertTrue(Playlists.isTwitchPlaylist("https://usher.ttvnw.net/api/channel/hls/a.m3u8"));
        assertTrue(Playlists.isTwitchPlaylist("https://x.playlist.ttvnw.net/v1/playlist/abc.m3u8"));
        assertFalse(Playlists.isTwitchPlaylist("https://x.hls.ttvnw.net/v1/segment/abc.ts"));
        assertFalse(Playlists.isTwitchPlaylist("https://evil.example/ttvnw.net/a.m3u8"));
    }

    @Test
    public void swapsOnlyTheTokenOfAUsherUrl() {
        String url = Playlists.withToken(
                "https://usher.ttvnw.net/api/channel/hls/a.m3u8?token=old&sig=s1&allow_source=true&play_session_id=p&cdm=wv",
                "{\"a\":1}", "s2");
        assertTrue(url.startsWith("https://usher.ttvnw.net/api/channel/hls/a.m3u8?"));
        assertTrue(url.contains("token=%7B%22a%22%3A1%7D"));
        assertTrue(url.contains("sig=s2"));
        assertTrue(url.contains("allow_source=true"));
        assertTrue(url.contains("cdm=wv"));
        assertFalse(url.contains("play_session_id"));
        assertFalse(url.contains("old"));
    }

    @Test
    public void identifiesPlaylistsByPathWhateverTheQuery() {
        String a = "https://video-weaver.tyo05.hls.ttvnw.net/v1/playlist/ABCDEFGH.m3u8";
        assertEquals(Playlists.path(a), Playlists.path(a + "?_HLS_msn=5&player=x"));
        assertEquals(Playlists.path(a), Playlists.path(a.replace("tyo05", "tyo03")));
        assertNull(Playlists.path("https://x/"));
    }
}
