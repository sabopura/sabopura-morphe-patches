package io.github.sabopura.morphe.extension.youtube.danmaku;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure Java comment parsing shared by regular-video scheduling and unit-style checks. */
final class CommentParser {
    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile(
            "(?<![\\d:])(?:(\\d{1,3}):)?(\\d{1,3}):([0-5]\\d)(?!\\d)"
    );
    private static final Pattern LEADING_SEPARATOR = Pattern.compile(
            "^[\\s\\-–—|/・:：]+"
    );
    private static final Pattern TRAILING_SEPARATOR = Pattern.compile(
            "[\\s\\-–—|/・:：]+$"
    );

    private CommentParser() {
    }

    static List<TimestampSegment> splitTimestampSegments(String text, long videoLengthMs) {
        return splitTimestampSegments(RichCommentText.plain(text), videoLengthMs);
    }

    static List<TimestampSegment> splitTimestampSegments(RichCommentText content,
                                                          long videoLengthMs) {
        if (content == null || content.isEmpty()) return Collections.emptyList();
        String text = content.text;

        Matcher matcher = TIMESTAMP_PATTERN.matcher(text);
        List<Match> matches = new ArrayList<>();
        while (matcher.find()) {
            long timeMs = parseTimestampMs(matcher);
            if (timeMs < 0 || (videoLengthMs > 0 && timeMs > videoLengthMs)) continue;
            matches.add(new Match(matcher.start(), matcher.end(), timeMs));
        }
        if (matches.isEmpty()) return Collections.emptyList();

        List<TimestampSegment> result = new ArrayList<>(matches.size());
        RichCommentText prefix = cleanSegment(content.substring(0, matches.get(0).start));
        List<int[]> timestampRanges = new ArrayList<>(matches.size());
        for (Match match : matches) timestampRanges.add(new int[]{match.start, match.end});
        RichCommentText fallback = cleanSegment(content.removeRanges(timestampRanges));
        if (fallback.isEmpty()) fallback = content.normalized();

        for (int index = 0; index < matches.size(); index++) {
            Match current = matches.get(index);
            int end = index + 1 < matches.size() ? matches.get(index + 1).start : text.length();
            RichCommentText segment = cleanSegment(content.substring(current.end, end));
            if (index == 0 && !prefix.isEmpty()) {
                segment = segment.isEmpty() ? prefix : prefix.concat(segment, true);
            }
            if (segment.isEmpty()) segment = fallback;
            if (!segment.isEmpty()) {
                result.add(new TimestampSegment(segment, current.timeMs));
            }
        }
        return result;
    }

    static boolean hasTimestamp(String text, long videoLengthMs) {
        return !splitTimestampSegments(text, videoLengthMs).isEmpty();
    }

    private static long parseTimestampMs(Matcher matcher) {
        try {
            long minutes = Long.parseLong(matcher.group(2));
            long seconds = Long.parseLong(matcher.group(3));
            String hoursText = matcher.group(1);
            long totalSeconds;
            if (hoursText == null) {
                totalSeconds = minutes * 60 + seconds;
            } else {
                if (minutes >= 60) return -1;
                totalSeconds = Long.parseLong(hoursText) * 3600 + minutes * 60 + seconds;
            }
            return totalSeconds * 1000;
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static RichCommentText cleanSegment(RichCommentText value) {
        RichCommentText normalized = value.normalized();
        String cleaned = normalized.text;
        Matcher leading = LEADING_SEPARATOR.matcher(cleaned);
        int start = leading.find() ? leading.end() : 0;
        Matcher trailing = TRAILING_SEPARATOR.matcher(cleaned);
        int end = trailing.find() ? trailing.start() : cleaned.length();
        return normalized.substring(start, Math.max(start, end)).normalized();
    }

    private static final class Match {
        final int start;
        final int end;
        final long timeMs;

        Match(int start, int end, long timeMs) {
            this.start = start;
            this.end = end;
            this.timeMs = timeMs;
        }
    }

    static final class TimestampSegment {
        final String text;
        final RichCommentText richText;
        final long timeMs;

        TimestampSegment(String text, long timeMs) {
            this(RichCommentText.plain(text), timeMs);
        }

        TimestampSegment(RichCommentText richText, long timeMs) {
            this.richText = richText;
            this.text = richText.fallbackText();
            this.timeMs = timeMs;
        }
    }
}
