# TV Remote relay

A Cloudflare Worker with one Durable Object per TV, so a phone can reach the launcher from outside the home
network. The launcher (`RemoteRelay.java`) keeps a WebSocket open to `/tv`; the phone page at `/` (an
installable web app) posts commands to `/api/cmd`.

- **Pairing**: hold OK on the network pill on the TV and scan the QR code. It holds
  `https://<relay>/#<TV id>.<phone key>`. The TV sends the relay only the SHA-256 of the phone key; the first
  connection for a TV id claims it (the TV key's hash is stored), later ones must use the same key.
  "New code" in that menu makes a new phone key, which unpairs every phone.
- **Commands** pass through the relay by name (`{cmd, text?, args?}`), so a new feature usually needs only
  `src/page.js`. Features: `banner`, `banner_clear`, `lock`, `unlock`, `lock_timer` (minutes),
  `lock_timer_cancel`, `set_pin` (4 digits, shared with App lock), `screen_off`, `screen_on`, `standby`,
  `wake`, `set_psk`, `ping`. Building blocks: `sony` {service, method, params, version} (any IP control
  method), `button` {name | names[], gapMs} (any remote button, e.g. VolumeUp, Netflix, Num1), `buttons`,
  `intent` {action, data, type, package, component, categories, extras}, `launch` {package}, `apps`,
  `global` {action: back|home|recents|notifications|quick_settings|lock_screen}, `local` {method, path, body}
  (the home-network phone page's `/api/…`, e.g. `/api/report?range=7d`), `screen_time` {range: today|7d}, `info`, `caps`, `agents` {action: list|create|revoke|set} (phones only). The answer's
  `data` carries any result.
- **Live status**: phones open `/phone?id=…` and send `{t:"auth", key}`; the relay pushes
  `{t:"status", online, power, state, name, lastSeen}` whenever something changes.
- **Notifications**: Web Push (VAPID keys in the Worker secrets `VAPID_PRIVATE`/`VAPID_PUBLIC`, a copy in
  `~/.config/tv-remote-vapid.json`). "On" when the TV says hello, "off" on its screen-off event or after two
  minutes without a connection. On iPhone only from the Home Screen app.
- **Power**: screen off/on, standby and wake use the TV's Sony IP control (Settings › Network & Internet › Remote device settings › Control remotely On, then Home network ›
  IP control, Authentication "Normal and Pre-Shared Key"); save the same key from the phone page. Without
  a key, standby falls back to the launcher's accessibility service. Wake from standby is best effort: the
  TV usually stops the launcher's connection in standby.
- **AI agents** (`src/agents.js`): `POST /api/cmd` and `/api/status` also take
  `Authorization: Bearer agent:<TV id>.<key>`. The TV makes agent keys (Settings › AI agents) or the phone page
  makes one and hands the TV only its SHA-256; the TV sends the relay `[{h, read, control}]` in its hello and
  again (`{t:"agents"}`) on every create, revoke or permission change, so a revoked key gets 403 at once and an
  agent's status socket (`/phone`, `{t:"auth", agent}`) is closed with 4003. The relay forwards only the agent
  commands listed in `agents.js` (never `set_pin`, `set_psk`, `adb`, `sony`, `intent`, `local`, `agents`), adds
  the key hash, and the TV enforces **read** (status, info, caps, apps, buttons, `screen_time`; on by default)
  and **control** (button, longpress, global, type, launch, power, banner, lock; when granted), logging each
  command (Settings › AI agents › Agent log, and the phone page). A refusal is 403 `{denied:true}`. Rate limit
  per key: 20 at once, 60 a minute (429 with Retry-After); 20 wrong keys a minute lock agents out of that TV
  for the minute. `GET /api/guide` is the markdown guide for agents, written from the same list. An MCP server
  is in [`tools/tv-remote-mcp`](../tools/tv-remote-mcp).
- **Stored**: TV key hash, phone key hashes, agent key hashes and permissions (and the last 20 revoked hashes),
  push subscriptions, TV name, power and lock state, last seen.
- **Tests**: `npm install`, then `npm test` (agent rules, the guide, the launcher's lists match, the page script
  parses without backticks or dollar-brace) and `npm run test:e2e` (a local `wrangler dev` with a fake TV: agent
  auth, revoke, rate limits, `/api/guide`, and the MCP server's smoke test).

Deploy (personal account): `npx wrangler deploy`. The launcher's relay address is `remote_relay` in
`launcher/app/src/main/res/values/config.xml` (or `remote_relay` in a theme's `config.json`); empty turns
the feature off. The icons are drawn by `python3 icons/make-icons.py`, which rewrites `src/icons.js`.
