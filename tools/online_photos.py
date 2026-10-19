"""Real photos for the online recipes (DynamoDB `cookalong-recipes`), hosted on the GitHub Pages site.

Re-run it whenever recipes without a photo have been added. Three steps, from the repo root:

    python tools/online_photos.py find  --work <dir>          # candidates + contact sheets for review
    python tools/online_photos.py apply --work <dir>          # after writing <dir>/picks.json
    python tools/online_photos.py sync-db                     # after pushing site/ (see below)

       (python tools/online_photos.py find --work <dir2> --terms terms.json  retries chosen recipes with
       hand-written search terms, e.g. the name of a similar, better documented dish.)
find   Reads every online recipe that has no decision in photos/photos.json yet and gathers up to 8 freely
       licensed candidates per dish, in this order:
         b  based.cooking's own public-domain picture (from the recipe's page in the based.cooking repo)
         w  pictures on the recipe's Wikibooks page (Wikimedia Commons files)
         p  the lead picture of the matching Wikipedia article (free images only)
         c  Wikimedia Commons search on the dish name
       Commons files are kept only under CC0, public domain, CC BY or CC BY-SA. The contact sheets
       (<dir>/sheet-NN.png) show each candidate already cropped to the TV's 16:9, numbered from 0.
       Look at every sheet and write <dir>/picks.json: {"recipe-id": 2, "other-id": null, ...}.
       Pick a candidate only if it really shows that dish; null means none fits (the TV then keeps its
       illustrated fallback, and the recipe is skipped by later runs unless you pass --retry-none).
       {"pick": 2, "top": 0.25} moves the crop band up for tall photos (0 = top, 0.5 = center).
apply  Crops each pick to a 640x360 JPEG in photos/<id>.jpg, records source, author and license in
       photos/photos.json, copies the photos into site/images/, merges their credits into site/credits.json
       and rewrites the "Online recipe photos" section of CREDITS.md.
       Then publish: cd site && git add images credits.json && git commit && git push.
sync-db Waits until each photo is live on GitHub Pages, then sets `image` (https URL with a content hash, so
       TVs refetch a replaced photo) and `imageCredit` inside the recipe JSON and bumps `updatedAt`, so TVs
       pick the change up on their next sync. Only those two fields and updatedAt are touched, guarded by a
       condition on the old updatedAt so a concurrent edit is never overwritten (the item is retried).
"""
import argparse
import concurrent.futures
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PHOTOS = os.path.join(ROOT, "photos")
MANIFEST = os.path.join(PHOTOS, "photos.json")
SITE = os.path.join(ROOT, "site")
SITE_URL = "https://cookalong-tv.github.io/"
TABLE, REGION = "cookalong-recipes", "ap-south-1"
BASED_COOKING_GIT = "https://github.com/LukeSmithxyz/based.cooking"
UA = "CookAlongPhotoFinder/1.1 (https://github.com/cookalong-tv/cookalong-tv.github.io) python-urllib/%d.%d" % sys.version_info[:2]
COMMONS = "https://commons.wikimedia.org/w/api.php"
WIKIPEDIA = "https://en.wikipedia.org/w/api.php"
WIKIBOOKS = "https://en.wikibooks.org/w/api.php"
OK_LICENSE = re.compile(r"^(cc0|public domain|pd\b|cc by(-sa)? [\d.]+)", re.I)
SKIP_FILE = re.compile(r"(icon|logo|stub|nuvola|crystal|gnome|cookbook|wikibooks|symbol|map|flag|diagram|chart)", re.I)
FILLER = re.compile(r"\b(easy|simple|simplest|quick|homemade|home-made|classic|basic|best|perfect|my|mom'?s|"
                    r"grandma'?s|one[- ]pot|instant|healthy|ultimate|traditional|authentic|style|recipe|the)\b", re.I)
THUMB_W, FINAL_W = 330, 960  # Wikimedia's standard thumbnail steps; other widths get rate-limited
MAX_CANDIDATES = 8

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")


# ---- small helpers ------------------------------------------------------------------------------------------------

def load_json(path, default):
    try:
        with io.open(path, encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        return default


def save_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with io.open(tmp, "w", encoding="utf-8") as f:
        f.write(json.dumps(data, ensure_ascii=False, indent=1, sort_keys=True) + "\n")
    os.replace(tmp, path)


def clean_html(s):
    return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", s or "")).strip()


def tidy_author(a):
    """Credit-line form of a Commons author field: no places, timestamps or wiki markup."""
    a = re.sub(r"^\[https?://\S+\s+([^\]]+)\]", r"", a)             # [https://… Name] -> Name
    a = re.sub(r"^©\s*|\s*/\s*Wikipedia.*$|Create the proper attribution.*$", "", a)
    a = re.sub(r",?\s*modified by .*$", "", a)
    a = re.sub(r"\s+\d{1,2}:\d{2},\s+\d{1,2}\s+\w+\s+\d{4}\s+\(UTC\)$", "", a)  # signature timestamp
    a = re.sub(r"\s+(from|at)\s+(?!.*(user|wikipedia))[^()]*$", "", a, flags=re.I)  # "Name from London, UK"
    a = re.sub(r"\s+at\s+(English|Japanese|Malayalam|\w+) Wikipedia$|\s+at wts wikivoyage$", "", a, flags=re.I)
    a = re.sub(r"^\(WT-shared\)\s*", "", a)
    a = re.sub(r"\s*\((a flickr user|photographer|Photographer|talk|contribs?)\)", "", a)
    return a.strip(" .,;-") or "Unknown author"


def author_of(meta, uploader=None):
    a = clean_html(meta.get("Artist", {}).get("value"))
    m = re.match(r"No machine-readable author provided\.\s*(.+?) assumed", a)
    if m:
        a = m.group(1)
    a = re.sub(r"\s*\((talk|contribs?)\)", "", a)
    a = re.sub(r"^(User:|Photo by |Photograph by |by )", "", a, flags=re.I).strip(" .,;")
    if not a or a.lower() in ("own work", "unknown", "unknown author"):
        a = uploader or clean_html(meta.get("Credit", {}).get("value"))[:60] or "Unknown author"
        a = re.sub(r"~commonswiki$", "", a)
    return tidy_author(a)[:60]


class Http:
    """Polite, cached GETs: one request at a time to the APIs, backing off on 429 / 5xx."""

    def __init__(self, cache_dir):
        self.cache_dir = cache_dir
        os.makedirs(cache_dir, exist_ok=True)
        self.last = 0.0

    def json(self, api, **params):
        params.update(format="json", formatversion="2")
        url = api + "?" + urllib.parse.urlencode(params)
        key = os.path.join(self.cache_dir, hashlib.sha1(url.encode()).hexdigest() + ".json")
        if os.path.exists(key):
            return load_json(key, {})
        data = json.loads(self.get(url, pause=1.0))
        save_json(key, data)
        return data

    def get(self, url, pause=0.0, limit=8_000_000):
        for attempt in range(6):
            wait = pause - (time.time() - self.last)
            if wait > 0:
                time.sleep(wait)
            self.last = time.time()
            try:
                req = urllib.request.Request(url, headers={"User-Agent": UA})
                with urllib.request.urlopen(req, timeout=40) as r:
                    return r.read(limit)
            except urllib.error.HTTPError as e:
                if e.code not in (429, 500, 502, 503, 504) or attempt == 5:
                    raise
                wait = int(e.headers.get("Retry-After") or 0) or 5 * (attempt + 1)
                print("  HTTP %d from %s, waiting %ds" % (e.code, url.split("/")[2], wait), file=sys.stderr, flush=True)
                time.sleep(wait)
            except (urllib.error.URLError, TimeoutError):
                if attempt == 5:
                    raise
                time.sleep(5 * (attempt + 1))


# ---- recipes ------------------------------------------------------------------------------------------------------

def online_recipes():
    """Every non-bundled recipe in DynamoDB as (item, recipe dict)."""
    import boto3
    table = boto3.resource("dynamodb", region_name=REGION).Table(TABLE)
    out, kw = [], {}
    while True:
        page = table.scan(**kw)
        for item in page["Items"]:
            if item.get("source") == "bundled":
                continue
            out.append((item, json.loads(item["recipe"])))
        if "LastEvaluatedKey" not in page:
            return out
        kw["ExclusiveStartKey"] = page["LastEvaluatedKey"]


def search_terms(title):
    """The dish name and simpler variants: "Easy Gambas al Ajillo (Garlic Shrimp)" -> both names, no filler."""
    terms = []
    for part in [re.sub(r"\(.*?\)", "", title)] + re.findall(r"\((.*?)\)", title):
        for t in (part, FILLER.sub(" ", part)):
            t = re.sub(r"\s+", " ", t.replace(",", " ")).strip(" -")
            if len(t) > 2 and t.lower() not in (x.lower() for x in terms):
                terms.append(t)
    return terms


# ---- candidate sources --------------------------------------------------------------------------------------------

def based_cooking_repo(path):
    if path and os.path.isdir(os.path.join(path, "content")):
        return path
    path = path or os.path.join(tempfile.gettempdir(), "cookalong-based.cooking")
    if not os.path.isdir(os.path.join(path, "content")):
        subprocess.run(["git", "clone", "--depth", "1", BASED_COOKING_GIT, path], check=True)
    return path


def based_cooking_pix(repo, recipe):
    url = recipe.get("sourceUrl", "")
    if "based.cooking" not in url:
        return []
    md = os.path.join(repo, "content", url.rstrip("/").split("/")[-1] + ".md")
    if not os.path.exists(md):
        return []
    text = io.open(md, encoding="utf-8").read()
    out = []
    for rel in dict.fromkeys(re.findall(r"/pix/[^)\s\"']+", text)):
        f = os.path.join(repo, "static", rel.lstrip("/").replace("/", os.sep))
        if os.path.exists(f):
            out.append({"source": "based.cooking", "file": f, "title": rel, "page": url,
                        "license": "Public domain", "author": "based.cooking"})
    return out


def commons_info(http, titles):
    """imageinfo for File: titles (Commons only), filtered to usable licenses and sizes."""
    out = {}
    titles = list(dict.fromkeys(titles))
    for i in range(0, len(titles), 40):
        data = http.json(COMMONS, action="query", prop="imageinfo", titles="|".join(titles[i:i + 40]),
                         iiprop="url|size|mime|extmetadata|user", iiurlwidth=THUMB_W)
        for p in data.get("query", {}).get("pages", []):
            info = (p.get("imageinfo") or [None])[0]
            if info and usable(info):
                out[p["title"]] = candidate(p["title"], info)
    return out


def usable(info):
    meta = info.get("extmetadata", {})
    lic = clean_html(meta.get("LicenseShortName", {}).get("value"))
    if not OK_LICENSE.match(lic) or meta.get("NonFree", {}).get("value") == "true":
        return False
    if info.get("mime") not in ("image/jpeg", "image/png", "image/webp"):
        return False
    w, h = info.get("width", 0), info.get("height", 0)
    return w >= 640 and h >= 360 and w >= h * 0.75


def candidate(title, info):
    meta = info.get("extmetadata", {})
    thumb = info.get("thumburl") or info["url"]
    final = thumb.replace("/%dpx-" % THUMB_W, "/%dpx-" % FINAL_W) if "/%dpx-" % THUMB_W in thumb else info["url"]
    if info.get("width", 0) <= FINAL_W:
        final = info["url"]
    return {
        "source": "commons", "title": title, "page": info.get("descriptionurl"), "thumb": thumb, "url": final,
        "width": info.get("width"), "height": info.get("height"),
        "license": clean_html(meta.get("LicenseShortName", {}).get("value")),
        "licenseUrl": clean_html(meta.get("LicenseUrl", {}).get("value")),
        "author": author_of(meta, info.get("user")),
    }


def wikibooks_files(http, recipes):
    """Pictures on each recipe's Wikibooks page, 50 pages per API call: {recipe id: [File: titles]}."""
    pages = {}
    for r in recipes:
        url = r.get("sourceUrl", "")
        if "wikibooks.org/wiki/" in url:
            pages[urllib.parse.unquote(url.split("/wiki/", 1)[1]).replace("_", " ")] = r["id"]
    out = {rid: [] for rid in pages.values()}
    names = sorted(pages)
    for i in range(0, len(names), 50):
        cont = {}
        while True:
            data = http.json(WIKIBOOKS, action="query", prop="images", titles="|".join(names[i:i + 50]),
                             imlimit="max", redirects="1", **cont)
            q = data.get("query", {})
            alias = {}
            for m in q.get("normalized", []) + q.get("redirects", []):
                alias[m["to"]] = alias.get(m["from"], m["from"])
            for p in q.get("pages", []):
                rid = pages.get(alias.get(p["title"], p["title"]))
                if rid:
                    out[rid] += [im["title"] for im in p.get("images", [])
                                 if not SKIP_FILE.search(im["title"]) and not im["title"].lower().endswith((".svg", ".gif"))]
            if "continue" not in data:
                break
            cont = {k: v for k, v in data["continue"].items() if k != "continue"}
    return out


def wikipedia_lead_files(http, term):
    data = http.json(WIKIPEDIA, action="query", generator="search", gsrsearch=term, gsrlimit="2",
                     gsrnamespace="0", prop="pageimages", piprop="name", pilicense="free")
    pages = sorted(data.get("query", {}).get("pages", []), key=lambda p: p.get("index", 9))
    return ["File:" + p["pageimage"] for p in pages if p.get("pageimage")]


def commons_search(http, term, limit=8):
    data = http.json(COMMONS, action="query", generator="search", gsrnamespace="6", gsrlimit=str(limit),
                     gsrsearch=term + " filetype:bitmap", prop="imageinfo",
                     iiprop="url|size|mime|extmetadata|user", iiurlwidth=THUMB_W)
    pages = sorted(data.get("query", {}).get("pages", []), key=lambda p: p.get("index", 99))
    out = []
    for p in pages:
        info = (p.get("imageinfo") or [None])[0]
        if info and usable(info):
            out.append(candidate(p["title"], info))
    return out


OPENVERSE = "https://api.openverse.org/v1/images/"


def openverse_search(http, term, limit=8):
    """Openverse (no API key): CC0 / public domain / CC BY / CC BY-SA photos from Flickr and others."""
    url = OPENVERSE + "?" + urllib.parse.urlencode({
        "q": term, "license": "cc0,pdm,by,by-sa", "page_size": limit, "mature": "false"})
    key = os.path.join(http.cache_dir, hashlib.sha1(url.encode()).hexdigest() + ".json")
    data = load_json(key, None)
    if data is None:
        data = json.loads(http.get(url, pause=1.0))
        save_json(key, data)
    out = []
    for r in data.get("results", []):
        if r.get("source") in ("wikimedia", "commons") or (r.get("width") or 9999) < 640:
            continue
        lic = {"cc0": "CC0", "pdm": "Public domain"}.get(r["license"]) or             "CC %s %s" % (r["license"].upper().replace("-SA", "-SA"), r.get("license_version") or "")
        out.append({
            "source": "openverse/" + (r.get("source") or "web"), "title": "%s: %s" % (r.get("source"), r.get("title") or r["id"]),
            "page": r["foreign_landing_url"], "thumb": r.get("thumbnail") or r["url"], "url": r["url"],
            "width": r.get("width"), "height": r.get("height"), "license": lic.strip(),
            "licenseUrl": r.get("license_url", ""), "author": tidy_author(r.get("creator") or "Unknown author")[:60],
            "via": (r.get("source") or "web").capitalize(),
        })
    return out


def gather(http, repo, recipes, found, save, terms=None):
    """Candidates for each recipe, in batched phases to keep the number of API calls (and 429s) down."""
    print("Wikibooks pages...", flush=True)
    wb = wikibooks_files(http, recipes)
    print("Wikipedia lead pictures...", flush=True)
    wp = {r["id"]: wikipedia_lead_files(http, search_terms(r["title"])[0]) for r in recipes}
    print("Commons licenses...", flush=True)
    info = commons_info(http, [f for files in list(wb.values()) + list(wp.values()) for f in files])
    for n, r in enumerate(recipes, 1):
        cands = based_cooking_pix(repo, r)
        for kind, files in (("wikibooks", wb.get(r["id"], [])), ("wikipedia", wp[r["id"]])):
            cands += [dict(info[f], source="commons/" + kind) for f in files if f in info]
        extra = (terms or {}).get(r["id"])
        for t in extra or search_terms(r["title"])[:2]:
            if len(cands) >= (12 if extra else 5):
                break
            cands += [dict(c, source="commons/search") for c in commons_search(http, t)]
        if extra:  # hand-written terms: Commons alone wasn't enough, so Openverse photos go in first
            ov = []
            for t in extra[:2]:
                ov += openverse_search(http, t, 5)
            cands = cands[:3] + ov + cands[3:]
        seen, out = set(), []
        for c in cands:
            if c["title"] not in seen:
                seen.add(c["title"])
                out.append(c)
        found[r["id"]] = {"title": r["title"], "candidates": out[:MAX_CANDIDATES]}
        save()
        print("%3d/%d %-40s %d candidates" % (n, len(recipes), r["id"], len(found[r["id"]]["candidates"])), flush=True)


# ---- images -------------------------------------------------------------------------------------------------------

def crop_16x9(im, top=0.5, width=640):
    from PIL import Image
    im = im.convert("RGB")
    w, h = im.size
    if w / h > 16 / 9:
        nw = int(h * 16 / 9)
        im = im.crop(((w - nw) // 2, 0, (w - nw) // 2 + nw, h))
    else:
        nh = int(w * 9 / 16)
        y = int((h - nh) * top)
        im = im.crop((0, y, w, y + nh))
    return im.resize((width, width * 9 // 16), Image.LANCZOS)


def fetch_image(http, c, cache_dir, key):
    """Local file for a candidate (`key` = "thumb" for the sheets or "url" for the final photo)."""
    from PIL import Image
    if c.get("file"):
        return Image.open(c["file"])
    url = c[key]
    path = os.path.join(cache_dir, hashlib.sha1(url.encode()).hexdigest() + os.path.splitext(url.split("?")[0])[1][:5])
    if not os.path.exists(path):
        data = http.get(url, pause=0.25)
        with open(path, "wb") as f:
            f.write(data)
    return Image.open(path)


def contact_sheets(http, work, rows, per_sheet=8):
    """rows: [(recipe, candidates)]. Each row: a title bar, then the candidates cropped 16:9 and numbered."""
    from PIL import Image, ImageDraw, ImageFont
    tw, th, bar, gap = 180, 101, 26, 6
    try:
        font = ImageFont.truetype("arialbd.ttf", 17)
        small = ImageFont.truetype("arial.ttf", 13)
    except OSError:
        font = small = ImageFont.load_default()
    thumbs = os.path.join(work, "thumbs")
    os.makedirs(thumbs, exist_ok=True)

    def thumb(c):
        try:
            return crop_16x9(fetch_image(http, c, thumbs, "thumb"), width=tw).resize((tw, th))
        except Exception as e:  # a broken file just shows as grey
            print("  thumb failed:", c["title"], e, file=sys.stderr)
            return None

    sheets = []
    for s in range(0, len(rows), per_sheet):
        chunk = rows[s:s + per_sheet]
        sheet = Image.new("RGB", (MAX_CANDIDATES * (tw + gap) + gap, len(chunk) * (bar + th + gap + 16)), "white")
        d = ImageDraw.Draw(sheet)
        with concurrent.futures.ThreadPoolExecutor(3) as pool:
            imgs = [list(pool.map(thumb, cands)) for _, cands in chunk]
        y = 0
        for (recipe, cands), row in zip(chunk, imgs):
            d.rectangle([0, y, sheet.width, y + bar - 2], fill="#203040")
            d.text((gap, y + 3), "%s   [%s]" % (recipe["title"], recipe["id"]), fill="white", font=font)
            y += bar
            for i, (c, im) in enumerate(zip(cands, row)):
                x = gap + i * (tw + gap)
                if im is not None:
                    sheet.paste(im, (x, y))
                else:
                    d.rectangle([x, y, x + tw, y + th], fill="#999")
                d.rectangle([x, y, x + 22, y + 20], fill="black")
                d.text((x + 6, y + 1), str(i), fill="yellow", font=font)
                d.text((x, y + th + 1), c["source"].replace("commons/", "")[:6] + " " +
                       re.sub(r"^File:", "", c["title"])[:20], fill="#333", font=small)
            y += th + gap + 16
        name = os.path.join(work, "sheet-%02d.png" % (s // per_sheet + 1))
        sheet.save(name)
        sheets.append(name)
    return sheets


# ---- commands -----------------------------------------------------------------------------------------------------

def cmd_find(args):
    manifest = load_json(MANIFEST, {})
    http = Http(os.path.join(args.work, "api-cache"))
    repo = based_cooking_repo(args.based_cooking)
    terms = load_json(args.terms, {}) if args.terms else {}
    todo = []
    for item, r in online_recipes():
        done = manifest.get(r["id"])
        if r["id"] in terms:  # hand-written search terms: look again even if reviewed before
            if not str(r.get("image", "")).startswith("https://") and not (done and not done.get("none")):
                todo.append(r)
            continue
        if args.terms:
            continue
        if str(r.get("image", "")).startswith("https://") or (done and not (done.get("none") and args.retry_none)):
            continue
        if args.only and r["id"] not in args.only:
            continue
        todo.append(r)
    todo.sort(key=lambda r: r["id"])
    if args.limit:
        todo = todo[:args.limit]
    print("%d recipes need a photo" % len(todo), flush=True)
    path = os.path.join(args.work, "candidates.json")
    found = load_json(path, {})
    gather(http, repo, [r for r in todo if r["id"] not in found], found, lambda: save_json(path, found), terms)
    rows = [({"id": r["id"], "title": r["title"]}, found[r["id"]]["candidates"]) for r in todo
            if found[r["id"]]["candidates"]]
    for name in contact_sheets(http, args.work, rows):
        print("sheet", name)
    print("no candidates:", ", ".join(r["id"] for r in todo if not found[r["id"]]["candidates"]) or "none")


def cmd_apply(args):
    manifest = load_json(MANIFEST, {})
    found = load_json(os.path.join(args.work, "candidates.json"), {})
    picks = load_json(os.path.join(args.work, "picks.json"), {})
    http = Http(os.path.join(args.work, "api-cache"))
    originals = os.path.join(args.work, "originals")
    os.makedirs(originals, exist_ok=True)
    today = time.strftime("%Y-%m-%d")
    unknown = sorted(set(picks) - set(found))
    if unknown:
        sys.exit("picks.json has ids that are not in candidates.json: " + ", ".join(unknown))
    for rid, pick in sorted(picks.items()):
        if pick is None:
            manifest[rid] = {"none": True, "checked": today, "title": found.get(rid, {}).get("title", rid)}
            continue
        pick = pick if isinstance(pick, dict) else {"pick": pick}
        c = found[rid]["candidates"][pick["pick"]]
        dest = os.path.join(PHOTOS, rid + ".jpg")
        os.makedirs(PHOTOS, exist_ok=True)
        crop_16x9(fetch_image(http, c, originals, "url"), top=pick.get("top", 0.5)).save(
            dest, "JPEG", quality=82, optimize=True, progressive=True)
        sha = hashlib.sha1(open(dest, "rb").read()).hexdigest()[:10]
        entry = {"title": found[rid]["title"], "source": c["source"], "page": c["page"], "license": c["license"],
                 "file": os.path.basename(c["title"]), "sha": sha, "checked": today}
        if c["source"].startswith(("commons", "openverse")):
            via = c.get("via", "Wikimedia Commons")
            entry.update(author=c["author"], licenseUrl=c.get("licenseUrl", ""), via=via,
                         credit="%s · %s · %s" % (c["author"], c["license"], via))
        manifest[rid] = entry
        print("%-40s %5.0f KB  %s" % (rid, os.path.getsize(dest) / 1024, entry.get("credit", "public domain (" + c["source"] + ")")))
    normalize_manifest(manifest)
    save_json(MANIFEST, manifest)
    publish_to_site(manifest)
    write_credits(manifest)


def normalize_manifest(manifest):
    """Re-tidies stored authors (credit lines made by older runs) and rebuilds each credit from them."""
    for e in manifest.values():
        if e.get("author"):
            e["author"] = tidy_author(e["author"])
            e["credit"] = "%s · %s · %s" % (e["author"], e["license"], e.get("via", "Wikimedia Commons"))


def publish_to_site(manifest):
    """Copies the photos next to the bundled ones in site/images/ and merges their credits into credits.json."""
    os.makedirs(os.path.join(SITE, "images"), exist_ok=True)
    credits = load_json(os.path.join(SITE, "credits.json"), {})
    for rid, e in manifest.items():
        if e.get("none"):
            continue
        shutil.copy(os.path.join(PHOTOS, rid + ".jpg"), os.path.join(SITE, "images", rid + ".jpg"))
        if e.get("credit"):
            credits[rid] = e["credit"]
        else:
            credits.pop(rid, None)
    with io.open(os.path.join(SITE, "credits.json"), "w", encoding="utf-8") as f:
        json.dump(credits, f, ensure_ascii=False, indent=1)
    print("site/: %d photos" % sum(1 for e in manifest.values() if not e.get("none")))


CREDITS_HEADER = "## Online recipe photos"


def write_credits(manifest):
    path = os.path.join(ROOT, "CREDITS.md")
    text = io.open(path, encoding="utf-8").read() if os.path.exists(path) else "# Photo credits\n"
    text = text.split("\n" + CREDITS_HEADER)[0].rstrip("\n") + "\n"
    lines = ["", CREDITS_HEADER, "",
             "Photos for the recipes the TV downloads from the CookAlong recipe library (DynamoDB), hosted on",
             SITE_URL + "images/. Wikimedia Commons photos are cropped and resized to 640x360; based.cooking",
             "photos are public domain. Generated by tools/online_photos.py from photos/photos.json.", ""]
    for rid, e in sorted(manifest.items(), key=lambda kv: kv[1].get("title", kv[0]).lower()):
        if e.get("none"):
            continue
        if e.get("credit"):
            lines.append("- **%s**: [%s](%s), %s ([license](%s))" % (
                e["title"], e["file"], e["page"], e["credit"], e.get("licenseUrl", "")))
        else:
            lines.append("- **%s**: [%s](%s), public domain" % (e["title"], e["file"], e["page"]))
    io.open(path, "w", encoding="utf-8").write(text + "\n".join(lines) + "\n")
    print("CREDITS.md updated")


def photo_url(rid, entry):
    return "%simages/%s.jpg?v=%s" % (SITE_URL, rid, entry["sha"])


def live(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Cache-Control": "no-cache"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status == 200 and r.headers.get_content_type() == "image/jpeg"
    except (urllib.error.URLError, TimeoutError):
        return False


def cmd_sync_db(args):
    import boto3
    from botocore.exceptions import ClientError
    manifest = load_json(MANIFEST, {})
    table = boto3.resource("dynamodb", region_name=REGION).Table(TABLE)
    updated = unchanged = waiting = 0
    for item, r in online_recipes():
        e = manifest.get(r["id"])
        if not e or e.get("none"):
            continue
        url = photo_url(r["id"], e)
        if r.get("image") == url and r.get("imageCredit") == e.get("credit"):
            unchanged += 1
            continue
        local = os.path.join(PHOTOS, r["id"] + ".jpg")
        if not live(url) or not same_bytes(url, local):
            print("not live yet:", url)
            waiting += 1
            continue
        for _ in range(5):
            r["image"] = url
            if e.get("credit"):
                r["imageCredit"] = e["credit"]
            else:
                r.pop("imageCredit", None)
            if args.dry_run:
                print("would update", r["id"], url)
                break
            try:
                table.update_item(
                    Key={"id": r["id"]},
                    UpdateExpression="SET #r = :r, updatedAt = :now",
                    ConditionExpression="updatedAt = :old",
                    ExpressionAttributeNames={"#r": "recipe"},
                    ExpressionAttributeValues={":r": json.dumps(r, ensure_ascii=False, separators=(",", ":")),
                                               ":now": int(time.time() * 1000), ":old": item["updatedAt"]},
                )
                updated += 1
                break
            except ClientError as ex:
                if ex.response["Error"]["Code"] != "ConditionalCheckFailedException":
                    raise
                item = table.get_item(Key={"id": r["id"]}, ConsistentRead=True)["Item"]  # edited meanwhile: redo
                r = json.loads(item["recipe"])
    print("updated %d, already current %d, waiting for GitHub Pages %d" % (updated, unchanged, waiting))


def same_bytes(url, local):
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Cache-Control": "no-cache"})
    with urllib.request.urlopen(req, timeout=20) as r:
        return r.read() == open(local, "rb").read()


def cmd_status(args):
    manifest = load_json(MANIFEST, {})
    rows = online_recipes()
    photo = sum(1 for _, r in rows if str(r.get("image", "")).startswith("https://"))
    staged = sum(1 for _, r in rows if r["id"] in manifest and not manifest[r["id"]].get("none")
                 and not str(r.get("image", "")).startswith("https://"))
    none = [r["id"] for _, r in rows if manifest.get(r["id"], {}).get("none")]
    new = [r["id"] for _, r in rows if r["id"] not in manifest and not str(r.get("image", "")).startswith("https://")]
    print("online recipes: %d\n  photo live in DynamoDB: %d\n  photo staged, not synced: %d\n"
          "  reviewed, no suitable photo (fallback art): %d\n  not reviewed yet: %d" % (
              len(rows), photo, staged, len(none), len(new)))
    if args.verbose:
        print("no photo:", ", ".join(sorted(none)))
        print("not reviewed:", ", ".join(sorted(new)))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    f = sub.add_parser("find")
    f.add_argument("--work", required=True)
    f.add_argument("--based-cooking", help="existing clone of the based.cooking repo (cloned if missing)")
    f.add_argument("--retry-none", action="store_true", help="look again for recipes marked 'no suitable photo'")
    f.add_argument("--only", nargs="*", help="recipe ids")
    f.add_argument("--terms", help='JSON file {"recipe-id": ["search term", ...]}: look only for these recipes, '
                                   'using these Commons search terms (for dishes the automatic search missed)')
    f.add_argument("--limit", type=int)
    a = sub.add_parser("apply")
    a.add_argument("--work", required=True)
    s = sub.add_parser("sync-db")
    s.add_argument("--dry-run", action="store_true")
    st = sub.add_parser("status")
    st.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()
    {"find": cmd_find, "apply": cmd_apply, "sync-db": cmd_sync_db, "status": cmd_status}[args.cmd](args)


if __name__ == "__main__":
    main()
