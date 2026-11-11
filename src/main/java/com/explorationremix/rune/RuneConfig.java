package com.explorationremix.rune;

import com.explorationremix.rune.engine.Limits;
import net.minecraftforge.common.ForgeConfigSpec;

/**
 * The rune settings: {@code serverconfig/explorationremix-runes.toml} in each world, sent to every client that joins
 * (so the rune screen reads rows with the server's limits). Every limit fails in a way the player can see.
 */
public final class RuneConfig {
    public static final ForgeConfigSpec SPEC;
    public static final RuneConfig INSTANCE;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        INSTANCE = new RuneConfig(b);
        SPEC = b.build();
    }

    public final ForgeConfigSpec.BooleanValue runesHurtPlayers;
    public final ForgeConfigSpec.IntValue maxParts, maxActions, idleTicks;
    public final ForgeConfigSpec.IntValue maxCarriers, releasesPerTick, maxBarrierBlocks, maxGlowBlocks, maxIceBlocks;
    public final ForgeConfigSpec.IntValue combatSeconds, swapTicks, maxMotes;
    public final ForgeConfigSpec.DoubleValue energyPerDamage, energyMaxPerHit, energyFloor, fadeDelaySeconds, fadePerSecond, refillPerSecond;
    public final ForgeConfigSpec.DoubleValue runeDamage;

    private RuneConfig(ForgeConfigSpec.Builder b) {
        b.push("players");
        runesHurtPlayers = b.comment("Whether runes touch other players at all: damage, pushes, chains and homing. Off, other players are",
                "passed through; on, only those the game lets the caster hurt (pvp on, not a teammate without friendly fire,",
                "not in creative).").define("runesHurtPlayers", false);
        b.pop();
        b.push("limits");
        maxParts = b.comment("Parts in one cast (relays deep, plus one). Deeper parts are grey: \"too deep\".").defineInRange("maxParts", 4, 1, 8);
        maxActions = b.comment("Actions in one cast, counting copies. Whole extra copies are cut and not paid for.").defineInRange("maxActions", 16, 1, 64);
        idleTicks = b.comment("Ticks without casting after which gear goes back to its first cast.").defineInRange("idleTicks", 60, 1, 1200);
        maxCarriers = b.comment("Rune bolts and fields one player can have out at once. A cast that would pass it doesn't start and isn't",
                "paid for; payloads that would pass it fizzle.").defineInRange("maxCarriers", 32, 1, 256);
        releasesPerTick = b.comment("Payloads one player's runes can let go in one tick; more wait their turn.").defineInRange("releasesPerTick", 16, 1, 256);
        maxBarrierBlocks = b.comment("Barrier blocks one player can have standing; a new wall shatters the oldest.").defineInRange("maxBarrierBlocks", 12, 0, 256);
        maxGlowBlocks = b.comment("Glow motes one player can have lit; a new one puts out the oldest.").defineInRange("maxGlowBlocks", 16, 0, 256);
        maxMotes = b.comment("Siphon motes one player can have out at once; past it, new ones join the nearest, or one lying out of reach fades.")
                .defineInRange("maxMotes", 64, 1, 512);
        maxIceBlocks = b.comment("Rune ice blocks one player can have on the water; the oldest melts first.").defineInRange("maxIceBlocks", 48, 0, 512);
        b.pop();
        b.push("gear");
        combatSeconds = b.comment("Seconds after you were last hurt before you can rearrange runes.").defineInRange("combatSeconds", 5, 0, 60);
        swapTicks = b.comment("Ticks gear must be in your hand before it casts.").defineInRange("swapTicks", 10, 0, 100);
        runeDamage = b.comment("Multiplies all rune damage.").defineInRange("runeDamage", 1.0, 0.0, 10.0);
        b.pop();
        b.push("energy");
        energyPerDamage = b.comment("Energy knocked loose, per point of damage, when gear with runes hits a creature fighting you (a sword's",
                "swing, a bow's or crossbow's arrow). It flies back as motes into the gear that hit. Rune hits knock loose a",
                "share of what their cast cost instead (much more with Siphon).").defineInRange("energyPerDamage", 2.0, 0.0, 20.0);
        energyMaxPerHit = b.comment("The most energy one sword or arrow hit can knock loose.").defineInRange("energyMaxPerHit", 30.0, 0.0, 200.0);
        energyFloor = b.comment("The share of its most energy gear settles back to once a fight goes quiet, and starts with: enough to",
                "open the next fight with a cast.").defineInRange("energyFloor", 0.3, 0.0, 1.0);
        fadeDelaySeconds = b.comment("Seconds after gear last gained energy or cast before the fight counts as quiet and its energy",
                "starts going back to the floor.").defineInRange("fadeDelaySeconds", 4.0, 0.0, 120.0);
        fadePerSecond = b.comment("How fast energy above the floor fades once the fight is quiet.").defineInRange("fadePerSecond", 7.0, 0.0, 1000.0);
        refillPerSecond = b.comment("How fast energy below the floor comes back once the fight is quiet.").defineInRange("refillPerSecond", 3.0, 0.0, 1000.0);
        b.pop();
    }

    /** How gear's energy goes back to its floor, safe to call before the config is loaded (defaults then). */
    public static com.explorationremix.rune.engine.HostSpec.Fade fade() {
        return new com.explorationremix.rune.engine.HostSpec.Fade((int) Math.round(get(INSTANCE.fadeDelaySeconds) * 20.0),
                (float) (get(INSTANCE.fadePerSecond) / 20.0), (float) (get(INSTANCE.refillPerSecond) / 20.0));
    }

    /** The share of its most energy gear settles back to. */
    public static float floorShare() { return (float) get(INSTANCE.energyFloor); }

    /** The reading limits, safe to call before the config is loaded (defaults then). */
    public static Limits limits() {
        try {
            return new Limits(INSTANCE.maxParts.get(), INSTANCE.maxActions.get(), INSTANCE.idleTicks.get());
        } catch (IllegalStateException notLoaded) {
            return Limits.DEFAULT;
        }
    }

    /** A setting's value, or its default before the config is loaded (a client in the menu). */
    public static int get(ForgeConfigSpec.IntValue v) {
        try {
            return v.get();
        } catch (IllegalStateException notLoaded) {
            return v.getDefault();
        }
    }

    public static double get(ForgeConfigSpec.DoubleValue v) {
        try {
            return v.get();
        } catch (IllegalStateException notLoaded) {
            return v.getDefault();
        }
    }

    public static boolean get(ForgeConfigSpec.BooleanValue v) {
        try {
            return v.get();
        } catch (IllegalStateException notLoaded) {
            return v.getDefault();
        }
    }
}
