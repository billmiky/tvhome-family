package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * AI agents that drive the TV through the relay (RemoteRelay), each with its own key. The TV makes the key and
 * shows it once (or the phone page makes it and sends only its SHA-256); only the hash is kept, here and on the
 * relay, which gets every agent's hash and permissions in the hello and again on each change, so a revoked key
 * stops at once. The relay marks an agent's command with the hash; check() decides it here.
 *
 * Permissions: read (status, info, caps, apps, buttons, screen time), on by default; control (remote keys, type,
 * launch, power, message, lock) when the owner grants it; adb (the existing adb command: Wireless debugging on, off
 * and its state, never a shell) when the owner grants that too. Granting control or adb asks for the TV's PIN, on
 * the TV and from the phone page (grants()). Nothing else ever: no PIN, IP control key, Sony
 * calls, intents, the phone page's API or the agent list. The lists match relay/src/agents.js (a test checks).
 * Every agent command goes into a capped log (who, when, what, result) that Settings › AI agents shows.
 *
 * Plain Java over a key-value Store, so the JVM tests run it without Android.
 */
final class Agents {
    static final String[] READ = {"ping", "info", "caps", "apps", "buttons", "screen_time"};
    static final String[] CONTROL = {
            "button", "longpress", "global", "type", "launch", "screen_off", "screen_on", "standby", "wake",
            "banner", "banner_clear", "lock", "unlock", "lock_timer", "lock_timer_cancel",
    };
    /** Wireless debugging: anyone on the home network who is already paired can then connect. */
    static final String[] ADB = {"adb"};
    static final int MAX = 10;
    static final int LOG_MAX = 100;
    static final int MAX_NAME = 40;
    /** Last used is saved at most this often for one agent: a status check every few seconds needn't write. */
    private static final long USED_SAVE_MS = 60_000;

    private static final String K_AGENTS = "remote_agents";
    private static final String K_LOG = "remote_agent_log";
    private static final Set<String> READ_SET = new HashSet<>(Arrays.asList(READ));
    private static final Set<String> CONTROL_SET = new HashSet<>(Arrays.asList(CONTROL));
    private static final Set<String> ADB_SET = new HashSet<>(Arrays.asList(ADB));
    private static final SecureRandom RNG = new SecureRandom();

    /** Where the lists live: the launcher's SharedPreferences on the TV, a map in the tests. */
    interface Store {
        String get(String key);

        void put(String key, String value);
    }

    static final class Agent {
        final String id;
        String name;
        final String hash;
        boolean read;
        boolean control;
        boolean adb;
        final long created;
        long lastUsed;

        Agent(String id, String name, String hash, boolean read, boolean control, long created, long lastUsed) {
            this.id = id;
            this.name = name;
            this.hash = hash;
            this.read = read;
            this.control = control;
            this.created = created;
            this.lastUsed = lastUsed;
        }
    }

    /** A new agent and its key, which exists only here: shown once, never stored. */
    static final class Created {
        final Agent agent;
        final String key;

        Created(Agent agent, String key) {
            this.agent = agent;
            this.key = key;
        }
    }

    private final Store store;
    private final List<Agent> agents = new ArrayList<>();
    private final JSONArray log;

    Agents(Store store) {
        this.store = store;
        try {
            JSONArray a = new JSONArray(orEmpty(store.get(K_AGENTS), "[]"));
            for (int i = 0; i < a.length() && agents.size() < MAX; i++) {
                JSONObject o = a.getJSONObject(i);
                String h = o.optString("h");
                if (!h.matches("[0-9a-f]{64}")) continue;
                agents.add(new Agent(o.optString("id"), o.optString("name"), h, o.optBoolean("read", true),
                        o.optBoolean("control"), o.optLong("created"), o.optLong("used")));
                agents.get(agents.size() - 1).adb = o.optBoolean("adb");
            }
        } catch (Exception ignored) {
            // A broken list: start empty (every key stops working, which is the safe way to fail).
        }
        JSONArray l;
        try {
            l = new JSONArray(orEmpty(store.get(K_LOG), "[]"));
        } catch (Exception e) {
            l = new JSONArray();
        }
        log = l;
    }

    // ---- the list ------------------------------------------------------------------------------------

    synchronized List<Agent> list() {
        return new ArrayList<>(agents);
    }

    synchronized Agent byId(String id) {
        for (Agent a : agents) if (a.id.equals(id)) return a;
        return null;
    }

    synchronized Agent byHash(String hash) {
        if (hash == null) return null;
        for (Agent a : agents) if (a.hash.equals(hash)) return a;
        return null;
    }

    /** A new agent with a key made here (Settings on the TV): read only until control is granted. */
    synchronized Created create(String name, boolean control, long now) throws Exception {
        String key = random(24);
        return new Created(add(name, sha256(key), control, now), key);
    }

    /** A new agent whose key the phone page made: only its hash comes to the TV. Throws when full or a bad hash. */
    synchronized Agent add(String name, String hash, boolean control, long now) throws Exception {
        if (agents.size() >= MAX) throw new IllegalStateException("At most " + MAX + " agents. Revoke one first.");
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("bad key hash");
        if (byHash(hash) != null) throw new IllegalArgumentException("That key is already an agent.");
        String n = cleanName(name);
        Agent a = new Agent(random(6), n.isEmpty() ? "Agent " + (agents.size() + 1) : n, hash, true, control, now, 0);
        agents.add(a);
        save();
        return a;
    }

    synchronized boolean revoke(String id) {
        Agent a = byId(id);
        if (a == null) return false;
        agents.remove(a);
        save();
        return true;
    }

    /** Switches read, control or adb (null leaves it). */
    synchronized boolean set(String id, Boolean read, Boolean control, Boolean adb) {
        Agent a = byId(id);
        if (a == null) return false;
        if (read != null) a.read = read;
        if (control != null) a.control = control;
        if (adb != null) a.adb = adb;
        save();
        return true;
    }

    // ---- deciding --------------------------------------------------------------------------------------

    /** True when the change gives agent a control or adb it doesn't have: the owner's PIN first, if there is one. */
    static boolean grants(Agent a, Boolean control, Boolean adb) {
        return a != null && (Boolean.TRUE.equals(control) && !a.control || Boolean.TRUE.equals(adb) && !a.adb);
    }

    /** "read", "control", "adb", or null: agents may never send it. */
    static String needs(String cmd) {
        if (READ_SET.contains(cmd)) return "read";
        if (CONTROL_SET.contains(cmd)) return "control";
        if (ADB_SET.contains(cmd)) return "adb";
        return null;
    }

    /** Null when agent a may run cmd now, else the refusal for the answer (a is null for a revoked key). */
    static String refusal(Agent a, String cmd) {
        if (a == null) return "This agent key was revoked on the TV.";
        String need = needs(cmd);
        if (need == null) return "Agents may not use \"" + cmd + "\".";
        if ("read".equals(need) && !a.read) return "This agent may not read from the TV (no read permission).";
        if ("control".equals(need) && !a.control) return "This agent may not control the TV. The owner can grant control in Settings › AI agents.";
        if ("adb".equals(need) && !a.adb) return "This agent may not switch Wireless debugging. The owner can grant ADB in Settings › AI agents.";
        return null;
    }

    /** The commands agent a may use: caps and info answer with these instead of every command the TV knows. */
    static JSONArray caps(Agent a) {
        JSONArray c = new JSONArray();
        if (a.read) for (String s : READ) c.put(s);
        if (a.control) for (String s : CONTROL) c.put(s);
        if (a.adb) for (String s : ADB) c.put(s);
        return c;
    }

    /** An answer to agent a: caps (and info's caps) narrowed to what it may use. Changes out in place. */
    static void forAgent(Agent a, String cmd, JSONObject out) throws Exception {
        if ("caps".equals(cmd)) out.put("data", caps(a));
        else if ("info".equals(cmd) && out.optJSONObject("data") != null) out.getJSONObject("data").put("caps", caps(a));
    }

    // ---- use and the log -------------------------------------------------------------------------------

    /** Agent a was heard from: last used, saved once a minute at most. */
    synchronized void used(Agent a, long now) {
        boolean save = now - a.lastUsed >= USED_SAVE_MS;
        a.lastUsed = now;
        if (save) save();
    }

    /** One line in the log: who, when, what, the result. The oldest go past LOG_MAX. */
    synchronized void log(Agent a, String hash, String cmd, String what, boolean ok, String msg, long now) {
        try {
            JSONObject e = new JSONObject().put("t", now).put("cmd", cmd).put("ok", ok);
            if (a != null) e.put("id", a.id).put("name", a.name);
            else e.put("name", "revoked key " + (hash != null && hash.length() >= 8 ? hash.substring(0, 8) : "?"));
            if (what != null && !what.isEmpty()) e.put("what", what);
            if (msg != null && !msg.isEmpty()) e.put("msg", msg.length() > 120 ? msg.substring(0, 120) + "…" : msg);
            log.put(e);
            while (log.length() > LOG_MAX) log.remove(0);
            store.put(K_LOG, log.toString());
        } catch (Exception ignored) {
            // Not logged: never in the way of the command.
        }
    }

    /** The log, newest first, at most n. */
    synchronized JSONArray log(int n) {
        JSONArray out = new JSONArray();
        for (int i = log.length() - 1; i >= 0 && out.length() < n; i--) out.put(log.opt(i));
        return out;
    }

    /**
     * What a command was, for the log: the arguments that say what happened, never typed text (it may be a
     * password), and a message only by its length.
     */
    static String summary(String cmd, String text, JSONObject args) {
        switch (cmd) {
            case "button": {
                JSONArray names = args.optJSONArray("names");
                if (names == null) return args.optString("name", text);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < names.length(); i++) sb.append(i > 0 ? " " : "").append(names.optString(i));
                return sb.toString();
            }
            case "global":
                return args.optString("action", text);
            case "launch":
                return args.optString("package", text);
            case "type":
                return args.optString("text", text).length() + " characters" + (args.optBoolean("enter") ? ", Enter" : "");
            case "banner":
                return text.length() + " characters";
            case "lock_timer":
                return text + " min";
            case "screen_time":
                return args.optString("range", text.isEmpty() ? "today" : text);
            case "adb":
                return args.optString("action", text.isEmpty() ? "status" : text);
            default:
                return "";
        }
    }

    // ---- the lists out -----------------------------------------------------------------------------------

    /** For the relay: [{h, read, control, adb}]. Hashes only, as for phone keys. */
    synchronized JSONArray forRelay() throws Exception {
        JSONArray a = new JSONArray();
        for (Agent x : agents) a.put(new JSONObject().put("h", x.hash).put("read", x.read).put("control", x.control).put("adb", x.adb));
        return a;
    }

    /** For the phone page: no hash (the page can't use one, and it is half of nothing a guest should see). */
    synchronized JSONArray forPhone() throws Exception {
        JSONArray a = new JSONArray();
        for (Agent x : agents) {
            a.put(new JSONObject().put("id", x.id).put("name", x.name).put("read", x.read).put("control", x.control).put("adb", x.adb)
                    .put("created", x.created).put("lastUsed", x.lastUsed));
        }
        return a;
    }

    /** What an agent pastes: Bearer agent:<TV id>.<key>. */
    static String token(String tvId, String key) {
        return "agent:" + tvId + "." + key;
    }

    static String cleanName(String name) {
        String n = name == null ? "" : name.replaceAll("[\\p{Cntrl}]", " ").trim().replaceAll("\\s+", " ");
        return n.length() > MAX_NAME ? n.substring(0, MAX_NAME) : n;
    }

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Agent x : agents) {
                a.put(new JSONObject().put("id", x.id).put("name", x.name).put("h", x.hash).put("read", x.read)
                        .put("control", x.control).put("adb", x.adb).put("created", x.created).put("used", x.lastUsed));
            }
            store.put(K_AGENTS, a.toString());
        } catch (Exception ignored) {
            // org.json never fails for these types.
        }
    }

    private static String orEmpty(String s, String empty) {
        return s == null || s.isEmpty() ? empty : s;
    }

    /** n random bytes as base64url without padding. */
    static String random(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String sha256(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(64);
        for (byte b : d) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }
}
