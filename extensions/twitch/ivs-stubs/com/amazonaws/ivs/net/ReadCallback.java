package com.amazonaws.ivs.net;

/** Compile-only copy. Package-private in the SDK; the patch makes it public. */
public interface ReadCallback {
    int getTimeout();

    void onData(java.nio.ByteBuffer data, boolean end);

    void onData(byte[] data, int length, boolean end);

    void onError(Exception error);
}
