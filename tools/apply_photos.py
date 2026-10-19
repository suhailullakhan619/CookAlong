"""Replaces recipe illustrations with real photos and records the credit for each.

    python tools/apply_photos.py photos.json

photos.json maps recipe id -> one of:
  {"file": "path/to/local.webp"}                                         (public domain, no credit)
  {"url": "https://upload.wikimedia.org/...", "credit": "Author · CC BY-SA 4.0 · Wikimedia Commons",
   "page": "https://commons.wikimedia.org/wiki/File:...", "licenseUrl": "..."}
Each photo is center-cropped to 640x360 JPEG as assets/images/<id>.jpg; the old .png illustration is removed.
Credits are written to the recipe ("imageCredit") and listed in CREDITS.md.
"""
import io
import json
import os
import sys
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import import_based_cooking as bc  # noqa: E402  (crop_photo)
import import_recipes as ir  # noqa: E402

UA = "CookAlong-photo-fetch/1.0 (Fire TV recipe app; github.com/cookalong-tv)"
ROOT = ir.ROOT


def crop(src, dest, top=0.5):
    """Crop to 16:9 and resize to 640x360. `top` places the crop band in tall photos (0 = top, 0.5 = center)."""
    from PIL import Image
    im = Image.open(src).convert("RGB")
    w, h = im.size
    if w / h > 16 / 9:
        nw = int(h * 16 / 9)
        im = im.crop(((w - nw) // 2, 0, (w - nw) // 2 + nw, h))
    else:
        nh = int(w * 9 / 16)
        y = int((h - nh) * top)
        im = im.crop((0, y, w, y + nh))
    im.resize((640, 360), Image.LANCZOS).save(dest, "JPEG", quality=82, optimize=True, progressive=True)


def main():
    spec = json.load(io.open(sys.argv[1], encoding="utf-8"))
    tmp = os.path.join(os.path.dirname(os.path.abspath(sys.argv[1])), "photo-downloads")
    os.makedirs(tmp, exist_ok=True)
    catalog = json.load(io.open(ir.RECIPES, encoding="utf-8"))
    by_id = {r["id"]: r for r in catalog["recipes"]}
    credits = []
    for rid, p in spec.items():
        r = by_id[rid]
        if "url" in p:
            src = os.path.join(tmp, rid + "-" + os.path.basename(p["url"].split("?")[0]).lower()[-60:])
            if not os.path.exists(src):  # never download the same photo twice
                req = urllib.request.Request(p["url"], headers={"User-Agent": UA})
                with urllib.request.urlopen(req, timeout=30) as resp, open(src, "wb") as f:
                    f.write(resp.read(5_000_001))
        else:
            src = p["file"]
        dest = os.path.join(ir.ASSETS, "images", rid + ".jpg")
        crop(src, dest, p.get("cropTop", 0.5))
        old = os.path.join(ir.ASSETS, "images", rid + ".png")
        if os.path.exists(old):
            os.remove(old)
        r["image"] = "images/%s.jpg" % rid
        if p.get("credit"):
            r["imageCredit"] = p["credit"]
            credits.append((r["title"], p))
        else:
            r.pop("imageCredit", None)
        print("%-24s %5.0f KB  %s" % (rid, os.path.getsize(dest) / 1024, p.get("credit", "public domain (based.cooking)")))
    io.open(ir.RECIPES, "w", encoding="utf-8").write(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n")

    lines = ["# Photo credits", "",
             "Recipe photos come from Wikimedia Commons under the licenses below (cropped and resized to 640x360),",
             "and from [based.cooking](https://github.com/LukeSmithxyz/based.cooking) (public domain). Each Commons credit",
             "is also shown on the recipe screen and on the phone shopping-list page.", ""]
    for title, p in sorted(credits):
        lines.append("- **%s**: [%s](%s), %s ([license](%s))" % (
            title, p.get("fileTitle", "photo"), p.get("page", ""), p["credit"], p.get("licenseUrl", "")))
    path = os.path.join(ROOT, "CREDITS.md")
    old = io.open(path, encoding="utf-8").read() if os.path.exists(path) else ""
    marker = "\n## Online recipe photos"  # section kept up to date by tools/online_photos.py
    io.open(path, "w", encoding="utf-8").write("\n".join(lines) + "\n" + (old[old.find(marker):] if marker in old else ""))
    print("CREDITS.md written (%d credited photos)" % len(credits))


if __name__ == "__main__":
    main()
