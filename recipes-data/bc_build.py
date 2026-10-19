import os, sys, json, re
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rlib
from rlib import ir, db
from bc_cands import candidates
import import_based_cooking as ibc

def build(fid, name, tags, node, r, intro):
    texts = node["recipeInstructions"]
    ings = rlib.to_unit_ingredients(node["recipeIngredient"])
    steps = rlib.build_steps(texts, ings)
    r2 = dict(r); r2["id"] = fid; r2["ingredients"] = ings; r2["steps"] = steps
    r2["dietaryTags"] = rlib.tags_for(ings, r["title"])
    meat_tag = bool(ibc.MEAT_TAGS & set(tags))
    if meat_tag: r2["dietaryTags"] = []
    return r2

def eligible(r):
    ings, steps = r["ingredients"], r["steps"]
    if not (3 <= len(ings) <= 20 and 2 <= len(steps) <= 12): return "counts %d/%d" % (len(ings), len(steps))
    if sum(i["amount"] is not None for i in ings) < 0.6 * len(ings): return "few amounts"
    if len(r["title"]) > 32: return "title"
    if not 1 <= r["servings"] <= 20: return "servings"
    return None

if __name__ == "__main__":
    ex_ids, existing = rlib.online()
    ok = []; skipped = []
    for fid, name, tags, node, r, intro in candidates():
        r2 = build(fid, name, tags, node, r, intro)
        why = eligible(r2)
        if r2["title"].lower() in existing: why = "exists"
        if why: skipped.append((fid, why)); continue
        ok.append((fid, tags, r2, intro))
    print(len(ok), "eligible;", len(skipped), "skipped")
    from collections import Counter
    print(Counter(w for _, w in skipped).most_common(8))
    for fid, tags, r2, intro in ok:
        print("%-34s|%s|%s|%s" % (fid, ",".join(tags), r2["title"], intro[:90]))
