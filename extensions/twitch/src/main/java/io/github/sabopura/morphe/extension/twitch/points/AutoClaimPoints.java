package io.github.sabopura.morphe.extension.twitch.points;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

public final class AutoClaimPoints {
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final ClaimDispatcher DISPATCHER = new ClaimDispatcher(
            new ClaimDispatcher.Access() {
                public boolean enabled(Object state) { return PointsAccess.isEnabled(state); }
                public Object claim(Object state) { return PointsAccess.activeClaim(state); }
                public String id(Object claim) { return PointsAccess.id(claim); }
                public String status(Object state) {
                    Object status = PointsAccess.status(state);
                    return status == null ? null : status instanceof Enum<?> ? ((Enum<?>) status).name() : "INVALID";
                }
                public void submit(Object presenter, String id) { PointsAccess.claimBonus(presenter, id); }
            }, action -> UI.post(action), SystemClock::elapsedRealtime,
            () -> Log.w("Sabopura.Points", "Bonus claim unavailable; manual claim remains available"));
    private AutoClaimPoints() {}

    /** Observe native output, after Twitch's login, enabled-channel and ban checks. */
    public static void onViewState(Object viewState, Object presenter) {
        // Do not re-enter Twitch's state machine from its render callback.
        DISPATCHER.update(viewState, presenter);
    }
}
