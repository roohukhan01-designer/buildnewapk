package com.roohu.clipforge;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/** Shared network helpers: HTTP failures with a status code, and retry with exponential backoff + jitter. */
final class Net {
    /** A non-2xx answer. 429 and 5xx are retried; 4xx (bad key, bad request) fail immediately. */
    static final class HttpFail extends IOException {
        final int code; final long retryAfterMs;
        HttpFail(int code, String msg, long retryAfterMs) { super(msg); this.code = code; this.retryAfterMs = retryAfterMs; }
    }

    interface Call<T> { T run() throws Exception; }

    /** Runs the call up to `attempts` times: waits 1 s, 2 s, 4 s... (max 16 s, honouring Retry-After). Call off the UI thread only. */
    static <T> T retry(Call<T> call, int attempts) throws Exception {
        long delay = 1000;
        Exception last = null;
        for (int i = 0; i < attempts; i++) {
            try {
                return call.run();
            } catch (HttpFail f) {
                if (f.code != 429 && f.code < 500) throw f;
                last = f;
                if (i == attempts - 1) break;
                pause(Math.max(delay, f.retryAfterMs) + (long) (Math.random() * 400));
            } catch (SocketTimeoutException | ConnectException | UnknownHostException | EOFException e) {
                last = e;
                if (i == attempts - 1) break;
                pause(delay + (long) (Math.random() * 400));
            }
            delay = Math.min(delay * 2, 16000);
        }
        if (last instanceof HttpFail) throw last;
        throw new IOException("Cannot reach the cloud captions service. Check the internet connection and try again.");
    }

    private static void pause(long ms) throws IOException {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Cancelled"); }
    }
}
