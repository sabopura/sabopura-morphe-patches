package io.github.sabopura.morphe.extension.smartnews;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class BrowserRulesTest {
    private static final String ARTICLE = "https://news.example.jp/articles/123?ref=top";

    @Test
    public void webArticlesLeaveTheApp() {
        assertEquals(ARTICLE, BrowserRules.articleUrl(ARTICLE, null, "WEB", false, false));
        assertEquals(ARTICLE, BrowserRules.articleUrl(ARTICLE, null, "HYBRID", false, false));
        assertEquals(ARTICLE, BrowserRules.articleUrl(ARTICLE, null, null, false, false));
    }

    @Test
    public void smartViewArticlesStayUnlessChosen() {
        assertNull(BrowserRules.articleUrl(ARTICLE, null, "SMART", false, false));
        assertNull(BrowserRules.articleUrl(ARTICLE, null, "SMART_VIDEO", false, false));
        assertEquals(ARTICLE, BrowserRules.articleUrl(ARTICLE, null, "SMART", false, true));
        assertEquals(ARTICLE, BrowserRules.articleUrl(ARTICLE, null, "SMART_VIDEO", false, true));
    }

    @Test
    public void appOnlyArticlesStay() {
        for (String style : new String[] {"VIDEO", "COUPON", "APP", "AUDIO", "SOMETHING_NEW"}) {
            assertNull(style, BrowserRules.articleUrl(ARTICLE, null, style, false, true));
        }
        assertNull(BrowserRules.articleUrl(ARTICLE, null, "WEB", true, true));
    }

    @Test
    public void opensThePageSmartNewsWouldShow() {
        String internal = ARTICLE + "&from=smtnews";
        assertEquals(internal, BrowserRules.articleUrl(ARTICLE, internal, "WEB", false, false));
        assertEquals(ARTICLE, BrowserRules.articleUrl(ARTICLE, null, "WEB", false, false));
        assertEquals(ARTICLE, BrowserRules.articleUrl(ARTICLE, "https://content.smartnews.com/a", "WEB", false, false));
        assertNull(BrowserRules.articleUrl("https://content.smartnews.com/a", null, "WEB", false, false));
        assertNull(BrowserRules.articleUrl(null, null, "WEB", false, false));
    }

    @Test
    public void picksTheBrowser() {
        List<String> installed = Arrays.asList("com.android.chrome", "com.example.browser.beta", "org.mozilla.firefox");
        String choice = "com.example.browser, com.example.browser.beta";
        assertEquals("com.example.browser.beta", BrowserRules.pickBrowser(choice, installed, "com.android.chrome"));
        assertEquals("org.mozilla.firefox", BrowserRules.pickBrowser("default", installed, "org.mozilla.firefox"));
        assertEquals("org.mozilla.firefox", BrowserRules.pickBrowser("com.brave.browser", installed, "org.mozilla.firefox"));
        String systemChooser = "android";
        assertEquals("com.android.chrome", BrowserRules.pickBrowser("default", installed, systemChooser));
        assertEquals("com.android.chrome", BrowserRules.pickBrowser("default", installed, null));
        assertNull(BrowserRules.pickBrowser("com.android.chrome", Collections.<String>emptyList(), null));
    }

    @Test
    public void onlyOutsideWebPages() {
        assertTrue(BrowserRules.isOutsideWebUrl("http://example.com"));
        assertTrue(BrowserRules.isOutsideWebUrl("HTTPS://Example.COM:8443/path"));
        assertTrue(BrowserRules.isOutsideWebUrl("https://news.example.jp/記事?q=a|b"));
        assertTrue(BrowserRules.isOutsideWebUrl("https://smartnews.com.example.com/"));
        assertTrue(BrowserRules.isOutsideWebUrl("https://notsmartnews.com/"));
        assertFalse(BrowserRules.isOutsideWebUrl("https://smartnews.com/"));
        assertFalse(BrowserRules.isOutsideWebUrl("https://Tour.SmartNews.com./campaign/"));
        assertFalse(BrowserRules.isOutsideWebUrl("https://user@content.smartnews.com:443/x"));
        assertTrue(BrowserRules.isOutsideWebUrl("https://www.smartnews.com@example.com/"));
        assertFalse(BrowserRules.isOutsideWebUrl("https://www.smartnews.com\\@example.com/"));
        assertFalse(BrowserRules.isOutsideWebUrl("https://www.smartnews.com?next=https://example.com/"));
        assertFalse(BrowserRules.isOutsideWebUrl("https://www.smartnews.com#https://example.com/"));
        assertTrue(BrowserRules.isOutsideWebUrl("http://[2001:db8::1]:8080/"));
        assertTrue(BrowserRules.isOutsideWebUrl(" https://example.com/"));
        assertFalse(BrowserRules.isOutsideWebUrl("intent://scan/#Intent;scheme=zxing;end"));
        assertFalse(BrowserRules.isOutsideWebUrl("mailto:someone@example.com"));
        assertFalse(BrowserRules.isOutsideWebUrl("https:///path"));
        assertFalse(BrowserRules.isOutsideWebUrl("/relative/path"));
        assertFalse(BrowserRules.isOutsideWebUrl(""));
    }
}
