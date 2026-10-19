"""Finds freely licensed dish photos on Wikimedia Commons (search + license metadata only; downloads nothing).

    python tools/find_commons_photos.py > photo-candidates.json

Only public domain, CC0, CC BY and CC BY-SA files are kept. Each candidate carries the author and license, which
CookAlong must show as a credit for CC BY / CC BY-SA photos.
"""
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API = "https://commons.wikimedia.org/w/api.php"
UA = "CookAlong-photo-finder/1.0 (Fire TV recipe app; contact via github.com/cookalong-tv)"
OK_LICENSE = re.compile(r"^(cc0|public domain|pd|cc by(-sa)? [\d.]+)", re.I)

QUERIES = {
    "lemon-garlic-spaghetti": ["spaghetti aglio olio", "spaghetti lemon garlic", "spaghetti al limone"],
    "masala-omelette": ["masala omelette", "indian omelette", "omelette onion tomato chili"],
    "tomato-basil-soup": ["tomato soup basil", "tomato soup bowl"],
    "veggie-fried-rice": ["vegetable fried rice", "egg fried rice"],
    "chocolate-mug-cake": ["mug cake", "chocolate mug cake"],
    "dal-tadka": ["dal tadka", "dal fry", "yellow dal"],
    "vegetable-upma": ["upma", "rava upma"],
    "vegetable-pulao": ["vegetable pulao", "veg pulao", "pulao rice"],
    "mango-lassi": ["mango lassi"],
    "classic-guacamole": ["guacamole bowl", "guacamole"],
    "greek-salad": ["greek salad", "horiatiki"],
    "garlic-butter-shrimp": ["garlic shrimp", "gambas al ajillo", "garlic butter shrimp"],
}


def clean(html):
    return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", html or "")).strip()


def search(term):
    params = {
        "action": "query", "format": "json", "generator": "search", "gsrnamespace": 6, "gsrlimit": 12,
        "gsrsearch": term + " filetype:bitmap", "prop": "imageinfo",
        "iiprop": "url|size|mime|extmetadata", "iiurlwidth": 640,
    }
    req = urllib.request.Request(API + "?" + urllib.parse.urlencode(params), headers={"User-Agent": UA})
    for attempt in range(5):  # be polite to Wikimedia: pause between calls, back off on 429
        time.sleep(1.5 * (attempt + 1))
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                data = json.load(r)
            break
        except urllib.error.HTTPError as e:
            if e.code != 429 or attempt == 4:
                raise
            time.sleep(10 * (attempt + 1))
    out = []
    for p in (data.get("query", {}).get("pages", {}) or {}).values():
        info = (p.get("imageinfo") or [{}])[0]
        meta = info.get("extmetadata", {})
        lic = clean(meta.get("LicenseShortName", {}).get("value"))
        if not OK_LICENSE.match(lic) or info.get("mime") not in ("image/jpeg", "image/png", "image/webp"):
            continue
        w, h = info.get("width", 0), info.get("height", 0)
        if w < 800 or h < 450 or w < h * 0.9:  # landscape-ish and big enough for a TV card
            continue
        out.append({
            "title": p.get("title"), "page": info.get("descriptionurl"), "thumb": info.get("thumburl"),
            "width": w, "height": h, "license": lic,
            "licenseUrl": clean(meta.get("LicenseUrl", {}).get("value")),
            "author": clean(meta.get("Artist", {}).get("value"))[:80] or "Unknown",
            "index": p.get("index", 99),
        })
    return sorted(out, key=lambda c: c["index"])


def main():
    result = {}
    for rid, terms in QUERIES.items():
        seen, cands = set(), []
        for t in terms:
            for c in search(t):
                if c["title"] not in seen:
                    seen.add(c["title"])
                    cands.append(c)
        result[rid] = cands[:6]
        print("%-24s %d candidates" % (rid, len(result[rid])), file=sys.stderr)
    json.dump(result, sys.stdout, indent=2, ensure_ascii=False)


if __name__ == "__main__":
    main()
