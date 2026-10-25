// AI agents: what an agent key may do through the relay, in one place. The relay checks the names here (an agent
// can't reach set_pin, adb, sony, intent, local or the agent list at all), GET /api/guide is written from them,
// the phone page shows the permission summaries, and test/agents.test.js checks that the launcher's lists
// (Agents.READ and Agents.CONTROL) say the same. The TV enforces read and control; the relay only forwards.
//
// An agent authenticates with "Authorization: Bearer agent:<TV id>.<key>". The TV makes the key (or the phone page
// does, and hands the TV only its SHA-256); the TV sends the relay the hashes with each agent's permissions, in its
// hello and again on every change, so a revoked key stops working at once.

export const AGENT_TOKEN = /^agent:([A-Za-z0-9_-]{12,32})[.]([A-Za-z0-9_-]{22,64})$/;
export const MAX_AGENTS = 10;
// A token bucket per agent key: BURST requests at once, then PER_MINUTE a minute.
export const RATE = { burst: 20, perMinute: 60 };
// Wrong agent keys for one TV: this many a minute, then every agent request waits out the minute.
export const BAD_AUTH = { perMinute: 20 };

export const PERMS = {
  read: { title: "Read", summary: "Status, info, the TV's apps and screen time" },
  control: { title: "Control", summary: "Remote keys, typing, opening apps, power, messages and the lock" },
};

const STATE = { locked: false, hasPin: true, lockAt: 0 };
const ok = (msg, data) => (data === undefined ? { ok: true, msg, state: STATE } : { ok: true, msg, state: STATE, data });

/**
 * Every command an agent may send. `status` is the relay's own (POST /api/status); the others go to the TV as
 * POST /api/cmd {cmd, text?, args?}. `req` and `res` are the examples the guide prints.
 */
export const AGENT_COMMANDS = [
  { cmd: "status", perm: "read", summary: "Online or not, power, lock state, name, last seen. POST /api/status with an empty body.",
    req: {}, res: { ok: true, online: true, name: "BRAVIA", lastSeen: 1761370000000, power: "on", state: STATE } },
  { cmd: "ping", perm: "read", summary: "Checks the TV answers.", req: { cmd: "ping" }, res: ok("") },
  { cmd: "info", perm: "read", summary: "Model, Android and launcher versions, screen on, IP control set up, and the commands this agent may use (caps).",
    req: { cmd: "info" },
    res: ok("", { model: "KD-55X85J", android: "12", sdk: 31, launcher: "2.2", interactive: true, ipKeySaved: true, ipControl: true,
      overlay: true, accessibility: true, caps: ["ping", "info", "apps"] }) },
  { cmd: "caps", perm: "read", summary: "The commands this agent may use now (its permissions applied).", req: { cmd: "caps" },
    res: ok("", ["apps", "button", "caps", "info", "ping"]) },
  { cmd: "apps", perm: "read", summary: "The TV's apps: label and package, by label.", req: { cmd: "apps" },
    res: ok("", [{ label: "Netflix", package: "com.netflix.ninja" }, { label: "YouTube", package: "com.google.android.youtube.tv" }]) },
  { cmd: "buttons", perm: "read", summary: "The remote button names this TV has (for `button`). Needs IP control.", req: { cmd: "buttons" },
    res: ok("", ["ChannelDown", "ChannelUp", "Confirm", "Down", "Home", "Left", "Mute", "Return", "Right", "Up", "VolumeDown", "VolumeUp"]) },
  { cmd: "screen_time", perm: "read", summary: "Screen time today (args.range \"today\") or the last 7 days (\"7d\"): total and per app in ms, with buckets by hour or day.",
    req: { cmd: "screen_time", args: { range: "today" } },
    res: ok("", { allowed: true, total: 5400000, labels: ["00", "01", "…", "23"], buckets: [0, 0, 1800000], apps: [{ name: "YouTube", ms: 3600000, buckets: [0, 0, 1800000] }] }) },
  { cmd: "button", perm: "control", summary: "Presses a remote button (args.name), or several (args.names, at most 20, args.gapMs between them). \"A|B\" presses the first this TV has. Names: Up, Down, Left, Right, Confirm, Return, Home, VolumeUp, VolumeDown, Mute, ChannelUp, ChannelDown, Netflix, Num1… (see `buttons`).",
    req: { cmd: "button", args: { name: "VolumeUp" } }, res: ok("") },
  { cmd: "longpress", perm: "control", summary: "Holds OK on whatever has the focus (the launcher's accessibility service).", req: { cmd: "longpress" }, res: ok("") },
  { cmd: "global", perm: "control", summary: "args.action: back, home, recents, notifications, quick_settings or lock_screen.", req: { cmd: "global", args: { action: "home" } }, res: ok("") },
  { cmd: "type", perm: "control", summary: "Replaces the text in the TV's selected text field with args.text; args.enter submits it.",
    req: { cmd: "type", args: { text: "the bear", enter: true } }, res: ok("Sent.") },
  { cmd: "launch", perm: "control", summary: "Opens an app by package (args.package; see `apps`). Refused while the TV is locked.",
    req: { cmd: "launch", args: { package: "com.netflix.ninja" } }, res: ok("Opened.") },
  { cmd: "screen_off", perm: "control", summary: "Blanks the picture; the TV keeps running. Needs IP control.", req: { cmd: "screen_off" }, res: ok("Screen off. The TV keeps running.") },
  { cmd: "screen_on", perm: "control", summary: "Brings the picture back.", req: { cmd: "screen_on" }, res: ok("Screen on.") },
  { cmd: "standby", perm: "control", summary: "Puts the TV in standby (its connection usually ends there).", req: { cmd: "standby" }, res: ok("Turning off.") },
  { cmd: "wake", perm: "control", summary: "Turns the TV on from standby, best effort (in standby the TV is usually offline).", req: { cmd: "wake" }, res: ok("The TV is on.") },
  { cmd: "banner", perm: "control", summary: "Shows a message (text, at most 500 characters) in large type on the TV until someone presses OK.",
    req: { cmd: "banner", text: "Dinner is ready!" }, res: ok("Shown on the TV.") },
  { cmd: "banner_clear", perm: "control", summary: "Takes the message off.", req: { cmd: "banner_clear" }, res: ok("Cleared.") },
  { cmd: "lock", perm: "control", summary: "Locks the TV: Home with \"This TV is locked\" until someone types the PIN (a PIN must be set).", req: { cmd: "lock" },
    res: { ok: true, msg: "The TV is locked.", state: { locked: true, hasPin: true, lockAt: 0 } } },
  { cmd: "unlock", perm: "control", summary: "Unlocks the TV.", req: { cmd: "unlock" }, res: ok("The TV is unlocked.") },
  { cmd: "lock_timer", perm: "control", summary: "Locks the TV in text minutes (1 to 720), counting down in a corner.", req: { cmd: "lock_timer", text: "30" },
    res: { ok: true, msg: "The TV locks in 30 min.", state: { locked: false, hasPin: true, lockAt: 1761371800000 } } },
  { cmd: "lock_timer_cancel", perm: "control", summary: "Cancels the lock timer.", req: { cmd: "lock_timer_cancel" }, res: ok("Timer cancelled.") },
];

const BY_NAME = new Map(AGENT_COMMANDS.map((c) => [c.cmd, c]));

/** The permission a command needs from an agent, or null when agents may not send it at all. */
export function agentNeeds(cmd) {
  const c = BY_NAME.get(cmd);
  return c ? c.perm : null;
}

/** The command names a permission covers, without the relay's own status (for the launcher check). */
export function commandsFor(perm) {
  return AGENT_COMMANDS.filter((c) => c.perm === perm && c.cmd !== "status").map((c) => c.cmd);
}

/** {id, key} from an Authorization header "Bearer agent:<id>.<key>", or null. */
export function parseAgentAuth(header) {
  const h = String(header || "");
  if (!h.startsWith("Bearer ")) return null;
  const m = AGENT_TOKEN.exec(h.slice(7).trim());
  return m ? { id: m[1], key: m[2] } : null;
}

/** The agents list from the TV: [{h, read, control}], hashes checked, at most MAX_AGENTS. */
export function cleanAgents(list) {
  if (!Array.isArray(list)) return [];
  return list
    .filter((a) => a && typeof a.h === "string" && /^[0-9a-f]{64}$/.test(a.h))
    .slice(0, MAX_AGENTS)
    .map((a) => ({ h: a.h, read: a.read === true, control: a.control === true }));
}

/**
 * One request from an agent against its bucket {tokens, at} (changed in place; a new key starts full). Returns 0
 * when it may go ahead, else the seconds to wait.
 */
export function takeToken(bucket, now, rate = RATE) {
  const perMs = rate.perMinute / 60000;
  if (bucket.at === undefined) {
    bucket.tokens = rate.burst;
    bucket.at = now;
  }
  bucket.tokens = Math.min(rate.burst, bucket.tokens + (now - bucket.at) * perMs);
  bucket.at = now;
  if (bucket.tokens >= 1) {
    bucket.tokens -= 1;
    return 0;
  }
  return Math.max(1, Math.ceil((1 - bucket.tokens) / perMs / 1000));
}

/** The guide an agent (or its developer) reads: GET /api/guide, markdown. */
export function guide(origin) {
  const o = String(origin || "https://<relay>").replace(/\/+$/, "");
  const j = (v) => JSON.stringify(v);
  const out = [];
  out.push("# TV Remote relay: guide for AI agents", "");
  out.push("This relay passes commands to one Android TV running TV Home (the launcher). An agent drives the TV with the",
    "commands below and nothing else: no shell, no settings, no PIN. The TV checks every command against the agent's",
    "permissions and keeps a log of them, which its owner can read on the TV.", "");
  out.push("## Authentication", "");
  out.push("The TV's owner creates an agent key on the TV (Settings › AI agents) or in the phone remote (Settings › AI agents).",
    "It is shown once and looks like `agent:<TV id>.<key>`. Send it on every request:", "");
  out.push("```", "Authorization: Bearer agent:<TV id>.<key>", "Content-Type: application/json", "```", "");
  out.push("The relay keeps only SHA-256 hashes of keys. Revoking a key on the TV or the phone takes effect at once: requests",
    "get 403 and open sockets using it are closed.", "");
  out.push("## Permissions", "");
  out.push("| Permission | Default | Covers |", "|---|---|---|");
  out.push("| `read` | on | " + PERMS.read.summary + ": " + AGENT_COMMANDS.filter((c) => c.perm === "read").map((c) => "`" + c.cmd + "`").join(", ") + " |");
  out.push("| `control` | off, the owner grants it | " + PERMS.control.summary + ": " + AGENT_COMMANDS.filter((c) => c.perm === "control").map((c) => "`" + c.cmd + "`").join(", ") + " |");
  out.push("");
  out.push("## Endpoints", "");
  out.push("- `POST " + o + "/api/status` with `{}`: the TV's status (needs `read`).");
  out.push("- `POST " + o + "/api/cmd` with `{\"cmd\": \"<name>\", \"text\"?: \"…\", \"args\"?: {…}}`: one command, answered when the TV has done it",
    "  (or after 10 s). The answer is `{ok, msg, state, data?}`: `msg` is a sentence for a person, `state` the lock state",
    "  `{locked, hasPin, lockAt}`, `data` the result where there is one.");
  out.push("- `GET " + o + "/api/guide`: this page.", "");
  out.push("```sh", "curl -s " + o + "/api/cmd \\", "  -H 'Authorization: Bearer agent:<TV id>.<key>' \\", "  -H 'Content-Type: application/json' \\",
    "  -d '{\"cmd\":\"button\",\"args\":{\"name\":\"Home\"}}'", "```", "");
  out.push("## Commands", "");
  out.push("| Command | Needs | What it does |", "|---|---|---|");
  for (const c of AGENT_COMMANDS) out.push("| `" + c.cmd + "` | " + c.perm + " | " + c.summary.replace(/\|/g, "\\|") + " |");
  out.push("");
  for (const c of AGENT_COMMANDS) {
    out.push("### " + c.cmd, "", c.summary, "");
    out.push((c.cmd === "status" ? "`POST /api/status`" : "`POST /api/cmd`") + " request:", "", "```json", j(c.req), "```", "", "Response:", "", "```json", j(c.res), "```", "");
  }
  out.push("## Errors", "");
  out.push("| HTTP | Body | Meaning |", "|---|---|---|");
  out.push("| 400 | `{ok:false, error}` | Bad JSON, a bad command name, text over 500 characters, args not an object. |");
  out.push("| 401 | `{ok:false, error:\"bad agent key\"}` | No key, a malformed one, or one this TV never made. |");
  out.push("| 403 | `{ok:false, error:\"revoked\"}` | The key was revoked. |");
  out.push("| 403 | `{ok:false, denied:true, msg}` | The command needs a permission this agent doesn't have, or agents may not send it. |");
  out.push("| 413 | `{ok:false, error}` | Body over 8 KB or args over 3000 characters. |");
  out.push("| 429 | `{ok:false, error:\"rate limited\", retryAfter}` | Too many requests; wait `retryAfter` seconds (also the Retry-After header). |");
  out.push("| 503 | `{ok:false, offline:true, msg}` | The TV is offline (off, in standby or without network). |");
  out.push("| 200 | `{ok:false, msg}` | The TV couldn't do it (for example no text field selected, IP control not set up); `timeout:true` when it didn't answer in 10 s. |");
  out.push("");
  out.push("## Rate limits", "");
  out.push("Each agent key may send " + RATE.burst + " requests at once and " + RATE.perMinute + " a minute after that (status included). More than",
    BAD_AUTH.perMinute + " wrong keys a minute for one TV make every agent request for it wait out the minute. Be gentle: a button press",
    "takes the TV about a quarter of a second, and `button` with `names` sends a sequence in one request.", "");
  out.push("## Live status (optional)", "");
  out.push("A WebSocket to `" + o.replace(/^http/, "ws") + "/phone?id=<TV id>` that sends `{\"t\":\"auth\",\"agent\":\"<key>\"}` first gets",
    "`{\"t\":\"status\", online, power, state, name, lastSeen}` on every change (needs `read`; no live picture for agents). A revoked",
    "key's socket is closed with code 4003.", "");
  out.push("## MCP", "");
  out.push("tools/tv-remote-mcp in the repository is an MCP server (stdio, Node, no build step) with these commands as tools;",
    "set RELAY_URL=" + o + " and TV_AGENT_KEY=agent:<TV id>.<key>.", "");
  return out.join("\n");
}
