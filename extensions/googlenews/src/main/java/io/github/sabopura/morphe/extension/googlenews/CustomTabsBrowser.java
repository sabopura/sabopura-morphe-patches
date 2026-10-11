package io.github.sabopura.morphe.extension.googlenews;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.util.Log;

import java.util.List;

/**
 * Picks the browser Google News opens articles with, replacing the app's server-controlled
 * allowlist. Mirrors androidx CustomTabsClient.getPackageName(context, null, false):
 * the user's default browser when it offers a Custom Tabs service, otherwise the first
 * browser that does. Returning null keeps the app's own in-app WebView fallback.
 */
public final class CustomTabsBrowser {
    private static final String TAG = "SabopuraGoogleNews";
    private static final String ACTION_CUSTOM_TABS_CONNECTION =
            "android.support.customtabs.action.CustomTabsService";

    // A neutral URL that no app claims through verified app links.
    private static final Uri PROBE_URL = Uri.parse("http://www.example.com");

    private CustomTabsBrowser() {
    }

    public static String getPackageName(Context context) {
        if (context == null) return null;
        try {
            PackageManager packageManager = context.getPackageManager();
            String ownPackage = context.getPackageName();
            Intent view = new Intent(Intent.ACTION_VIEW, PROBE_URL)
                    .addCategory(Intent.CATEGORY_BROWSABLE);

            String defaultBrowser = packageOf(packageManager.resolveActivity(view, 0));
            if (isCustomTabsBrowser(packageManager, defaultBrowser, ownPackage)) {
                return defaultBrowser;
            }

            List<ResolveInfo> browsers =
                    packageManager.queryIntentActivities(view, PackageManager.MATCH_ALL);
            for (ResolveInfo browser : browsers) {
                String candidate = packageOf(browser);
                if (isCustomTabsBrowser(packageManager, candidate, ownPackage)) {
                    return candidate;
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Custom Tabs browser lookup failed", e);
        }
        return null;
    }

    private static String packageOf(ResolveInfo info) {
        if (info == null) return null;
        ActivityInfo activity = info.activityInfo;
        return activity == null ? null : activity.packageName;
    }

    private static boolean isCustomTabsBrowser(
            PackageManager packageManager, String candidate, String ownPackage) {
        // The disambiguation dialog resolves to the "android" package when no default is set.
        if (candidate == null || candidate.equals("android") || candidate.equals(ownPackage)) {
            return false;
        }
        Intent service = new Intent(ACTION_CUSTOM_TABS_CONNECTION).setPackage(candidate);
        return packageManager.resolveService(service, 0) != null;
    }
}
