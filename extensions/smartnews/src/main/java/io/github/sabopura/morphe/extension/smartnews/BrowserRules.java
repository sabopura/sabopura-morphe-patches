package io.github.sabopura.morphe.extension.smartnews;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class BrowserRules {
    // `ArticleViewStyle` names. ArticleContainer shows these, and articles without a style, as a
    // web page first.
    private static final Set<String> WEB_STYLES = new HashSet<>(Arrays.asList("WEB", "HYBRID"));
    private static final Set<String> READER_STYLES = new HashSet<>(Arrays.asList("SMART", "SMART_VIDEO"));

    private BrowserRules() {
    }

    /**
     * Like SmartNews' `Link.selectAccessUrl()`, the internal URL comes first, so the browser shows
     * the page SmartNews would have shown.
     */
    static String articleUrl(String url, String internalUrl, String style, boolean premium,
                             boolean smartViewArticles) {
        if (premium || !leavesApp(style, smartViewArticles)) {
            return null;
        }
        if (isOutsideWebUrl(internalUrl)) {
            return internalUrl;
        }
        return isOutsideWebUrl(url) ? url : null;
    }

    private static boolean leavesApp(String style, boolean smartViewArticles) {
        return style == null || WEB_STYLES.contains(style)
                || (smartViewArticles && READER_STYLES.contains(style));
    }

    static String pickBrowser(String choice, List<String> browsers, String defaultBrowser) {
        if (choice != null) {
            for (String candidate : choice.split(",")) {
                candidate = candidate.trim();
                if (browsers.contains(candidate)) {
                    return candidate;
                }
            }
        }
        if (defaultBrowser != null && browsers.contains(defaultBrowser)) {
            return defaultBrowser;
        }
        return browsers.isEmpty() ? null : browsers.get(0);
    }

    static boolean isOutsideWebUrl(String url) {
        String host = webHost(url);
        return host != null && !host.equals("smartnews.com") && !host.endsWith(".smartnews.com");
    }

    // Lenient on purpose: page URLs are not always strictly valid, which java.net.URI rejects.
    private static String webHost(String url) {
        if (url == null) {
            return null;
        }
        int separator = url.indexOf("://");
        if (separator <= 0) {
            return null;
        }
        String scheme = url.substring(0, separator).trim().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return null;
        }
        int start = separator + 3;
        int end = start;
        while (end < url.length() && "/\\?#".indexOf(url.charAt(end)) < 0) {
            end++;
        }
        String authority = url.substring(start, end);
        authority = authority.substring(authority.lastIndexOf('@') + 1);
        int port = authority.lastIndexOf(':');
        if (port >= 0 && authority.indexOf(']') < port) {
            authority = authority.substring(0, port);
        }
        String host = authority.toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        return host.isEmpty() ? null : host;
    }
}
