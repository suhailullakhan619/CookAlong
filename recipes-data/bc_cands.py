import os, sys, json, io, re
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rlib
from rlib import ir, db
import import_based_cooking as ibc
REPO = "C:/Users/suhai/AppData/Local/Temp/claude/C--amazon-firetv/c5e2195e-5d50-4ae7-8272-64ed2a263b38/scratchpad/ext/based.cooking/content"


def intro(path):
    t = io.open(path, encoding="utf-8").read()
    body = re.match(r"^---.*?\n---\s*\n(.*)$", t, re.S).group(1)
    for para in re.split(r"\n\s*\n", body):
        p = para.strip()
        if p and not p.startswith(("-", "#", "!", "1.")):
            return ibc.clean_md(p)
    return ""


def items(sec_text, pattern):
    """List items with wrapped continuation lines joined (ibc.parse drops continuation lines)."""
    out, cur = [], None
    for line in sec_text.split("\n"):
        m = re.match(pattern, line)
        if m:
            if cur is not None:
                out.append(cur)
            cur = m.group(1)
        elif not line.strip():
            if cur is not None:
                out.append(cur)
                cur = None
        elif cur is not None and not re.match(r"^\s*(#|---)", line):
            cur += " " + line.strip()
    if cur is not None:
        out.append(cur)
    return [ibc.clean_md(x) for x in out if x.strip()]


def candidates():
    out = []
    for name in sorted(os.listdir(REPO)):
        if not name.endswith(".md") or name.startswith("_"):
            continue
        p = os.path.join(REPO, name)
        node, pic = ibc.parse(p)
        if not node:
            continue
        tags = [t.lower() for t in node["recipeCategory"]]
        if ibc.SKIP_TAGS & set(tags):
            continue
        text = io.open(p, encoding="utf-8").read()
        body = re.match(r"^---\s*\n.*?\n---\s*\n(.*)$", text, re.S).group(1)
        d = ibc.section(body, "Directions") or ibc.section(body, "Instructions") or ibc.section(body, "Steps")
        steps2 = items(d, r"^\s*(?:\d+[.)]|[-*+])\s+(.+)$")
        ings2 = items(ibc.section(body, "Ingredients"), r"^\s*[-*+]\s+(.+)$")
        if steps2:
            node["recipeInstructions"] = steps2
        if ings2:
            node["recipeIngredient"] = ings2
        r = ir.convert(node)
        if not r:
            continue
        fid = ir.slug(os.path.splitext(name)[0])
        out.append((fid, name, tags, node, r, intro(p)))
    return out


if __name__ == "__main__":
    c = candidates()
    print(len(c))
