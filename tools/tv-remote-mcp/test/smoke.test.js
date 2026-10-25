// Smoke test: the MCP server over stdio against a local relay (wrangler dev, from relay/) and a fake TV.
// Needs relay/node_modules (cd relay && npm install). Run from relay/: npm run test:e2e.

import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createInterface } from "node:readline";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { startRelay, fakeTv, randomKey } from "../../../relay/test/dev.js";

const SERVER = join(dirname(fileURLToPath(import.meta.url)), "..", "server.js");

/** The server on stdio: call(method, params) resolves with the result (or rejects with the error). */
function mcp(env) {
  const proc = spawn(process.execPath, [SERVER], { env: { ...process.env, ...env }, stdio: ["pipe", "pipe", "inherit"] });
  const waiting = new Map();
  let n = 0;
  createInterface({ input: proc.stdout }).on("line", (line) => {
    const m = JSON.parse(line);
    const w = waiting.get(m.id);
    if (!w) return;
    waiting.delete(m.id);
    if (m.error) w.reject(Object.assign(new Error(m.error.message), { code: m.error.code }));
    else w.resolve(m.result);
  });
  const call = (method, params) =>
    new Promise((resolve, reject) => {
      const id = ++n;
      waiting.set(id, { resolve, reject });
      proc.stdin.write(JSON.stringify({ jsonrpc: "2.0", id, method, params }) + "\n");
    });
  const notify = (method) => proc.stdin.write(JSON.stringify({ jsonrpc: "2.0", method }) + "\n");
  return { call, notify, stop: () => proc.kill() };
}

let relay, tv, owner, reader, admin;
const agent = { key: randomKey(), read: true, control: true };
// A key of its own for the adb tools: the first test's calls use up most of the other key's burst.
const adbAgent = { key: randomKey(), read: false, control: false, adb: true };
const readOnly = { key: randomKey(), read: true, control: false };
before(async () => {
  relay = await startRelay();
  tv = await fakeTv(relay.url, { agents: [agent, readOnly, adbAgent] });
  owner = mcp({ RELAY_URL: relay.url + "/", TV_AGENT_KEY: tv.token(agent) });
  admin = mcp({ RELAY_URL: relay.url, TV_AGENT_KEY: tv.token(adbAgent) });
  reader = mcp({ RELAY_URL: relay.url, TV_AGENT_KEY: "Bearer " + tv.token(readOnly) });
});
after(async () => {
  owner && owner.stop();
  reader && reader.stop();
  admin && admin.stop();
  tv && tv.close();
  relay && (await relay.stop());
});

const text = (r) => r.content.map((c) => c.text).join("\n");

test("initialize and the tool list", async () => {
  const init = await owner.call("initialize", { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "test", version: "0" } });
  assert.equal(init.protocolVersion, "2025-06-18");
  assert.ok(init.capabilities.tools);
  owner.notify("notifications/initialized");
  const { tools } = await owner.call("tools/list", {});
  const names = tools.map((t) => t.name).sort();
  assert.deepEqual(names, ["adb_wifi_off", "adb_wifi_on", "adb_wifi_status", "launch_app", "list_apps", "lock", "message", "press_key", "screen_off", "screen_on", "screen_time",
    "standby", "tv_command", "tv_status", "type_text", "unlock"]);
  for (const t of tools) assert.equal(t.inputSchema.type, "object", t.name);
  await assert.rejects(owner.call("nope/never", {}), (e) => e.code === -32601);
});

test("tools reach the TV as the right commands", async () => {
  const st = await owner.call("tools/call", { name: "tv_status", arguments: {} });
  assert.equal(st.isError, false);
  assert.match(text(st), /"online":true/);

  const call = async (name, args) => {
    const r = await owner.call("tools/call", { name, arguments: args });
    assert.equal(r.isError, false, name + ": " + text(r));
    return tv.seen.at(-1);
  };
  assert.deepEqual((await call("press_key", { keys: ["Up", "Confirm"], gap_ms: 300 })).args, { names: ["Up", "Confirm"], gapMs: 300 });
  assert.deepEqual((await call("press_key", { key: "Home" })).args, { name: "Home" });
  assert.deepEqual((await call("type_text", { text: "the bear", enter: true })).args, { text: "the bear", enter: true });
  assert.deepEqual((await call("launch_app", { app: "netflix" })).args, { package: "com.netflix.ninja" });
  assert.deepEqual((await call("launch_app", { app: "com.google.android.youtube.tv" })).args, { package: "com.google.android.youtube.tv" });
  assert.equal((await call("list_apps", {})).cmd, "apps");
  assert.equal((await call("screen_off", {})).cmd, "screen_off");
  assert.equal((await call("screen_on", {})).cmd, "screen_on");
  assert.equal((await call("standby", {})).cmd, "standby");
  const msg = await call("message", { text: "Dinner!" });
  assert.equal(msg.cmd, "banner");
  assert.equal(msg.text, "Dinner!");
  assert.equal((await call("message", { clear: true })).cmd, "banner_clear");
  assert.equal((await call("lock", {})).cmd, "lock");
  const timer = await call("lock", { minutes: 30 });
  assert.equal(timer.cmd, "lock_timer");
  assert.equal(timer.text, "30");
  assert.equal((await call("unlock", {})).cmd, "unlock");
  assert.equal((await call("unlock", { cancel_timer: true })).cmd, "lock_timer_cancel");
  assert.deepEqual((await call("screen_time", { range: "7d" })).args, { range: "7d" });
  const g = await call("tv_command", { cmd: "global", args: { action: "back" } });
  assert.equal(g.cmd, "global");
  for (const m of tv.seen.slice(-5)) assert.ok(m.agent, "marked as the agent's");
});

test("adb tools: the existing adb command, with the adb permission only", async () => {
  for (const [tool, action] of [["adb_wifi_status", "status"], ["adb_wifi_on", "on"], ["adb_wifi_off", "off"]]) {
    const r = await admin.call("tools/call", { name: tool, arguments: {} });
    assert.equal(r.isError, false, tool + ": " + text(r));
    assert.equal(tv.seen.at(-1).cmd, "adb");
    assert.deepEqual(tv.seen.at(-1).args, { action });
  }
  assert.match(text(await admin.call("tools/call", { name: "adb_wifi_on", arguments: {} })), /"port":37241/);
  const key = await admin.call("tools/call", { name: "press_key", arguments: { key: "Home" } });
  assert.equal(key.isError, true, "adb is not control");
});

test("a read-only key: reads work, control comes back as an error the agent can read", async () => {
  await reader.call("initialize", { protocolVersion: "2024-11-05", capabilities: {}, clientInfo: { name: "test", version: "0" } });
  const apps = await reader.call("tools/call", { name: "list_apps", arguments: {} });
  assert.equal(apps.isError, false);
  const key = await reader.call("tools/call", { name: "press_key", arguments: { key: "Home" } });
  assert.equal(key.isError, true);
  assert.match(text(key), /denied/);
  const adb = await reader.call("tools/call", { name: "adb_wifi_on", arguments: {} });
  assert.equal(adb.isError, true, "no adb permission");
  const pin = await reader.call("tools/call", { name: "tv_command", arguments: { cmd: "set_pin", text: "0000" } });
  assert.equal(pin.isError, true);
  assert.match(text(pin), /Agents may not use/);
});

test("without a key the tools say what to set", async () => {
  const bare = mcp({ RELAY_URL: relay.url, TV_AGENT_KEY: "" });
  try {
    const r = await bare.call("tools/call", { name: "tv_status", arguments: {} });
    assert.equal(r.isError, true);
    assert.match(text(r), /TV_AGENT_KEY/);
  } finally {
    bare.stop();
  }
});
