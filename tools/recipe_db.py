"""CookAlong online recipe catalog (DynamoDB table cookalong-recipes, served by GET /recipes).

    python tools/recipe_db.py validate recipes-data/batch-001.json [...]   # check files, print problems
    python tools/recipe_db.py upload   recipes-data/batch-001.json [...]   # validate, then put into DynamoDB
    python tools/recipe_db.py existing                                     # ids + titles already online
    python tools/recipe_db.py seed                                         # upload the app's bundled recipes
    python tools/recipe_db.py count

Batch files use exactly the app's format: {"recipes": [ {...}, ... ]} (see app/src/main/assets/recipes.json).
Each DynamoDB item:
  id, title, categories[], dietaryTags[], totalMinutes, ingredientNames[], source, updatedAt (epoch ms),
  recipe  <- the full recipe JSON as a string (what the app downloads)
Needs: pip install boto3, and AWS credentials allowed to use the table (region ap-south-1).
"""
import io
import json
import os
import re
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BUNDLED = os.path.join(ROOT, "app", "src", "main", "assets", "recipes.json")
REGION = os.environ.get("AWS_REGION", "ap-south-1")
TABLE = os.environ.get("RECIPES_TABLE", "cookalong-recipes")

ID_RE = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")
ANIMATIONS = {"boil", "stir", "chop", "fry", "bake", "mix", "microwave", "pour", "rest", "serve"}
DIETS = {"vegetarian", "vegan", "gluten-free", "dairy-free"}
CATEGORIES = {
    "indian", "breakfast", "snack", "sweet", "quick", "healthy", "italian", "mexican", "chinese", "thai",
    "japanese", "korean", "middle-eastern", "mediterranean", "american", "french", "spanish", "greek", "soup",
    "salad", "rice", "noodles", "baking", "drink", "south-indian", "north-indian", "street-food",
    "british", "australian",
}
UNITS = {"", "g", "kg", "ml", "l", "tsp", "tbsp", "cup", "clove", "can", "handful", "pinch", "sprig", "slice",
         "to taste", "oz", "lb"}
SOURCES = {
    # source id: (display name, allowed licenses)
    "based-cooking": ("based.cooking", {"Public domain"}),
    "wikibooks": ("Wikibooks Cookbook", {"CC BY-SA 4.0", "CC BY-SA 3.0"}),
    "howtocook": ("HowToCook", {"Public domain"}),
    "public-domain": ("Public domain", {"Public domain", "CC0"}),
}
MEAT = re.compile(r"(chicken|beef|pork|bacon|\bham\b|lamb|mutton|veal|sausage|chorizo|salami|pepperoni|prosciutto|"
                  r"pancetta|turkey|duck|fish|salmon|tuna|\bcod\b|anchov|shrimp|prawn|crab|lobster|mussel|clam|squid|"
                  r"oyster sauce|fish sauce|worcestershire|gelatin|lard|\bmince\b|steak|meat)", re.I)
ANIMAL = re.compile(r"(egg|milk|butter|ghee|cream|cheese|paneer|yogurt|yoghurt|curd|honey|mayonnaise|whey|feta|"
                    r"parmesan|mozzarella)", re.I)


def problems(r, known_ids):
    """Returns a list of human-readable problems with one recipe (empty = valid)."""
    p = []
    rid = r.get("id", "")
    if not isinstance(rid, str) or not ID_RE.match(rid) or len(rid) > 60:
        p.append("id must be lowercase-kebab-case, max 60 chars")
    if rid in known_ids:
        p.append("duplicate id (already exists)")
    title = r.get("title", "")
    if not title or len(title) > 32:
        p.append("title required, max 32 characters")
    if len(r.get("description", "")) > 110:
        p.append("description max 110 characters")
    for k in ("prepTime", "cookTime"):
        if not isinstance(r.get(k), int) or not 0 <= r[k] <= 600:
            p.append(f"{k} must be whole minutes 0-600")
    if not isinstance(r.get("servings"), int) or not 1 <= r["servings"] <= 20:
        p.append("servings must be 1-20")
    for t in r.get("dietaryTags", []):
        if t not in DIETS:
            p.append(f"unknown dietary tag '{t}' (allowed: {sorted(DIETS)})")
    for c in r.get("categories", []):
        if c not in CATEGORIES:
            p.append(f"unknown category '{c}' (allowed: {sorted(CATEGORIES)})")
    ings = r.get("ingredients", [])
    if not 2 <= len(ings) <= 20:
        p.append("2-20 ingredients")
    ing_ids = set()
    for i in ings:
        iid = i.get("id", "")
        if not ID_RE.match(str(iid)) or iid in ing_ids:
            p.append(f"ingredient id '{iid}' missing, malformed or repeated")
        ing_ids.add(iid)
        if not i.get("name") or len(i["name"]) > 60:
            p.append(f"ingredient '{iid}': name required, max 60 chars")
        amt = i.get("amount")
        if amt is not None and (not isinstance(amt, (int, float)) or amt <= 0):
            p.append(f"ingredient '{iid}': amount must be a positive number or null")
        if i.get("unit", "") not in UNITS:
            p.append(f"ingredient '{iid}': unit '{i.get('unit')}' not allowed (allowed: {sorted(UNITS)})")
        if amt is None and i.get("unit", "") not in ("", "to taste"):
            p.append(f"ingredient '{iid}': no amount needs unit '' or 'to taste'")
    steps = r.get("steps", [])
    if not 2 <= len(steps) <= 12:
        p.append("2-12 steps")
    if [s.get("order") for s in steps] != list(range(1, len(steps) + 1)):
        p.append("step 'order' must be 1..n in sequence")
    for s in steps:
        txt = s.get("instructionText", "")
        if not txt or len(txt) > 220:
            p.append(f"step {s.get('order')}: instructionText required, max 220 chars")
        for ref in s.get("relatedIngredientIds", []):
            if ref not in ing_ids:
                p.append(f"step {s.get('order')}: unknown ingredient id '{ref}'")
        secs = s.get("suggestedTimerSeconds")
        if secs is not None:
            if not isinstance(secs, int) or not 10 <= secs <= 6 * 3600:
                p.append(f"step {s.get('order')}: suggestedTimerSeconds must be 10-21600")
            if not s.get("timerLabel") or len(s["timerLabel"]) > 14:
                p.append(f"step {s.get('order')}: a timer needs a short timerLabel (max 14 chars)")
        if s.get("animation") is not None and s["animation"] not in ANIMATIONS:
            p.append(f"step {s.get('order')}: animation must be one of {sorted(ANIMATIONS)}")
    names = " ".join(i.get("name", "") for i in ings) + " " + title
    tags = set(r.get("dietaryTags", []))
    if tags & {"vegetarian", "vegan"} and MEAT.search(names):
        p.append("tagged vegetarian/vegan but an ingredient looks like meat or fish")
    if "vegan" in tags and ANIMAL.search(" ".join(i.get("name", "") for i in ings)):
        p.append("tagged vegan but an ingredient looks like dairy, egg or honey")
    if r.get("image"):
        p.append("leave 'image' out: online recipes use generated art until a licensed photo is added")
    # attribution: where it came from and under which license (shown in the app; required for CC BY-SA)
    src = r.get("source")
    if src != "bundled":
        if src not in SOURCES:
            p.append(f"source must be one of {sorted(SOURCES)}")
        elif r.get("license") not in SOURCES[src][1]:
            p.append(f"license for source '{src}' must be one of {sorted(SOURCES[src][1])}")
        if not str(r.get("sourceUrl", "")).startswith("https://"):
            p.append("sourceUrl must be the https link to the original recipe")
        if not r.get("credit") or len(r["credit"]) > 80:
            p.append("credit required (e.g. 'Wikibooks Cookbook contributors'), max 80 chars")
    return p


def load_batch(path):
    with io.open(path, encoding="utf-8-sig") as f:
        data = json.load(f)
    return data["recipes"] if isinstance(data, dict) else data


def table():
    import boto3
    return boto3.resource("dynamodb", region_name=REGION).Table(TABLE)


def existing():
    t, items, kw = table(), [], {"ProjectionExpression": "id, title"}
    while True:
        page = t.scan(**kw)
        items += page.get("Items", [])
        if "LastEvaluatedKey" not in page:
            return items
        kw["ExclusiveStartKey"] = page["LastEvaluatedKey"]


def to_item(r, source):
    r = dict(r)
    r.setdefault("source", source)
    return {
        "id": r["id"], "title": r["title"], "source": r["source"],
        "categories": r.get("categories", []), "dietaryTags": r.get("dietaryTags", []),
        "totalMinutes": int(r.get("prepTime", 0)) + int(r.get("cookTime", 0)),
        "ingredientNames": [re.split(r"[,(]", i["name"])[0].strip().lower() for i in r.get("ingredients", [])],
        "updatedAt": int(time.time() * 1000),
        "recipe": json.dumps(r, ensure_ascii=False, separators=(",", ":")),
    }


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    cmd, files = sys.argv[1], sys.argv[2:]
    if cmd in ("existing", "count"):
        items = existing()
        if cmd == "count":
            print(len(items))
        else:
            for it in sorted(items, key=lambda x: x["id"]):
                print(f'{it["id"]}\t{it["title"]}')
        return
    if cmd == "seed":
        batch = load_batch(BUNDLED)
        with table().batch_writer() as w:
            for r in batch:
                w.put_item(Item=to_item(r, "bundled"))
        print(f"Seeded {len(batch)} bundled recipes")
        return
    if cmd not in ("validate", "upload"):
        sys.exit(__doc__)
    known = {r["id"] for r in load_batch(BUNDLED)}
    known_titles = {r["title"].lower() for r in load_batch(BUNDLED)}
    if cmd == "upload":
        online = existing()
        known |= {it["id"] for it in online}
        known_titles |= {it["title"].lower() for it in online}
    ok, bad, seen = [], 0, set()
    for path in files:
        for r in load_batch(path):
            p = problems(r, known | seen)
            if r.get("title", "").lower() in known_titles:
                p.append("a recipe with this title already exists")
            if p:
                bad += 1
                print(f"✗ {path} :: {r.get('id', '?')}")
                for x in p:
                    print(f"    - {x}")
            else:
                ok.append(r)
                seen.add(r["id"])
                known_titles.add(r["title"].lower())
    print(f"{len(ok)} valid, {bad} with problems")
    if cmd == "upload" and ok:
        with table().batch_writer() as w:
            for r in ok:
                w.put_item(Item=to_item(r, r["source"]))
        print(f"Uploaded {len(ok)} recipes to {TABLE}")
    if bad:
        sys.exit(1)


if __name__ == "__main__":
    main()
