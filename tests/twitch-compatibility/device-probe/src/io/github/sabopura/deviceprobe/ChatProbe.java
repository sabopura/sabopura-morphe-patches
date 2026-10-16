package io.github.sabopura.deviceprobe;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

/**
 * Binds Twitch 31.5.2's real native chat row (holder, model and binder; the obfuscated names
 * are fixture-specific, as in LayoutProbe) with each chat setting and reads what the row shows.
 */
public class ChatProbe extends Instrumentation {
    private static final String TAG = "ChatProbe";
    private final StringBuilder report = new StringBuilder();
    private int failed, serial;
    private ClassLoader loader;
    private Context themed;
    private SharedPreferences prefs;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    private Class<?> type(String name) throws Exception {
        return Class.forName(name, true, loader);
    }

    /** A rendered row and the views the patch works on. */
    private static final class Row {
        View item;
        TextView text;
    }

    private Row bind(String shown, String login, String authorId) throws Exception {
        Context context = getTargetContext();
        View row = LayoutInflater.from(themed).inflate(
                context.getResources().getIdentifier("chat_message_item", "layout", context.getPackageName()), null, false);
        Class<?> holderType = type("h5d"), modelType = type("mtp"), styleType = type("o96");
        Object holder = holderType.getConstructor(View.class).newInstance(row);
        Object config = type("n96").getConstructors()[0].newInstance(false, false, false, false, styleType.getField("Default").get(null));
        Object content = type("hb6").getConstructors()[0].newInstance(null, null, null, null, shown, false);
        Object formatter = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{type("kotlin.jvm.functions.Function1")}, (p, m, a) -> content);
        Object tuid = type("tv.twitch.android.models.Tuid").getConstructor(String.class).newInstance(authorId);
        java.lang.reflect.Constructor<?> constructor = java.util.Arrays.stream(modelType.getConstructors())
                .filter(c -> c.getParameterTypes().length == 19).findFirst().get();
        Object[] arguments = {"probe-" + (serial++), type("lb6").getField("a").get(null), tuid, login, login, Long.valueOf(System.currentTimeMillis() / 1000),
                formatter, null, null, false, null, null, config, null, null, "1", false, null, false};
        Object model = constructor.newInstance(arguments);
        holderType.getMethod("C", modelType, boolean.class).invoke(holder, model, false);
        Row result = new Row();
        result.item = row;
        result.text = (TextView) row.findViewById(context.getResources().getIdentifier("chat_message_item", "id", context.getPackageName()));
        if (result.text == null) result.text = findText(row);
        return result;
    }

    private static TextView findText(View view) {
        if (view instanceof TextView) return (TextView) view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView t = findText(g.getChildAt(i));
                if (t != null) return t;
            }
        }
        return null;
    }

    private void settings(Object... pairs) throws Exception {
        SharedPreferences.Editor e = prefs.edit().clear();
        for (int i = 0; i < pairs.length; i += 2) {
            Object v = pairs[i + 1];
            if (v instanceof Boolean) e.putBoolean((String) pairs[i], (Boolean) v);
            else if (v instanceof Integer) e.putInt((String) pairs[i], (Integer) v);
            else e.putString((String) pairs[i], (String) v);
        }
        e.commit();
        type("io.github.sabopura.morphe.extension.twitch.chat.ChatSettings").getMethod("reload", Context.class)
                .invoke(null, getTargetContext());
    }

    private static boolean highlighted(TextView t) {
        CharSequence s = t.getText();
        return s instanceof Spanned && ((Spanned) s).getSpans(0, s.length(), BackgroundColorSpan.class).length > 0;
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        Context context = getTargetContext();
        loader = context.getClassLoader();
        themed = new ContextThemeWrapper(context, context.getApplicationInfo().theme);
        prefs = context.getSharedPreferences("sabopura_chat", Context.MODE_PRIVATE);
        java.util.Map<String, ?> saved = prefs.getAll();
        runOnMainSync(() -> {
            try {
                settings();
                Row plain = bind("viewer: hello chat", "viewer", "11");
                line("INFO row text \"" + plain.text.getText() + "\" size " + plain.text.getTextSize());
                check(plain.text.getText().toString().endsWith("viewer: hello chat"), "defaults leave the row as Twitch drew it");
                float size = plain.text.getTextSize();

                settings("timestamps", true);
                Row stamped = bind("viewer: hello chat", "viewer", "11");
                check(stamped.text.getText().toString().matches("^\\d{2}:\\d{2} .*viewer: hello chat$"), "time stamps: \"" + stamped.text.getText() + "\"");
                settings("timestamps", true, "timestamp_seconds", true);
                stamped = bind("viewer: hello chat", "viewer", "11");
                check(stamped.text.getText().toString().matches("^\\d{2}:\\d{2}:\\d{2} .*"), "time stamps with seconds");

                settings("words", "bot");
                Row named = bind("botfan: hi all", "botfan", "12");
                check(named.item.getVisibility() == View.VISIBLE, "a blocked word in the sender's name does not hide the message");
                Row worded = bind("viewer: the bot is down", "viewer", "11");
                check(worded.item.getVisibility() == View.GONE, "a blocked word in the message hides it");

                settings("users", "spammer");
                check(bind("spammer: buy now", "spammer", "13").item.getVisibility() == View.GONE, "a hidden user is hidden");
                check(bind("viewer: spammer is here", "viewer", "11").item.getVisibility() == View.VISIBLE, "others mentioning that user are not");

                settings("highlights", "sabopura");
                check(!highlighted(bind("Sabopura: hi", "sabopura", "14").text), "own name as the sender is not highlighted");
                check(highlighted(bind("viewer: hi sabopura", "viewer", "11").text), "a mention is highlighted");

                settings("show_login", true);
                Row localized = bind("日本語名: こんにちは", "nihongo", "15");
                check(localized.text.getText().toString().endsWith("日本語名 (nihongo): こんにちは"), "login after a different display name: \"" + localized.text.getText() + "\"");
                Row same = bind("Viewer: hi", "viewer", "11");
                check(same.text.getText().toString().endsWith("Viewer: hi"), "no login when it is the display name");

                settings("font_percent", 150);
                Row big = bind("viewer: hello chat", "viewer", "11");
                check(Math.abs(big.text.getTextSize() - size * 1.5f) < 1f, "text size 150%: " + size + " -> " + big.text.getTextSize());

                settings("alternating", true);
                Row a = bind("viewer: one", "viewer", "11"), b = bind("viewer: two", "viewer", "11");
                check(highlighted(a.text) != highlighted(b.text), "alternate rows get a background");

                settings("enabled", false, "words", "hello", "timestamps", true);
                Row off = bind("viewer: hello chat", "viewer", "11");
                check(off.item.getVisibility() == View.VISIBLE && off.text.getText().toString().endsWith("viewer: hello chat")
                        && !off.text.getText().toString().matches("^\\d{2}:.*"), "switched off: nothing changes");
            } catch (Throwable t) {
                fail("crash " + t + " " + Log.getStackTraceString(t));
            }
        });
        try {
            SharedPreferences.Editor e = prefs.edit().clear();
            for (java.util.Map.Entry<String, ?> entry : saved.entrySet()) {
                Object v = entry.getValue();
                if (v instanceof Boolean) e.putBoolean(entry.getKey(), (Boolean) v);
                else if (v instanceof Integer) e.putInt(entry.getKey(), (Integer) v);
                else if (v instanceof Long) e.putLong(entry.getKey(), (Long) v);
                else if (v instanceof Float) e.putFloat(entry.getKey(), (Float) v);
                else if (v instanceof String) e.putString(entry.getKey(), (String) v);
            }
            e.commit();
        } catch (Exception ignored) {
        }
        line("RESULT failed=" + failed);
        result.putString(Instrumentation.REPORT_KEY_STREAMRESULT, report.toString());
        finish(0, result);
    }

    private void check(boolean ok, String what) {
        if (ok) line("PASS " + what);
        else fail(what);
    }

    private void fail(String what) {
        failed++;
        line("FAIL " + what);
    }

    private void line(String text) {
        Log.i(TAG, text);
        report.append(text).append('\n');
    }
}
