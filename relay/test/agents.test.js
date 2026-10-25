// The agent rules on their own (node --test, no Worker runtime): auth parsing, the TV's list, the rate limit,
// the guide, and that the launcher's lists say the same as agents.js.

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { AGENT_COMMANDS, PERMS, RATE, agentNeeds, commandsFor, parseAgentAuth, cleanAgents, takeToken, guide } from "../src/agents.js";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const H = "a".repeat(64);

test("agent auth: Bearer agent:<id>.<key> only", () => {
  assert.deepEqual(parseAgentAuth("Bearer agent:abcdefghijkl.0123456789abcdefghijkl"), { id: "abcdefghijkl", key: "0123456789abcdefghijkl" });
  for (const bad of [undefined, "", "agent:abcdefghijkl.0123456789abcdefghijkl", "Bearer abcdefghijkl.0123456789abcdefghijkl",
    "Bearer agent:short.0123456789abcdefghijkl", "Bearer agent:abcdefghijkl.tooshort", "Bearer agent:abc def ghijkl.0123456789abcdefghijkl",
    "Bearer agent:abcdefghijkl.0123456789abcdefghijkl.extra", "Basic agent:abcdefghijkl.0123456789abcdefghijkl"]) {
    assert.equal(parseAgentAuth(bad), null, String(bad));
  }
});

test("the TV's agent list: hashes checked, booleans strict, capped", () => {
  assert.deepEqual(cleanAgents(null), []);
  assert.deepEqual(cleanAgents([{ h: H, read: true, control: "yes" }, { h: "nothex", read: true }, { h: H.toUpperCase() }, null]),
    [{ h: H, read: true, control: false, adb: false }]);
  assert.deepEqual(cleanAgents([{ h: H, adb: true }]), [{ h: H, read: false, control: false, adb: true }]);
  assert.equal(cleanAgents(Array.from({ length: 30 }, () => ({ h: H }))).length, 10);
});

test("only the listed commands, and never the owner's", () => {
  for (const c of ["set_pin", "set_psk", "sony", "intent", "local", "agents", "anything"]) assert.equal(agentNeeds(c), null, c);
  assert.equal(agentNeeds("status"), "read");
  assert.equal(agentNeeds("apps"), "read");
  assert.equal(agentNeeds("button"), "control");
  assert.equal(agentNeeds("lock"), "control");
  assert.equal(agentNeeds("adb"), "adb");
  for (const c of AGENT_COMMANDS) assert.ok(PERMS[c.perm], c.cmd);
});

test("rate limit: a burst, then one a second", () => {
  const b = {};
  let t = 1_000_000;
  for (let i = 0; i < RATE.burst; i++) assert.equal(takeToken(b, t), 0, "request " + i);
  const wait = takeToken(b, t);
  assert.ok(wait >= 1 && wait <= 2, "wait " + wait);
  t += 1000;
  assert.equal(takeToken(b, t), 0);
  assert.ok(takeToken(b, t) > 0);
  // A minute idle fills it again, never past the burst.
  t += 10 * 60000;
  for (let i = 0; i < RATE.burst; i++) assert.equal(takeToken(b, t), 0);
  assert.ok(takeToken(b, t) > 0);
});

test("the guide documents every command with JSON examples", () => {
  const g = guide("https://relay.example");
  for (const h of ["## Authentication", "## Permissions", "## Commands", "## Errors", "## Rate limits", "Authorization: Bearer agent:<TV id>.<key>"]) {
    assert.ok(g.includes(h), h);
  }
  for (const c of AGENT_COMMANDS) {
    assert.ok(g.includes("### " + c.cmd + "\n"), c.cmd);
    assert.ok(g.includes(JSON.stringify(c.req)), c.cmd + " request");
    assert.ok(g.includes(JSON.stringify(c.res)), c.cmd + " response");
  }
  for (const code of ["400", "401", "403", "413", "429", "503"]) assert.ok(g.includes("| " + code + " |"), code);
  assert.ok(g.includes(RATE.burst + " requests at once and " + RATE.perMinute + " a minute"));
  assert.ok(g.includes("https://relay.example/api/cmd"));
  assert.ok(!/set_pin|set_psk|intent|"sony"/.test(g), "no owner-only command in the guide");
  assert.ok(g.includes("| `adb` | off") && g.includes(PERMS.adb.warning), "adb, its default and its warning");
});

test("the launcher's Agents.READ and Agents.CONTROL match agents.js", () => {
  const java = readFileSync(join(ROOT, "launcher/app/src/main/java/com/gathin/tvhome/Agents.java"), "utf8");
  const list = (name) => {
    const m = new RegExp("static final String\\[\\] " + name + " = \\{([^}]*)\\}").exec(java);
    assert.ok(m, name);
    return [...m[1].matchAll(/"([a-z_]+)"/g)].map((x) => x[1]).sort();
  };
  assert.deepEqual(list("READ"), commandsFor("read").sort());
  assert.deepEqual(list("CONTROL"), commandsFor("control").sort());
  assert.deepEqual(list("ADB"), commandsFor("adb").sort());
});
