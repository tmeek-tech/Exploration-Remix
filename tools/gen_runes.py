"""Rune definitions, lang strings, and item assets for Exploration Remix. Imported by gen_data.py."""
import os, json
from PIL import Image, ImageDraw

MOD = "explorationremix"

# id, kind, cost (ticks of cooldown it adds), tier (1..5), weight, attunements, params, color, name, description
RUNES = [
    # triggers
    ("on_hit", "trigger", 20, 1, 14, ["combat"], {}, "gold", "On hit", "Fires when you hit a creature with this tool."),
    ("on_combo_finisher", "trigger", 10, 1, 8, ["combat"], {}, "gold", "On finisher", "Fires on the last swing of a Better Combat combo, or every third hit without it."),
    ("on_kill", "trigger", 10, 1, 8, ["combat"], {}, "gold", "On kill", "Fires when you kill a creature with this tool."),
    ("when_hurt", "trigger", 30, 1, 10, ["combat", "utility"], {}, "gold", "When hurt", "Fires when something hurts you while you hold this tool."),
    ("on_block", "trigger", 20, 1, 8, ["combat", "utility"], {}, "gold", "On block", "Fires when you block a hit with a shield."),
    ("on_momentum_hit", "trigger", 15, 2, 7, ["movement", "combat"], {}, "gold", "On momentum hit", "Fires when you land a hit out of a sprint, a Dash, a Leap, or a fall."),
    ("on_stagger", "trigger", 10, 2, 7, ["combat"], {}, "gold", "On stagger", "Fires when your hit breaks a creature's poise."),
    ("on_jump", "trigger", 30, 1, 12, ["movement"], {}, "gold", "On jump", "Fires when you jump."),
    ("while_falling", "trigger", 40, 1, 8, ["movement"], {}, "gold", "While falling", "Fires again and again while you fall more than three blocks."),
    ("while_sneaking", "trigger", 60, 1, 8, ["utility", "fun"], {}, "gold", "While sneaking", "Fires again and again while you sneak."),
    # modifiers
    ("twice", "modifier", 40, 2, 8, ["combat", "fun"], {"repeats": 2}, "aqua", "Twice", "The next effect happens twice. Stacks."),
    ("wide", "modifier", 30, 1, 10, ["element"], {"scale": 1.8}, "aqua", "Wide", "The next effect covers a much bigger area. Stacks."),
    ("chain", "modifier", 40, 2, 8, ["element"], {"jumps": 3, "range": 6}, "aqua", "Chain", "The next effect jumps to creatures near its target. Stacks."),
    ("delayed", "modifier", 10, 1, 8, ["fun", "utility"], {"ticks": 15}, "aqua", "Delayed", "The next effect fires a moment later. Stacks."),
    ("quiet", "modifier", 20, 2, 7, ["utility"], {}, "aqua", "Quiet", "The next effect makes no noise that hunters hear."),
    ("heavy", "modifier", 50, 2, 8, ["combat"], {"force": 1.75, "poise": 12}, "aqua", "Heavy", "The next effect hits with far more force and breaks poise. Stacks."),
    # effects
    ("spark", "effect", 30, 1, 12, ["element"], {"damage": 6, "range": 5, "jumps": 2, "wet_range": 10}, "light_purple", "Spark", "Lightning strikes the target and arcs to creatures nearby. Wet creatures and water carry it much further."),
    ("frost", "effect", 30, 1, 10, ["element"], {"duration": 80, "radius": 3, "slow": 2}, "light_purple", "Frost", "Chills and slows creatures around the target. A wet creature freezes solid, and a heavy hit shatters it."),
    ("ember", "effect", 30, 1, 10, ["element"], {"seconds": 5, "radius": 2.5}, "light_purple", "Ember", "Sets the target burning. Fire spreads between packed creatures and never burns blocks."),
    ("shove", "effect", 25, 1, 10, ["combat"], {"force": 1.4, "radius": 3.5, "poise": 8}, "light_purple", "Shove", "A burst that throws creatures away from you. Anything slammed into a wall takes impact damage."),
    ("ward", "effect", 50, 1, 9, ["utility"], {"absorption": 1, "duration": 100}, "light_purple", "Ward", "A brief shield of extra hearts and resistance."),
    ("mend", "effect", 60, 1, 9, ["utility"], {"heal": 4, "regen": 60}, "light_purple", "Mend", "Heals you at once and a little more over a few seconds."),
    ("smoke", "effect", 50, 2, 8, ["utility"], {"radius": 4, "duration": 200}, "light_purple", "Smoke", "A cloud hunters cannot see through."),
    ("lure", "effect", 40, 2, 8, ["utility", "fun"], {"range": 32, "duration": 160}, "light_purple", "Lure", "A noise decoy that hunters go to investigate."),
    ("leap", "effect", 25, 1, 12, ["movement"], {"power": 0.9}, "light_purple", "Leap", "Throws you upward. Your next landing is soft."),
    ("dash", "effect", 25, 1, 12, ["movement"], {"power": 1.5}, "light_purple", "Dash", "Throws you forward the way you look. Your next landing is soft."),
    ("glide", "effect", 30, 1, 9, ["movement"], {"duration": 80}, "light_purple", "Glide", "You drift down slowly for a while."),
    ("confetti", "effect", 10, 1, 6, ["fun"], {"count": 60}, "light_purple", "Confetti", "A shower of colour and a cheerful noise. That is all it does."),
    ("blast", "effect", 80, 3, 6, ["element", "utility"], {"power": 3.2, "debris": 6}, "light_purple", "Blast", "An explosion that breaks layer stone. Debris falls from the ceiling and hurts whatever it lands on. Band stone never breaks."),
    # passives
    ("keen_edge", "passive", 0, 1, 10, ["combat"], {"damage": 0.5}, "green", "Keen Edge", "This tool deals half again as much damage. Stacks."),
    ("thick_hide", "passive", 0, 1, 10, ["utility"], {"reduction": 0.3}, "green", "Thick Hide", "While you hold this tool you take less damage. Stacks."),
    ("cats_feet", "passive", 0, 1, 10, ["movement"], {}, "green", "Cat's Feet", "While you hold this tool, falls never hurt you."),
]

ATTUNEMENTS = {"movement": "Movement", "combat": "Combat", "element": "Element", "utility": "Utility", "fun": "Fun"}

def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f: json.dump(obj, f, indent=2)

def runes(res):
    for rid, kind, cost, tier, weight, att, params, color, name, desc in RUNES:
        write(os.path.join(res, "data", MOD, "runes", rid + ".json"),
              {"kind": kind, "cost": cost, "tier": tier, "weight": weight, "attunements": att, "params": params, "color": color})

def lang(d):
    for rid, kind, cost, tier, weight, att, params, color, name, desc in RUNES:
        d[f"rune.{MOD}.{rid}"] = name
        d[f"rune.{MOD}.{rid}.desc"] = desc
    for k, n in ATTUNEMENTS.items(): d[f"attunement.{MOD}.{k}"] = n
    d.update({
        f"rune.{MOD}.kind.trigger": "Trigger: starts a sentence",
        f"rune.{MOD}.kind.modifier": "Modifier: changes the next effect",
        f"rune.{MOD}.kind.effect": "Effect: ends a clause",
        f"rune.{MOD}.kind.passive": "Passive: always on while held",
        f"rune.{MOD}.cost": "Adds %ss to the sentence's cooldown",
        f"rune.{MOD}.block.no_run": "The block sleeps. It only wakes during a run.",
        f"rune.{MOD}.block.not_enough": "Waking this block costs %s XP",
        f"rune.{MOD}.block.full": "No free slot on this tool. Remove a rune first.",
        f"rune.{MOD}.block.hold_tool": "Hold the tool you want to work on",
        f"rune.{MOD}.attuned": "Attuned to %s",
        f"rune.{MOD}.screen.wake": "Wake (%s XP)",
        f"rune.{MOD}.screen.tier": "Tier %s",
        f"rune.{MOD}.screen.xp": "%s XP",
        f"rune.{MOD}.screen.woken": "Already woken this run. Rearrange freely.",
        f"rune.{MOD}.screen.no_run": "Dark. Runes only live during a run.",
        f"rune.{MOD}.screen.choose": "Choose one rune:",
        f"rune.{MOD}.tooltip.dead": "Runes from an old run (dead)",
        f"rune.{MOD}.tooltip.vanilla_dead": "Vanilla enchantments do nothing in this world.",
        f"rune.{MOD}.tooltip.empty": "No runes yet (%s slots per sentence)",
        f"screen.{MOD}.runes": "Runes",
        f"screen.{MOD}.runes.no_run": "Not on a run. Runes wake when you pass the first band.",
        f"screen.{MOD}.runes.run": "On a run, deepest so far: %s",
        f"screen.{MOD}.runes.hint": "Read-only. Runes are rearranged at rune blocks.",
        f"screen.{MOD}.runes.empty": "No tools or weapons in your inventory can hold runes.",
        f"hud.{MOD}.gate": "A gate",
        f"hud.{MOD}.xp": "  %s XP",
        f"rune.{MOD}.tooltip.sentence": "%s → %s",
        f"rune.{MOD}.tooltip.cooldown": "cooldown %ss",
        f"rune.{MOD}.tooltip.passives": "Always: %s",
        f"rune.{MOD}.tooltip.slots": "%s of %s rune slots",
        f"rune.{MOD}.tooltip.attuned": "Attuned: %s",
        f"container.{MOD}.rune_block": "Rune block",
        f"item.{MOD}.attunement_book": "Attunement book",
        f"item.{MOD}.attunement_book.desc": "Use it on a tool at a rune block. %s runes become more likely in that tool's offers, for good.",
        f"item.{MOD}.attunement_book.crumbles": "Crumbles when the run ends",
        f"run.{MOD}.started": "The run begins. Runes and XP last until it ends.",
        f"run.{MOD}.entered": "You enter the %s",
        f"run.{MOD}.gate": "Gate %s passed",
        f"run.{MOD}.going_home": "You touch the bedrock shrine. In a moment, home.",
        f"run.{MOD}.ended": "The run ends.",
        f"run.{MOD}.no_portal": "Portals cannot be lit this deep.",
        f"run.{MOD}.no_bed": "Only a bed under the sky sets your spawn.",
        f"run.{MOD}.no_elytra": "Elytra do not open below the first band.",
        f"run.{MOD}.no_enchant": "Enchanting tables do nothing here. Find a rune block.",
        f"run.{MOD}.no_teleport": "Nothing teleports through unbreakable stone.",
        f"screen.{MOD}.run_summary": "Run summary",
        f"screen.{MOD}.run_summary.end.death": "You died.",
        f"screen.{MOD}.run_summary.end.bedrock": "You reached bedrock.",
        f"screen.{MOD}.run_summary.end.climbed_out": "You climbed back out.",
        f"screen.{MOD}.run_summary.end.dimension": "You left the world.",
        f"screen.{MOD}.run_summary.end.logout": "The run ended when you left.",
        f"screen.{MOD}.run_summary.end.command": "The run was ended by command.",
        f"screen.{MOD}.run_summary.time": "Time: %s min %s s",
        f"screen.{MOD}.run_summary.deepest": "Deepest: %s",
        f"screen.{MOD}.run_summary.gates": "Gates passed: %s",
        f"screen.{MOD}.run_summary.kills": "Kills: %s",
        f"screen.{MOD}.run_summary.xp": "XP earned %s, spent %s",
        f"screen.{MOD}.run_summary.woken": "Rune blocks woken: %s",
        f"screen.{MOD}.run_summary.damage": "Damage taken: %s",
        f"screen.{MOD}.run_summary.runes": "Runes taken:",
        f"key.categories.{MOD}": "Exploration Remix",
        f"key.{MOD}.runes": "Show tool runes",
    })

def item_assets(assets):
    T = os.path.join(assets, "textures/item"); os.makedirs(T, exist_ok=True)
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0)); d = ImageDraw.Draw(img)
    d.rectangle([3, 2, 13, 14], fill=(88, 48, 120, 255), outline=(40, 20, 60, 255))
    d.rectangle([3, 2, 5, 14], fill=(60, 30, 90, 255))
    d.line([(8, 5), (8, 11)], fill=(230, 200, 255, 255)); d.line([(6, 8), (10, 8)], fill=(230, 200, 255, 255))
    d.point((11, 4), (255, 240, 200, 255)); d.point((11, 12), (255, 240, 200, 255))
    img.save(f"{T}/attunement_book.png")
    write(os.path.join(assets, "models/item/attunement_book.json"), {"parent": "minecraft:item/generated", "textures": {"layer0": f"{MOD}:item/attunement_book"}})
