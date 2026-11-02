package com.explorationremix.world.eruption;

import com.explorationremix.world.lava.LavaCycle;
import com.explorationremix.world.lava.LavaFlood;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerLevel;

/** The Caldera's eruptions on the server (0.5.1; filled in by the eruption build). */
public final class Eruptions {
    private Eruptions() {}

    /** Every overworld tick, after the flood ({@code LavaFlood.tick}). */
    public static void tick(ServerLevel level, LavaFlood.Band band, LavaCycle.State state, long now) {}

    /** {@code /remix erupt}. */
    public static LiteralArgumentBuilder<CommandSourceStack> command() { return Commands.literal("erupt").executes(c -> 0); }

    /** When the server stops. */
    public static void reset() {}
}
