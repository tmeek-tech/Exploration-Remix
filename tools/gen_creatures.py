"""Creature shapes and textures. Each creature is a tree of boxes (model units, 1/16 block, y down, ground at 24)
written to assets/<mod>/creatures/<id>.json; the client builds the model from it, so UVs here and in game agree."""
import json, math, os, random
from PIL import Image, ImageDraw

MOD = "explorationremix"

def box(frm, size, inflate=0.0): return {"from": list(frm), "size": list(size), "inflate": inflate}

def part(name, parent, pivot, boxes, role="static", phase=0.0, side=0, axis="x", amp=1.0):
    return {"name": name, "parent": parent, "pivot": list(pivot), "boxes": boxes, "role": role, "phase": phase, "side": side, "axis": axis, "amp": amp}

def legs4(prefix, parent, dx, y, dz, size, frm=None, amp=1.0):
    out = []
    i = 0
    for sx in (1, -1):
        for sz in (1, -1):
            f = frm or (-size[0] / 2, 0, -size[2] / 2)
            out.append(part(f"{prefix}{i}", parent, (sx * dx, y, sz * dz), [box(f, size)], "leg", phase=(0.0 if (sx * sz) > 0 else math.pi), side=sx, amp=amp))
            i += 1
    return out

def creature(cid, size_blocks, scale, palette, pattern, parts, eyes=True, shadow=0.5, hang=False):
    return {"id": cid, "size": size_blocks, "scale": scale, "palette": palette, "pattern": pattern, "parts": parts, "eyes": eyes, "shadow": shadow, "hang": hang}

CREATURES = []

# --- flocker: a tiny cave bat ---------------------------------------------------------------------------------
CREATURES.append(creature("flocker", (0.4, 0.3), 1.0, {"base": (52, 50, 66), "dark": (28, 26, 40), "light": (90, 86, 110), "belly": (120, 110, 130), "eye": (255, 230, 120)}, "speck", [
    part("body", None, (0, 21, 0), [box((-1.5, -1.5, -2), (3, 3, 4))], "body"),
    part("head", "body", (0, -0.5, -2), [box((-1.5, -1.5, -2), (3, 3, 2))], "head"),
    part("wing_l", "body", (1.5, 0, 0), [box((0, -0.5, -1.5), (6, 1, 3))], "wing", side=1),
    part("wing_r", "body", (-1.5, 0, 0), [box((-6, -0.5, -1.5), (6, 1, 3))], "wing", side=-1),
    part("tail", "body", (0, 0.5, 2), [box((-1, -0.5, 0), (2, 1, 3))], "tail"),
], shadow=0.2, hang=True))

# --- nest creatures -------------------------------------------------------------------------------------------
CREATURES.append(creature("swarmer", (0.7, 0.5), 1.0, {"base": (214, 196, 160), "dark": (120, 96, 60), "light": (240, 230, 200), "belly": (170, 150, 110), "eye": (40, 20, 20)}, "plates", [
    part("body", None, (0, 20, 0), [box((-3, -2, -5), (6, 4, 10))], "body"),
    part("head", "body", (0, -0.5, -5), [box((-2, -1.5, -4), (4, 3, 4))], "head"),
    part("jaw_l", "head", (1.5, 1, -4), [box((0, 0, -3), (1, 1, 3))], "jaw", side=1),
    part("jaw_r", "head", (-1.5, 1, -4), [box((-1, 0, -3), (1, 1, 3))], "jaw", side=-1),
    part("tail", "body", (0, -0.5, 5), [box((-1, -1, 0), (2, 2, 5))], "tail"),
] + [part(f"leg{i}", "body", (3 * s, 1, -3 + (i % 3) * 3), [box((0 if s > 0 else -4, -1, -1), (4, 2, 2))], "leg", phase=(i % 3) * 2.1 + (0 if s > 0 else math.pi), side=s, axis="y", amp=0.6)
     for i, s in [(0, 1), (1, 1), (2, 1), (3, -1), (4, -1), (5, -1)]], shadow=0.35))

CREATURES.append(creature("armored", (1.3, 1.0), 1.0, {"base": (84, 88, 96), "dark": (40, 42, 50), "light": (130, 134, 140), "belly": (150, 110, 80), "eye": (255, 150, 60)}, "plates", [
    part("body", None, (0, 14, 0), [box((-5, -4, -7), (10, 8, 14))], "body"),
    part("plates", "body", (0, -4, 0), [box((-5.5, -1, -6), (11, 2, 12))], "static"),
    part("head", "body", (0, 0, -7), [box((-3, -2.5, -6), (6, 5, 6))], "head"),
    part("tail", "body", (0, 0, 7), [box((-1.5, -1.5, 0), (3, 3, 5))], "tail"),
] + legs4("leg", "body", 4, 4, 4, (3, 6, 3)), shadow=0.7))

CREATURES.append(creature("spitter", (0.8, 1.0), 1.0, {"base": (120, 140, 70), "dark": (60, 80, 36), "light": (190, 210, 110), "belly": (210, 200, 120), "eye": (255, 240, 120)}, "speck", [
    part("body", None, (0, 15, 0), [box((-3, -2.5, -4), (6, 5, 8))], "body"),
    part("neck", "body", (0, -1, -4), [box((-1.5, -1.5, -6), (3, 3, 6))], "neck"),
    part("head", "neck", (0, 0, -6), [box((-2, -2, -5), (4, 4, 5))], "head"),
    part("tail", "body", (0, -0.5, 4), [box((-1, -1, 0), (2, 2, 6))], "tail"),
] + legs4("leg", "body", 3, 2, 3, (2, 7, 2)), shadow=0.45))

# --- hunters ---------------------------------------------------------------------------------------------------
CREATURES.append(creature("climber", (1.0, 0.9), 1.0, {"base": (70, 52, 40), "dark": (34, 24, 18), "light": (150, 120, 90), "belly": (110, 90, 70), "eye": (255, 190, 60)}, "stripes", [
    part("body", None, (0, 11, 0), [box((-3, -3, -6), (6, 6, 12))], "body"),
    part("head", "body", (0, -1, -6), [box((-2.5, -2, -6), (5, 4, 6))], "head"),
    part("tail", "body", (0, -1, 6), [box((-1, -1, 0), (2, 2, 8))], "tail"),
] + legs4("leg", "body", 3, 3, 5, (2, 10, 2), amp=1.2), shadow=0.6))

CREATURES.append(creature("ambusher", (1.4, 1.6), 1.0, {"base": (22, 26, 44), "dark": (8, 10, 20), "light": (40, 60, 90), "belly": (30, 40, 60), "eye": None}, "veins", [
    part("body", None, (0, 8, 0), [box((-6, -6, -5), (12, 12, 10))], "body"),
    part("head", "body", (0, -6, -5), [box((-4, -3, -6), (8, 6, 8))], "head"),
] + legs4("arm", "body", 6, -2, 3, (4, 14, 4), amp=0.8), eyes=False, shadow=0.9, hang=True))

CREATURES.append(creature("burrower", (0.9, 0.9), 1.0, {"base": (120, 60, 40), "dark": (60, 28, 18), "light": (190, 110, 70), "belly": (150, 100, 70), "eye": (255, 120, 60)}, "cracks", [
    part("body", None, (0, 18, 0), [box((-3.5, -3.5, -3), (7, 7, 8))], "body"),
    part("head", "body", (0, 0, -3), [box((-4, -4, -5), (8, 8, 5))], "head"),
    part("claw_l", "head", (3, 2, -3), [box((-1, 0, -2), (2, 3, 2))], "leg", phase=0.0, side=1),
    part("claw_r", "head", (-3, 2, -3), [box((-1, 0, -2), (2, 3, 2))], "leg", phase=math.pi, side=-1),
    part("tail", "body", (0, 0, 5), [box((-3, -3, 0), (6, 6, 7))], "tail"),
    part("tail2", "tail", (0, 0, 7), [box((-2, -2, 0), (4, 4, 6))], "tail", phase=1.2),
], shadow=0.5))

CREATURES.append(creature("smasher", (2.0, 1.9), 1.25, {"base": (40, 38, 42), "dark": (16, 14, 18), "light": (90, 80, 84), "belly": (70, 50, 46), "eye": (255, 110, 40)}, "cracks", [
    part("body", None, (0, 6, 0), [box((-7, -6, -9), (14, 12, 18))], "body"),
    part("plates", "body", (0, -6, 0), [box((-7.5, -2, -8), (15, 2, 16))], "static"),
    part("head", "body", (0, -2, -9), [box((-5, -4, -10), (10, 8, 10))], "head"),
    part("tail", "body", (0, -2, 9), [box((-2, -2, 0), (4, 4, 10))], "tail"),
] + legs4("leg", "body", 6, 6, 6, (5, 12, 5), amp=0.7), shadow=1.1))

EGG_COLORS = {"flocker": ((52, 50, 66), (255, 230, 120)), "swarmer": ((214, 196, 160), (120, 96, 60)), "armored": ((84, 88, 96), (255, 150, 60)),
              "spitter": ((120, 140, 70), (255, 240, 120)), "climber": ((70, 52, 40), (255, 190, 60)), "ambusher": ((22, 26, 44), (40, 60, 90)),
              "burrower": ((120, 60, 40), (255, 120, 60)), "smasher": ((40, 38, 42), (255, 110, 40))}
NAMES = {"flocker": "Cave Flocker", "swarmer": "Nest Swarmer", "armored": "Nest Warden", "spitter": "Nest Spitter", "climber": "Climber",
         "ambusher": "Ceiling Ambusher", "burrower": "Burrower", "smasher": "Smasher", "spit": "Nest Spit"}

# --- UV packing ------------------------------------------------------------------------------------------------
def pack(c):
    boxes = []
    for p in c["parts"]:
        for b in p["boxes"]:
            w, h, d = (int(math.ceil(v)) for v in b["size"])
            boxes.append((b, 2 * (w + d), h + d))
    order = sorted(range(len(boxes)), key=lambda i: -boxes[i][2])
    tex_w = 64
    while True:
        x = y = shelf = 0; ok = True
        for i in order:
            b, bw, bh = boxes[i]
            if x + bw > tex_w:
                x = 0; y += shelf; shelf = 0
            if bw > tex_w: ok = False; break
            b["uv"] = [x, y]; x += bw + 1; shelf = max(shelf, bh + 1)
        tex_h = y + shelf
        if ok and tex_h <= tex_w: return tex_w, tex_w
        if ok and tex_h <= 128 and tex_w == 64: return tex_w, 128
        tex_w *= 2
        if tex_w > 256: raise RuntimeError("creature too big to pack: " + c["id"])

def shade(col, f): return tuple(max(0, min(255, int(v * f))) for v in col)
def lerp(a, b, t): return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))

def paint(c, tex_w, tex_h):
    r = random.Random(hash(c["id"]) & 0xffffffff)
    img = Image.new("RGBA", (tex_w, tex_h), (0, 0, 0, 0)); d = ImageDraw.Draw(img)
    pal = c["palette"]; pattern = c["pattern"]
    for p in c["parts"]:
        for b in p["boxes"]:
            w, h, dd = (int(math.ceil(v)) for v in b["size"]); u, v = b["uv"]
            # side strip: west, north, east, south
            for x in range(u, u + 2 * (w + dd)):
                for y in range(v + dd, v + dd + h):
                    n = r.random()
                    col = lerp(pal["base"], pal["dark"], 0.35) if n < 0.18 else (lerp(pal["base"], pal["light"], 0.4) if n > 0.85 else pal["base"])
                    if pattern == "stripes" and ((x + y) // 3) % 4 == 0: col = lerp(col, pal["dark"], 0.5)
                    if pattern == "plates" and (y - v - dd) % 4 == 0: col = lerp(col, pal["dark"], 0.6)
                    if pattern == "veins" and n > 0.93: col = lerp(col, pal["light"], 0.9)
                    if pattern == "cracks" and n > 0.95: col = pal["eye"] or pal["light"]
                    if pattern == "speck" and n > 0.9: col = pal["light"]
                    # the strip's lower rows darken toward the belly
                    if h > 2 and y >= v + dd + h - max(1, h // 3): col = lerp(col, pal["belly"], 0.35)
                    img.putpixel((x, y), col + (255,))
            # top (up) and bottom (down) faces
            for x in range(u + dd, u + dd + w):
                for y in range(v, v + dd):
                    n = r.random()
                    img.putpixel((x, y), lerp(pal["belly"], pal["dark"], 0.2 if n < 0.7 else 0.5) + (255,))          # down
                    img.putpixel((x + w, y), lerp(pal["base"], pal["light"], 0.5 if n > 0.6 else 0.25) + (255,))    # up
            if p["role"] == "head" and c["eyes"] and pal["eye"]:
                # the north face carries the eyes
                fx0, fy0 = u + dd, v + dd
                ey = fy0 + max(1, h // 3)
                for ex in (fx0 + max(1, w // 4), fx0 + w - 1 - max(1, w // 4)):
                    img.putpixel((ex, ey), pal["eye"] + (255,))
                    if w >= 6: img.putpixel((ex + (1 if ex < fx0 + w // 2 else -1), ey), lerp(pal["eye"], (255, 255, 255), 0.5) + (255,))
    return img

def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f: json.dump(obj, f, indent=1)

def generate(res, assets):
    for c in CREATURES:
        tex_w, tex_h = pack(c)
        paint(c, tex_w, tex_h).save(os.path.join(assets, "textures/entity", c["id"] + ".png")) if os.makedirs(os.path.join(assets, "textures/entity"), exist_ok=True) is None else None
        write(os.path.join(assets, "creatures", c["id"] + ".json"), {
            "texture_width": tex_w, "texture_height": tex_h, "scale": c["scale"], "shadow": c["shadow"], "hang": c["hang"],
            "parts": [{k: p[k] for k in ("name", "parent", "pivot", "role", "phase", "side", "axis", "amp")} | {"boxes": [{"from": b["from"], "size": b["size"], "uv": b["uv"], "inflate": b["inflate"]} for b in p["boxes"]]} for p in c["parts"]]})
        write(os.path.join(assets, "models/item", c["id"] + "_spawn_egg.json"), {"parent": "minecraft:item/template_spawn_egg"})

def lang(d):
    for cid, n in NAMES.items():
        d[f"entity.{MOD}.{cid}"] = n
        if cid != "spit": d[f"item.{MOD}.{cid}_spawn_egg"] = n + " Spawn Egg"
    d[f"block.{MOD}.egg_cluster"] = "Egg Cluster"
    d[f"run.{MOD}.nest_open"] = "The nest is broken. The gate is open."
    d[f"run.{MOD}.nest_near"] = "Something stirs in the gate's nest."
    d[f"run.{MOD}.nest_cluster"] = "%s egg clusters left"

def egg_colors(): return EGG_COLORS

if __name__ == "__main__":
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    generate(os.path.join(root, "src/main/resources"), os.path.join(root, "src/main/resources/assets", MOD))
    print("ok")
