package net.kdt.pojavlaunch.coint.auth;

import android.app.Instrumentation;
import android.content.ContextWrapper;
import android.os.Bundle;
import com.google.gson.JsonObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Device checks without account credentials or access to the real session file. */
public final class CointAuthInstrumentation extends Instrumentation {
    private boolean discovery;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        discovery = arguments != null && "true".equals(arguments.getString("discovery"));
        start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        File directory = new File(getTargetContext().getNoBackupFilesDir(), "auth-instrumentation-probe");
        boolean created = false;
        int code = -1;
        try {
            if (!directory.mkdir()) throw new IOException("Probe directory already exists");
            created = true;
            CointSessionStore store = new CointSessionStore(new ContextWrapper(getTargetContext()) {
                @Override public File getNoBackupFilesDir() { return directory; }
            });
            GravitAuth.Session session = new GravitAuth.Session();
            session.username = "CointProbe";
            session.uuid = "11111111-1111-1111-1111-111111111111";
            session.authId = "std";
            session.accessToken = "instrumentation-fake-access";
            session.refreshToken = "instrumentation-fake-refresh";
            session.expiresAt = 123_456L;
            store.save(session);
            File saved = new File(directory, "coint-session.v1");
            byte[] bytes = Files.readAllBytes(saved.toPath());
            String raw = new String(bytes, StandardCharsets.ISO_8859_1);
            if (raw.contains(session.accessToken) || raw.contains(session.refreshToken) || raw.contains(session.username)) {
                throw new IOException("Session not encrypted");
            }
            GravitAuth.Session loaded = store.load();
            if (!session.refreshToken.equals(loaded.refreshToken) || !session.uuid.equals(loaded.uuid)
                    || loaded.expiresAt != session.expiresAt) throw new IOException("Session round trip failed");
            bytes[bytes.length - 1] ^= 1;
            Files.write(saved.toPath(), bytes);
            boolean rejected = false;
            try { store.load(); } catch (IOException expected) { rejected = true; }
            if (!rejected) throw new IOException("Modified ciphertext accepted");
            store.clear();
            if (store.load() != null) throw new IOException("Session was not removed");
            result.putString("session_storage", "PASS: encrypted round trip, integrity, removal");
            if (discovery) {
                try (GravitSocket socket = new GravitSocket()) {
                    JsonObject request = new JsonObject();
                    request.addProperty("type", "getAvailabilityAuth");
                    String id = java.util.UUID.randomUUID().toString();
                    request.addProperty("requestUUID", id);
                    JsonObject response = socket.request(request);
                    if (!id.equals(GravitAuth.string(response, "requestUUID"))
                            || !"getAvailabilityAuth".equals(GravitAuth.string(response, "type"))
                            || !response.has("list") || !response.get("list").isJsonArray()) {
                        throw new IOException("Discovery protocol mismatch");
                    }
                    result.putString("anonymous_discovery", "PASS: correlated WSS response");
                }
            }
        } catch (Exception error) {
            // Do not include arbitrary payloads or exception messages in device output.
            result.putString("failure", error.getClass().getSimpleName());
            code = 1;
        } finally {
            // Only this test's isolated files; never the real account directory.
            if (created) {
                new File(directory, "coint-session.v1").delete();
                new File(directory, "coint-session.v1.bak").delete();
                new File(directory, "coint-session.v1.new").delete();
                directory.delete();
            }
        }
        finish(code, result);
    }
}
