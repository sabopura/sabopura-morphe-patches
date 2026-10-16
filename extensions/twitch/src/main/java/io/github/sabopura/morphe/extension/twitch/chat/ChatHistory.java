package io.github.sabopura.morphe.extension.twitch.chat;

import io.github.sabopura.morphe.extension.twitch.ui.Text;
import android.app.AlertDialog;
import android.content.Context;
import android.text.*;
import android.view.*;
import android.widget.*;

import io.github.sabopura.morphe.extension.twitch.chat.emotes.EmoteSupport;

import org.json.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

public final class ChatHistory {
    private static final LinkedHashMap<String, HistoryMessage> MESSAGES = new LinkedHashMap<>();
    private static boolean loaded, savePending;
    private static long generation;
    private static final java.util.concurrent.ExecutorService IO =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "ChatHistoryStorage");
                        t.setDaemon(true);
                        return t;
                    });

    public static synchronized void record(Context context, HistoryMessage m) {
        if (m.id == null || m.text == null || m.text.length() > 16000) return;
        if (ChatSettings.preferences(context).getBoolean("save_history", false) && !loaded)
            load(context.getApplicationContext(), () -> {});
        MESSAGES.put(m.room + ":" + m.id, m);
        while (MESSAGES.size() > 1000) MESSAGES.remove(MESSAGES.keySet().iterator().next());
        if (ChatSettings.preferences(context).getBoolean("save_history", false) && !savePending) {
            savePending = true;
            Context app = context.getApplicationContext();
            long expected = generation;
            PublicApi.UI.postDelayed(
                    () -> {
                        savePending = false;
                        List<HistoryMessage> snapshot = snapshot(null);
                        IO.execute(() -> save(app, snapshot, expected));
                    },
                    2000);
        }
    }

    private static synchronized List<HistoryMessage> snapshot(String room) {
        List<HistoryMessage> list = new ArrayList<>();
        for (HistoryMessage m : MESSAGES.values())
            if (room == null || room.equals(m.room)) list.add(m);
        return list;
    }

    private static synchronized void save(Context c, List<HistoryMessage> messages, long expected) {
        if (expected != generation
                || !ChatSettings.preferences(c).getBoolean("save_history", false)) return;
        android.util.AtomicFile file =
                new android.util.AtomicFile(new File(c.getFilesDir(), "chat-history.json"));
        FileOutputStream out = null;
        try {
            JSONArray a = new JSONArray();
            for (HistoryMessage m : messages)
                a.put(
                        new JSONObject()
                                .put("id", m.id)
                                .put("room", m.room)
                                .put("user", m.user)
                                .put("userId", m.userId)
                                .put("text", m.text)
                                .put("time", m.time));
            byte[] bytes = a.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 2 * 1024 * 1024) return;
            out = file.startWrite();
            out.write(bytes);
            file.finishWrite(out);
        } catch (Exception ignored) {
            if (out != null) file.failWrite(out);
        }
    }

    private static void load(Context c, Runnable done) {
        if (loaded) {
            IO.execute(() -> PublicApi.UI.post(done));
            return;
        }
        loaded = true;
        long expected = generation;
        IO.execute(
                () -> {
                    try {
                        File file = new File(c.getFilesDir(), "chat-history.json");
                        if (file.length() > 2 * 1024 * 1024) return;
                        String value;
                        try (InputStream in = new FileInputStream(file);
                                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                            byte[] b = new byte[8192];
                            int n;
                            while ((n = in.read(b)) != -1) {
                                if (out.size() + n > 2 * 1024 * 1024) throw new IOException();
                                out.write(b, 0, n);
                            }
                            value = out.toString("UTF-8");
                        }
                        JSONArray a = new JSONArray(value);
                        synchronized (ChatHistory.class) {
                            if (expected != generation) return;
                            for (int i = 0; i < a.length() && i < 1000; i++) {
                                JSONObject o = a.getJSONObject(i);
                                HistoryMessage m =
                                        new HistoryMessage(
                                                o.getString("id"),
                                                o.getString("room"),
                                                o.getString("user"),
                                                o.getString("text"),
                                                o.getLong("time"),
                                                o.optString("userId", ""));
                                MESSAGES.putIfAbsent(m.room + ":" + m.id, m);
                            }
                            while (MESSAGES.size() > 1000)
                                MESSAGES.remove(MESSAGES.keySet().iterator().next());
                        }
                    } catch (Exception ignored) {
                    } finally {
                        PublicApi.UI.post(done);
                    }
                });
    }

    public static void show(Context context, boolean recent) {
        String channel = ChatEnhancements.channelName(), room = ChatEnhancements.channelId();
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(1);
        TextView status = new TextView(context);
        box.addView(status);
        EditText search = new EditText(context);
        search.setHint(Text.label("本文・ユーザーを検索", "Search messages and users"));
        box.addView(search);
        ListView list = new ListView(context);
        box.addView(
                list,
                new LinearLayout.LayoutParams(
                        -1, Math.round(360 * context.getResources().getDisplayMetrics().density)));
        List<HistoryMessage> source = new ArrayList<>(), visible = new ArrayList<>();
        BaseAdapter adapter =
                new BaseAdapter() {
                    public int getCount() {
                        return visible.size();
                    }

                    public Object getItem(int p) {
                        return visible.get(p);
                    }

                    public long getItemId(int p) {
                        return p;
                    }

                    public View getView(int p, View old, ViewGroup parent) {
                        TextView t =
                                old instanceof TextView ? (TextView) old : new TextView(context);
                        HistoryMessage m = visible.get(p);
                        t.setPadding(16, 12, 16, 12);
                        t.setTextSize(14);
                        t.setText(
                                (m.time > 0
                                                ? new SimpleDateFormat(
                                                                        "HH:mm:ss",
                                                                        Locale.getDefault())
                                                                .format(new Date(m.time))
                                                        + " "
                                                : "")
                                        + (recent ? m.user + ": " : "")
                                        + m.text);
                        EmoteSupport.bind(t, room);
                        return t;
                    }
                };
        list.setAdapter(adapter);
        Runnable filter =
                () -> {
                    String q = search.getText().toString().toLowerCase(Locale.ROOT);
                    ChatSettings settings = ChatSettings.get(context);
                    visible.clear();
                    for (HistoryMessage m : source)
                        if ((m.user + " " + m.text).toLowerCase(Locale.ROOT).contains(q)
                                && (!settings.enabled
                                        || !settings.rules.blocks(m.text, m.user, m.userId)))
                            visible.add(m);
                    adapter.notifyDataSetChanged();
                };
        search.addTextChangedListener(
                new TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

                    public void onTextChanged(CharSequence s, int a, int b, int c) {
                        filter.run();
                    }

                    public void afterTextChanged(Editable e) {}
                });
        AlertDialog.Builder builder =
                new AlertDialog.Builder(context)
                        .setTitle(
                                recent
                                        ? Text.label("過去チャット", "Recent chat")
                                        : Text.label(
                                                "表示したメッセージの履歴", "Viewed message history"))
                        .setView(box)
                        .setPositiveButton(android.R.string.ok, null);
        // A neutral button closes the dialog unless it gets its own listener, so only the
        // history has one; the source of recent chat is named in the status line instead.
        if (!recent) builder.setNeutralButton(Text.label("履歴を削除", "Clear history"), null);
        AlertDialog dialog = builder.create();
        dialog.show();
        if (!recent) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                    .setOnClickListener(
                            v -> {
                                synchronized (ChatHistory.class) {
                                    generation++;
                                    savePending = false;
                                    MESSAGES.clear();
                                    new android.util.AtomicFile(
                                                    new File(
                                                            context.getFilesDir(),
                                                            "chat-history.json"))
                                            .delete();
                                }
                                source.clear();
                                filter.run();
                            });
            load(
                    context,
                    () -> {
                        if (!dialog.isShowing()) return;
                        source.addAll(snapshot(room));
                        source.sort(Comparator.comparingLong(m -> m.time));
                        filter.run();
                        status.setText(
                                Text.label(
                                        "この端末で表示した最大1,000件",
                                        "Up to 1,000 messages viewed on this device"));
                    });
            return;
        }
        if (channel == null || !channel.matches("[A-Za-z0-9_]{1,25}")) {
            status.setText(Text.label("配信を開いてからお使いください", "Open a channel first"));
            return;
        }
        status.setText(Text.label("取得中…", "Loading…"));
        if (!PublicApi.submit(
                () -> {
                    try {
                        JSONObject json =
                                new JSONObject(
                                        PublicApi.get(
                                                "https://recent-messages.robotty.de/api/v2/recent-messages/"
                                                        + channel.toLowerCase(Locale.ROOT)
                                                        + "?limit=500&hideModeratedMessages=true"));
                        JSONArray a = json.optJSONArray("messages");
                        Map<String, HistoryMessage> unique = new LinkedHashMap<>();
                        if (a != null)
                            for (int i = 0; i < a.length(); i++) {
                                HistoryMessage m = HistoryMessage.parseIrc(a.optString(i));
                                if (m != null) unique.put(m.id, m);
                            }
                        List<HistoryMessage> messages = new ArrayList<>(unique.values());
                        messages.sort(Comparator.comparingLong(m -> m.time));
                        PublicApi.UI.post(
                                () -> {
                                    if (!dialog.isShowing()) return;
                                    source.addAll(messages);
                                    filter.run();
                                    status.setText(
                                            Text.label(
                                                    "公開履歴 " + messages.size() + " 件（robotty.de）",
                                                    "Public history: " + messages.size() + " (robotty.de)"));
                                });
                    } catch (Exception e) {
                        PublicApi.UI.post(
                                () -> {
                                    if (dialog.isShowing())
                                        status.setText(
                                                Text.label(
                                                        "履歴を取得できません（未収集・拒否・サービス停止）",
                                                        "History unavailable: uncollected, opted"
                                                                + " out, or service offline"));
                                });
                    }
                }))
            status.setText(
                    Text.label(
                            "取得待ちが多いため再度お試しください",
                            "Please try again after pending requests complete"));
    }
}
