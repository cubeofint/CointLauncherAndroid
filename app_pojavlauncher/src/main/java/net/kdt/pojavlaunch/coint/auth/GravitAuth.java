package net.kdt.pojavlaunch.coint.auth;

import com.google.gson.*;
import java.io.IOException;
import java.util.UUID;

/** Independent wire model for the public GravitLauncher 5.7.9 API. */
public final class GravitAuth {
    public static final String ENDPOINT = "wss://launcher.mawlee.org/api";

    public interface Channel extends AutoCloseable {
        JsonObject request(JsonObject request) throws IOException;
        void close();
    }

    public static final class Failure extends IOException {
        public final String code;
        public Failure(String code) { super("COINT auth: " + code); this.code = code; }
    }

    public static final class Session {
        public String authId, username, uuid, accessToken, refreshToken;
        public long expiresAt;
    }

    private final Channel channel;
    public GravitAuth(Channel channel) { this.channel = channel; }

    public Session login(String login, String password, long now) throws IOException {
        if (login.trim().isEmpty() || password.isEmpty()) throw new Failure("credentials");
        JsonObject available = call("getAvailabilityAuth", new JsonObject());
        String authId = passwordProvider(available);
        JsonObject request = new JsonObject();
        request.addProperty("login", login.trim());
        request.addProperty("auth_id", authId);
        // API login does not impersonate a signed desktop launcher or request a game token.
        request.addProperty("authType", "API");
        request.addProperty("getSession", false);
        JsonObject credential = new JsonObject();
        credential.addProperty("type", "plain");
        credential.addProperty("password", password);
        request.add("password", credential);
        JsonObject response;
        try { response = call("auth", request); }
        finally { credential.remove("password"); }
        Session session = new Session();
        session.authId = authId;
        profile(session, response);
        tokens(session, response, now);
        return session;
    }

    /** Persist rotated refresh tokens before the subsequent restore can fail. */
    public Session restore(Session saved, long now, Checkpoint checkpoint) throws IOException {
        Session session = new Gson().fromJson(new Gson().toJson(saved), Session.class);
        validate(session);
        if (session.expiresAt != 0 && session.expiresAt <= now + 30_000L) {
            refresh(session, now, checkpoint);
        }
        JsonObject response;
        try { response = restoreRequest(session); }
        catch (Failure failure) {
            if (!failure.code.equals("auth.expiretoken")) throw failure;
            refresh(session, now, checkpoint);
            response = restoreRequest(session);
        }
        if (!response.has("userInfo") || !response.get("userInfo").isJsonObject()) throw new Failure("protocol");
        String expectedUuid = session.uuid;
        profile(session, response.getAsJsonObject("userInfo"));
        if (!expectedUuid.equals(session.uuid)) throw new Failure("protocol");
        return session;
    }

    public interface Checkpoint { void save(Session session) throws IOException; }

    private JsonObject restoreRequest(Session session) throws IOException {
        JsonObject request = new JsonObject();
        request.addProperty("authId", session.authId);
        request.addProperty("accessToken", session.accessToken);
        request.addProperty("needUserInfo", true);
        request.add("extended", new JsonObject());
        return call("restore", request);
    }

    private void refresh(Session session, long now, Checkpoint checkpoint) throws IOException {
        JsonObject request = new JsonObject();
        request.addProperty("authId", session.authId);
        request.addProperty("refreshToken", session.refreshToken);
        tokens(session, call("refreshToken", request), now);
        checkpoint.save(session);
    }

    private JsonObject call(String type, JsonObject request) throws IOException {
        String id = UUID.randomUUID().toString();
        request.addProperty("type", type);
        request.addProperty("requestUUID", id);
        JsonObject response = channel.request(request);
        if (!id.equals(string(response, "requestUUID"))) throw new Failure("protocol");
        String responseType = string(response, "type");
        if (responseType.equals("error")) {
            // Never expose arbitrary server text: it may contain credentials or internal data.
            String code = string(response, "error");
            switch (code) {
                case "auth.wrongpassword": case "auth.usernotfound": case "auth.userblocked":
                case "auth.require2fa": case "auth.expiretoken": case "auth.invalidtoken":
                    throw new Failure(code);
                default: throw new Failure("rejected");
            }
        }
        if (!type.equals(responseType)) throw new Failure("protocol");
        return response;
    }

    private static String passwordProvider(JsonObject response) throws Failure {
        if (!response.has("list") || !response.get("list").isJsonArray()) throw new Failure("protocol");
        String selected = null;
        int count = 0;
        for (JsonElement element : response.getAsJsonArray("list")) {
            if (!element.isJsonObject()) continue;
            JsonObject provider = element.getAsJsonObject();
            if (!provider.has("visible") || !provider.get("visible").isJsonPrimitive()
                    || !provider.get("visible").getAsJsonPrimitive().isBoolean()
                    || !provider.get("visible").getAsBoolean()) continue;
            if (!provider.has("details") || !provider.get("details").isJsonArray()) continue;
            JsonArray details = provider.getAsJsonArray("details");
            // Other factors must be implemented explicitly, never silently skipped.
            if (details.size() != 1 || !details.get(0).isJsonObject()
                    || !"password".equals(string(details.get(0).getAsJsonObject(), "type"))) continue;
            String name = required(provider, "name");
            if ("std".equals(name)) return name;
            count++;
            selected = name;
        }
        if (selected == null || count != 1) throw new Failure("provider");
        return selected;
    }

    private static void profile(Session session, JsonObject response) throws Failure {
        if (!response.has("playerProfile") || !response.get("playerProfile").isJsonObject()) throw new Failure("protocol");
        JsonObject profile = response.getAsJsonObject("playerProfile");
        String username = required(profile, "username");
        String uuid = required(profile, "uuid");
        if (!username.matches("[A-Za-z0-9_]{1,16}")
                || !uuid.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) throw new Failure("protocol");
        session.username = username;
        session.uuid = UUID.fromString(uuid).toString();
    }

    private static void tokens(Session session, JsonObject response, long now) throws Failure {
        if (!response.has("oauth") || !response.get("oauth").isJsonObject()) throw new Failure("session");
        JsonObject oauth = response.getAsJsonObject("oauth");
        session.accessToken = required(oauth, "accessToken");
        session.refreshToken = required(oauth, "refreshToken");
        try {
            JsonElement value = oauth.get("expire");
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new ArithmeticException();
            long seconds = value.getAsBigDecimal().longValueExact();
            if (seconds < 0) throw new ArithmeticException();
            session.expiresAt = seconds == 0 ? 0 : Math.addExact(now, Math.multiplyExact(seconds, 1000));
        } catch (RuntimeException invalid) { throw new Failure("protocol"); }
    }

    public static void validate(Session session) throws Failure {
        if (session == null || session.expiresAt < 0) throw new Failure("session");
        JsonObject object = new Gson().toJsonTree(session).getAsJsonObject();
        for (String key : new String[]{"authId", "username", "uuid", "accessToken", "refreshToken"}) required(object, key);
        JsonObject response = new JsonObject();
        JsonObject profile = new JsonObject();
        profile.addProperty("username", session.username);
        profile.addProperty("uuid", session.uuid);
        response.add("playerProfile", profile);
        profile(session, response);
    }

    private static String required(JsonObject object, String key) throws Failure {
        String value = string(object, key);
        if (value.isEmpty()) throw new Failure("protocol");
        return value;
    }

    static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : "";
    }
}
