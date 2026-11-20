#!/usr/bin/env python3
"""Review pictures of the vent field's textures (Deep Gardens round 2), and the colour check of Taran's look rules.

Writes two pictures, into build/ unless told otherwise:
  dg_blocks_sheet.png  every texture of the vent field as gen_data.py wrote it, eight times its size and labelled, with
                       its glow overlay (<texture>_glow.png) beside it over a dark check; an overlay drawn over a vanilla
                       rock (gen_data's option rock) is shown over that rock, a pointed dripstone over the check
  dg_blocks_mock.png   a wall of each block, a face tiled three by three as in a dark cave: the texture (over its rock)
                       at 30 % of its brightness and the overlay over it at full brightness; each neon block in its four
                       colours, the glow mat's and the vent beads' top and side; then pointed black dripstone, lit, as a
                       stalactite, a stalagmite and a pair whose tips have merged, under or on black dripstone
and prints the colour check: every pixel of every texture and overlay against the rules (textures.cool_problems, and
no grey leaning warm), the hues it found, and the rocks' mean colours against their targets.

The vanilla rocks come from the game's jar that ForgeGradle keeps in the Gradle caches (only for these pictures:
nothing the mod ships is taken from it); without it they are a flat grey of blackstone's mean colour.

In the game a block that gives light also lights its own faces (the air beside it holds its light less one), so the
rock of the brightest blocks reads lighter there than on the mock.

Run: python3 tools/vent_sheet.py [OUT_DIR]   (after python3 tools/gen_data.py)
"""
import io, os, sys, zipfile
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from PIL import Image, ImageChops, ImageDraw, ImageFont
import textures
import gen_data

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEXTURES = os.path.join(ROOT, "src/main/resources/assets/explorationremix/textures")
FONT_DIR = "/usr/share/fonts/truetype/dejavu"
CLIENT_JAR = os.path.join(os.environ.get("GRADLE_USER_HOME", os.path.expanduser("~/.gradle")),
                          "caches/forge_gradle/minecraft_repo/versions/1.20.1/client-extra.jar")
BG, INK, DIM_INK = (34, 33, 40), (232, 230, 238), (150, 148, 160)
DARK = 0.3  # the mock's rock brightness
# The look rules' targets for the rocks' mean colours.
TARGETS = {"black_dripstone": (50, 47, 58), "pale_stem": (212, 204, 222)}
# The mock's rows: (label, textures); a neon block's row is its four colours.
PLAIN = ["black_dripstone", "pale_stem", "seep_crust"]
NEON_ROWS = [("glow_mat", "_top"), ("glow_mat", "_side"), ("thin_glow_mat", "_top"), ("thin_glow_mat", "_side"),
             ("glow_cap", "_top"), ("glow_cap", "_side"), ("glow_cap", "_bottom"), ("vent_crown", ""), ("vent_beads", "")]
# The mock's pointed dripstone, as columns of blocks from the top: (label, [(direction, thickness), ...]), with black
# dripstone above a stalactite and below a stalagmite.
POINTED = "pointed_black_dripstone"
POINTED_COLUMNS = [("stalactite", [("down", "base"), ("down", "middle"), ("down", "frustum"), ("down", "tip")]),
                   ("stalagmite", [("up", "tip"), ("up", "frustum"), ("up", "middle"), ("up", "base")]),
                   ("merged tips", [("down", "frustum"), ("down", "tip_merge"), ("up", "tip_merge"), ("up", "frustum")]),
                   ("short pair", [("down", "tip"), None, None, ("up", "tip")])]


def font(size):
    try:
        return ImageFont.truetype(os.path.join(FONT_DIR, "DejaVuSans.ttf"), size)
    except OSError:
        return ImageFont.load_default()


def load(name):
    """The texture gen_data.py wrote (name with its folder, block/... or item/...), and its overlay or None."""
    img = Image.open(os.path.join(TEXTURES, name + ".png")).convert("RGBA")
    glow = os.path.join(TEXTURES, name + "_glow.png")
    return img, (Image.open(glow).convert("RGBA") if os.path.exists(glow) else None)


_VANILLA = {}


def vanilla(texture):
    """A vanilla texture (minecraft:block/blackstone and the like) from the game's jar, or a flat stand-in."""
    if texture not in _VANILLA:
        path = "assets/minecraft/textures/" + texture.split(":", 1)[1] + ".png"
        try:
            with zipfile.ZipFile(CLIENT_JAR) as jar:
                _VANILLA[texture] = Image.open(io.BytesIO(jar.read(path))).convert("RGBA")
        except (OSError, KeyError):
            print(f"  (no {CLIENT_JAR}: {texture} drawn as flat grey)")
            _VANILLA[texture] = Image.new("RGBA", (16, 16), (42, 36, 41, 255))
    return _VANILLA[texture]


def rocks():
    """The vanilla rock under each texture drawn over one (gen_data's option rock), as {texture: vanilla texture}: the
    rock's side where the texture covers the sides, else its top."""
    out = {}
    for bid, entry in gen_data.BLOCKS.items():
        opts = entry[2] if len(entry) > 2 else {}
        if "rock" not in opts:
            continue
        end, side = gen_data.ROCKS[opts["rock"]]
        for neon in (textures.NEONS if opts.get("neon") else [None]):
            m = bid if neon is None else f"{bid}_{neon}"
            for group, suffix in gen_data.overlays(opts).items():
                if group == "side" or f"block/{m}{suffix}" not in out:
                    out[f"block/{m}{suffix}"] = side if group == "side" else end
    return out


ROCK_UNDER = rocks()


def drawn(name):
    """A texture as the game draws it: over its vanilla rock if it has one, and its glow overlay or None."""
    img, glow = load(name)
    if name in ROCK_UNDER:
        rock = vanilla(ROCK_UNDER[name]).copy()
        rock.alpha_composite(img)
        img = rock
    return img, glow


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
    names = list(textures.VENT_FIELD)
    side = 16 * scale
    cell_w, cell_h = 2 * side + 12 + 28, side + 58
    rows = (len(names) + cols - 1) // cols
    out = Image.new("RGBA", (cols * cell_w + 28, rows * cell_h + 60), BG + (255,))
    d = ImageDraw.Draw(out)
    d.text((16, 14), "Vent field textures at 8x: each over its vanilla rock if it has one, and its glow overlay "
           "(full block light) over a dark check", fill=INK, font=f)
    for k, n in enumerate(names):
        img, glow = drawn(n)
        x, y = 16 + (k % cols) * cell_w, 44 + (k // cols) * cell_h
        back = checker(side, side)
        back.alpha_composite(img.resize((side, side), Image.NEAREST))
        out.paste(back, (x, y))
        if glow is not None:
            back = checker(side, side)
            back.alpha_composite(glow.resize((side, side), Image.NEAREST))
            out.paste(back, (x + side + 12, y))
        label = n.split("/", 1)[1] + (" (item)" if n.startswith("item/") else "")
        d.text((x, y + side + 4), label, fill=INK, font=small)
        d.text((x, y + side + 18), "+ " + label + "_glow" if glow is not None else "(no overlay)", fill=DIM_INK, font=small)
        if n in ROCK_UNDER:
            d.text((x, y + side + 32), "over " + ROCK_UNDER[n], fill=DIM_INK, font=small)
    out.convert("RGB").save(path)


def pointed_column(blocks, scale):
    """A column of blocks for the mock, lit: black dripstone above and below, pointed black dripstone between (None is
    air), each block drawn at its size on the cave's background."""
    side = 16 * scale
    col = Image.new("RGBA", (side, side * (len(blocks) + 2)), BG + (255,))
    rock = load("block/black_dripstone")[0].resize((side, side), Image.NEAREST)
    col.paste(rock, (0, 0))
    col.paste(rock, (0, side * (len(blocks) + 1)))
    for k, b in enumerate(blocks):
        if b is not None:
            img = load(f"block/{POINTED}_{b[0]}_{b[1]}")[0].resize((side, side), Image.NEAREST)
            col.alpha_composite(img, (0, side * (k + 1)))
    return col


def mock(path, scale=4):
    f, small = font(14), font(11)
    side = 48 * scale
    cell_w, cell_h = side + 12, side + 26
    rows = [("", PLAIN[:4]), ("", PLAIN[4:])] + [(b + face, [f"{b}_{c}{face}" for c in textures.NEONS]) for b, face in NEON_ROWS]
    tall = side * 2  # the pointed dripstone's row: six blocks of side / 3
    out = Image.new("RGBA", (4 * cell_w + 28, len(rows) * cell_h + 20 + tall + 26 + 60), BG + (255,))
    d = ImageDraw.Draw(out)
    d.text((16, 14), f"Vent field blocks as walls in a dark cave: faces tiled 3x3, rock at {round(DARK * 100)} % brightness, "
           "glow at full", fill=INK, font=f)
    for r, (_, names) in enumerate(rows):
        for c, n in enumerate(names):
            img, glow = drawn("block/" + n)
            x, y = 16 + c * cell_w, 44 + r * cell_h
            out.paste(tiled(dark_face(img, glow)).resize((side, side), Image.NEAREST), (x, y))
            d.text((x, y + side + 4), n, fill=INK, font=small)
    y = 44 + len(rows) * cell_h
    d.text((16, y), f"{POINTED}, lit, between black dripstone blocks:", fill=INK, font=small)
    for c, (label, blocks) in enumerate(POINTED_COLUMNS):
        x = 16 + c * cell_w
        out.paste(pointed_column(blocks, scale), (x, y + 20))
        d.text((x, y + 20 + tall + 4), label, fill=INK, font=small)
    out.convert("RGB").save(path)


def colour_check():
    """Every pixel of every vent field texture and overlay against the rules; returns the problems."""
    problems, files, pixels, lime_files = [], 0, 0, 0
    hues = {"cool": [], "lime": []}
    stale = []
    for tex in textures.VENT_FIELD:
        img, glow = load(tex)
        if ImageChops.difference(img, textures.PAINTERS[tex]()).getbbox() is not None:
            stale.append(tex)
        for name, im in ((tex, img), (tex + "_glow", glow)):
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
        img, _ = load("block/" + n)
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
