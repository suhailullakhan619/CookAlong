"""Imports public-domain recipes from based.cooking (https://github.com/LukeSmithxyz/based.cooking, Unlicense).

    git clone --depth 1 https://github.com/LukeSmithxyz/based.cooking <somewhere>
    python tools/import_based_cooking.py <somewhere>/based.cooking            # preview the selection
    python tools/import_based_cooking.py <somewhere>/based.cooking --write    # add them + their photos

Only recipes that suit a TV are taken: a photo, 3-16 ingredients that mostly have quantities, and 3-12 short
steps. Photos are center-cropped to 640x360 JPEGs (needs Pillow). Parsing (amounts, units, step timers,
categories) is shared with tools/import_recipes.py.
"""
import argparse
import io
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import import_recipes as ir  # noqa: E402

MEAT = re.compile(r"(chicken|beef|pork|bacon|\bham\b|lamb|mutton|veal|sausage|korv|wurst|kielbasa|chorizo|salami|"
                  r"pepperoni|prosciutto|pancetta|guanciale|mortadella|turkey|duck|venison|rabbit|fish|salmon|tuna|"
                  r"\bcod\b|anchov|shrimp|prawn|crab|lobster|mussel|clam|squid|oyster|worcestershire|gelatin|lard|suet|"
                  r"meat|\bmince\b|mincemeat|steak)", re.I)
MEAT_TAGS = {"chicken", "beef", "pork", "fish", "sausage", "meat", "seafood", "lamb", "turkey", "bacon", "ham"}
SKIP_TAGS = {"sauce", "condiment", "spice", "alcohol", "liquor", "medieval"}
TAG_CATEGORIES = {"breakfast": "breakfast", "dessert": "sweet", "sweet": "sweet", "cookies": "sweet",
                  "drink": "sweet", "snack": "snack", "side": "snack", "salad": "snack", "indian": "indian",
                  "curry": "indian"}
MAX_PER_GROUP = 6  # keep the selection varied instead of 30 pasta dishes


def clean_md(s):
    s = re.sub(r"!\[[^\]]*\]\([^)]*\)", "", s)               # images
    s = re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", s)           # links -> text
    s = re.sub(r"[*_`]+", "", s)                              # emphasis
    s = re.sub(r"\s*\((?:approx\.?\s*)?[\d.,/ ]+\s*(?:dl|ml|cl|l|g|kg|oz|lbs?|°[CF])\)", "", s)  # metric duplicates
    return re.sub(r"\s+", " ", s).strip()


def section(body, name):
    m = re.search(r"^##\s*" + name + r"\b.*?$(.*?)(?=^##\s|\Z)", body, re.M | re.S | re.I)
    return m.group(1) if m else ""


def parse(path):
    text = io.open(path, encoding="utf-8").read()
    fm = re.match(r"^---\s*\n(.*?)\n---\s*\n(.*)$", text, re.S)
    if not fm:
        return None, None
    meta, body = fm.group(1), fm.group(2)
    title = re.search(r'^title:\s*"?(.*?)"?\s*$', meta, re.M)
    tags = re.findall(r"'([^']+)'|\"([^\"]+)\"", (re.search(r"^tags:\s*(.*)$", meta, re.M) or [None, ""])[1])
    tags = [a or b for a, b in tags]
    pic = re.search(r"!\[[^\]]*\]\((/pix/[^)\s]+)\)", body)

    def line_val(label):
        m = re.search(label + r"[^:\n]*:\s*(.+)", body, re.I)
        return m.group(1).strip() if m else ""

    ingredients = [clean_md(m) for m in re.findall(r"^\s*[-*+]\s+(.+)$", section(body, "Ingredients"), re.M)]
    directions_md = section(body, "Directions") or section(body, "Instructions") or section(body, "Steps")
    steps = [clean_md(m) for m in re.findall(r"^\s*(?:\d+[.)]|[-*+])\s+(.+)$", directions_md, re.M)]
    node = {
        "@type": "Recipe",
        "name": clean_md(title.group(1)) if title else "",
        "recipeCategory": tags,
        "prepTime": line_val("Prep time"), "cookTime": line_val("Cook time"),
        "recipeYield": line_val("Servings"),
        "recipeIngredient": [i for i in ingredients if i],
        "recipeInstructions": [s for s in steps if s],
    }
    return node, pic.group(1) if pic else None


def good(r):
    ings, steps = r["ingredients"], r["steps"]
    if not (3 <= len(ings) <= 16 and 3 <= len(steps) <= 12):
        return False
    if sum(i["amount"] is not None for i in ings) < 0.7 * len(ings):
        return False
    if any(len(s["instructionText"]) > 260 for s in steps) or len(r["title"]) > 32:
        return False
    return True


def crop_photo(src, dest):
    from PIL import Image
    im = Image.open(src).convert("RGB")
    w, h = im.size
    target = 16 / 9
    if w / h > target:
        nw = int(h * target)
        im = im.crop(((w - nw) // 2, 0, (w - nw) // 2 + nw, h))
    else:
        nh = int(w / target)
        im = im.crop((0, (h - nh) // 2, w, (h - nh) // 2 + nh))
    im.resize((640, 360), Image.LANCZOS).save(dest, "JPEG", quality=82, optimize=True, progressive=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("repo", help="path to a clone of based.cooking")
    ap.add_argument("--write", action="store_true", help="add the selected recipes and photos to the app")
    ap.add_argument("--limit", type=int, default=32, help="maximum number of recipes to add (default 32)")
    args = ap.parse_args()

    with io.open(ir.RECIPES, encoding="utf-8") as f:
        catalog = json.load(f)
    existing = {r["id"] for r in catalog["recipes"]}
    existing_titles = {r["title"].lower() for r in catalog["recipes"]}

    picked, groups = [], {}
    for name in sorted(os.listdir(os.path.join(args.repo, "content"))):
        if not name.endswith(".md") or name.startswith("_"):
            continue
        node, pic = parse(os.path.join(args.repo, "content", name))
        if not node or not pic:
            continue
        r = ir.convert(node)
        if not r or not good(r) or r["id"] in existing or r["title"].lower() in existing_titles:
            continue
        r.pop("_imageUrl", None)
        r["id"] = ir.slug(os.path.splitext(name)[0])
        if r["id"] in existing:
            continue
        tags = [t.lower() for t in node["recipeCategory"]]
        if SKIP_TAGS & set(tags):
            continue  # sauces, condiments and liqueurs aren't dishes to cook along with
        # based.cooking has no diet tags; mark vegetarian only when neither the tags, title nor any ingredient
        # mentions meat or fish. When unsure, no tag (never a wrong "vegetarian").
        has_meat = bool(MEAT_TAGS & set(tags)) or bool(MEAT.search(r["title"])) or \
            any(MEAT.search(i["name"]) for i in r["ingredients"])
        r["dietaryTags"] = [] if has_meat else ["vegetarian"]
        cats = {TAG_CATEGORIES[t] for t in tags if t in TAG_CATEGORIES}
        if has_meat:
            cats.discard("sweet")  # "sweet" there means sweet-tasting, e.g. hoisin pork
        r["categories"] = sorted(cats)
        group = (r["categories"] or [t for t in tags if t not in ("quick", "basic", "easy")] or ["other"])[0]
        if groups.get(group, 0) >= MAX_PER_GROUP:
            continue
        photo = os.path.join(args.repo, "static", pic.lstrip("/"))
        if not os.path.exists(photo):
            continue
        groups[group] = groups.get(group, 0) + 1
        picked.append((r, photo))
        if len(picked) >= args.limit:
            break

    for r, _ in picked:
        print("- %-30s %2d ingr, %2d steps, %d timers  %s" % (
            r["title"], len(r["ingredients"]), len(r["steps"]),
            sum("suggestedTimerSeconds" in s for s in r["steps"]), ", ".join(r["dietaryTags"] + r["categories"])))
    if not args.write:
        print("\nPreview: %d recipe(s). Re-run with --write to add them." % len(picked))
        return

    for r, photo in picked:
        dest = os.path.join(ir.ASSETS, "images", r["id"] + ".jpg")
        crop_photo(photo, dest)
        r["image"] = "images/%s.jpg" % r["id"]
        r["description"] = r["description"] or "From based.cooking (public domain)."
        catalog["recipes"].append({k: r[k] for k in ["id", "title", "description", "image", "prepTime", "cookTime",
                                                     "servings", "dietaryTags", "categories", "ingredients", "steps"]})
    with io.open(ir.RECIPES, "w", encoding="utf-8") as f:
        json.dump(catalog, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print("\nAdded %d recipe(s) with photos. Rebuild the app and re-publish the phone page." % len(picked))


if __name__ == "__main__":
    main()
