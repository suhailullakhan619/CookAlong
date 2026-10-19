"""Builds site/: the public phone page for the shopping-list QR, ready for GitHub Pages or any static host.

Run from the repo root:  python tools/build_site.py
Then publish the site/ folder and put its URL in app/src/main/assets/config.json -> "companionBaseUrl".
The page and pictures come straight from the app's assets, so the TV and the web page never drift apart.
"""
import os
import shutil

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
SITE = os.path.join(ROOT, "site")

# Empty site/ but keep the folder itself (and its .git, so re-publishing is a normal commit + push).
os.makedirs(SITE, exist_ok=True)
for name in os.listdir(SITE):
    if name == ".git":
        continue
    path = os.path.join(SITE, name)
    shutil.rmtree(path) if os.path.isdir(path) else os.remove(path)
os.makedirs(os.path.join(SITE, "images"))
shutil.copy(os.path.join(ASSETS, "companion", "index.html"), os.path.join(SITE, "index.html"))
shutil.copy(os.path.join(ASSETS, "companion", "scan.html"), os.path.join(SITE, "scan.html"))  # fridge-scan camera page
for name in sorted(os.listdir(os.path.join(ASSETS, "images"))):
    if name.endswith((".png", ".jpg")):
        shutil.copy(os.path.join(ASSETS, "images", name), os.path.join(SITE, "images", name))
# photo credits shown under the dish picture on the phone page (required for CC BY / CC BY-SA photos)
import json
with open(os.path.join(ASSETS, "recipes.json"), encoding="utf-8") as f:
    credits = {r["id"]: r["imageCredit"] for r in json.load(f)["recipes"] if r.get("imageCredit")}
# photos of the online recipes (tools/online_photos.py); the TV downloads them from here too
PHOTOS = os.path.join(ROOT, "photos")
if os.path.exists(os.path.join(PHOTOS, "photos.json")):
    with open(os.path.join(PHOTOS, "photos.json"), encoding="utf-8") as f:
        for rid, e in json.load(f).items():
            if not e.get("none"):
                shutil.copy(os.path.join(PHOTOS, rid + ".jpg"), os.path.join(SITE, "images", rid + ".jpg"))
                if e.get("credit"):
                    credits.setdefault(rid, e["credit"])
with open(os.path.join(SITE, "credits.json"), "w", encoding="utf-8") as f:
    json.dump(credits, f, ensure_ascii=False, indent=1)
open(os.path.join(SITE, ".nojekyll"), "w").close()  # serve files as-is on GitHub Pages
print("built", SITE)
