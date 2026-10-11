package io.github.sabopura.morphe.extension.twitch.chat;

import org.junit.Test;
import static org.junit.Assert.*;

public class ChatRulesTest {
    @Test public void matchesWidthAndCaseButDoesNotTreatInputAsRegex() {
        ChatRules rules = new ChatRules("ＳＰＡＭ\na.*b", "", "GOOD");
        assertTrue(rules.blocks("some spam here", "user", "42"));
        assertFalse(rules.blocks("axxb", "user", "42"));
        assertTrue(rules.blocks("literal a.*b", "user", "42"));
        assertTrue(rules.highlights("good news"));
    }
    @Test public void userRulesAreExactAndSupportIdsAndAtPrefix() {
        ChatRules rules = new ChatRules("", "@Alice\n123", "");
        assertTrue(rules.blocks("message", "ALICE", "999"));
        assertTrue(rules.blocks("message", "someone", "123"));
        assertFalse(rules.blocks("Alice said hello", "alice2", "1234"));
    }
    @Test public void blankRulesAndNullFieldsAreHarmless() {
        ChatRules rules = new ChatRules("\n\n", null, "\n");
        assertFalse(rules.blocks(null, null, null));
        assertFalse(rules.highlights("anything"));
    }
}
