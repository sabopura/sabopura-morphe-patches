package io.github.sabopura.morphe.extension.youtube.danmaku;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public final class CommentParserTest {
    @Test
    public void splitsEveryTimestampIntoItsFollowingSection() {
        List<CommentParser.TimestampSegment> result = CommentParser.splitTimestampSegments(
                "見どころ 0:15 イントロ / 1:20 サビ / 1:02:03 エンディング",
                4_000_000L
        );

        assertEquals(3, result.size());
        assertEquals("見どころ イントロ", result.get(0).text);
        assertEquals(15_000L, result.get(0).timeMs);
        assertEquals("サビ", result.get(1).text);
        assertEquals(80_000L, result.get(1).timeMs);
        assertEquals("エンディング", result.get(2).text);
        assertEquals(3_723_000L, result.get(2).timeMs);
    }

    @Test
    public void rejectsInvalidOrOutOfRangeTimestamps() {
        assertTrue(CommentParser.splitTimestampSegments("99:99 invalid", 600_000L).isEmpty());
        assertTrue(CommentParser.splitTimestampSegments("20:00 after video", 300_000L).isEmpty());
    }

    @Test
    public void keepsOneEntryPerTimestampWhenSectionsAreEmpty() {
        List<CommentParser.TimestampSegment> result = CommentParser.splitTimestampSegments(
                "0:10 / 0:20",
                30_000L
        );
        assertEquals(2, result.size());
        assertEquals(10_000L, result.get(0).timeMs);
        assertEquals(20_000L, result.get(1).timeMs);
    }

    @Test
    public void timestampSplitPreservesInlineEmojiInItsSection() {
        RichCommentText content = RichCommentText.builder()
                .appendText("0:10 intro ")
                .appendImage("https://yt3.ggpht.com/kusa=s24", "Kusa")
                .appendText(" / 0:20 end")
                .build();

        List<CommentParser.TimestampSegment> result =
                CommentParser.splitTimestampSegments(content, 30_000L);

        assertEquals(2, result.size());
        assertEquals("intro :Kusa:", result.get(0).text);
        assertEquals(1, result.get(0).richText.images.size());
        assertEquals("end", result.get(1).text);
    }
}
