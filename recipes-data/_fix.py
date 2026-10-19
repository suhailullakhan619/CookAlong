p = r"C:\amazon_firetv\cookalong\recipes-data\rlib.py"
b = open(p, "rb").read()
print(b.count(b"\x01"))
b = b.replace(b"\x01", b"\\1")
open(p, "wb").write(b)

p2 = r"C:\amazon_firetv\cookalong\recipes-data\wb_parse.py"
s = open(p2, encoding="utf-8").read()
old = 'r"\\{\\{\\s*Recipe summary(.*?)\\n\\}\\}"'
new = 'r"\\{\\{\\s*recipe\\s*summary(.*?)\\n\\s*\\}\\}"'
print(old in s)
s = s.replace(old, new)
open(p2, "w", encoding="utf-8").write(s)
