package io.github.sabopura.morphe.extension.twitch.chat;

import static org.junit.Assert.*;
import org.junit.Test;

public class ChatTimeTest {
    @Test public void nativeEpochSecondsMatchIrcAndJavaEpochMillis() {
        assertEquals(1700000000000L, ChatTime.nativeSecondsToMillis(1700000000L));
        RepeatFilter repeats = new RepeatFilter();
        assertFalse(repeats.isRepeat("room", "user", "a", "same", ChatTime.nativeSecondsToMillis(1700000000L)));
        assertTrue(repeats.isRepeat("room", "user", "b", "same", ChatTime.nativeSecondsToMillis(1700000007L)));
        assertFalse(repeats.isRepeat("room", "user", "c", "same", ChatTime.nativeSecondsToMillis(1700000020L)));
    }
    @Test public void absentAndInvalidNativeTimesRemainUnknown() {
        assertEquals(0, ChatTime.nativeSecondsToMillis(null));
        assertEquals(0, ChatTime.nativeSecondsToMillis(0L));
        assertEquals(0, ChatTime.nativeSecondsToMillis(-1L));
        assertEquals(0, ChatTime.nativeSecondsToMillis(Long.MAX_VALUE));
    }
}
