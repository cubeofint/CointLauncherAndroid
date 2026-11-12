package net.kdt.pojavlaunch.coint.auth;

import com.google.gson.*;
import okhttp3.*;
import java.io.IOException;
import java.util.concurrent.*;

/** One bounded, sequential request stream. No payload logging or automatic credential retry. */
public final class GravitSocket implements GravitAuth.Channel {
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build();
    private final BlockingQueue<Object> replies = new ArrayBlockingQueue<>(4);
    private final WebSocket socket;
    private volatile String pendingId;
    private volatile boolean dead;

    public GravitSocket() {
        socket = CLIENT.newWebSocket(new Request.Builder().url(GravitAuth.ENDPOINT).build(), new WebSocketListener() {
            @Override public void onMessage(WebSocket ws, String text) {
                if (text.length() > 1024 * 1024) { fail(); ws.cancel(); return; }
                try {
                    JsonElement value = JsonParser.parseString(text);
                    if (!value.isJsonObject()) { fail(); ws.cancel(); return; }
                    JsonObject response = value.getAsJsonObject();
                    if (GravitAuth.string(response, "requestUUID").equals(pendingId)) {
                        if (!replies.offer(response)) { fail(); ws.cancel(); }
                    }
                } catch (RuntimeException invalid) { fail(); ws.cancel(); }
            }
            @Override public void onFailure(WebSocket ws, Throwable error, Response response) { fail(); }
            @Override public void onClosing(WebSocket ws, int code, String reason) { fail(); ws.close(code, null); }
            @Override public void onClosed(WebSocket ws, int code, String reason) { fail(); }
        });
    }

    private void fail() { dead = true; replies.offer(Boolean.FALSE); }

    @Override public synchronized JsonObject request(JsonObject request) throws IOException {
        if (dead) throw new GravitAuth.Failure("network");
        replies.clear();
        pendingId = GravitAuth.string(request, "requestUUID");
        try {
            if (!socket.send(request.toString())) throw new GravitAuth.Failure("network");
            Object response = replies.poll(30, TimeUnit.SECONDS);
            if (!(response instanceof JsonObject)) throw new GravitAuth.Failure("network");
            return (JsonObject) response;
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw new GravitAuth.Failure("network");
        } finally { pendingId = null; }
    }

    @Override public void close() { fail(); socket.cancel(); }
}
