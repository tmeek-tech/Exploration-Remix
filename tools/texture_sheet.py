#!/usr/bin/env python3
"""Draws the review sheets of the plant and crystal textures, grouped by layer and biome.

Each texture is shown as painted, and a glowing one also as it reads in a dark cave: the texture at a quarter of its
brightness with its overlay (<texture>_glow.png) at full. A second sheet stacks the vines (three body tiles over the
tip, in the order of their variants) and puts the tall plants' tops on their bottoms, to check that the tiles join.

Run: python3 tools/texture_sheet.py OUT.png [biome or layer]   (also writes OUT_stacks.png)
"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from PIL import Image, ImageDraw, ImageFont
import textures

FONT_DIR = "/usr/share/fonts/truetype/dejavu"

# layer -> biome -> [(texture, label, light)]
SHEET = [
    ("Upper Caves", [
        ("Rootvaults", [("white_glow_flower", "White glow flower", 6),
                        ("amber_bulb_vine_plant", "Amber bulb vine", 6), ("amber_bulb_vine_plant_2", "Amber bulb vine 2", 6),
                        ("amber_bulb_vine_plant_3", "Amber bulb vine 3", 6), ("amber_bulb_vine", "Amber bulb vine, tip", 12),
                        ("amber_bulb_vine_2", "Amber bulb vine, tip 2", 12),
                        ("weeping_ivy_plant", "Weeping ivy", 0), ("weeping_ivy_plant_2", "Weeping ivy 2", 0),
                        ("weeping_ivy_plant_3", "Weeping ivy 3", 0), ("weeping_ivy", "Weeping ivy, tip", 0),
                        ("weeping_ivy_2", "Weeping ivy, tip 2", 0),
                        ("grotto_ivy", "Grotto ivy", 0), ("grotto_ivy_2", "Grotto ivy 2", 0),
                        ("starmoss", "Starmoss", 4), ("starmoss_2", "Starmoss 2", 4)]),
        ("Ivory Root Cavern", [("lantern_fungus", "Lantern fungus", 13), ("lantern_fungus_2", "Lantern fungus 2", 13),
                               ("lantern_roots", "Lantern roots", 8),
                               ("ivory_bracket", "Ivory bracket, top", 11), ("ivory_bracket_2", "Ivory bracket, top 2", 11),
                               ("ivory_bracket_under", "Ivory bracket, under", 11),
                               ("gold_threads_plant", "Gold threads", 5), ("gold_threads_plant_2", "Gold threads 2", 5),
                               ("gold_threads", "Gold threads, tip", 9), ("gold_threads_2", "Gold threads, tip 2", 9),
                               ("sage_bush", "Sage bush", 0), ("sage_bush_2", "Sage bush 2", 0)]),
        ("Silver Fern Rift", [("silver_fern", "Silver fern", 0), ("silver_fern_2", "Silver fern 2", 0), ("silver_fern_3", "Silver fern 3", 0),
                              ("silver_frond_plant", "Silver frond", 0), ("silver_frond_plant_2", "Silver frond 2", 0),
                              ("silver_frond", "Silver frond, tip", 0), ("silver_frond_2", "Silver frond, tip 2", 0),
                              ("tall_silver_fern_top", "Tall silver fern, top", 0), ("tall_silver_fern_bottom", "Tall silver fern, bottom", 0),
                              ("tall_silver_fern_top_2", "Tall silver fern, top 2", 0),
                              ("tall_silver_fern_bottom_2", "Tall silver fern, bottom 2", 0),
                              ("silver_wall_fern", "Silver wall fern", 0), ("silver_wall_fern_2", "Silver wall fern 2", 0)]),
    ]),
    ("Deep Gardens", [
        ("Lantern Lake", [("wisteria_plant", "Wisteria", 0), ("wisteria_plant_2", "Wisteria 2", 0), ("wisteria_plant_3", "Wisteria 3", 0),
                          ("wisteria", "Wisteria, lower tip", 0),
                          ("pale_wisteria_plant", "Pale wisteria", 0), ("pale_wisteria_plant_2", "Pale wisteria 2", 0),
                          ("pale_wisteria", "Pale wisteria, tip", 0), ("lantern_pod", "Lantern pods", 14),
                          ("wisteria_petals", "Wisteria petals", 0), ("wisteria_petals_2", "Wisteria petals 2", 0),
                          ("wisteria_petals_3", "Wisteria petals 3", 0),
                          ("lavender_flower", "Lavender flower", 0), ("gold_flower", "Gold flower", 9),
                          ("pale_coral", "Pale coral", 0), ("pale_coral_2", "Pale coral 2", 0),
                          ("lilac_waterweed", "Lilac waterweed", 0), ("lilac_waterweed_2", "Lilac waterweed 2", 0),
                          ("lilac_waterweed_3", "Lilac waterweed 3", 0),
                          ("lantern_weed", "Lantern weed", 8), ("lantern_weed_2", "Lantern weed 2", 8),
                          ("pool_pearls", "Pool pearls", 4), ("pool_pearls_2", "Pool pearls 2", 4)]),
        ("Ember Gardens", [("orange_shelf_fungus", "Shelf fungus, top", 6), ("orange_shelf_fungus_2", "Shelf fungus, top 2", 6),
                           ("orange_shelf_fungus_under", "Shelf fungus, under", 6),
                           ("lavender_coral", "Lavender coral", 0), ("lavender_coral_2", "Lavender coral 2", 0),
                           ("pale_coral_fan", "Pale coral fan", 0),
                           ("ember_anemone", "Ember anemone", 11), ("ember_anemone_2", "Ember anemone 2", 11),
                           ("pool_pearls", "Pool pearls", 4), ("pool_pearls_2", "Pool pearls 2", 4),
                           ("ember_vines_plant", "Ember vines", 10), ("ember_vines_plant_2", "Ember vines 2", 10),
                           ("ember_vines", "Ember vines, tip", 10),
                           ("rose_tendrils_plant", "Rose tendrils", 0), ("rose_tendrils_plant_2", "Rose tendrils 2", 0),
                           ("rose_tendrils", "Rose tendrils, tip", 0),
                           ("ochre_grass", "Ochre grass", 0), ("ochre_grass_2", "Ochre grass 2", 0), ("ochre_grass_3", "Ochre grass 3", 0),
                           ("lilac_plume_top", "Lilac plume, top", 0), ("lilac_plume_bottom", "Lilac plume, bottom", 0),
                           ("lilac_plume_top_2", "Lilac plume, top 2", 0), ("lilac_plume_bottom_2", "Lilac plume, bottom 2", 0),
                           ("amber_shard", "Amber shard", 7), ("amber_shard_2", "Amber shard 2", 7)]),
    ]),
    ("Basalt Galleries", [
        ("Basalt Galleries", [("cinder_bloom", "Cinder bloom", 5), ("cinder_bloom_2", "Cinder bloom 2", 5),
                              ("ash_fern", "Ash fern", 0), ("ash_fern_2", "Ash fern 2", 0),
                              ("smoulder_lichen", "Smoulder lichen", 3), ("smoulder_lichen_2", "Smoulder lichen 2", 3),
                              ("amber_shard", "Amber shard", 7), ("amber_shard_2", "Amber shard 2", 7)]),
    ]),
    ("Blue Reaches", [
        ("Deep Blue Rift", [("blue_glow_mushroom", "Blue glow mushroom", 10), ("blue_glow_mushroom_2", "Blue glow mushroom 2", 10),
                            ("blue_glow_mushroom_3", "Blue glow mushroom 3", 10),
                            ("blue_glow_vines_plant", "Blue glow vines", 6), ("blue_glow_vines_plant_2", "Blue glow vines 2", 6),
                            ("blue_glow_vines", "Blue glow vines, tip", 10),
                            ("glowbell", "Glowbell", 9), ("glowbell_2", "Glowbell 2", 9),
                            ("pagoda_fungus_top", "Pagoda fungus, top", 7), ("pagoda_fungus_bottom", "Pagoda fungus, bottom", 7),
                            ("pagoda_fungus_top_2", "Pagoda fungus 2, top", 7), ("pagoda_fungus_bottom_2", "Pagoda fungus 2, bottom", 7),
                            ("blue_lichen", "Blue lichen", 4), ("blue_lichen_2", "Blue lichen 2", 4), ("blue_lichen_3", "Blue lichen 3", 4)]),
        ("Asurine Geodes and Deep Blue Rift", [("asurine_crystal", "Asurine crystal", 10), ("asurine_crystal_2", "Asurine crystal 2", 10),
                                               ("asurine_bud", "Asurine bud", 5), ("asurine_bud_2", "Asurine bud 2", 5),
                                               ("asurine_crust", "Asurine crust", 3), ("asurine_crust_2", "Asurine crust 2", 3)]),
        ("Deep dark", [("dusk_vines_plant", "Dusk vines", 0), ("dusk_vines_plant_2", "Dusk vines 2", 0), ("dusk_vines", "Dusk vines, tip", 0)]),
    ]),
]

# Vines: (tip, number of body variants). Tall plants: (id, number of variants).
VINES = [("amber_bulb_vine", 3), ("weeping_ivy", 3), ("silver_frond", 2), ("gold_threads", 2), ("dusk_vines", 2), ("wisteria", 3),
         ("pale_wisteria", 2), ("blue_glow_vines", 2), ("ember_vines", 2), ("rose_tendrils", 2)]
TALL = [("tall_silver_fern", 2), ("pagoda_fungus", 2), ("lilac_plume", 2)]


def paint(name):
    return textures.PAINTERS["block/" + name]()


def dark_view(name):
    """The texture as it reads in a dark cave: a quarter as bright, with its glowing pixels at full brightness."""
    img = paint(name)
    px = img.load()
    for y in range(img.size[1]):
        for x in range(img.size[0]):
            r, g, b, a = px[x, y]
            if a:
                px[x, y] = (r // 4, g // 4, b // 4, a)
    img.alpha_composite(textures.glow_layer(paint(name), textures.GLOW["block/" + name]))
    return img


def variant(base, k):
    return base if k == 1 else f"{base}_{k}"


def main(out_path, only=None):
    scale, pad, tile = 8, 24, 16 * 8
    f_layer = ImageFont.truetype(os.path.join(FONT_DIR, "DejaVuSans-Bold.ttf"), 26)
    f_biome = ImageFont.truetype(os.path.join(FONT_DIR, "DejaVuSans-Bold.ttf"), 18)
    f_name = ImageFont.truetype(os.path.join(FONT_DIR, "DejaVuSans.ttf"), 15)
    f_light = ImageFont.truetype(os.path.join(FONT_DIR, "DejaVuSans.ttf"), 13)
    sheet = [(layer, [(b, items) for b, items in biomes if not only or only.lower() in (layer + " " + b).lower()])
             for layer, biomes in SHEET]
    sheet = [(layer, biomes) for layer, biomes in sheet if biomes]
    cols = 4
    col_w = 2 * tile + 40
    width = pad * 2 + cols * col_w
    h = pad + 50
    for layer, biomes in sheet:
        h += 44
        for biome, items in biomes:
            h += 30 + ((len(items) + cols - 1) // cols) * (tile + 56)
    img = Image.new("RGB", (width, h + pad), (29, 29, 33))
    d = ImageDraw.Draw(img)
    d.text((pad, pad), "Exploration Remix: plants and crystals (16 by 16, shown 8x; glowing ones also in the dark)", font=f_layer,
           fill=(240, 240, 240))
    y = pad + 50
    for layer, biomes in sheet:
        d.text((pad, y), layer, font=f_layer, fill=(232, 197, 71))
        y += 44
        for biome, items in biomes:
            d.text((pad, y), biome, font=f_biome, fill=(200, 200, 210))
            y += 30
            for i, (tex, label, light) in enumerate(items):
                x = pad + (i % cols) * col_w
                yy = y + (i // cols) * (tile + 56)
                d.rectangle([x - 2, yy - 2, x + tile + 1, yy + tile + 1], fill=(52, 52, 58))
                t = paint(tex).resize((tile, tile), Image.NEAREST)
                img.paste(t, (x, yy), t)
                if "block/" + tex in textures.GLOW:
                    xd = x + tile + 8
                    d.rectangle([xd - 2, yy - 2, xd + tile + 1, yy + tile + 1], fill=(8, 8, 10))
                    t = dark_view(tex).resize((tile, tile), Image.NEAREST)
                    img.paste(t, (xd, yy), t)
                d.text((x, yy + tile + 6), label, font=f_name, fill=(235, 235, 235))
                d.text((x, yy + tile + 26), f"light {light}" if light else "no light", font=f_light,
                       fill=(247, 196, 98) if light else (150, 150, 160))
            y += ((len(items) + cols - 1) // cols) * (tile + 56)
    img.save(out_path)
    print(out_path, img.size)
    stacks(os.path.splitext(out_path)[0] + "_stacks.png")


def stacks(path):
    """Vines: three body tiles (variants 1, 2, 3, or 1, 2, 1 when there are two) over the tip. Tall plants: top on bottom."""
    s = 4
    t = 16 * s
    talls = [(tid, k) for tid, n in TALL for k in range(1, n + 1)]
    cols = len(VINES) + len(talls)
    out = Image.new("RGBA", (12 + cols * (t + 12), 4 * t + 40), (58, 58, 64, 255))
    d = ImageDraw.Draw(out)
    for c, (vid, n) in enumerate(VINES):
        x = 12 + c * (t + 12)
        for k, v in enumerate([1, 2, 3] if n >= 3 else [1, 2, 1] if n == 2 else [1, 1, 1]):
            out.alpha_composite(paint(variant(vid + "_plant", v)).resize((t, t), Image.NEAREST), (x, 8 + k * t))
        out.alpha_composite(paint(vid).resize((t, t), Image.NEAREST), (x, 8 + 3 * t))
        d.text((x, 4 * t + 14), vid[:11], fill=(235, 235, 235, 255))
    for c, (tid, k) in enumerate(talls, start=len(VINES)):
        x = 12 + c * (t + 12)
        out.alpha_composite(paint(variant(tid + "_top", k)).resize((t, t), Image.NEAREST), (x, 8 + 2 * t))
        out.alpha_composite(paint(variant(tid + "_bottom", k)).resize((t, t), Image.NEAREST), (x, 8 + 3 * t))
        d.text((x, 4 * t + 14), variant(tid, k)[:11], fill=(235, 235, 235, 255))
    out.save(path)
    print(path, out.size)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None)
