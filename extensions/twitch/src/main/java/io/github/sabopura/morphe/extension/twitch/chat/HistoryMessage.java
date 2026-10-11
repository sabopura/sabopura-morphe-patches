package io.github.sabopura.morphe.extension.twitch.chat;

import java.util.*;

public final class HistoryMessage {
    public final String id, room, user, userId, text;
    public final long time;

    public HistoryMessage(String id, String room, String user, String text, long time) {
        this(id, room, user, text, time, "");
    }

    public HistoryMessage(
            String id, String room, String user, String text, long time, String userId) {
        this.id = id;
        this.room = room == null ? "" : room;
        this.user = user == null ? "" : user;
        this.userId = userId == null ? "" : userId;
        this.text = text;
        this.time = time;
    }

    public static HistoryMessage parseIrc(String line) {
        if (line == null || line.length() > 16000) return null;
        int command = line.indexOf(" PRIVMSG #"), payload = line.indexOf(" :", command + 10);
        if (command < 0 || payload < 0) return null;
        String channel = line.substring(command + 10, payload);
        if (!channel.matches("[A-Za-z0-9_]{1,25}")) return null;
        Map<String, String> tags = new HashMap<>();
        if (line.startsWith("@")) {
            int end = line.indexOf(' ');
            if (end < 1) return null;
            for (String tag : line.substring(1, end).split(";")) {
                int equal = tag.indexOf('=');
                if (equal > 0)
                    tags.put(tag.substring(0, equal), unescape(tag.substring(equal + 1)));
            }
        }
        int prefix = line.indexOf(" :"), bang = line.indexOf('!', prefix + 2);
        String user = tags.get("display-name");
        if (user == null || user.isEmpty())
            user = bang > prefix + 2 ? line.substring(prefix + 2, bang) : "";
        String text = line.substring(payload + 2);
        if (text.startsWith("\u0001ACTION ") && text.endsWith("\u0001"))
            text = "/me " + text.substring(8, text.length() - 1);
        long time = 0;
        try {
            time =
                    Long.parseLong(
                            tags.getOrDefault(
                                    "tmi-sent-ts", tags.getOrDefault("rm-received-ts", "0")));
        } catch (NumberFormatException ignored) {
        }
        return new HistoryMessage(
                tags.getOrDefault("id", line),
                channel,
                user,
                text,
                time,
                tags.getOrDefault("user-id", ""));
    }

    public static String unescape(String s) {
        StringBuilder b = new StringBuilder();
        boolean escape = false;
        for (char c : s.toCharArray()) {
            if (escape) {
                b.append(c == 's' ? ' ' : c == ':' ? ';' : c == 'r' ? '\r' : c == 'n' ? '\n' : c);
                escape = false;
            } else if (c == '\\') escape = true;
            else b.append(c);
        }
        return b.toString();
    }
}
