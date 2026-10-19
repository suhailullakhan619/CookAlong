import sys, collections
sys.path.insert(0,'.')
import rlib
t = rlib.db.table()
items=[]; kw={"ProjectionExpression":"id, #s","ExpressionAttributeNames":{"#s":"source"}}
while True:
    p=t.scan(**kw); items+=p["Items"]
    if "LastEvaluatedKey" not in p: break
    kw["ExclusiveStartKey"]=p["LastEvaluatedKey"]
c=collections.Counter(i.get("source","?") for i in items)
print("batch %s: uploaded %s, total %d, per-source %s" % (sys.argv[1] if len(sys.argv)>1 else "-", sys.argv[2] if len(sys.argv)>2 else "-", len(items), dict(c)))
