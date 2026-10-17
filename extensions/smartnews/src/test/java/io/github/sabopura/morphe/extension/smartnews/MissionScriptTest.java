package io.github.sabopura.morphe.extension.smartnews;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MissionScriptTest {
    @Test
    public void matchesOnlyTheMissionLandingPage() {
        assertTrue(MissionScript.isMissionPage("https://tour.smartnews.com/campaign/"));
        assertTrue(MissionScript.isMissionPage("https://tour.smartnews.com/campaign?rt=abc#top"));
        assertFalse(MissionScript.isMissionPage("https://tour.smartnews.com/campaign-preview/"));
        assertFalse(MissionScript.isMissionPage("https://tour.smartnews.com/errors/campaign-ended"));
        assertFalse(MissionScript.isMissionPage("http://tour.smartnews.com/campaign/"));
        assertFalse(MissionScript.isMissionPage("https://tour.smartnews.com.example.com/campaign/"));
        assertFalse(MissionScript.isMissionPage("https://points.smartnews.com/"));
        assertFalse(MissionScript.isMissionPage(null));
        assertFalse(MissionScript.isMissionPage("not a url"));
    }

    @Test
    public void summarisesClaims() {
        assertEquals("スマニュー +45P を受け取りました・抽選 2 回（はずれ）",
                MissionScript.summary(2, 45, 2, 0, 0, true));
        assertEquals("+30 SmartNews points claimed", MissionScript.summary(1, 30, 0, 0, 0, false));
        assertEquals("ミッション報酬を 1 件受け取りました", MissionScript.summary(1, 0, 0, 0, 0, true));
        assertEquals("抽選に当選しました！マイミッションで確認してください",
                MissionScript.summary(0, 0, 1, 1, 0, true));
        assertEquals("受け取れなかった報酬が 1 件あります（ログインなどが必要な場合があります）",
                MissionScript.summary(0, 0, 0, 0, 1, true));
        assertEquals("", MissionScript.summary(0, 0, 0, 0, 0, true));
    }

    @Test
    public void lotteryOptionIsEmbedded() {
        assertTrue(MissionScript.script(true, false, false).contains("LOT=true"));
        assertTrue(MissionScript.script(false, false, true).contains("LOT=false"));
        assertTrue(MissionScript.script(false, false, true).contains("BG=true"));
    }
}
