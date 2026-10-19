"""Shared helpers for building recipe batches (data only; imports the repo's tools without modifying them)."""
import io
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "tools"))
import import_recipes as ir  # noqa: E402
import recipe_db as db  # noqa: E402

VEG_BAD = re.compile(db.MEAT.pattern + r"|stock|broth|bouillon|parmesan|parmigiano|pecorino|rennet|dripping|"
                     r"bone|gelatin|shrimp paste|kielbasa|wurst|korv|guanciale|mortadella|venison|rabbit|suet|"
                     r"pesto|oyster|nam pla|bonito|dashi|isinglass|collagen|jamon|bresaola|capers? brine", re.I)
DAIRY = re.compile(r"(milk|butter|ghee|cream|cheese|paneer|yogurt|yoghurt|curd|whey|feta|parmesan|mozzarella|"
                   r"custard|chocolate|ice cream|kefir|ricotta|mascarpone|casein|buttermilk|khoa|khoya|dahi|"
                   r"malai|halloumi|quark|skyr|margarine|bread|cake|biscuit|cookie|cracker|sauce|stock|broth|"
                   r"bouillon|mayo|pesto|dressing|naan|roti|pastry|pie|tortilla|wafer|nutella)", re.I)
EGGHONEY = re.compile(r"(egg|honey|mayonnaise|gelatin|albumen|meringue)", re.I)
GLUTEN = re.compile(r"(flour|wheat|pasta|spaghetti|macaroni|noodle|bread|crumb|soy sauce|tamari|sauce|stock|broth|"
                    r"bouillon|beer|ale\b|malt|barley|rye|couscous|semolina|rava|sooji|suji|seitan|cracker|biscuit|"
                    r"cookie|cake|pastry|dough|oat|bulgur|farro|spelt|tortilla|naan|roti|chapati|paratha|puri|"
                    r"dumpling|wrapper|pie|crust|batter|cereal|granola|muesli|gnocchi|panko|bun|roll|pita|lasagn|"
                    r"orzo|vermicelli|udon|ramen|beer|vinegar|ketchup|mustard|curry paste|miso|sausage|ham\b|"
                    r"seasoning|mix\b|powder blend|masala blend|baking powder|self-raising|self-rising|semolina|"
                    r"atta|maida|maida|besan x)", re.I)


VEGAN_UNSURE = re.compile(r"(pasta|noodle|macaroni|spaghetti|bread|naan|roti|dough|pastry|puff|tortilla|wrapper|chocolate|"
                          r"wine|beer|margarine|mayo|biscuit|cookie|cracker|cake|pesto|dumpling|gnocchi|lasagn|bun\b)", re.I)


def tags_for(ings, title=""):
    """Conservative diet tags from the ingredient list only (never claims safety when unsure)."""
    names = " ".join(i["name"] for i in ings)
    tags = []
    if not VEG_BAD.search(names + " " + title):
        tags.append("vegetarian")
        if not db.ANIMAL.search(names) and not EGGHONEY.search(names) and not VEGAN_UNSURE.search(names):
            tags = ["vegan"]
    return tags


def finalize(r, source, license_, url, credit):
    r = dict(r)
    r.pop("_imageUrl", None)
    r.pop("image", None)
    r.pop("imageCredit", None)
    r["source"], r["license"], r["sourceUrl"], r["credit"] = source, license_, url, credit
    order = ["id", "title", "description", "prepTime", "cookTime", "servings", "dietaryTags", "categories",
             "ingredients", "steps", "source", "license", "sourceUrl", "credit"]
    return {k: r[k] for k in order if k in r}


def split_text(t, limit=215):
    """Split one long instruction into chunks <= limit, preferring sentence then clause boundaries."""
    t = re.sub(r"\s+", " ", t).strip()
    if len(t) <= limit:
        return [t]
    sents = re.split(r"(?<=[.!?])\s+(?=[A-Z0-9])", t)
    pieces = []
    for s in sents:
        while len(s) > limit:
            cut = max(s.rfind("; ", 0, limit), s.rfind(", ", 0, limit), s.rfind(" and ", 0, limit),
                      s.rfind(" then ", 0, limit))
            if cut < 60:
                cut = s.rfind(" ", 0, limit)
            pieces.append(s[:cut].rstrip(",; ") + ("." if not s[:cut].rstrip().endswith(".") else ""))
            s = s[cut:].lstrip(",; ")
            s = s[0].upper() + s[1:] if s else s
        if s:
            pieces.append(s)
    out, cur = [], ""
    for p in pieces:
        if cur and len(cur) + 1 + len(p) <= limit and len(cur) < 90:
            cur += " " + p
        elif cur and len(cur) + 1 + len(p) <= limit and len(p) < 40:
            cur += " " + p
        else:
            if cur:
                out.append(cur)
            cur = p
    if cur:
        out.append(cur)
    return out


WORDNUM = {"one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "seven": 7, "eight": 8, "nine": 9,
           "ten": 10, "fifteen": 15, "twenty": 20, "thirty": 30, "forty": 40, "forty-five": 45, "sixty": 60}
_N = r"(\d+(?:\.\d+)?|" + "|".join(sorted(WORDNUM, key=len, reverse=True)) + r")"
TIMER_RE = re.compile(r"(?:\b" + _N + r"(?:\s*(?:-|–|to|or)\s*" + _N + r")?\s*(hours?|hrs?|minutes?|mins?|seconds?|secs?)\b"
                      r"|\b(half an hour|an hour|a minute)\b)", re.I)


def _num(x):
    return float(x) if re.match(r"^\d", x) else float(WORDNUM[x.lower()])


def find_timer(text):
    """First stated duration in a step -> (seconds, start index) or None. Ranges use the upper bound."""
    mh = re.search(r"\b(\d+)\s*(?:h|hr|hrs|hours?)\s*(?:and\s*)?(\d+)\s*(?:m|mins?|minutes?)\b", text, re.I)
    if mh:
        return int(mh.group(1)) * 3600 + int(mh.group(2)) * 60, mh.start()
    mh = re.search(r"\b(\d+)\s+(\d)/(\d)\s*(?:hours?|hrs?)\b", text, re.I)
    if mh:
        return int((int(mh.group(1)) + int(mh.group(2)) / int(mh.group(3))) * 3600), mh.start()
    for m in TIMER_RE.finditer(text):
        if m.group(4):
            secs = {"half an hour": 1800, "an hour": 3600, "a minute": 60}[m.group(4).lower()]
        else:
            n = _num(m.group(2) or m.group(1))
            u = m.group(3).lower()
            secs = int(n * 3600 if u.startswith("h") else n * 60 if u.startswith("m") else n)
        if 10 <= secs <= 6 * 3600:
            return secs, m.start()
    return None


LABELS = [("simmer", "Simmer"), ("boil", "Boil"), ("bak", "Bake"), ("roast", "Roast"), ("fry", "Fry"),
          ("sauté", "Sauté"), ("saute", "Sauté"), ("grill", "Grill"), ("steam", "Steam"), ("marinat", "Marinate"),
          ("soak", "Soak"), ("chill", "Chill"), ("refrigerat", "Chill"), ("freez", "Freeze"), ("rest", "Rest"),
          ("rise", "Rise"), ("proof", "Proof"), ("knead", "Knead"), ("steep", "Steep"), ("cool", "Cool"),
          ("stand", "Stand"), ("sit", "Rest"), ("cook", "Cook"), ("heat", "Heat"), ("brown", "Brown"),
          ("toast", "Toast"), ("blend", "Blend"), ("reduce", "Reduce"), ("whisk", "Whisk"), ("stir", "Stir"),
          ("mix", "Mix"), ("preheat", "Preheat"), ("wait", "Wait"), ("set", "Set"), ("ferment", "Ferment"),
          ("infus", "Infuse"), ("dry", "Dry"), ("drain", "Drain"), ("microwave", "Microwave")]


def label_for(t, pos=None):
    tl = t.lower()
    head = tl[:pos] if pos else tl
    best = None
    for k, v in LABELS:
        for m in re.finditer(r"\b" + k, head):
            if best is None or m.start() > best[0]:
                best = (m.start(), v)
    if best:
        return best[1]
    for k, v in LABELS:
        if re.search(r"\b" + k, tl):
            return v
    return "Timer"


VERBANIM = {}
for _v, _a in [("chop slice dice mince peel cut crush grate trim shred julienne halve quarter core", "chop"),
               ("stir fold", "stir"),
               ("mix combine whisk beat knead toss blend mash season sift cream", "mix"),
               ("fry saute sauté brown sear toast sweat heat", "fry"),
               ("boil simmer steam poach bring", "boil"),
               ("bake roast preheat broil grill", "bake"),
               ("pour drizzle", "pour"),
               ("rest chill cool refrigerate leave soak marinate marinade rise proof ferment", "rest"),
               ("serve garnish plate", "serve"), ("microwave", "microwave")]:
    for _w in _v.split():
        VERBANIM[_w] = _a


def anim_for(t):
    words = re.findall(r"[A-Za-zé]+", t.lower())
    for w in words[:3]:
        if w in VERBANIM:
            return VERBANIM[w]
    return None


def build_steps(texts, ingredients, explicit=None):
    """texts: list of instruction strings (split if too long). Adds timers/animations/related ingredient ids."""
    chunks = []
    for t in texts:
        # a step holding two separate durations is split per sentence so each gets its own timer
        sents = re.split(r"(?<=[.!?])\s+(?=[A-Z])", t.strip())
        if len(sents) > 1 and sum(1 for x in sents if find_timer(x)) >= 2:
            for x in sents:
                chunks += split_text(x)
        else:
            chunks += split_text(t)
    steps = []
    for i, t in enumerate(chunks, 1):
        st = {"id": "s%d" % i, "order": i, "instructionText": t, "relatedIngredientIds": ir.related(t, ingredients)}
        a = anim_for(t)
        if a:
            st["animation"] = a
        ft = find_timer(t)
        if ft:
            st["suggestedTimerSeconds"] = ft[0]
            st["timerLabel"] = label_for(t, ft[1])
        steps.append(st)
    return steps


def _conv(line):
    """Pre-pass for units import_recipes doesn't know: quarts, pints, sticks, dashes, (optional) prefixes."""
    s = re.sub(r"[❄✅❗️\U0001F000-\U0001FFFF]", "", line).strip()
    s = re.sub(r"\s*⁄\s*", "/", s)                       # fraction slash
    s = re.sub(r"^(?:~|approx\.?|approximately|about|around)\s*", "", s, flags=re.I)
    s = re.sub(r"\s*\((?:g|ml|tbsp\.?|tsp\.?|kg|l|oz|lb)\)\s*(?:of\s+)?", " ", s)
    s = re.sub(r"^(\d+(?:/\d+)?)\s*ea\.?\s*(?:\([^)]*\)\s*)?", r"\1 ", s, flags=re.I)
    mp = re.match(r"^[\d./ ]+\s*(?:cups?|tablespoons?|teaspoons?)?\s*(?:and|plus)\s+[\d./ ]+\s*(?:cups?|tablespoons?|teaspoons?)\s*\((\d+)\s*(ml|g)\)\s*(.*)$", s, re.I)
    if mp:  # "1/4 cup plus 1 tablespoon (75 ml) sugar" -> "75 ml sugar"
        s = "%s %s %s" % (mp.group(1), mp.group(2).lower(), mp.group(3))
    s = re.sub(r"^(\d+(?:\.\d+)?)\s*\+\s*", r"\1 ", s)
    opt = False
    m = re.match(r"^\(?optional\)?:?\s*(.*)$", s, re.I)
    if m:
        s, opt = m.group(1), True
    for vf, vv in ir.VULGAR.items():
        s = re.sub(r"(\d)" + vf, r"\1 " + vv, s).replace(vf, vv)

    def mult(mo, factor, unit):
        n = ir.parse_number(mo.group(1))
        v = round(n * factor, -1) if n else None
        if v and unit == "ml" and v >= 1000:
            return "%s l " % round(v / 1000, 1)
        return "%s %s " % (v if v else mo.group(1), unit)
    s = re.sub(r"^(\d+\s+\d+/\d+|\d+/\d+|\d+(?:\.\d+)?)\s*(?:quarts?|qts?\.?)\s+(?:of\s+)?", lambda mo: mult(mo, 946, "ml"), s, flags=re.I)
    s = re.sub(r"^(\d+\s+\d+/\d+|\d+/\d+|\d+(?:\.\d+)?)\s*(?:pints?|pt\.?)\s+(?:of\s+)?", lambda mo: mult(mo, 473, "ml"), s, flags=re.I)
    s = re.sub(r"^(\d+\s+\d+/\d+|\d+/\d+|\d+(?:\.\d+)?)\s*sticks?\s+(?:of\s+)?(butter|margarine)", lambda mo: mult(mo, 113, "g") + mo.group(2), s, flags=re.I)
    s = re.sub(r"^(\d+(?:/\d+)?)\s*dash(?:es)?\s+(?:of\s+)?", r"\1 pinch ", s, flags=re.I)
    s = re.sub(r"^(?:a\s+)?(?:pinch|dash) of\s+", "", s, flags=re.I) if False else s
    if opt:
        s += ", optional"
    return s


def to_unit_ingredients(lines):
    used = set()
    out = []
    for ln in lines:
        if ln.strip().endswith(":") and not re.search(r"\d", ln):
            continue  # a sub-heading such as "Spices:", not an ingredient
        ing = ir.parse_ingredient(_conv(ln), used)
        ing["id"] = ing["id"].strip("-") or "item"
        if len(ing["name"]) > 60:
            n = re.sub(r"\s*\([^)]*\)", "", ing["name"]).strip(" ,")
            if len(n) > 60:
                n = n[:60].rsplit(" ", 1)[0].rstrip(" ,")
            ing["name"] = n
        if ing["amount"] is None:
            ing["unit"] = "to taste"
        if len(ing["name"]) > 60:
            ing["name"] = ing["name"][:57].rstrip() + "..."
        out.append(ing)
    return out


def write_batch(n, recipes):
    path = os.path.join(HERE, "batch-%03d.json" % n)
    with io.open(path, "w", encoding="utf-8") as f:
        json.dump({"recipes": recipes}, f, ensure_ascii=False, indent=1)
        f.write("\n")
    return path


def online():
    """(ids, lowercase titles) already online or bundled or in local batch files."""
    items = db.existing()
    ids = {i["id"] for i in items}
    titles = {i["title"].lower() for i in items}
    return ids, titles
