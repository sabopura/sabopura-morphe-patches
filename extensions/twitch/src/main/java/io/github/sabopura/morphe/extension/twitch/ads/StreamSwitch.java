package io.github.sabopura.morphe.extension.twitch.ads;

/**
 * Decides, for each refresh of a live media playlist, whether the player gets the original
 * session or the ad-free backup. Network access is supplied by the caller.
 */
final class StreamSwitch {
    /** Fetches the backup's matching media playlist, or returns null if none is clean. */
    interface Backup {
        String cleanPlaylist();
    }

    /** What happened, for counting and logging. */
    enum Outcome { ORIGINAL, SWITCHED_TO_BACKUP, ON_BACKUP, BACK_TO_ORIGINAL, AD_KEPT }

    private boolean onBackup;
    private Outcome last = Outcome.ORIGINAL;

    synchronized boolean onBackup() {
        return onBackup;
    }

    synchronized Outcome lastOutcome() {
        return last;
    }

    /** The playlist to give the player for this refresh of the original session. */
    synchronized String choose(String original, Backup backup) {
        boolean ad = Playlists.hasStitchedAd(original);
        if (!ad && (!onBackup || Playlists.isCleanLive(original))) {
            // A clean original is used again only once it carries live sequence numbers, so the
            // player's numbering continues from the backup without a jump.
            last = onBackup ? Outcome.BACK_TO_ORIGINAL : Outcome.ORIGINAL;
            onBackup = false;
            return Playlists.prepare(original);
        }
        String clean = backup.cleanPlaylist();
        if (clean != null) {
            last = onBackup ? Outcome.ON_BACKUP : Outcome.SWITCHED_TO_BACKUP;
            onBackup = true;
            return Playlists.prepare(clean);
        }
        // No backup now: the original is still the best there is, ad or not.
        last = ad ? Outcome.AD_KEPT : Outcome.BACK_TO_ORIGINAL;
        onBackup = false;
        return Playlists.prepare(original);
    }
}
