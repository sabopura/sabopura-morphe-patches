package io.github.sabopura.morphe.extension.youtube.danmaku;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Immutable comment text with inline YouTube emoji image positions. */
final class RichCommentText {
    static final char IMAGE_PLACEHOLDER = '\uFFFC';

    final String text;
    final List<InlineImage> images;

    RichCommentText(String text, List<InlineImage> images) {
        this.text = text == null ? "" : text;
        List<InlineImage> copy = images == null
                ? new ArrayList<>() : new ArrayList<>(images);
        copy.sort(Comparator.comparingInt(value -> value.offset));
        this.images = Collections.unmodifiableList(copy);
    }

    static RichCommentText plain(String text) {
        return new RichCommentText(text, Collections.emptyList());
    }

    static Builder builder() {
        return new Builder();
    }

    static RichCommentText withAttachments(String source, List<SourceImage> attachments) {
        if (source == null) source = "";
        if (attachments == null || attachments.isEmpty()) return plain(source);
        List<SourceImage> ordered = new ArrayList<>(attachments);
        ordered.sort(Comparator.comparingInt(value -> value.start));
        Builder builder = builder();
        int cursor = 0;
        for (SourceImage image : ordered) {
            int start = image.start;
            int end = start + image.length;
            if (start < cursor || image.length <= 0 || start < 0 || end > source.length()) {
                continue;
            }
            builder.appendText(source.substring(cursor, start));
            builder.appendImage(image.url, image.label);
            cursor = end;
        }
        builder.appendText(source.substring(cursor));
        return builder.build();
    }

    String fallbackText() {
        if (images.isEmpty()) return text;
        StringBuilder result = new StringBuilder(text.length() + images.size() * 8);
        int imageIndex = 0;
        for (int index = 0; index < text.length(); index++) {
            InlineImage image = imageIndex < images.size() ? images.get(imageIndex) : null;
            if (image != null && image.offset == index && text.charAt(index) == IMAGE_PLACEHOLDER) {
                result.append(':').append(image.label.isEmpty() ? "emoji" : image.label).append(':');
                imageIndex++;
            } else {
                result.append(text.charAt(index));
            }
        }
        return result.toString();
    }

    int visibleCodePointCount() {
        int count = text.codePointCount(0, text.length());
        return Math.max(0, count);
    }

    boolean isEmpty() {
        return text.isEmpty();
    }

    RichCommentText substring(int start, int end) {
        int safeStart = Math.max(0, Math.min(start, text.length()));
        int safeEnd = Math.max(safeStart, Math.min(end, text.length()));
        List<InlineImage> sliced = new ArrayList<>();
        for (InlineImage image : images) {
            if (image.offset >= safeStart && image.offset < safeEnd) {
                sliced.add(new InlineImage(image.offset - safeStart, image.url, image.label));
            }
        }
        return new RichCommentText(text.substring(safeStart, safeEnd), sliced);
    }

    RichCommentText concat(RichCommentText other, boolean spaceBetween) {
        if (other == null || other.isEmpty()) return this;
        if (isEmpty()) return other;
        String separator = spaceBetween ? " " : "";
        List<InlineImage> merged = new ArrayList<>(images);
        int shift = text.length() + separator.length();
        for (InlineImage image : other.images) {
            merged.add(new InlineImage(image.offset + shift, image.url, image.label));
        }
        return new RichCommentText(text + separator + other.text, merged);
    }

    RichCommentText removeRanges(List<int[]> ranges) {
        if (ranges == null || ranges.isEmpty()) return this;
        RichCommentText result = plain("");
        int cursor = 0;
        for (int[] range : ranges) {
            if (range == null || range.length < 2) continue;
            int start = Math.max(cursor, Math.min(range[0], text.length()));
            int end = Math.max(start, Math.min(range[1], text.length()));
            result = result.concat(substring(cursor, start), false);
            cursor = end;
        }
        return result.concat(substring(cursor, text.length()), false);
    }

    /** Removes invisible joiners and collapses whitespace without losing image offsets. */
    RichCommentText normalized() {
        Builder builder = builder();
        int imageIndex = 0;
        boolean pendingSpace = false;
        boolean hasOutput = false;
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            InlineImage image = imageIndex < images.size() ? images.get(imageIndex) : null;
            if (image != null && image.offset == index && value == IMAGE_PLACEHOLDER) {
                if (pendingSpace && hasOutput) builder.appendText(" ");
                pendingSpace = false;
                builder.appendImage(image.url, image.label);
                hasOutput = true;
                imageIndex++;
            } else if (value == '\u2060') {
                // YouTube uses WORD JOINER as layout metadata; it is not visible content.
            } else if (Character.isWhitespace(value)) {
                pendingSpace = hasOutput;
            } else {
                if (pendingSpace && hasOutput) builder.appendText(" ");
                pendingSpace = false;
                builder.appendText(String.valueOf(value));
                hasOutput = true;
            }
        }
        return builder.build();
    }

    static final class InlineImage {
        final int offset;
        final String url;
        final String label;

        InlineImage(int offset, String url, String label) {
            this.offset = offset;
            this.url = url == null ? "" : url;
            this.label = label == null ? "" : label;
        }
    }

    static final class SourceImage {
        final int start;
        final int length;
        final String url;
        final String label;

        SourceImage(int start, int length, String url, String label) {
            this.start = start;
            this.length = length;
            this.url = url;
            this.label = label;
        }
    }

    static final class Builder {
        private final StringBuilder text = new StringBuilder();
        private final List<InlineImage> images = new ArrayList<>();

        Builder appendText(String value) {
            if (value != null) text.append(value);
            return this;
        }

        Builder appendImage(String url, String label) {
            images.add(new InlineImage(text.length(), url, label));
            text.append(IMAGE_PLACEHOLDER);
            return this;
        }

        RichCommentText build() {
            return new RichCommentText(text.toString(), images);
        }
    }
}
