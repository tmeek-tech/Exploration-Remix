#!/usr/bin/env python3
"""Review pictures of the vent field's textures (Deep Gardens round 2), and the colour check of Taran's look rules.

Writes two pictures, into build/ unless told otherwise:
  dg_blocks_sheet.png  every texture of the vent field as gen_data.py wrote it, eight times its size and labelled, with
                       its glow overlay (<texture>_glow.png) beside it over a dark check
  dg_blocks_mock.png   a wall of each block, a face tiled three by three as in a dark cave: the texture at 30 % of its
                       brightness and the overlay over it at full brightness; each neon block in its four colours, the
                       glow mat's top and side
and prints the colour check: every pixel of every texture and overlay against the rules (textures.cool_problems, and
no grey leaning warm), the hues it found, and the rocks' mean colours against their targets.

In the game a block that gives light also lights its own faces (the air beside it holds its light less one), so the
rock of the brightest blocks reads lighter there than on the mock.

Run: python3 tools/vent_sheet.py [OUT_DIR]   (after python3 tools/gen_data.py)
"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from PIL import Image, ImageChops, ImageDraw, ImageFont
import textures

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEXTURES = os.path.join(ROOT, "src/main/resources/assets/explorationremix/textures/block")
FONT_DIR = "/usr/share/fonts/truetype/dejavu"
BG, INK, DIM_INK = (34, 33, 40), (232, 230, 238), (150, 148, 160)
DARK = 0.3  # the mock's rock brightness
# The look rules' targets for the rocks' mean colours.
TARGETS = {"vent_rock": (55, 52, 63), "black_dripstone": (50, 47, 58), "live_vent_rock": (78, 68, 92), "pale_stem": (212, 204, 222)}
# The mock's rows: (label, textures); a neon block's row is its four colours.
PLAIN = ["vent_rock", "black_dripstone", "live_vent_rock", "pale_stem", "seep_crust", "glow_mat_bottom"]
NEON_ROWS = [("glow_mat", "_top"), ("glow_mat", "_side"), ("glow_cap", ""), ("vent_crown", ""), ("den_fissure", ""),
             ("vent_glow", ""), ("throat_vein", ""), ("spire_tip", "")]


def font(size):
    try:
        return ImageFont.truetype(os.path.join(FONT_DIR, "DejaVuSans.ttf"), size)
    except OSError:
        return ImageFont.load_default()


def load(name):
    """The texture gen_data.py wrote (block/<name>), and its overlay or None."""
    img = Image.open(os.path.join(TEXTURES, name + ".png")).convert("RGBA")
    glow = os.path.join(TEXTURES, name + "_glow.png")
    return img, (Image.open(glow).convert("RGBA") if os.path.exists(glow) else None)


def checker(w, h, cell=8):
    out = Image.new("RGBA", (w, h), (22, 22, 26, 255))
    d = ImageDraw.Draw(out)
    for y in range(0, h, cell):
        for x in range(0, w, cell):
            if (x // cell + y // cell) % 2:
                d.rectangle((x, y, x + cell - 1, y + cell - 1), fill=(30, 30, 36, 255))
    return out


def dark_face(img, glow):
    """The face in a dark cave: the texture at DARK of its brightness, the overlay over it at full."""
    out = img.copy()
    px = out.load()
    for y in range(out.size[1]):
        for x in range(out.size[0]):
            r, g, b, a = px[x, y]
            px[x, y] = (round(r * DARK), round(g * DARK), round(b * DARK), a)
    if glow is not None:
        out.alpha_composite(glow)
    return out


def tiled(img, n=3):
    out = Image.new("RGBA", (img.size[0] * n, img.size[1] * n))
    for i in range(n):
        for j in range(n):
            out.paste(img, (i * img.size[0], j * img.size[1]))
    return out


def sheet(path, scale=8, cols=3):
    f, small = font(14), font(11)
    names = [n.split("/", 1)[1] for n in textures.VENT_FIELD]
    side = 16 * scale
    cell_w, cell_h = 2 * side + 12 + 28, side + 44
    rows = (len(names) + cols - 1) // cols
    out = Image.new("RGBA", (cols * cell_w + 28, rows * cell_h + 60), BG + (255,))
    d = ImageDraw.Draw(out)
    d.text((16, 14), "Vent field textures, 8x: each texture, and its glow overlay (drawn at full block light) over a dark check",
           fill=INK, font=f)
    for k, n in enumerate(names):
        img, glow = load(n)
        x, y = 16 + (k % cols) * cell_w, 44 + (k // cols) * cell_h
        out.paste(img.resize((side, side), Image.NEAREST), (x, y))
        if glow is not None:
            back = checker(side, side)
            back.alpha_composite(glow.resize((side, side), Image.NEAREST))
            out.paste(back, (x + side + 12, y))
        d.text((x, y + side + 4), n, fill=INK, font=small)
        d.text((x, y + side + 18), "+ " + n + "_glow" if glow is not None else "(no overlay)", fill=DIM_INK, font=small)
    out.convert("RGB").save(path)


def mock(path, scale=4):
    f, small = font(14), font(11)
    side = 48 * scale
    cell_w, cell_h = side + 12, side + 26
    rows = [("", PLAIN[:4]), ("", PLAIN[4:])] + [(b + face, [f"{b}_{c}{face}" for c in textures.NEONS]) for b, face in NEON_ROWS]
    out = Image.new("RGBA", (4 * cell_w + 28, len(rows) * cell_h + 60), BG + (255,))
    d = ImageDraw.Draw(out)
    d.text((16, 14), f"Vent field blocks as walls in a dark cave: faces tiled 3x3, rock at {round(DARK * 100)} % brightness, glow at full",
           fill=INK, font=f)
    for r, (_, names) in enumerate(rows):
        for c, n in enumerate(names):
            img, glow = load(n)
            x, y = 16 + c * cell_w, 44 + r * cell_h
            out.paste(tiled(dark_face(img, glow)).resize((side, side), Image.NEAREST), (x, y))
            d.text((x, y + side + 4), n, fill=INK, font=small)
    out.convert("RGB").save(path)


def colour_check():
    """Every pixel of every vent field texture and overlay against the rules; returns the problems."""
    problems, files, pixels, lime_files = [], 0, 0, 0
    hues = {"cool": [], "lime": []}
    stale = []
    for tex in textures.VENT_FIELD:
        n = tex.split("/", 1)[1]
        img, glow = load(n)
        if ImageChops.difference(img, textures.PAINTERS[tex]()).getbbox() is not None:
            stale.append(n)
        for name, im in ((n, img), (n + "_glow", glow)):
            if im is None:
                continue
            files += 1
            lime_files += "_lime" in name
            problems += [(name,) + p for p in textures.cool_problems(name, im)]
            for c in im.getdata():
                if c[3] == 0:
                    continue
                pixels += 1
                h, _ = textures.hue_sat(c)
                if h is None:
                    if c[0] > c[2] or c[1] > c[2]:  # a grey, but warmer than neutral
                        problems.append((name, "grey leaning warm", c[:3]))
                elif textures.LIME_HUES[0] <= h <= textures.LIME_HUES[1]:
                    hues["lime"].append(h)
                else:
                    hues["cool"].append(h)
    print(f"colour check: {files} files ({len(textures.VENT_FIELD)} textures and their overlays), {pixels} opaque pixels, "
          f"{len(problems)} outside the rules")
    for kind, hs in hues.items():
        if hs:
            print(f"  {kind} hues: {min(hs):.0f} to {max(hs):.0f} degrees over {len(hs)} pixels"
                  + (f" (only in the {lime_files} files named _lime)" if kind == "lime" else ""))
    for p in problems[:20]:
        print("  PROBLEM", p)
    if stale:
        print("  textures differ from their painters (run tools/gen_data.py):", ", ".join(stale))
    for n, target in TARGETS.items():
        img, _ = load(n)
        px = list(img.getdata())
        mean = tuple(round(sum(p[k] for p in px) / len(px)) for k in range(3))
        print(f"  {n}: mean {mean}, target {target}")
    return problems + stale


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "build")
    os.makedirs(out_dir, exist_ok=True)
    sheet(os.path.join(out_dir, "dg_blocks_sheet.png"))
    mock(os.path.join(out_dir, "dg_blocks_mock.png"))
    print("wrote", os.path.join(out_dir, "dg_blocks_sheet.png"), "and", os.path.join(out_dir, "dg_blocks_mock.png"))
    return 1 if colour_check() else 0


if __name__ == "__main__":
    sys.exit(main())
