package io.github.sabopura.morphe.extension.twitch.danmaku;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads Twitch's parsed live chat message object.
 *
 * <p>The message class and its fields are obfuscated, so fields are recognised by their
 * Java type (one {@code Map} of IRC tags, one {@code Integer} name colour, one {@code Long}
 * timestamp) and by the content of lists (chat tokens). The layout is resolved once per
 * class and cached.
 */
final class ChatMessageReader {
    /** A chat message ready for display. */
    static final class Comment {
        final String id;
        final String author;
        final Integer color;
        final List<ChatTokens.Segment> segments;
        final String plainText;
        /** Server send time in epoch milliseconds, or 0 when unknown. */
        final long sentAtMs;

        Comment(String id, String author, Integer color, List<ChatTokens.Segment> segments,
                long sentAtMs) {
            this.id = id;
            this.author = author;
            this.color = color;
            this.segments = segments;
            this.plainText = ChatTokens.plainText(segments);
            this.sentAtMs = sentAtMs;
        }
    }

    private static final class Layout {
        final List<Field> tags = new ArrayList<>();
        final List<Field> colors = new ArrayList<>();
        final List<Field> timestamps = new ArrayList<>();
        final List<Field> lists = new ArrayList<>();
        final List<Field> others = new ArrayList<>();
    }

    private static final Map<Class<?>, Layout> LAYOUTS = new ConcurrentHashMap<>();

    private ChatMessageReader() {
    }

    static Comment read(Object message) throws IllegalAccessException {
        if (message == null) return null;
        Layout layout = layoutOf(message.getClass());

        Map<?, ?> tags = null;
        for (Field field : layout.tags) {
            Object value = field.get(message);
            if (value instanceof Map) {
                tags = (Map<?, ?>) value;
                break;
            }
        }

        List<ChatTokens.Segment> segments = null;
        for (Field field : layout.lists) {
            Object value = field.get(message);
            if (!(value instanceof List)) continue;
            List<?> list = (List<?>) value;
            if (list.isEmpty() || !ChatTokens.isToken(String.valueOf(list.get(0)))) continue;
            segments = new ArrayList<>(ChatTokens.parseAll(list));
            break;
        }
        if (segments == null) segments = new ArrayList<>();

        // A message consisting of a single "gigantified" emote keeps it in its own field.
        for (Field field : layout.others) {
            Object value = field.get(message);
            if (value == null) continue;
            String description = String.valueOf(value);
            if (!description.startsWith("EmoteToken(")) continue;
            ChatTokens.Segment emote = ChatTokens.parse(description);
            if (emote != null) segments.add(emote);
        }
        if (segments.isEmpty()) return null;

        Integer color = null;
        for (Field field : layout.colors) {
            Object value = field.get(message);
            if (value instanceof Integer) {
                color = (Integer) value;
                break;
            }
        }

        long sentAtMs = 0L;
        Object sentTag = tags == null ? null : tags.get("tmi-sent-ts");
        if (sentTag != null) {
            try {
                sentAtMs = Long.parseLong(String.valueOf(sentTag));
            } catch (NumberFormatException ignored) {
                // Fall back to the typed timestamp below.
            }
        }
        if (sentAtMs <= 0L) {
            for (Field field : layout.timestamps) {
                Object value = field.get(message);
                if (value instanceof Long) {
                    long raw = (Long) value;
                    sentAtMs = raw > 100_000_000_000L ? raw : raw * 1000L;
                    break;
                }
            }
        }

        String id = tag(tags, "id");
        String author = tag(tags, "display-name");
        if (author == null) author = tag(tags, "login");
        return new Comment(id, author == null ? "" : author, color, segments, sentAtMs);
    }

    private static String tag(Map<?, ?> tags, String key) {
        if (tags == null) return null;
        Object value = tags.get(key);
        if (value == null) return null;
        String text = String.valueOf(value);
        return text.isEmpty() ? null : text;
    }

    private static Layout layoutOf(Class<?> type) {
        Layout cached = LAYOUTS.get(type);
        if (cached != null) return cached;
        Layout layout = new Layout();
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                Class<?> fieldType = field.getType();
                try {
                    field.setAccessible(true);
                } catch (RuntimeException ignored) {
                    continue;
                }
                if (Map.class.isAssignableFrom(fieldType)) {
                    layout.tags.add(field);
                } else if (fieldType == Integer.class) {
                    layout.colors.add(field);
                } else if (fieldType == Long.class) {
                    layout.timestamps.add(field);
                } else if (List.class.isAssignableFrom(fieldType) || fieldType == Object.class) {
                    // Some builds widen one list field to Object; content decides.
                    layout.lists.add(field);
                } else if (!fieldType.isPrimitive() && fieldType != String.class) {
                    layout.others.add(field);
                }
            }
        }
        LAYOUTS.put(type, layout);
        return layout;
    }
}
