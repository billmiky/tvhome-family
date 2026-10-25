// For the end-to-end tests: a local relay (wrangler dev, its state in a temporary folder) and a fake TV that
// connects the way the launcher does, says hello with its phone and agent hashes, and answers commands with the
// launcher's rules for agents (Agents.READ / Agents.CONTROL), so the relay's forwarding is what is tested.

import { spawn } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { createHash, randomBytes } from "node:crypto";
import { commandsFor } from "../src/agents.js";

const RELAY_DIR = join(dirname(fileURLToPath(import.meta.url)), "..");

export const sha256 = (s) => createHash("sha256").update(s).digest("hex");
export const randomKey = (n = 24) => randomBytes(n).toString("base64url");

function freePort() {
  return new Promise((resolve) => {
    const s = createServer();
    s.listen(0, "127.0.0.1", () => {
      const p = s.address().port;
      s.close(() => resolve(p));
    });
  });
}

/** Starts wrangler dev; resolves with {url, stop()} once it answers. */
export async function startRelay() {
  const port = await freePort();
  const state = mkdtempSync(join(tmpdir(), "tv-relay-"));
  const proc = spawn(join(RELAY_DIR, "node_modules", ".bin", "wrangler"),
    ["dev", "--local", "--ip", "127.0.0.1", "--port", String(port), "--persist-to", state, "--show-interactive-dev-session=false"],
    { cwd: RELAY_DIR, stdio: ["ignore", "pipe", "pipe"], env: { ...process.env, WRANGLER_SEND_METRICS: "false", NO_COLOR: "1" } });
  let log = "";
  proc.stdout.on("data", (d) => (log += d));
  proc.stderr.on("data", (d) => (log += d));
  const url = "http://127.0.0.1:" + port;
  const until = Date.now() + 90000;
  for (;;) {
    if (proc.exitCode !== null) throw new Error("wrangler dev exited:\n" + log);
    try {
      const r = await fetch(url + "/api/guide");
      if (r.ok) break;
    } catch {}
    if (Date.now() > until) {
      proc.kill();
      throw new Error("wrangler dev did not start:\n" + log);
    }
    await new Promise((r) => setTimeout(r, 300));
  }
  return {
    url,
    log: () => log,
    stop: async () => {
      proc.kill("SIGTERM");
      await new Promise((r) => (proc.exitCode !== null ? r() : proc.once("exit", r)));
      rmSync(state, { recursive: true, force: true });
    },
  };
}

const READ = new Set(commandsFor("read"));
const CONTROL = new Set(commandsFor("control"));

/**
 * A fake TV on the relay. agents: [{key, read, control}]. Every command it gets is kept in `seen` (with the agent
 * hash the relay added); it answers like the launcher: refused (denied) unless the agent may, else ok.
 */
export async function fakeTv(url, { agents = [] } = {}) {
  const id = randomKey(12);
  const tvKey = randomKey(24);
  const phoneKey = randomKey(16);
  const tv = { id, phoneKey, agents: agents.map((a) => ({ ...a, h: sha256(a.key) })), seen: [] };
  const ws = new WebSocket(url.replace(/^http/, "ws") + "/tv?id=" + id, { headers: { Authorization: "Bearer " + tvKey } });
  await new Promise((resolve, reject) => {
    ws.onopen = resolve;
    ws.onerror = (e) => reject(new Error("TV socket: " + (e.message || "error")));
  });
  const relayList = () => tv.agents.map((a) => ({ h: a.h, read: !!a.read, control: !!a.control }));
  ws.onmessage = (e) => {
    if (typeof e.data !== "string" || e.data === "pong") return;
    const m = JSON.parse(e.data);
    if (m.t !== "cmd") return;
    tv.seen.push(m);
    const ack = { t: "ack", n: m.n, ok: true, msg: "", state: { locked: false, hasPin: true, lockAt: 0 } };
    if (m.agent) {
      const a = tv.agents.find((x) => x.h === m.agent);
      const need = READ.has(m.cmd) ? "read" : CONTROL.has(m.cmd) ? "control" : null;
      if (!a || !need || !a[need]) Object.assign(ack, { ok: false, denied: true, msg: "refused by the TV" });
    }
    if (ack.ok && m.cmd === "apps") ack.data = [{ label: "Netflix", package: "com.netflix.ninja" }, { label: "YouTube", package: "com.google.android.youtube.tv" }];
    ws.send(JSON.stringify(ack));
  };
  tv.hello = () => ws.send(JSON.stringify({ t: "hello", name: "Test TV", phones: [sha256(phoneKey)], agents: relayList(), power: "on", state: {} }));
  /** The TV's list changed (revoke, permissions): what RemoteRelay.pushAgents sends. */
  tv.pushAgents = () => ws.send(JSON.stringify({ t: "agents", agents: relayList() }));
  tv.token = (a) => "agent:" + id + "." + a.key;
  tv.close = () => ws.close();
  tv.hello();
  // The relay stores the hello before it answers anything else on this object; a status round trip proves it.
  for (let i = 0; i < 50; i++) {
    const r = await fetch(url + "/api/status", { method: "POST", body: JSON.stringify({ id, key: phoneKey }) });
    if (r.status === 200) break;
    await new Promise((r) => setTimeout(r, 100));
  }
  return tv;
}

/** POST as an agent: {status, body, headers}. */
export async function asAgent(url, path, token, body) {
  const r = await fetch(url + path, {
    method: "POST",
    headers: { authorization: "Bearer " + token, "content-type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  let j = null;
  try {
    j = await r.json();
  } catch {}
  return { status: r.status, body: j, headers: r.headers };
}
