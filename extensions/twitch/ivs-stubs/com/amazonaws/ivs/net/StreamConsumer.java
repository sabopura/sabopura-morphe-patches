package com.amazonaws.ivs.net;

/** Compile-only copy. Package-private in the SDK; the patch makes it public. */
public interface StreamConsumer {
    void consume(ReadCallback callback);
}
