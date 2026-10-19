"""Imports recipes from a RecipeSage export (or any schema.org Recipe JSON-LD) into CookAlong.

RecipeSage: Side menu > Settings > Export Recipe Data > JSON-LD. Save the file, then run from the repo root:

    python tools/import_recipes.py path/to/recipesage-export.json            # preview only, writes nothing
    python tools/import_recipes.py path/to/recipesage-export.json --write    # add to app/src/main/assets/recipes.json
    python tools/import_recipes.py export.json --write --images              # also download each recipe's photo

What it does per recipe:
  - ingredients like "2 1/2 cups flour, sifted" become amount 2.5, unit "cup", name "flour, sifted"
    (lines it can't split keep the whole text with no amount, so nothing is lost)
  - steps come from recipeInstructions; "simmer for 10 minutes" becomes a 10:00 step timer
  - prep/cook times (ISO 8601 like PT15M, or "15 minutes"), servings from recipeYield
  - diet tags (vegetarian, vegan, gluten-free, dairy-free) and categories (indian, breakfast, snack, sweet)
    from recipeCategory / recipeCuisine / keywords
  - recipes whose id already exists are skipped (use --replace to overwrite them)
Without --images, recipes get CookAlong's generated placeholder art. After importing, rebuild the app and
re-publish the phone page (python tools/build_site.py, then commit + push inside site/).
"""
import argparse
import io
import json
import os
import re
import sys
import urllib.request
from fractions import Fraction

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
RECIPES = os.path.join(ASSETS, "recipes.json")

UNITS = {
    "cup": "cup", "cups": "cup", "c": "cup",
    "tablespoon": "tbsp", "tablespoons": "tbsp", "tbsp": "tbsp", "tbs": "tbsp", "tbl": "tbsp", "T": "tbsp",
    "teaspoon": "tsp", "teaspoons": "tsp", "tsp": "tsp", "t": "tsp",
    "gram": "g", "grams": "g", "g": "g", "gr": "g",
    "kilogram": "kg", "kilograms": "kg", "kg": "kg",
    "milliliter": "ml", "milliliters": "ml", "millilitre": "ml", "millilitres": "ml", "ml": "ml",
    "dl": "dl", "deciliter": "dl", "decilitre": "dl", "cl": "cl",  # converted to ml below
    "spoon": "tbsp", "spoons": "tbsp",
    "liter": "l", "liters": "l", "litre": "l", "litres": "l", "l": "l",
    "ounce": "oz", "ounces": "oz", "oz": "oz",
    "pound": "lb", "pounds": "lb", "lb": "lb", "lbs": "lb",
    "pinch": "pinch", "pinches": "pinch",
    "clove": "clove", "cloves": "clove",
    "can": "can", "cans": "can", "tin": "can", "tins": "can",
    "handful": "handful", "handfuls": "handful",
    "slice": "slice", "slices": "slice",
    "sprig": "sprig", "sprigs": "sprig",
}
VULGAR = {"½": "1/2", "⅓": "1/3", "⅔": "2/3", "¼": "1/4", "¾": "3/4", "⅛": "1/8", "⅕": "1/5", "⅙": "1/6"}
NUM = r"(\d+\s+\d+/\d+|\d+/\d+|\d+(?:[.,]\d+)?)"
DIETS = {"vegetarian": "vegetarian", "vegan": "vegan", "gluten-free": "gluten-free", "gluten free": "gluten-free",
         "dairy-free": "dairy-free", "dairy free": "dairy-free"}
CATEGORIES = {"indian": "indian", "breakfast": "breakfast", "brunch": "breakfast", "snack": "snack",
              "appetizer": "snack", "salad": "snack", "side": "snack", "dessert": "sweet", "sweet": "sweet",
              "drink": "sweet", "beverage": "sweet", "baking": "sweet"}


def as_list(v):
    if v is None:
        return []
    return v if isinstance(v, list) else [v]


def text_of(v):
    if isinstance(v, dict):
        return (v.get("text") or v.get("name") or "").strip()
    return str(v).strip() if v is not None else ""


def slug(s):
    return re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")[:60] or "recipe"


def minutes(v):
    """ISO 8601 duration (PT1H20M) or free text ("20 minutes", "1 hr")."""
    s = text_of(as_list(v)[0]) if as_list(v) else ""
    if not s:
        return 0
    m = re.fullmatch(r"P(?:\d+D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?", s.strip(), re.I)
    if m and any(m.groups()):
        h, mi, sec = (int(g or 0) for g in m.groups())
        return h * 60 + mi + (1 if sec else 0)
    h = re.search(r"(\d+)\s*(?:h|hr|hrs|hour|hours)\b", s, re.I)
    mi = re.search(r"(\d+)\s*(?:m|min|mins|minute|minutes)\b", s, re.I)
    if h or mi:
        return (int(h.group(1)) * 60 if h else 0) + (int(mi.group(1)) if mi else 0)
    return int(s) if s.isdigit() else 0


def parse_number(s):
    s = s.replace(",", ".").strip()
    try:
        return float(sum(Fraction(p) for p in s.split()))
    except (ValueError, ZeroDivisionError):
        return None


def parse_ingredient(line, used_ids):
    """'2 1/2 cups flour, sifted' -> {amount 2.5, unit 'cup', name 'flour, sifted'}."""
    raw = re.sub(r"\s+", " ", line).strip()
    s = raw
    for k, v in VULGAR.items():
        s = re.sub(r"(\d)" + k, r"\1 " + v, s).replace(k, v)
    amount, unit, name = None, "", s
    m = re.match(NUM + r"(?:\s*(?:-|–|to)\s*" + NUM + r")?\s*(.*)$", s)
    if m:
        amount = parse_number(m.group(1))
        rest = m.group(3).strip()
        w = re.match(r"([A-Za-z]+)\.?\s+(?:of\s+)?(.*)$", rest)
        if w and (w.group(1) in UNITS or w.group(1).lower() in UNITS):
            unit = UNITS.get(w.group(1)) or UNITS[w.group(1).lower()]
            rest = w.group(2)
        # drop a second, equivalent measurement: "100 g / 1/2 cups butter" -> "butter"
        rest = re.sub(r"^(?:/|or)\s*" + NUM + r"\s*[A-Za-z]+\.?\s+(?:of\s+)?", "", rest)
        # ...or in brackets first: "(or 3/4 cup) of flour", "(1 3/4 oz) vermicelli" -> "flour", "vermicelli"
        rest = re.sub(r"^\((?:or\s+)?[^)]*\d[^)]*\)\s*(?:of\s+)?", "", rest, flags=re.I)
        rest = re.sub(r"^of\s+", "", rest, flags=re.I)
        name = rest or raw
    name = name.strip(" ,")
    base = slug(re.split(r"[,(]", name)[0])[:24] or "item"
    iid, n = base, 2
    while iid in used_ids:
        iid, n = "%s-%d" % (base, n), n + 1
    used_ids.add(iid)
    if amount is None or amount <= 0:
        return {"id": iid, "name": raw, "amount": None, "unit": ""}
    if unit in ("dl", "cl"):  # decilitres/centilitres (common in Nordic recipes) -> ml, which the app scales
        amount, unit = amount * (100 if unit == "dl" else 10), "ml"
    return {"id": iid, "name": name, "amount": round(amount, 3), "unit": unit}


def instructions(v):
    out = []
    for item in as_list(v):
        if isinstance(item, dict) and item.get("itemListElement") is not None:  # HowToSection
            out += instructions(item["itemListElement"])
        else:
            t = text_of(item)
            out += [p.strip() for p in re.split(r"\n+", t) if p.strip()]
    # drop "1." / "Step 2:" prefixes RecipeSage keeps from plain-text recipes
    return [re.sub(r"^(?:step\s*)?\d+[.):]\s*", "", s, flags=re.I) for s in out]


def step_timer(text):
    m = re.search(r"(\d+)(?:\s*(?:-|–|to)\s*(\d+))?\s*(hours?|hrs?|minutes?|mins?|seconds?|secs?)\b", text, re.I)
    if not m:
        return None
    n = int(m.group(2) or m.group(1))  # for "8-10 minutes" use the upper bound
    u = m.group(3).lower()
    secs = n * 3600 if u.startswith("h") else n * 60 if u.startswith("m") else n
    return secs if 10 <= secs <= 6 * 3600 else None


def stem(word):
    """'potatoes' -> 'potato', 'onions' -> 'onion', so singular and plural mentions both match."""
    if word.endswith("oes"):
        return word[:-2]
    if word.endswith("s") and not word.endswith("ss"):
        return word[:-1]
    return word


def related(text, ingredients):
    t = text.lower()
    ids = []
    for ing in ingredients:
        words = [w for w in re.findall(r"[a-z]+", ing["name"].lower()) if len(w) > 3]
        key = words[-1] if words else None
        # match the main noun ("flour" in "plain flour, sifted")
        main = re.split(r"[,(]", ing["name"].lower())[0]
        nouns = [w for w in re.findall(r"[a-z]+", main) if len(w) > 3]
        if nouns and re.search(r"\b" + re.escape(stem(nouns[-1])), t):
            ids.append(ing["id"])
        elif key and re.search(r"\b" + re.escape(stem(key)), t):
            ids.append(ing["id"])
    return ids


def convert(node):
    title = text_of(node.get("name"))
    if not title:
        return None
    used = set()
    ingredients = [parse_ingredient(text_of(x), used) for x in as_list(node.get("recipeIngredient")) if text_of(x)]
    steps_text = instructions(node.get("recipeInstructions"))
    if not steps_text:
        return None
    steps = []
    for i, t in enumerate(steps_text, 1):
        st = {"id": "s%d" % i, "order": i, "instructionText": t, "relatedIngredientIds": related(t, ingredients)}
        timer = step_timer(t)
        if timer:
            st["suggestedTimerSeconds"] = timer
            st["timerLabel"] = "Step %d" % i
        steps.append(st)
    labels = " ".join(text_of(x).lower() for k in ("recipeCategory", "recipeCuisine", "keywords", "suitableForDiet")
                      for x in as_list(node.get(k)))
    diets = sorted({v for k, v in DIETS.items() if k in labels})
    if "vegan" in diets and "vegetarian" in diets:
        diets.remove("vegetarian")
    cats = sorted({v for k, v in CATEGORIES.items() if k in labels})
    yields = [parse_number(m) for m in re.findall(r"\d+", " ".join(text_of(y) for y in as_list(node.get("recipeYield"))))]
    servings = int(yields[0]) if yields and yields[0] else 2
    prep, cook = minutes(node.get("prepTime")), minutes(node.get("cookTime"))
    if not prep and not cook:
        cook = minutes(node.get("totalTime"))
    images = [text_of(i) if not isinstance(i, dict) else i.get("url", "") for i in as_list(node.get("image") or node.get("images"))]
    return {
        "id": slug(title),
        "title": title,
        "description": re.sub(r"\s+", " ", text_of(node.get("description")))[:140],
        "prepTime": prep, "cookTime": cook, "servings": max(1, min(servings, 50)),
        "dietaryTags": diets, "categories": cats,
        "ingredients": ingredients, "steps": steps,
        "_imageUrl": next((u for u in images if u.startswith("http")), None),
    }


def recipe_nodes(data):
    if isinstance(data, list):
        for d in data:
            yield from recipe_nodes(d)
    elif isinstance(data, dict):
        t = data.get("@type")
        if t == "Recipe" or (isinstance(t, list) and "Recipe" in t):
            yield data
        for key in ("@graph", "recipes", "itemListElement"):
            if key in data:
                yield from recipe_nodes(data[key])


def download(url, dest):
    req = urllib.request.Request(url, headers={"User-Agent": "CookAlong-importer"})
    with urllib.request.urlopen(req, timeout=20) as r:
        if int(r.headers.get("Content-Length") or 0) > 8_000_000:
            raise ValueError("image too large")
        data = r.read(8_000_001)
    with open(dest, "wb") as f:
        f.write(data)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("export", help="RecipeSage JSON-LD export (.json)")
    ap.add_argument("--write", action="store_true", help="add the recipes to assets/recipes.json")
    ap.add_argument("--replace", action="store_true", help="overwrite recipes with the same id")
    ap.add_argument("--images", action="store_true", help="download each recipe's photo into assets/images/")
    args = ap.parse_args()

    with io.open(args.export, encoding="utf-8-sig") as f:
        data = json.load(f)
    converted = [r for r in (convert(n) for n in recipe_nodes(data)) if r]
    if not converted:
        sys.exit("No schema.org Recipe entries with a name and instructions were found in that file.")

    with io.open(RECIPES, encoding="utf-8") as f:
        catalog = json.load(f)
    existing = {r["id"]: i for i, r in enumerate(catalog["recipes"])}
    added = skipped = 0
    for r in converted:
        url = r.pop("_imageUrl")
        print("- %s: %d ingredients, %d steps, %d timers, %s" % (
            r["title"], len(r["ingredients"]), len(r["steps"]),
            sum("suggestedTimerSeconds" in s for s in r["steps"]), ", ".join(r["dietaryTags"] + r["categories"]) or "no tags"))
        unsplit = [i["name"] for i in r["ingredients"] if i["amount"] is None]
        if unsplit:
            print("    kept as-is (no amount found): " + "; ".join(unsplit[:5]))
        if r["id"] in existing and not args.replace:
            print("    skipped: a recipe with id '%s' already exists (use --replace)" % r["id"])
            skipped += 1
            continue
        if args.write and args.images and url:
            ext = ".png" if url.lower().split("?")[0].endswith(".png") else ".jpg"
            dest = os.path.join(ASSETS, "images", r["id"] + ext)
            try:
                download(url, dest)
                r["image"] = "images/" + r["id"] + ext
                print("    photo saved: " + os.path.relpath(dest, ROOT))
            except Exception as e:  # keep going; the app shows generated art instead
                print("    photo not downloaded (%s); using generated art" % e)
        ordered = {k: r[k] for k in ["id", "title", "description", "image", "prepTime", "cookTime", "servings",
                                     "dietaryTags", "categories", "ingredients", "steps"] if k in r}
        if r["id"] in existing:
            catalog["recipes"][existing[r["id"]]] = ordered
        else:
            catalog["recipes"].append(ordered)
        added += 1

    if args.write:
        with io.open(RECIPES, "w", encoding="utf-8") as f:
            json.dump(catalog, f, ensure_ascii=False, indent=2)
            f.write("\n")
        print("\nAdded/updated %d recipe(s), skipped %d. Rebuild the app to see them." % (added, skipped))
    else:
        print("\nPreview only: %d recipe(s) ready, %d would be skipped. Re-run with --write to add them." % (added, skipped))


if __name__ == "__main__":
    main()
