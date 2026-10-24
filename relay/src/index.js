// Relay between TV Home (the launcher) and the phone remote page.
//
// The TV keeps one WebSocket open to its Durable Object (GET /tv?id=…, Authorization: Bearer <tv key>);
// the first connection for an id claims it. Over that socket the TV lists the phones it paired (SHA-256
// of each phone key, shown to the phone in the TV's QR code), its power and lock state. A phone POSTs
// /api/cmd with the TV id and its key; the object checks the key, hands the command to the TV and waits
// for its answer. Commands pass through by name, so a new feature on the phone page needs no relay change.
// Phones may also hold a WebSocket (/phone) for live status, and a Web Push subscription for "TV on/off".
// Stored: the key hashes, push subscriptions, the TV's name, power and lock state, when it was last seen.

import { DurableObject } from "cloudflare:workers";
import { PAGE } from "./page.js";
import { SW } from "./sw.js";
import { ICON512, ICON192, APPLE180 } from "./icons.js";
import { OUTFIT } from "./font.js";
import { push, makeVapid } from "./webpush.js";

const ID = /^[A-Za-z0-9_-]{12,32}$/;
const KEY = /^[A-Za-z0-9_-]{22,64}$/;
const HEX64 = /^[0-9a-f]{64}$/;
const CMD = /^[a-z][a-z0-9_.-]{0,40}$/;
const PAIRING = /^[A-Za-z0-9_-]{12,32}[.][A-Za-z0-9_-]{22,64}$/;
const MAX_TEXT = 500;
const MAX_ARGS = 3000;
const MAX_DATA = 256 * 1024;
const ANSWER_MS = 10000;
const MAX_PHONES = 10;
const MAX_SUBS = 10;
const MAX_PHONE_SOCKETS = 20;
// The TV gone this long (no socket) counts as off: a short drop is a network blip, not a power change.
const OFF_AFTER_MS = 2 * 60 * 1000;
// A dropped TV socket shows as offline only after this: a Wi-Fi blip reconnects within seconds.
const GRACE_MS = 15 * 1000;
const SUBJECT = "https://tv-remote.ptrinh.workers.dev";
const ICONS = {
  "/icon-512.png": ICON512,
  "/icon-192.png": ICON192,
  "/apple-touch-icon.png": APPLE180,
  "/apple-touch-icon-precomposed.png": APPLE180,
  "/apple-touch-icon-180x180.png": APPLE180,
  "/favicon.png": ICON192,
};
// start_url is "/": Android keeps the pairing in the browser's storage, which the installed app shares.
// iOS starts the home-screen app at the address it was added from, which keeps #<id>.<key> (see page.js).
const MANIFEST = {
  id: "/",
  name: "TV Remote",
  short_name: "TV Remote",
  start_url: "/",
  scope: "/",
  display: "standalone",
  background_color: "#0b0b0f",
  theme_color: "#0b0b0f",
  icons: [
    { src: "/icon-192.png", sizes: "192x192", type: "image/png", purpose: "any" },
    { src: "/icon-512.png", sizes: "512x512", type: "image/png", purpose: "any" },
    { src: "/icon-512.png", sizes: "512x512", type: "image/png", purpose: "maskable" },
  ],
};

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    const path = url.pathname;
    if (path === "/" && (req.method === "GET" || req.method === "HEAD")) {
      // A paired page (/?p=<id>.<key>) links the manifest that starts the home-screen app paired.
      const p = url.searchParams.get("p") || "";
      const page = PAIRING.test(p) ? PAGE.replace('href="/manifest.webmanifest"', 'href="/manifest.webmanifest?p=' + p + '"') : PAGE;
      return new Response(page, {
        headers: {
          "content-type": "text/html; charset=utf-8",
          "cache-control": "no-store",
          "content-security-policy":
            "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; img-src 'self' data: blob:; font-src 'self'; manifest-src 'self'; worker-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
          "referrer-policy": "no-referrer",
        },
      });
    }
    if (path === "/manifest.webmanifest") {
      // ?p=<id>.<key> from a paired page: the home-screen app starts paired. iOS opens it at start_url
      // with storage of its own, so without this it would always say "Not paired".
      const p = url.searchParams.get("p") || "";
      const m = PAIRING.test(p);
      const manifest = m ? { ...MANIFEST, start_url: "/?p=" + p } : MANIFEST;
      return new Response(JSON.stringify(manifest), {
        headers: { "content-type": "application/manifest+json", "cache-control": m ? "private, no-store" : "public, max-age=3600" },
      });
    }
    if (path in ICONS || path === "/favicon.ico") {
      const bytes = Uint8Array.from(atob(ICONS[path] || ICON192), (c) => c.charCodeAt(0));
      return new Response(bytes, { headers: { "content-type": "image/png", "cache-control": "public, max-age=86400" } });
    }
    if (path === "/outfit.ttf") {
      const bytes = Uint8Array.from(atob(OUTFIT), (c) => c.charCodeAt(0));
      return new Response(bytes, { headers: { "content-type": "font/ttf", "cache-control": "public, max-age=2592000, immutable" } });
    }
    if (path === "/sw.js") {
      return new Response(SW, { headers: { "content-type": "text/javascript", "cache-control": "no-cache" } });
    }
    if (path === "/api/vapid") return json(200, { key: (await vapidKeys(env)).pub });
    if (path === "/tv" || path === "/phone") {
      const id = url.searchParams.get("id") || "";
      if (!ID.test(id)) return text(400, "bad id");
      return env.TV.get(env.TV.idFromName(id)).fetch(req);
    }
    if (path === "/api/cmd" || path === "/api/status" || path === "/api/push") {
      if (req.method !== "POST") return json(405, { ok: false, error: "POST only" });
      const len = Number(req.headers.get("content-length") || 0);
      if (len > 8192) return json(413, { ok: false, error: "too large" });
      let body;
      try {
        body = await req.json();
      } catch {
        return json(400, { ok: false, error: "bad json" });
      }
      if (!body || !ID.test(body.id || "") || !KEY.test(body.key || "")) return json(400, { ok: false, error: "not paired" });
      const stub = env.TV.get(env.TV.idFromName(body.id));
      return stub.fetch(new Request("https://tv" + path, { method: "POST", body: JSON.stringify(body) }));
    }
    return text(404, "not found");
  },
};

export class Tv extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.env = env;
    this.pending = new Map();
    // Keep-alive pings (TV and phones) are answered without waking the object.
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
  }

  tv() {
    return this.ctx.getWebSockets("tv")[0] || null;
  }

  async paired(key) {
    const phones = (await this.ctx.storage.get("phones")) || [];
    return phones.includes(await sha256(key));
  }

  async fetch(req) {
    const url = new URL(req.url);
    if (url.pathname === "/_vapid") return json(200, await this.vapid());
    if (url.pathname === "/tv") return this.connectTv(req);
    if (url.pathname === "/phone") return this.connectPhone(req);
    const body = await req.json();
    if (!(await this.paired(body.key))) return json(403, { ok: false, error: "not paired" });

    if (url.pathname === "/api/status") {
      if (this.tv()) await this.ask({ cmd: "ping" }, 4000);
      return json(200, { ok: true, ...(await this.status()) });
    }
    if (url.pathname === "/api/push") return this.subscribe(body);

    const cmd = String(body.cmd || "");
    if (!CMD.test(cmd)) return json(400, { ok: false, error: "bad command name" });
    const msg = { cmd };
    const t = typeof body.text === "string" ? body.text.trim() : "";
    if (t.length > MAX_TEXT) return json(400, { ok: false, error: "text is at most 500 characters" });
    if (t) msg.text = t;
    if (body.args !== undefined) {
      if (!body.args || typeof body.args !== "object" || Array.isArray(body.args)) return json(400, { ok: false, error: "args is an object" });
      if (JSON.stringify(body.args).length > MAX_ARGS) return json(413, { ok: false, error: "args too large" });
      msg.args = body.args;
    }
    if (cmd === "banner" && !t) return json(400, { ok: false, error: "Type a message first." });
    if (cmd === "set_pin" && !/^[0-9]{4}$/.test(t)) return json(400, { ok: false, error: "The PIN is 4 digits." });
    if (cmd === "lock_timer" && !(/^[0-9]{1,3}$/.test(t) && +t >= 1 && +t <= 720)) return json(400, { ok: false, error: "The timer is 1 to 720 minutes." });
    if (cmd === "set_psk" && t.length > 64) return json(400, { ok: false, error: "PSK is at most 64 characters" });
    const r = await this.ask(msg, ANSWER_MS);
    return json(r.offline ? 503 : 200, r);
  }

  // ---- sockets ---------------------------------------------------------------------------------------

  async connectTv(req) {
    if (req.headers.get("upgrade") !== "websocket") return text(426, "websocket only");
    const auth = req.headers.get("authorization") || "";
    const key = auth.startsWith("Bearer ") ? auth.slice(7) : "";
    if (!KEY.test(key)) return text(401, "no key");
    const hash = await sha256(key);
    const owner = await this.ctx.storage.get("tv");
    if (owner && owner !== hash) return text(403, "this id belongs to another TV");
    if (!owner) await this.ctx.storage.put("tv", hash);
    for (const old of this.ctx.getWebSockets("tv")) {
      try {
        old.close(4000, "replaced");
      } catch {}
    }
    const [client, server] = Object.values(new WebSocketPair());
    this.ctx.acceptWebSocket(server, ["tv"]);
    await this.ctx.storage.put("lastSeen", Date.now());
    await this.ctx.storage.deleteAlarm();
    await this.ctx.storage.delete(["droppedAt", "offlineSent"]);
    return new Response(null, { status: 101, webSocket: client });
  }

  /** A phone's live status. The key comes in the first message ({t:"auth", key}), not in the address. */
  async connectPhone(req) {
    if (req.headers.get("upgrade") !== "websocket") return text(426, "websocket only");
    const phones = this.ctx.getWebSockets("phone");
    if (phones.length >= MAX_PHONE_SOCKETS) {
      try {
        phones[0].close(4001, "too many");
      } catch {}
    }
    const [client, server] = Object.values(new WebSocketPair());
    this.ctx.acceptWebSocket(server, ["phone"]);
    server.serializeAttachment({ auth: false });
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws, data) {
    // A binary message from the TV is a live-view frame (JPEG): to every phone watching.
    if (typeof data !== "string") {
      if (!this.ctx.getTags(ws).includes("tv")) return;
      for (const p of this.ctx.getWebSockets("phone")) {
        const a = p.deserializeAttachment();
        if (a && a.auth && a.live) {
          try {
            p.send(data);
          } catch {}
        }
      }
      return;
    }
    let m;
    try {
      m = JSON.parse(typeof data === "string" ? data : new TextDecoder().decode(data));
    } catch {
      return;
    }
    if (this.ctx.getTags(ws).includes("phone")) {
      if (m.t === "auth" && typeof m.key === "string" && KEY.test(m.key) && (await this.paired(m.key))) {
        ws.serializeAttachment({ auth: true, live: false });
        ws.send(JSON.stringify({ t: "status", ...(await this.status()) }));
      } else if (m.t === "auth") {
        ws.send(JSON.stringify({ t: "unpaired" }));
        ws.close(4003, "not paired");
      } else if (m.t === "live") {
        const a = ws.deserializeAttachment() || {};
        if (!a.auth) return;
        a.live = m.on === true;
        a.fps = Math.max(1, Math.min(5, Number(m.fps) || 2));
        ws.serializeAttachment(a);
        this.liveChanged();
      }
      return;
    }
    if (m.t === "hello") {
      const phones = Array.isArray(m.phones) ? m.phones.filter((h) => typeof h === "string" && HEX64.test(h)).slice(0, MAX_PHONES) : [];
      await this.ctx.storage.put({ phones, name: typeof m.name === "string" ? m.name.slice(0, 60) : "TV", lastSeen: Date.now() });
      if (m.state && typeof m.state === "object") await this.ctx.storage.put("state", m.state);
      await this.setPower(m.power === "off" ? "off" : "on", true);
      await this.broadcast();
      this.liveChanged();
    } else if (m.t === "event") {
      if (m.state && typeof m.state === "object") await this.ctx.storage.put("state", m.state);
      if (m.power === "on" || m.power === "off") await this.setPower(m.power, true);
      await this.broadcast();
    } else if (m.t === "live_error") {
      const msg = JSON.stringify({ t: "live_error", msg: String(m.msg || "").slice(0, 200) });
      for (const p of this.ctx.getWebSockets("phone")) {
        const a = p.deserializeAttachment();
        if (a && a.auth && a.live) {
          try {
            p.send(msg);
          } catch {}
        }
      }
    } else if (m.t === "ack") {
      const p = this.pending.get(m.n);
      const data = m.data === undefined ? undefined : JSON.stringify(m.data).length > MAX_DATA ? null : m.data;
      if (p) {
        this.pending.delete(m.n);
        p({ ok: m.ok === true, msg: typeof m.msg === "string" ? m.msg.slice(0, 300) : "", state: m.state || null, data });
      }
      const prev = JSON.stringify((await this.ctx.storage.get("state")) || null);
      await this.ctx.storage.put("lastSeen", Date.now());
      if (m.state && typeof m.state === "object" && JSON.stringify(m.state) !== prev) {
        await this.ctx.storage.put("state", m.state);
        await this.broadcast();
      }
    }
  }

  async webSocketClose(ws, code) {
    try {
      ws.close(code === 1005 || code === 1006 ? 1000 : code, "bye");
    } catch {}
    if (this.ctx.getTags(ws).includes("phone")) {
      const a = ws.deserializeAttachment();
      if (a && a.live) this.liveChanged(ws);
    }
    if (this.ctx.getTags(ws).includes("tv")) await this.seen(ws);
    if (this.ctx.getTags(ws).includes("tv") && !this.ctx.getWebSockets("tv").some((s) => s !== ws)) {
      // Not offline yet: the alarm says so if the TV hasn't come back within GRACE_MS.
      await this.ctx.storage.put({ droppedAt: Date.now(), offlineSent: false });
      await this.ctx.storage.setAlarm(Date.now() + GRACE_MS);
    }
  }

  async webSocketError(ws) {
    await this.webSocketClose(ws, 1006);
  }

  /** No TV GRACE_MS after the drop: tell the phones it is offline; after OFF_AFTER_MS it is off. */
  async alarm() {
    if (this.tv()) return;
    const s = await this.ctx.storage.get(["droppedAt", "offlineSent"]);
    const dropped = s.get("droppedAt") || Date.now();
    if (!s.get("offlineSent")) {
      await this.ctx.storage.put("offlineSent", true);
      await this.broadcast(false);
    }
    if (Date.now() - dropped >= OFF_AFTER_MS - 1000) await this.setPower("off");
    else await this.ctx.storage.setAlarm(dropped + OFF_AFTER_MS);
  }

  /** Tells the TV whether anyone watches the live view now (gone: a phone socket that is closing). */
  liveChanged(gone) {
    const tv = this.tv();
    if (!tv) return;
    let fps = 0;
    for (const p of this.ctx.getWebSockets("phone")) {
      if (p === gone) continue;
      const a = p.deserializeAttachment();
      if (a && a.auth && a.live) fps = Math.max(fps, a.fps || 2);
    }
    try {
      tv.send(JSON.stringify({ t: "live", on: fps > 0, fps: fps || 2 }));
    } catch {}
  }

  /**
   * Last seen: the TV's keep-alive pings are answered without waking the object, so lastSeen alone is
   * when it last connected or answered; the latest ping (or this moment, for a socket closing) is fresher.
   */
  async seen(ws) {
    let t = Date.now();
    if (ws) {
      try {
        const ping = this.ctx.getWebSocketAutoResponseTimestamp(ws);
        if (ping) t = Math.max(ping.getTime(), (await this.ctx.storage.get("lastSeen")) || 0);
      } catch {}
    }
    await this.ctx.storage.put("lastSeen", t);
  }

  // ---- status, power, push ---------------------------------------------------------------------------

  async status(online) {
    let live = online === undefined ? !!this.tv() : online;
    if (!live && online === undefined) {
      // Inside the grace after a drop the TV still counts as online (it is most likely reconnecting).
      const g = await this.ctx.storage.get(["droppedAt", "offlineSent"]);
      live = !!g.get("droppedAt") && !g.get("offlineSent") && Date.now() - g.get("droppedAt") < GRACE_MS;
    }
    if (live) await this.ctx.storage.put("lastSeen", Date.now());
    const s = await this.ctx.storage.get(["name", "lastSeen", "state", "power"]);
    return {
      online: live,
      name: s.get("name") || "TV",
      lastSeen: s.get("lastSeen") || 0,
      state: s.get("state") || null,
      power: s.get("power") || null,
    };
  }

  async broadcast(online) {
    const msg = JSON.stringify({ t: "status", ...(await this.status(online)) });
    for (const ws of this.ctx.getWebSockets("phone")) {
      const a = ws.deserializeAttachment();
      if (!a || !a.auth) continue;
      try {
        ws.send(msg);
      } catch {}
    }
  }

  /** Stores a power change and tells the push subscribers; quiet makes the caller broadcast instead. */
  async setPower(power, quiet) {
    const before = await this.ctx.storage.get("power");
    if (before === power) return;
    await this.ctx.storage.put("power", power);
    if (!quiet) await this.broadcast();
    if (!before) return;   // first time we learn it: nothing changed
    const name = (await this.ctx.storage.get("name")) || "TV";
    await this.notify({
      title: power === "on" ? `${name} is on` : `${name} is off`,
      body: power === "on" ? "The TV was switched on." : "The TV was switched off.",
      tag: "tv-power",
    });
  }

  async notify(payload) {
    const subs = (await this.ctx.storage.get("subs")) || [];
    if (!subs.length) return;
    const keys = await vapidKeys(this.env);
    const keep = [];
    for (const sub of subs) {
      try {
        const status = await push(sub, payload, keys, this.env.VAPID_SUBJECT || SUBJECT);
        if (status !== 404 && status !== 410) keep.push(sub);
      } catch {
        keep.push(sub);
      }
    }
    if (keep.length !== subs.length) await this.ctx.storage.put("subs", keep);
  }

  async subscribe(body) {
    let subs = (await this.ctx.storage.get("subs")) || [];
    if (body.unsubscribe) {
      subs = subs.filter((s) => s.endpoint !== body.unsubscribe);
      await this.ctx.storage.put("subs", subs);
      return json(200, { ok: true, msg: "Notifications off." });
    }
    const s = body.sub;
    if (!s || typeof s.endpoint !== "string" || !s.endpoint.startsWith("https://") || s.endpoint.length > 1000
      || !s.keys || typeof s.keys.p256dh !== "string" || typeof s.keys.auth !== "string") {
      return json(400, { ok: false, error: "bad subscription" });
    }
    subs = subs.filter((x) => x.endpoint !== s.endpoint);
    subs.push({ endpoint: s.endpoint, keys: { p256dh: s.keys.p256dh, auth: s.keys.auth } });
    await this.ctx.storage.put("subs", subs.slice(-MAX_SUBS));
    if (body.test) {
      const name = (await this.ctx.storage.get("name")) || "TV";
      await this.notify({ title: "TV Remote", body: `You will be told when ${name} turns on or off.`, tag: "tv-test" });
    }
    return json(200, { ok: true, msg: "Notifications on." });
  }

  /** Only in the object named VAPID_HOLDER: the relay's own VAPID pair, made the first time it is asked for. */
  async vapid() {
    let keys = await this.ctx.storage.get("vapid");
    if (!keys) {
      keys = await makeVapid();
      await this.ctx.storage.put("vapid", keys);
    }
    return keys;
  }

  ask(msg, ms) {
    const ws = this.tv();
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

// Notifications need a VAPID key pair. The Worker secrets VAPID_PRIVATE (a JWK) and VAPID_PUBLIC win when set;
// otherwise the relay makes its own pair once and keeps it in one Durable Object, whose name (with a colon) no
// request from outside can reach: the ids the routes accept are letters, digits, _ and - only.
const VAPID_HOLDER = "vapid:keys";
let vapidCache = null;
async function vapidKeys(env) {
  if (env.VAPID_PRIVATE && env.VAPID_PUBLIC) return { jwk: JSON.parse(env.VAPID_PRIVATE), pub: env.VAPID_PUBLIC };
  if (!vapidCache) {
    const r = await env.TV.get(env.TV.idFromName(VAPID_HOLDER)).fetch("https://tv/_vapid");
    vapidCache = await r.json();
  }
  return vapidCache;
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
