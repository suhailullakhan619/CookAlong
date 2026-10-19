"""Tiny text format for hand-written recipes (HowToCook translations):

    @ id | Title | one-line description | prep min | cook min | servings | cat,cat | repo path
    - 2 tbsp cooking oil
    - 3 eggs
    > Step text (a stated duration becomes the step timer automatically).

Diet tags come from rlib.tags_for (conservative), steps get animations/timers/related ingredients from rlib.
"""
import os, re, sys, urllib.parse
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rlib
from rlib import db

HTC_BASE = "https://github.com/Anduin2017/HowToCook/blob/master/dishes/"


def parse_file(path):
    recipes, cur = [], None
    for raw in open(path, encoding="utf-8"):
        line = raw.rstrip("\n")
        if not line.strip() or line.startswith("#"):
            continue
        if line.startswith("@"):
            f = [x.strip() for x in line[1:].split("|")]
            cur = {"id": f[0], "title": f[1], "description": f[2], "prep": int(f[3]), "cook": int(f[4]),
                   "servings": int(f[5]), "cats": [c for c in f[6].split(",") if c], "path": f[7], "ings": [], "steps": []}
            recipes.append(cur)
        elif line.startswith("- "):
            cur["ings"].append(line[2:].strip())
        elif line.startswith("> "):
            cur["steps"].append(line[2:].strip())
        else:
            raise ValueError("bad line: " + line)
    return recipes


def build(c, source="howtocook"):
    ings = rlib.to_unit_ingredients(c["ings"])
    steps = rlib.build_steps(c["steps"], ings)
    r = {"id": c["id"], "title": c["title"], "description": c["description"], "prepTime": c["prep"],
         "cookTime": c["cook"], "servings": c["servings"], "dietaryTags": rlib.tags_for(ings, c["title"]),
         "categories": c["cats"], "ingredients": ings, "steps": steps}
    url = HTC_BASE + urllib.parse.quote(c["path"], safe="/")
    return rlib.finalize(r, "howtocook", "Public domain", url, "HowToCook contributors")


if __name__ == "__main__":
    n = int(sys.argv[1])
    out = []
    for f in sys.argv[2:]:
        out += [build(c) for c in parse_file(f)]
    ex_ids, ex_titles = rlib.online()
    keep = []
    for r in out:
        if r["id"] in ex_ids or r["title"].lower() in ex_titles:
            print("-- duplicate, skipped", r["id"])
            continue
        keep.append(r)
        print("%-30s %s/%s sv%d %s %s steps=%d timers=%d" % (r["id"], r["prepTime"], r["cookTime"], r["servings"], r["dietaryTags"], r["categories"], len(r["steps"]), sum("suggestedTimerSeconds" in s for s in r["steps"])))
    print(rlib.write_batch(n, keep), len(keep))
