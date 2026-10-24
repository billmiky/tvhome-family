// The phone remote: one page, no build step, installable (Add to Home Screen). Paired by the TV's QR code
// (#<id>.<key> in the address), which the page keeps in localStorage and moves to /?p=<id>.<key>: there the
// server links a manifest whose start_url is that address, since iOS starts a home-screen app at start_url
// with storage of its own (and reads the manifest before any script could change the link).
// A WebSocket to the relay (/phone) keeps the status live: controls go away while the TV is offline and
// come back the moment it is on. Commands go through run(cmd, {text, args}); the TV's building blocks
// (sony, button, intent, launch, apps, global, local, info) let new features live here alone.
// The look follows the launcher (Apple TV style): Outfit, black with a warm ambient glow, glass panels with
// a hairline border, spaced capitals for labels, white for the chosen or main action.
// (No backticks or dollar-brace in the script below: this file is one template literal.)
export const PAGE = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, viewport-fit=cover">
<meta name="theme-color" content="#000000">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
<meta name="apple-mobile-web-app-title" content="TV Remote">
<link rel="manifest" href="/manifest.webmanifest">
<link rel="icon" type="image/png" sizes="192x192" href="/icon-192.png">
<link rel="apple-touch-icon" sizes="180x180" href="/apple-touch-icon.png">
<link rel="preload" href="/outfit.ttf" as="font" type="font/ttf" crossorigin>
<title>TV Remote</title>
<style>
  @font-face { font-family:"Outfit"; src:url("/outfit.ttf") format("truetype"); font-weight:100 900; font-display:swap; }
  :root {
    --text:#fff; --dim:rgba(235,235,245,.62); --faint:rgba(235,235,245,.32);
    --glass:linear-gradient(180deg, rgba(44,44,48,.62), rgba(22,22,24,.72));
    --key:rgba(255,255,255,.08); --key-on:rgba(255,255,255,.16); --hair:rgba(255,255,255,.14);
    --ok:#34c759; --bad:#ff453a; --warn:#ff9f0a;
    --tabbar:calc(64px + env(safe-area-inset-bottom));
  }
  * { box-sizing:border-box; -webkit-tap-highlight-color:transparent; }
  [hidden] { display:none !important; }
  html, body { overflow-x:hidden; max-width:100%; -webkit-text-size-adjust:100%; text-size-adjust:100%; }
  body { margin:0; min-height:100vh; color:var(--text); background:#000;
         font:16px/1.4 "Outfit", -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
         padding:calc(env(safe-area-inset-top) + 18px) 16px calc(var(--tabbar) + 18px); }
  /* The ambient glow behind Home, here warm and soft. */
  body::before { content:""; position:fixed; inset:0; z-index:-1; pointer-events:none;
         background:radial-gradient(90% 55% at 18% -8%, rgba(255,92,38,.34), transparent 62%),
                    radial-gradient(70% 45% at 105% 8%, rgba(120,70,255,.16), transparent 60%), #000; }
  main { max-width:520px; margin:0 auto; }
  button { font:inherit; color:inherit; border:0; cursor:pointer; background:none; padding:0; }
  button:disabled { opacity:.4; }
  svg { display:block; }

  .eyebrow { font-size:12px; font-weight:500; letter-spacing:.22em; text-transform:uppercase; color:var(--dim); }
  header { margin:8px 2px 20px; }
  header .hrow { display:flex; align-items:flex-end; justify-content:space-between; gap:12px; }
  #greeting { line-height:1.5; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  header h1 { min-width:0; margin:2px 0 0; padding-bottom:4px; font-size:34px; font-weight:300; letter-spacing:-.01em; line-height:1.2; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  .status { flex:0 0 auto; display:flex; align-items:center; gap:8px; height:34px; padding:0 14px; border-radius:17px; font-size:14px; font-weight:500;
            background:var(--glass); border:1px solid var(--hair); -webkit-backdrop-filter:blur(20px); backdrop-filter:blur(20px); }
  .dot { width:8px; height:8px; border-radius:50%; background:var(--faint); }
  .status.on .dot { background:var(--ok); box-shadow:0 0 8px rgba(52,199,89,.7); } .status.off .dot { background:var(--bad); }
  .status.standby .dot { background:var(--warn); }

  .card { position:relative; background:var(--glass); border:1px solid var(--hair); border-radius:22px; padding:18px;
          margin-bottom:14px; -webkit-backdrop-filter:blur(24px) saturate(140%); backdrop-filter:blur(24px) saturate(140%); }
  .card-head { display:flex; align-items:center; justify-content:space-between; gap:10px; margin-bottom:14px; }
  .hint { font-size:13px; color:var(--dim); margin:12px 0 0; line-height:1.45; }
  .hint b { color:var(--text); font-weight:500; }

  /* Glass pills; white is the main action, like the focused pill on the TV. */
  .pill { display:inline-flex; align-items:center; justify-content:center; gap:8px; height:48px; padding:0 20px; border-radius:24px;
          background:var(--key); border:1px solid var(--hair); font-weight:500; font-size:16px; transition:background .15s, transform .1s; }
  .pill:active, .key:active { transform:scale(.97); background:var(--key-on); }
  .pill.white { background:#f5f5f7; color:#0b0b0d; border-color:transparent; }
  .pill.white:active { background:#fff; }
  .pill.red { color:#ff6961; }
  .pill.sm { height:40px; padding:0 16px; font-size:15px; }
  .pill.icon { width:48px; padding:0; }
  .row { display:flex; gap:10px; margin-top:12px; }
  .row > .pill { flex:1; }
  .row > *, .card-head > * { min-width:0; }

  textarea, input[type=text], input[type=password], input[type=number], input[type=url] {
    width:100%; background:rgba(0,0,0,.35); color:var(--text); border:1px solid var(--hair); border-radius:16px; padding:13px 15px;
    font:inherit; font-size:17px; -webkit-appearance:none; appearance:none; }
  textarea { min-height:110px; resize:none; }
  textarea:focus, input:focus { outline:none; border-color:rgba(255,255,255,.5); }
  input::placeholder, textarea::placeholder { color:var(--faint); }
  .count { font-size:12px; color:var(--faint); text-align:right; margin-top:6px; font-variant-numeric:tabular-nums; }

  /* Bottom tab bar, iOS style. */
  nav.tabs { position:fixed; left:0; right:0; bottom:0; z-index:5; display:flex; justify-content:space-around;
             padding:8px 6px calc(6px + env(safe-area-inset-bottom)); background:rgba(18,18,20,.72);
             -webkit-backdrop-filter:blur(24px) saturate(160%); backdrop-filter:blur(24px) saturate(160%); border-top:1px solid rgba(255,255,255,.08); }
  nav.tabs button { flex:1; max-width:96px; display:flex; flex-direction:column; align-items:center; gap:3px; color:var(--faint);
                    font-size:10.5px; font-weight:500; letter-spacing:.02em; padding:2px 0; }
  nav.tabs button.sel { color:var(--text); }
  body.tv-off nav.tabs button[data-tab="remote"], body.tv-off nav.tabs button[data-tab="message"],
  body.tv-off nav.tabs button[data-tab="lock"], body.tv-off nav.tabs button[data-tab="activity"] { opacity:.35; }

  .offline { text-align:center; padding:28px 18px; }
  .offline .big { width:52px; height:52px; margin:0 auto 12px; color:var(--dim); }
  .offline b { display:block; font-size:20px; font-weight:500; margin-bottom:6px; }
  .offline span { color:var(--dim); font-size:14px; }
  body.tv-off .needs-tv { display:none; }

  /* Remote */
  .screen { position:relative; aspect-ratio:16/9; background:#000; border-radius:14px; overflow:hidden; border:1px solid rgba(255,255,255,.08); }
  /* Full screen: the real Fullscreen API where the browser has it, this overlay where it doesn't (iPhone). */
  .screen.full { position:fixed; inset:0; z-index:20; aspect-ratio:auto; border-radius:0; border:0; }
  .screen .exit { position:absolute; top:max(12px, env(safe-area-inset-top)); right:12px; z-index:2; width:44px; height:44px; border-radius:22px;
                  background:rgba(0,0,0,.45); color:#fff; display:none; align-items:center; justify-content:center; }
  .screen.full .exit { display:flex; }
  .screen img { position:absolute; inset:0; width:100%; height:100%; object-fit:contain; }
  .screen .msg { position:absolute; inset:0; display:flex; align-items:center; justify-content:center; text-align:center; padding:16px; color:var(--dim); font-size:14px; }
  .remote button { touch-action:manipulation; user-select:none; -webkit-user-select:none; display:flex; align-items:center; justify-content:center; }
  .ring { position:relative; width:min(270px, 74vw); aspect-ratio:1; margin:6px auto 0; border-radius:50%;
          background:radial-gradient(circle at 50% 35%, rgba(255,255,255,.12), rgba(255,255,255,.05)); border:1px solid var(--hair); }
  .ring .arrow { position:absolute; width:34%; height:34%; border-radius:50%; color:var(--dim); }
  .ring .arrow:active { color:#fff; background:rgba(255,255,255,.06); }
  .ring .up { top:0; left:33%; } .ring .down { bottom:0; left:33%; } .ring .left { left:0; top:33%; } .ring .right { right:0; top:33%; }
  .ring .ok { position:absolute; inset:30%; border-radius:50%; background:rgba(255,255,255,.16); border:1px solid rgba(255,255,255,.18);
              font-size:17px; font-weight:500; letter-spacing:.12em; }
  .ring .ok:active { background:#f5f5f7; color:#0b0b0d; }
  .line { display:flex; align-items:center; justify-content:center; gap:14px; margin-top:20px; }
  .key { flex:0 0 auto; width:58px; height:58px; border-radius:50%; background:var(--key); border:1px solid var(--hair); transition:background .15s, transform .1s; }
  .key.big { width:74px; height:74px; }
  .key.white { background:#f5f5f7; color:#0b0b0d; border-color:transparent; }
  .rocker { flex:0 1 220px; display:flex; align-items:stretch; height:58px; border-radius:29px; background:var(--key); border:1px solid var(--hair); overflow:hidden; }
  .rocker button { flex:1; }
  .rocker button:active { background:var(--key-on); }
  .rocker button + button { border-left:1px solid rgba(255,255,255,.08); }
  .rocker .tag { align-self:center; font-size:11px; font-weight:600; letter-spacing:.2em; color:var(--faint); padding:0 6px 0 8px; }
  .power { display:grid; grid-template-columns:repeat(2, 1fr); gap:8px; }
  .power button { display:flex; flex-direction:column; align-items:center; gap:7px; padding:12px 2px 10px; border-radius:18px; background:var(--key);
                  border:1px solid var(--hair); font-size:11.5px; color:var(--dim); line-height:1.15; text-align:center; }
  .power button svg { color:var(--text); }
  .power button.red svg { color:#ff6961; }
  .power button.green { background:rgba(52,199,89,.2); border-color:rgba(52,199,89,.45); color:#fff; }
  .power button.green svg { color:#4cd964; }
  .power button:active { background:var(--key-on); }

  /* Lock */
  .lockface { display:flex; align-items:center; gap:16px; }
  .lockface .badge { width:56px; height:56px; border-radius:18px; display:flex; align-items:center; justify-content:center; background:var(--key); border:1px solid var(--hair); }
  .lockface.locked .badge { background:rgba(255,69,58,.18); color:#ff6961; border-color:rgba(255,69,58,.35); }
  .lockface .state { font-size:24px; font-weight:400; }
  .chips { display:flex; flex-wrap:wrap; gap:8px; }
  .countdown { font-size:64px; font-weight:200; letter-spacing:-.02em; line-height:1; font-variant-numeric:tabular-nums; text-align:center; margin:6px 0 4px; }

  /* Activity: one series, one hue, thin bars rounded at the data end. */
  .seg { display:flex; background:rgba(0,0,0,.35); border:1px solid var(--hair); border-radius:14px; padding:3px; gap:3px; }
  .seg button { padding:7px 14px; border-radius:11px; color:var(--dim); font-weight:500; font-size:14px; white-space:nowrap; }
  .seg button.sel { background:#f5f5f7; color:#0b0b0d; }
  .total { font-size:52px; font-weight:200; letter-spacing:-.02em; line-height:1; font-variant-numeric:tabular-nums; }
  .chart { position:relative; display:flex; align-items:flex-end; gap:2px; height:130px; margin-top:18px; border-bottom:1px solid var(--hair); }
  .chart .b { flex:1; display:flex; align-items:flex-end; height:100%; cursor:pointer; }
  .chart .b i { display:block; width:100%; min-height:1px; background:var(--warn); border-radius:4px 4px 0 0; opacity:.9; }
  .chart .b.sel i { opacity:1; background:#ffc266; }
  .axis { display:flex; justify-content:space-between; font-size:11px; color:var(--faint); margin-top:6px; }
  .tip { position:absolute; top:-6px; transform:translate(-50%,-100%); background:rgba(40,40,44,.95); border:1px solid var(--hair); border-radius:10px; padding:4px 9px; font-size:12px; white-space:nowrap; pointer-events:none; }
  .apps { margin-top:16px; display:flex; flex-direction:column; gap:2px; }
  .st-head { display:flex; align-items:flex-end; justify-content:space-between; gap:12px; }
  .st-head .eyebrow { margin-bottom:6px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  .st-head > div { min-width:0; }
  .st-head .total { font-size:clamp(34px, 11vw, 48px); white-space:nowrap; }
  .st-head .pill { flex:0 0 auto; white-space:nowrap; }
  .apps .app { display:block; width:100%; text-align:left; padding:10px 12px; margin:0 -12px; width:calc(100% + 24px); border-radius:14px; }
  .apps .app:active { background:rgba(255,255,255,.06); }
  .apps .app.sel { background:rgba(255,255,255,.1); }
  .app .top { display:flex; justify-content:space-between; font-size:15px; gap:10px; }
  .app .top span:last-child { color:var(--dim); font-variant-numeric:tabular-nums; }
  .app .bar { height:5px; background:rgba(255,255,255,.08); border-radius:99px; margin-top:7px; overflow:hidden; }
  .app .bar i { display:block; height:100%; background:var(--warn); border-radius:99px; }

  /* Settings: grouped rows. */
  .group { padding:4px 0; }
  .setting { display:flex; align-items:center; gap:14px; padding:14px 18px; }
  .setting + .setting, .setting + .sub, .sub + .setting { border-top:1px solid rgba(255,255,255,.08); }
  .setting .ic { width:34px; height:34px; border-radius:10px; display:flex; align-items:center; justify-content:center; background:var(--key); flex:0 0 auto; }
  .setting .txt { flex:1; min-width:0; }
  .setting .txt b { display:block; font-weight:500; }
  .setting .txt span { display:block; font-size:13px; color:var(--dim); overflow-wrap:anywhere; -webkit-user-select:text; user-select:text; }
  .sub { padding:0 18px 16px 66px; }
  .steps { font-size:13px; color:var(--dim); margin:4px 0 12px; padding-left:18px; }
  .steps li { margin:5px 0; } .steps b { color:var(--text); font-weight:500; }
  .switch { appearance:none; -webkit-appearance:none; width:51px; height:31px; border-radius:99px; background:rgba(120,120,128,.36); position:relative; flex:0 0 auto; cursor:pointer; transition:.2s; border:0; padding:0; }
  .switch::after { content:""; position:absolute; top:2px; left:2px; width:27px; height:27px; border-radius:50%; background:#fff; transition:.2s; box-shadow:0 2px 6px rgba(0,0,0,.3); }
  .switch:checked { background:var(--ok); } .switch:checked::after { left:22px; }

  .empty { text-align:center; padding:40px 10px; color:var(--dim); }
  .empty .big { width:64px; height:64px; margin:0 auto 16px; color:var(--text); }
  .empty b { display:block; color:var(--text); font-size:24px; font-weight:400; margin-bottom:10px; }

  #toast { position:fixed; left:50%; bottom:calc(var(--tabbar) + 14px); transform:translateX(-50%) translateY(16px); opacity:0; z-index:9;
           background:rgba(44,44,48,.92); -webkit-backdrop-filter:blur(20px); backdrop-filter:blur(20px); border:1px solid var(--hair);
           padding:11px 18px; border-radius:22px; font-size:15px; transition:.22s; max-width:calc(100% - 32px); pointer-events:none; }
  #toast.show { opacity:1; transform:translateX(-50%) translateY(0); }
  #toast.bad { border-color:rgba(255,69,58,.6); }
</style>
</head>
<body class="tv-off">
<main>
  <header>
    <div class="eyebrow" id="greeting">TV Remote</div>
    <div class="hrow">
      <h1 id="title">TV Remote</h1>
      <span class="status" id="status"><span class="dot"></span><span id="statusText">…</span></span>
    </div>
  </header>

  <div id="unpaired" class="card empty" hidden>
    <i class="big" data-i="tv"></i>
    <b>Not paired yet</b>
    On the TV, hold OK on the Wi-Fi button at the top of the Home screen and scan the QR code with this phone.
    <p style="margin:24px 0 0">Or paste the QR code's link (in the camera, hold the link and copy it):</p>
    <div class="row">
      <input id="pairLink" type="url" autocomplete="off" placeholder="https://tv-remote…/#…" style="flex:1">
      <button class="pill white" id="pairBtn" style="flex:0 0 auto">Pair</button>
    </div>
  </div>

  <div id="paired" hidden>
    <section class="card offline" id="offlineCard">
      <i class="big" data-i="tvoff"></i>
      <b id="offlineTitle">Connecting to the TV…</b>
      <span id="offlineText">The controls appear as soon as the TV answers.</span>
    </section>

    <!-- Remote -->
    <div class="tab" id="tab-remote">
      <section class="card needs-tv">
        <div class="card-head">
          <span class="eyebrow">Live view</span>
          <div style="display:flex;gap:8px">
            <button class="pill sm icon" id="liveFull" aria-label="Full screen" style="width:40px"><i data-i="expand"></i></button>
            <button class="pill sm icon" id="liveToggle" aria-label="Show or hide the live view" style="width:40px"><i data-i="eye"></i></button>
          </div>
        </div>
        <div id="liveBox">
          <div class="screen" id="liveScreen"><img id="liveImg" alt=""><div class="msg" id="liveMsg">Starting the live view…</div>
            <button class="exit" id="liveExit" aria-label="Leave full screen"><i data-i="shrink"></i></button></div>
          <p class="hint">About two pictures a second through the relay, smoother on the home network. Netflix and other protected video, HDMI and the tuner show black.</p>
        </div>
      </section>

      <section class="card needs-tv remote" style="padding:24px 16px 20px">
        <div class="ring">
          <button class="arrow up" data-key="Up" data-repeat aria-label="Up"><i data-i="up"></i></button>
          <button class="arrow left" data-key="Left" data-repeat aria-label="Left"><i data-i="left"></i></button>
          <button class="arrow right" data-key="Right" data-repeat aria-label="Right"><i data-i="right"></i></button>
          <button class="arrow down" data-key="Down" data-repeat aria-label="Down"><i data-i="down"></i></button>
          <button class="ok" id="okBtn" aria-label="OK, hold for the long press">OK</button>
        </div>
        <div class="line">
          <button class="key" data-key="Return" aria-label="Back"><i data-i="back"></i></button>
          <button class="key big" data-key="Home" aria-label="Home"><i data-i="home"></i></button>
          <button class="key white" data-key="ActionMenu|Options|SyncMenu" aria-label="Menu"><i data-i="menu"></i></button>
        </div>
        <div class="line">
          <button class="key" data-key="Mute" aria-label="Mute"><i data-i="mute"></i></button>
          <div class="rocker"><button data-key="VolumeDown" data-repeat aria-label="Volume down"><i data-i="vdown"></i></button><button data-key="VolumeUp" data-repeat aria-label="Volume up"><i data-i="vup"></i></button></div>
        </div>
        <div class="line">
          <button class="key" id="kbOpen" aria-label="Keyboard"><i data-i="kb"></i></button>
          <div class="rocker"><button data-key="ChannelDown" data-repeat aria-label="Channel down"><i data-i="minus"></i></button><span class="tag">CH</span><button data-key="ChannelUp" data-repeat aria-label="Channel up"><i data-i="plus"></i></button></div>
        </div>
      </section>

      <section class="card needs-tv" id="kb" hidden>
        <div class="card-head"><span class="eyebrow">Keyboard</span><button class="pill sm" id="kbClose">Done</button></div>
        <textarea id="kbText" placeholder="Type or paste. It appears in the TV's selected text field." autocomplete="off" autocapitalize="off" spellcheck="false"></textarea>
        <div class="row">
          <button class="pill white" id="kbEnter"><i data-i="enter"></i>Enter</button>
          <button class="pill icon" id="kbClear" aria-label="Clear"><i data-i="x"></i></button>
        </div>
        <p class="hint">Select a text field on the TV first. What you type replaces its text as you go.</p>
      </section>

      <section class="card needs-tv">
        <div class="card-head"><span class="eyebrow">Power</span></div>
        <div id="ipSetup" hidden style="margin-bottom:14px">
          <p class="hint" style="margin-top:0" id="ipSetupText">These use the TV's IP control, which isn't set up yet. On the TV:</p>
          <ol class="steps">
            <li><b>Settings › Network &amp; Internet › Remote device settings › Control remotely</b>: On.</li>
            <li><b>Settings › Network &amp; Internet › Home network › IP control › Authentication</b>: Normal and Pre-Shared Key.</li>
            <li><b>Pre-Shared Key</b> (same menu): any key, for example 4 digits.</li>
            <li>Enter the same key here.</li>
          </ol>
          <div class="row" style="margin-top:0">
            <input id="psk2" type="password" autocomplete="off" maxlength="64" placeholder="Pre-Shared Key" style="flex:1">
            <button class="pill white" id="savePsk2" style="flex:0 0 auto">Save</button>
          </div>
        </div>
        <div class="power">
          <button id="btnPicture"><i id="pictureIcon" data-i="screenoff"></i><span id="pictureLabel">Screen off</span></button>
          <button data-cmd="standby" class="red" id="btnStandby"><i data-i="power"></i>Standby</button>
          <button data-cmd="wake" class="green" id="btnWake" hidden><i data-i="power"></i>Turn on</button>
        </div>
        <p class="hint">Screen off blanks the picture but keeps the TV running, so Screen on always works. From standby the TV may not hear Turn on.</p>
      </section>
    </div>

    <!-- Message -->
    <div class="tab" id="tab-message" hidden>
      <section class="card needs-tv">
        <div class="card-head"><span class="eyebrow">Message on the TV</span></div>
        <textarea id="text" maxlength="500" placeholder="Dinner is ready!"></textarea>
        <div class="count"><span id="count">0</span>/500</div>
        <div class="row">
          <button class="pill white" id="send"><i data-i="send"></i>Show on TV</button>
          <button class="pill icon" id="clear" aria-label="Clear the message on the TV"><i data-i="x"></i></button>
        </div>
        <p class="hint">The TV goes to Home and shows the message in large type until someone presses OK. The ✕ button takes it off.</p>
      </section>
    </div>

    <!-- Lock -->
    <div class="tab" id="tab-lock" hidden>
      <section class="card needs-tv">
        <div class="lockface" id="lockFace">
          <div class="badge"><i id="lockIcon" data-i="unlock"></i></div>
          <div><div class="eyebrow">This TV is</div><div class="state" id="lockBadge">Unlocked</div></div>
        </div>
        <div class="row" style="margin-top:18px">
          <button class="pill white" id="lockNow"><i data-i="lock"></i>Lock</button>
          <button class="pill" id="unlockNow"><i data-i="unlock"></i>Unlock</button>
        </div>
        <p class="hint" id="noPin" hidden>Set a 4-digit PIN in Settings first.</p>
      </section>
      <section class="card needs-tv">
        <div class="card-head"><span class="eyebrow">Lock timer</span><i data-i="timer" style="color:var(--dim)"></i></div>
        <div id="timerRun" hidden>
          <div class="countdown" id="countdown">0:00</div>
          <p class="hint" style="text-align:center;margin-top:0">until the TV locks</p>
          <div class="row"><button class="pill" id="timerCancel">Cancel timer</button></div>
        </div>
        <div id="timerSet">
          <div class="chips" id="chips">
            <button class="pill sm" data-min="15">15 min</button><button class="pill sm" data-min="30">30 min</button><button class="pill sm" data-min="45">45 min</button>
            <button class="pill sm" data-min="60">1 h</button><button class="pill sm" data-min="90">1 h 30</button><button class="pill sm" data-min="120">2 h</button>
          </div>
          <div class="row">
            <input id="minutes" type="number" inputmode="numeric" min="1" max="720" placeholder="Minutes" style="flex:1">
            <button class="pill white" id="timerStart" style="flex:0 0 auto">Start</button>
          </div>
          <p class="hint">The TV counts down in a corner, then locks. The PIN unlocks it.</p>
        </div>
      </section>
    </div>

    <!-- Activity -->
    <div class="tab" id="tab-activity" hidden>
      <section class="card needs-tv">
        <div class="card-head">
          <span class="eyebrow">Screen time</span>
          <div class="seg" id="range"><button data-r="today" class="sel">Today</button><button data-r="7d">7 days</button></div>
        </div>
        <div id="stBody"><p class="hint" style="margin:0">Loading…</p></div>
      </section>
    </div>

    <!-- Settings -->
    <div class="tab" id="tab-settings" hidden>
      <section class="card group">
        <div class="setting">
          <span class="ic"><i data-i="bell"></i></span>
          <div class="txt"><b>Notifications</b><span id="notifyHint">When the TV turns on or off</span></div>
          <input type="checkbox" class="switch" id="notify" aria-label="Notify me when the TV turns on or off">
        </div>
        <div class="setting" id="notifyOffRow" hidden>
          <span class="ic"><i data-i="power"></i></span>
          <div class="txt"><b>When it turns off</b><span>Off: only when the TV turns on</span></div>
          <input type="checkbox" class="switch" id="notifyOff" aria-label="Also notify me when the TV turns off">
        </div>
        <div class="setting" id="install" hidden>
          <span class="ic"><i data-i="add"></i></span>
          <div class="txt"><b>Add to Home Screen</b><span id="installHint"></span></div>
          <button class="pill sm white" id="installBtn" hidden>Install</button>
        </div>
      </section>

      <section class="card group needs-tv">
        <div class="setting">
          <span class="ic"><i data-i="key"></i></span>
          <div class="txt"><b>PIN</b><span>Unlocks the TV; also its App lock PIN</span></div>
        </div>
        <div class="sub">
          <div class="row" style="margin-top:0">
            <input id="pin" type="password" inputmode="numeric" pattern="[0-9]*" maxlength="4" autocomplete="off" placeholder="4 digits" style="flex:1">
            <button class="pill white" id="savePin" style="flex:0 0 auto">Save</button>
          </div>
        </div>
        <div class="setting">
          <span class="ic"><i data-i="plug"></i></span>
          <div class="txt"><b>Sony IP control key</b><span>For Screen off/on, standby and the remote keys</span></div>
        </div>
        <div class="sub">
          <ol class="steps">
            <li><b>Settings › Network &amp; Internet › Remote device settings › Control remotely</b>: On.</li>
            <li><b>Settings › Network &amp; Internet › Home network › IP control › Authentication</b>: Normal and Pre-Shared Key.</li>
            <li><b>Pre-Shared Key</b> (same menu): any key, for example 4 digits.</li>
            <li>Enter the same key below.</li>
          </ol>
          <div class="row" style="margin-top:0">
            <input id="psk" type="password" autocomplete="off" maxlength="64" placeholder="Pre-Shared Key" style="flex:1">
            <button class="pill white" id="savePsk" style="flex:0 0 auto">Save</button>
          </div>
        </div>
      </section>

      <section class="card group needs-tv" id="adbCard" hidden>
        <div class="setting">
          <span class="ic"><i data-i="terminal"></i></span>
          <div class="txt"><b>ADB</b><span id="adbHint">Wireless debugging</span></div>
          <input type="checkbox" class="switch" id="adbSwitch" aria-label="ADB over the network">
        </div>
      </section>

      <section class="card group">
        <div class="setting">
          <span class="ic" style="color:#ff6961"><i data-i="unlink"></i></span>
          <div class="txt"><b>Forget this TV</b><span>Scan the QR code again to pair</span></div>
          <button class="pill sm red" id="forget">Forget</button>
        </div>
      </section>
    </div>
  </div>
</main>

<nav class="tabs" id="tabs" hidden>
  <button data-tab="remote" class="sel"><i data-i="dpad"></i>Remote</button>
  <button data-tab="message"><i data-i="chat"></i>Message</button>
  <button data-tab="lock"><i data-i="lock"></i>Lock</button>
  <button data-tab="activity"><i data-i="chart"></i>Activity</button>
  <button data-tab="settings"><i data-i="gear"></i>Settings</button>
</nav>
<div id="toast"></div>
<script>
(() => {
  const $ = (id) => document.getElementById(id);

  // ---- icons (stroke icons, 24 px grid) ------------------------------------------------------------
  const SPK = '<path d="M11 5 6 9H3v6h3l5 4z"/>';
  const ICONS = {
    up: '<path d="m6 15 6-6 6 6"/>', down: '<path d="m6 9 6 6 6-6"/>', left: '<path d="m15 6-6 6 6 6"/>', right: '<path d="m9 6 6 6-6 6"/>',
    back: '<path d="M19 12H5"/><path d="m11 6-6 6 6 6"/>',
    home: '<path d="M4 11.5 12 4l8 7.5V20h-5v-5h-6v5H4z"/>',
    menu: '<path d="M4 7h16M4 12h16M4 17h16"/>',
    mute: SPK + '<path d="m16 9 5 6M21 9l-5 6"/>',
    vdown: SPK + '<path d="M15.5 9.5a3.5 3.5 0 0 1 0 5"/>',
    vup: SPK + '<path d="M15.5 9.5a3.5 3.5 0 0 1 0 5"/><path d="M18.5 6.5a8 8 0 0 1 0 11"/>',
    plus: '<path d="M12 6v12M6 12h12"/>', minus: '<path d="M6 12h12"/>',
    kb: '<rect x="2" y="6" width="20" height="12" rx="2"/><path d="M6 10h.01M10 10h.01M14 10h.01M18 10h.01M7 14h10"/>',
    enter: '<path d="M20 5v6a3 3 0 0 1-3 3H5"/><path d="m9 10-4 4 4 4"/>',
    x: '<path d="M18 6 6 18M6 6l12 12"/>',
    eye: '<path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/>',
    expand: '<path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5"/>',
    shrink: '<path d="M9 4v5H4M15 4v5h5M9 20v-5H4M15 20v-5h5"/>',
    eyeoff: '<path d="M10.6 5.1A10 10 0 0 1 12 5c6.4 0 10 7 10 7a17 17 0 0 1-2.6 3.5M6.6 6.6A17 17 0 0 0 2 12s3.6 7 10 7a9.7 9.7 0 0 0 5.4-1.6"/><path d="M14.1 14.1a3 3 0 0 1-4.2-4.2M3 3l18 18"/>',
    screen: '<rect x="3" y="4" width="18" height="12" rx="2"/><path d="M8 20h8M12 16v4"/>',
    screenoff: '<path d="M7 4h12a2 2 0 0 1 2 2v8a2 2 0 0 1-1 1.7M17 16H5a2 2 0 0 1-2-2V6a2 2 0 0 1 .6-1.4"/><path d="M8 20h8M12 16v4M3 3l18 18"/>',
    power: '<path d="M12 3v9"/><path d="M6.3 7.2a8 8 0 1 0 11.4 0"/>',
    sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>',
    send: '<path d="m22 2-7 20-4-9-9-4z"/><path d="M22 2 11 13"/>',
    lock: '<rect x="4" y="11" width="16" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/>',
    unlock: '<rect x="4" y="11" width="16" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 7.8-1.2"/>',
    timer: '<circle cx="12" cy="13" r="8"/><path d="M12 9v4l2.5 2.5M9 2h6"/>',
    chart: '<path d="M4 20V10M10 20V4M16 20v-7M22 20H2"/>',
    gear: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/>',
    dpad: '<circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="3"/><path d="m12 4.5-1.2 1.4h2.4zM12 19.5l-1.2-1.4h2.4zM4.5 12l1.4-1.2v2.4zM19.5 12l-1.4-1.2v2.4z"/>',
    chat: '<path d="M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12z"/>',
    bell: '<path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9"/><path d="M10.3 21a1.9 1.9 0 0 0 3.4 0"/>',
    key: '<circle cx="7.5" cy="15.5" r="4.5"/><path d="m10.7 12.3 9.8-9.8M16 7l3 3M18.5 4.5l2 2"/>',
    plug: '<path d="M9 2v6M15 2v6M7 8h10v4a5 5 0 0 1-10 0zM12 17v5"/>',
    add: '<rect x="3" y="3" width="18" height="18" rx="4"/><path d="M12 8v8M8 12h8"/>',
    unlink: '<path d="M9 17H7A5 5 0 0 1 7 7h2M15 7h2a5 5 0 0 1 4 8M8 12h3M3 3l18 18"/>',
    tv: '<rect x="2" y="5" width="20" height="13" rx="2.5"/><path d="M8 21h8M8 2l4 3 4-3"/>',
    terminal: '<rect x="2" y="4" width="20" height="16" rx="2.5"/><path d="m7 9 3 3-3 3M13 15h4"/>',
    tvoff: '<rect x="2" y="5" width="20" height="13" rx="2.5"/><path d="M8 21h8M9 9l6 5M15 9l-6 5"/>',
  };
  function icon(e) {
    const d = ICONS[e.dataset.i];
    if (!d) return;
    const big = e.classList.contains("big");
    e.innerHTML = '<svg viewBox="0 0 24 24" width="' + (big ? "100%" : "24") + '" height="' + (big ? "100%" : "24") + '" fill="none" stroke="currentColor" stroke-width="' + (big ? 1.4 : 1.9) + '" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + d + '</svg>';
  }
  document.querySelectorAll("[data-i]").forEach(icon);
  function setIcon(e, name) { e.dataset.i = name; icon(e); }

  // ---- pairing -----------------------------------------------------------------------------------
  const STORE = "tvremote";
  let tv = null;
  try { tv = JSON.parse(localStorage.getItem(STORE) || "null"); } catch (e) {}
  function parsePairing(s) {
    const m = /([A-Za-z0-9_-]{12,32})[.]([A-Za-z0-9_-]{22,64})$/.exec(s || "");
    return m ? { id: m[1], key: m[2] } : null;
  }
  const given = parsePairing(location.hash.slice(1)) || parsePairing(new URLSearchParams(location.search).get("p"));
  if (given) {
    tv = given;
    try { localStorage.setItem(STORE, JSON.stringify(tv)); } catch (e) {}
  }
  // A paired page lives at /?p=<id>.<key>: the server then links the manifest whose start_url is that
  // address, so Add to Home Screen makes an app that opens paired (iOS gives it storage of its own).
  if (tv && location.search !== "?p=" + tv.id + "." + tv.key) {
    location.replace("/?p=" + tv.id + "." + tv.key);
    return;
  }
  let swReg = null;
  if ("serviceWorker" in navigator) navigator.serviceWorker.register("/sw.js").then((r) => (swReg = r)).catch(() => {});

  // The TV's own greeting: "Good afternoon, Mr Trịnh 👋", the name from its settings (kept for the next start).
  let greetName = "";
  try { greetName = localStorage.getItem("tvremote-greet") || ""; } catch (e) {}
  function drawGreeting() {
    const h = new Date().getHours();
    const g = h < 5 ? "Good night" : h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : h < 22 ? "Good evening" : "Good night";
    $("greeting").textContent = (greetName ? g + ", " + greetName : g) + " 👋";
  }
  drawGreeting();
  setInterval(drawGreeting, 60000);

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
    // On: no Turn on. Standby (screen off, still connected): no Standby, a green Turn on instead.
    const standby = on && power === "off";
    $("btnStandby").hidden = standby;
    $("btnWake").hidden = !standby;
    $("status").className = "status " + (on ? (power === "off" ? "standby" : "on") : "off");
    $("statusText").textContent = on ? (power === "off" ? "Standby" : "Online") : "Offline" + (lastSeen ? " · " + ago(lastSeen) : "");
    document.body.classList.toggle("tv-off", !on);
    $("offlineCard").hidden = on || tab === "settings";
    if (!on) {
      $("offlineTitle").textContent = power === "off" ? "The TV is off" : "The TV is offline";
      $("offlineText").textContent = "Last seen " + (ago(lastSeen) || "a while ago") + ". The controls come back by themselves when it is on.";
    }
    if (on && was === false) { toast("The TV is back."); loadReport(); loadInfo(); }
    if (on !== was) setLive(tab === "remote");
    if (on && was === null) { loadReport(); loadInfo(); }
  }
  function setLock(st) {
    if (!st) return;
    $("lockBadge").textContent = st.locked ? "Locked" : "Unlocked";
    $("lockFace").classList.toggle("locked", !!st.locked);
    setIcon($("lockIcon"), st.locked ? "lock" : "unlock");
    $("noPin").hidden = !!st.hasPin;
    lockAt = st.lockAt || 0;
    drawTimer();
  }
  function drawTimer() {
    const left = lockAt ? Math.ceil((lockAt - Date.now()) / 1000) : 0;
    $("timerRun").hidden = left <= 0;
    $("timerSet").hidden = left > 0;
    if (left > 0) {
      const hh = Math.floor(left / 3600), mm = Math.floor(left / 60) % 60, s = left % 60;
      $("countdown").textContent = (hh ? hh + ":" + String(mm).padStart(2, "0") : mm) + ":" + String(s).padStart(2, "0");
    }
  }
  setInterval(drawTimer, 1000);
  // Offline: keep "· 5 min ago" current. A tap on the pill checks again now.
  setInterval(() => { if (online === false) $("statusText").textContent = "Offline" + (lastSeen ? " · " + ago(lastSeen) : ""); }, 30000);

  // Live status over a WebSocket; the key goes in the first message, not in the address.
  let sock = null, backoff = 1000, pinger = null;
  function connect() {
    if (sock && sock.readyState <= 1) return;
    const ws = new WebSocket((location.protocol === "https:" ? "wss://" : "ws://") + location.host + "/phone?id=" + tv.id);
    sock = ws;
    ws.onopen = () => {
      backoff = 1000;
      ws.send(JSON.stringify({ t: "auth", key: tv.key }));
      if (liveOn) ws.send(JSON.stringify({ t: "live", on: true, fps: LIVE_FPS }));
      clearInterval(pinger);
      pinger = setInterval(() => { try { ws.send("ping"); } catch (e) {} }, 30000);
    };
    ws.binaryType = "blob";
    ws.onmessage = (e) => {
      if (typeof e.data !== "string") { showFrame(e.data); return; }
      if (e.data === "pong") return;
      let msg;
      try { msg = JSON.parse(e.data); } catch (err) { return; }
      if (msg.t === "status") applyStatus(msg);
      if (msg.t === "live_error") $("liveMsg").textContent = msg.msg || "No picture.";
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
    if (j.data.ipControl) loadPicture();
    $("ipSetupText").textContent = j.data.ipKeySaved
      ? "The TV refuses the saved IP control key. Check it on the TV and enter it again:"
      : "These use the TV's IP control, which isn't set up yet. On the TV:";
    const st = await run("local", { args: { method: "GET", path: "/api/state" } }, null);
    if (st.ok && st.data && typeof st.data.greeting === "string") {
      greetName = st.data.greeting.trim();
      try { localStorage.setItem("tvremote-greet", greetName); } catch (e) {}
      drawGreeting();
    }
  }
  // Screen off/on: one button that blanks the picture or brings it back; the TV says which it is now.
  let pictureOff = false;
  function drawPicture() {
    setIcon($("pictureIcon"), pictureOff ? "screen" : "screenoff");
    $("pictureLabel").textContent = pictureOff ? "Screen on" : "Screen off";
    $("btnPicture").classList.toggle("green", pictureOff);
  }
  async function loadPicture() {
    const j = await run("sony", { args: { service: "system", method: "getPowerSavingMode", params: [], version: "1.0" } }, null);
    if (j.ok && Array.isArray(j.data) && j.data[0]) { pictureOff = j.data[0].mode === "pictureOff"; drawPicture(); }
  }
  $("btnPicture").onclick = async (e) => {
    const j = await run(pictureOff ? "screen_on" : "screen_off", {}, pictureOff ? "Screen on." : "Screen off.", e.currentTarget);
    if (j.ok) { pictureOff = !pictureOff; drawPicture(); }
  };

  async function savePsk(input, btn) {
    if (!input.value.trim()) { toast("Enter the key first.", true); return; }
    btn.disabled = true;
    const j = await post("/api/cmd", { cmd: "set_psk", text: input.value }).catch(() => ({ msg: "No connection." }));
    btn.disabled = false;
    // The TV says "refused the IP control key" for a wrong key: say it plainly.
    if (j.ok) toast(j.msg || "Saved on the TV.");
    else toast(/refuse/i.test(j.msg || "") ? "Incorrect key." : j.msg || j.error || "Something went wrong.", true);
    if (j.ok) { input.value = ""; $("ipSetup").hidden = true; }
    else loadInfo();
  }

  // ---- tabs ---------------------------------------------------------------------------------------
  let tab = "remote";
  function showTab(t) {
    tab = t;
    document.querySelectorAll("#tabs button").forEach((b) => b.classList.toggle("sel", b.dataset.tab === t));
    document.querySelectorAll(".tab").forEach((p) => (p.hidden = p.id !== "tab-" + t));
    $("offlineCard").hidden = online === true || t === "settings";
    setLive(t === "remote");
    if (t === "activity") loadReport();
    if (t === "settings") loadAdb();
    window.scrollTo(0, 0);
    try { sessionStorage.setItem("tvremote-tab", t); } catch (e) {}
  }
  document.querySelectorAll("#tabs button").forEach((b) => (b.onclick = () => showTab(b.dataset.tab)));

  // ---- live view and remote keys -------------------------------------------------------------------
  // Over the relay about 2 pictures a second; straight from the TV on the home network (the TV serves this page
  // over http there) up to 30, the TV dropping frames it can't keep up with.
  const LIVE_FPS = location.protocol === "http:" ? 30 : 2;
  let liveOn = false, frameUrl = null, lastFrameAt = 0;
  let liveShown = true;
  try { liveShown = localStorage.getItem("tvremote-live") !== "hidden"; } catch (e) {}
  function drawLiveToggle() {
    $("liveBox").hidden = !liveShown;
    setIcon($("liveToggle").firstElementChild, liveShown ? "eye" : "eyeoff");
  }
  drawLiveToggle();
  $("liveToggle").onclick = () => {
    liveShown = !liveShown;
    drawLiveToggle();
    try { localStorage.setItem("tvremote-live", liveShown ? "shown" : "hidden"); } catch (e) {}
    setLive(tab === "remote");
  };
  // Full screen: the Fullscreen API (Android, desktop), else a fixed overlay (iPhone has the API only for video).
  let fullHome = null;
  function setFull(on) {
    const el = $("liveScreen");
    if (on === el.classList.contains("full")) return;
    // Out to <body> while full: the card's backdrop-filter would make position:fixed relative to the card.
    if (on) { fullHome = document.createComment("live"); el.replaceWith(fullHome); document.body.appendChild(el); }
    else if (fullHome) { fullHome.replaceWith(el); fullHome = null; }
    el.classList.toggle("full", on);
    document.body.style.overflow = on ? "hidden" : "";
    const req = el.requestFullscreen || el.webkitRequestFullscreen;
    if (on && req) { try { const r = req.call(el); if (r && r.catch) r.catch(() => {}); } catch (e) {} }
    if (!on && (document.fullscreenElement || document.webkitFullscreenElement)) {
      try { (document.exitFullscreen || document.webkitExitFullscreen).call(document); } catch (e) {}
    }
    if (on && screen.orientation && screen.orientation.lock) screen.orientation.lock("landscape").catch(() => {});
  }
  $("liveFull").onclick = () => { if (!liveShown) { $("liveToggle").click(); } setFull(true); };
  $("liveExit").onclick = () => setFull(false);
  document.addEventListener("fullscreenchange", () => { if (!document.fullscreenElement) setFull(false); });
  document.addEventListener("webkitfullscreenchange", () => { if (!document.webkitFullscreenElement) setFull(false); });

  function setLive(on) {
    on = on && liveShown && !document.hidden && online !== false;
    if (on === liveOn) return;
    liveOn = on;
    if (on) { $("liveMsg").textContent = "Starting the live view…"; $("liveMsg").hidden = false; lastFrameAt = 0; }
    if (sock && sock.readyState === 1) sock.send(JSON.stringify({ t: "live", on: on, fps: LIVE_FPS }));
  }
  function showFrame(blob) {
    if (!liveOn) return;
    const url = URL.createObjectURL(blob);
    const img = $("liveImg");
    img.onload = () => { if (frameUrl) URL.revokeObjectURL(frameUrl); frameUrl = url; };
    img.src = url;
    lastFrameAt = Date.now();
    $("liveMsg").hidden = true;
  }
  setInterval(() => {
    if (liveOn && lastFrameAt && Date.now() - lastFrameAt > 8000) { $("liveMsg").hidden = false; $("liveMsg").textContent = "No picture from the TV right now."; }
  }, 2000);

  // One press at a time (a press waits for the last one); holding repeats where it makes sense.
  let pressing = Promise.resolve();
  function press(name) {
    if (navigator.vibrate) navigator.vibrate(8);
    pressing = pressing.then(() => run("button", { args: { name: name } }, null)).catch(() => {});
    return pressing;
  }
  document.querySelectorAll("[data-key]").forEach((b) => {
    let timer = null;
    const stop = () => { clearTimeout(timer); clearInterval(timer); timer = null; };
    b.addEventListener("pointerdown", (e) => {
      e.preventDefault();
      press(b.dataset.key);
      if (b.hasAttribute("data-repeat")) timer = setTimeout(() => (timer = setInterval(() => press(b.dataset.key), 220)), 450);
    });
    ["pointerup", "pointerleave", "pointercancel"].forEach((ev) => b.addEventListener(ev, stop));
    b.addEventListener("contextmenu", (e) => e.preventDefault());
  });

  // OK: a tap presses OK; held for 0.6 s it is the remote's long press (IRCC has no key hold, so the TV's
  // accessibility service long-clicks whatever has the focus).
  (() => {
    const b = $("okBtn");
    let timer = null, held = false;
    b.addEventListener("pointerdown", (e) => {
      e.preventDefault();
      held = false;
      timer = setTimeout(() => {
        timer = null;
        held = true;
        if (navigator.vibrate) navigator.vibrate([15, 40, 15]);
        if (caps && caps.indexOf("longpress") < 0) { toast("Holding OK needs a newer launcher on the TV.", true); return; }
        pressing = pressing.then(() => run("longpress", {}, null)).catch(() => {});
      }, 600);
    });
    const end = (tap) => {
      if (timer) { clearTimeout(timer); timer = null; if (tap && !held) press("Confirm"); }
    };
    b.addEventListener("pointerup", () => end(true));
    b.addEventListener("pointerleave", () => end(false));
    b.addEventListener("pointercancel", () => end(false));
    b.addEventListener("contextmenu", (e) => e.preventDefault());
  })();

  // Keyboard: the whole text goes to the TV's focused field as it is typed (a pause of 350 ms).
  let typeTimer = null, typing = Promise.resolve();
  function sendText(enter) {
    const text = $("kbText").value;
    typing = typing.then(() => run("type", { args: { text: text, enter: !!enter } }, enter ? "Sent." : null)).catch(() => {});
  }
  $("kbOpen").onclick = () => { $("kb").hidden = false; $("kbText").focus(); $("kb").scrollIntoView({ block: "start", behavior: "smooth" }); };
  $("kbClose").onclick = () => { $("kb").hidden = true; $("kbText").blur(); };
  $("kbText").addEventListener("input", () => { clearTimeout(typeTimer); typeTimer = setTimeout(() => sendText(false), 350); });
  $("kbEnter").onclick = () => { clearTimeout(typeTimer); sendText(true); };
  $("kbClear").onclick = () => { $("kbText").value = ""; clearTimeout(typeTimer); sendText(false); $("kbText").focus(); };

  // ---- screen time ------------------------------------------------------------------------------
  let range = "today";
  function hm(ms) {
    const min = Math.round(ms / 60000);
    if (min < 60) return min + " min";
    return Math.floor(min / 60) + " h" + (min % 60 ? " " + (min % 60) + " min" : "");
  }
  function el(tag, cls, text) { const e = document.createElement(tag); if (cls) e.className = cls; if (text !== undefined) e.textContent = text; return e; }
  // The report: the total and its chart, or one app's when its row is tapped (as on the TV).
  let report = null, picked = null;
  async function loadReport() {
    if (!online || tab !== "activity") return;
    const j = await run("local", { args: { method: "GET", path: "/api/report" + (range === "7d" ? "?range=7d" : "") } }, null);
    const box = $("stBody");
    if (!j.ok) { box.textContent = ""; box.appendChild(el("p", "hint", j.msg || "Could not load the report.")); return; }
    report = j.data || {};
    if (picked && !(report.apps || []).some((a) => a.name === picked)) picked = null;
    drawReport();
  }
  function drawReport() {
    const box = $("stBody");
    box.textContent = "";
    const r = report || {};
    if (!r.allowed) { box.appendChild(el("p", "hint", "The TV needs usage access for Screen time (the install script grants it).")); return; }
    const apps = r.apps || [];
    const app = picked ? apps.find((a) => a.name === picked) : null;
    const total = app ? app.ms : r.total || 0;
    const buckets = app ? app.buckets || [] : r.buckets || [];
    const labels = r.labels || [];

    const head = el("div", "st-head");
    const left = el("div");
    left.appendChild(el("div", "eyebrow", app ? app.name : "All apps"));
    left.appendChild(el("div", "total", hm(total)));
    head.appendChild(left);
    if (app) {
      const back = el("button", "pill sm", "All apps");
      back.onclick = () => { picked = null; drawReport(); };
      head.appendChild(back);
    }
    box.appendChild(head);
    box.appendChild(el("p", "hint", range === "7d" ? "in the last 7 days" : "today")).style.marginTop = "6px";

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
    if (apps.length) {
      const list = el("div", "apps");
      const top = Math.max(1, apps[0].ms);
      apps.forEach((a) => {
        const row = el("button", "app" + (a.name === picked ? " sel" : ""));
        const t = el("div", "top");
        t.appendChild(el("span", "", a.name));
        t.appendChild(el("span", "", hm(a.ms)));
        const bar = el("div", "bar");
        const fill = el("i");
        fill.style.width = Math.max(2, Math.round((a.ms / top) * 100)) + "%";
        bar.appendChild(fill);
        row.appendChild(t);
        row.appendChild(bar);
        row.onclick = () => { picked = a.name === picked ? null : a.name; drawReport(); };
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

  // ---- ADB (Wireless debugging) ----------------------------------------------------------------------
  function drawAdb(d) {
    $("adbSwitch").checked = !!d.wifi;
    $("adbHint").textContent = d.wifi
      ? (d.ip && d.port ? "adb connect " + d.ip + ":" + d.port : "Wireless debugging on" + (d.ip ? " · " + d.ip : ""))
      : d.tcp5555 && d.ip ? "Off · classic ADB on " + d.ip + ":5555" : "Wireless debugging off";
  }
  async function loadAdb() {
    if (!online) return;
    if (caps && caps.indexOf("adb") < 0) { $("adbCard").hidden = true; return; }
    const j = await run("adb", { args: { action: "status" } }, null);
    $("adbCard").hidden = !j.ok;
    if (j.ok && j.data) drawAdb(j.data);
  }
  $("adbSwitch").onchange = async (e) => {
    const box = e.currentTarget;
    box.disabled = true;
    $("adbHint").textContent = box.checked ? "Switching on…" : "Switching off…";
    const j = await run("adb", { args: { action: box.checked ? "on" : "off" } }, box.checked ? "ADB on." : "ADB off.");
    box.disabled = false;
    if (j.ok && j.data) drawAdb(j.data); else loadAdb();
  };

  // ---- notifications ---------------------------------------------------------------------------
  const standalone = matchMedia("(display-mode: standalone)").matches || navigator.standalone === true;
  const ios = /iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
  const NOTIFY = "tvremote-notify";
  const NOTIFY_OFF = "tvremote-notify-off";
  function notifyOff() {
    try { return localStorage.getItem(NOTIFY_OFF) !== "0"; } catch (e) { return true; }
  }
  function showNotifyOff(on) {
    $("notifyOffRow").hidden = !on;
    $("notifyOff").checked = notifyOff();
  }
  function canPush() { return "serviceWorker" in navigator && "PushManager" in window && "Notification" in window; }
  function b64ToBytes(s) {
    const t = s.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((s.length + 3) % 4);
    return Uint8Array.from(atob(t), (c) => c.charCodeAt(0));
  }
  async function setupNotify() {
    if (!canPush()) {
      $("notify").disabled = true;
      $("notifyHint").textContent = ios && !standalone ? "Add to Home Screen first, then switch this on there" : "This browser can't show notifications";
      return;
    }
    let on = false;
    try { on = localStorage.getItem(NOTIFY) === "1" && Notification.permission === "granted"; } catch (e) {}
    $("notify").checked = on;
    showNotifyOff(on);
    if (Notification.permission === "denied") $("notifyHint").textContent = "Blocked in the phone's settings";
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
        const j = await post("/api/push", { sub: sub.toJSON(), test: true, off: notifyOff() });
        if (!j.ok) throw new Error(j.msg || j.error || "The relay refused.");
        try { localStorage.setItem(NOTIFY, "1"); } catch (err) {}
        showNotifyOff(true);
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
      showNotifyOff(false);
      toast("Notifications off.");
    }
  };
  $("notifyOff").onchange = async (e) => {
    const box = e.currentTarget;
    try {
      const reg = swReg || (await navigator.serviceWorker.ready);
      const sub = await reg.pushManager.getSubscription();
      if (!sub) throw new Error("Switch notifications on first.");
      const j = await post("/api/push", { endpoint: sub.endpoint, off: box.checked });
      if (!j.ok) throw new Error(j.msg || j.error || "The relay refused.");
      try { localStorage.setItem(NOTIFY_OFF, box.checked ? "1" : "0"); } catch (err) {}
      toast(box.checked ? "You will be told when the TV turns on or off." : "Only when the TV turns on.");
    } catch (err) {
      box.checked = !box.checked;
      toast(err.message || "Could not change it.", true);
    }
  };

  // ---- start ------------------------------------------------------------------------------------
  $("pairBtn").onclick = () => {
    const t = parsePairing($("pairLink").value.trim());
    if (!t) { toast("That is not the TV's pairing link.", true); return; }
    try { localStorage.setItem(STORE, JSON.stringify(t)); } catch (e) {}
    location.replace("/?p=" + t.id + "." + t.key);
  };
  if (!tv) { $("unpaired").hidden = false; $("status").className = "status off"; $("statusText").textContent = "Not paired"; return; }
  $("paired").hidden = false;
  $("tabs").hidden = false;

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
      $("installHint").textContent = "Share › Add to Home Screen opens it like an app";
      $("install").hidden = false;
    }
    let prompt = null;
    addEventListener("beforeinstallprompt", (e) => {
      e.preventDefault();
      prompt = e;
      $("installHint").textContent = "Open the remote like an app";
      $("installBtn").hidden = false;
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

  $("status").style.cursor = "pointer";
  $("status").onclick = () => { $("statusText").textContent = "Checking…"; connect(); refresh(); };

  setupNotify();
  connect();
  refresh();
  setInterval(() => { if (!document.hidden) { refresh(); loadReport(); } }, 60000);
  document.addEventListener("visibilitychange", () => {
    if (!document.hidden) { connect(); refresh(); }
    setLive(tab === "remote");
  });
  let start = "remote";
  try { start = sessionStorage.getItem("tvremote-tab") || "remote"; } catch (e) {}
  showTab(["remote", "message", "lock", "activity", "settings"].includes(start) ? start : "remote");
})();
</script>
</body>
</html>`;
