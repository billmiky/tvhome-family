// The phone remote: one page, no build step, installable (Add to Home Screen). Paired by the TV's QR code
// (#<id>.<key> in the address), which the page keeps in localStorage and in the address: iOS starts a
// home-screen app at the address it was added from, with storage of its own.
export const PAGE = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="theme-color" content="#0b0b0f">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
<meta name="apple-mobile-web-app-title" content="TV Remote">
<link rel="manifest" href="/manifest.webmanifest">
<link rel="icon" type="image/png" sizes="192x192" href="/icon-192.png">
<link rel="apple-touch-icon" href="/apple-touch-icon.png">
<title>TV Remote</title>
<style>
  :root { --bg:#0b0b0f; --card:#17171d; --line:#2a2a33; --text:#f4f4f6; --dim:#9a9aa6; --accent:#ff9f0a; --ok:#30d158; --bad:#ff453a; }
  * { box-sizing:border-box; -webkit-tap-highlight-color:transparent; }
  body { margin:0; background:var(--bg); color:var(--text); font:16px/1.4 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
         padding:max(16px, env(safe-area-inset-top)) 16px max(24px, env(safe-area-inset-bottom)); }
  main { max-width:520px; margin:0 auto; }
  header { display:flex; align-items:center; justify-content:space-between; margin:4px 0 18px; }
  h1 { font-size:26px; margin:0; letter-spacing:-.02em; }
  .pill { font-size:13px; color:var(--dim); background:var(--card); border:1px solid var(--line); border-radius:99px; padding:5px 11px; display:flex; gap:7px; align-items:center; }
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
  .chips button.sel { background:var(--accent); color:#1a1000; }
  .countdown { font-size:34px; font-weight:700; letter-spacing:-.02em; font-variant-numeric:tabular-nums; }
  .lockstate { display:flex; align-items:center; justify-content:space-between; margin-bottom:12px; }
  .badge { font-size:13px; padding:4px 10px; border-radius:99px; background:#24242c; color:var(--dim); }
  .badge.locked { background:#3a1715; color:#ff8a80; }
  .count { font-size:12px; color:var(--dim); text-align:right; margin-top:6px; }
  .hint { font-size:13px; color:var(--dim); margin:10px 0 0; }
  #toast { position:fixed; left:50%; bottom:max(24px, env(safe-area-inset-bottom)); transform:translateX(-50%) translateY(20px); opacity:0;
           background:#2c2c34; border:1px solid var(--line); padding:11px 16px; border-radius:12px; font-size:15px; transition:.2s; max-width:calc(100% - 32px); pointer-events:none; }
  #toast.show { opacity:1; transform:translateX(-50%) translateY(0); }
  #toast.bad { border-color:var(--bad); }
  .empty { text-align:center; padding:40px 8px; color:var(--dim); }
  .empty b { color:var(--text); display:block; font-size:20px; margin-bottom:8px; }
  details summary { cursor:pointer; color:var(--dim); font-size:14px; }
  details[open] summary { margin-bottom:10px; }
</style>
</head>
<body>
<main>
  <header>
    <h1 id="title">TV Remote</h1>
    <span class="pill" id="status"><span class="dot"></span><span id="statusText">…</span></span>
  </header>

  <div id="unpaired" class="empty" hidden>
    <b>Not paired yet</b>
    On the TV, hold <em>Settings</em> on the Home screen, choose <em>Remote Control</em> and scan the QR code with this phone.
  </div>

  <div id="paired" hidden>
    <section>
      <h2>Message on the TV</h2>
      <textarea id="text" maxlength="500" placeholder="Dinner is ready!"></textarea>
      <div class="count"><span id="count">0</span>/500</div>
      <div class="row">
        <button class="primary" id="send">Show on TV</button>
        <button id="clear">Clear</button>
      </div>
      <p class="hint">The TV goes to Home and shows the message in large type until someone presses OK.</p>
    </section>

    <section>
      <div class="lockstate"><h2 style="margin:0">Lock</h2><span class="badge" id="lockBadge">Unlocked</span></div>
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

    <section>
      <h2>Power</h2>
      <div class="grid">
        <button data-cmd="screen_off">Screen off</button>
        <button data-cmd="screen_on">Screen on</button>
        <button data-cmd="standby" class="danger">Turn off (standby)</button>
        <button data-cmd="wake">Turn on</button>
      </div>
      <p class="hint">Screen off keeps the TV running, so Screen on always works. After Turn off (standby) the TV may not hear Turn on.</p>
    </section>

    <section id="install" hidden>
      <h2>Add to Home Screen</h2>
      <p class="hint" id="installHint" style="margin-top:0"></p>
      <div class="row" id="installRow" hidden><button class="primary" id="installBtn">Install app</button></div>
    </section>

    <section>
      <details>
        <summary>Settings</summary>
        <h2>PIN</h2>
        <input id="pin" type="password" inputmode="numeric" pattern="[0-9]*" maxlength="4" autocomplete="off" placeholder="4 digits">
        <div class="row"><button id="savePin">Save PIN on TV</button></div>
        <p class="hint" id="pinState">Unlocks the TV after Lock TV or the timer. It is also the TV's App lock PIN.</p>
        <h2 style="margin-top:20px">Sony IP control key</h2>
        <input id="psk" type="password" autocomplete="off" maxlength="64" placeholder="Pre-Shared Key">
        <div class="row"><button id="savePsk">Save on TV</button></div>
        <p class="hint">Screen off/on and standby use the TV's IP control. On the TV: Settings › Network › Home network › IP control: Authentication "Normal and Pre-Shared Key", then set the same key here.</p>
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
  const m = /^#([A-Za-z0-9_-]{12,32})\\.([A-Za-z0-9_-]{22,64})$/.exec(location.hash);
  if (m) {
    tv = { id: m[1], key: m[2] };
    try { localStorage.setItem(STORE, JSON.stringify(tv)); } catch (e) {}
  } else if (tv) {
    history.replaceState(null, "", location.pathname + "#" + tv.id + "." + tv.key);
  }
  if ("serviceWorker" in navigator) navigator.serviceWorker.register("/sw.js").catch(() => {});

  let toastTimer;
  function toast(msg, bad) {
    const t = $("toast");
    t.textContent = msg;
    t.className = "show" + (bad ? " bad" : "");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => (t.className = ""), 2600);
  }

  async function post(path, extra) {
    const r = await fetch(path, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ id: tv.id, key: tv.key, ...extra }) });
    let j = {};
    try { j = await r.json(); } catch (e) {}
    if (r.status === 403) j.msg = "This phone is no longer paired. Scan the TV's QR code again.";
    return j;
  }

  let lockAt = 0;
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
      const h = Math.floor(left / 3600), m = Math.floor(left / 60) % 60, s = left % 60;
      $("countdown").textContent = (h ? h + ":" + String(m).padStart(2, "0") : m) + ":" + String(s).padStart(2, "0");
    }
  }
  setInterval(drawTimer, 1000);

  function setStatus(online, name) {
    $("status").className = "pill " + (online ? "on" : "off");
    $("statusText").textContent = online ? "Online" : "Offline";
    if (name) { $("title").textContent = name; document.title = name + " · Remote"; }
  }

  async function refresh() {
    try {
      const j = await post("/api/status", {});
      setStatus(!!j.online, j.name);
      setLock(j.state);
    } catch (e) { setStatus(false); }
  }

  async function run(cmd, extra, okMsg, btn) {
    if (btn) btn.disabled = true;
    try {
      const j = await post("/api/cmd", { cmd, ...extra });
      setLock(j.state);
      if (j.ok) { toast(j.msg || okMsg); setStatus(true); }
      else { toast(j.msg || j.error || "Something went wrong.", true); if (j.offline) setStatus(false); }
      return j.ok;
    } catch (e) {
      toast("No connection.", true);
      return false;
    } finally { if (btn) btn.disabled = false; }
  }

  if (!tv) { $("unpaired").hidden = false; setStatus(false); $("statusText").textContent = "Not paired"; return; }
  $("paired").hidden = false;

  $("text").addEventListener("input", () => ($("count").textContent = $("text").value.length));
  $("send").onclick = async (e) => {
    const text = $("text").value.trim();
    if (!text) { toast("Type a message first.", true); return; }
    if (await run("banner", { text }, "Shown on the TV.", e.currentTarget)) { $("text").value = ""; $("count").textContent = 0; }
  };
  $("clear").onclick = (e) => run("banner_clear", {}, "Cleared.", e.currentTarget);
  document.querySelectorAll("[data-cmd]").forEach((b) => (b.onclick = () => run(b.dataset.cmd, {}, "Done.", b)));
  $("lockNow").onclick = (e) => run("lock", {}, "Locked.", e.currentTarget);
  $("unlockNow").onclick = (e) => run("unlock", {}, "Unlocked.", e.currentTarget);
  document.querySelectorAll("#chips [data-min]").forEach((b) => (b.onclick = () => run("lock_timer", { text: b.dataset.min }, "Timer started.", b)));
  $("timerStart").onclick = (e) => {
    const m = parseInt($("minutes").value, 10);
    if (!(m >= 1 && m <= 720)) { toast("1 to 720 minutes.", true); return; }
    run("lock_timer", { text: String(m) }, "Timer started.", e.currentTarget).then((ok) => ok && ($("minutes").value = ""));
  };
  $("timerCancel").onclick = (e) => run("lock_timer_cancel", {}, "Timer cancelled.", e.currentTarget);
  $("savePin").onclick = (e) => {
    const pin = $("pin").value.trim();
    if (!/^[0-9]{4}$/.test(pin)) { toast("The PIN is 4 digits.", true); return; }
    run("set_pin", { text: pin }, "PIN saved.", e.currentTarget).then((ok) => ok && ($("pin").value = ""));
  };
  $("savePsk").onclick = (e) => run("set_psk", { text: $("psk").value }, "Saved on the TV.", e.currentTarget).then((ok) => ok && ($("psk").value = ""));
  $("forget").onclick = () => {
    try { localStorage.removeItem(STORE); } catch (e) {}
    location.reload();
  };

  const standalone = matchMedia("(display-mode: standalone)").matches || navigator.standalone === true;
  if (!standalone) {
    const ios = /iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
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

  refresh();
  setInterval(() => { if (!document.hidden) refresh(); }, 30000);
  document.addEventListener("visibilitychange", () => { if (!document.hidden) refresh(); });
})();
</script>
</body>
</html>`;
