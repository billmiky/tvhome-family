package com.gathin.tvhome;

import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AgentsTest {
    /** The launcher's preferences, as a map. */
    private static final class MapStore implements Agents.Store {
        final Map<String, String> m = new HashMap<>();

        @Override public String get(String key) {
            return m.get(key);
        }

        @Override public void put(String key, String value) {
            m.put(key, value);
        }
    }

    private static final long NOW = 1_761_370_000_000L;

    @Test public void readByDefaultControlWhenGranted() throws Exception {
        Agents ag = new Agents(new MapStore());
        Agents.Agent a = ag.create("Claude", false, NOW).agent;
        assertTrue(a.read);
        assertFalse(a.control);
        for (String c : Agents.READ) assertNull(c, Agents.refusal(a, c));
        for (String c : Agents.CONTROL) assertNotNull(c, Agents.refusal(a, c));
        ag.set(a.id, null, true);
        for (String c : Agents.CONTROL) assertNull(c, Agents.refusal(a, c));
        // Read off: status-like commands are refused, control still works.
        ag.set(a.id, false, null);
        assertNotNull(Agents.refusal(a, "info"));
        assertNull(Agents.refusal(a, "button"));
    }

    @Test public void agentsNeverReachTheOwnersCommands() throws Exception {
        Agents ag = new Agents(new MapStore());
        Agents.Agent a = ag.create("Claude", true, NOW).agent;
        for (String c : new String[] {"set_pin", "set_psk", "adb", "sony", "intent", "local", "agents", "nope"}) {
            assertNotNull(c, Agents.refusal(a, c));
            assertNull(c, Agents.needs(c));
        }
        // Every command an agent may use is one the TV runs (RemoteRelay can't load on the JVM: read its source).
        String relay = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("src/main/java/com/gathin/tvhome/RemoteRelay.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        for (String c : Agents.READ) assertTrue(c, relay.contains("case \"" + c + "\":"));
        for (String c : Agents.CONTROL) assertTrue(c, relay.contains("case \"" + c + "\":"));
    }

    @Test public void revokedKeyIsRefusedAtOnce() throws Exception {
        MapStore store = new MapStore();
        Agents ag = new Agents(store);
        Agents.Created c = ag.create("Claude", true, NOW);
        String hash = Agents.sha256(c.key);
        assertEquals(c.agent, ag.byHash(hash));
        assertTrue(ag.revoke(c.agent.id));
        assertNull(ag.byHash(hash));
        assertNotNull(Agents.refusal(ag.byHash(hash), "ping"));
        // The relay's list no longer has it, and neither does a fresh load from the preferences.
        assertFalse(ag.forRelay().toString().contains(hash));
        assertNull(new Agents(store).byHash(hash));
        assertFalse(ag.revoke(c.agent.id));
    }

    @Test public void keysAreRandomAndOnlyHashesAreKept() throws Exception {
        MapStore store = new MapStore();
        Agents ag = new Agents(store);
        Agents.Created a = ag.create("A", false, NOW), b = ag.create("B", false, NOW);
        assertFalse(a.key.equals(b.key));
        assertTrue(a.key.matches("[A-Za-z0-9_-]{32}"));
        for (String v : store.m.values()) {
            assertFalse(v.contains(a.key));
            assertFalse(v.contains(b.key));
        }
        assertEquals("agent:TVID123456789.k", Agents.token("TVID123456789", "k"));
    }

    @Test public void noSecretsInAnswers() throws Exception {
        Agents ag = new Agents(new MapStore());
        Agents.Created c = ag.create("Claude", false, NOW);
        String hash = Agents.sha256(c.key);
        // The phone page's list: names and permissions, no key and no hash.
        String phone = ag.forPhone().toString();
        assertFalse(phone.contains(hash));
        assertFalse(phone.contains(c.key));
        assertTrue(phone.contains("Claude"));
        // caps and info for an agent: only what it may use, never the owner's commands.
        JSONObject out = new JSONObject().put("data", new JSONArray(java.util.Arrays.asList("ping", "caps", "set_pin", "set_psk", "adb", "sony", "intent", "local", "agents", "button", "apps")));
        Agents.forAgent(c.agent, "caps", out);
        String caps = out.get("data").toString();
        for (String s : new String[] {"set_pin", "set_psk", "adb", "sony", "intent", "local", "agents", "button"}) {
            assertFalse(s, caps.contains("\"" + s + "\""));
        }
        assertTrue(caps.contains("\"apps\""));
        JSONObject info = new JSONObject().put("data", new JSONObject().put("model", "X").put("caps", new JSONArray().put("adb")));
        Agents.forAgent(c.agent, "info", info);
        assertFalse(info.toString().contains("adb"));
        // The log never holds typed text.
        ag.log(c.agent, hash, "type", Agents.summary("type", "", new JSONObject().put("text", "hunter2").put("enter", true)), true, "Sent.", NOW);
        ag.log(c.agent, hash, "banner", Agents.summary("banner", "secret plans", new JSONObject()), true, "", NOW);
        String log = ag.log(10).toString();
        assertFalse(log.contains("hunter2"));
        assertFalse(log.contains("secret plans"));
        assertFalse(log.contains(hash));
        assertTrue(log.contains("7 characters, Enter"));
    }

    @Test public void logIsCappedNewestFirst() throws Exception {
        MapStore store = new MapStore();
        Agents ag = new Agents(store);
        Agents.Agent a = ag.create("Claude", true, NOW).agent;
        for (int i = 0; i < Agents.LOG_MAX + 25; i++) ag.log(a, a.hash, "button", "n" + i, true, "", NOW + i);
        JSONArray all = ag.log(1000);
        assertEquals(Agents.LOG_MAX, all.length());
        assertEquals("n" + (Agents.LOG_MAX + 24), all.getJSONObject(0).getString("what"));
        assertEquals("n25", all.getJSONObject(Agents.LOG_MAX - 1).getString("what"));
        assertEquals(Agents.LOG_MAX, new Agents(store).log(1000).length());
        // A revoked key's attempts are logged by a short hash, not a name it no longer has.
        ag.log(null, "0123456789abcdef" + "0".repeat(48), "button", "Home", false, "revoked", NOW);
        assertEquals("revoked key 01234567", ag.log(1).getJSONObject(0).getString("name"));
    }

    @Test public void permissionsAndNamesSurviveARestart() throws Exception {
        MapStore store = new MapStore();
        Agents ag = new Agents(store);
        Agents.Agent a = ag.create("  Claude\n Code  ", false, NOW).agent;
        ag.set(a.id, null, true);
        ag.used(a, NOW + 5);
        Agents again = new Agents(store);
        Agents.Agent b = again.byId(a.id);
        assertEquals("Claude Code", b.name);
        assertTrue(b.read && b.control);
        assertEquals(NOW + 5, b.lastUsed);
        assertEquals(new JSONArray().put(new JSONObject().put("h", a.hash).put("read", true).put("control", true)).toString(),
                again.forRelay().toString());
    }

    @Test public void atMostMaxAgentsAndHashesChecked() throws Exception {
        Agents ag = new Agents(new MapStore());
        for (int i = 0; i < Agents.MAX; i++) ag.create("A" + i, false, NOW);
        try {
            ag.create("one more", false, NOW);
            fail();
        } catch (IllegalStateException expected) {
            // Full.
        }
        Agents fresh = new Agents(new MapStore());
        try {
            fresh.add("bad", "not a hash", false, NOW);
            fail();
        } catch (IllegalArgumentException expected) {
            // Rejected.
        }
        String h = Agents.sha256("a key the phone page made, 22+ chars");
        Agents.Agent a = fresh.add("", h, false, NOW);
        assertEquals("Agent 1", a.name);
        try {
            fresh.add("again", h, false, NOW);
            fail();
        } catch (IllegalArgumentException expected) {
            // The same key twice.
        }
    }
}
