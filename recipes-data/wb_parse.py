"""Wikibooks Cookbook wikitext -> candidate recipes (CC BY-SA). Fetches in bulk (cached), 1 request/second."""
import json
import os
import re
import sys
import html

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rlib  # noqa: E402
import wb_api  # noqa: E402
from rlib import ir  # noqa: E402

CACHE = os.path.join(wb_api.SCR, "pages.json")


def load_cache():
    return json.load(open(CACHE, encoding="utf-8")) if os.path.exists(CACHE) else {}


def fetch(titles):
    cache = load_cache()
    todo = [t for t in titles if t not in cache]
    for i in range(0, len(todo), 40):
        chunk = todo[i:i + 40]
        d = wb_api.api(action="query", prop="revisions", rvprop="content|ids", rvslots="main", titles="|".join(chunk))
        got = set()
        for p in d["query"]["pages"]:
            rev = (p.get("revisions") or [{}])[0]
            cache[p["title"]] = {"text": rev.get("slots", {}).get("main", {}).get("content", ""), "rev": rev.get("revid")}
            got.add(p["title"])
        for t in chunk:  # normalized titles may differ
            if t not in cache:
                cache[t] = {"text": "", "rev": None}
        json.dump(cache, open(CACHE, "w", encoding="utf-8"), ensure_ascii=False)
        print("fetched", i + len(chunk), "/", len(todo), file=sys.stderr)
    return cache


def strip_markup(s):
    s = re.sub(r"<!--.*?-->", "", s, flags=re.S)
    s = re.sub(r"<ref[^>]*>.*?</ref>", "", s, flags=re.S)
    s = re.sub(r"<ref[^>]*/>", "", s)
    s = re.sub(r"<br\s*/?>", " ", s, flags=re.I)
    s = re.sub(r"<[^>]+>", "", s)
    s = re.sub(r"\{\{[^{}]*\}\}", "", s)
    s = re.sub(r"\[\[(?:File|Image|Category):[^\]]*\]\]", "", s, flags=re.I)
    s = re.sub(r"\[\[[^\]|]*\|([^\]]*)\]\]", r"\1", s)
    s = re.sub(r"\[\[([^\]]*)\]\]", r"\1", s)
    s = re.sub(r"\[https?://\S+\s+([^\]]*)\]", r"\1", s)
    s = re.sub(r"\[https?://\S+\]", "", s)
    s = re.sub(r"'{2,}", "", s)
    s = html.unescape(s)
    return re.sub(r"\s+", " ", s).strip()


def template_fields(text):
    m = re.search(r"\{\{\s*recipe\s*summary(.*?)\n\s*\}\}", text, re.S | re.I)
    out = {}
    if m:
        for k, v in re.findall(r"^\s*\|\s*([A-Za-z ]+?)\s*=\s*(.*?)\s*$", m.group(1), re.M):
            out[k.lower()] = strip_markup(v)
    return out


def sections(text):
    parts = re.split(r"^(={2,4})\s*(.*?)\s*\1\s*$", text, flags=re.M)
    secs = []  # (level, name, body)
    secs.append((0, "", parts[0]))
    for i in range(1, len(parts) - 2, 3):
        secs.append((len(parts[i]), parts[i + 1].strip(), parts[i + 2]))
    return secs


def minutes_of(s):
    s = s.lower()
    if not s:
        return 0
    h = re.search(r"(\d+(?:\.\d+)?)\s*(?:h|hr|hrs|hour|hours)\b", s)
    m = re.search(r"(\d+)\s*(?:m|min|mins|minute|minutes)\b", s)
    if h or m:
        return int((float(h.group(1)) * 60 if h else 0) + (int(m.group(1)) if m else 0))
    return 0


def parse(title, text):
    """-> dict or (None, reason)"""
    if text.lstrip().upper().startswith("#REDIRECT"):
        return None, "redirect"
    fields = template_fields(text)
    secs = sections(text)
    ing_lines, step_lines = [], []
    for lvl, name, body in secs:
        n = name.lower()
        if re.match(r"^(ingredients?|ingredient list)\b", n) or (ing_lines and lvl >= 3 and not step_lines and not re.search(r"proced|direction|method|instruction|prepar", n)):
            for line in body.split("\n"):
                m = re.match(r"^\s*\*+\s*(.+)$", line)
                if m:
                    t = strip_markup(m.group(1))
                    if t:
                        ing_lines.append(t)
                elif re.match(r"^\s*;", line):  # definition-list sub-heading
                    pass
        elif re.match(r"^(procedure|directions?|method|instructions?|preparation|steps?)\b", n) or (step_lines and lvl >= 3 and not re.search(r"note|tip|variation|see also|reference|external", n)):
            for line in re.split(r"\n(?=\s*[#*:])", body):
                m = re.match(r"^\s*(#+|\*+)\s*(.+)$", line, re.S)
                if m:
                    t = strip_markup(m.group(2))
                    if t:
                        step_lines.append(t)
    if not ing_lines:
        return None, "no ingredients"
    if not step_lines:
        # some pages use plain paragraphs under Procedure
        for lvl, name, body in secs:
            if re.match(r"^(procedure|directions?|method|instructions?|preparation)\b", name.lower()):
                paras = [strip_markup(p) for p in re.split(r"\n\s*\n", body) if strip_markup(p)]
                step_lines = paras
    if not step_lines:
        return None, "no steps"
    return {"title": re.sub(r"^Cookbook:", "", title), "ingredients": ing_lines, "steps": step_lines,
            "servings": fields.get("servings") or fields.get("yield") or "", "time": fields.get("time") or "",
            "fields": fields}, None


def build(title, node):
    ings = rlib.to_unit_ingredients(node["ingredients"])
    steps = rlib.build_steps(node["steps"], ings)
    sv = re.search(r"\d+", node["servings"] or "")
    total = minutes_of(node["time"])
    return ings, steps, (int(sv.group(0)) if sv else None), total
