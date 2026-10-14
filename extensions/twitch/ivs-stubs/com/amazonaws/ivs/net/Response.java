package com.amazonaws.ivs.net;

import java.util.concurrent.ExecutorService;

/** Compile-only copy of the Amazon IVS SDK class shipped inside Twitch. */
public class Response {
    public Response(int status, String url) { throw new UnsupportedOperationException(); }

    public String getHeader(String name) { throw new UnsupportedOperationException(); }

    public int getStatus() { throw new UnsupportedOperationException(); }

    public String getUrl() { throw new UnsupportedOperationException(); }

    public void readContent(ReadCallback callback) { throw new UnsupportedOperationException(); }

    public void setConsumer(ExecutorService executor, StreamConsumer consumer) { throw new UnsupportedOperationException(); }

    public void setHeader(String name, String value) { throw new UnsupportedOperationException(); }
}
