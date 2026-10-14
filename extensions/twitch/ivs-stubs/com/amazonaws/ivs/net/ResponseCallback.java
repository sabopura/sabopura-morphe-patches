package com.amazonaws.ivs.net;

/** Compile-only copy of the Amazon IVS SDK interface shipped inside Twitch. */
public interface ResponseCallback {
    void onError(Exception error);

    void onResponse(Response response);
}
