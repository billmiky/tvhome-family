"""Channel page URL -> live .m3u8 link, the way the site's player.js does it:
read window.playerConfig, POST apiUrl/endpoint, then fileUrl = reverse(base64decode(...)).
Usage: python3 extract_stream.py <page-url> [<page-url> ...]"""
import base64, json, re, sys, urllib.request
from urllib.parse import urlsplit

UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36"

def fetch(url, origin, data=None):
    # Cloudflare answers bare curl/urllib with a 403 page; a browser UA + Origin/Referer gets through
    req = urllib.request.Request(url, data=data, headers={
        "User-Agent": UA, "Origin": origin, "Referer": origin + "/",
        "Content-Type": "application/x-www-form-urlencoded"})
    with urllib.request.urlopen(req, timeout=20) as r:
        return r.read().decode("utf-8", "replace")

def config(html):
    m = re.search(r"window\.playerConfig\s*=\s*\{(.*?)\};", html, re.S)
    if not m: raise ValueError("no playerConfig on page")
    return dict(re.findall(r'(\w+)\s*:\s*"([^"]*)"', m.group(1)))

def stream(page):
    p = urlsplit(page); origin = f"{p.scheme}://{p.netloc}"
    cfg = config(fetch(page, origin))
    if cfg.get("streamType") == "direct":
        return cfg["sourceUrl"]
    try:
        data = json.loads(fetch(f"{cfg['apiUrl']}/{cfg['endpoint']}", origin, data=b""))
        return base64.b64decode(data["fileUrl"]).decode()[::-1]
    except Exception as e:
        print(f"api failed ({e}), using fallbackUrl", file=sys.stderr)
        return cfg["fallbackUrl"]

for page in sys.argv[1:]:
    print(stream(page))
