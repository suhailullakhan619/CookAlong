import sys
sys.path.insert(0,'.')
import rlib
for r in rlib.db.load_batch(sys.argv[1]):
    print("## %s | %s | %s/%s sv%d %s %s" % (r["id"], r["title"], r["prepTime"], r["cookTime"], r["servings"], r["dietaryTags"], r["categories"]))
    print("  D:", r["description"])
    print("  I:", "; ".join(("%g%s %s" % (i["amount"], i["unit"], i["name"][:28]) if i["amount"] is not None else i["name"][:28]) for i in r["ingredients"]))
    print("  S:", " | ".join("%s%s" % (s["instructionText"][:48], "[T%d]"%(s["suggestedTimerSeconds"]//60) if "suggestedTimerSeconds" in s else "") for s in r["steps"]))
