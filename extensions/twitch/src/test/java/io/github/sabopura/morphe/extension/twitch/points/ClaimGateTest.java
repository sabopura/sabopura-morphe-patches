package io.github.sabopura.morphe.extension.twitch.points;

import org.junit.Test;
import static org.junit.Assert.*;

public class ClaimGateTest {
    @Test public void coalescesDuplicatesAndCapsRetries() {
        ClaimGate gate = new ClaimGate();
        assertTrue(gate.acquire("a", null, 100));
        assertFalse(gate.acquire("a", null, 101));
        assertFalse(gate.acquire("a", "UNKNOWN", 30099));
        assertTrue(gate.acquire("a", "UNKNOWN", 30100));
        assertTrue(gate.acquire("a", null, 60100));
        assertFalse(gate.acquire("a", null, 90100));
        assertTrue(gate.acquire("b", null, 90100));
    }
    @Test public void terminalAndUnrecognizedStatusesNeverSubmit() {
        for (String status : new String[]{"SUCCESS", "FORBIDDEN", "NOT_FOUND", "NEW_STATUS"})
            assertFalse(new ClaimGate().acquire("a", status, 1));
        assertFalse(new ClaimGate().acquire(null, null, 1));
        assertFalse(new ClaimGate().acquire("", null, 1));
        assertFalse(new ClaimGate().acquire("a", null, -1));
    }
    @Test public void historyIsBoundedAndExpires() {
        ClaimGate gate = new ClaimGate();
        for (int i = 0; i < 129; i++) assertTrue(gate.acquire("claim-" + i, null, 0));
        assertTrue(gate.acquire("claim-0", null, 0));
        assertFalse(gate.acquire("claim-128", null, 100));
        assertTrue(gate.acquire("claim-128", null, 1800000));
    }
}
