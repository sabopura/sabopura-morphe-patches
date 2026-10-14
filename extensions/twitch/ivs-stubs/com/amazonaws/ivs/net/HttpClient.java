package com.amazonaws.ivs.net;

/** Compile-only copy of the Amazon IVS SDK interface shipped inside Twitch. */
public interface HttpClient {
    String description();

    void execute(Request request, ResponseCallback callback);

    void release();
}
