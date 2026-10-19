import json, os, time, urllib.parse, urllib.request
SCR = "C:/Users/suhai/AppData/Local/Temp/claude/C--amazon-firetv/b2502969-0734-46ce-ae96-b61f26a92662/scratchpad/wb"
UA = "CookAlong-recipe-importer/1.0 (educational Fire TV recipe app; contact ramu@mobil80.com)"
API = "https://en.wikibooks.org/w/api.php"
_last = [0.0]

def api(**params):
    params.update(format="json", formatversion="2")
    key = os.path.join(SCR, "c_" + str(abs(hash(json.dumps(params, sort_keys=True)))) + ".json")
    url = API + "?" + urllib.parse.urlencode(params)
    wait = 1.1 - (time.time() - _last[0])
    if wait > 0: time.sleep(wait)
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=40) as r:
        data = json.load(r)
    _last[0] = time.time()
    return data

def members(cat, cmtype="page|subcat"):
    out, cont = [], {}
    while True:
        d = api(action="query", list="categorymembers", cmtitle=cat, cmlimit="500", cmtype=cmtype, **cont)
        out += d["query"]["categorymembers"]
        if "continue" in d: cont = {"cmcontinue": d["continue"]["cmcontinue"]}
        else: return out
