package io.github.sabopura.morphe.extension.twitch.player;
import org.junit.Test;
import static org.junit.Assert.*;
public class LiveProxyTest {
    @Test public void acceptsOnlyHttpsBaseWithoutEmbeddedCredentialsOrParameters(){
        assertTrue(LiveProxy.validBase("https://lb-as.cdn-perfprod.com/live/"));
        for(String value:new String[]{"http://proxy.example/live/","https://user:password@proxy.example/live/","https://proxy.example/live/?token=secret","https://proxy.example/live/#fragment","https://proxy.example/live","https://proxy.example:80/live/","file:///tmp/"})
            assertFalse(value,LiveProxy.validBase(value));
    }
}
