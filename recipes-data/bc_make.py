import os, sys, json, re
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rlib
from rlib import ir, db
from bc_cands import candidates
import bc_build, bc_meta

TAGMAP = {"italian": "italian", "mexican": "mexican", "indian": "indian", "thai": "thai", "french": "french",
          "spanish": "spanish", "greek": "greek", "american": "american", "chinese": "chinese", "japanese": "japanese",
          "lebanese": "middle-eastern", "turkish": "middle-eastern", "tunisian": "middle-eastern",
          "israeli": "middle-eastern", "mediterranean": "mediterranean", "breakfast": "breakfast",
          "brunch": "breakfast", "snack": "snack", "cookies": "snack", "dessert": "sweet", "sweet": "sweet",
          "sweets": "sweet", "soup": "soup", "salad": "salad", "drink": "drink", "bread": "baking", "baking": "baking",
          "muffins": "baking", "cake": "baking", "dough": "baking", "quick": "quick", "icecream": "sweet",
          "pancake": "breakfast", "noodles": "noodles", "korean": "korean"}

def cats(fid, tags, r):
    c = {TAGMAP[t] for t in tags if t in TAGMAP}
    title = r["title"].lower()
    ing = " ".join(i["name"].lower() for i in r["ingredients"])
    if re.search(r"\b(rice|pilaf|risotto|paella)\b", title): c.add("rice")
    if re.search(r"noodle|chow mein|ramen", title): c.add("noodles")
    if "tabouleh" in title or "slaw" in title: c.add("salad")
    rich = re.search(r"mayo|cream|butter|bacon|cheese|sugar|syrup|yogurt|flour|egg|oil\b", ing)
    if r["dietaryTags"] and not rich and ({"soup", "salad"} & c or re.search(r"lentil|bean|chickpea|tofu|vegetable|dahl|dal\b", title)):
        c.add("healthy")
    tot = r["prepTime"] + r["cookTime"]
    if 0 < tot <= 25 and not c & {"sweet", "baking"}: c.add("quick")
    if c & {"sweet"} and "snack" in c and "baking" in c: pass
    if "indian" in c and "north-indian" not in c and re.search(r"masala|paneer|tikka|dahl|dal\b|chai|tea", title): c.add("north-indian")
    return sorted(c)

def make(fid, name, tags, node, r, intro):
    r2 = bc_build.build(fid, name, tags, node, r, intro)
    r2["description"] = bc_meta.DESC[fid]
    r2["categories"] = [c for c in cats(fid, tags, r2) if c not in bc_meta.CAT_DROP.get(fid, ())]
    notes = []
    if r2["prepTime"] == 0 and r2["cookTime"] == 0:
        passive = sum(s.get("suggestedTimerSeconds", 0) for s in r2["steps"] if s.get("timerLabel") in ("Mix", "Marinate", "Chill", "Rest", "Soak", "Rise", "Proof", "Freeze", "Stand"))
        active = sum(s.get("suggestedTimerSeconds", 0) for s in r2["steps"]) - passive
        r2["prepTime"] = 10 + round(passive / 60 / 5) * 5
        r2["cookTime"] = max(5, round(active / 60 / 5) * 5) if active else 15
        notes.append("time estimated from step timers")
    if fid in bc_meta.TITLES: r2["title"] = bc_meta.TITLES[fid]
    if not node.get("recipeYield") and fid not in bc_meta.SERV: notes.append("servings guessed (2)")
    if fid in bc_meta.NOTAG: r2["dietaryTags"] = []
    if fid in bc_meta.TIMES: r2["prepTime"], r2["cookTime"] = bc_meta.TIMES[fid]
    if fid in bc_meta.SERV: r2["servings"] = bc_meta.SERV[fid]
    for c in bc_meta.CAT_ADD.get(fid, ()):
        if c not in r2["categories"]: r2["categories"] = sorted(r2["categories"] + [c])
    if not re.search(r"servings", open(os.path.join(bc_build.__dict__.get("REPO", ""), "x"), "a").name if False else "servings"): pass
    return rlib.finalize(r2, "based-cooking", "Public domain", "https://based.cooking/%s/" % os.path.splitext(name)[0],
                         "based.cooking contributors"), notes

def all_ready():
    ex_ids, ex_titles = rlib.online()
    byfid = {c[0]: c for c in candidates()}
    out = []
    for fid in bc_meta.DESC:
        if fid in bc_meta.SKIP or not bc_meta.DESC[fid] or fid not in byfid: continue
        c = byfid[fid]
        r, notes = make(*c)
        if bc_build.eligible(r) or r["title"].lower() in ex_titles or r["id"] in ex_ids: continue
        out.append((r, notes))
    return out

if __name__ == "__main__":
    n = int(sys.argv[1]); size = int(sys.argv[2]) if len(sys.argv) > 2 else 20
    ready = all_ready()
    print(len(ready), "ready")
    pick = ready[:size]
    path = rlib.write_batch(n, [r for r, _ in pick])
    print(path)
    for r, notes in pick:
        print("%-30s %s/%s sv%d %s %s %s" % (r["id"][:30], r["prepTime"], r["cookTime"], r["servings"], r["dietaryTags"], r["categories"], notes))
