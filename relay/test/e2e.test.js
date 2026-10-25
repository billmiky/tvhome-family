// End to end against a local relay (wrangler dev) and a fake TV: agent auth, the TV's permission answers,
// revocation (requests and sockets), the agent rate limits and /api/guide. npm run test:e2e.

import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { startRelay, fakeTv, asAgent, randomKey, sha256 } from "./dev.js";
import { RATE, BAD_AUTH } from "../src/agents.js";

let relay;
before(async () => (relay = await startRelay()));
after(async () => relay && (await relay.stop()));

const phone = (tv, path, body) =>
  fetch(relay.url + path, { method: "POST", body: JSON.stringify({ id: tv.id, key: tv.phoneKey, ...body }) }).then(async (r) => ({ status: r.status, body: await r.json() }));

test("GET /api/guide: markdown, no auth", async () => {
  const r = await fetch(relay.url + "/api/guide");
  assert.equal(r.status, 200);
  assert.match(r.headers.get("content-type"), /text\/markdown/);
  const g = await r.text();
  assert.ok(g.includes("## Commands") && g.includes(relay.url + "/api/cmd"));
});

test("the page gets the permission summaries", async () => {
  const html = await (await fetch(relay.url + "/")).text();
  assert.ok(!html.includes("/*AGENT_PERMS*/null"));
  assert.ok(html.includes('"control":{"title":"Control"'));
});

test("read-only agent: status and reads pass, control is refused by the TV, owner commands by the relay", async () => {
  const reader = { key: randomKey(), read: true, control: false };
  const tv = await fakeTv(relay.url, { agents: [reader] });
  try {
    const st = await asAgent(relay.url, "/api/status", tv.token(reader));
    assert.equal(st.status, 200);
    assert.equal(st.body.ok, true);
    assert.equal(st.body.online, true);
    assert.equal(st.body.name, "Test TV");
    // The status check pinged the TV for the agent (its last used).
    assert.equal(tv.seen.at(-1).agent, sha256(reader.key));

    const apps = await asAgent(relay.url, "/api/cmd", tv.token(reader), { cmd: "apps" });
    assert.equal(apps.status, 200);
    assert.equal(apps.body.data[0].package, "com.netflix.ninja");
    assert.equal(tv.seen.at(-1).cmd, "apps");
    assert.equal(tv.seen.at(-1).agent, sha256(reader.key));

    const key = await asAgent(relay.url, "/api/cmd", tv.token(reader), { cmd: "button", args: { name: "Home" } });
    assert.equal(key.status, 403);
    assert.equal(key.body.denied, true);

    const before = tv.seen.length;
    for (const cmd of ["set_pin", "adb", "sony", "intent", "local", "agents", "set_psk"]) {
      const r = await asAgent(relay.url, "/api/cmd", tv.token(reader), { cmd, text: "1234", args: { action: "on" } });
      assert.equal(r.status, 403, cmd);
      assert.equal(r.body.denied, true, cmd);
    }
    assert.equal(tv.seen.length, before, "none of them reached the TV");
  } finally {
    tv.close();
  }
});

test("agent with control: keys go through; bad keys and headers are refused", async () => {
  const agent = { key: randomKey(), read: true, control: true };
  const tv = await fakeTv(relay.url, { agents: [agent] });
  try {
    const r = await asAgent(relay.url, "/api/cmd", tv.token(agent), { cmd: "button", args: { names: ["Up", "Confirm"] } });
    assert.equal(r.status, 200);
    assert.deepEqual(tv.seen.at(-1).args, { names: ["Up", "Confirm"] });

    assert.equal((await asAgent(relay.url, "/api/status", "agent:" + tv.id + "." + randomKey())).status, 401);
    assert.equal((await asAgent(relay.url, "/api/status", "agent:" + tv.id + ".short")).status, 401);
    assert.equal((await asAgent(relay.url, "/api/status", tv.phoneKey)).status, 401);
    assert.equal((await asAgent(relay.url, "/api/push", tv.token(agent), { test: true })).status, 401);
    assert.equal((await asAgent(relay.url, "/api/cmd", tv.token(agent), { cmd: "Bad Name" })).status, 400);

    // A phone can't pass itself off as an agent (or mark its command with someone's hash).
    const p = await phone(tv, "/api/cmd", { cmd: "ping", agent: agent.key });
    assert.equal(p.status, 200);
    assert.equal(tv.seen.at(-1).agent, undefined);
  } finally {
    tv.close();
  }
});

test("revoking a key stops it at once and closes its socket", async () => {
  const keep = { key: randomKey(), read: true, control: true };
  const gone = { key: randomKey(), read: true, control: true };
  const tv = await fakeTv(relay.url, { agents: [keep, gone] });
  try {
    const ws = new WebSocket(relay.url.replace(/^http/, "ws") + "/phone?id=" + tv.id);
    const first = new Promise((resolve) => (ws.onmessage = (e) => resolve(JSON.parse(e.data))));
    const closed = new Promise((resolve) => (ws.onclose = (e) => resolve(e.code)));
    await new Promise((resolve) => (ws.onopen = resolve));
    ws.send(JSON.stringify({ t: "auth", agent: gone.key }));
    assert.equal((await first).t, "status");
    assert.equal((await asAgent(relay.url, "/api/cmd", tv.token(gone), { cmd: "ping" })).status, 200);

    tv.agents = tv.agents.filter((a) => a.key !== gone.key);
    tv.pushAgents();
    assert.equal(await closed, 4003);
    const r = await asAgent(relay.url, "/api/cmd", tv.token(gone), { cmd: "ping" });
    assert.equal(r.status, 403);
    assert.equal(r.body.error, "revoked");
    assert.equal((await asAgent(relay.url, "/api/status", tv.token(gone))).status, 403);
    assert.equal((await asAgent(relay.url, "/api/status", tv.token(keep))).status, 200);

    // Read taken away: no status either.
    tv.agents[0].read = false;
    tv.pushAgents();
    await new Promise((r) => setTimeout(r, 200));
    const st = await asAgent(relay.url, "/api/status", tv.token(keep));
    assert.equal(st.status, 403);
    assert.equal(st.body.denied, true);
  } finally {
    tv.close();
  }
});

test("a launcher without agents (an older one) lets no agent in", async () => {
  const agent = { key: randomKey(), read: true, control: true };
  const tv = await fakeTv(relay.url, { agents: [agent] });
  try {
    assert.equal((await asAgent(relay.url, "/api/status", tv.token(agent))).status, 200);
    tv.agents = [];
    tv.hello();
    await new Promise((r) => setTimeout(r, 200));
    assert.equal((await asAgent(relay.url, "/api/status", tv.token(agent))).status, 403);
  } finally {
    tv.close();
  }
});

test("rate limit per agent key, and for wrong keys", async () => {
  const a = { key: randomKey(), read: true, control: true };
  const b = { key: randomKey(), read: true, control: true };
  const tv = await fakeTv(relay.url, { agents: [a, b] });
  try {
    for (let i = 0; i < RATE.burst; i++) assert.equal((await asAgent(relay.url, "/api/cmd", tv.token(a), { cmd: "ping" })).status, 200, "request " + i);
    const r = await asAgent(relay.url, "/api/cmd", tv.token(a), { cmd: "ping" });
    assert.equal(r.status, 429);
    assert.equal(r.body.error, "rate limited");
    assert.ok(Number(r.headers.get("retry-after")) >= 1);
    // Another key has a bucket of its own; the phone has none.
    assert.equal((await asAgent(relay.url, "/api/cmd", tv.token(b), { cmd: "ping" })).status, 200);
    assert.equal((await phone(tv, "/api/cmd", { cmd: "ping" })).status, 200);

    for (let i = 0; i < BAD_AUTH.perMinute; i++) assert.equal((await asAgent(relay.url, "/api/status", "agent:" + tv.id + "." + randomKey())).status, 401);
    assert.equal((await asAgent(relay.url, "/api/status", "agent:" + tv.id + "." + randomKey())).status, 429);
    assert.equal((await asAgent(relay.url, "/api/cmd", tv.token(b), { cmd: "ping" })).status, 429, "guessing locks agents out for the minute");
    assert.equal((await phone(tv, "/api/cmd", { cmd: "ping" })).status, 200, "but not the owner");
  } finally {
    tv.close();
  }
});
