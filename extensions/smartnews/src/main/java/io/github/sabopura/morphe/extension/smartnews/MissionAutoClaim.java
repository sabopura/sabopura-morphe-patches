package io.github.sabopura.morphe.extension.smartnews;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Hooks on SmartNews' SNClient bridge. The bridge is attached to every bridge WebView; once the
 * "My missions" page starts talking to it, the claim script is injected into that page.
 */
public final class MissionAutoClaim {
    private static final String TAG = "SabopuraMissions";
    private static final long INJECT_SPACING_MS = 1_500L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<Object, WeakReference<WebView>> BRIDGES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<WebView, Long> LAST_INJECT =
            Collections.synchronizedMap(new WeakHashMap<>());

    private MissionAutoClaim() {
    }

    /** Replaced by the patch when the `draw_lotteries` option is off. */
    public static boolean drawLotteries() {
        return true;
    }

    /** Called at the start of the bridge's attach method, before SNClient is added. */
    public static void onBridgeAttached(Object bridge, WebView webView) {
        if (bridge == null || webView == null) {
            return;
        }
        BRIDGES.put(bridge, new WeakReference<>(webView));
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Bridge attached: " + bridge.getClass().getName());
        }
        try {
            webView.addJavascriptInterface(
                    new Reporter(webView.getContext().getApplicationContext()), MissionScript.INTERFACE);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to add the report interface", t);
        }
    }

    /** Called on the JavaBridge thread for every SNClient message from the page. */
    public static void onBridgeMessage(Object bridge) {
        WeakReference<WebView> reference = BRIDGES.get(bridge);
        WebView webView = reference == null ? null : reference.get();
        if (webView != null) {
            MAIN.post(() -> inject(webView));
        }
    }

    private static void inject(WebView webView) {
        try {
            String url = webView.getUrl();
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Bridge message from " + url);
            }
            if (!MissionScript.isMissionPage(url)) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            Long last = LAST_INJECT.get(webView);
            if (last != null && now - last < INJECT_SPACING_MS) {
                return;
            }
            LAST_INJECT.put(webView, now);
            // The script guards itself per document, so repeated injections are harmless.
            boolean hidden = MissionBackground.isHidden(webView);
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Injecting (background=" + hidden + ") " + MissionBackground.describeParents(webView));
            }
            webView.evaluateJavascript(MissionScript.script(
                    drawLotteries(), Log.isLoggable(TAG, Log.DEBUG), hidden), null);
        } catch (Throwable t) {
            Log.w(TAG, "Injection failed", t);
        }
    }

    /** Failures are mentioned once per process; the background page runs repeatedly. */
    private static boolean failureShown;

    public static final class Reporter {
        private final Context context;

        Reporter(Context context) {
            this.context = context;
        }

        @JavascriptInterface
        public void log(String message) {
            Log.i(TAG, String.valueOf(message));
        }

        @JavascriptInterface
        public void report(int points, int amount, int draws, int wins, int failed) {
            Log.i(TAG, "points=" + points + " amount=" + amount + " draws=" + draws
                    + " wins=" + wins + " failed=" + failed);
            int shownFailures = failureShown ? 0 : failed;
            if (shownFailures > 0) {
                failureShown = true;
            }
            boolean japanese = "ja".equals(Locale.getDefault().getLanguage());
            String text = MissionScript.summary(points, amount, draws, wins, shownFailures, japanese);
            if (text.isEmpty()) {
                return;
            }
            MAIN.post(() -> Toast.makeText(
                    context, text, wins > 0 ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show());
        }

        /** The background page has nothing left to claim. */
        @JavascriptInterface
        public void done() {
            MissionBackground.onScriptDone();
        }
    }
}
