import re, sys, subprocess
from concurrent.futures import ThreadPoolExecutor

SRC = sys.argv[1]   # dir with vn/sg/us/fr .m3u
OUT = sys.argv[2]

def parse(path):
    lines = open(path, encoding="utf-8").read().splitlines()
    out, cur = [], None
    for l in lines:
        if l.startswith("#EXTINF"):
            cur = {"inf": l}
        elif l.startswith("#") or not l.strip():
            continue
        elif cur:
            cur["url"] = l.strip()
            inf = cur["inf"]
            cur["name"] = inf.rsplit(",", 1)[1].strip()
            m = re.search(r'group-title="([^"]*)"', inf); cur["group"] = m.group(1) if m else ""
            m = re.search(r'tvg-id="([^"]*)"', inf); cur["id"] = m.group(1) if m else ""
            m = re.search(r'tvg-logo="([^"]*)"', inf); cur["logo"] = m.group(1) if m else ""
            m = re.search(r"\((\d+)p\)", cur["name"]); cur["res"] = int(m.group(1)) if m else 0
            out.append(cur); cur = None
    return out

src = {c: parse(f"{SRC}/{c}.m3u") for c in ("vn", "sg", "us", "fr")}
intl = src["us"] + src["fr"]

def bad_tag(e): return "[Not 24/7]" in e["name"] or "[Geo-blocked]" in e["name"]
def hq(e): return e["res"] >= 720

NEWS = re.compile(r"^(CNN( HD)? |CNN Originals|CBS News (24/7|Philly)|ABC News Live 1 |NBC News NOW|Fox News Channel|LiveNOW from FOX|"
                  r"Bloomberg TV \(|Yahoo! Finance|Reuters TV|Scripps News|Cheddar News|France 24 English|Euronews English|Fox Weather|CNBC)")
DOCS = re.compile(r"^(BBC Earth|PBS Nature|Bloomberg Originals \()")
LIFE_EXTRA = re.compile(r"^(BBC Food|BBC Home|TV5MONDE Chefs|TV5Monde Style)")
KIDS_EXTRA = re.compile(r"^(Disney Jr\.|Nickelodeon Junior|PBS Kids \()")

# niche / non-Vietnamese / regional duplicates
DROP = re.compile(r"^(Lao |Lao-Thai|Uniquely Thai|Hmong|CNN en Espanol|BabyFirst Spanish|S Play Event|TG Junior|Brat TV|"
                  r"PBS Kids (Alaska|Hawaii|Mountain|Pacific|Eastern)|Nick Jr\. Pluto|Nickelodeon Pluto|"
                  r"30A |Beach TV|Duck Hunting|Biz TV|KNOV-CD|Palm Beaches|Pocono|Fun Roads|Waypoint|Kaloopy|Smart LifeStyle|"
                  r"INFAST|AKC TV|CinePride|Revry|Aspire TV|Outdoor America|PursuitUP|Destination America|Journy|Hungry|NBC LX|Localish|"
                  r"Backstage|Love Pets|Unidentified|Court TV)")

picked = []
def add(cat, e):
    if not DROP.search(e["name"]): picked.append(dict(e, cat=cat))

for e in src["vn"]:
    if "[Not 24/7]" not in e["name"]: add("Vietnam", e)
for e in src["sg"]:
    if "[Not 24/7]" not in e["name"]: add("Singapore", e)
for e in intl:
    if bad_tag(e) or not hq(e): continue
    g, n = e["group"], e["name"]
    if NEWS.search(n): add("News", e)
    elif KIDS_EXTRA.search(n) or (("Kids" in g or "Animation" in g) and "Religious" not in g): add("Cartoon", e)
    elif LIFE_EXTRA.search(n) or any(k in g for k in ("Lifestyle", "Cooking", "Travel", "Outdoor")) and "Shop" not in g: add("Lifestyle", e)
    elif DOCS.search(n) or (g == "Documentary" and e["res"] >= 1080): add("Documentary", e)

FASHION = [
    ("FashionTV Paris HD", "https://edge-fast3.evrideo.tv/bfdbb576-83f7-11f0-9f89-0200170e3e04_1000028043_HLS/manifest.m3u8", "https://i.imgur.com/GFTAN5Q.png"),
    ("FashionTV HD", "http://fashionlive.mediacdn.ru/cdn/fashiontv/playlist.m3u8", ""),
    ("Fashion TV HD 2", "http://stream.mcquack.net/287/index.m3u8", ""),
]
for n, u, logo in FASHION:
    add("Lifestyle", {"name": n, "url": u, "logo": logo, "id": "", "res": 1080, "group": ""})

def alive(e):
    try:
        r = subprocess.run(["curl", "-sL", "-m", "8", "-r", "0-2047", "-A", "Mozilla/5.0", e["url"]],
                           capture_output=True, timeout=12)
        return r.stdout.lstrip().startswith(b"#EXTM3U")
    except Exception:
        return False

with ThreadPoolExecutor(32) as ex:
    ok = list(ex.map(alive, picked))
live = [e for e, a in zip(picked, ok) if a]

# dedupe: one entry per channel, keep highest resolution
def key(e):
    base = re.sub(r"\s*(\(\d+p\)|\[.*?\]|\bSD\b|\bHD\b)\s*", " ", e["name"]).strip().lower()
    return e["id"].split("@")[0].lower() or base
best = {}
for e in live:
    k = (e["cat"], key(e))
    if k not in best or e["res"] > best[k]["res"]: best[k] = e

ORDER = ["Vietnam", "Singapore", "News", "Cartoon", "Lifestyle", "Documentary"]
final = sorted(best.values(), key=lambda e: (ORDER.index(e["cat"]), e["name"].lower()))
with open(OUT, "w", encoding="utf-8") as f:
    f.write("#EXTM3U\n")
    for e in final:
        attrs = f'tvg-id="{e["id"]}" ' if e["id"] else ""
        attrs += f'tvg-logo="{e["logo"]}" ' if e["logo"] else ""
        f.write(f'#EXTINF:-1 {attrs}group-title="{e["cat"]}",{e["name"]}\n{e["url"]}\n')

print(f"candidates {len(picked)}  alive {len(live)}  final {len(final)}")
for c in ORDER:
    names = [e["name"] for e in final if e["cat"] == c]
    print(f"\n## {c} ({len(names)})\n" + " | ".join(names))
