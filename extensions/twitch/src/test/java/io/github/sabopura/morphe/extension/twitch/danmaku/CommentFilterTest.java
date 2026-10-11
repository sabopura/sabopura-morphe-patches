package io.github.sabopura.morphe.extension.twitch.danmaku;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CommentFilterTest {
    @Test
    public void emptyRulesBlockNothing() {
        assertTrue(CommentFilter.parse("").isEmpty());
        assertTrue(CommentFilter.parse(" \n , ").isEmpty());
        assertFalse(CommentFilter.parse(null).blocks("anything"));
    }

    @Test
    public void plainRulesIgnoreCaseWidthAndKana() {
        CommentFilter filter = CommentFilter.parse("spoiler\nネタバレ, ＡＢＣ");
        assertTrue(filter.blocks("No SPOILERS please"));
        assertTrue(filter.blocks("これはねたばれです"));
        assertTrue(filter.blocks("abc"));
        assertFalse(filter.blocks("great stream"));
    }

    @Test
    public void slashDelimitedRulesAreRegularExpressions() {
        CommentFilter filter = CommentFilter.parse("/^w+$/\n/[0-9]{3}-[0-9]{4}/");
        assertTrue(filter.blocks("wwww"));
        assertFalse(filter.blocks("wow"));
        assertTrue(filter.blocks("call 555-1234"));
    }

    @Test
    public void invalidRegularExpressionDoesNotDisableOtherRules() {
        CommentFilter filter = CommentFilter.parse("/[unclosed/\nbad");
        assertTrue(filter.blocks("so bad"));
        assertFalse(filter.blocks("[unclosed"));
    }

    @Test
    public void duplicateKeyFoldsRepetitionAndWidth() {
        assertEquals(CommentFilter.duplicateKey("www"), CommentFilter.duplicateKey("wwwwwwww"));
        assertEquals(CommentFilter.duplicateKey("ｗｗｗ"), CommentFilter.duplicateKey("www"));
        assertEquals(CommentFilter.duplicateKey("草 草"), CommentFilter.duplicateKey("草草"));
        assertNotEquals(CommentFilter.duplicateKey("ww"), CommentFilter.duplicateKey("www"));
        assertNotEquals(CommentFilter.duplicateKey("hello"), CommentFilter.duplicateKey("help"));
    }
}
