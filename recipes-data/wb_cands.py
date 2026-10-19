import json, os, re, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rlib, wb_parse, wb_api
from rlib import ir, db

TITLES = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "wb_titles.json"), encoding="utf-8"))


def short_title(t):
    t = re.sub(r"\s+", " ", t).strip()
    if len(t) > 32:
        t2 = re.sub(r"\s*\([^)]*\)", "", t).strip()
        if t2 and len(t2) <= 32:
            t = t2
    return t


def page_slug(title):
    return ir.slug(re.sub(r"^Cookbook:", "", title))


def eligible(title, ings, steps, sv):
    if not (3 <= len(ings) <= 20 and 2 <= len(steps) <= 12):
        return "counts %d/%d" % (len(ings), len(steps))
    if sum(i["amount"] is not None for i in ings) < 0.6 * len(ings):
        return "few amounts"
    if len(short_title(re.sub(r"^Cookbook:", "", title))) > 32:
        return "title"
    if any(len(s["instructionText"]) > 220 for s in steps):
        return "long step"
    return None


def all_candidates(titles=None):
    cache = wb_parse.load_cache()
    out, why = [], {}
    for t, v in cache.items():
        if titles is not None and t not in titles:
            continue
        if not t.startswith("Cookbook:"):
            continue
        node, reason = wb_parse.parse(t, v["text"])
        if not node:
            why[t] = reason
            continue
        ings, steps, sv, total = wb_parse.build(t, node)
        e = eligible(t, ings, steps, sv)
        if e:
            why[t] = e
            continue
        out.append({"page": t, "id": page_slug(t), "title": short_title(node["title"]), "ings": ings, "steps": steps,
                    "servings": sv, "total": total, "rev": v["rev"], "cats": TITLES.get(t, []), "fields": node["fields"]})
    return out, why


if __name__ == "__main__":
    from collections import Counter
    tiers = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "wb_tiers.json"), encoding="utf-8"))
    sel = set(tiers["t1"])
    out, why = all_candidates(sel)
    print(len(out), "eligible of", len(sel))
    print(Counter(w.split(" ")[0] + " " + (w.split(" ")[1] if " " in w else "") for w in why.values()).most_common(10))
