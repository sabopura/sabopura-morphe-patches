package io.github.sabopura.morphe.extension.twitch.chat;
import org.junit.Test;
import static org.junit.Assert.*;
public class HistoryMessageTest {
    @Test public void decodesIrcTagsAndActionWithoutLosingUnicode(){
        HistoryMessage m=HistoryMessage.parseIrc("@id=abc;display-name=User\\sName;user-id=12;tmi-sent-ts=1700000000000 :user!u@host PRIVMSG #channel :\u0001ACTION 日本語\u0001");
        assertNotNull(m);assertEquals("User Name",m.user);assertEquals("/me 日本語",m.text);assertEquals("channel",m.room);assertEquals(1700000000000L,m.time);
    }
    @Test public void ignoresModerationAndMalformedChannel(){
        assertNull(HistoryMessage.parseIrc(":server CLEARCHAT #channel :user"));
        assertNull(HistoryMessage.parseIrc(":u!u@host PRIVMSG #channel/path :text"));
        assertNull(HistoryMessage.parseIrc("not irc"));
    }
    @Test public void trailingEscapeAndEmptyDisplayUseProtocolRules(){
        assertEquals("a;b c\\d\ne",HistoryMessage.unescape("a\\:b\\sc\\\\d\\ne\\"));
        assertEquals("user",HistoryMessage.parseIrc("@display-name= :user!u@h PRIVMSG #c :message").user);
    }
}
