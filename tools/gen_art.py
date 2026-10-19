"""Generates the bundled placeholder recipe art and the Fire TV launcher banner.

Run from the repo root:  python tools/gen_art.py   (needs Pillow: pip install pillow)
Replace any PNG in app/src/main/assets/images/ with a real photo (16:9, ~640x360) at any time.
"""
import math
import os
import random

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "images")
RES = os.path.join(ROOT, "app", "src", "main", "res")
S = 2  # supersample factor
W, H = 640 * S, 360 * S


def gradient(c1, c2):
    img = Image.new("RGB", (W, H))
    d = ImageDraw.Draw(img)
    for x in range(0, W, 2):
        t = x / W
        col = tuple(int(c1[i] + (c2[i] - c1[i]) * t) for i in range(3))
        d.line([(x, 0), (x, H)], fill=col, width=2)
    return img


def shadow(base, box, radius=40, offset=(0, 24), alpha=110):
    sh = Image.new("L", base.size, 0)
    ImageDraw.Draw(sh).ellipse([box[0] + offset[0], box[1] + offset[1], box[2] + offset[0], box[3] + offset[1]], fill=alpha)
    sh = sh.filter(ImageFilter.GaussianBlur(radius))
    base.paste((0, 0, 0), mask=sh)


def plate(img, cx, cy, r, rim=(244, 238, 228), inner=(250, 247, 240)):
    shadow(img, (cx - r, cy - r, cx + r, cy + r))
    d = ImageDraw.Draw(img)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=rim)
    d.ellipse([cx - r * 0.78, cy - r * 0.78, cx + r * 0.78, cy + r * 0.78], fill=inner)
    return d


def bowl(img, cx, cy, r, color):
    shadow(img, (cx - r, cy - r, cx + r, cy + r))
    d = ImageDraw.Draw(img)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=color)
    return d


def dots(d, cx, cy, r, n, size, colors, seed):
    rnd = random.Random(seed)
    for _ in range(n):
        a = rnd.random() * math.tau
        rr = math.sqrt(rnd.random()) * r
        x, y = cx + math.cos(a) * rr, cy + math.sin(a) * rr
        s = size * (0.7 + rnd.random() * 0.6)
        d.ellipse([x - s, y - s, x + s, y + s], fill=rnd.choice(colors))


def leaf(d, x, y, s, ang, color=(76, 140, 64)):
    pts = []
    for t in range(0, 181, 15):
        a = math.radians(t)
        pts.append((math.cos(a) * s, math.sin(a) * s * 0.45))
    pts += [(p[0], -p[1]) for p in reversed(pts)]
    ca, sa = math.cos(ang), math.sin(ang)
    d.polygon([(x + p[0] * ca - p[1] * sa, y + p[0] * sa + p[1] * ca) for p in pts], fill=color)


def save(img, name):
    img = img.resize((W // S, H // S), Image.LANCZOS)
    img.save(os.path.join(OUT, name + ".png"), optimize=True)


def spaghetti():
    img = gradient((92, 52, 22), (214, 150, 66))
    cx, cy, r = W // 2, H // 2 + 10, 300
    d = plate(img, cx, cy, r)
    rnd = random.Random(1)
    for _ in range(70):
        a0 = rnd.random() * math.tau
        rr = 40 + rnd.random() * 170
        pts = [(cx + math.cos(a0 + t / 20) * (rr + 14 * math.sin(t / 3)), cy + math.sin(a0 + t / 20) * (rr + 14 * math.sin(t / 3))) for t in range(40)]
        d.line(pts, fill=rnd.choice([(240, 200, 110), (232, 188, 92), (248, 214, 130)]), width=10)
    for (x, y) in [(cx + 120, cy - 90), (cx - 150, cy + 70)]:
        d.ellipse([x - 46, y - 46, x + 46, y + 46], fill=(250, 220, 60))
        d.ellipse([x - 36, y - 36, x + 36, y + 36], fill=(255, 241, 150))
        for k in range(8):
            a = k * math.tau / 8
            d.line([(x, y), (x + math.cos(a) * 34, y + math.sin(a) * 34)], fill=(250, 220, 60), width=4)
    for k in range(9):
        leaf(d, cx - 60 + k * 18, cy - 30 + (k % 3) * 22, 16, k, (60, 130, 60))
    save(img, "lemon-garlic-spaghetti")


def curry():
    img = gradient((60, 30, 18), (176, 92, 40))
    cx, cy = W // 2, H // 2 + 10
    d = bowl(img, cx, cy, 300, (238, 232, 220))
    d.ellipse([cx - 250, cy - 250, cx + 250, cy + 250], fill=(222, 138, 52))
    d.ellipse([cx - 200, cy - 180, cx + 140, cy + 120], fill=(232, 160, 70))
    dots(d, cx, cy, 210, 55, 20, [(236, 196, 120), (226, 182, 104)], 2)
    for k in range(10):
        leaf(d, cx - 160 + k * 34, cy + 60 - (k % 4) * 40, 40, k * 0.7, (52, 120, 48))
    d.arc([cx - 120, cy - 120, cx + 120, cy + 120], 200, 320, fill=(250, 240, 220), width=14)
    save(img, "chickpea-coconut-curry")


def omelette():
    img = gradient((40, 70, 60), (120, 170, 120))
    cx, cy = W // 2, H // 2 + 10
    d = plate(img, cx, cy, 300)
    d.chord([cx - 230, cy - 170, cx + 230, cy + 230], 180, 360, fill=(246, 200, 70))
    d.chord([cx - 230, cy - 120, cx + 230, cy + 120], 0, 180, fill=(236, 184, 50))
    dots(d, cx, cy - 20, 160, 40, 10, [(200, 50, 40), (60, 140, 60), (150, 60, 120)], 3)
    save(img, "masala-omelette")


def chicken():
    img = gradient((50, 40, 34), (140, 100, 70))
    shadow(img, (140, 90, W - 140, H - 70), radius=30)
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([140, 80, W - 140, H - 80], radius=40, fill=(70, 70, 76))
    d.rounded_rectangle([170, 110, W - 170, H - 110], radius=30, fill=(96, 92, 96))
    rnd = random.Random(4)
    for _ in range(16):
        x, y = 220 + rnd.random() * (W - 440), 150 + rnd.random() * (H - 300)
        d.ellipse([x - 34, y - 28, x + 34, y + 28], fill=(232, 204, 140))
    for _ in range(12):
        x, y = 220 + rnd.random() * (W - 440), 150 + rnd.random() * (H - 300)
        d.rounded_rectangle([x - 26, y - 22, x + 26, y + 22], radius=10, fill=rnd.choice([(220, 50, 40), (250, 190, 40), (150, 60, 110)]))
    for (x, y) in [(400, 330), (700, 260), (950, 380), (560, 470)]:
        d.ellipse([x - 90, y - 64, x + 90, y + 64], fill=(176, 84, 34))
        d.ellipse([x - 70, y - 50, x + 60, y + 30], fill=(204, 110, 44))
    save(img, "sheet-pan-chicken")


def pancakes():
    img = gradient((70, 40, 60), (210, 140, 120))
    cx, cy = W // 2, H // 2 + 40
    d = plate(img, cx, cy, 290)
    for i in range(5):
        y = cy + 60 - i * 34
        d.ellipse([cx - 200, y - 60, cx + 200, y + 60], fill=(196, 132, 60))
        d.ellipse([cx - 196, y - 64, cx + 196, y + 52], fill=(232, 176, 96))
    for k in range(6):
        x = cx - 110 + k * 44
        y = cy - 110 + (k % 2) * 10
        d.ellipse([x - 26, y - 22, x + 26, y + 22], fill=(250, 236, 180))
        d.ellipse([x - 8, y - 6, x + 8, y + 6], fill=(214, 190, 120))
    save(img, "banana-pancakes")


def soup():
    img = gradient((30, 50, 40), (100, 140, 90))
    cx, cy = W // 2, H // 2 + 10
    d = bowl(img, cx, cy, 300, (240, 236, 228))
    d.ellipse([cx - 250, cy - 250, cx + 250, cy + 250], fill=(206, 62, 42))
    d.arc([cx - 140, cy - 120, cx + 120, cy + 120], 0, 300, fill=(250, 230, 210), width=16)
    d.arc([cx - 80, cy - 70, cx + 70, cy + 70], 40, 330, fill=(250, 230, 210), width=12)
    for k in range(5):
        leaf(d, cx + 120 + (k % 2) * 30, cy - 120 + k * 34, 46, 0.4 + k * 0.5, (58, 140, 58))
    save(img, "tomato-basil-soup")


def fried_rice():
    img = gradient((40, 30, 50), (150, 100, 130))
    cx, cy = W // 2, H // 2 + 10
    d = bowl(img, cx, cy, 300, (40, 90, 140))
    d.ellipse([cx - 250, cy - 250, cx + 250, cy + 250], fill=(236, 226, 200))
    dots(d, cx, cy, 230, 320, 9, [(248, 242, 226), (230, 218, 186), (222, 204, 160)], 5)
    dots(d, cx, cy, 210, 40, 14, [(90, 170, 70)], 6)
    dots(d, cx, cy, 210, 30, 12, [(240, 130, 40)], 7)
    dots(d, cx, cy, 200, 18, 22, [(250, 210, 70)], 8)
    save(img, "veggie-fried-rice")


def mug_cake():
    img = gradient((50, 34, 30), (160, 110, 90))
    cx, cy = W // 2, H // 2 + 40
    shadow(img, (cx - 200, cy - 160, cx + 200, cy + 200), radius=30)
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([cx - 170, cy - 150, cx + 170, cy + 190], radius=40, fill=(236, 120, 90))
    d.arc([cx + 120, cy - 80, cx + 280, cy + 100], 270, 90, fill=(236, 120, 90), width=34)
    d.ellipse([cx - 170, cy - 200, cx + 170, cy - 100], fill=(96, 52, 30))
    d.ellipse([cx - 150, cy - 230, cx + 150, cy - 120], fill=(110, 62, 36))
    dots(d, cx, cy - 175, 110, 14, 14, [(60, 30, 18)], 9)
    for k in range(3):
        x = cx - 80 + k * 80
        d.line([(x + 16 * math.sin(t / 6), cy - 260 - t * 3) for t in range(30)], fill=(250, 240, 230), width=8)
    save(img, "chocolate-mug-cake")


def dal():
    img = gradient((70, 44, 18), (196, 140, 50))
    cx, cy = W // 2, H // 2 + 10
    d = bowl(img, cx, cy, 300, (238, 232, 220))
    d.ellipse([cx - 250, cy - 250, cx + 250, cy + 250], fill=(232, 178, 52))
    d.ellipse([cx - 190, cy - 170, cx + 160, cy + 140], fill=(240, 196, 80))
    dots(d, cx, cy - 20, 150, 60, 7, [(150, 90, 30), (120, 70, 20)], 11)  # cumin
    for (x, y, a) in [(cx - 90, cy - 40, 0.6), (cx + 70, cy + 30, -0.4)]:  # dried chilies
        leaf(d, x, y, 60, a, (190, 40, 30))
    for k in range(7):
        leaf(d, cx - 120 + k * 40, cy + 110 - (k % 3) * 30, 26, k, (60, 130, 60))
    save(img, "dal-tadka")


def paneer():
    img = gradient((90, 30, 20), (210, 110, 60))
    cx, cy = W // 2, H // 2 + 10
    d = bowl(img, cx, cy, 300, (40, 40, 44))
    d.ellipse([cx - 250, cy - 250, cx + 250, cy + 250], fill=(214, 90, 40))
    d.ellipse([cx - 200, cy - 180, cx + 150, cy + 130], fill=(228, 112, 52))
    rnd = random.Random(12)
    for _ in range(11):
        x, y = cx - 170 + rnd.random() * 320, cy - 150 + rnd.random() * 280
        d.rounded_rectangle([x - 32, y - 28, x + 32, y + 28], radius=8, fill=(250, 242, 222))
        d.rounded_rectangle([x - 32, y + 12, x + 32, y + 28], radius=6, fill=(236, 200, 150))
    d.arc([cx - 150, cy - 150, cx + 150, cy + 150], 200, 330, fill=(250, 235, 215), width=16)
    for k in range(5):
        leaf(d, cx + 60 + k * 26, cy - 140 + (k % 2) * 24, 22, k, (60, 130, 60))
    save(img, "paneer-butter-masala")


def upma():
    img = gradient((40, 70, 50), (150, 190, 120))
    cx, cy = W // 2, H // 2 + 20
    d = plate(img, cx, cy, 300)
    d.ellipse([cx - 210, cy - 150, cx + 210, cy + 170], fill=(240, 220, 160))
    d.ellipse([cx - 180, cy - 170, cx + 170, cy + 110], fill=(246, 230, 176))
    dots(d, cx, cy - 20, 170, 220, 5, [(230, 205, 140), (250, 238, 196)], 13)
    dots(d, cx, cy - 20, 160, 26, 13, [(90, 170, 70)], 14)  # peas
    dots(d, cx, cy - 20, 160, 22, 11, [(240, 130, 40)], 15)  # carrot
    for k in range(6):
        leaf(d, cx - 140 + k * 55, cy - 70 + (k % 2) * 60, 30, k * 0.9, (40, 110, 50))  # curry leaves
    d.ellipse([cx + 150, cy - 210, cx + 250, cy - 130], fill=(250, 220, 60))  # lemon wedge
    d.ellipse([cx + 162, cy - 198, cx + 238, cy - 142], fill=(255, 241, 150))
    save(img, "vegetable-upma")


def pulao():
    img = gradient((80, 50, 30), (200, 150, 90))
    cx, cy = W // 2, H // 2 + 10
    d = plate(img, cx, cy, 300)
    d.ellipse([cx - 220, cy - 200, cx + 220, cy + 200], fill=(246, 240, 222))
    rnd = random.Random(16)
    for _ in range(380):  # long basmati grains
        a = rnd.random() * math.tau
        rr = math.sqrt(rnd.random()) * 200
        x, y = cx + math.cos(a) * rr, cy + math.sin(a) * rr
        t = rnd.random() * math.pi
        dx, dy = math.cos(t) * 12, math.sin(t) * 12
        d.line([(x - dx, y - dy), (x + dx, y + dy)], fill=rnd.choice([(255, 252, 240), (236, 226, 200), (250, 236, 196)]), width=7)
    dots(d, cx, cy, 180, 40, 13, [(90, 170, 70)], 17)
    dots(d, cx, cy, 180, 30, 11, [(240, 130, 40)], 18)
    for (x, y) in [(cx - 60, cy - 60), (cx + 90, cy + 40)]:  # bay leaves
        leaf(d, x, y, 50, 0.5, (90, 120, 50))
    d.rounded_rectangle([cx + 20, cy - 120, cx + 110, cy - 100], radius=8, fill=(140, 80, 40))  # cinnamon
    save(img, "vegetable-pulao")


def lassi():
    img = gradient((210, 120, 40), (250, 200, 90))
    cx, cy = W // 2, H // 2 + 30
    shadow(img, (cx - 130, cy - 230, cx + 130, cy + 250), radius=30)
    d = ImageDraw.Draw(img)
    d.polygon([(cx - 130, cy - 230), (cx + 130, cy - 230), (cx + 105, cy + 240), (cx - 105, cy + 240)], fill=(250, 250, 250))
    d.polygon([(cx - 120, cy - 170), (cx + 120, cy - 170), (cx + 100, cy + 225), (cx - 100, cy + 225)], fill=(252, 190, 70))
    d.ellipse([cx - 120, cy - 190, cx + 120, cy - 150], fill=(255, 214, 120))
    d.line([(cx + 40, cy - 330), (cx + 10, cy - 160)], fill=(220, 60, 80), width=22)  # straw
    for (x, y) in [(cx - 50, cy - 100), (cx + 30, cy - 60)]:
        d.rounded_rectangle([x - 26, y - 26, x + 26, y + 26], radius=8, fill=(255, 236, 190))  # ice
    d.ellipse([cx + 180, cy - 40, cx + 330, cy + 120], fill=(250, 170, 40))  # mango
    d.ellipse([cx + 200, cy - 20, cx + 300, cy + 80], fill=(255, 200, 80))
    leaf(d, cx + 300, cy - 50, 50, -0.6, (70, 140, 60))
    save(img, "mango-lassi")


def guacamole():
    img = gradient((30, 60, 30), (120, 170, 80))
    cx, cy = W // 2, H // 2 + 10
    rnd = random.Random(19)
    for k in range(12):  # tortilla chips around the bowl
        a = k * math.tau / 12
        x, y = cx + math.cos(a) * 300, cy + math.sin(a) * 250
        pts = [(x + math.cos(a + o) * 70, y + math.sin(a + o) * 70) for o in (0, 2.2, 4.2)]
        ImageDraw.Draw(img).polygon(pts, fill=rnd.choice([(240, 200, 100), (230, 180, 80)]))
    d = bowl(img, cx, cy, 240, (190, 90, 50))
    d.ellipse([cx - 200, cy - 200, cx + 200, cy + 200], fill=(150, 190, 70))
    dots(d, cx, cy, 170, 70, 14, [(120, 170, 50), (175, 210, 100)], 20)
    dots(d, cx, cy, 150, 18, 10, [(220, 60, 50)], 21)  # tomato
    for k in range(5):
        leaf(d, cx - 80 + k * 40, cy - 30 + (k % 2) * 30, 22, k, (50, 120, 50))
    save(img, "classic-guacamole")


def greek_salad():
    img = gradient((30, 80, 130), (120, 180, 220))
    cx, cy = W // 2, H // 2 + 10
    d = bowl(img, cx, cy, 300, (245, 245, 240))
    d.ellipse([cx - 255, cy - 255, cx + 255, cy + 255], fill=(110, 170, 70))
    rnd = random.Random(22)
    for _ in range(14):  # cucumber slices
        x, y = cx - 200 + rnd.random() * 400, cy - 200 + rnd.random() * 400
        if (x - cx) ** 2 + (y - cy) ** 2 < 200 ** 2:
            d.ellipse([x - 32, y - 32, x + 32, y + 32], fill=(110, 170, 70))
            d.ellipse([x - 24, y - 24, x + 24, y + 24], fill=(220, 240, 190))
    for _ in range(12):  # tomato wedges
        x, y = cx - 200 + rnd.random() * 400, cy - 200 + rnd.random() * 400
        if (x - cx) ** 2 + (y - cy) ** 2 < 200 ** 2:
            d.chord([x - 40, y - 30, x + 40, y + 40], 180, 360, fill=(220, 50, 40))
    dots(d, cx, cy, 190, 14, 14, [(50, 30, 50)], 23)  # olives
    d.rounded_rectangle([cx - 90, cy - 70, cx + 90, cy + 50], radius=10, fill=(252, 250, 242))  # feta
    dots(d, cx, cy - 10, 70, 30, 3, [(90, 120, 50)], 24)  # oregano
    save(img, "greek-salad")


def shrimp():
    img = gradient((60, 40, 30), (190, 120, 70))
    cx, cy = W // 2, H // 2 + 10
    shadow(img, (cx - 290, cy - 230, cx + 290, cy + 230))
    d = ImageDraw.Draw(img)
    d.ellipse([cx - 290, cy - 230, cx + 290, cy + 230], fill=(60, 64, 70))
    d.ellipse([cx - 260, cy - 205, cx + 260, cy + 205], fill=(90, 92, 96))
    d.line([(cx + 270, cy), (cx + 330, cy - 10)], fill=(60, 64, 70), width=40)
    d.ellipse([cx - 230, cy - 180, cx + 230, cy + 180], fill=(235, 190, 90))  # garlic butter
    for k in range(7):
        a = k * math.tau / 7 + 0.3
        x, y = cx + math.cos(a) * 130, cy + math.sin(a) * 100
        d.arc([x - 60, y - 50, x + 60, y + 50], 30, 300, fill=(250, 130, 90), width=34)
        d.arc([x - 45, y - 35, x + 45, y + 35], 40, 290, fill=(255, 170, 130), width=10)
    dots(d, cx, cy, 160, 40, 6, [(250, 240, 200)], 26)  # garlic
    for k in range(6):
        leaf(d, cx - 100 + k * 40, cy - 10 + (k % 2) * 30, 18, k, (60, 130, 60))
    save(img, "garlic-butter-shrimp")


def banner():
    bw, bh = 320 * 4, 180 * 4
    img = Image.new("RGB", (bw, bh), (20, 17, 15))
    d = ImageDraw.Draw(img)
    d.ellipse([-200, int(bh * 0.55), bw + 200, int(bh * 1.8)], fill=(42, 33, 26))
    ox, oy = 110, 300
    amber = (242, 176, 79)
    d.rounded_rectangle([ox, oy, ox + 300, oy + 210], radius=50, fill=amber)
    d.rectangle([ox, oy, ox + 300, oy + 60], fill=amber)
    d.rounded_rectangle([ox - 40, oy - 30, ox + 340, oy + 10], radius=20, fill=amber)
    d.rounded_rectangle([ox + 120, oy - 70, ox + 180, oy - 30], radius=12, fill=amber)
    for k in range(3):
        x = ox + 80 + k * 70
        d.line([(x + 14 * math.sin(t / 5), oy - 90 - t * 4) for t in range(25)], fill=(245, 237, 227), width=14)
    fonts = [r"C:\Windows\Fonts\segoeuib.ttf", r"C:\Windows\Fonts\arialbd.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"]
    font_path = next((p for p in fonts if os.path.exists(p)), None)
    big = ImageFont.truetype(font_path, 124) if font_path else ImageFont.load_default()
    small = ImageFont.truetype(font_path, 54) if font_path else ImageFont.load_default()
    d.text((480, 265), "CookAlong", font=big, fill=(245, 237, 227))
    d.text((488, 440), "hands-free recipes", font=small, fill=amber)
    img = img.resize((320, 180), Image.LANCZOS)
    os.makedirs(os.path.join(RES, "drawable-xhdpi"), exist_ok=True)
    img.save(os.path.join(RES, "drawable-xhdpi", "banner.png"), optimize=True)


if __name__ == "__main__":
    import sys
    # The dishes now use real photos (see CREDITS.md / tools/apply_photos.py); the drawn placeholders are only
    # generated on request:  python tools/gen_art.py --dishes
    os.makedirs(OUT, exist_ok=True)
    for f in (() if "--dishes" not in sys.argv else (spaghetti, curry, omelette, chicken, pancakes, soup, fried_rice, mug_cake,
              dal, paneer, upma, pulao, lassi, guacamole, greek_salad, shrimp)):
        f()
    banner()
    print("ok")
