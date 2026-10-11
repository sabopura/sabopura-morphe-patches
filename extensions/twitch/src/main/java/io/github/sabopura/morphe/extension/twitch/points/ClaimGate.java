package io.github.sabopura.morphe.extension.twitch.points;

import java.util.LinkedHashMap;

/** Process-local, bounded suppression of duplicate bonus claims. Uses monotonic time. */
public final class ClaimGate {
    private static final int MAX_ENTRIES = 128, MAX_ATTEMPTS = 3;
    private static final long RETRY_MS = 30_000, RETAIN_MS = 30 * 60_000;
    private final LinkedHashMap<String, Attempt> attempts = new LinkedHashMap<>();

    private static final class Attempt {
        long last;
        int count;
    }

    public synchronized boolean acquire(String id, String status, long now) {
        if (id == null || id.isEmpty() || id.length() > 256 || now < 0) return false;
        // Success, forbidden and expired claims are terminal. Unknown/new enum values fail closed.
        if (status != null && !status.equals("UNKNOWN")) return false;
        attempts.entrySet().removeIf(e -> now < e.getValue().last || now - e.getValue().last >= RETAIN_MS);
        Attempt attempt = attempts.get(id);
        if (attempt != null && (attempt.count >= MAX_ATTEMPTS || now - attempt.last < RETRY_MS)) return false;
        if (attempt == null) {
            if (attempts.size() >= MAX_ENTRIES) attempts.remove(attempts.keySet().iterator().next());
            attempt = new Attempt();
            attempts.put(id, attempt);
        }
        attempt.last = now;
        attempt.count++;
        return true;
    }
}
