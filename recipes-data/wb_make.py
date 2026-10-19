import json, os, re, sys, urllib.parse
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rlib, wb_cands
from rlib import ir, db
import wb_meta

CATMAP = {"Indian": "indian", "Punjabi": "north-indian", "Pakistani": "north-indian", "South Indian": "south-indian",
          "Bengali": "indian", "Chinese": "chinese", "Thai": "thai", "Mexican": "mexican", "Japanese": "japanese",
          "Korean": "korean", "Greek": "greek", "Spanish": "spanish", "French": "french", "Italian": "italian",
          "Middle Eastern": "middle-eastern", "Turkish": "middle-eastern", "Lebanese": "middle-eastern",
          "Mediterranean": "mediterranean", "Breakfast recipes": "breakfast", "Snack recipes": "snack",
          "Recipes for soup": "soup", "Recipes for salad": "salad", "Recipes for fried rice": "rice",
          "Recipes for biryani": "rice", "Recipes for risotto": "rice", "Jewish": "middle-eastern",
          "Sri Lankan": "indian", "Nepalese": "indian", "Vietnamese": "", "Indonesian": "", "Malaysian": "",
          "Filipino": "", "Moroccan": "middle-eastern", "Ethiopian": ""}
PASSIVE = ("Mix", "Marinate", "Chill", "Rest", "Soak", "Rise", "Proof", "Freeze", "Stand", "Steep", "Ferment", "Cool", "Infuse", "Set", "Dry")


def cats(c, r):
    out = {CATMAP[x] for x in c["cats"] if CATMAP.get(x)}
    t = r["title"].lower()
    ing = " ".join(i["name"].lower() for i in r["ingredients"])
    if re.search(r"\bsoup|\bchorba|\bpho\b|\bsambar|\bbroth", t): out.add("soup")
    if re.search(r"\bsalad|selatta|\bslaw", t): out.add("salad")
    if re.search(r"\brice\b|pulao|biryani|pilaf|palau|khichdi|pongal|fried rice|risotto", t): out.add("rice")
    if re.search(r"noodle|udon|ramen|pad thai|chow mein|vermicelli", t): out.add("noodles")
    if re.search(r"halwa|pudding|barfi|kheer|kulfi|(?<!pan)cake|cookie|dessert|sweet|custard|flan|mochi|fritters? with syrup|candied|ice cream|halo|brownie|pie\b|donut|churro|jelly|khurma|pastry|biscuit", t): out.add("sweet")
    if re.search(r"lassi|juice|lemonade|coffee|chai|tea\b|smoothie|drink|milkshake|panna|jeera|borhani", t): out.add("drink")
    if re.search(r"omelet|omelette|pancake|porridge|frittata|huevos|egg", t) and "sweet" not in out: out.add("breakfast")
    if re.search(r"naan|bread|roti|chapati|paratha|pita|tortilla|flatbread|biscuit|muffin|scone", t) and not re.search(r"omelet|pudding|tortilla de|tortilla espa|spanish", t): out.add("baking")
    if re.search(r"street|chaat|puri|pakora|samosa|vada|bhaji|tikki", t): out.add("street-food")
    if r["dietaryTags"] and not out & {"sweet", "baking", "drink"} and re.search(r"lentil|dal|dhal|bean|chickpea|tofu|vegetable|salad|soup|sambar|chole|curry", t) and not re.search(r"cream|butter|cheese|ghee|sugar|mayo|fried", ing + t):
        out.add("healthy")
    tot = r["prepTime"] + r["cookTime"]
    if tot <= 30 and not out & {"sweet", "baking"} and (c["cats"] and ({"Very Easy recipes", "Easy recipes"} & set(c["cats"])) or tot <= 20): out.add("quick")
    return sorted(out)


def times(c, r):
    notes = []
    steps = r["steps"]
    if c["total"]:
        prep = min(10, c["total"] // 3)
        return prep, c["total"] - prep, notes
    passive = sum(s.get("suggestedTimerSeconds", 0) for s in steps if s.get("timerLabel") in PASSIVE)
    active = sum(s.get("suggestedTimerSeconds", 0) for s in steps) - passive
    prep = 10 + (5 if len(r["ingredients"]) > 10 else 0) + min(round(passive / 60 / 5) * 5, 480)
    cook = max(10, round(active / 60 / 5) * 5, round(3 * len(steps) / 5) * 5)
    notes.append("times estimated")
    return min(prep, 600), min(cook, 600), notes


def make(c):
    r = {"id": c["id"][:60].strip("-"), "title": wb_meta.TITLES.get(c["id"], c["title"]), "description": wb_meta.DESC[c["id"]],
         "ingredients": c["ings"], "steps": c["steps"]}
    r["dietaryTags"] = [] if c["id"] in wb_meta.NOTAG else rlib.tags_for(c["ings"], r["title"])
    notes = []
    r["servings"] = wb_meta.SERV.get(c["id"]) or c["servings"] or 4
    if not (c["id"] in wb_meta.SERV or c["servings"]): notes.append("servings guessed (4)")
    if not 1 <= r["servings"] <= 20: r["servings"] = min(20, max(1, r["servings"]))
    r["prepTime"], r["cookTime"], n2 = times(c, r)
    notes += n2
    if c["id"] in wb_meta.TIMES: r["prepTime"], r["cookTime"] = wb_meta.TIMES[c["id"]]
    r["categories"] = [x for x in cats(c, r) if x not in wb_meta.CAT_DROP.get(c["id"], ())]
    for x in wb_meta.CAT_ADD.get(c["id"], ()):
        if x not in r["categories"]: r["categories"] = sorted(r["categories"] + [x])
    url = "https://en.wikibooks.org/wiki/" + urllib.parse.quote(c["page"].replace(" ", "_"), safe="/:_(),'!-.~")
    return rlib.finalize(r, "wikibooks", "CC BY-SA 4.0", url, "Wikibooks Cookbook contributors"), notes


def pool():
    tiers = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "wb_tiers.json"), encoding="utf-8"))
    out, why = wb_cands.all_candidates(None)
    return {c["id"]: c for c in out}


def resolve(prefixes, P):
    ids = []
    for p in prefixes:
        m = [k for k in P if k == p] or [k for k in P if k.startswith(p)]
        if len(m) != 1:
            print("!! pick", p, "->", m[:4])
            continue
        ids.append(m[0])
    return ids


if __name__ == "__main__":
    # usage: wb_make.py <batch no> <group name>   (group = key of wb_meta.GROUPS); "view" shows candidates only
    mode, name = sys.argv[1], sys.argv[2]
    P = pool()
    ids = resolve(wb_meta.GROUPS[name], P)
    ex_ids, ex_titles = rlib.online()
    if mode == "view":
        for i in ids:
            c = P[i]
            print("## %s | %s | %s" % (i, c["title"], ",".join(c["cats"])))
            print("  I:", "; ".join(("%g%s %s" % (x["amount"], x["unit"], x["name"][:26]) if x["amount"] is not None else x["name"][:26]) for x in c["ings"]))
            print("  S:", " | ".join("%s%s" % (s["instructionText"][:52], "[T%d]" % (s["suggestedTimerSeconds"] // 60) if "suggestedTimerSeconds" in s else "") for s in c["steps"]))
        sys.exit()
    recs = []
    for i in ids:
        if i not in wb_meta.DESC:
            print("!! no description for", i); continue
        r, notes = make(P[i])
        if r["id"] in ex_ids or r["title"].lower() in ex_titles:
            print("-- duplicate, skipped", i); continue
        recs.append(r)
        print("%-34s %s/%s sv%d %s %s %s" % (r["id"][:34], r["prepTime"], r["cookTime"], r["servings"], r["dietaryTags"], r["categories"], notes))
    path = rlib.write_batch(int(mode), recs)
    print(path, len(recs))
