package io.github.sabopura.twitchharness;

import android.net.Uri;

public final class PlayerFixture implements com.amazonaws.ivs.player.Player {
    public static final class NativeSize {
        private final int width, height;

        public NativeSize(int width, int height) {
            this.width = width;
            this.height = height;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }
    }

    public boolean paused;
    public long position = 10000;
    public float rate = 1;
    public String path =
            "https://usher.ttvnw.net/api/channel/hls/test.m3u8?token=synthetic&sig=synthetic";

    public void pause() {
        paused = true;
    }

    public void play() {
        paused = false;
    }

    public void load(Uri uri) {
        path = uri.toString();
        position = 0;
    }

    public void seekTo(long p) {
        position = p;
    }

    public void setPlaybackRate(float v) {
        rate = v;
    }

    public long getPosition() {
        return position;
    }

    public long getDuration() {
        return 100000;
    }

    public long getBufferedPosition() {
        return position + 1000;
    }

    public long getLiveLatency() {
        return 3000;
    }

    public long getAverageBitrate() {
        return 4000000;
    }

    public float getPlaybackRate() {
        return rate;
    }

    public String getPath() {
        return path;
    }

    public static class Vod {
        public String getId() {
            return "v123456";
        }
    }

    public static class Channel {
        public int getId() {
            return 1234;
        }

        public String getName() {
            return "test";
        }
    }
}
