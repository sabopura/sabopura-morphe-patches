package io.github.sabopura.morphe.extension.twitch.chat;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Literal rules: no user-supplied regular expressions run on Twitch's UI thread. */
public final class ChatRules {
    private final List<String> blocked;
    private final List<String> highlighted;
    private final Set<String> users;

    public ChatRules(String words, String usernames, String highlights) {
        blocked = lines(words);
        highlighted = lines(highlights);
        users = new HashSet<>();
        for (String user : lines(usernames))
            users.add(user.startsWith("@") ? user.substring(1) : user);
    }

    public boolean blocks(String text, String username, String userId) {
        return users.contains(normalize(username))
                || users.contains(normalize(userId))
                || contains(blocked, text);
    }

    public boolean highlights(String text) {
        return contains(highlighted, text);
    }

    private static boolean contains(List<String> rules, String text) {
        if (rules.isEmpty()) return false;
        String normalized = normalize(text);
        for (String rule : rules) if (normalized.contains(rule)) return true;
        return false;
    }

    private static List<String> lines(String text) {
        List<String> result = new ArrayList<>();
        if (text == null) return result;
        for (String line : text.split("[\\r\\n]+")) {
            String normalized = normalize(line.trim());
            if (!normalized.isEmpty()) result.add(normalized);
            if (result.size() == 200) break;
        }
        return result;
    }

    /**
     * The message of a rendered chat row ("badges Name: message"), so word rules do not match
     * the sender's name. Rows without a name, such as notices, are used whole.
     */
    public static String body(String row) {
        if (row == null) return "";
        int split = row.indexOf(": ");
        return split < 0 ? row : row.substring(split + 2);
    }

    static String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .trim();
    }
}
