package io.github.sabopura.morphe.extension.twitch.danmaku;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Immutable NG-word filter and duplicate key builder. Pure Java so it can be unit tested. */
final class CommentFilter {
    static final CommentFilter EMPTY = new CommentFilter(Collections.emptyList(),
            Collections.emptyList());

    private final List<String> words;
    private final List<Pattern> patterns;

    private CommentFilter(List<String> words, List<Pattern> patterns) {
        this.words = words;
        this.patterns = patterns;
    }

    /**
     * Parses one rule per line or comma. Plain rules are case-, width- and
     * kana-insensitive substrings; {@code /expression/} rules are regular expressions.
     */
    static CommentFilter parse(String rules) {
        if (rules == null || rules.trim().isEmpty()) return EMPTY;
        List<String> words = new ArrayList<>();
        List<Pattern> patterns = new ArrayList<>();
        for (String line : rules.split("[\\r\\n]+")) {
            String trimmedLine = line.trim();
            if (trimmedLine.length() > 2 && trimmedLine.startsWith("/")
                    && trimmedLine.endsWith("/")) {
                try {
                    patterns.add(Pattern.compile(
                            trimmedLine.substring(1, trimmedLine.length() - 1),
                            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
                } catch (PatternSyntaxException ignored) {
                    // An invalid expression must never disable the other rules.
                }
                continue;
            }
            for (String rule : trimmedLine.split("[,、，]")) {
                String normalized = normalize(rule);
                if (!normalized.isEmpty()) words.add(normalized);
            }
        }
        if (words.isEmpty() && patterns.isEmpty()) return EMPTY;
        return new CommentFilter(Collections.unmodifiableList(words),
                Collections.unmodifiableList(patterns));
    }

    boolean isEmpty() {
        return words.isEmpty() && patterns.isEmpty();
    }

    boolean blocks(String text) {
        if (isEmpty() || text == null) return false;
        String normalized = normalize(text);
        for (String word : words) {
            if (normalized.contains(word)) return true;
        }
        for (Pattern pattern : patterns) {
            if (pattern.matcher(text).find()) return true;
        }
        return false;
    }

    /** Folds width, case, katakana/hiragana, whitespace, and long repetitions. */
    static String normalize(String text) {
        if (text == null) return "";
        String folded = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(folded.length());
        for (int index = 0; index < folded.length(); index++) {
            char value = folded.charAt(index);
            if (Character.isWhitespace(value) || value == '⁠') continue;
            if (value >= 'ァ' && value <= 'ヶ') value = (char) (value - 0x60);
            result.append(value);
        }
        return result.toString();
    }

    /**
     * Key used to merge spam such as "www", "wwwww" and "ｗｗｗ" or repeated emoji.
     * Runs of the same character longer than three collapse to three.
     */
    static String duplicateKey(String text) {
        String normalized = normalize(text);
        StringBuilder result = new StringBuilder(normalized.length());
        int run = 0;
        int previous = -1;
        for (int offset = 0; offset < normalized.length(); ) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            run = codePoint == previous ? run + 1 : 1;
            previous = codePoint;
            if (run <= 3) result.appendCodePoint(codePoint);
        }
        return result.toString();
    }
}
