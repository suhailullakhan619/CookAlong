"""dsl.py's hand-written text format, for any allowed source (Wikibooks, HowToCook, based.cooking).

    !source wikibooks                      # wikibooks | howtocook | based-cooking (applies to the following recipes)
    @ id | Title | one-line description | prep min | cook min | servings | cat,cat | ref [| tags]
    - 2 tbsp ghee
    - salt                                 # no amount -> "to taste"
    > Step text. A stated duration becomes the step timer automatically.
    > Step text {notimer}                  # no timer even though a duration is mentioned
    > Step text {t=600 Simmer}             # explicit timer seconds + label

ref:  wikibooks -> page title ("Cookbook:Rasam (Tamarind and Tomato Soup)")
      howtocook -> repo path under dishes/ ("vegetable_dish/西红柿炒鸡蛋.md")
      based-cooking -> page slug ("red-lentil-dahl")
tags: omitted/"auto" -> rlib.tags_for (conservative); "none"; or a comma list such as "vegetarian" or
      "vegan,gluten-free". Vegetarian/vegan claims are re-checked against rlib's meat/animal patterns.

    python dsl2.py <batch no> file.txt [...]     # writes batch-NNN.json, skipping ids/titles already online
    python dsl2.py check file.txt [...]          # build and print, write nothing
"""
import os
import re
import sys
import urllib.parse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rlib  # noqa: E402
from rlib import db  # noqa: E402

SOURCES = {
    "wikibooks": ("CC BY-SA 4.0", "Wikibooks Cookbook contributors",
                  lambda ref: "https://en.wikibooks.org/wiki/" + urllib.parse.quote(ref.replace(" ", "_"), safe="/:_(),'!-.~")),
    "howtocook": ("Public domain", "HowToCook contributors",
                  lambda ref: "https://github.com/Anduin2017/HowToCook/blob/master/dishes/" + urllib.parse.quote(ref, safe="/")),
    "based-cooking": ("Public domain", "based.cooking contributors", lambda ref: "https://based.cooking/%s/" % ref),
}
MARK = re.compile(r"\s*\{(notimer|t=(\d+)\s+([^}]+))\}\s*")
GENERIC = set("""seed seeds powder leaf leaves paste juice fresh freshly chopped sliced ground dried whole small large
medium big red green black white yellow hot cold warm finely grated minced optional cooking raw plain sprig piece pieces
and or of the a an for to taste extra virgin light dark thick thin boiled cooked peeled washed soaked split skinless flakes
crushed roasted toasted pods pod stick sticks cut diced into about each some few water oil""".split())


def _stem(w):
    if w.endswith("ies") and len(w) > 4:
        return w[:-2]          # chillies -> chilli
    if w.endswith("oes"):
        return w[:-2]          # tomatoes -> tomato
    if w.endswith("s") and not w.endswith("ss") and len(w) > 3:
        return w[:-1]
    return w


DESCRIPTIVE = set("""fresh freshly chopped sliced finely grated minced diced crushed roasted toasted boiled cooked peeled
washed soaked large small medium big optional cut into about each some few and or of the a an for to""".split())


GENERIC |= {"brown", "golden", "baking", "ice"}


def _words(name):
    main = re.split(r"[,(]", name.lower())[0]
    head, _, purpose = main.partition(" for ")  # "sugar for the crust": "crust" helps pick it but isn't a key
    words = [_stem(w) for w in re.findall(r"[a-z]+", head) if len(w) >= 3 and w not in DESCRIPTIVE]
    ks = [w for w in words if w not in GENERIC] or words[-1:]  # "oil", "water": the generic word is the ingredient
    extra = [_stem(w) for w in re.findall(r"[a-z]+", purpose) if len(w) >= 3 and w not in DESCRIPTIVE]
    return set(words + extra), set(ks)


# words that look like timer verbs but aren't the action here ("over medium heat", "the rest of", "brown sugar")
NOT_VERB = re.compile(r"(?:medium|high|low|gentle|moderate)(?:[- ](?:high|low))?\s+heat|\b(?:over|from|off|on)\s+(?:the\s+)?heat"
                      r"|\brest of\b|\bbrown (?:sugar|rice|bread|lentils?)\b|\bgolden brown\b|\b(?:soaked|boiled|cooked|fried|"
                      r"roasted|toasted|chilled|cooled|heated|steamed|baked|mixed)\b|\bmix(?:ture|er)\b|\bsteamer\b|\bset aside\b"
                      r"|\bbaking (?:paper|sheet|tray|dish|soda|powder|pan|tin)\b|\bfrying pan\b|\b(?:stirring|whisking|turning)\b"
                      r"|\bboiling water\b", re.I)
EXTRA_LABELS = [(r"leave\b", "Rest"), (r"pulse\b", "Blend"), (r"press\b", "Press"), (r"drain\b", "Drain")]


def timer_label(text, pos):
    masked = NOT_VERB.sub(lambda m: " " * len(m.group(0)), text)
    best = None
    for k, v in rlib.LABELS + EXTRA_LABELS:
        for m in re.finditer(r"\b" + k, masked[:pos].lower()):
            if best is None or m.start() > best[0]:
                best = (m.start(), v)
    return best[1] if best else rlib.label_for(masked, pos)


def related(text, ings):
    """Ingredient ids a step mentions. When several share a word ("coriander" seeds vs leaves, "the dal"), the one whose
    name matches the step best wins; ties go to the ingredient listed first."""
    t = text.lower()
    found = lambda w: re.search(r"\b" + re.escape(w), t)
    cands = []
    for n, ing in enumerate(ings):
        words, ks = _words(ing["name"])
        kw = {k for k in ks if found(k)}
        if kw:
            cands.append((-sum(1 for w in words if found(w)), n, ing["id"], kw))
    out, taken = [], set()
    for score, n, iid, kw in sorted(cands):
        if kw - taken:
            out.append((n, iid))
            taken |= kw
    return [iid for n, iid in sorted(out)]


def parse_file(path):
    recipes, cur, source = [], None, None
    for n, raw in enumerate(open(path, encoding="utf-8"), 1):
        line = raw.rstrip("\n")
        if not line.strip() or line.startswith("#"):
            continue
        if line.startswith("!source"):
            source = line.split()[1]
            if source not in SOURCES:
                raise ValueError("%s:%d unknown source %s" % (path, n, source))
        elif line.startswith("@"):
            f = [x.strip() for x in line[1:].split("|")]
            if len(f) not in (8, 9):
                raise ValueError("%s:%d header needs 8 or 9 fields: %s" % (path, n, line))
            cur = {"id": f[0], "title": f[1], "description": f[2], "prep": int(f[3]), "cook": int(f[4]),
                   "servings": int(f[5]), "cats": [c.strip() for c in f[6].split(",") if c.strip()], "ref": f[7],
                   "tags": f[8] if len(f) == 9 else "auto", "source": source, "ings": [], "steps": [], "line": n}
            if not source:
                raise ValueError("%s:%d no !source line before the first recipe" % (path, n))
            recipes.append(cur)
        elif line.startswith("- "):
            cur["ings"].append(line[2:].strip())
        elif line.startswith("> "):
            cur["steps"].append(line[2:].strip())
        else:
            raise ValueError("%s:%d bad line: %s" % (path, n, line))
    return recipes


def diet_tags(spec, ings, title):
    auto = rlib.tags_for(ings, title)
    if spec == "auto":
        return auto
    if spec == "none":
        return []
    tags = [t.strip() for t in spec.split(",") if t.strip()]
    names = " ".join(i["name"] for i in ings)
    if {"vegetarian", "vegan"} & set(tags) and rlib.VEG_BAD.search(names + " " + title):
        raise ValueError("%s: tagged vegetarian but an ingredient looks non-vegetarian" % title)
    if "vegan" in tags and (db.ANIMAL.search(names) or rlib.EGGHONEY.search(names)):
        raise ValueError("%s: tagged vegan but an ingredient looks animal-derived" % title)
    return tags


def build(c):
    ings = rlib.to_unit_ingredients(c["ings"])
    steps = []
    for text in c["steps"]:
        marks = MARK.findall(text)
        clean = MARK.sub(" ", text).strip()
        st = rlib.build_steps([clean], ings)
        if len(st) != 1:
            raise ValueError("%s: step was split in %d (shorten it or keep one duration): %s" % (c["id"], len(st), clean))
        st = st[0]
        st["relatedIngredientIds"] = related(st["instructionText"], ings)
        if "suggestedTimerSeconds" in st:
            st["timerLabel"] = timer_label(st["instructionText"], rlib.find_timer(st["instructionText"])[1])
        for m in marks:
            st.pop("suggestedTimerSeconds", None)
            st.pop("timerLabel", None)
            if m[0] != "notimer":
                st["suggestedTimerSeconds"], st["timerLabel"] = int(m[1]), m[2].strip()
        steps.append(st)
    for i, st in enumerate(steps, 1):
        st["id"], st["order"] = "s%d" % i, i
    r = {"id": c["id"], "title": c["title"], "description": c["description"], "prepTime": c["prep"],
         "cookTime": c["cook"], "servings": c["servings"], "dietaryTags": diet_tags(c["tags"], ings, c["title"]),
         "categories": c["cats"], "ingredients": ings, "steps": steps}
    lic, credit, url = SOURCES[c["source"]]
    return rlib.finalize(r, c["source"], lic, url(c["ref"]), credit)


def show(r):
    print("%-32s %s/%s sv%d %s %s steps=%d timers=%d" % (r["id"][:32], r["prepTime"], r["cookTime"], r["servings"],
          r["dietaryTags"], r["categories"], len(r["steps"]), sum("suggestedTimerSeconds" in s for s in r["steps"])))


if __name__ == "__main__":
    mode, files = sys.argv[1], sys.argv[2:]
    out = []
    for f in files:
        out += [build(c) for c in parse_file(f)]
    ex_ids, ex_titles = rlib.online()
    if mode == "check":
        seen_ids, seen_titles = set(), set()
        for r in out:
            show(r)
            for p in db.problems(r, ex_ids | seen_ids):
                print("    !!", p)
            if r["title"].lower() in ex_titles | seen_titles:
                print("    !! title already online or repeated")
            seen_ids.add(r["id"])
            seen_titles.add(r["title"].lower())
        print(len(out), "recipes checked")
        sys.exit()
    keep, seen = [], set()
    for r in out:
        if r["id"] in ex_ids or r["title"].lower() in ex_titles or r["id"] in seen:
            print("-- duplicate, skipped", r["id"])
            continue
        seen.add(r["id"])
        keep.append(r)
        show(r)
    print(rlib.write_batch(int(mode), keep), len(keep))
