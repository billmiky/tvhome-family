// Relay between TV Home (the launcher) and the phone remote page.
//
// The TV keeps one WebSocket open to its Durable Object (GET /tv?id=…, Authorization: Bearer <tv key>);
// the first connection for an id claims it. Over that socket the TV lists the phones it paired (SHA-256
// of each phone key, shown to the phone in the TV's QR code). A phone POSTs /api/cmd with the TV id and
// its key; the object checks the key, hands the command to the TV and waits for its answer.
// Nothing is stored but the hashes, the TV's name and when it was last seen.

import { DurableObject } from "cloudflare:workers";
import { PAGE } from "./page.js";
import { ICON512, ICON192, APPLE180 } from "./icons.js";

const ID = /^[A-Za-z0-9_-]{12,32}$/;
const KEY = /^[A-Za-z0-9_-]{22,64}$/;
const HEX64 = /^[0-9a-f]{64}$/;
const COMMANDS = new Set([
  "ping", "banner", "banner_clear", "screen_off", "screen_on", "standby", "wake", "set_psk",
  "set_pin", "lock", "unlock", "lock_timer", "lock_timer_cancel",
]);
const MAX_TEXT = 500;
const ANSWER_MS = 8000;
const MAX_PHONES = 10;
const ICONS = { "/icon-512.png": ICON512, "/icon-192.png": ICON192, "/apple-touch-icon.png": APPLE180 };
// start_url is "/": Android keeps the pairing in the browser's storage, which the installed app shares.
// iOS starts the home-screen app at the address it was added from, which keeps #<id>.<key> (see page.js).
const MANIFEST = {
  name: "TV Remote",
  short_name: "TV Remote",
  start_url: "/",
  scope: "/",
  display: "standalone",
  background_color: "#0b0b0f",
  theme_color: "#0b0b0f",
  icons: [
    { src: "/icon-192.png", sizes: "192x192", type: "image/png", purpose: "any maskable" },
    { src: "/icon-512.png", sizes: "512x512", type: "image/png", purpose: "any maskable" },
  ],
};

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (url.pathname === "/" && req.method === "GET") {
      return new Response(PAGE, {
        headers: {
          "content-type": "text/html; charset=utf-8",
          "cache-control": "no-cache",
          "content-security-policy":
            "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; manifest-src 'self'; worker-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
          "referrer-policy": "no-referrer",
        },
      });
    }
    if (url.pathname === "/manifest.webmanifest") {
      return new Response(JSON.stringify(MANIFEST), { headers: { "content-type": "application/manifest+json", "cache-control": "public, max-age=3600" } });
    }
    if (url.pathname in ICONS) {
      const bytes = Uint8Array.from(atob(ICONS[url.pathname]), (c) => c.charCodeAt(0));
      return new Response(bytes, { headers: { "content-type": "image/png", "cache-control": "public, max-age=86400" } });
    }
    if (url.pathname === "/sw.js") {
      // Only so the page counts as an installable app; every request still goes to the network.
      return new Response("self.addEventListener('fetch', () => {});\n", { headers: { "content-type": "text/javascript", "cache-control": "no-cache" } });
    }
    if (url.pathname === "/tv") {
      const id = url.searchParams.get("id") || "";
      if (!ID.test(id)) return text(400, "bad id");
      return env.TV.get(env.TV.idFromName(id)).fetch(req);
    }
    if (url.pathname === "/api/cmd" || url.pathname === "/api/status") {
      if (req.method !== "POST") return json(405, { ok: false, error: "POST only" });
      const len = Number(req.headers.get("content-length") || 0);
      if (len > 4096) return json(413, { ok: false, error: "too large" });
      let body;
      try {
        body = await req.json();
      } catch {
        return json(400, { ok: false, error: "bad json" });
      }
      if (!body || !ID.test(body.id || "") || !KEY.test(body.key || "")) return json(400, { ok: false, error: "not paired" });
      const stub = env.TV.get(env.TV.idFromName(body.id));
      return stub.fetch(new Request("https://tv" + url.pathname, { method: "POST", body: JSON.stringify(body) }));
    }
    return text(404, "not found");
  },
};

export class Tv extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.pending = new Map();
    // Keep-alive pings are answered without waking the object.
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
  }

  async fetch(req) {
    const url = new URL(req.url);
    if (url.pathname === "/tv") return this.connect(req);
    const body = await req.json();
    const phones = (await this.ctx.storage.get("phones")) || [];
    if (!phones.includes(await sha256(body.key))) return json(403, { ok: false, error: "not paired" });
    const name = (await this.ctx.storage.get("name")) || "TV";
    const lastSeen = (await this.ctx.storage.get("lastSeen")) || 0;
    if (url.pathname === "/api/status") {
      const r = await this.ask({ cmd: "ping" }, 4000);
      return json(200, { ok: true, online: r.ok, name, lastSeen: r.ok ? Date.now() : lastSeen, state: r.state || null });
    }
    const cmd = String(body.cmd || "");
    if (!COMMANDS.has(cmd)) return json(400, { ok: false, error: "unknown command" });
    const msg = { cmd };
    if (cmd === "set_pin" || cmd === "lock_timer") {
      const t = typeof body.text === "string" ? body.text.trim() : "";
      if (cmd === "set_pin" && !/^[0-9]{4}$/.test(t)) return json(400, { ok: false, error: "The PIN is 4 digits." });
      if (cmd === "lock_timer" && !(/^[0-9]{1,3}$/.test(t) && +t >= 1 && +t <= 720)) return json(400, { ok: false, error: "The timer is 1 to 720 minutes." });
      msg.text = t;
    }
    if (cmd === "banner" || cmd === "set_psk") {
      const t = typeof body.text === "string" ? body.text.trim() : "";
      if (cmd === "banner" && (t.length === 0 || t.length > MAX_TEXT)) return json(400, { ok: false, error: "text is 1 to 500 characters" });
      if (cmd === "set_psk" && t.length > 64) return json(400, { ok: false, error: "PSK is at most 64 characters" });
      msg.text = t;
    }
    const r = await this.ask(msg, ANSWER_MS);
    return json(r.offline ? 503 : 200, r);
  }

  async connect(req) {
    if (req.headers.get("upgrade") !== "websocket") return text(426, "websocket only");
    const auth = req.headers.get("authorization") || "";
    const key = auth.startsWith("Bearer ") ? auth.slice(7) : "";
    if (!KEY.test(key)) return text(401, "no key");
    const hash = await sha256(key);
    const owner = await this.ctx.storage.get("tv");
    if (owner && owner !== hash) return text(403, "this id belongs to another TV");
    if (!owner) await this.ctx.storage.put("tv", hash);
    for (const old of this.ctx.getWebSockets()) {
      try {
        old.close(4000, "replaced");
      } catch {}
    }
    const [client, server] = Object.values(new WebSocketPair());
    this.ctx.acceptWebSocket(server);
    await this.ctx.storage.put("lastSeen", Date.now());
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws, data) {
    let m;
    try {
      m = JSON.parse(typeof data === "string" ? data : new TextDecoder().decode(data));
    } catch {
      return;
    }
    if (m.t === "hello") {
      const phones = Array.isArray(m.phones) ? m.phones.filter((h) => typeof h === "string" && HEX64.test(h)).slice(0, MAX_PHONES) : [];
      await this.ctx.storage.put({
        phones,
        name: typeof m.name === "string" ? m.name.slice(0, 60) : "TV",
        lastSeen: Date.now(),
      });
    } else if (m.t === "ack") {
      const p = this.pending.get(m.n);
      if (p) {
        this.pending.delete(m.n);
        p({ ok: m.ok === true, msg: typeof m.msg === "string" ? m.msg.slice(0, 200) : "", state: m.state || null });
      }
      await this.ctx.storage.put("lastSeen", Date.now());
    }
  }

  async webSocketClose(ws, code) {
    try {
      ws.close(code === 1005 || code === 1006 ? 1000 : code, "bye");
    } catch {}
  }

  ask(msg, ms) {
    const ws = this.ctx.getWebSockets()[0];
    if (!ws) return Promise.resolve({ ok: false, offline: true, msg: "The TV is offline." });
    const n = crypto.randomUUID();
    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        this.pending.delete(n);
        resolve({ ok: false, offline: true, msg: "The TV did not answer." });
      }, ms);
      this.pending.set(n, (r) => {
        clearTimeout(timer);
        resolve(r);
      });
      try {
        ws.send(JSON.stringify({ t: "cmd", n, ...msg }));
      } catch {
        clearTimeout(timer);
        this.pending.delete(n);
        resolve({ ok: false, offline: true, msg: "The TV is offline." });
      }
    });
  }
}

async function sha256(s) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function json(status, obj) {
  return new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json", "cache-control": "no-store" } });
}

function text(status, s) {
  return new Response(s, { status, headers: { "content-type": "text/plain; charset=utf-8" } });
}
