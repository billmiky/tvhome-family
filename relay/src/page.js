// The phone remote: one page, no build step, installable (Add to Home Screen). Paired by the TV's QR code
// (#<id>.<key> in the address), which the page keeps in localStorage, in the address and in the manifest's
// start_url (/manifest.webmanifest?p=…): iOS starts a home-screen app at start_url, with storage of its own.
// A WebSocket to the relay (/phone) keeps the status live: controls go away while the TV is offline and
// come back the moment it is on. Commands go through run(cmd, {text, args}); the TV's building blocks
// (sony, button, intent, launch, apps, global, local, info) let new features live here alone.
// (No backticks or dollar-brace in the script below: this file is one template literal.)
export const PAGE = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="theme-color" content="#0b0b0f">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
<meta name="apple-mobile-web-app-title" content="TV Remote">
<link rel="manifest" href="/manifest.webmanifest">
<link rel="icon" type="image/png" sizes="192x192" href="/icon-192.png">
<link rel="apple-touch-icon" sizes="180x180" href="/apple-touch-icon.png">
<title>TV Remote</title>
<style>
  :root { --bg:#0b0b0f; --card:#17171d; --line:#2a2a33; --text:#f4f4f6; --dim:#9a9aa6; --accent:#ff9f0a; --ok:#30d158; --bad:#ff453a; }
  * { box-sizing:border-box; -webkit-tap-highlight-color:transparent; }
  body { margin:0; background:var(--bg); color:var(--text); font:16px/1.4 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
         padding:max(16px, env(safe-area-inset-top)) 16px max(24px, env(safe-area-inset-bottom)); }
  main { max-width:520px; margin:0 auto; }
  header { display:flex; align-items:center; justify-content:space-between; margin:4px 0 18px; gap:12px; }
  h1 { font-size:26px; margin:0; letter-spacing:-.02em; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  .pill { flex:0 0 auto; font-size:13px; color:var(--dim); background:var(--card); border:1px solid var(--line); border-radius:99px; padding:5px 11px; display:flex; gap:7px; align-items:center; }
  .dot { width:8px; height:8px; border-radius:50%; background:var(--dim); }
  .on .dot { background:var(--ok); } .off .dot { background:var(--bad); }
  section { background:var(--card); border:1px solid var(--line); border-radius:18px; padding:16px; margin-bottom:14px; }
  h2 { font-size:13px; font-weight:600; color:var(--dim); text-transform:uppercase; letter-spacing:.06em; margin:0 0 12px; }
  textarea, input { width:100%; background:#0f0f14; color:var(--text); border:1px solid var(--line); border-radius:12px; padding:12px; font:inherit; font-size:17px; }
  textarea { min-height:96px; resize:vertical; }
  textarea:focus, input:focus { outline:none; border-color:var(--accent); }
  .row { display:flex; gap:10px; margin-top:10px; }
  .grid { display:grid; grid-template-columns:1fr 1fr; gap:10px; }
  button { flex:1; border:0; border-radius:14px; padding:15px 12px; font:inherit; font-weight:600; color:var(--text); background:#24242c; cursor:pointer; }
  button:active { transform:scale(.98); }
  button:disabled { opacity:.45; }
  button.primary { background:var(--accent); color:#1a1000; }
  button.danger { background:#3a1715; color:#ff8a80; }
  .chips { display:flex; flex-wrap:wrap; gap:8px; margin-top:12px; }
  .chips button { flex:0 0 auto; padding:10px 14px; border-radius:99px; font-weight:500; }
  .seg { display:flex; background:#0f0f14; border:1px solid var(--line); border-radius:12px; padding:3px; gap:3px; }
  .seg button { padding:8px; border-radius:9px; background:transparent; font-weight:500; color:var(--dim); }
  .seg button.sel { background:#2c2c34; color:var(--text); }
  .countdown { font-size:34px; font-weight:700; letter-spacing:-.02em; font-variant-numeric:tabular-nums; }
  .head { display:flex; align-items:center; justify-content:space-between; margin-bottom:12px; gap:10px; }
  .head h2 { margin:0; }
  .badge { font-size:13px; padding:4px 10px; border-radius:99px; background:#24242c; color:var(--dim); }
  .badge.locked { background:#3a1715; color:#ff8a80; }
  .count { font-size:12px; color:var(--dim); text-align:right; margin-top:6px; }
  .hint { font-size:13px; color:var(--dim); margin:10px 0 0; }
  #toast { position:fixed; left:50%; bottom:max(24px, env(safe-area-inset-bottom)); transform:translateX(-50%) translateY(20px); opacity:0;
           background:#2c2c34; border:1px solid var(--line); padding:11px 16px; border-radius:12px; font-size:15px; transition:.2s; max-width:calc(100% - 32px); pointer-events:none; z-index:9; }
  #toast.show { opacity:1; transform:translateX(-50%) translateY(0); }
  #toast.bad { border-color:var(--bad); }
  .empty { text-align:center; padding:40px 8px; color:var(--dim); }
  .empty b { color:var(--text); display:block; font-size:20px; margin-bottom:8px; }
  .offline { text-align:center; padding:22px 16px; }
  .offline b { display:block; font-size:18px; margin-bottom:4px; }
  .offline span { color:var(--dim); font-size:14px; }
  .needs-tv { transition:opacity .25s; }
  body.tv-off .needs-tv { display:none; }
  details summary { cursor:pointer; color:var(--dim); font-size:14px; }
  details[open] summary { margin-bottom:10px; }
  .switch { display:flex; align-items:center; justify-content:space-between; gap:12px; }
  .switch input { appearance:none; -webkit-appearance:none; width:51px; height:31px; padding:0; border-radius:99px; background:#39393f; border:0; position:relative; flex:0 0 auto; cursor:pointer; transition:.2s; }
  .switch input::after { content:""; position:absolute; top:2px; left:2px; width:27px; height:27px; border-radius:50%; background:#fff; transition:.2s; }
  .switch input:checked { background:var(--ok); }
  .switch input:checked::after { left:22px; }
  /* Screen time: one series, one hue (the accent), thin bars rounded at the data end. */
  .total { font-size:34px; font-weight:700; letter-spacing:-.02em; font-variant-numeric:tabular-nums; margin-top:12px; }
  .chart { position:relative; display:flex; align-items:flex-end; gap:2px; height:120px; margin-top:12px; border-bottom:1px solid var(--line); }
  .chart .b { flex:1; display:flex; align-items:flex-end; height:100%; cursor:pointer; }
  .chart .b i { display:block; width:100%; min-height:1px; background:var(--accent); border-radius:4px 4px 0 0; }
  .chart .b.sel i { background:#ffc266; }
  .axis { display:flex; justify-content:space-between; font-size:11px; color:var(--dim); margin-top:6px; }
  .tip { position:absolute; top:-6px; transform:translate(-50%,-100%); background:#2c2c34; border:1px solid var(--line); border-radius:8px; padding:4px 8px; font-size:12px; white-space:nowrap; pointer-events:none; }
  .apps { margin-top:16px; display:flex; flex-direction:column; gap:12px; }
  .app .top { display:flex; justify-content:space-between; font-size:15px; gap:10px; }
  .app .top span:last-child { color:var(--dim); font-variant-numeric:tabular-nums; }
  .app .bar { height:6px; background:#24242c; border-radius:99px; margin-top:6px; overflow:hidden; }
  .app .bar i { display:block; height:100%; background:var(--accent); border-radius:99px; }
</style>
</head>
<body class="tv-off">
<main>
  <header>
    <h1 id="title">TV Remote</h1>
    <span class="pill" id="status"><span class="dot"></span><span id="statusText">…</span></span>
  </header>

  <div id="unpaired" class="empty" hidden>
    <b>Not paired yet</b>
    On the TV, hold OK on the Wi-Fi button at the top of the Home screen and scan the QR code with this phone.
  </div>

  <div id="paired" hidden>
    <section class="offline" id="offlineCard">
      <b id="offlineTitle">Connecting to the TV…</b>
      <span id="offlineText">The controls appear as soon as the TV answers.</span>
    </section>

    <section class="needs-tv">
      <h2>Message on the TV</h2>
      <textarea id="text" maxlength="500" placeholder="Dinner is ready!"></textarea>
      <div class="count"><span id="count">0</span>/500</div>
      <div class="row">
        <button class="primary" id="send">Show on TV</button>
        <button id="clear">Clear</button>
      </div>
      <p class="hint">The TV goes to Home and shows the message in large type until someone presses OK.</p>
    </section>

    <section class="needs-tv">
      <div class="head"><h2>Lock</h2><span class="badge" id="lockBadge">Unlocked</span></div>
      <div class="row" style="margin-top:0">
        <button class="danger" id="lockNow">Lock TV</button>
        <button id="unlockNow">Unlock</button>
      </div>
      <p class="hint" id="noPin" hidden>Set a 4-digit PIN in Settings below first.</p>
      <h2 style="margin-top:18px">Lock timer</h2>
      <div id="timerRun" hidden>
        <div class="countdown" id="countdown">0:00</div>
        <p class="hint" style="margin-top:4px">until the TV locks</p>
        <div class="row"><button id="timerCancel">Cancel timer</button></div>
      </div>
      <div id="timerSet">
        <div class="chips" id="chips">
          <button data-min="15">15 min</button><button data-min="30">30 min</button><button data-min="45">45 min</button>
          <button data-min="60">1 h</button><button data-min="90">1 h 30</button><button data-min="120">2 h</button>
        </div>
        <div class="row">
          <input id="minutes" type="number" inputmode="numeric" min="1" max="720" placeholder="Minutes" style="flex:1">
          <button class="primary" id="timerStart" style="flex:0 0 auto">Start</button>
        </div>
        <p class="hint">The TV counts down in a corner, then shows the lock screen. The PIN unlocks it.</p>
      </div>
    </section>

    <section class="needs-tv">
      <div class="head">
        <h2>Screen time</h2>
        <div class="seg" id="range"><button data-r="today" class="sel">Today</button><button data-r="7d">7 days</button></div>
      </div>
      <div id="stBody"><p class="hint" style="margin:0">Loading…</p></div>
    </section>

    <section class="needs-tv">
      <h2>Power</h2>
      <div id="ipSetup" hidden style="margin-bottom:14px">
        <p class="hint" style="margin-top:0">These buttons use the TV's IP control, which is off. On the TV: <b>Settings › Network &amp; Internet › Home network › IP control</b>: Authentication <b>Normal and Pre-Shared Key</b>, then Pre-Shared Key: type any key (for example 4 digits). Enter the same key here:</p>
        <div class="row">
          <input id="psk2" type="password" autocomplete="off" maxlength="64" placeholder="Pre-Shared Key" style="flex:1">
          <button class="primary" id="savePsk2" style="flex:0 0 auto">Save</button>
        </div>
      </div>
      <div class="grid">
        <button data-cmd="screen_off">Screen off</button>
        <button data-cmd="screen_on">Screen on</button>
        <button data-cmd="standby" class="danger">Turn off (standby)</button>
        <button data-cmd="wake">Turn on</button>
      </div>
      <p class="hint">Screen off keeps the TV running, so Screen on always works. After Turn off (standby) the TV may not hear Turn on.</p>
    </section>

    <section>
      <div class="switch">
        <div><h2 style="margin:0 0 4px">Notifications</h2><span class="hint" style="margin:0" id="notifyHint">Tell me when the TV turns on or off.</span></div>
        <input type="checkbox" id="notify" aria-label="Notify me when the TV turns on or off">
      </div>
    </section>

    <section id="install" hidden>
      <h2>Add to Home Screen</h2>
      <p class="hint" id="installHint" style="margin-top:0"></p>
      <div class="row" id="installRow" hidden><button class="primary" id="installBtn">Install app</button></div>
    </section>

    <section>
      <details>
        <summary>Settings</summary>
        <div class="needs-tv">
          <h2>PIN</h2>
          <input id="pin" type="password" inputmode="numeric" pattern="[0-9]*" maxlength="4" autocomplete="off" placeholder="4 digits">
          <div class="row"><button id="savePin">Save PIN on TV</button></div>
          <p class="hint">Unlocks the TV after Lock TV or the timer. It is also the TV's App lock PIN.</p>
          <h2 style="margin-top:20px">Sony IP control key</h2>
          <input id="psk" type="password" autocomplete="off" maxlength="64" placeholder="Pre-Shared Key">
          <div class="row"><button id="savePsk">Save on TV</button></div>
          <p class="hint">Screen off/on and standby use the TV's IP control. On the TV: Settings › Network › Home network › IP control: Authentication "Normal and Pre-Shared Key", then set the same key here.</p>
        </div>
        <div class="row"><button class="danger" id="forget">Forget this TV</button></div>
      </details>
    </section>
  </div>
</main>
<div id="toast"></div>
<script>
(() => {
  const $ = (id) => document.getElementById(id);
  const STORE = "tvremote";
  let tv = null;
  try { tv = JSON.parse(localStorage.getItem(STORE) || "null"); } catch (e) {}
  const m = /^#([A-Za-z0-9_-]{12,32})[.]([A-Za-z0-9_-]{22,64})$/.exec(location.hash);
  if (m) {
    tv = { id: m[1], key: m[2] };
    try { localStorage.setItem(STORE, JSON.stringify(tv)); } catch (e) {}
  } else if (tv) {
    history.replaceState(null, "", location.pathname + "#" + tv.id + "." + tv.key);
  }
  // The manifest's start_url carries the pairing, so Add to Home Screen makes an app that starts paired.
  if (tv) document.querySelector("link[rel=manifest]").href = "/manifest.webmanifest?p=" + tv.id + "." + tv.key;
  let swReg = null;
  if ("serviceWorker" in navigator) navigator.serviceWorker.register("/sw.js").then((r) => (swReg = r)).catch(() => {});

  let toastTimer;
  function toast(msg, bad) {
    const t = $("toast");
    t.textContent = msg;
    t.className = "show" + (bad ? " bad" : "");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => (t.className = ""), 2600);
  }

  async function post(path, extra) {
    const r = await fetch(path, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(Object.assign({ id: tv.id, key: tv.key }, extra)) });
    let j = {};
    try { j = await r.json(); } catch (e) {}
    if (r.status === 403) j.msg = "This phone is no longer paired. Scan the TV's QR code again.";
    return j;
  }

  // ---- status ---------------------------------------------------------------------------------
  let online = null, lockAt = 0, lastSeen = 0;
  function ago(t) {
    if (!t) return "";
    const s = Math.max(0, Math.round((Date.now() - t) / 1000));
    if (s < 90) return "just now";
    if (s < 5400) return Math.round(s / 60) + " min ago";
    if (s < 129600) return Math.round(s / 3600) + " h ago";
    return Math.round(s / 86400) + " days ago";
  }
  function applyStatus(st) {
    if (st.name) { $("title").textContent = st.name; document.title = st.name + " · Remote"; }
    if (st.lastSeen) lastSeen = st.lastSeen;
    setLock(st.state);
    setOnline(!!st.online, st.power);
  }
  function setOnline(on, power) {
    const was = online;
    online = on;
    $("status").className = "pill " + (on ? "on" : "off");
    $("statusText").textContent = on ? (power === "off" ? "Standby" : "Online") : "Offline";
    document.body.classList.toggle("tv-off", !on);
    $("offlineCard").hidden = on;
    if (!on) {
      $("offlineTitle").textContent = power === "off" ? "The TV is off" : "The TV is offline";
      $("offlineText").textContent = "Last seen " + (ago(lastSeen) || "a while ago") + ". The controls come back by themselves when it is on.";
    }
    if (on && was === false) { toast("The TV is back."); loadReport(); loadInfo(); }
    if (on && was === null) { loadReport(); loadInfo(); }
  }
  function setLock(st) {
    if (!st) return;
    $("lockBadge").textContent = st.locked ? "Locked" : "Unlocked";
    $("lockBadge").className = "badge" + (st.locked ? " locked" : "");
    $("noPin").hidden = !!st.hasPin;
    lockAt = st.lockAt || 0;
    drawTimer();
  }
  function drawTimer() {
    const left = lockAt ? Math.ceil((lockAt - Date.now()) / 1000) : 0;
    $("timerRun").hidden = left <= 0;
    $("timerSet").hidden = left > 0;
    if (left > 0) {
      const h = Math.floor(left / 3600), mm = Math.floor(left / 60) % 60, s = left % 60;
      $("countdown").textContent = (h ? h + ":" + String(mm).padStart(2, "0") : mm) + ":" + String(s).padStart(2, "0");
    }
  }
  setInterval(drawTimer, 1000);

  // Live status over a WebSocket; the key goes in the first message, not in the address.
  let sock = null, backoff = 1000, pinger = null;
  function connect() {
    if (sock && sock.readyState <= 1) return;
    const ws = new WebSocket((location.protocol === "https:" ? "wss://" : "ws://") + location.host + "/phone?id=" + tv.id);
    sock = ws;
    ws.onopen = () => {
      backoff = 1000;
      ws.send(JSON.stringify({ t: "auth", key: tv.key }));
      clearInterval(pinger);
      pinger = setInterval(() => { try { ws.send("ping"); } catch (e) {} }, 30000);
    };
    ws.onmessage = (e) => {
      if (e.data === "pong") return;
      let msg;
      try { msg = JSON.parse(e.data); } catch (err) { return; }
      if (msg.t === "status") applyStatus(msg);
      if (msg.t === "unpaired") toast("This phone is no longer paired. Scan the TV's QR code again.", true);
    };
    ws.onclose = () => {
      clearInterval(pinger);
      if (sock !== ws) return;
      sock = null;
      if (!document.hidden) setTimeout(connect, backoff);
      backoff = Math.min(backoff * 2, 30000);
    };
  }
  async function refresh() {
    try { const j = await post("/api/status", {}); if (j.ok) applyStatus(j); } catch (e) {}
  }

  async function run(cmd, opts, okMsg, btn) {
    if (btn) btn.disabled = true;
    try {
      const j = await post("/api/cmd", Object.assign({ cmd: cmd }, opts || {}));
      setLock(j.state);
      if (j.ok) { if (okMsg !== null) toast(j.msg || okMsg || "Done."); }
      else { toast(j.msg || j.error || "Something went wrong.", true); if (j.offline) setOnline(false); }
      return j;
    } catch (e) {
      toast("No connection.", true);
      return {};
    } finally { if (btn) btn.disabled = false; }
  }

  // What the TV has: IP control set up, and the commands it knows (an older launcher knows fewer).
  let caps = null;
  async function loadInfo() {
    const j = await run("info", {}, null);
    if (!j.ok || !j.data) return;
    caps = j.data.caps || null;
    $("ipSetup").hidden = !!j.data.ipControl;
  }
  async function savePsk(input, btn) {
    const j = await run("set_psk", { text: input.value }, "Saved on the TV.", btn);
    if (j.ok) { input.value = ""; $("ipSetup").hidden = true; }
    else loadInfo();
  }

  // ---- screen time ------------------------------------------------------------------------------
  let range = "today";
  function hm(ms) {
    const min = Math.round(ms / 60000);
    if (min < 60) return min + " min";
    return Math.floor(min / 60) + " h " + (min % 60 ? (min % 60) + " min" : "");
  }
  function el(tag, cls, text) { const e = document.createElement(tag); if (cls) e.className = cls; if (text !== undefined) e.textContent = text; return e; }
  async function loadReport() {
    if (!online) return;
    const j = await run("local", { args: { method: "GET", path: "/api/report" + (range === "7d" ? "?range=7d" : "") } }, null);
    const box = $("stBody");
    box.textContent = "";
    if (!j.ok) { box.appendChild(el("p", "hint", j.msg || "Could not load the report.")); return; }
    const r = j.data || {};
    if (!r.allowed) { box.appendChild(el("p", "hint", "The TV needs usage access for Screen time (the install script grants it).")); return; }
    box.appendChild(el("div", "total", hm(r.total || 0)));
    box.appendChild(el("p", "hint", range === "7d" ? "in the last 7 days" : "today")).style.marginTop = "2px";
    const buckets = r.buckets || [], labels = r.labels || [];
    const max = Math.max(1, ...buckets);
    const chart = el("div", "chart");
    const tip = el("div", "tip");
    tip.hidden = true;
    buckets.forEach((v, i) => {
      const b = el("div", "b");
      const bar = el("i");
      bar.style.height = Math.round((v / max) * 100) + "%";
      b.appendChild(bar);
      b.title = (labels[i] || "") + ": " + hm(v);
      b.onclick = () => {
        chart.querySelectorAll(".b").forEach((x) => x.classList.remove("sel"));
        b.classList.add("sel");
        tip.textContent = (labels[i] || "") + " · " + hm(v);
        tip.style.left = ((i + 0.5) / buckets.length * 100) + "%";
        tip.hidden = false;
      };
      chart.appendChild(b);
    });
    chart.appendChild(tip);
    box.appendChild(chart);
    if (labels.length) {
      const axis = el("div", "axis");
      [0, Math.floor((labels.length - 1) / 2), labels.length - 1].forEach((k) => axis.appendChild(el("span", "", labels[k])));
      box.appendChild(axis);
    }
    const apps = r.apps || [];
    if (apps.length) {
      const list = el("div", "apps");
      const top = Math.max(1, apps[0].ms);
      apps.forEach((a) => {
        const row = el("div", "app");
        const t = el("div", "top");
        t.appendChild(el("span", "", a.name));
        t.appendChild(el("span", "", hm(a.ms)));
        const bar = el("div", "bar");
        const fill = el("i");
        fill.style.width = Math.max(2, Math.round((a.ms / top) * 100)) + "%";
        bar.appendChild(fill);
        row.appendChild(t);
        row.appendChild(bar);
        list.appendChild(row);
      });
      box.appendChild(list);
    } else if (!r.total) {
      box.appendChild(el("p", "hint", "Nothing watched yet."));
    }
  }
  document.querySelectorAll("#range button").forEach((b) => (b.onclick = () => {
    range = b.dataset.r;
    document.querySelectorAll("#range button").forEach((x) => x.classList.toggle("sel", x === b));
    loadReport();
  }));

  // ---- notifications ---------------------------------------------------------------------------
  const standalone = matchMedia("(display-mode: standalone)").matches || navigator.standalone === true;
  const ios = /iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
  const NOTIFY = "tvremote-notify";
  function canPush() { return "serviceWorker" in navigator && "PushManager" in window && "Notification" in window; }
  function b64ToBytes(s) {
    const t = s.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((s.length + 3) % 4);
    return Uint8Array.from(atob(t), (c) => c.charCodeAt(0));
  }
  async function setupNotify() {
    if (!canPush()) {
      $("notify").disabled = true;
      $("notifyHint").textContent = ios && !standalone ? "Add this page to the Home Screen first, then switch this on there." : "This browser can't show notifications.";
      return;
    }
    let on = false;
    try { on = localStorage.getItem(NOTIFY) === "1" && Notification.permission === "granted"; } catch (e) {}
    $("notify").checked = on;
    if (Notification.permission === "denied") $("notifyHint").textContent = "Notifications are blocked for this page in the phone's settings.";
  }
  $("notify").onchange = async (e) => {
    const box = e.currentTarget;
    const reg = swReg || (await navigator.serviceWorker.ready);
    if (box.checked) {
      try {
        const perm = await Notification.requestPermission();
        if (perm !== "granted") throw new Error("Notifications were not allowed.");
        const v = await (await fetch("/api/vapid")).json();
        const sub = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: b64ToBytes(v.key) });
        const j = await post("/api/push", { sub: sub.toJSON(), test: true });
        if (!j.ok) throw new Error(j.msg || j.error || "The relay refused.");
        try { localStorage.setItem(NOTIFY, "1"); } catch (err) {}
        toast("Notifications on.");
      } catch (err) {
        box.checked = false;
        toast(err.message || "Could not switch notifications on.", true);
      }
    } else {
      try {
        const sub = await reg.pushManager.getSubscription();
        if (sub) { await post("/api/push", { unsubscribe: sub.endpoint }); await sub.unsubscribe(); }
      } catch (err) {}
      try { localStorage.removeItem(NOTIFY); } catch (err) {}
      toast("Notifications off.");
    }
  };

  // ---- start ------------------------------------------------------------------------------------
  if (!tv) { $("unpaired").hidden = false; $("status").className = "pill off"; $("statusText").textContent = "Not paired"; return; }
  $("paired").hidden = false;

  $("text").addEventListener("input", () => ($("count").textContent = $("text").value.length));
  $("send").onclick = async (e) => {
    const text = $("text").value.trim();
    if (!text) { toast("Type a message first.", true); return; }
    if ((await run("banner", { text: text }, "Shown on the TV.", e.currentTarget)).ok) { $("text").value = ""; $("count").textContent = 0; }
  };
  $("clear").onclick = (e) => run("banner_clear", {}, "Cleared.", e.currentTarget);
  document.querySelectorAll("[data-cmd]").forEach((b) => (b.onclick = () => run(b.dataset.cmd, {}, "Done.", b)));
  $("lockNow").onclick = (e) => run("lock", {}, "Locked.", e.currentTarget);
  $("unlockNow").onclick = (e) => run("unlock", {}, "Unlocked.", e.currentTarget);
  document.querySelectorAll("#chips [data-min]").forEach((b) => (b.onclick = () => run("lock_timer", { text: b.dataset.min }, "Timer started.", b)));
  $("timerStart").onclick = async (e) => {
    const mins = parseInt($("minutes").value, 10);
    if (!(mins >= 1 && mins <= 720)) { toast("1 to 720 minutes.", true); return; }
    if ((await run("lock_timer", { text: String(mins) }, "Timer started.", e.currentTarget)).ok) $("minutes").value = "";
  };
  $("timerCancel").onclick = (e) => run("lock_timer_cancel", {}, "Timer cancelled.", e.currentTarget);
  $("savePin").onclick = async (e) => {
    const pin = $("pin").value.trim();
    if (!/^[0-9]{4}$/.test(pin)) { toast("The PIN is 4 digits.", true); return; }
    if ((await run("set_pin", { text: pin }, "PIN saved.", e.currentTarget)).ok) $("pin").value = "";
  };
  $("savePsk").onclick = (e) => savePsk($("psk"), e.currentTarget);
  $("savePsk2").onclick = (e) => savePsk($("psk2"), e.currentTarget);
  $("forget").onclick = () => {
    try { localStorage.removeItem(STORE); } catch (e) {}
    location.replace(location.pathname);
  };

  if (!standalone) {
    if (ios) {
      $("installHint").textContent = "Tap Share, then Add to Home Screen, to open the remote like an app.";
      $("install").hidden = false;
    }
    let prompt = null;
    addEventListener("beforeinstallprompt", (e) => {
      e.preventDefault();
      prompt = e;
      $("installHint").textContent = "Put the remote on your home screen and open it like an app.";
      $("installRow").hidden = false;
      $("install").hidden = false;
    });
    $("installBtn").onclick = async () => {
      if (!prompt) return;
      prompt.prompt();
      await prompt.userChoice.catch(() => {});
      prompt = null;
      $("install").hidden = true;
    };
    addEventListener("appinstalled", () => ($("install").hidden = true));
  }

  setupNotify();
  connect();
  refresh();
  setInterval(() => { if (!document.hidden) { refresh(); if (online) loadReport(); } }, 60000);
  document.addEventListener("visibilitychange", () => { if (!document.hidden) { connect(); refresh(); } });
})();
</script>
</body>
</html>`;
