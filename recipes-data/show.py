import sys, json
sys.path.insert(0,'.')
import rlib
for r in rlib.db.load_batch(sys.argv[1]):
    print("## %s | %s | %s/%s sv%d %s %s" % (r["id"], r["title"], r["prepTime"], r["cookTime"], r["servings"], r["dietaryTags"], r["categories"]))
    print("  D:", r["description"])
    print("  I:", "; ".join(("%s%s %s" % (i["amount"] if i["amount"] is not None else "", i["unit"] if i["unit"]!="to taste" else "~", i["name"])) for i in r["ingredients"]))
    for s in r["steps"]:
        print("  %d. %s%s%s" % (s["order"], s["instructionText"], " [%ds %s]" % (s["suggestedTimerSeconds"], s["timerLabel"]) if "suggestedTimerSeconds" in s else "", " <%s>" % s["animation"] if "animation" in s else ""))
