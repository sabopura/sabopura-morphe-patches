package io.github.sabopura.morphe.extension.twitch.ads;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads and rewrites Twitch's HLS playlists. Pure Java so the tests can use real playlists.
 *
 * <p>In a normal live media playlist, {@code EXT-X-MEDIA-SEQUENCE} equals
 * {@code EXT-X-TWITCH-LIVE-SEQUENCE}: the number of the stream's segment, shared by every
 * viewer session. Answering with another session's playlist therefore keeps the numbering the
 * player already knows, which is what lets a stitched ad be replaced without a visible seam.
 */
public final class Playlists {
    private static final Pattern ATTRIBUTE = Pattern.compile("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)");
    private static final Pattern MEDIA_SEQUENCE = Pattern.compile("(?m)^#EXT-X-MEDIA-SEQUENCE:(\\d+)\\s*$");
    private static final Pattern LIVE_SEQUENCE = Pattern.compile("(?m)^#EXT-X-TWITCH-LIVE-SEQUENCE:(\\d+)\\s*$");
    private static final Pattern CHANNEL = Pattern.compile("^/api/(?:v2/)?channel/hls/([A-Za-z0-9_]{1,25})\\.m3u8$");

    /** Cues the player turns into requests for ads it plays itself. Removed whenever blocking. */
    private static final String[] CLIENT_AD_CUES = {
            "CLASS=\"twitch-maf-ad\"",
            "pbyp-preflight",
    };
    /** Cues that describe a stitched ad. Only removed when no ad segment is left. */
    private static final String[] STITCHED_AD_CUES = {
            "CLASS=\"twitch-stitched-ad\"",
            "CLASS=\"twitch-ad-quartile\"",
    };

    private Playlists() {
    }

    public static boolean isMaster(String text) {
        return text.contains("#EXT-X-STREAM-INF");
    }

    /** True when the playlist carries a stitched ad: its cue or a segment from the ad server. */
    public static boolean hasStitchedAd(String text) {
        if (text.contains("CLASS=\"twitch-stitched-ad\"")) return true;
        for (String line : text.split("\n")) {
            if (line.startsWith("#EXTINF:") && line.contains(",Amazon")) return true;
        }
        return false;
    }

    /** The live sequence number of the first segment, or -1 if the playlist has none. */
    public static long liveSequence(String text) {
        Matcher m = LIVE_SEQUENCE.matcher(text);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    /** True for a live playlist that can be served as is: no ad and a live sequence number. */
    public static boolean isCleanLive(String text) {
        return !hasStitchedAd(text) && liveSequence(text) >= 0 && text.contains("#EXTINF:");
    }

    /**
     * Prepares a live media playlist for the player: numbers it by live sequence, so that
     * playlists of different sessions line up, and drops the cues of client-requested ads.
     * Stitched-ad cues stay while an ad segment is present, so the fallback can still see it.
     */
    public static String prepare(String text) {
        long live = liveSequence(text);
        String out = text;
        if (live >= 0) {
            Matcher m = MEDIA_SEQUENCE.matcher(out);
            if (m.find() && Long.parseLong(m.group(1)) != live) {
                out = out.substring(0, m.start(1)) + live + out.substring(m.end(1));
            }
        }
        boolean stripStitched = !hasStitchedAd(out);
        StringBuilder b = new StringBuilder(out.length());
        for (String line : out.split("\n", -1)) {
            if (line.startsWith("#EXT-X-DATERANGE:")
                    && (containsAny(line, CLIENT_AD_CUES) || stripStitched && containsAny(line, STITCHED_AD_CUES))) {
                continue;
            }
            if (b.length() > 0) b.append('\n');
            b.append(line);
        }
        return b.toString();
    }

    private static boolean containsAny(String line, String[] needles) {
        for (String needle : needles) if (line.contains(needle)) return true;
        return false;
    }

    /** The channel login of a usher master playlist URL, or null for anything else (VODs, clips). */
    public static String channel(String url) {
        try {
            URI uri = URI.create(url);
            if (!"usher.ttvnw.net".equalsIgnoreCase(uri.getHost()) || uri.getPath() == null) return null;
            Matcher m = CHANNEL.matcher(uri.getPath());
            return m.matches() ? m.group(1).toLowerCase(Locale.ROOT) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The path of a URL, which identifies a Twitch playlist even when the query changes. */
    public static String path(String url) {
        try {
            String path = URI.create(url).getPath();
            return path == null || path.length() < 8 ? null : path;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Host and the start of the path, for logs; never the query with its tokens. */
    public static String describe(String url) {
        try {
            URI uri = URI.create(url);
            String path = uri.getPath() == null ? "" : uri.getPath();
            return uri.getHost() + (path.length() > 32 ? path.substring(0, 32) + "…" : path);
        } catch (IllegalArgumentException e) {
            return "?";
        }
    }

    /** True for an HLS playlist served by Twitch, the only requests worth inspecting. */
    public static boolean isTwitchPlaylist(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            String path = uri.getPath();
            return host != null && path != null && path.endsWith(".m3u8")
                    && (host.equalsIgnoreCase("usher.ttvnw.net") || host.toLowerCase(Locale.ROOT).endsWith(".ttvnw.net"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Copies a usher URL with another access token. Everything else, such as codecs, stays. */
    public static String withToken(String masterUrl, String token, String signature) {
        URI uri = URI.create(masterUrl);
        String raw = uri.getRawQuery();
        Map<String, String> query = new LinkedHashMap<>();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                if (pair.isEmpty()) continue;
                int eq = pair.indexOf('=');
                query.put(eq < 0 ? pair : pair.substring(0, eq), eq < 0 ? "" : pair.substring(eq + 1));
            }
        }
        query.put("token", encode(token));
        query.put("sig", encode(signature));
        query.remove("play_session_id");
        query.put("cache_buster", Long.toString(System.currentTimeMillis()));
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : query.entrySet()) {
            if (b.length() > 0) b.append('&');
            b.append(e.getKey()).append('=').append(e.getValue());
        }
        return uri.getScheme() + "://" + uri.getRawAuthority() + uri.getRawPath() + "?" + b;
    }

    private static String encode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One rendition of a master playlist. */
    public static final class Variant {
        public final String uri;
        public final String group;
        public final String resolution;
        public final double frameRate;
        public final long bandwidth;

        Variant(String uri, Map<String, String> attributes) {
            this.uri = uri;
            this.group = unquote(attributes.get("VIDEO"));
            this.resolution = unquote(attributes.get("RESOLUTION"));
            this.frameRate = parseDouble(attributes.get("FRAME-RATE"));
            this.bandwidth = parseLong(attributes.get("BANDWIDTH"));
        }

        boolean audioOnly() {
            return "audio_only".equals(group) || resolution == null;
        }
    }

    public static List<Variant> variants(String master, String masterUrl) {
        List<Variant> found = new ArrayList<>();
        Map<String, String> pending = null;
        for (String raw : master.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                pending = attributes(line.substring("#EXT-X-STREAM-INF:".length()));
            } else if (pending != null && !line.isEmpty() && !line.startsWith("#")) {
                found.add(new Variant(resolve(masterUrl, line), pending));
                pending = null;
            }
        }
        return Collections.unmodifiableList(found);
    }

    /** The backup rendition that best matches what the player chose from the primary session. */
    public static Variant match(List<Variant> candidates, Variant wanted) {
        if (candidates.isEmpty()) return null;
        for (Variant v : candidates) if (wanted.group != null && wanted.group.equals(v.group)) return v;
        for (Variant v : candidates) {
            if (wanted.resolution != null && wanted.resolution.equals(v.resolution)
                    && Math.abs(wanted.frameRate - v.frameRate) < 1) return v;
        }
        if (wanted.audioOnly()) {
            for (Variant v : candidates) if (v.audioOnly()) return v;
        }
        Variant best = null;
        for (Variant v : candidates) {
            if (v.audioOnly()) continue;
            boolean fits = v.bandwidth <= wanted.bandwidth;
            if (best == null) {
                best = v;
            } else if (fits && (best.bandwidth > wanted.bandwidth || v.bandwidth > best.bandwidth)) {
                best = v;
            } else if (!fits && best.bandwidth > wanted.bandwidth && v.bandwidth < best.bandwidth) {
                best = v;
            }
        }
        return best;
    }

    static Map<String, String> attributes(String list) {
        Map<String, String> map = new LinkedHashMap<>();
        Matcher m = ATTRIBUTE.matcher(list);
        while (m.find()) map.put(m.group(1), m.group(2));
        return map;
    }

    private static String unquote(String value) {
        if (value == null) return null;
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1) : value;
    }

    private static double parseDouble(String value) {
        try {
            return value == null ? 0 : Double.parseDouble(unquote(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long parseLong(String value) {
        try {
            return value == null ? 0 : Long.parseLong(unquote(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String resolve(String base, String uri) {
        try {
            return URI.create(base).resolve(uri).toString();
        } catch (IllegalArgumentException e) {
            return uri;
        }
    }
}
