package io.github.sabopura.morphe.extension.twitch.chat;

public final class ChatTime {
    private ChatTime() {}

    /** Twitch divides IRC tmi-sent-ts by 1000 before storing MessageRecyclerItem's timestamp. */
    public static long nativeSecondsToMillis(Long seconds) {
        if (seconds == null || seconds <= 0 || seconds > Long.MAX_VALUE / 1000L) return 0;
        return seconds * 1000L;
    }
}
