package io.github.sabopura.morphe.extension.smartnews;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Browser;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public final class ExternalBrowser {
    private static final String TAG = "SabopuraBrowser";

    private static final String ACTION_CUSTOM_TABS_CONNECTION =
            "android.support.customtabs.action.CustomTabsService";
    private static final String EXTRA_SESSION = "android.support.customtabs.extra.SESSION";
    private static final String EXTRA_TITLE_VISIBILITY = "android.support.customtabs.extra.TITLE_VISIBILITY";
    private static final String EXTRA_SHARE_STATE = "androidx.browser.customtabs.extra.SHARE_STATE";
    private static final int SHOW_PAGE_TITLE = 1;
    private static final int SHARE_STATE_ON = 1;

    // A neutral URL that no app claims through verified app links.
    private static final Uri PROBE_URL = Uri.parse("http://www.example.com");

    private ExternalBrowser() {
    }

    /** Replaced by the patch with the `browser` option. */
    public static String browserPackage() {
        return "default";
    }

    /** Replaced by the patch with the `custom_tabs` option. */
    public static boolean customTabs() {
        return true;
    }

    /** Replaced by the patch with the `smart_view_articles` option. */
    public static boolean smartViewArticles() {
        return false;
    }

    /**
     * Called before SmartNews opens an article. Returns the URL to open in a browser, or null to
     * keep the article in SmartNews.
     */
    public static String articleTarget(Context context, String url, String internalUrl, Enum<?> style,
                                       Boolean premium) {
        String target = BrowserRules.articleUrl(url, internalUrl, style == null ? null : style.name(),
                Boolean.TRUE.equals(premium), smartViewArticles());
        if (target != null && browserFor(context) == null) {
            target = null;
        }
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Article " + style + " url=" + url + " internalUrl=" + internalUrl + " -> " + target);
        }
        return target;
    }

    /**
     * Called before WebBrowserActivity loads a web page. True when the page opened in a browser;
     * the activity then closes.
     */
    public static boolean onWebPageLoad(Activity activity, String url) {
        boolean leaves = activity != null && BrowserRules.isOutsideWebUrl(url);
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Web page " + url + " leaves=" + leaves);
        }
        if (!leaves || !open(activity, url)) {
            return false;
        }
        activity.finish();
        return true;
    }

    /**
     * Called in WebBrowserActivity.onCreate. Pages that SmartNews may show in SmartView never
     * reach onWebPageLoad, so with `smart_view_articles` on they leave here: the activity's URL
     * is cleared and it finishes through its own "no URL" path.
     */
    public static void onWebBrowserCreate(Activity activity, Bundle savedState) {
        Intent intent = activity == null || savedState != null || !smartViewArticles() ? null : activity.getIntent();
        if (intent == null || !intent.getBooleanExtra("allowSmartView", false)) {
            return;
        }
        String url = intent.getDataString();
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "SmartView page " + url);
        }
        if (BrowserRules.isOutsideWebUrl(url) && open(activity, url)) {
            intent.setData(null);
        }
    }

    /** Opens the page in the chosen browser. False when no browser could take it. */
    public static boolean open(Context context, String url) {
        try {
            String browser = browserFor(context);
            if (browser == null) {
                return false;
            }
            Activity activity = activityOf(context);
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .setPackage(browser);
            if (customTabs() && hasCustomTabs(context.getPackageManager(), browser)) {
                // A session extra, even an empty one, is what marks a Custom Tabs intent.
                Bundle session = new Bundle();
                session.putBinder(EXTRA_SESSION, null);
                intent.putExtras(session);
                intent.putExtra(EXTRA_TITLE_VISIBILITY, SHOW_PAGE_TITLE);
                intent.putExtra(EXTRA_SHARE_STATE, SHARE_STATE_ON);
                if (activity == null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
            } else {
                // Like a tapped link: browsers reuse one tab for the pages SmartNews opens.
                intent.putExtra(Browser.EXTRA_APPLICATION_ID, context.getPackageName());
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            (activity != null ? activity : context).startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "No browser took " + url, e);
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to open " + url, e);
        }
        return false;
    }

    private static String browserFor(Context context) {
        PackageManager packageManager = context.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_VIEW, PROBE_URL).addCategory(Intent.CATEGORY_BROWSABLE);
        List<String> browsers = new ArrayList<>();
        for (ResolveInfo info : packageManager.queryIntentActivities(probe, PackageManager.MATCH_ALL)) {
            String name = packageOf(info);
            if (name != null && !name.equals(context.getPackageName()) && !browsers.contains(name)) {
                browsers.add(name);
            }
        }
        // Without a default browser this resolves to the system's app chooser, which is not listed.
        String defaultBrowser = packageOf(packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY));
        return BrowserRules.pickBrowser(browserPackage(), browsers, defaultBrowser);
    }

    private static boolean hasCustomTabs(PackageManager packageManager, String browser) {
        Intent service = new Intent(ACTION_CUSTOM_TABS_CONNECTION).setPackage(browser);
        return packageManager.resolveService(service, 0) != null;
    }

    private static Activity activityOf(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    private static String packageOf(ResolveInfo info) {
        ActivityInfo activity = info == null ? null : info.activityInfo;
        return activity == null ? null : activity.packageName;
    }
}
