import os, re, sys
ROOT = "C:/Users/suhai/AppData/Local/Temp/claude/C--amazon-firetv/b2502969-0734-46ce-ae96-b61f26a92662/scratchpad/htc/repo/dishes/"
for rel in sys.argv[1:]:
    p = ROOT + rel
    if os.path.isdir(p.rstrip("/")) and not p.endswith(".md"):
        base = os.path.basename(p.rstrip("/"))
        p = os.path.join(p, base + ".md")
        rel = rel.rstrip("/") + "/" + base + ".md"
    elif not p.endswith(".md"):
        if os.path.isdir(p):
            pass
        else:
            p += ".md"; rel += ".md"
    try:
        t = open(p, encoding="utf-8").read()
    except Exception as e:
        print("!!", rel, e); continue
    t = re.split(r"\n## 附加内容", t)[0]
    t = re.sub(r"\n\s*\n", "\n", t)
    t = re.sub(r"!\[[^\]]*\]\([^)]*\)", "", t)
    print("=== PATH:", rel)
    print(t[:2200])
