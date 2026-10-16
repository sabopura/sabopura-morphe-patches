package io.github.sabopura.morphe.extension.twitch.player;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * Root of Twitch's player view. Behaves exactly like the FrameLayout it replaces; it only tells
 * {@link PlayerGestures} where the video is, because the swipes are read at the window (the
 * player's controls lie above the video and receive the touches first).
 */
public final class PlayerGestureFrame extends FrameLayout {
    public PlayerGestureFrame(Context c, AttributeSet a) {
        super(c, a);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        PlayerGestures.attach(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        PlayerGestures.detach(this);
        super.onDetachedFromWindow();
    }
}
