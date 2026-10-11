package io.github.sabopura.morphe.extension.twitch.player;

import android.content.SharedPreferences;
import android.net.Uri;

public final class LiveProxy {
    private LiveProxy() {}

    private static volatile Uri lastOriginal, lastRouted;

    public static Uri forReload(String path) {
        Uri original = lastOriginal, routed = lastRouted;
        return original != null
                        && (path.equals(original.toString())
                                || routed != null && path.equals(routed.toString()))
                ? rewrite(original)
                : Uri.parse(path);
    }

    /** Build from the public channel name only. Never copy a query, token, device ID or header. */
    public static Uri rewrite(Uri original) {
        try {
            SharedPreferences p = PlayerTools.preferences();
            String base = p == null ? "" : p.getString("live_proxy", "");
            if (!"usher.ttvnw.net".equals(original.getHost())
                    || !original.getPath().contains("/channel/hls/")) return original;
            PlayerTools.onLive();
            lastOriginal = original;
            if (base.isEmpty()) {
                lastRouted = original;
                return original;
            }
            Uri endpoint = Uri.parse(base);
            if (!validBase(base)) return original;
            String channel = original.getLastPathSegment();
            if (channel == null || !channel.endsWith(".m3u8")) return original;
            channel = channel.substring(0, channel.length() - 5);
            if (!channel.matches("[A-Za-z0-9_]{1,25}")) return original;
            Uri routed =
                    endpoint.buildUpon()
                            .appendPath(channel)
                            .appendQueryParameter("allow_source", "true")
                            .appendQueryParameter("allow_audio_only", "true")
                            .appendQueryParameter("fast_bread", "true")
                            .appendQueryParameter("type", "any")
                            .appendQueryParameter("player", "twitchweb")
                            .build();
            lastRouted = routed;
            return routed;
        } catch (Exception ignored) {
            return original;
        }
    }

    public static boolean validBase(String base) {
        try {
            java.net.URI u = new java.net.URI(base);
            return "https".equals(u.getScheme())
                    && u.getHost() != null
                    && u.getUserInfo() == null
                    && u.getQuery() == null
                    && u.getFragment() == null
                    && (u.getPort() == -1 || u.getPort() == 443)
                    && u.getPath().endsWith("/");
        } catch (Exception e) {
            return false;
        }
    }
}
