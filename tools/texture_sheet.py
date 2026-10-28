#!/usr/bin/env python3
"""Draws the review sheet of the new plant and crystal textures, grouped by layer and biome.

Run: python3 tools/texture_sheet.py OUT.png
"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from PIL import Image, ImageDraw, ImageFont
import plants

FONT_DIR = "/usr/share/fonts/truetype/dejavu"

# layer -> biome -> [(texture, label, light)]
SHEET = [
    ("Upper Caves", [
        ("Rootvaults", [("white_glow_flower", "White glow flower", 6)]),
        ("Ivory Root Cavern", [("lantern_fungus", "Lantern fungus", 13), ("lantern_roots", "Lantern roots", 8)]),
        ("Silver Fern Rift", [("silver_fern", "Silver fern", 0)]),
    ]),
    ("Deep Gardens", [
        ("Lantern Lake", [("wisteria_plant", "Wisteria", 0), ("wisteria", "Wisteria, lower tip", 0), ("lantern_pod", "Lantern pods", 14),
                          ("lavender_flower", "Lavender flower", 0), ("gold_flower", "Gold flower", 0)]),
        ("Ember Gardens", [("orange_shelf_fungus", "Shelf fungus, top", 6), ("orange_shelf_fungus_under", "Shelf fungus, under", 6),
                           ("lavender_coral", "Lavender coral", 0), ("pale_coral_fan", "Pale coral fan", 0),
                           ("ember_vines_plant", "Ember vines", 10), ("ember_vines", "Ember vines, tip", 10)]),
    ]),
    ("Blue Reaches", [
        ("Deep Blue Rift", [("blue_glow_mushroom", "Blue glow mushroom", 10), ("blue_glow_vines_plant", "Blue glow vines", 8),
                            ("blue_glow_vines", "Blue glow vines, tip", 8)]),
        ("Asurine Geodes and Deep Blue Rift", [("asurine_crystal", "Asurine crystal", 10)]),
    ]),
]


def main(out_path):
    scale, pad, tile = 8, 24, 16 * 8
    f_layer = ImageFont.truetype(os.path.join(FONT_DIR, "DejaVuSans-Bold.ttf"), 26)
    f_biome = ImageFont.truetype(os.path.join(FONT_DIR, "DejaVuSans-Bold.ttf"), 18)
    f_name = ImageFont.truetype(os.path.join(FONT_DIR, "DejaVuSans.ttf"), 15)
    f_light = ImageFont.truetype(os.path.join(FONT_DIR, "DejaVuSans.ttf"), 13)
    cols = 6
    col_w = tile + 44
    width = pad * 2 + cols * col_w
    # measure
    h = pad + 50
    for layer, biomes in SHEET:
        h += 44
        for biome, items in biomes:
            rows = (len(items) + cols - 1) // cols
            h += 30 + rows * (tile + 56)
    img = Image.new("RGB", (width, h + pad), (29, 29, 33))
    d = ImageDraw.Draw(img)
    d.text((pad, pad), "Exploration Remix: new plants and crystals (16 by 16, shown 8x)", font=f_layer, fill=(240, 240, 240))
    y = pad + 50
    for layer, biomes in SHEET:
        d.text((pad, y), layer, font=f_layer, fill=(232, 197, 71))
        y += 44
        for biome, items in biomes:
            d.text((pad, y), biome, font=f_biome, fill=(200, 200, 210))
            y += 30
            for i, (tex, label, light) in enumerate(items):
                x = pad + (i % cols) * col_w
                yy = y + (i // cols) * (tile + 56)
                d.rectangle([x - 2, yy - 2, x + tile + 1, yy + tile + 1], fill=(44, 44, 50))
                t = plants.PAINTERS["block/" + tex]().resize((tile, tile), Image.NEAREST)
                img.paste(t, (x, yy), t)
                d.text((x, yy + tile + 6), label, font=f_name, fill=(235, 235, 235))
                d.text((x, yy + tile + 26), f"glows {light}" if light else "no glow", font=f_light,
                       fill=(247, 196, 98) if light else (150, 150, 160))
            y += ((len(items) + cols - 1) // cols) * (tile + 56)
    img.save(out_path)
    print(out_path, img.size)


if __name__ == "__main__":
    main(sys.argv[1])
