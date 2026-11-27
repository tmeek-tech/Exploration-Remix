package com.explorationremix.combat.foes;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * Which of vanilla's hostile mobs fight the new way (the card of 27 Nov, built at its recommended answer while it is
 * open: the overworld's that you meet, the illagers too), by their exact type, so a mod's own mob built on a vanilla
 * one is left as its mod made it. The Nether's and the End's are untouched, magma cubes among them.
 */
public enum FoeKind {
    /** Zombies, husks, zombie villagers and drowned: the lunging slam. */
    ZOMBIE(true),
    /** Skeletons and strays: they aim ahead, back off, kick, and every shot has a tell. */
    SKELETON(true),
    /** Spiders: the crouch and pounce, the bite up close, the drop from above. */
    SPIDER(true),
    /** Cave spiders: the same, quicker. */
    CAVE_SPIDER(true),
    /** Creepers: a crouch and shudder as they swell, and a rush once lit. */
    CREEPER(false),
    /** Endermen: they blink behind their quarry, then strike. */
    ENDERMAN(true),
    /** Witches: they keep their distance, and each throw has a wind-up; a parry sends the bottle back. */
    WITCH(false),
    /** Slimes: a squash before each slam; only the slam hurts. */
    SLIME(true),
    /** Pillagers: a tell before each bolt. */
    PILLAGER(false),
    /** Vindicators: the axe raised overhead before the chop. They already hit hard, so their blows stay as they were. */
    VINDICATOR(false);

    /** Whether its blows are made harder ({@link FoeRules#DAMAGE}). */
    public final boolean harder;
    /** Its name in the settings. */
    public final String key;

    FoeKind(boolean harder) {
        this.harder = harder;
        this.key = name().toLowerCase(Locale.ROOT);
    }

    /** The kind {@code e} is, or null when it fights as vanilla made it. */
    @Nullable
    public static FoeKind of(Entity e) {
        EntityType<?> t = e.getType();
        if (t == EntityType.ZOMBIE || t == EntityType.HUSK || t == EntityType.ZOMBIE_VILLAGER || t == EntityType.DROWNED) return ZOMBIE;
        if (t == EntityType.SKELETON || t == EntityType.STRAY) return SKELETON;
        if (t == EntityType.SPIDER) return SPIDER;
        if (t == EntityType.CAVE_SPIDER) return CAVE_SPIDER;
        if (t == EntityType.CREEPER) return CREEPER;
        if (t == EntityType.ENDERMAN) return ENDERMAN;
        if (t == EntityType.WITCH) return WITCH;
        if (t == EntityType.SLIME) return SLIME;
        if (t == EntityType.PILLAGER) return PILLAGER;
        if (t == EntityType.VINDICATOR) return VINDICATOR;
        return null;
    }

    @Nullable
    public static FoeKind byKey(String key) {
        for (FoeKind k : values()) if (k.key.equals(key)) return k;
        return null;
    }
}
