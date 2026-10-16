package io.github.sabopura.morphe.extension.twitch.chat;

import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import io.github.sabopura.morphe.extension.twitch.chat.emotes.EmoteSupport;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Hooks only already-rendered chat rows. Does not access account credentials or mutate messages.
 */
public final class ChatEnhancements {
    private static final Map<TextView, Row> ROWS = new WeakHashMap<>();
    private static final RepeatFilter REPEATS = new RepeatFilter();
    private static volatile String currentRoom;
    private static volatile String currentChannel;

    public static String channelId() {
        return currentRoom;
    }

    public static String channelName() {
        return currentChannel;
    }

    private static boolean loggedFailure;
    private static int sequence;
    private static final java.util.LinkedHashMap<String, Integer> ORDER =
            new java.util.LinkedHashMap<String, Integer>() {
                protected boolean removeEldestEntry(Map.Entry<String, Integer> e) {
                    return size() > 1024;
                }
            };

    private ChatEnhancements() {}

    public static void onChannelChanged(String channelId, String channelName) {
        currentRoom = channelId;
        currentChannel = channelName;
        EmoteSupport.onChannelChanged(channelId, channelName);
    }

    public static void bind(Object holder, Object model, boolean selected) {
        TextView text = null;
        try {
            text = RowAccess.textView(holder);
            View item = RowAccess.itemView(holder);
            Long timestamp = RowAccess.timestamp(model);
            bindRow(
                    text,
                    item,
                    RowAccess.messageId(model),
                    RowAccess.channelId(model),
                    RowAccess.username(model),
                    RowAccess.userId(model),
                    ChatTime.nativeSecondsToMillis(timestamp));
        } catch (Exception failure) {
            if (text != null) {
                Row old = ROWS.remove(text);
                if (old != null) old.restoreVisibility();
            }
            if (!loggedFailure) {
                loggedFailure = true;
                // Avoid including chat, user IDs or exception payloads in device logs.
                Log.w(
                        "SabopuraChat",
                        "Chat row enhancement unavailable: " + failure.getClass().getSimpleName());
            }
        }
    }

    public static void bindVod(Object row, Object holder) {
        try {
            TextView view = VodRowAccess.textView(holder);
            Object model = VodRowAccess.model(row), commenter = VodRowAccess.commenter(model);
            long timestamp = 0;
            try {
                timestamp = java.time.Instant.parse(VodRowAccess.createdAt(model)).toEpochMilli();
            } catch (Exception ignored) {
            }
            bindRow(
                    view,
                    RowAccess.itemView(holder),
                    "vod:" + VodRowAccess.id(model),
                    VodRowAccess.channel(model),
                    VodRowAccess.username(commenter),
                    VodRowAccess.userId(commenter),
                    timestamp);
        } catch (Exception ignored) {
        }
    }

    /** Also used by the isolated Android harness with synthetic chat rows. */
    public static void bindRow(
            TextView text,
            View item,
            String id,
            String room,
            String username,
            String userId,
            long timestamp) {
        if (text == null || item == null) return;
        Row old = ROWS.remove(text);
        if (old != null) {
            old.restoreVisibility();
            text.setTextSize(TypedValue.COMPLEX_UNIT_PX, old.fontPx);
        }
        String effectiveRoom = room == null || room.isEmpty() ? currentRoom : room;
        Row row = new Row(text, item, effectiveRoom, username, userId, timestamp);
        String key = effectiveRoom + ":" + id;
        Integer order = ORDER.get(key);
        row.alert =
                order == null
                        && timestamp > 0
                        && Math.abs(System.currentTimeMillis() - timestamp) < 30000;
        if (order == null) {
            order = sequence++;
            ORDER.put(key, order);
        }
        row.order = order;
        ChatHistory.record(
                text.getContext(),
                new HistoryMessage(
                        id, effectiveRoom, username, row.original.toString(), timestamp, userId));
        row.repeat = REPEATS.isRepeat(effectiveRoom, userId, id, row.body, timestamp);
        ROWS.put(text, row);
        apply(text, row);
    }

    private static void apply(TextView text, Row row) {
        row.restoreVisibility();
        ChatSettings settings = ChatSettings.get(text.getContext());
        android.content.SharedPreferences prefs = ChatSettings.preferences(text.getContext());
        boolean alert = row.alert;
        row.alert = false;
        EmoteSupport.clear(text);
        text.setText(row.original, TextView.BufferType.SPANNABLE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_PX, row.fontPx);
        if (!settings.enabled) return;
        if (settings.rules.blocks(row.body, row.username, row.userId)
                || (settings.repeats && row.repeat)) {
            row.hide();
            return;
        }
        SpannableStringBuilder decorated = new SpannableStringBuilder(row.original);
        if (prefs.getBoolean("alternating", false) && (row.order & 1) != 0)
            decorated.setSpan(
                    new BackgroundColorSpan(0x206f6f6f),
                    0,
                    decorated.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (prefs.getBoolean("show_login", false)) showLogin(decorated, row.username);
        ChatMetadata.decorate(text, decorated, row.username, row.userId);
        if (settings.rules.highlights(row.body)) {
            decorated.setSpan(
                    new BackgroundColorSpan(
                            prefs.getInt("highlight_color", Color.argb(100, 145, 70, 255))),
                    0,
                    decorated.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (alert && prefs.getBoolean("vibrate_highlight", false))
                try {
                    android.os.Vibrator vibrator =
                            (android.os.Vibrator)
                                    text.getContext()
                                            .getSystemService(
                                                    android.content.Context.VIBRATOR_SERVICE);
                    if (vibrator != null)
                        vibrator.vibrate(
                                android.os.VibrationEffect.createOneShot(
                                        Math.max(
                                                10,
                                                Math.min(1000, prefs.getInt("vibration_ms", 100))),
                                        android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                } catch (SecurityException ignored) {
                }
        }
        if (settings.timestamps && row.timestamp > 0) {
            String stamp =
                    new SimpleDateFormat(
                                    prefs.getBoolean("timestamp_seconds", false)
                                            ? "HH:mm:ss "
                                            : "HH:mm ",
                                    Locale.getDefault())
                            .format(new Date(row.timestamp));
            decorated.insert(0, stamp);
            decorated.setSpan(
                    new RelativeSizeSpan(.8f), 0, stamp.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        text.setTextSize(TypedValue.COMPLEX_UNIT_PX, row.fontPx * settings.fontPercent / 100f);
        text.setText(decorated, TextView.BufferType.SPANNABLE);
        if (settings.sevenTv || settings.bttv || settings.ffz) EmoteSupport.bind(text, row.room);
    }

    /** "表示名 (login)" when the display name is not just the login in another case. */
    static void showLogin(SpannableStringBuilder text, String login) {
        if (login == null || !login.matches("[A-Za-z0-9_]{1,25}")) return;
        String plain = text.toString();
        int split = plain.indexOf(": ");
        if (split <= 0
                || plain.substring(0, split).toLowerCase(Locale.ROOT)
                        .contains(login.toLowerCase(Locale.ROOT))) return;
        String shown = " (" + login + ")";
        text.insert(split, shown);
        text.setSpan(new RelativeSizeSpan(.85f), split, split + shown.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new ForegroundColorSpan(0xFFADADB8), split, split + shown.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    public static void refresh() {
        for (Map.Entry<TextView, Row> entry : new ArrayList<>(ROWS.entrySet())) {
            TextView text = entry.getKey();
            Row row = entry.getValue();
            if (text != null && row != null) apply(text, row);
        }
    }

    private static final class Row {
        final SpannedString original;
        /** The message without the sender, for word rules and repeats. */
        final String body;
        final WeakReference<View> item;
        final float fontPx;
        final String room, username, userId;
        final long timestamp;
        final int visibility, height;
        boolean hidden, repeat;
        boolean alert;
        int order;

        Row(TextView text, View view, String room, String username, String userId, long timestamp) {
            original = SpannedString.valueOf(text.getText());
            body = ChatRules.body(original.toString());
            item = new WeakReference<>(view);
            fontPx = text.getTextSize();
            this.room = room;
            this.username = username;
            this.userId = userId;
            this.timestamp = timestamp;
            visibility = view.getVisibility();
            height =
                    view.getLayoutParams() == null
                            ? ViewGroup.LayoutParams.WRAP_CONTENT
                            : view.getLayoutParams().height;
        }

        void hide() {
            View view = item.get();
            if (view == null) return;
            hidden = true;
            view.setVisibility(View.GONE);
            ViewGroup.LayoutParams p = view.getLayoutParams();
            if (p != null) {
                p.height = 0;
                view.setLayoutParams(p);
            }
        }

        void restoreVisibility() {
            View view = item.get();
            if (!hidden || view == null) return;
            view.setVisibility(visibility);
            ViewGroup.LayoutParams p = view.getLayoutParams();
            if (p != null) {
                p.height = height;
                view.setLayoutParams(p);
            }
            hidden = false;
        }
    }
}
