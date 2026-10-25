#!/usr/bin/env node
// MCP server for TV Home: lets an AI agent (Claude Code, Claude Desktop, any MCP client) drive the TV through the
// TV Remote relay with an agent key. Stdio, newline-delimited JSON-RPC, Node 18+, no dependencies, no build step.
//
//   RELAY_URL     the relay, e.g. https://tv-remote.example.workers.dev
//   TV_AGENT_KEY  the key from the TV or the phone remote (Settings › AI agents): agent:<TV id>.<key>
//
// Each tool is one relay request (POST /api/cmd or /api/status). The TV decides what the key may do: read
// (status, apps, screen time) always, control (keys, typing, apps, power, message, lock) and adb (Wireless debugging
// on/off and its address; never a shell) when its owner grants them.
// The relay documents every command at GET <RELAY_URL>/api/guide.

import { createInterface } from "node:readline";

const VERSION = "1.0.0";
const PROTOCOLS = ["2025-06-18", "2025-03-26", "2024-11-05"];
const RELAY = String(process.env.RELAY_URL || "").trim().replace(/\/+$/, "");
const KEY = String(process.env.TV_AGENT_KEY || "").trim().replace(/^Bearer\s+/i, "");
const TIMEOUT_MS = 15000;

/** A relay request; the answer's JSON, with the HTTP status as `http`. */
async function relay(path, body) {
  if (!RELAY) throw new Error("Set RELAY_URL to the relay's address (https://…workers.dev).");
  if (!/^agent:[A-Za-z0-9_-]{12,32}[.][A-Za-z0-9_-]{22,64}$/.test(KEY)) {
    throw new Error("Set TV_AGENT_KEY to the agent key from the TV (Settings › AI agents), agent:<TV id>.<key>.");
  }
  const r = await fetch(RELAY + path, {
    method: "POST",
    headers: { authorization: "Bearer " + KEY, "content-type": "application/json" },
    body: JSON.stringify(body || {}),
    signal: AbortSignal.timeout(TIMEOUT_MS),
  });
  let j = {};
  try {
    j = await r.json();
  } catch {}
  return { http: r.status, ...j };
}

const cmd = (name, extra) => relay("/api/cmd", { cmd: name, ...(extra || {}) });

/** An app by package, or by its label as the TV shows it ("Netflix"). */
async function findPackage(app) {
  if (/^[A-Za-z][\w]*(\.[\w]+)+$/.test(app)) return app;
  const r = await cmd("apps");
  if (!r.ok || !Array.isArray(r.data)) return { failed: r };
  const want = app.toLowerCase();
  const hit = r.data.find((a) => a.label.toLowerCase() === want) || r.data.find((a) => a.label.toLowerCase().includes(want));
  return hit ? hit.package : { failed: { ok: false, http: 404, msg: "No app called \"" + app + "\" on the TV. list_apps shows them." } };
}

const str = (description) => ({ type: "string", description });
const TOOLS = [
  {
    name: "tv_status",
    description: "Whether the TV is online, its power (on, or off meaning standby/screen off), lock state, name and when it was last seen.",
    inputSchema: { type: "object", properties: {} },
    run: () => relay("/api/status", {}),
  },
  {
    name: "tv_command",
    description: "Any agent command by name, as the relay guide (GET <relay>/api/guide) documents: {cmd, text?, args?}. For example {cmd:\"global\", args:{action:\"back\"}}.",
    inputSchema: {
      type: "object",
      properties: { cmd: str("Command name, e.g. info, buttons, global, longpress, wake, lock_timer_cancel"), text: str("The command's text, where it takes one"), args: { type: "object", description: "The command's arguments" } },
      required: ["cmd"],
    },
    run: (a) => cmd(String(a.cmd || ""), { ...(a.text !== undefined ? { text: String(a.text) } : {}), ...(a.args ? { args: a.args } : {}) }),
  },
  {
    name: "press_key",
    description: "Presses remote buttons: Up, Down, Left, Right, Confirm (OK), Return (Back), Home, VolumeUp, VolumeDown, Mute, ChannelUp, ChannelDown, Netflix, Num0-Num9… (tv_command {cmd:\"buttons\"} lists this TV's). Several in order with keys.",
    inputSchema: {
      type: "object",
      properties: {
        key: str("One button name"),
        keys: { type: "array", items: { type: "string" }, maxItems: 20, description: "Several buttons, pressed in order" },
        gap_ms: { type: "integer", minimum: 50, maximum: 2000, description: "Pause between buttons (default 250)" },
      },
    },
    run: (a) => {
      const args = Array.isArray(a.keys) && a.keys.length ? { names: a.keys.map(String) } : { name: String(a.key || "") };
      if (a.gap_ms) args.gapMs = a.gap_ms;
      return cmd("button", { args });
    },
  },
  {
    name: "type_text",
    description: "Types into the TV's selected text field (select one first, e.g. a search box), replacing its text; enter submits.",
    inputSchema: { type: "object", properties: { text: str("The text"), enter: { type: "boolean", description: "Submit afterwards" } }, required: ["text"] },
    run: (a) => cmd("type", { args: { text: String(a.text || ""), enter: a.enter === true } }),
  },
  {
    name: "launch_app",
    description: "Opens an app on the TV by package (com.netflix.ninja) or by its name (Netflix).",
    inputSchema: { type: "object", properties: { app: str("Package or app name") }, required: ["app"] },
    run: async (a) => {
      const pkg = await findPackage(String(a.app || ""));
      return typeof pkg === "string" ? cmd("launch", { args: { package: pkg } }) : pkg.failed;
    },
  },
  { name: "list_apps", description: "The TV's apps: label and package.", inputSchema: { type: "object", properties: {} }, run: () => cmd("apps") },
  { name: "screen_off", description: "Blanks the picture; the TV keeps running (screen_on brings it back).", inputSchema: { type: "object", properties: {} }, run: () => cmd("screen_off") },
  { name: "screen_on", description: "Brings the picture back after screen_off.", inputSchema: { type: "object", properties: {} }, run: () => cmd("screen_on") },
  { name: "standby", description: "Puts the TV in standby. It usually goes offline there, so it may not come back on from here.", inputSchema: { type: "object", properties: {} }, run: () => cmd("standby") },
  {
    name: "message",
    description: "Shows a message in large type on the TV until someone presses OK (at most 500 characters); clear takes it off.",
    inputSchema: { type: "object", properties: { text: str("The message"), clear: { type: "boolean", description: "Take the message off instead" } } },
    run: (a) => (a.clear ? cmd("banner_clear") : cmd("banner", { text: String(a.text || "") })),
  },
  {
    name: "lock",
    description: "Locks the TV until someone types the PIN, now or after minutes (1-720, counting down on screen).",
    inputSchema: { type: "object", properties: { minutes: { type: "integer", minimum: 1, maximum: 720, description: "Lock after this many minutes instead of now" } } },
    run: (a) => (a.minutes ? cmd("lock_timer", { text: String(a.minutes) }) : cmd("lock")),
  },
  {
    name: "unlock",
    description: "Unlocks the TV; cancel_timer stops a lock timer instead.",
    inputSchema: { type: "object", properties: { cancel_timer: { type: "boolean", description: "Cancel the lock timer" } } },
    run: (a) => (a.cancel_timer ? cmd("lock_timer_cancel") : cmd("unlock")),
  },
  {
    name: "screen_time",
    description: "Screen time today or over the last 7 days: total and per app (milliseconds), by hour or by day.",
    inputSchema: { type: "object", properties: { range: { type: "string", enum: ["today", "7d"], description: "today (default) or 7d" } } },
    run: (a) => cmd("screen_time", { args: { range: a.range === "7d" ? "7d" : "today" } }),
  },
  {
    name: "adb_wifi_status",
    description: "Wireless debugging (ADB over Wi-Fi): whether it is on, and the address to `adb connect` to. Needs the adb permission.",
    inputSchema: { type: "object", properties: {} },
    run: () => cmd("adb", { args: { action: "status" } }),
  },
  {
    name: "adb_wifi_on",
    description: "Switches Wireless debugging on and answers its address. Anyone on the home network who is already paired can then connect. Needs the adb permission.",
    inputSchema: { type: "object", properties: {} },
    run: () => cmd("adb", { args: { action: "on" } }),
  },
  {
    name: "adb_wifi_off",
    description: "Switches Wireless debugging off. Needs the adb permission.",
    inputSchema: { type: "object", properties: {} },
    run: () => cmd("adb", { args: { action: "off" } }),
  },
];

/** The relay's answer as MCP content: the TV's sentence first, then the JSON. */
function result(r) {
  const failed = !r.ok;
  let lead = r.msg || r.error || "";
  if (r.http === 429) lead = "Rate limited: try again in " + (r.retryAfter || 60) + " s.";
  else if (r.http === 401) lead = "The relay doesn't know this agent key (TV_AGENT_KEY).";
  else if (r.http === 403 && r.error === "revoked") lead = "This agent key was revoked on the TV.";
  else if (r.http === 503) lead = r.msg || "The TV is offline.";
  const { http, ...body } = r;
  return { content: [{ type: "text", text: (lead ? lead + "\n" : "") + JSON.stringify(body) }], isError: failed };
}

async function handle(m) {
  switch (m.method) {
    case "initialize": {
      const asked = m.params && m.params.protocolVersion;
      return {
        protocolVersion: PROTOCOLS.includes(asked) ? asked : PROTOCOLS[0],
        capabilities: { tools: {} },
        serverInfo: { name: "tv-remote", version: VERSION },
        instructions: "Drives one Android TV through its TV Remote relay. Check tv_status first; navigate with press_key, " +
          "type into a selected field with type_text, open apps with launch_app. A command the agent key may not use " +
          "answers that it needs the control permission, which only the TV's owner can grant.",
      };
    }
    case "ping":
      return {};
    case "tools/list":
      return { tools: TOOLS.map(({ name, description, inputSchema }) => ({ name, description, inputSchema })) };
    case "tools/call": {
      const t = TOOLS.find((x) => x.name === (m.params && m.params.name));
      if (!t) throw Object.assign(new Error("Unknown tool " + (m.params && m.params.name)), { code: -32602 });
      try {
        return result(await t.run((m.params && m.params.arguments) || {}));
      } catch (e) {
        return { content: [{ type: "text", text: String(e && e.message ? e.message : e) }], isError: true };
      }
    }
    default:
      throw Object.assign(new Error("Method not found: " + m.method), { code: -32601 });
  }
}

function send(obj) {
  process.stdout.write(JSON.stringify(obj) + "\n");
}

createInterface({ input: process.stdin }).on("line", async (line) => {
  if (!line.trim()) return;
  let m;
  try {
    m = JSON.parse(line);
  } catch {
    send({ jsonrpc: "2.0", id: null, error: { code: -32700, message: "Parse error" } });
    return;
  }
  if (m.id === undefined || m.id === null) return; // a notification (initialized, cancelled): nothing to answer
  try {
    send({ jsonrpc: "2.0", id: m.id, result: await handle(m) });
  } catch (e) {
    send({ jsonrpc: "2.0", id: m.id, error: { code: e.code || -32603, message: e.message } });
  }
});
