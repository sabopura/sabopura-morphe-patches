package io.github.sabopura.morphe.extension.twitch.chat.emotes;

import java.net.URI;

final class EmoteUrls {
    private EmoteUrls() {}

    static boolean allowed(String value) {
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            return "https".equals(uri.getScheme())
                    && uri.getUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && ("cdn.7tv.app".equals(host)
                            || "cdn.betterttv.net".equals(host)
                            || "cdn.frankerfacez.com".equals(host)
                            || ("fourtf.com".equals(host)
                                    && uri.getPath().startsWith("/chatterino/badges/"))
                            || ("flxrs.com".equals(host)
                                    && uri.getPath().startsWith("/dankchat/badges/"))
                            || ("raw.githubusercontent.com".equals(host)
                                    && uri.getPath()
                                            .startsWith("/chatsen/resources/master/assets/")));
        } catch (Exception ignored) {
            return false;
        }
    }
}
