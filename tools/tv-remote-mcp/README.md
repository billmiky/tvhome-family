# tv-remote-mcp

An MCP server that lets an AI agent (Claude Code, Claude Desktop, any MCP client) drive the TV through the TV
Remote relay. Stdio, Node 18+, one file, no dependencies, no build step.

## Key

On the TV, hold OK on Settings › **AI agents** › Create an agent (the key is shown once, as text and a QR code),
or in the phone remote, Settings › **AI agents** › Create. The key looks like `agent:<TV id>.<key>`. A new agent
may **read** (status, apps, screen time); switch **control** on for it (keys, typing, opening apps, power,
messages, lock) and, if you want, **adb** (Wireless debugging on/off and its address, never a shell) on the TV or
the phone; granting either asks for the TV's PIN. Revoke it there too: it stops working at once.

## Claude Code

```bash
claude mcp add tv-remote \
  --env RELAY_URL=https://tv-remote.<you>.workers.dev \
  --env TV_AGENT_KEY='agent:<TV id>.<key>' \
  -- node /path/to/tv-vibehack/tools/tv-remote-mcp/server.js
```

Add `--scope user` to have it in every project. Then ask, for example, "turn the volume up three steps" or
"open YouTube and search for lo-fi".

Claude Desktop and other clients take the same command in their config:

```json
{ "mcpServers": { "tv-remote": { "command": "node", "args": ["/path/to/tools/tv-remote-mcp/server.js"],
  "env": { "RELAY_URL": "https://tv-remote.<you>.workers.dev", "TV_AGENT_KEY": "agent:<TV id>.<key>" } } } }
```

## Tools

| Tool | Needs | Does |
|---|---|---|
| `tv_status` | read | online, power, lock state, name, last seen |
| `list_apps` | read | the TV's apps, label and package |
| `screen_time` | read | today or 7 days, total and per app |
| `press_key` | control | one button (`key`) or several in order (`keys`, `gap_ms`) |
| `type_text` | control | into the selected text field; `enter` submits |
| `launch_app` | control | by package or by name ("Netflix") |
| `screen_off`, `screen_on`, `standby` | control | power (screen off keeps the TV running) |
| `message` | control | large text on the TV until OK; `clear` takes it off |
| `lock`, `unlock` | control | lock now or after `minutes`; `cancel_timer` |
| `adb_wifi_status`, `adb_wifi_on`, `adb_wifi_off` | adb | Wireless debugging and its address (no shell). Anyone on the home network who is already paired can then connect |
| `tv_command` | per command | any agent command: `{cmd, text?, args?}` |

Every command, its JSON and the errors are in the relay's guide: `GET <RELAY_URL>/api/guide`. A command the key
may not use comes back as a tool error saying so; rate limits (20 at once, 60 a minute) come back with the wait.

## Test

`cd relay && npm install && npm run test:e2e` runs `test/smoke.test.js`: this server over stdio against a local
relay (`wrangler dev`) and a fake TV.
