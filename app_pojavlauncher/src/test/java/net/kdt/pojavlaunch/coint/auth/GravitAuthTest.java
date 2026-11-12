package net.kdt.pojavlaunch.coint.auth;

import com.google.gson.*;
import org.junit.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.Assert.*;

public class GravitAuthTest {
    private static final String UUID_A = "11111111-1111-1111-1111-111111111111";
    private static final String UUID_B = "22222222-2222-2222-2222-222222222222";
    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private static final class Fake implements GravitAuth.Channel {
        final Queue<JsonObject> replies = new ArrayDeque<>();
        final List<JsonObject> requests = new ArrayList<>();
        Fake(String... values) { for (String value : values) replies.add(json(value)); }
        @Override public JsonObject request(JsonObject request) {
            requests.add(request.deepCopy());
            JsonObject reply = replies.remove();
            if (!reply.has("requestUUID")) reply.add("requestUUID", request.get("requestUUID"));
            return reply;
        }
        @Override public void close() { }
    }
    private static String available() {
        return "{'type':'getAvailabilityAuth','list':[{'name':'std','visible':true,'details':[{'type':'password'}]}]}";
    }
    private static String auth() {
        return "{'type':'auth','playerProfile':{'uuid':'"+UUID_A+"','username':'Crazer'},'accessToken':'GAME-DO-NOT-USE','oauth':{'accessToken':'api-token','refreshToken':'refresh-token','expire':3600}}";
    }
    private static String restored(String uuid) {
        return "{'type':'restore','userInfo':{'playerProfile':{'uuid':'"+uuid+"','username':'Crazer'}}}";
    }
    private static GravitAuth.Session session(long expires) {
        GravitAuth.Session session = new GravitAuth.Session();
        session.username = "Crazer"; session.uuid = UUID_A; session.authId = "std";
        session.accessToken = "old-api"; session.refreshToken = "old-refresh"; session.expiresAt = expires;
        return session;
    }

    @Test public void usesOwnApiAccountAndNeverConfusesGameToken() throws Exception {
        Fake fake = new Fake(available(), auth());
        GravitAuth.Session result = new GravitAuth(fake).login(" Crazer ", " secret ", 1000);
        assertEquals("api-token", result.accessToken);
        assertEquals(3_601_000, result.expiresAt);
        JsonObject request = fake.requests.get(1);
        assertEquals("API", request.get("authType").getAsString());
        assertEquals("std", request.get("auth_id").getAsString());
        assertEquals("plain", request.getAsJsonObject("password").get("type").getAsString());
        assertEquals(" secret ", request.getAsJsonObject("password").get("password").getAsString());
    }
    @Test public void neverSendsPasswordToUnsupportedMultiFactorProvider() throws Exception {
        Fake fake = new Fake("{'type':'getAvailabilityAuth','list':[{'name':'std','visible':true,'details':[{'type':'password'},{'type':'totp'}]}]}");
        GravitAuth.Failure failure = assertThrows(GravitAuth.Failure.class, () -> new GravitAuth(fake).login("Crazer", "secret", 0));
        assertEquals("provider", failure.code);
        assertEquals(1, fake.requests.size());
    }
    @Test public void prefersStdEvenWhenOtherProvidersComeFirst() throws Exception {
        Fake fake = new Fake("{'type':'getAvailabilityAuth','list':[{'name':'a','visible':true,'details':[{'type':'password'}]},{'name':'b','visible':true,'details':[{'type':'password'}]},{'name':'std','visible':true,'details':[{'type':'password'}]}]}", auth());
        assertEquals("std", new GravitAuth(fake).login("Crazer", "secret", 0).authId);
    }
    @Test public void rejectsWrongCorrelationId() throws Exception {
        Fake fake = new Fake("{'type':'getAvailabilityAuth','requestUUID':'wrong'}");
        assertEquals("protocol", assertThrows(GravitAuth.Failure.class, () -> new GravitAuth(fake).login("Crazer", "secret", 0)).code);
    }
    @Test public void rejectsWrongResponseType() throws Exception {
        Fake fake = new Fake("{'type':'auth'}");
        assertEquals("protocol", assertThrows(GravitAuth.Failure.class, () -> new GravitAuth(fake).login("Crazer", "secret", 0)).code);
    }
    @Test public void doesNotExposeArbitraryServerErrorText() throws Exception {
        Fake fake = new Fake(available(), "{'type':'error','error':'secret-token-should-not-escape'}");
        IOException error = assertThrows(IOException.class, () -> new GravitAuth(fake).login("Crazer", "secret", 0));
        assertFalse(error.toString().contains("secret-token"));
    }
    @Test public void mapsWrongPasswordWithoutLosingErrorCode() throws Exception {
        Fake fake = new Fake(available(), "{'type':'error','error':'auth.wrongpassword'}");
        assertEquals("auth.wrongpassword", assertThrows(GravitAuth.Failure.class, () -> new GravitAuth(fake).login("Crazer", "secret", 0)).code);
    }
    @Test public void rejectsMissingOAuthInsteadOfSavingGameToken() throws Exception {
        Fake fake = new Fake(available(), "{'type':'auth','playerProfile':{'uuid':'"+UUID_A+"','username':'Crazer'},'accessToken':'game-only'}");
        assertEquals("session", assertThrows(GravitAuth.Failure.class, () -> new GravitAuth(fake).login("Crazer", "secret", 0)).code);
    }
    @Test public void checkpointsRotatedRefreshBeforeRestoreFails() throws Exception {
        Fake fake = new Fake("{'type':'refreshToken','oauth':{'accessToken':'new-api','refreshToken':'new-refresh','expire':600}}", "{'type':'error','error':'auth.invalidtoken'}");
        List<GravitAuth.Session> saved = new ArrayList<>();
        assertThrows(GravitAuth.Failure.class, () -> new GravitAuth(fake).restore(session(10), 1000, saved::add));
        assertEquals("new-refresh", saved.get(0).refreshToken);
        assertEquals("new-api", fake.requests.get(1).get("accessToken").getAsString());
        assertEquals("old-refresh", fake.requests.get(0).get("refreshToken").getAsString());
    }
    @Test public void restoresWithoutRefreshingUnexpiredToken() throws Exception {
        Fake fake = new Fake(restored(UUID_A));
        GravitAuth.Session saved = session(100_000);
        assertEquals(UUID_A, new GravitAuth(fake).restore(saved, 1000, s -> fail()).uuid);
        assertEquals(1, fake.requests.size());
        assertEquals("restore", fake.requests.get(0).get("type").getAsString());
    }
    @Test public void refreshesOnceWhenServerReportsExpiry() throws Exception {
        Fake fake = new Fake("{'type':'error','error':'auth.expiretoken'}", "{'type':'refreshToken','oauth':{'accessToken':'new-api','refreshToken':'new-refresh','expire':600}}", restored(UUID_A));
        GravitAuth.Session saved = session(100_000);
        assertEquals("new-api", new GravitAuth(fake).restore(saved, 1000, s -> {}).accessToken);
        assertEquals("old-api", saved.accessToken);
        assertEquals(3, fake.requests.size());
    }
    @Test public void rejectsRestoredDifferentIdentity() throws Exception {
        Fake fake = new Fake(restored(UUID_B));
        assertEquals("protocol", assertThrows(GravitAuth.Failure.class, () -> new GravitAuth(fake).restore(session(100_000), 1000, s -> {})).code);
    }
    @Test public void rejectsInvalidExpiryWithoutOverflow() throws Exception {
        JsonObject bad = json(auth());
        bad.getAsJsonObject("oauth").addProperty("expire", Long.MAX_VALUE);
        Fake fake = new Fake(available(), bad.toString());
        assertEquals("protocol", assertThrows(GravitAuth.Failure.class, () -> new GravitAuth(fake).login("Crazer", "secret", 1000)).code);
    }
}
