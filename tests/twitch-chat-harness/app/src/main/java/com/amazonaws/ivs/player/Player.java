package com.amazonaws.ivs.player;

import android.net.Uri;

/** Harness-only substitute for the public interface resolved by PlayerTools in Twitch. */
public interface Player {
    void pause();

    void play();

    void load(Uri uri);

    void seekTo(long time);

    void setPlaybackRate(float value);

    long getPosition();

    long getDuration();

    long getBufferedPosition();

    long getLiveLatency();

    long getAverageBitrate();

    float getPlaybackRate();

    String getPath();
}
