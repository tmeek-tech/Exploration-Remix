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
    public final ForgeConfigSpec.IntValue combatSeconds, swapTicks, tapTicks, maxMotes;
    public final ForgeConfigSpec.DoubleValue swordChargePerDamage, swordChargeMaxPerHit;
    public final ForgeConfigSpec.DoubleValue runeDamage;

    private RuneConfig(ForgeConfigSpec.Builder b) {
        b.push("players");
        runesHurtPlayers = b.comment("Whether runes touch other players at all: damage, pushes, chains and homing. Off, other players are",
                "passed through.").define("runesHurtPlayers", false);
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
        maxMotes = b.comment("Siphon motes one player can have on their way at once; past it, new ones join the nearest.")
                .defineInRange("maxMotes", 64, 1, 512);
        maxIceBlocks = b.comment("Rune ice blocks one player can have on the water; the oldest melts first.").defineInRange("maxIceBlocks", 48, 0, 512);
        b.pop();
        b.push("gear");
        combatSeconds = b.comment("Seconds after you were last hurt before you can rearrange runes.").defineInRange("combatSeconds", 5, 0, 60);
        swapTicks = b.comment("Ticks gear must be in your hand before it casts.").defineInRange("swapTicks", 10, 0, 100);
        tapTicks = b.comment("With a shield in the off hand, a right-click this short (in ticks) casts the sword; held longer, it",
                "only raises the shield.").defineInRange("tapTicks", 4, 1, 20);
        swordChargePerDamage = b.comment("Energy a sword gains per point of damage from a full-strength hit on a creature fighting you.")
                .defineInRange("swordChargePerDamage", 2.0, 0.0, 20.0);
        swordChargeMaxPerHit = b.comment("The most energy one sword hit can give.").defineInRange("swordChargeMaxPerHit", 30.0, 0.0, 200.0);
        runeDamage = b.comment("Multiplies all rune damage.").defineInRange("runeDamage", 1.0, 0.0, 10.0);
        b.pop();
    }

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
