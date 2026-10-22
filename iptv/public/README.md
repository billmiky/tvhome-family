**Published:** World Tivi config https://trinh.uk/worldtivi/vn.json (paste this URL in World Tivi: first run ▸ Nhập URL, or Settings ▸ Kết nối config) · m3u for other players https://trinh.uk/worldtivi/vn.m3u (without the VTV/VTVgo channels: they need World Tivi's vtvgo / vtv-vn proxy scripts)

# WorldTivi public channel list

Free, legal-to-watch channels (free FAST channels and broadcasters' own free streams), ready to import.

| File | For |
|---|---|
| `worldtivi.json` | **World Tivi** only (config v2): the playlist, a fallback-link script and a TV guide script, all in one file |
| `tv.m3u` | Any other IPTV player (TiviMate, Televizo, VLC, Kodi…): the same channels as a plain m3u |

## Contents (82 channels)

News 20 · Documentary 12 · Lifestyle 18 · Cartoon 18 · Sports 2 · Việt Nam 11 · Singapore 1

- 6 channels have a backup link (`worldtivi://direct-m3u8/<main> | <backup>`): if the main one fails, World Tivi plays the backup.
- TV guide: the `xmltv-map` script takes the programme guide of 48 channels from public epgshare01 XMLTV files. The Vietnamese provincial stations have no public guide.
- Vietnamese channels: only provincial stations that stream for free from their own servers. VTV, SCTV, HTV and the channels on VTVgo are **not** included.

## Import into World Tivi

- **First run**: choose "Nhập URL" and type the URL of the **folder** that holds `worldtivi.json` (e.g. `https://example.com/worldtivi/`, not `…/worldtivi.json`).
- **Already set up**: Settings ▸ Kết nối config ▸ enter the same folder URL.
- The app downloads `worldtivi.json` again on each update, so changes to the file reach every TV.

`worldtivi.json` is in World Tivi's own format. Other apps cannot read it; give them `tv.m3u`.

## Other players

Add `tv.m3u` as the playlist (by URL or as a file). Its `url-tvg` lists the epgshare01 guide files, and its `tvg-id`s are the epgshare01 ids, so the guide matches without extra setup. The guide files are large (several MB each); if your player is slow, remove the `url-tvg`.

Links are tested when this list is built and can stop working later. They are not hosted here; each one points at the broadcaster's or FAST service's own server.
